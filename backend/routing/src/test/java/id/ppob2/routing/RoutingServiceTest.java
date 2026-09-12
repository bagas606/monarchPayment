package id.ppob2.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import id.ppob2.catalog.domain.Provider;
import id.ppob2.catalog.domain.ProviderSku;
import id.ppob2.catalog.repository.ProviderRepository;
import id.ppob2.catalog.repository.ProviderSkuRepository;
import id.ppob2.pricing.domain.PatternEconomics;
import id.ppob2.pricing.domain.SkuUsage;
import id.ppob2.pricing.repository.PatternEconomicsRepository;
import id.ppob2.pricing.repository.SkuUsageRepository;
import id.ppob2.sharedkernel.money.Money;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure logic test with mocked repositories — safe here because {@link RoutingService} only
 * reads, doing no flush-sensitive writes; contrast with this codebase's payment/order slices,
 * where the real bugs lived in transactional/JDBC behavior mocks cannot exercise.
 */
class RoutingServiceTest {

    private final ProviderSkuRepository providerSkuRepository = mock(ProviderSkuRepository.class);
    private final ProviderRepository providerRepository = mock(ProviderRepository.class);
    private final PatternEconomicsRepository patternEconomicsRepository = mock(PatternEconomicsRepository.class);
    private final SkuUsageRepository skuUsageRepository = mock(SkuUsageRepository.class);
    private final RoutingService routingService = new RoutingService(
            providerSkuRepository, providerRepository, patternEconomicsRepository, skuUsageRepository);

    @Test
    void picksHighestScoringEligiblePattern() throws Exception {
        ProviderSku sku1 = providerSku(1L, 10L, "ACTIVE", null);
        ProviderSku sku2 = providerSku(2L, 10L, "ACTIVE", null);
        Provider provider = provider(10L, "ACTIVE");

        given(providerSkuRepository.findByIdIn(anyList())).willReturn(List.of(sku1, sku2));
        given(providerRepository.findAllById(anyList())).willReturn(List.of(provider));
        given(skuUsageRepository.findByProviderSkuIdInAndUsageDate(anyList(), any())).willReturn(List.of());
        given(patternEconomicsRepository.findByPatternIdInAndSnapshotDate(anyList(), any())).willReturn(List.of(
                economics(100L, true, "50.0"),
                economics(200L, true, "90.0")));

        PatternCandidate low = new PatternCandidate(100L, List.of(new PatternCandidate.ComponentQuantity(1L, 1, Money.ZERO)));
        PatternCandidate high = new PatternCandidate(200L, List.of(new PatternCandidate.ComponentQuantity(2L, 1, Money.ZERO)));

        var result = routingService.selectBestEligible(List.of(low, high));

        assertThat(result).isPresent();
        assertThat(result.get().patternId()).isEqualTo(200L);
    }

    @Test
    void excludesPatternWithInactiveSku() throws Exception {
        ProviderSku inactiveSku = providerSku(1L, 10L, "DISABLED", null);
        Provider provider = provider(10L, "ACTIVE");

        given(providerSkuRepository.findByIdIn(anyList())).willReturn(List.of(inactiveSku));
        given(providerRepository.findAllById(anyList())).willReturn(List.of(provider));
        given(skuUsageRepository.findByProviderSkuIdInAndUsageDate(anyList(), any())).willReturn(List.of());
        given(patternEconomicsRepository.findByPatternIdInAndSnapshotDate(anyList(), any())).willReturn(List.of(
                economics(100L, true, "99.0")));

        PatternCandidate candidate = new PatternCandidate(100L, List.of(new PatternCandidate.ComponentQuantity(1L, 1, Money.ZERO)));

        var result = routingService.selectBestEligible(List.of(candidate));

        assertThat(result).isEmpty();
    }

    @Test
    void excludesPatternWhenEconomicsFlagsIneligible() throws Exception {
        ProviderSku sku = providerSku(1L, 10L, "ACTIVE", null);
        Provider provider = provider(10L, "ACTIVE");

        given(providerSkuRepository.findByIdIn(anyList())).willReturn(List.of(sku));
        given(providerRepository.findAllById(anyList())).willReturn(List.of(provider));
        given(skuUsageRepository.findByProviderSkuIdInAndUsageDate(anyList(), any())).willReturn(List.of());
        given(patternEconomicsRepository.findByPatternIdInAndSnapshotDate(anyList(), any())).willReturn(List.of(
                economics(100L, false, "99.0")));

        PatternCandidate candidate = new PatternCandidate(100L, List.of(new PatternCandidate.ComponentQuantity(1L, 1, Money.ZERO)));

        var result = routingService.selectBestEligible(List.of(candidate));

        assertThat(result).isEmpty();
    }

    @Test
    void excludesPatternExceedingSkuQuota() throws Exception {
        ProviderSku sku = providerSku(1L, 10L, "ACTIVE", 5);
        Provider provider = provider(10L, "ACTIVE");
        SkuUsage usage = skuUsage(1L, 5L);

        given(providerSkuRepository.findByIdIn(anyList())).willReturn(List.of(sku));
        given(providerRepository.findAllById(anyList())).willReturn(List.of(provider));
        given(skuUsageRepository.findByProviderSkuIdInAndUsageDate(anyList(), any())).willReturn(List.of(usage));
        given(patternEconomicsRepository.findByPatternIdInAndSnapshotDate(anyList(), any())).willReturn(List.of(
                economics(100L, true, "99.0")));

        PatternCandidate candidate = new PatternCandidate(100L, List.of(new PatternCandidate.ComponentQuantity(1L, 1, Money.ZERO)));

        var result = routingService.selectBestEligible(List.of(candidate));

        assertThat(result).isEmpty();
    }

    // --- test doubles: entities have no public constructors for test-only field population ---

    private static ProviderSku providerSku(long id, long providerId, String status, Integer quotaDaily) throws Exception {
        Constructor<ProviderSku> ctor = ProviderSku.class.getDeclaredConstructor(
                Long.class, Long.class, String.class, Money.class, String.class, Integer.class);
        ctor.setAccessible(true);
        ProviderSku sku = ctor.newInstance(providerId, 1L, "SKU-" + id, Money.of(1000L), status, quotaDaily);
        setId(sku, id);
        return sku;
    }

    private static Provider provider(long id, String status) throws Exception {
        Constructor<Provider> ctor = Provider.class.getDeclaredConstructor(String.class, String.class, String.class, int.class, Integer.class);
        ctor.setAccessible(true);
        Provider provider = ctor.newInstance("PROV-" + id, "Provider " + id, status, 8000, null);
        setId(provider, id);
        return provider;
    }

    private static PatternEconomics economics(long patternId, boolean eligible, String score) {
        return new PatternEconomics(patternId, LocalDate.now(), Money.of(0L), Money.of(0L), BigDecimal.ZERO,
                Money.of(0L), Money.of(0L), BigDecimal.ZERO, new BigDecimal(score), eligible);
    }

    private static SkuUsage skuUsage(long providerSkuId, long dailyUsage) throws Exception {
        Constructor<SkuUsage> ctor = SkuUsage.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        SkuUsage usage = ctor.newInstance();
        setField(usage, "providerSkuId", providerSkuId);
        setField(usage, "dailyUsage", dailyUsage);
        return usage;
    }

    private static void setId(Object entity, long id) throws Exception {
        setField(entity, "id", id);
    }

    private static void setField(Object entity, String fieldName, Object value) throws Exception {
        Field field = entity.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(entity, value);
    }
}
