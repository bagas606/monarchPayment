package id.ppob2.app.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
 * verification here uses Ayolinx's own key/secret instead, inside {@link PaymentCallbackService}.
 *
 * <p>doc.ayolinx.id/api-299374923 requires a JSON body on every response, not just a status code
 * — an unacknowledged/bodiless response is retried up to 4x over 1h. The exact non-{@code
 * 2005600} codes for our reject cases (invalid signature, malformed body) aren't published in the
 * public docs found; {@link #responseCode} below is a reasonable placeholder following the one
 * worked example's shape ({@code 2005600}/"Successful"), flagged like the rest of this
 * integration as unverified against the real contract.
 */
@RestController
public class AyolinxWebhookController {

    private final PaymentCallbackService paymentCallbackService;

    public AyolinxWebhookController(PaymentCallbackService paymentCallbackService) {
        this.paymentCallbackService = paymentCallbackService;
    }

    @PostMapping("/internal/webhooks/ayolinx")
    public ResponseEntity<NotifyResponse> receiveCallback(@RequestBody String rawBody, HttpServletRequest request) {
        PaymentCallbackOutcome outcome = paymentCallbackService.processCallback(rawBody, headersOf(request));

        HttpStatus status = switch (outcome) {
            case SIGNATURE_INVALID -> HttpStatus.UNAUTHORIZED;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
            // Section 48.5: acknowledge everything else — duplicates, unknown references, and
            // stale-terminal-state callbacks are all permanently classified, not retryable errors.
            case PROCESSED, DUPLICATE_IGNORED, UNKNOWN_REFERENCE, STALE_TERMINAL_STATE -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(new NotifyResponse(responseCode(status), status == HttpStatus.OK ? "Successful" : status.getReasonPhrase()));
    }

    private String responseCode(HttpStatus status) {
        return status.value() + "5600";
    }

    /** {@code @JsonNaming} override for the same reason as {@code AyolinxCallbackPayload}: the
     * app's global Jackson config is SNAKE_CASE (for our own partner-facing JSON), but Ayolinx
     * expects {@code responseCode}/{@code responseMessage}, not {@code response_code}. */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record NotifyResponse(String responseCode, String responseMessage) {
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
