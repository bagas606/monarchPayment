package id.ppob2.decomposition;

import id.ppob2.pricing.UsageTrackingService;
import id.ppob2.routing.PatternCandidate;
import id.ppob2.routing.RoutingService;
import id.ppob2.sharedkernel.money.Money;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 33.2's {@code PAID -> DECOMPOSITION_SELECTED} trigger ("Routing module selects
 * eligible pattern") and its side effect ("Pattern usage counter incremented"), composed here:
 * `decomposition` depends on both `routing` and `pricing` (Section 20.2), so this is where the
 * lookup → score/filter → usage-bookkeeping pipeline is wired together for `order` to call.
 */
@Service
public class PatternSelectionOrchestrator {

    private final PatternLookupService patternLookupService;
    private final RoutingService routingService;
    private final UsageTrackingService usageTrackingService;

    public PatternSelectionOrchestrator(PatternLookupService patternLookupService,
                                         RoutingService routingService,
                                         UsageTrackingService usageTrackingService) {
        this.patternLookupService = patternLookupService;
        this.routingService = routingService;
        this.usageTrackingService = usageTrackingService;
    }

    /** @return the selected pattern_id, or empty if no eligible pattern was found — Section
     * 33.2's BR-DEC-exhaustion case, which the caller must handle as {@code PAID -> REFUND_PENDING}. */
    @Transactional
    public Optional<Long> selectPattern(Long productId, Money parentAmount) {
        var candidates = patternLookupService.findCandidates(productId, parentAmount);
        Optional<PatternCandidate> selected = routingService.selectBestEligible(candidates);

        selected.ifPresent(candidate -> {
            Map<Long, Integer> skuQuantities = new HashMap<>();
            candidate.componentSkuQuantities().forEach(cq ->
                    skuQuantities.merge(cq.providerSkuId(), cq.quantity(), Integer::sum));
            usageTrackingService.recordPatternSelected(candidate.patternId(), skuQuantities);
        });

        return selected.map(PatternCandidate::patternId);
    }
}
