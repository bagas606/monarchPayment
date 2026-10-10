package id.ppob2.reconciliation.repository;

import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * {@link JpaSpecificationExecutor} is here for {@code ReconciliationQueryService}'s filtered read
 * (Section 41.9) and not for want of a derived query: the filter set is a power set — any
 * combination of type, status, reference and date range may be absent — and the derived-query or
 * {@code @Query} alternative is a {@code :param IS NULL OR column = :param} chain, which on
 * Postgres hands Hibernate an untyped {@code null} bind for the enum and {@code LocalDate}
 * parameters and fails at the driver with "could not determine data type of parameter". A
 * specification adds only the predicates that have a value, so an all-absent filter emits no
 * {@code WHERE} clause at all instead of five null comparisons.
 */
public interface ReconciliationRepository extends JpaRepository<Reconciliation, Long>,
        JpaSpecificationExecutor<Reconciliation> {

    /** Used to guard against opening a duplicate record for the same {@code recon_type} +
     * {@code reference_id} — e.g. an authorized retry re-triggering the same detection path
     * while an earlier discrepancy for that reference is still OPEN/INVESTIGATING. */
    boolean existsByReconTypeAndReferenceIdAndStatusIn(ReconciliationType reconType, Long referenceId, List<ReconciliationStatus> statuses);
}
