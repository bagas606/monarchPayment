package id.ppob2.pricing.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to the `provider_price` table, PRD Section 22.7. Versioned provider cost per SKU:
 * {@code pricingVersion} is monotonic per {@code providerSkuId}, and exactly one row per SKU may
 * have {@code effectiveUntil IS NULL} (the currently-active version) — enforced by the
 * {@code provider_price_active_idx} partial unique index in the migration, not just convention.
 *
 * <p>References {@code catalog.ProviderSku} by id only, same as {@link SkuUsage} — `pricing` has
 * no dependency edge to `catalog` in Section 20.2's graph, so this is a data-level FK (in the
 * migration) with no Java-level coupling, matching the existing pattern in this module.
 */
@Entity
@Table(name = "provider_price")
public class ProviderPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_sku_id", nullable = false)
    private Long providerSkuId;

    @Column(name = "pricing_version", nullable = false)
    private int pricingVersion;

    @Column(name = "provider_cost", nullable = false)
    private Money providerCost;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    protected ProviderPrice() {
    }

    public ProviderPrice(Long providerSkuId, int pricingVersion, Money providerCost, Instant effectiveFrom) {
        this.providerSkuId = providerSkuId;
        this.pricingVersion = pricingVersion;
        this.providerCost = providerCost;
        this.effectiveFrom = effectiveFrom;
    }

    public Long getId() {
        return id;
    }

    public Long getProviderSkuId() {
        return providerSkuId;
    }

    public int getPricingVersion() {
        return pricingVersion;
    }

    public Money getProviderCost() {
        return providerCost;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveUntil() {
        return effectiveUntil;
    }
}
