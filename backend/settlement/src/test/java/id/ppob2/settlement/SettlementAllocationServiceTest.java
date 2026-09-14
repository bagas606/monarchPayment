package id.ppob2.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import id.ppob2.settlement.domain.AllocationMethod;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.settlement.domain.SettlementPartnerAllocation;
import id.ppob2.settlement.domain.SettlementStatus;
import id.ppob2.settlement.repository.SettlementPartnerAllocationRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettlementAllocationServiceTest {

    private final SettlementPartnerAllocationRepository allocationRepository = mock(SettlementPartnerAllocationRepository.class);
    private final SettlementAllocationService service = new SettlementAllocationService(allocationRepository);

    @Test
    void evenlyDivisibleSplitNeedsNoRemainderDistribution() {
        Settlement settlement = settlement(Money.of(100L), Money.of(100L), Money.of(10L));
        given(allocationRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        Map<Long, Money> weights = new LinkedHashMap<>();
        weights.put(1L, Money.of(50L));
        weights.put(2L, Money.of(50L));

        List<SettlementPartnerAllocation> result = service.allocateProRata(settlement, weights);

        assertThat(result).hasSize(2);
        assertThat(byPartner(result, 1L).getGrossAmount()).isEqualTo(Money.of(50L));
        assertThat(byPartner(result, 2L).getGrossAmount()).isEqualTo(Money.of(50L));
        assertThat(byPartner(result, 1L).getFeeAllocated()).isEqualTo(Money.of(5L));
        assertThat(byPartner(result, 2L).getFeeAllocated()).isEqualTo(Money.of(5L));
        assertThat(result).allMatch(a -> a.getAllocationMethod() == AllocationMethod.PRO_RATA);
    }

    @Test
    void unevenSplitDistributesLeftoverUnitsByLargestRemainderWithAscendingIdTieBreak() {
        // 100 / 3 partners with equal weight (1,1,1): raw share 33.33 each, floor 33, 1 unit left
        // over. All three remainders tie (100 mod 3 == 1 for each), so the leftover unit goes to
        // the lowest partner_id, per the documented tie-break rule.
        Settlement settlement = settlement(Money.of(100L), Money.of(100L), Money.of(10L));
        given(allocationRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        Map<Long, Money> weights = new LinkedHashMap<>();
        weights.put(3L, Money.of(1L));
        weights.put(1L, Money.of(1L));
        weights.put(2L, Money.of(1L));

        List<SettlementPartnerAllocation> result = service.allocateProRata(settlement, weights);

        assertThat(byPartner(result, 1L).getGrossAmount()).isEqualTo(Money.of(34L));
        assertThat(byPartner(result, 2L).getGrossAmount()).isEqualTo(Money.of(33L));
        assertThat(byPartner(result, 3L).getGrossAmount()).isEqualTo(Money.of(33L));
        assertThat(byPartner(result, 1L).getFeeAllocated()).isEqualTo(Money.of(4L));
        assertThat(byPartner(result, 2L).getFeeAllocated()).isEqualTo(Money.of(3L));
        assertThat(byPartner(result, 3L).getFeeAllocated()).isEqualTo(Money.of(3L));

        // BR-REC-002's invariant: sums must equal actual_amount/fee_amount exactly.
        Money grossSum = result.stream().map(SettlementPartnerAllocation::getGrossAmount).reduce(Money.ZERO, Money::add);
        Money feeSum = result.stream().map(SettlementPartnerAllocation::getFeeAllocated).reduce(Money.ZERO, Money::add);
        assertThat(grossSum).isEqualTo(settlement.getActualAmount());
        assertThat(feeSum).isEqualTo(settlement.getFeeAmount());

        assertThat(byPartner(result, 1L).getNetAmount()).isEqualTo(Money.of(30L));
        assertThat(byPartner(result, 2L).getNetAmount()).isEqualTo(Money.of(30L));
        assertThat(byPartner(result, 3L).getNetAmount()).isEqualTo(Money.of(30L));
    }

    @Test
    void singlePartnerReceivesTheFullSettlement() {
        Settlement settlement = settlement(Money.of(77777L), Money.of(77777L), Money.of(1234L));
        given(allocationRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        List<SettlementPartnerAllocation> result = service.allocateProRata(settlement, Map.of(9L, Money.of(77777L)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGrossAmount()).isEqualTo(Money.of(77777L));
        assertThat(result.get(0).getFeeAllocated()).isEqualTo(Money.of(1234L));
    }

    @Test
    void noContributingPartnersProducesAnEmptyAllocationRatherThanAnError() {
        Settlement settlement = settlement(Money.of(0L), Money.of(0L), Money.of(0L));

        List<SettlementPartnerAllocation> result = service.allocateProRata(settlement, Map.of());

        assertThat(result).isEmpty();
        verify(allocationRepository).deleteBySettlementId(any());
        verify(allocationRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    void zeroTotalExpectedAcrossNonEmptyPartnersIsRejectedRatherThanDividingByZero() {
        Settlement settlement = settlement(Money.of(100L), Money.of(100L), Money.of(10L));

        assertThatThrownBy(() -> service.allocateProRata(settlement, Map.of(1L, Money.ZERO, 2L, Money.ZERO)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.SETTLEMENT_ALLOCATION_INVALID);
    }

    private static Settlement settlement(Money expected, Money actual, Money fee) {
        return new Settlement(LocalDate.of(2026, 9, 14), "BATCH-1", expected, actual, fee, SettlementStatus.MATCHED);
    }

    private static SettlementPartnerAllocation byPartner(List<SettlementPartnerAllocation> allocations, Long partnerId) {
        return allocations.stream()
                .filter(a -> a.getPartnerId().equals(partnerId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no allocation for partner " + partnerId));
    }
}
