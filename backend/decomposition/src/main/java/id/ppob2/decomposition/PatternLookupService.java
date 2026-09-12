package id.ppob2.decomposition;

import id.ppob2.catalog.domain.ProviderSku;
import id.ppob2.catalog.repository.ProviderSkuRepository;
import id.ppob2.decomposition.domain.DecompositionComponent;
import id.ppob2.decomposition.domain.DecompositionPattern;
import id.ppob2.decomposition.repository.DecompositionComponentRepository;
import id.ppob2.decomposition.repository.DecompositionPatternRepository;
import id.ppob2.decomposition.repository.PatternGenerationRepository;
import id.ppob2.routing.PatternCandidate;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * PRD Section 28.3: this only *looks up* pre-generated patterns — no combinatorial search runs
 * here. Section 31.1's cached candidate list is Redis-backed in the target design; this queries
 * Postgres directly, the same simplification this codebase already made for nonce storage
 * (Section 19.1 designates Redis there too).
 *
 * <p>Neither {@code pattern_generation} (Section 22.9) nor {@code decomposition_pattern}
 * (Section 22.10) carries a {@code product_id} column, even though Section 29.2 states patterns
 * are generated "independently" per {@code (product, parent_amount)} pair — a real gap in the
 * documented schema. This filters candidates by product after the fact, via each pattern's
 * components' {@code provider_sku.product_id}, rather than inventing an undocumented column.
 *
 * <p>Section 28.2's hard invariant — {@code SUM(face_value × quantity) == parent_amount}, "no
 * under-allocation, no over-allocation, no silent rounding" — is enforced here, as the named
 * "runtime assertion before a pattern is ever attached to a parent order": any candidate whose
 * components' *current* {@code provider_sku.face_value} no longer sums to the pattern's
 * {@code parent_amount} is dropped and logged loudly, never handed to {@link
 * id.ppob2.routing.RoutingService}. Checked against the live SKU value rather than {@code
 * decomposition_component.face_value}'s generation-time snapshot, since a mismatch between the
 * two is itself the structural-drift signal Section 30.3 describes — checking the snapshot only
 * tells you the pattern was self-consistent when generated, not whether it still protects money
 * today.
 */
@Service
public class PatternLookupService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PatternLookupService.class);

    private final PatternGenerationRepository patternGenerationRepository;
    private final DecompositionPatternRepository decompositionPatternRepository;
    private final DecompositionComponentRepository decompositionComponentRepository;
    private final ProviderSkuRepository providerSkuRepository;

    public PatternLookupService(PatternGenerationRepository patternGenerationRepository,
                                 DecompositionPatternRepository decompositionPatternRepository,
                                 DecompositionComponentRepository decompositionComponentRepository,
                                 ProviderSkuRepository providerSkuRepository) {
        this.patternGenerationRepository = patternGenerationRepository;
        this.decompositionPatternRepository = decompositionPatternRepository;
        this.decompositionComponentRepository = decompositionComponentRepository;
        this.providerSkuRepository = providerSkuRepository;
    }

    public List<PatternCandidate> findCandidates(Long productId, Money parentAmount) {
        var activeGeneration = patternGenerationRepository.findByStatus("ACTIVE");
        if (activeGeneration.isEmpty()) {
            return List.of();
        }

        List<DecompositionPattern> patterns = decompositionPatternRepository
                .findByParentAmountAndStructuralStatusAndGenerationId(parentAmount, "VALID", activeGeneration.get().getId());
        if (patterns.isEmpty()) {
            return List.of();
        }

        List<Long> patternIds = patterns.stream().map(DecompositionPattern::getId).toList();
        List<DecompositionComponent> components = decompositionComponentRepository.findByPatternIdIn(patternIds);
        Map<Long, List<DecompositionComponent>> componentsByPattern = components.stream()
                .collect(Collectors.groupingBy(DecompositionComponent::getPatternId));

        List<Long> skuIds = components.stream().map(DecompositionComponent::getProviderSkuId).distinct().toList();
        Map<Long, ProviderSku> skuById = providerSkuRepository.findByIdIn(skuIds).stream()
                .collect(Collectors.toMap(ProviderSku::getId, Function.identity()));

        Map<Long, Money> parentAmountByPattern = patterns.stream()
                .collect(Collectors.toMap(DecompositionPattern::getId, DecompositionPattern::getParentAmount));

        return patternIds.stream()
                .map(patternId -> toCandidate(patternId, componentsByPattern.getOrDefault(patternId, List.of()), skuById))
                .filter(candidate -> belongsToProduct(candidate, skuById, productId))
                .filter(candidate -> satisfiesAllocationInvariant(candidate, parentAmountByPattern.get(candidate.patternId())))
                .toList();
    }

    private PatternCandidate toCandidate(Long patternId, List<DecompositionComponent> components, Map<Long, ProviderSku> skuById) {
        List<PatternCandidate.ComponentQuantity> quantities = components.stream()
                .map(c -> {
                    ProviderSku sku = skuById.get(c.getProviderSkuId());
                    Money currentFaceValue = sku != null ? sku.getFaceValue() : null;
                    return new PatternCandidate.ComponentQuantity(c.getProviderSkuId(), c.getQuantity(), currentFaceValue);
                })
                .toList();
        return new PatternCandidate(patternId, quantities);
    }

    private boolean satisfiesAllocationInvariant(PatternCandidate candidate, Money parentAmount) {
        if (candidate.componentSkuQuantities().isEmpty()) {
            log.warn("Pattern {} has no decomposition_component rows at all (partial generation or bad seed data?) "
                            + "— dropping candidate rather than treating an empty sum as a valid allocation",
                    candidate.patternId());
            return false;
        }
        Money sum = Money.ZERO;
        for (PatternCandidate.ComponentQuantity cq : candidate.componentSkuQuantities()) {
            if (cq.currentFaceValue() == null) {
                log.warn("Pattern {} references provider_sku {} with no resolvable face_value — dropping candidate",
                        candidate.patternId(), cq.providerSkuId());
                return false;
            }
            sum = sum.add(cq.currentFaceValue().multiply(cq.quantity()));
        }
        if (!sum.equals(parentAmount)) {
            log.warn("Section 28.2 allocation invariant violated for pattern {}: components sum to {} but parent_amount is {} "
                            + "— dropping candidate rather than attaching a mis-valued pattern to an order",
                    candidate.patternId(), sum, parentAmount);
            return false;
        }
        return true;
    }

    private boolean belongsToProduct(PatternCandidate candidate, Map<Long, ProviderSku> skuById, Long productId) {
        return candidate.componentSkuQuantities().stream()
                .allMatch(cq -> {
                    ProviderSku sku = skuById.get(cq.providerSkuId());
                    return sku != null && productId.equals(sku.getProductId());
                });
    }
}
