package id.ppob2.settlement.repository;

import id.ppob2.settlement.domain.SettlementPartnerAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementPartnerAllocationRepository extends JpaRepository<SettlementPartnerAllocation, Long> {

    List<SettlementPartnerAllocation> findBySettlementId(Long settlementId);

    /**
     * PRD Section 37.3's re-derivation-on-RESOLVED case: a settlement's allocation rows are
     * replaced wholesale, not updated in place, since a change in contributing amounts can change
     * which partners are even represented (Section 22.28's {@code UNIQUE(settlement_id,
     * partner_id)} would otherwise be racing an upsert). A bulk JPQL delete, same shape as {@code
     * ProviderPriceRepository.closeActive} elsewhere in this codebase.
     */
    @Modifying
    @Query("DELETE FROM SettlementPartnerAllocation a WHERE a.settlementId = :settlementId")
    void deleteBySettlementId(@Param("settlementId") Long settlementId);
}
