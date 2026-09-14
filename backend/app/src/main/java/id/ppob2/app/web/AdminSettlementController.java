package id.ppob2.app.web;

import id.ppob2.settlement.domain.SettlementPartnerAllocation;
import id.ppob2.settlement.repository.SettlementPartnerAllocationRepository;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Section 41.8's "per-partner allocation breakdown" view. Read-only, so unlike {@code
 * SettlementIngestionController}/{@code AdminReconciliationController} this records nothing to
 * {@code audit_log} -- Section 43's "Recorded" requirement applies to admin *mutations*, and
 * nothing here changes state.
 *
 * <p>Gated behind the existing {@code settlement:ingest} permission (Section 42.1's seeded
 * permission set) rather than a new {@code settlement:view} permission, to avoid a second RBAC
 * migration (Section 42's V19 seeds only the four permission codes this codebase actually gates
 * something with) for what is, for now, a single read endpoint. If a broader settlement read
 * surface is built later (Section 41.8's other bullet points: expected/actual/fee views), revisit
 * this as its own {@code settlement:view} permission rather than growing this reuse further.
 */
@RestController
public class AdminSettlementController {

    private final SettlementPartnerAllocationRepository allocationRepository;

    public AdminSettlementController(SettlementPartnerAllocationRepository allocationRepository) {
        this.allocationRepository = allocationRepository;
    }

    @GetMapping("/admin/settlements/{id}/allocations")
    @PreAuthorize("hasAuthority('settlement:ingest')")
    public ResponseEntity<SettlementAllocationBreakdownResponse> allocations(@PathVariable("id") Long settlementId) {
        List<SettlementPartnerAllocation> allocations = allocationRepository.findBySettlementId(settlementId);

        Money reconcilingGrossTotal = allocations.stream()
                .map(SettlementPartnerAllocation::getGrossAmount)
                .reduce(Money.ZERO, Money::add);

        List<PartnerAllocationView> partners = allocations.stream()
                .map(a -> new PartnerAllocationView(a.getPartnerId(), a.getGrossAmount(), a.getFeeAllocated(),
                        a.getNetAmount(), a.getAllocationMethod().name(), a.getComputedAt().toString()))
                .toList();

        return ResponseEntity.ok(new SettlementAllocationBreakdownResponse(settlementId, partners, reconcilingGrossTotal));
    }

    /**
     * Section 41.8: "the view MUST also surface the reconciling total ... so a broken invariant is
     * visible to Finance, not just rejected silently at write time." {@code reconcilingGrossTotal}
     * is the sum of the rows actually returned here -- comparing it against the parent {@code
     * settlement.actual_amount} (fetched separately by the caller/UI) is how a Finance user would
     * notice a gap; this endpoint doesn't re-fetch {@code settlement} itself to keep this
     * controller's single dependency scoped to the allocation table it's named for.
     */
    public record SettlementAllocationBreakdownResponse(
            Long settlementId,
            List<PartnerAllocationView> partners,
            Money reconcilingGrossTotal
    ) {
    }

    public record PartnerAllocationView(
            Long partnerId,
            Money grossAmount,
            Money feeAllocated,
            Money netAmount,
            String allocationMethod,
            String computedAt
    ) {
    }
}
