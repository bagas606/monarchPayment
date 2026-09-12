package id.ppob2.decomposition;

import id.ppob2.catalog.domain.ProviderSku;
import id.ppob2.catalog.repository.ProviderSkuRepository;
import id.ppob2.decomposition.domain.DecompositionComponent;
import id.ppob2.decomposition.repository.DecompositionComponentRepository;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Exposes a selected pattern's components to `order` (Section 20.2 grants {@code order ->
 * decomposition}) so `order` can create {@code child_order} rows (Section 22.16) once a pattern
 * is attached — separate from {@link PatternLookupService}, which serves candidate lookup
 * before selection, not the post-selection expansion into fulfillable units.
 *
 * <p>{@code PatternComponentDto.faceValue} is resolved from the live {@code catalog.provider_sku
 * .face_value} (Section 22.6: "Value contribution toward parent_amount"), not from {@code
 * decomposition_component.face_value}'s generation-time snapshot — same authoritative source
 * {@link PatternLookupService#satisfiesAllocationInvariant} already trusts for this exact
 * invariant, for the same reason: the snapshot's semantics for {@code quantity > 1} (per-unit vs.
 * already-multiplied) are undefined by any constraint or established precedent in this codebase,
 * whereas {@code provider_sku.face_value} is unambiguously per-unit. By the time this method
 * runs, {@code PatternLookupService} has already verified this pattern's live-value sum equals
 * {@code parent_amount}, so treating it as authoritative here is safe.
 */
@Service
public class PatternComponentQueryService {

    private final DecompositionComponentRepository decompositionComponentRepository;
    private final ProviderSkuRepository providerSkuRepository;

    public PatternComponentQueryService(DecompositionComponentRepository decompositionComponentRepository,
                                         ProviderSkuRepository providerSkuRepository) {
        this.decompositionComponentRepository = decompositionComponentRepository;
        this.providerSkuRepository = providerSkuRepository;
    }

    public List<PatternComponentDto> getComponents(Long patternId) {
        List<DecompositionComponent> components = decompositionComponentRepository.findByPatternIdIn(List.of(patternId));

        List<Long> skuIds = components.stream().map(DecompositionComponent::getProviderSkuId).distinct().toList();
        Map<Long, Money> faceValueBySkuId = providerSkuRepository.findByIdIn(skuIds).stream()
                .collect(Collectors.toMap(ProviderSku::getId, ProviderSku::getFaceValue));

        return components.stream()
                .map(c -> {
                    Money faceValue = faceValueBySkuId.get(c.getProviderSkuId());
                    if (faceValue == null) {
                        throw new IllegalStateException(
                                "provider_sku " + c.getProviderSkuId() + " for pattern " + patternId + " has no resolvable face_value");
                    }
                    return new PatternComponentDto(c.getProviderSkuId(), c.getQuantity(), faceValue);
                })
                .toList();
    }
}
