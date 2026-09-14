package id.ppob2.settlement.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to the `settlement_partner_allocation` table, PRD Section 22.28. References {@code
 * partner.id} by plain {@code Long}, not a JPA relationship -- Section 20.2's dependency graph
 * grants `settlement` no edge to `partner`, the same precedent already established for
 * {@code ProviderPrice.providerSkuId} and {@code SkuUsage}/{@code PatternUsage} elsewhere in this
 * codebase: the FK lives in the migration only.
 *
 * <p>{@code netAmount} is stored, not solely derived from {@code grossAmount - feeAllocated} at
 * read time -- same defense-in-depth reasoning Section 22.28 gives: a stored, invariant-checked
 * value survives a future refactor of how the two components are computed without silently
 * drifting from what was actually persisted.
 */
@Entity
@Table(name = "settlement_partner_allocation")
public class SettlementPartnerAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "partner_id", nullable = false)
    private Long partnerId;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 0)
    private Money grossAmount;

    @Column(name = "fee_allocated", nullable = false, precision = 18, scale = 0)
    private Money feeAllocated;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 0)
    private Money netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_method", nullable = false, length = 16)
    private AllocationMethod allocationMethod;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected SettlementPartnerAllocation() {
    }

    public SettlementPartnerAllocation(Long settlementId, Long partnerId, Money grossAmount, Money feeAllocated,
                                        AllocationMethod allocationMethod) {
        this.settlementId = settlementId;
        this.partnerId = partnerId;
        this.grossAmount = grossAmount;
        this.feeAllocated = feeAllocated;
        this.netAmount = grossAmount.subtract(feeAllocated);
        this.allocationMethod = allocationMethod;
        this.computedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public Money getGrossAmount() {
        return grossAmount;
    }

    public Money getFeeAllocated() {
        return feeAllocated;
    }

    public Money getNetAmount() {
        return netAmount;
    }

    public AllocationMethod getAllocationMethod() {
        return allocationMethod;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
