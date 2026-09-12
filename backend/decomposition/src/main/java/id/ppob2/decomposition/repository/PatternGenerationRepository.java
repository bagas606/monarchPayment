package id.ppob2.decomposition.repository;

import id.ppob2.decomposition.domain.PatternGeneration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatternGenerationRepository extends JpaRepository<PatternGeneration, Long> {
    Optional<PatternGeneration> findByStatus(String status);
}
