package id.ppob2.payment.gateway;

import java.util.Map;

/**
 * PRD Section 25.1. Any future PG (Xendit, Midtrans, ...) implements this without touching
 * {@code OrderApplicationService} or the Order State Machine.
 */
public interface PaymentGateway {

    PaymentCreateResult createDynamicQris(PaymentCreateRequest request);

    PaymentInquiryResult inquire(String pgReference);

    RefundResult refund(RefundRequest request);

    boolean verifyCallbackSignature(String rawBody, Map<String, String> headers);
}
