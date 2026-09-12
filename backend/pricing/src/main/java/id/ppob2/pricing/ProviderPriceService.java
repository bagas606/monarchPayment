package id.ppob2.pricing;

import id.ppob2.pricing.domain.ProviderPrice;
import id.ppob2.pricing.repository.ProviderPriceRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 22.7: versioned provider cost per SKU. {@link #getActiveCost} is what `app`'s
 * composition (e.g. fulfillment dispatch resolving the cost to post to the Provider/Fulfillment
 * Ledger) reads; {@link #setPrice} is the only writer, and is the sole place the "exactly one
 * active row per SKU" invariant is maintained — it closes the current active row (if any) before
 * inserting the next version, in the same transaction, so the {@code provider_price_active_idx}
 * partial unique index is never even momentarily violated by this path.
 */
@Service
public class ProviderPriceService {

    private final ProviderPriceRepository providerPriceRepository;

    public ProviderPriceService(ProviderPriceRepository providerPriceRepository) {
        this.providerPriceRepository = providerPriceRepository;
    }

    public Optional<Money> getActiveCost(Long providerSkuId) {
        return providerPriceRepository.findByProviderSkuIdAndEffectiveUntilIsNull(providerSkuId)
                .map(ProviderPrice::getProviderCost);
    }

    @Transactional
    public ProviderPrice setPrice(Long providerSkuId, Money providerCost, Instant effectiveFrom) {
        providerPriceRepository.findByProviderSkuIdAndEffectiveUntilIsNull(providerSkuId)
                .ifPresent(current -> providerPriceRepository.closeActive(current.getId(), effectiveFrom));

        int nextVersion = providerPriceRepository.findTopByProviderSkuIdOrderByPricingVersionDesc(providerSkuId)
                .map(p -> p.getPricingVersion() + 1)
                .orElse(1);

        return providerPriceRepository.save(new ProviderPrice(providerSkuId, nextVersion, providerCost, effectiveFrom));
    }
}
