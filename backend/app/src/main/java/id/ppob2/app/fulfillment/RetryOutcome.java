package id.ppob2.app.fulfillment;

import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;

/** Result of {@link AdminChildOrderRetryOrchestrator#retry}. */
public record RetryOutcome(Long childOrderId, ChildOrderState childState, Long parentOrderId, OrderState parentState) {
}
