package id.ppob2.order.domain;

/** Order State Machine states, PRD Section 33.1. */
public enum OrderState {
    CREATED,
    PAYMENT_PENDING,
    PAID,
    DECOMPOSITION_SELECTED,
    FULFILLING,
    SUCCESS,
    PARTIAL_FAILED,
    FAILED,
    REFUND_PENDING,
    REFUNDED,
    EXPIRED,
    CANCELLED
}
