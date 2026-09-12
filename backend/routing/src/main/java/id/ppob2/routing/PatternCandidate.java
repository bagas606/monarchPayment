package id.ppob2.routing;

import id.ppob2.sharedkernel.money.Money;
import java.util.List;

/**
 * A structurally-valid pattern belonging to the active generation, with its components resolved
 * — built by `decomposition` (via {@code PatternLookupService}) and passed into
 * {@link RoutingService} to score/filter. Defined here, not in `decomposition`, because Section
 * 20.2's allowed-dependency graph runs {@code decomposition -> routing} — only `decomposition`
 * may depend on `routing`, never the reverse, so the shared type has to live on the side both
 * can see: the lower module in the direction of the dependency, i.e. `routing` itself.
 * {@code componentSkuQuantities} maps provider_sku_id to the quantity of that SKU the pattern
 * requires, alongside that SKU's *current* {@code provider_sku.face_value} — needed to verify
 * Section 28.2's hard invariant ({@code SUM(face_value × quantity) == parent_amount}) against
 * live catalog data before a pattern is ever attached to an order, not just against the
 * generation-time snapshot in {@code decomposition_component.face_value}.
 */
public record PatternCandidate(Long patternId, List<ComponentQuantity> componentSkuQuantities) {

    public record ComponentQuantity(Long providerSkuId, int quantity, Money currentFaceValue) {
    }
}
