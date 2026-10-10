package id.ppob1.sim.web;

import id.ppob1.sim.domain.WebhookVerdict;
import id.ppob1.sim.service.WebhookIngestService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PPOB1's inbound endpoint for PRD Section 23.8 — the {@code callback_url} it registers on every
 * order.
 *
 * <p>The body is taken as a raw {@code String}, not a bound record. That is required, not a
 * shortcut: the signature covers the exact bytes PPOB2 sent, so letting Jackson parse and
 * re-serialize would change whitespace or key order and produce a hash that can never match. The
 * path must also match {@code ppob1.callback-path} exactly, because PPOB2 signs the path component
 * of the {@code callback_url} it was given.
 *
 * <p>The status per verdict is chosen deliberately — PPOB2 retries any non-2xx four more times
 * over ~23 hours, so the status is a request to retry or not. See {@link WebhookVerdict}.
 */
@RestController
public class Ppob2WebhookController {

    private final WebhookIngestService ingestService;

    public Ppob2WebhookController(WebhookIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("${ppob1.callback-path}")
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature) {

        // A bodyless POST is still signed over the empty string's hash, so it gets verified like
        // any other request rather than short-circuited -- it will fail as MALFORMED after the
        // signature check, which is the honest order of events.
        WebhookVerdict verdict = ingestService.ingest(rawBody == null ? "" : rawBody, timestamp, nonce, signature);

        int status = switch (verdict) {
            // APPLIED and DUPLICATE are both successes from PPOB2's side: the event has been
            // taken. Answering non-2xx to a redelivery would make PPOB2 keep retrying something
            // already accepted.
            case APPLIED, DUPLICATE -> 200;
            // Signed correctly but names an order PPOB1 has never seen. Retrying cannot fix that,
            // and a retry storm would bury the row recording a genuine integration gap.
            case UNKNOWN_ORDER -> 200;
            case REJECTED_SIGNATURE -> 401;
            case MALFORMED -> 400;
        };

        return ResponseEntity.status(status).body(Map.of("verdict", verdict.name()));
    }
}
