package id.ppob1.sim.repository;

import id.ppob1.sim.domain.ReconEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconEntryRepository extends JpaRepository<ReconEntry, Long> {

    List<ReconEntry> findByRunIdOrderByVerdictAscIdAsc(UUID runId);
}
