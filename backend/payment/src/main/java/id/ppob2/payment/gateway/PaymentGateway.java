package id.ppob2.payment.gateway;

import java.util.Map;

/**
 * PRD Section 25.1. Any future PG (Xendit, Midtrans, ...) implements this without touching
 * {@code OrderApplicationService} or the Order State Machine.
 */
public interface PaymentGateway {

    PaymentCreateResult createDynamicQris(PaymentCreateRequest request);

    PaymentInquiryResult inquire(String pgReference);

    /**
     * Whether {@link #refund(RefundRequest)} can actually be called on this gateway — PRD Section
     * 25.2's "subject to Ayolinx's refund capability/window" and FR-PAY-007's "subject to ... PG
     * capability", made into something callers can branch on instead of discovering by catching
     * {@link UnsupportedOperationException}.
     *
     * <p>Both gateways in this codebase answer {@code false} today, for different reasons:
     * {@code StubQrisPaymentGateway} has no PG to call (unless its dev-only knob is set), and the
     * real {@code AyolinxPaymentGateway} answers false because Ayolinx's public API has no refund
     * endpoint at all — Section 73.3's open question 5 ("API-driven vs manual request") is still
     * open, and `qr-mpm-cancel` voids an *unpaid* QR rather than refunding a settled payment.
     *
     * <p>So this is not defensive boilerplate: it is the switch that decides whether a refund is
     * executed by us or recorded as having been executed out-of-band by a human. A gateway that
     * answers {@code true} must honour {@link #refund(RefundRequest)}; one that answers
     * {@code false} is expected to throw from it.
     */
    boolean supportsRefund();

    /**
     * Only legal when {@link #supportsRefund()} is {@code true}; implementations that answer
     * {@code false} throw {@link UnsupportedOperationException} rather than returning a failed
     * {@link RefundResult}, so a caller that skips the capability check fails loudly instead of
     * silently recording a refund nobody performed.
     */
    RefundResult refund(RefundRequest request);

    boolean verifyCallbackSignature(String rawBody, Map<String, String> headers);
}
