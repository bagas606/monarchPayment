package id.ppob2.decomposition.repository;

import id.ppob2.decomposition.domain.DecompositionPattern;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecompositionPatternRepository extends JpaRepository<DecompositionPattern, Long> {
    List<DecompositionPattern> findByParentAmountAndStructuralStatusAndGenerationId(
            Money parentAmount, String structuralStatus, Long generationId);
}
