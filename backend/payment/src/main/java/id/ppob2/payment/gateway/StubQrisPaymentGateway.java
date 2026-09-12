package id.ppob2.payment.gateway;

import id.ppob2.sharedkernel.security.HmacSigner;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development-only stand-in for {@code AyolinxPaymentGateway} (Section 25.1). No Ayolinx
 * integration exists yet — there is no contract, sandbox credential, or signature secret to
 * build against. This generates a syntactically-fake QR payload (not a valid EMVCo QRIS string)
 * so {@code OrderApplicationService} and the Create Order slice can be exercised end-to-end.
 * Swapping this for a real {@code AyolinxPaymentGateway} bean requires no change to `order` or
 * `payment` beyond a Spring profile/bean selection — that decoupling is the point of Section
 * 25.1's interface.
 *
 * <p>{@code @Profile("!prod")} is a deliberate guard, not decoration: without it, this is the
 * only {@link PaymentGateway} bean in the context and would load in production exactly as
 * happily as in dev, silently issuing QR codes no customer can pay. Once a real gateway exists,
 * activate the {@code prod} profile there (or replace this guard with an explicit bean-selection
 * property) rather than removing it.
 */
@Component
@Profile("!prod")
public class StubQrisPaymentGateway implements PaymentGateway {

    private final String webhookSecret;

    public StubQrisPaymentGateway(@Value("${ppob2.payment.ayolinx-webhook-secret:dev-webhook-secret}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    public PaymentCreateResult createDynamicQris(PaymentCreateRequest request) {
        String pgReference = "STUB-" + UUID.randomUUID();
        String qrPayload = "00020101021226STUBQRIS-" + request.orderNo() + "-" + request.amount() + "6304FFFF";
        return PaymentCreateResult.success(pgReference, qrPayload, request.expiresAt());
    }

    @Override
    public PaymentInquiryResult inquire(String pgReference) {
        return new PaymentInquiryResult(pgReference, "PENDING", null);
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        throw new UnsupportedOperationException("StubQrisPaymentGateway does not implement refunds; wire a real gateway first.");
    }

    /**
     * Real Ayolinx callback signing is unspecified (Section 25.2 says "HMAC/signature
     * verification against Ayolinx-provided secret" without naming the header or canonical
     * string). This stub invents a scheme — {@code HMAC-SHA256(secret, rawBody)} in the
     * {@code X-Ayolinx-Signature} header — reusing the same primitive as the partner-facing
     * Section 23.2 auth for consistency. Flagged for verification against the real contract,
     * same as {@link AyolinxCallbackPayload}'s shape.
     */
    @Override
    public boolean verifyCallbackSignature(String rawBody, Map<String, String> headers) {
        String signature = headers.get("X-Ayolinx-Signature");
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return HmacSigner.matches(webhookSecret, rawBody, signature);
    }
}
