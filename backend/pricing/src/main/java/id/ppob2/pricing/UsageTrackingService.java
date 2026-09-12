package id.ppob2.pricing;

import id.ppob2.pricing.repository.PatternUsageRepository;
import id.ppob2.pricing.repository.SkuUsageRepository;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PRD Section 32.1/33.2: records a pattern selection's usage counters. */
@Service
public class UsageTrackingService {

    private final PatternUsageRepository patternUsageRepository;
    private final SkuUsageRepository skuUsageRepository;

    public UsageTrackingService(PatternUsageRepository patternUsageRepository, SkuUsageRepository skuUsageRepository) {
        this.patternUsageRepository = patternUsageRepository;
        this.skuUsageRepository = skuUsageRepository;
    }

    /** @param skuQuantities provider_sku_id -> quantity consumed by the selected pattern's components. */
    @Transactional
    public void recordPatternSelected(Long patternId, Map<Long, Integer> skuQuantities) {
        LocalDate today = LocalDate.now();
        patternUsageRepository.increment(patternId, today);
        skuQuantities.forEach((providerSkuId, quantity) -> skuUsageRepository.increment(providerSkuId, today, quantity));
    }
}
