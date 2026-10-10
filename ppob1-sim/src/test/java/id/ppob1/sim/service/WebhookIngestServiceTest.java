package id.ppob1.sim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob1.sim.client.Ppob2RequestSigner;
import id.ppob1.sim.domain.InboundWebhook;
import id.ppob1.sim.domain.LocalState;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.domain.StateSource;
import id.ppob1.sim.domain.WebhookVerdict;
import id.ppob1.sim.repository.InboundWebhookRepository;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The Section 23.8 receiver's verdict matrix. Each case pins one of the decisions documented on
 * {@link WebhookVerdict} — above all that an invalid signature applies <em>nothing</em>, and that a
 * rejected callback is still recorded.
 */
class WebhookIngestServiceTest {

    private static final String SECRET = "ppob1-webhook-secret";
    private static final String PATH = "/webhooks/ppob2";
    private static final String TIMESTAMP = "1700000000000";

    private final Ppob1TransactionRepository transactionRepository = mock(Ppob1TransactionRepository.class);
    private final InboundWebhookRepository webhookRepository = mock(InboundWebhookRepository.class);
    private final Ppob2RequestSigner signer = new Ppob2RequestSigner();

    private final WebhookIngestService service = new WebhookIngestService(
            transactionRepository, webhookRepository, signer, new ObjectMapper(), SECRET, PATH);

    private static String body(String orderId, String state, String nonce) {
        return "{\"event_type\":\"ORDER_STATUS_CHANGED\",\"order_id\":\"" + orderId + "\",\"state\":\"" + state
                + "\",\"timestamp\":\"2026-10-10T00:00:00Z\",\"nonce\":\"" + nonce + "\"}";
    }

    /** The same body plus the sixth field PPOB2 echoes back from {@code metadata.reseller_txn_id}
     * (its {@code OutboundWebhookSender.payload}), which is absent entirely when the order carries
     * no metadata — hence a separate helper rather than a nullable parameter on {@link #body}. */
    private static String bodyWithResellerId(String orderId, String state, String nonce, String resellerTxnId) {
        return "{\"event_type\":\"ORDER_STATUS_CHANGED\",\"order_id\":\"" + orderId + "\",\"state\":\"" + state
                + "\",\"timestamp\":\"2026-10-10T00:00:00Z\",\"nonce\":\"" + nonce
                + "\",\"reseller_txn_id\":\"" + resellerTxnId + "\"}";
    }

    /** What a create call that never answered leaves behind: a committed sale with no upstream
     * order id, which is both {@code ReconVerdict.ORPHANED_SUBMIT} and the row this recovery is
     * for. */
    private static Ppob1Transaction orphanedSubmit() {
        return new Ppob1Transaction("PPOB1-TX-1", "MOBILE_LEGENDS", BigInteger.valueOf(20000), "GAMEID-1");
    }

    private String signatureFor(String rawBody, String nonce) {
        return signer.sign(SECRET, signer.stringToSign("POST", PATH, TIMESTAMP, nonce, signer.sha256Hex(rawBody)));
    }

    private static Ppob1Transaction awaitingPayment(String orderId) {
        Ppob1Transaction transaction = new Ppob1Transaction(
                "PPOB1-TX-1", "MOBILE_LEGENDS", BigInteger.valueOf(20000), "GAMEID-1");
        transaction.acceptedByPpob2(orderId, "PAYMENT_PENDING", "00020101", null);
        return transaction;
    }

    @Test
    void aCorrectlySignedCallbackForAKnownOrderIsApplied() {
        String raw = body("ORD-1", "SUCCESS", "n1");
        Ppob1Transaction transaction = awaitingPayment("ORD-1");
        given(transactionRepository.findByPpob2OrderId("ORD-1")).willReturn(Optional.of(transaction));

        WebhookVerdict verdict = service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1"));

        assertThat(verdict).isEqualTo(WebhookVerdict.APPLIED);
        assertThat(transaction.getLocalState()).isEqualTo(LocalState.COMPLETED);
        assertThat(transaction.getPpob2State()).isEqualTo("SUCCESS");
        // The source is what later proves the push integration is actually working -- a recon run
        // reports a terminal state learned any other way as WEBHOOK_NEVER_ARRIVED.
        assertThat(transaction.getLastSource()).isEqualTo(StateSource.WEBHOOK);
        verify(transactionRepository).save(transaction);
    }

