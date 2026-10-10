package id.ppob2.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.sharedkernel.security.HmacSigner;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * PRD Section 23.8: {@code POST {callback_url}}, signed the same way inbound Open API requests
 * are (Section 23.2's scheme, different secret) — {@code HmacSigner} is shared by both for
 * exactly this reason. This sends exactly **one** attempt; Section 23.8's "5 attempts over 24h"
 * retry-with-backoff schedule is not built (flagged in the README) — there is no delivery/retry
 * machinery, no persisted delivery-attempt-count, and no scheduled re-driver.
 *
 * <p>The body carries one field Section 23.8 does not document, {@code reseller_txn_id} — see
 * {@link #payload} for what it is for and why only that one key is echoed.
 *
 * <p>Deliberately does no persistence itself — the caller (an `app`-layer orchestrator) records
 * the outcome via {@link WebhookEventRecorder} in its own write, after this returns, so a slow or
 * unreachable partner endpoint (the normal case here, not exceptional) never holds a database
 * transaction open. Bounded connect/response timeouts for the same reason
 * {@code FulfillmentExecutionService}'s provider call must not run inside a transaction: this is
 * reached from an {@code AFTER_COMMIT} listener's call tree and must not hang it.
 */
@Component
public class OutboundWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(OutboundWebhookSender.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public OutboundWebhookDeliveryResult send(String callbackUrl, String secret, String orderId, String state,
                                                String resellerTxnId) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = randomNonceHex();
        String body;
        try {
            body = objectMapper.writeValueAsString(payload(orderId, state, nonce, resellerTxnId));
        } catch (Exception e) {
            // Serializing a Map of primitives cannot realistically fail; treated as a delivery
            // failure rather than propagated, since a webhook delivery problem must never abort
            // the rest of the AFTER_COMMIT listener's call tree.
            log.error("Failed to serialize outbound webhook payload for order {}", orderId, e);
            return OutboundWebhookDeliveryResult.failure(body(orderId, state, timestamp, nonce), "SERIALIZATION_ERROR: " + e.getMessage());
        }

        String path = URI.create(callbackUrl).getPath();
        String bodyHash = HmacSigner.sha256Hex(body);
        String stringToSign = HmacSigner.buildStringToSign("POST", path, timestamp, nonce, bodyHash);
        String signature = HmacSigner.sign(secret, stringToSign);

        try {
            HttpStatusCode status = webClient.post()
                    .uri(callbackUrl)
                    .header("Content-Type", "application/json")
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT)
                    .getStatusCode();

            if (status.is2xxSuccessful()) {
                return OutboundWebhookDeliveryResult.success(body);
            }
            return OutboundWebhookDeliveryResult.failure(body, "HTTP " + status.value());
        } catch (Exception e) {
            // Includes connection refused/timeout/unresolvable host — the expected case for an
            // unreachable partner endpoint, not an application bug. Never rethrown.
            log.warn("Outbound webhook delivery failed for order {} to {}: {}", orderId, callbackUrl, e.toString());
            return OutboundWebhookDeliveryResult.failure(body, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Section 23.8's documented body, plus {@code reseller_txn_id}.
     *
     * <p><b>That extra field is a deliberate addition to a documented contract.</b> Section 23.8's
     * example body has five fields and does not include it; this sends a sixth, additive, so a
     * partner parsing only the documented five is unaffected.
     *
     * <p>It exists so that a callback is correlatable by the identifier the <em>partner</em> owns.
     * With {@code order_id} alone, acting on a callback requires the partner to have successfully
     * stored our order number — so a partner whose books lost it, for any reason, receives a
     * correctly signed message about one of its own sales and can do nothing with it. Echoing back
     * the id the partner itself chose removes that dependency, and lets a partner key its whole
     * callback handling on its own reference instead of ours.
     *
     * <p>It also enables a recovery, and the honest scope of that is narrower than it first looks —
     * see {@code WebhookVerdict.RECOVERED_BY_RESELLER_ID} in {@code ppob1-sim} for which cases it
     * reaches and, more importantly, which it does not. In particular it does <em>not</em> rescue
     * the ordinary lost-create-response orphan: no callback is ever sent for one, because that order
     * is never paid and expires, and {@code EXPIRED} is not a state this sender fires on.
     *
     * <p><b>Only {@code reseller_txn_id}, never the whole {@code metadata} object.</b> That column
     * holds whatever a partner chose to put in it, this body is persisted on both sides of the wire
     * ({@code webhook_event} here, {@code ppob1_inbound_webhook} there) and is in flight to a URL the
     * partner supplied — so echoing the lot would copy any incidental personal data a partner
     * happens to annotate orders with into two more places. One key, the one with a documented
     * reader.
     *
     * <p>Package-private and separate from {@link #send} so the shape can be asserted without a
     * {@code WebClient}: the "absent when the order has no metadata" case is otherwise untestable
     * end to end, because every order the e2e harness places through {@code ppob1-sim} carries it.
     */
    Map<String, Object> payload(String orderId, String state, String nonce, String resellerTxnId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "ORDER_STATUS_CHANGED");
        payload.put("order_id", orderId);
        payload.put("state", state);
        payload.put("timestamp", Instant.now().toString());
        payload.put("nonce", nonce);
        // Conditional put, not put(key, null). This class deliberately uses its own bare
        // ObjectMapper rather than the app's configured bean, so no NON_NULL inclusion setting
        // applies -- and map entries are not governed by one in any case, so a null value would be
        // serialized as "reseller_txn_id":null. An order with no metadata should carry no such key
        // at all, not a key asserting the reseller has no id.
        if (resellerTxnId != null) {
            payload.put("reseller_txn_id", resellerTxnId);
        }
        return payload;
    }

    /**
     * The body recorded when serialization itself failed, which {@link #payload}'s own comment
     * explains cannot actually happen. Built by concatenation, so it deliberately carries only
     * PPOB2's own values — {@code order_no} and an {@code OrderState} name. {@code reseller_txn_id}
     * is partner-supplied text and is left out: pasting it between quotes here, with no escaping,
     * would let a value containing a double quote produce a malformed record, and the one place
     * that must never be fragile is the one that already means something went wrong.
     */
    private String body(String orderId, String state, String timestamp, String nonce) {
        return "{\"event_type\":\"ORDER_STATUS_CHANGED\",\"order_id\":\"" + orderId + "\",\"state\":\"" + state
                + "\",\"timestamp\":\"" + timestamp + "\",\"nonce\":\"" + nonce + "\"}";
    }

    private String randomNonceHex() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
