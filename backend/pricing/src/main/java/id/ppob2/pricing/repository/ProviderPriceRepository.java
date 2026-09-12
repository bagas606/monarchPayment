package id.ppob2.pricing.repository;

import id.ppob2.pricing.domain.ProviderPrice;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderPriceRepository extends JpaRepository<ProviderPrice, Long> {

    /** The currently-active version for a SKU — the row with {@code effective_until IS NULL},
     * matching the {@code provider_price_active_idx} partial unique index. */
    Optional<ProviderPrice> findByProviderSkuIdAndEffectiveUntilIsNull(Long providerSkuId);

    Optional<ProviderPrice> findTopByProviderSkuIdOrderByPricingVersionDesc(Long providerSkuId);

    /** Retires the current active row so a new version can take its place without ever
     * violating {@code provider_price_active_idx} (called from within {@code setPrice}'s
     * transaction, before the new row is inserted). */
    @Modifying
    @Query("update ProviderPrice p set p.effectiveUntil = :effectiveUntil where p.id = :id")
    void closeActive(@Param("id") Long id, @Param("effectiveUntil") Instant effectiveUntil);
}
