package id.ppob2.order;

/**
 * The shape {@link ChildOrdersReadyEvent} carries — deliberately not the {@code ChildOrder}
 * entity itself, so the `app`-layer listener doesn't need to re-read what `order` already had in
 * hand when it created the rows (Section 20.2 grants `order -> decomposition`/`catalog` no edge
 * either way, so `app` still has to resolve {@code providerSkuId -> providerId} itself via
 * `catalog` — this DTO just avoids a redundant read of `order`'s own data to get there).
 */
public record ChildOrderRef(Long childOrderId, Long providerSkuId, int quantity) {
}
