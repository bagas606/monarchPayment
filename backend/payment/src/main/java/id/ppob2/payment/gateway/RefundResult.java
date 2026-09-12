package id.ppob2.payment.gateway;

public record RefundResult(
        boolean success,
        String refundReference,
        String failureReason
) {
}
