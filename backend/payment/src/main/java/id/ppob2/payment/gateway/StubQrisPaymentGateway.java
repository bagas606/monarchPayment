package id.ppob2.payment.gateway;

import id.ppob2.sharedkernel.security.HmacSigner;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development-only stand-in for {@code id.ppob2.payment.gateway.ayolinx.AyolinxPaymentGateway}
 * (Section 25.1). Generates a syntactically-fake QR payload (not a valid EMVCo QRIS string) so
 * {@code OrderApplicationService} and the Create Order slice can be exercised end-to-end without
 * live Ayolinx credentials. Swapping this for the real gateway requires no change to `order` or
 * `payment` beyond flipping {@code ppob2.payment.gateway} — that decoupling is the point of
 * Section 25.1's interface.
 *
 * <p>Two independent guards, deliberately not one:
 * <ul>
 *   <li>{@code @ConditionalOnProperty(havingValue = "stub")} — lets {@code
 *       ppob2.payment.gateway=ayolinx} activate the real gateway in <em>any</em> environment
 *       (including dev), not only one gated by Spring profile, so it can actually be booted and
 *       exercised (and fail loudly on the HTTP call) before a `prod` flip.</li>
 *   <li>{@code @Profile("!prod")} — deliberately kept, <strong>without</strong> {@code
 *       matchIfMissing} on the property condition. If this were the only guard, an unconfigured
 *       {@code prod} deployment (property absent for any reason — misconfiguration, a missed
 *       env var) would fall through to loading this stub anyway, silently issuing QR codes no
 *       customer can pay — exactly the failure mode this class exists to prevent. With both
 *       guards, a `prod` boot with no working {@link PaymentGateway} bean fails to start
 *       (no bean satisfies the {@code PaymentGateway} dependency) rather than silently degrading.</li>
 * </ul>
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "ppob2.payment.gateway", havingValue = "stub")
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
