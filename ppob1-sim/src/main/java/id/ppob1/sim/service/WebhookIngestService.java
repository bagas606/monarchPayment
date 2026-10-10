package id.ppob1.sim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob1.sim.client.Ppob2RequestSigner;
import id.ppob1.sim.domain.InboundWebhook;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.domain.StateSource;
import id.ppob1.sim.domain.WebhookVerdict;
import id.ppob1.sim.repository.InboundWebhookRepository;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives PRD Section 23.8 {@code ORDER_STATUS_CHANGED} callbacks.
 *
 * <p><b>Verify first, parse second.</b> The signature is checked against the raw body before the
 * JSON is looked at, and a failed check applies nothing at all. The tempting shortcut — parse,
 * find the order, then verify — hands an unauthenticated caller a way to probe which order ids
 * exist by watching response timing, and more importantly makes it easy for a later edit to
 * accidentally act on data that was never authenticated.
 *
 * <p><b>The signature covers the raw bytes.</b> {@code rawBody} is the exact string received,
 * never a re-serialization of a parsed object: PPOB2 hashes the bytes it sent, and Jackson
 * round-tripping would change whitespace or key order and produce a hash that never matches. The
 * raw body is also what gets persisted, so a signature dispute can be settled afterwards.
 *
 * <p><b>Which HTTP status to answer is a correctness decision, not a formality.</b> PPOB2 retries
 * any non-2xx four times over roughly 23 hours. Answering non-2xx to something a retry cannot fix
 * (a malformed body, an order PPOB1 has never heard of) burns that budget and buries the one row
 * that records a real problem; answering 2xx to something transient loses the event. See
 * {@link WebhookVerdict} for the status chosen per verdict and why.
 */
