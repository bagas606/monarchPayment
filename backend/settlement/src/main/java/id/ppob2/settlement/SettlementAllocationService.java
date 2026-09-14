package id.ppob2.settlement;

import id.ppob2.settlement.domain.AllocationMethod;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.settlement.domain.SettlementPartnerAllocation;
import id.ppob2.settlement.repository.SettlementPartnerAllocationRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 37.3: attributes one platform-aggregate {@code settlement} batch back across the
 * partner(s) whose payments contributed to it. Attribution only -- this never moves money or
 * decides a payout; it is a read-side record of "whose activity does this settlement represent,"
 * consumed by Admin Web (Section 41.8). {@code settlement}'s module graph (Section 20.2) grants no
 * edge to `order`/`partner`, so this class takes each partner's already-resolved contribution as a
 * plain {@code Map<Long, Money>} -- the same composition-root pattern {@code
 * SettlementIngestionOrchestrator} already uses to resolve {@code expected_amount} from `payment`.
 *
 * <p>Only {@link AllocationMethod#PRO_RATA} is implemented. {@code EXACT} would require the
 * Ayolinx settlement report to carry per-transaction lines -- Section 37.1 flags that format as
 * unverified, and this codebase has no data model for it; see {@link AllocationMethod}'s Javadoc.
 */
@Service
public class SettlementAllocationService {

    private final SettlementPartnerAllocationRepository allocationRepository;

    public SettlementAllocationService(SettlementPartnerAllocationRepository allocationRepository) {
        this.allocationRepository = allocationRepository;
    }

    /**
     * @param partnerExpectedAmounts each contributing partner's share of {@code
     *                                settlement.expectedAmount} (i.e. that partner's sum of {@code
     *                                SUCCESS} payments for the settlement date) -- MUST sum to
     *                                exactly {@code settlement.expectedAmount}; the caller (the
     *                                composition root resolving this from `payment`/`order`) is
     *                                responsible for that, since this class cannot see either
     *                                module to verify it independently.
     */
    @Transactional
    public List<SettlementPartnerAllocation> allocateProRata(Settlement settlement, Map<Long, Money> partnerExpectedAmounts) {
        allocationRepository.deleteBySettlementId(settlement.getId());

        if (partnerExpectedAmounts.isEmpty()) {
            // No SUCCESS payment for this settlement's date carried a resolvable partner_id --
            // nothing to attribute. Left as an empty allocation set rather than an error: an
            // unattributable settlement is a real (if currently unexpected, single-channel MVP)
            // state, not a computation failure.
            return List.of();
        }

        Money totalExpected = partnerExpectedAmounts.values().stream().reduce(Money.ZERO, Money::add);
        if (totalExpected.equals(Money.ZERO)) {
            throw new ApiException(ErrorCode.SETTLEMENT_ALLOCATION_INVALID,
                    "Cannot pro-rate settlement " + settlement.getId()
                            + ": contributing partners sum to a zero expected amount.");
        }

        Money actualAmount = settlement.getActualAmount() != null ? settlement.getActualAmount() : Money.ZERO;
        Money feeAmount = settlement.getFeeAmount() != null ? settlement.getFeeAmount() : Money.ZERO;

        Map<Long, Money> grossShares = distributeByLargestRemainder(actualAmount, partnerExpectedAmounts, totalExpected);
        Map<Long, Money> feeShares = distributeByLargestRemainder(feeAmount, partnerExpectedAmounts, totalExpected);

        // BR-REC-002's invariant, checked explicitly rather than trusted from the rounding
        // arithmetic alone -- a computation that cannot satisfy it is rejected, never persisted.
        Money grossSum = grossShares.values().stream().reduce(Money.ZERO, Money::add);
        Money feeSum = feeShares.values().stream().reduce(Money.ZERO, Money::add);
        if (!grossSum.equals(actualAmount) || !feeSum.equals(feeAmount)) {
            throw new ApiException(ErrorCode.SETTLEMENT_ALLOCATION_INVALID,
                    "Pro-rata allocation for settlement " + settlement.getId()
                            + " does not sum exactly to actual_amount/fee_amount.");
        }

        List<SettlementPartnerAllocation> allocations = partnerExpectedAmounts.keySet().stream()
                .sorted()
                .map(partnerId -> new SettlementPartnerAllocation(settlement.getId(), partnerId,
                        grossShares.get(partnerId), feeShares.get(partnerId), AllocationMethod.PRO_RATA))
                .toList();

        return allocationRepository.saveAll(allocations);
    }

    /**
     * Largest-remainder apportionment of {@code pool} across {@code weights}, proportional to each
     * partner's weight over {@code totalWeight}. Section 37.3: floor each partner's raw share
     * ({@code weight * pool / totalWeight}), then hand the leftover whole units -- {@code pool}
     * minus the sum of the floors -- one at a time to the partners with the largest fractional
     * remainders (ties broken by ascending partner id, for determinism), until the distributed
     * total matches {@code pool} exactly.
     *
     * <p>Exact integer arithmetic throughout ({@link BigInteger#divideAndRemainder}), never
     * floating point, per Section 21.1. The leftover-unit count is mathematically guaranteed to be
     * strictly less than the number of partners (each remainder is in {@code [0, totalWeight)}, and
     * {@code totalWeight} equals the exact sum of the weights by construction), so every leftover
     * unit lands on a distinct partner in {@code byRemainderDesc} -- this is not merely assumed
     * bounded, it follows from {@code sum(weight_i) = totalWeight}.
     */
    private Map<Long, Money> distributeByLargestRemainder(Money pool, Map<Long, Money> weights, Money totalWeight) {
        BigInteger poolAmount = pool.toBigInteger();
        BigInteger total = totalWeight.toBigInteger();

        Map<Long, BigInteger> floors = new LinkedHashMap<>();
        Map<Long, BigInteger> remainders = new LinkedHashMap<>();
        BigInteger distributedSoFar = BigInteger.ZERO;

        for (Map.Entry<Long, Money> entry : weights.entrySet()) {
            BigInteger numerator = entry.getValue().toBigInteger().multiply(poolAmount);
            BigInteger[] divRem = numerator.divideAndRemainder(total);
            floors.put(entry.getKey(), divRem[0]);
            remainders.put(entry.getKey(), divRem[1]);
            distributedSoFar = distributedSoFar.add(divRem[0]);
        }

        BigInteger leftover = poolAmount.subtract(distributedSoFar);

        List<Long> byRemainderDesc = remainders.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = b.getValue().compareTo(a.getValue());
                    return cmp != 0 ? cmp : Long.compare(a.getKey(), b.getKey());
                })
                .map(Map.Entry::getKey)
                .toList();

        for (int i = 0; i < leftover.intValueExact(); i++) {
            Long partnerId = byRemainderDesc.get(i);
            floors.merge(partnerId, BigInteger.ONE, BigInteger::add);
        }

        Map<Long, Money> result = new LinkedHashMap<>();
        floors.forEach((partnerId, amount) -> result.put(partnerId, Money.of(amount)));
        return result;
    }
}
