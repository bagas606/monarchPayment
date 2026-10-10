package id.ppob2.pricing;

import id.ppob2.pricing.repository.PatternUsageRepository;
import id.ppob2.pricing.repository.SkuUsageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 32.1/33.2: records a pattern selection's usage counters.
 *
 * <p>The clock is injected rather than {@code LocalDate.now()} because this is the <b>write</b>
 * side of a pair: {@code RoutingService} reads these same counters to enforce
 * {@code provider_sku.quota_daily}. If the two ever keyed different days, a quota would be checked
 * against a counter nobody was incrementing — enforcement that silently stops enforcing. Both now
 * take the same {@code BusinessTimeConfig} bean, so they cannot disagree, and
 * {@code UsageTrackingServiceTest} pins them to one fixed clock just before midnight to prove it.
 */
@Service
public class UsageTrackingService {

    private final Clock businessClock;
    private final PatternUsageRepository patternUsageRepository;
    private final SkuUsageRepository skuUsageRepository;

    public UsageTrackingService(Clock businessClock, PatternUsageRepository patternUsageRepository,
                                 SkuUsageRepository skuUsageRepository) {
        this.businessClock = businessClock;
        this.patternUsageRepository = patternUsageRepository;
        this.skuUsageRepository = skuUsageRepository;
    }

    /** The business day these counters are keyed by — the same one {@code RoutingService} reads. */
    public LocalDate today() {
        return LocalDate.now(businessClock);
    }

    /** @param skuQuantities provider_sku_id -> quantity consumed by the selected pattern's components. */
    @Transactional
    public void recordPatternSelected(Long patternId, Map<Long, Integer> skuQuantities) {
        LocalDate today = today();
        patternUsageRepository.increment(patternId, today);
        skuQuantities.forEach((providerSkuId, quantity) -> skuUsageRepository.increment(providerSkuId, today, quantity));
    }
}
