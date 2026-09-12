package id.ppob2.fulfillment;

import id.ppob2.sharedkernel.money.Money;

/**
 * A {@code child_order} ready to dispatch, with {@code providerId} and {@code providerCost}
 * already resolved by the `app`-layer listener — `fulfillment` has no compile dependency on
 * `catalog` or `pricing`, so it cannot resolve {@code provider_sku_id -> provider_id} or the
 * active {@code provider_price} itself (see {@code ChildOrdersReadyEvent}'s Javadoc in `order`
 * for why that resolution happens where it does). {@code providerCost} is the cost per unit
 * (Section 22.7) — {@link FulfillmentExecutionService} multiplies by {@code quantity} before
 * posting to the Provider/Fulfillment Ledger. Always non-null by construction: the `app`-layer
 * listener routes a SKU with no active price to {@code unresolvableChildOrderIds} instead of
 * constructing a {@code ResolvedChildOrder} for it.
 */
public record ResolvedChildOrder(Long childOrderId, Long providerId, Long providerSkuId, int quantity, Money providerCost) {
}
