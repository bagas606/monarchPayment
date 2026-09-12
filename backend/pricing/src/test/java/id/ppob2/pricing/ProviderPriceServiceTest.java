package id.ppob2.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import id.ppob2.pricing.domain.ProviderPrice;
import id.ppob2.pricing.repository.ProviderPriceRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Mocked-repository test — proves the version-numbering and close-before-insert sequencing
 * logic, not the database write path (the partial unique index is a real Postgres constraint,
 * verified separately against real Postgres — see backend/README.md's provider_price section).
 */
class ProviderPriceServiceTest {

    private final ProviderPriceRepository repository = mock(ProviderPriceRepository.class);
    private final ProviderPriceService service = new ProviderPriceService(repository);

    @Test
    void firstPriceForASkuStartsAtVersionOneWithNothingToClose() {
        given(repository.findByProviderSkuIdAndEffectiveUntilIsNull(1L)).willReturn(Optional.empty());
        given(repository.findTopByProviderSkuIdOrderByPricingVersionDesc(1L)).willReturn(Optional.empty());
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));

        ProviderPrice result = service.setPrice(1L, Money.of(1000L), Instant.now());

        assertThat(result.getPricingVersion()).isEqualTo(1);
        verify(repository, org.mockito.Mockito.never()).closeActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repricingClosesThePreviousActiveVersionBeforeInsertingTheNext() {
        ProviderPrice current = new ProviderPrice(1L, 3, Money.of(1000L), Instant.now().minusSeconds(3600));
        given(repository.findByProviderSkuIdAndEffectiveUntilIsNull(1L)).willReturn(Optional.of(current));
        given(repository.findTopByProviderSkuIdOrderByPricingVersionDesc(1L)).willReturn(Optional.of(current));
        given(repository.save(org.mockito.ArgumentMatchers.any())).willAnswer(inv -> inv.getArgument(0));
        Instant effectiveFrom = Instant.now();

        ProviderPrice result = service.setPrice(1L, Money.of(1200L), effectiveFrom);

        assertThat(result.getPricingVersion()).isEqualTo(4);
        ArgumentCaptor<Instant> closedAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).closeActive(org.mockito.ArgumentMatchers.isNull(), closedAt.capture());
        assertThat(closedAt.getValue()).isEqualTo(effectiveFrom);
    }
}
