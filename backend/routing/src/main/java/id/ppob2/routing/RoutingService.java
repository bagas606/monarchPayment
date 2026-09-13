package id.ppob2.routing;

import id.ppob2.catalog.domain.Provider;
import id.ppob2.catalog.domain.ProviderSku;
import id.ppob2.catalog.repository.ProviderRepository;
import id.ppob2.catalog.repository.ProviderSkuRepository;
import id.ppob2.pricing.domain.PatternEconomics;
import id.ppob2.pricing.domain.SkuUsage;
import id.ppob2.pricing.repository.PatternEconomicsRepository;
import id.ppob2.pricing.repository.SkuUsageRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * PRD Section 31.1/31.2: filters candidate patterns down to the eligible set and picks the
 * highest-scored one. This deliberately does NOT compute Section 31.3's weighted scoring
 * formula — that formula is the (not-yet-built) offline daily re-scoring job's output, already
 * sitting in {@code pattern_economics.score} (Section 30.1: "Daily Economic Snapshot ...
 * recomputed daily"). Runtime routing's job per Section 31.1 is to read that pre-computed score
 * from a cached candidate list, not recompute it per request.
 *
 * <p>One Section 31.2 criterion is simplified here, flagged: provider-level aggregate quota isn't
 * enforced (Section 22.5's `provider` table has no `quota_daily` column, only
 * `rate_limit_per_min`, which is a rate limit, not a daily quota — provider-level daily quota
 * would need to be an aggregate over its SKUs, not implemented in this slice).
 *
 * <p>Picking the single highest pre-computed score deterministically, with no runtime tie-break
 * among near-ties, is correct per Section 31.1 — not a simplification. "Avoid concentrating all
 * volume on one provider" is Section 31.3's {@code w_loadbalance * load_balance_score} term
 * <em>inside</em> the composite score {@code pattern_economics.score} already holds — a scoring
 * <em>input</em> the (not-yet-built) offline daily re-score job is responsible for computing, not
 * something runtime routing recomputes or second-guesses. Layering a runtime tie-break on top
 * would double-count load balancing through two uncoordinated mechanisms once that job exists.
 */
@Service
public class RoutingService {

    private final ProviderSkuRepository providerSkuRepository;
    private final ProviderRepository providerRepository;
    private final PatternEconomicsRepository patternEconomicsRepository;
    private final SkuUsageRepository skuUsageRepository;

    public RoutingService(ProviderSkuRepository providerSkuRepository,
                           ProviderRepository providerRepository,
                           PatternEconomicsRepository patternEconomicsRepository,
                           SkuUsageRepository skuUsageRepository) {
        this.providerSkuRepository = providerSkuRepository;
        this.providerRepository = providerRepository;
        this.patternEconomicsRepository = patternEconomicsRepository;
        this.skuUsageRepository = skuUsageRepository;
    }

    public Optional<PatternCandidate> selectBestEligible(List<PatternCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now();

        List<Long> skuIds = candidates.stream()
                .flatMap(c -> c.componentSkuQuantities().stream())
                .map(PatternCandidate.ComponentQuantity::providerSkuId)
                .distinct()
                .toList();
        Map<Long, ProviderSku> skuById = providerSkuRepository.findByIdIn(skuIds).stream()
                .collect(Collectors.toMap(ProviderSku::getId, Function.identity()));
        Map<Long, Provider> providerById = providerRepository.findAllById(
                        skuById.values().stream().map(ProviderSku::getProviderId).distinct().toList()).stream()
                .collect(Collectors.toMap(Provider::getId, Function.identity()));
        Map<Long, SkuUsage> usageBySku = skuUsageRepository.findByProviderSkuIdInAndUsageDate(skuIds, today).stream()
                .collect(Collectors.toMap(SkuUsage::getProviderSkuId, Function.identity()));

        List<Long> patternIds = candidates.stream().map(PatternCandidate::patternId).toList();
        Map<Long, PatternEconomics> economicsByPattern = patternEconomicsRepository
                .findByPatternIdInAndSnapshotDate(patternIds, today).stream()
                .collect(Collectors.toMap(PatternEconomics::getPatternId, Function.identity()));

        return candidates.stream()
                .filter(c -> isEligible(c, skuById, providerById, usageBySku, economicsByPattern))
                .max(Comparator.comparing(c -> economicsByPattern.get(c.patternId()).getScore()));
    }

    private boolean isEligible(PatternCandidate candidate,
                                Map<Long, ProviderSku> skuById,
                                Map<Long, Provider> providerById,
                                Map<Long, SkuUsage> usageBySku,
                                Map<Long, PatternEconomics> economicsByPattern) {
        PatternEconomics economics = economicsByPattern.get(candidate.patternId());
        if (economics == null || !economics.isEligible()) {
            return false;
        }

        return candidate.componentSkuQuantities().stream().allMatch(cq -> {
            ProviderSku sku = skuById.get(cq.providerSkuId());
            if (sku == null || !sku.isActive()) {
                return false;
            }
            Provider provider = providerById.get(sku.getProviderId());
            if (provider == null || !provider.isRoutable()) {
                return false;
            }
            if (sku.getQuotaDaily() != null) {
                long usedSoFar = Optional.ofNullable(usageBySku.get(sku.getId())).map(SkuUsage::getDailyUsage).orElse(0L);
                if (usedSoFar + cq.quantity() > sku.getQuotaDaily()) {
                    return false;
                }
            }
            return true;
        });
    }
}
