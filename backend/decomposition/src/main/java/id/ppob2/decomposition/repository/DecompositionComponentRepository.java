package id.ppob2.decomposition.repository;

import id.ppob2.decomposition.domain.DecompositionComponent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecompositionComponentRepository extends JpaRepository<DecompositionComponent, Long> {
    List<DecompositionComponent> findByPatternIdIn(List<Long> patternIds);
}
