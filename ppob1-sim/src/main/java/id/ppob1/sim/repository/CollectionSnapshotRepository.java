package id.ppob1.sim.repository;

import id.ppob1.sim.domain.CollectionSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionSnapshotRepository extends JpaRepository<CollectionSnapshot, Long> {

    /** The latest snapshot for a date. Ordered by {@code id}, not {@code computed_at}: two
     * snapshots taken in the same clock tick would be ambiguous by timestamp, and {@code id} is a
     * total order on an append-only table. */
    Optional<CollectionSnapshot> findFirstByBusinessDateOrderByIdDesc(LocalDate businessDate);

    List<CollectionSnapshot> findAllByOrderByIdDesc();
}
