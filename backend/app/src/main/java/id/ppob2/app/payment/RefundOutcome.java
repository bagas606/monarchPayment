package id.ppob2.app.payment;

import id.ppob2.order.domain.OrderState;
import id.ppob2.payment.domain.PaymentStatus;

/**
 * Result of a completed refund, for the controller's response and audit snapshot — the same shape
 * and purpose as {@code RetryOutcome} next to it in `app`.
 *
 * <p>{@code gatewayExecuted} is carried out to the caller deliberately: "we instructed the PG to
 * return the money" and "a human moved the money and told us about it" are materially different
 * facts about the same {@code REFUNDED} state, and the operator reading the response or the audit
 * row needs to know which one happened. {@code refundReference} means the PG's own reference in the
 * first case and the operator's out-of-band evidence in the second.
 */
public record RefundOutcome(Long parentOrderId,
                             Long paymentId,
                             OrderState orderState,
                             PaymentStatus paymentStatus,
                             String refundReference,
                             boolean gatewayExecuted) {
}
