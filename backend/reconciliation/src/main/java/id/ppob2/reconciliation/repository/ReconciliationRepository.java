package id.ppob2.reconciliation.repository;

import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRepository extends JpaRepository<Reconciliation, Long> {

    /** Used to guard against opening a duplicate record for the same {@code recon_type} +
     * {@code reference_id} — e.g. an authorized retry re-triggering the same detection path
     * while an earlier discrepancy for that reference is still OPEN/INVESTIGATING. */
    boolean existsByReconTypeAndReferenceIdAndStatusIn(ReconciliationType reconType, Long referenceId, List<ReconciliationStatus> statuses);
}
