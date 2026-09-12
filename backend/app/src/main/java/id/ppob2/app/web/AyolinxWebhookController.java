package id.ppob2.app.web;

import id.ppob2.payment.callback.PaymentCallbackOutcome;
import id.ppob2.payment.callback.PaymentCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Section 25.2: {@code POST /internal/webhooks/ayolinx}. Section 25.2 also calls for this
 * endpoint to be "network-restricted / IP-allowlisted where PG supports it" — that's
 * infrastructure-level (reverse proxy / security group), not something this controller enforces;
 * flagged as a deployment prerequisite, not a code gap.
 *
 * <p>Deliberately outside {@code /api/v1/**}: this is a PG-to-PPOB2 callback, not a partner
 * Open API call, so the Section 23.2 client-credential HMAC scheme doesn't apply — signature
 * verification here uses Ayolinx's own (stubbed) callback secret instead, inside
 * {@link PaymentCallbackService}.
 */
@RestController
public class AyolinxWebhookController {

    private final PaymentCallbackService paymentCallbackService;

    public AyolinxWebhookController(PaymentCallbackService paymentCallbackService) {
        this.paymentCallbackService = paymentCallbackService;
    }

    @PostMapping("/internal/webhooks/ayolinx")
    public ResponseEntity<Void> receiveCallback(@RequestBody String rawBody, HttpServletRequest request) {
        PaymentCallbackOutcome outcome = paymentCallbackService.processCallback(rawBody, headersOf(request));

        return switch (outcome) {
            case SIGNATURE_INVALID -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case MALFORMED -> ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            // Section 48.5: acknowledge everything else — duplicates, unknown references, and
            // stale-terminal-state callbacks are all permanently classified, not retryable errors.
            case PROCESSED, DUPLICATE_IGNORED, UNKNOWN_REFERENCE, STALE_TERMINAL_STATE -> ResponseEntity.ok().build();
        };
    }

    /** HTTP header names are case-insensitive; a plain {@code HashMap} would make lookups
     * (e.g. {@code X-Ayolinx-Signature} vs {@code x-ayolinx-signature}) fragile to the client's
     * casing choice, so this uses a case-insensitive map instead. */
    private Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return Collections.emptyMap();
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
