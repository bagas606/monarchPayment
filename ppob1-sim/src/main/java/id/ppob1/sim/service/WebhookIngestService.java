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
            return record(rawBody, nonceHeader, null, null, null, false, WebhookVerdict.REJECTED_SIGNATURE);
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
            // Correctly signed, so it did come from PPOB2 — PPOB1 simply has no row for it. The
            // expected cause is a transaction still stuck in SUBMITTING because the create
            // response never arrived, which makes this callback the first PPOB1 has heard of an
            // order it is nonetheless on the hook for.
            log.error("Signed webhook for order {} that PPOB1 has no record of — state {}. "
                    + "Check for a SUBMITTING transaction whose create response was lost", orderId, state);
            return record(rawBody, nonce, eventType, orderId, state, true, WebhookVerdict.UNKNOWN_ORDER);
        }

        transaction.get().applyPpob2State(state, StateSource.WEBHOOK);
        transactionRepository.save(transaction.get());
        return record(rawBody, nonce, eventType, orderId, state, true, WebhookVerdict.APPLIED);
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
