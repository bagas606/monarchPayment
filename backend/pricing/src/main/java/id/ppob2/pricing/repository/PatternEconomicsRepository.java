package id.ppob2.pricing.repository;

import id.ppob2.pricing.domain.PatternEconomics;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatternEconomicsRepository extends JpaRepository<PatternEconomics, Long> {
    List<PatternEconomics> findByPatternIdInAndSnapshotDate(List<Long> patternIds, LocalDate snapshotDate);

    /** The projection in force at (or before) a given date — used to compare against, e.g., an
     * order's actual outcome using the snapshot that was current when the order was placed, not
     * a later re-scoring run's snapshot, which would retroactively redefine "expected" for an
     * order already settled. */
    Optional<PatternEconomics> findTopByPatternIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
            Long patternId, LocalDate onOrBefore);
}
