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
