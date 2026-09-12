package id.ppob2.order;

import java.util.List;

/**
 * Published after Section 33.2's {@code DECOMPOSITION_SELECTED -> FULFILLING} transition
 * ("Child orders created & dispatch begins"). `order` has no compile dependency on `catalog` or
 * `fulfillment` (Section 20.2's graph grants neither edge), so it cannot resolve
 * {@code provider_sku_id -> provider_id} or invoke fulfillment directly — that composition
 * happens in `app`, which depends on both, the same way {@code CreateOrderController} joins
 * `catalog` and `partner` data for `order`'s use case. This event is how `order` hands off without
 * needing either dependency itself.
 */
public record ChildOrdersReadyEvent(Long parentOrderId, List<ChildOrderRef> childOrders) {
}
