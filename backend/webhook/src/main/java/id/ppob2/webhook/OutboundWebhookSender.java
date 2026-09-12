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

    public OutboundWebhookDeliveryResult send(String callbackUrl, String secret, String orderId, String state) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = randomNonceHex();
        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event_type", "ORDER_STATUS_CHANGED");
            payload.put("order_id", orderId);
            payload.put("state", state);
            payload.put("timestamp", Instant.now().toString());
            payload.put("nonce", nonce);
            body = objectMapper.writeValueAsString(payload);
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