    @Test
    void anInvalidSignatureAppliesNothingAtAll() {
        String raw = body("ORD-1", "SUCCESS", "n1");
        Ppob1Transaction transaction = awaitingPayment("ORD-1");
        given(transactionRepository.findByPpob2OrderId("ORD-1")).willReturn(Optional.of(transaction));

        WebhookVerdict verdict = service.ingest(raw, TIMESTAMP, "n1", "00".repeat(32));

        assertThat(verdict).isEqualTo(WebhookVerdict.REJECTED_SIGNATURE);
        // The critical assertion: the state in the body is ignored. Anyone who learned an order id
        // could otherwise move PPOB1's books.
        assertThat(transaction.getLocalState()).isEqualTo(LocalState.AWAITING_PAYMENT);
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).findByPpob2OrderId(any());
    }

    @Test
    void aRejectedCallbackIsStillRecorded() {
        String raw = body("ORD-1", "SUCCESS", "n1");

        service.ingest(raw, TIMESTAMP, "n1", "00".repeat(32));

        ArgumentCaptor<InboundWebhook> captor = ArgumentCaptor.forClass(InboundWebhook.class);
        verify(webhookRepository).save(captor.capture());
        InboundWebhook recorded = captor.getValue();
        assertThat(recorded.getVerdict()).isEqualTo(WebhookVerdict.REJECTED_SIGNATURE);
        assertThat(recorded.isSignatureValid()).isFalse();
        // The raw body is kept so a signature dispute can be settled later: the signature covers
        // these exact bytes, and any re-serialization would hash differently.
        assertThat(recorded.getRawBody()).isEqualTo(raw);
        // Deliberately NOT parsed out of an unauthenticated body.
        assertThat(recorded.getPpob2OrderId()).isNull();
        // And the nonce is NOT stored. Persisting an unauthenticated nonce into the unique column
        // would let anyone reaching this endpoint burn one: forge a request carrying the nonce of
        // an undelivered callback, and PPOB2's genuine one then trips existsByNonce, is answered
        // 200 DUPLICATE, and is dropped -- with no retry, because 200 means "taken". A real event
        // lost via the dedup path rather than the apply path.
        assertThat(recorded.getNonce()).isNull();
    }

    @Test
    void aForgedNonceCannotBlockTheGenuineCallbackThatFollows() {
        // The attack the null above prevents, driven end to end.
        String raw = body("ORD-1", "SUCCESS", "n1");
        Ppob1Transaction transaction = awaitingPayment("ORD-1");
        given(transactionRepository.findByPpob2OrderId("ORD-1")).willReturn(Optional.of(transaction));

        // Forged first, carrying the nonce PPOB2 is about to use.
        assertThat(service.ingest(raw, TIMESTAMP, "n1", "00".repeat(32)))
                .isEqualTo(WebhookVerdict.REJECTED_SIGNATURE);

        // existsByNonce still answers false, because nothing claimed "n1" -- the mock defaults to
        // false, which is exactly what the null-nonce write leaves true of the real table.
        assertThat(service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1")))
                .isEqualTo(WebhookVerdict.APPLIED);
        assertThat(transaction.getLocalState()).isEqualTo(LocalState.COMPLETED);
    }

    @Test
    void aMissingSignatureHeaderIsRejectedRatherThanCrashing() {
        String raw = body("ORD-1", "SUCCESS", "n1");

        assertThat(service.ingest(raw, TIMESTAMP, "n1", null)).isEqualTo(WebhookVerdict.REJECTED_SIGNATURE);
    }

    @Test
    void aRedeliveredNonceIsADuplicateAndChangesNothing() {
        String raw = body("ORD-1", "SUCCESS", "n1");
        given(webhookRepository.existsByNonce("n1")).willReturn(true);

        WebhookVerdict verdict = service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1"));

        assertThat(verdict).isEqualTo(WebhookVerdict.DUPLICATE);
        verify(transactionRepository, never()).save(any());
        // Not re-recorded either: the first receipt is already on file, and a second row per
        // redelivery would make the webhook log mostly noise. PPOB2 retries 4 times by default.
        verify(webhookRepository, never()).save(any());
    }

    @Test
    void aSignedCallbackForAnUnknownOrderIsRecordedWithoutBeingApplied() {
        String raw = body("ORD-GHOST", "SUCCESS", "n1");
        given(transactionRepository.findByPpob2OrderId("ORD-GHOST")).willReturn(Optional.empty());

        WebhookVerdict verdict = service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1"));

        assertThat(verdict).isEqualTo(WebhookVerdict.UNKNOWN_ORDER);
        ArgumentCaptor<InboundWebhook> captor = ArgumentCaptor.forClass(InboundWebhook.class);
        verify(webhookRepository).save(captor.capture());
        // Signature verified, so the fields ARE trustworthy enough to record here -- unlike the
        // rejected-signature case above. This row is how a lost create-response is discovered.
        assertThat(captor.getValue().isSignatureValid()).isTrue();
        assertThat(captor.getValue().getPpob2OrderId()).isEqualTo("ORD-GHOST");
    }

    /**
     * A signed callback for a live order PPOB1 holds no id for — the case this receiver gained a
     * second lookup for. Before PPOB2 echoed {@code reseller_txn_id} back, this exact callback read
     * as {@link WebhookVerdict#UNKNOWN_ORDER}: PPOB1 was being told about one of its own sales and
     * had nothing to match it on.
     *
     * <p>{@code SUBMITTING} with no upstream id is the shape a lost create response leaves. When
     * this test was written that orphan produced no callback at all — no QR reached anyone, so the
     * order expired unpaid and {@code EXPIRED} fired nothing — so the fixture then stood only for a
     * sale whose pointer was lost after the order went live. PPOB2 notifies on {@code EXPIRED} as
     * of 2026-10-10, so it now stands for both, which is what the harness drives end to end
     * ({@code E2E-EXPREC-1}). See {@link WebhookVerdict#RECOVERED_BY_RESELLER_ID} for the scope,
     * including the two orphans still out of reach.
     */
    @Test
    void anUnknownOrderIsRecoveredByTheResellersOwnId() {
        String raw = bodyWithResellerId("ORD-RECOVERED", "SUCCESS", "n1", "PPOB1-TX-1");
        Ppob1Transaction orphan = orphanedSubmit();
        given(transactionRepository.findByPpob2OrderId("ORD-RECOVERED")).willReturn(Optional.empty());
        given(transactionRepository.findByResellerTxnId("PPOB1-TX-1")).willReturn(Optional.of(orphan));

        WebhookVerdict verdict = service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1"));

        assertThat(verdict).isEqualTo(WebhookVerdict.RECOVERED_BY_RESELLER_ID);
        assertThat(orphan.getPpob2OrderId()).isEqualTo("ORD-RECOVERED");
        assertThat(orphan.getLocalState()).isEqualTo(LocalState.COMPLETED);
        assertThat(orphan.getPpob2State()).isEqualTo("SUCCESS");
        assertThat(orphan.getLastSource()).isEqualTo(StateSource.WEBHOOK);
        verify(transactionRepository).save(orphan);
    }

    @Test
    void aResellerIdPpob1NeverIssuedIsStillAnUnknownOrder() {
        String raw = bodyWithResellerId("ORD-GHOST", "SUCCESS", "n1", "SOMEONE-ELSES-TX");
        given(transactionRepository.findByPpob2OrderId("ORD-GHOST")).willReturn(Optional.empty());
        given(transactionRepository.findByResellerTxnId("SOMEONE-ELSES-TX")).willReturn(Optional.empty());

        assertThat(service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1")))
                .isEqualTo(WebhookVerdict.UNKNOWN_ORDER);
        verify(transactionRepository, never()).save(any());
    }

    /**
     * The guard that makes matching on a reseller's own id safe to do at all. That id is unique in
     * PPOB1 but not necessarily upstream: PPOB2's idempotency key is per
     * {@code (client_id, idempotency_key)} and Section 22.3 lets one partner hold several
     * {@code api_client} rows, so the same reseller id sent through two of them produces two orders.
     * Adopting here would replace the order PPOB1 is tracking and leave one record for two orders.
     */
    @Test
    void aResellerIdAlreadyHeldAgainstADifferentOrderIsAConflictAndChangesNothing() {
        String raw = bodyWithResellerId("ORD-SECOND", "SUCCESS", "n1", "PPOB1-TX-1");
        Ppob1Transaction pinned = awaitingPayment("ORD-FIRST");
        given(transactionRepository.findByPpob2OrderId("ORD-SECOND")).willReturn(Optional.empty());
        given(transactionRepository.findByResellerTxnId("PPOB1-TX-1")).willReturn(Optional.of(pinned));

        WebhookVerdict verdict = service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1"));

        assertThat(verdict).isEqualTo(WebhookVerdict.ORDER_ID_CONFLICT);
        assertThat(pinned.getPpob2OrderId()).isEqualTo("ORD-FIRST");
        assertThat(pinned.getLocalState()).isEqualTo(LocalState.AWAITING_PAYMENT);
        verify(transactionRepository, never()).save(any());
    }

    /**
     * {@code REJECTED} is the other state that holds no upstream order id, and the guard is on the
     * id rather than on the state deliberately. This is in fact the <b>strongest</b> case for the
     * whole mechanism, not an afterthought: a {@code 409 IDEMPOTENCY_KEY_CONFLICT} is the one
     * refusal PPOB2 issues while an order for that key does exist, and
     * {@code ReconciliationService} excludes {@code REJECTED} from its working set — so recon will
     * never look at that row again and the callback is the only thing that can ever correct it.
     */
    @Test
    void aRejectedSaleIsAlsoRecoverableAndKeepsWhyItWasRejected() {
        String raw = bodyWithResellerId("ORD-EXISTED-ANYWAY", "SUCCESS", "n1", "PPOB1-TX-1");
        Ppob1Transaction rejected = new Ppob1Transaction(
                "PPOB1-TX-1", "MOBILE_LEGENDS", BigInteger.valueOf(20000), "GAMEID-1");
        rejected.rejectedByPpob2("IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key already used.");
        given(transactionRepository.findByPpob2OrderId("ORD-EXISTED-ANYWAY")).willReturn(Optional.empty());
        given(transactionRepository.findByResellerTxnId("PPOB1-TX-1")).willReturn(Optional.of(rejected));

        WebhookVerdict verdict = service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1"));

        assertThat(verdict).isEqualTo(WebhookVerdict.RECOVERED_BY_RESELLER_ID);
        assertThat(rejected.getPpob2OrderId()).isEqualTo("ORD-EXISTED-ANYWAY");
        assertThat(rejected.getLocalState()).isEqualTo(LocalState.COMPLETED);
        // Kept, not cleared. A COMPLETED row still carrying a rejection code is the whole finding:
        // PPOB2 refused this sale at create time and an order for it reached a terminal state
        // anyway. Clearing them would make this indistinguishable from an ordinary recovery.
        assertThat(rejected.getRejectionCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(rejected.getRejectionMessage()).isEqualTo("Idempotency-Key already used.");
    }

    @Test
    void aCallbackWithNoResellerIdAtAllNeverReachesTheSecondLookup() {
        // Every order placed before PPOB2's V23 carries no metadata, and so no reseller id. The
        // recovery must degrade to the old behaviour rather than querying for null.
        String raw = body("ORD-GHOST", "SUCCESS", "n1");
        given(transactionRepository.findByPpob2OrderId("ORD-GHOST")).willReturn(Optional.empty());

        assertThat(service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1")))
                .isEqualTo(WebhookVerdict.UNKNOWN_ORDER);
        verify(transactionRepository, never()).findByResellerTxnId(any());
    }

    @Test
    void anInvalidSignatureIsNotRecoveredEither() {
        // The reseller id is read only after the signature verifies, and that ordering is what
        // makes it safe to act on: it is an authenticated echo of PPOB1's own id, not a lookup key
        // a caller gets to choose. Otherwise anyone reaching this endpoint could attach an order id
        // of their choosing to any open sale.
        String raw = bodyWithResellerId("ORD-ATTACKER", "SUCCESS", "n1", "PPOB1-TX-1");
        Ppob1Transaction orphan = orphanedSubmit();
        given(transactionRepository.findByResellerTxnId("PPOB1-TX-1")).willReturn(Optional.of(orphan));

        assertThat(service.ingest(raw, TIMESTAMP, "n1", "00".repeat(32)))
                .isEqualTo(WebhookVerdict.REJECTED_SIGNATURE);
        assertThat(orphan.getPpob2OrderId()).isNull();
        verify(transactionRepository, never()).findByResellerTxnId(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void aSignedButUnparseableBodyIsMalformed() {
        String raw = "not json at all";

        assertThat(service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1")))
                .isEqualTo(WebhookVerdict.MALFORMED);
    }

    @Test
    void aSignedBodyMissingStateIsMalformedRatherThanAppliedAsNull() {
        String raw = "{\"event_type\":\"ORDER_STATUS_CHANGED\",\"order_id\":\"ORD-1\",\"nonce\":\"n1\"}";

        assertThat(service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1")))
                .isEqualTo(WebhookVerdict.MALFORMED);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void theBodyTimestampIsNotWhatGetsSigned() {
        // PPOB2 sends an ISO-8601 `timestamp` in the body AND an epoch-ms X-Timestamp header, and
        // signs with the header. Signing over the body's value fails every time and looks exactly
        // like a wrong secret, so this pins which one is authoritative.
        String raw = body("ORD-1", "SUCCESS", "n1");
        Ppob1Transaction transaction = awaitingPayment("ORD-1");
        given(transactionRepository.findByPpob2OrderId("ORD-1")).willReturn(Optional.of(transaction));

        String signedWithBodyTimestamp = signer.sign(SECRET, signer.stringToSign(
                "POST", PATH, "2026-10-10T00:00:00Z", "n1", signer.sha256Hex(raw)));

        assertThat(service.ingest(raw, TIMESTAMP, "n1", signedWithBodyTimestamp))
                .isEqualTo(WebhookVerdict.REJECTED_SIGNATURE);
        assertThat(service.ingest(raw, TIMESTAMP, "n1", signatureFor(raw, "n1")))
                .isEqualTo(WebhookVerdict.APPLIED);
    }

    @Test
    void thePathMustMatchTheRegisteredCallbackPath() {
        // PPOB2 signs the path component of the callback_url it was given, so a receiver mounted at
        // a different path than it registered rejects everything.
        String raw = body("ORD-1", "SUCCESS", "n1");
        String signedForAnotherPath = signer.sign(SECRET, signer.stringToSign(
                "POST", "/some/other/path", TIMESTAMP, "n1", signer.sha256Hex(raw)));

        assertThat(service.ingest(raw, TIMESTAMP, "n1", signedForAnotherPath))
                .isEqualTo(WebhookVerdict.REJECTED_SIGNATURE);
    }
}