@Service
public class WebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    private final Ppob1TransactionRepository transactionRepository;
    private final InboundWebhookRepository webhookRepository;
    private final Ppob2RequestSigner signer;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;
    private final String callbackPath;

    public WebhookIngestService(Ppob1TransactionRepository transactionRepository,
                                 InboundWebhookRepository webhookRepository,
                                 Ppob2RequestSigner signer,
                                 ObjectMapper objectMapper,
                                 @Value("${ppob1.webhook-secret}") String webhookSecret,
                                 @Value("${ppob1.callback-path}") String callbackPath) {
        this.transactionRepository = transactionRepository;
        this.webhookRepository = webhookRepository;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
        this.callbackPath = callbackPath;
    }

    /**
     * @param rawBody the request body exactly as received — see the class Javadoc.
     * @param timestampHeader {@code X-Timestamp}. Note this is the <b>header</b> value (epoch
     *     milliseconds), not the ISO-8601 {@code timestamp} field inside the body. PPOB2 sends both
     *     and they are different formats of a slightly different instant; signing over the body's
     *     one fails every time, and the failure looks exactly like a wrong secret.
     */
    @Transactional
    public WebhookVerdict ingest(String rawBody, String timestampHeader, String nonceHeader, String signatureHeader) {
        String stringToSign = signer.stringToSign("POST", callbackPath, timestampHeader,
                nonceHeader, signer.sha256Hex(rawBody));
        boolean signatureValid = timestampHeader != null && nonceHeader != null
                && signer.matches(webhookSecret, stringToSign, signatureHeader);

        if (!signatureValid) {
            log.warn("Rejected an inbound webhook with an invalid signature (nonce={})", nonceHeader);
            // The nonce is stored as NULL, NOT as the header value, even though the header value is
            // what gets logged above. Persisting an unauthenticated nonce into the unique `nonce`
            // column would let anyone who can reach this endpoint burn a nonce: forge a request
            // carrying the nonce of a callback not yet delivered, and when PPOB2's genuine one
            // arrives it trips existsByNonce, is answered 200 DUPLICATE, and is dropped — and
            // because 200 means "taken", PPOB2 never retries it. A real event lost by an
            // unauthenticated caller, through the dedup path rather than the apply path.
            //
            // This also restores the invariant stated at the top of this class: a failed signature
            // check applies nothing at all. An unverified nonce is still input, so it has no
            // business in the keyspace that decides what gets dropped. Postgres permits any number
            // of NULLs in a unique column, so rejected rows never collide with each other either.
            return record(rawBody, null, null, null, null, false, WebhookVerdict.REJECTED_SIGNATURE);
        }

        JsonNode body;
        try {
            body = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return record(rawBody, nonceHeader, null, null, null, true, WebhookVerdict.MALFORMED);
        }

        String eventType = body.hasNonNull("event_type") ? body.get("event_type").asText() : null;
        String orderId = body.hasNonNull("order_id") ? body.get("order_id").asText() : null;
        String state = body.hasNonNull("state") ? body.get("state").asText() : null;
        // The body's nonce is preferred over the header's for the duplicate check because Section
        // 23.8 defines the nonce as part of the signed payload; PPOB2 happens to send the same
        // value in both, and preferring the body means the dedup key is the one the signature
        // actually covers.
        String nonce = body.hasNonNull("nonce") ? body.get("nonce").asText() : nonceHeader;

        if (orderId == null || state == null) {
            return record(rawBody, nonce, eventType, orderId, state, true, WebhookVerdict.MALFORMED);
        }
        if (nonce != null && webhookRepository.existsByNonce(nonce)) {
            log.info("Duplicate webhook for order {} (nonce {}) — PPOB2 redelivery, answering 200", orderId, nonce);
            return WebhookVerdict.DUPLICATE;
        }

        Optional<Ppob1Transaction> transaction = transactionRepository.findByPpob2OrderId(orderId);
        if (transaction.isEmpty()) {
            return ingestUnmatchedOrder(rawBody, nonce, eventType, orderId, state,
                    body.hasNonNull("reseller_txn_id") ? body.get("reseller_txn_id").asText() : null);
        }

        transaction.get().applyPpob2State(state, StateSource.WEBHOOK);
        transactionRepository.save(transaction.get());
        return record(rawBody, nonce, eventType, orderId, state, true, WebhookVerdict.APPLIED);
    }

    /**
     * A correctly signed callback naming an order id PPOB1 holds no row for — so it did come from
     * PPOB2, and PPOB1 is nonetheless on the hook for an order it cannot see.
     *
     * <p>The second chance is {@code reseller_txn_id}, which PPOB2 echoes back from the
     * {@code metadata} PPOB1 sent on the create request. It is read only here, after the signature
     * has verified, and that ordering is the whole reason this is safe to act on: the value is an
     * authenticated echo of PPOB1's own id, not a caller-supplied lookup key. An unsigned caller
     * that could reach this method would otherwise be able to attach an order id of its choosing to
     * any sale PPOB1 has open.
     *
     * <p>Three outcomes, and only the first writes anything — see {@link WebhookVerdict} for the
     * reasoning behind each, and for why the guard is {@code ppob2_order_id IS NULL} rather than a
     * check on the local state.
     */
    private WebhookVerdict ingestUnmatchedOrder(String rawBody, String nonce, String eventType,
                                                 String orderId, String state, String resellerTxnId) {
        Optional<Ppob1Transaction> byResellerId = resellerTxnId == null
                ? Optional.empty()
                : transactionRepository.findByResellerTxnId(resellerTxnId);

        if (byResellerId.isEmpty()) {
            log.error("Signed webhook for order {} that PPOB1 has no record of — state {}, "
                            + "reseller_txn_id {}. Check for a SUBMITTING transaction whose create "
                            + "response was lost", orderId, state, resellerTxnId);
            return record(rawBody, nonce, eventType, orderId, state, true, WebhookVerdict.UNKNOWN_ORDER);
        }

        Ppob1Transaction candidate = byResellerId.get();
        if (candidate.getPpob2OrderId() != null) {
            log.error("Signed webhook for order {} carries reseller_txn_id {}, which PPOB1 already "
                            + "holds against a DIFFERENT order id {}. Applying nothing — adopting would "
                            + "replace the order being tracked. Two upstream orders for one reseller id "
                            + "is possible when a partner submits through more than one api_client",
                    orderId, resellerTxnId, candidate.getPpob2OrderId());
            return record(rawBody, nonce, eventType, orderId, state, true, WebhookVerdict.ORDER_ID_CONFLICT);
        }

        // The previous local state is logged because adopting overwrites it and nothing else keeps
        // it -- and it is the only thing that says which problem this was. REJECTED means PPOB2
        // refused this sale at create time and an order for it exists anyway (the 409 case, which
        // recon can never see); SUBMITTING means PPOB1 lost the pointer to a live order.
        log.warn("Recovered a sale PPOB1 held no upstream id for: reseller_txn_id {} was {} with no "
                        + "order id, adopting {} and applying state {}",
                resellerTxnId, candidate.getLocalState(), orderId, state);
        candidate.adoptPpob2OrderId(orderId);
        candidate.applyPpob2State(state, StateSource.WEBHOOK);
        transactionRepository.save(candidate);
        return record(rawBody, nonce, eventType, orderId, state, true, WebhookVerdict.RECOVERED_BY_RESELLER_ID);
    }

    /**
     * Persists the receipt. The unique constraint on {@code nonce} is treated as a duplicate rather
     * than an error: two concurrent redeliveries of the same event both pass the
     * {@code existsByNonce} check and one must lose, and the database is the only thing that can
     * actually arbitrate that. The in-memory check is the fast path, not the guarantee — the same
     * layering PPOB2 uses for {@code payment_event_dedup_uk}.
     */
    private WebhookVerdict record(String rawBody, String nonce, String eventType, String orderId,
                                   String state, boolean signatureValid, WebhookVerdict verdict) {
        try {
            webhookRepository.save(new InboundWebhook(
                    eventType, orderId, state, nonce, signatureValid, verdict, rawBody));
            return verdict;
        } catch (DataIntegrityViolationException duplicateNonce) {
            log.info("Concurrent redelivery collapsed by the nonce constraint (order {}, nonce {})", orderId, nonce);
            return WebhookVerdict.DUPLICATE;
        }
    }
}
