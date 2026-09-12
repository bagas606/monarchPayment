package id.ppob2.decomposition;

import id.ppob2.sharedkernel.money.Money;

/**
 * Minimal shape `order` needs to turn a selected pattern into child orders (Section 22.16) —
 * deliberately not the full {@code DecompositionComponent} entity, so `order` doesn't need to
 * import a `decomposition`-owned {@code @Entity} across the module boundary. {@code faceValue} is
 * the per-unit value from live {@code catalog.provider_sku.face_value} (see
 * {@code PatternComponentQueryService}'s Javadoc for why the live value, not the
 * {@code decomposition_component} snapshot) — carried onto {@code child_order} at creation time
 * rather than re-derived later by joining back to {@code decomposition_component} on
 * {@code provider_sku_id}, a join that isn't safe since nothing constrains a pattern to at most
 * one component per SKU.
 */
public record PatternComponentDto(Long providerSkuId, int quantity, Money faceValue) {
}
