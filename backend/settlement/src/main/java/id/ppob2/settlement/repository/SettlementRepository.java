package id.ppob2.settlement.repository;

import id.ppob2.settlement.domain.Settlement;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    boolean existsBySettlementDate(LocalDate settlementDate);
}
