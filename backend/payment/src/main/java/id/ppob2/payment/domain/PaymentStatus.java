package id.ppob2.payment.domain;

/** PRD Section 22.17 `payment.status`. */
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    EXPIRED,
    REFUNDED
}
