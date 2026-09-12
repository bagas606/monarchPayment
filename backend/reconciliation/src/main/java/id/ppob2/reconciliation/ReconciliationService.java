package id.ppob2.reconciliation;

import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.reconciliation.repository.ReconciliationRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 38.2: opens, investigates, and resolves `reconciliation` records (Section 22.23).
 * {@link #open} is {@code Propagation.MANDATORY} — same reasoning as {@code LedgerService.post}:
 * Section 38.2 states "every discrepancy is persisted to reconciliation" as an invariant, not a
 * best-effort follow-up, so it must commit atomically with whatever detected the discrepancy
 * (e.g. settlement ingestion) rather than in a separate transaction that could succeed
 * independently of, or fail silently after, the thing that triggered it.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository repository;

    public ReconciliationService(ReconciliationRepository repository) {
        this.repository = repository;
    }

    /** For callers (e.g. an `app`-layer orchestrator re-run for the same reference, such as an
     * authorized fulfillment retry) that must not open a second record while an earlier
     * discrepancy for the same {@code type} + {@code referenceId} is still unresolved. */
    public boolean hasOpenDiscrepancy(ReconciliationType type, Long referenceId) {
        return repository.existsByReconTypeAndReferenceIdAndStatusIn(
                type, referenceId, List.of(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Reconciliation open(ReconciliationType type, LocalDate reconDate, Long referenceId,
                                Money expectedValue, Money actualValue) {
        Money discrepancy = (expectedValue != null && actualValue != null) ? actualValue.subtract(expectedValue) : null;
        Reconciliation reconciliation = new Reconciliation(type, reconDate, referenceId, expectedValue, actualValue, discrepancy);
        return repository.save(reconciliation);
    }

    /** Unreachable from any path in this codebase yet — Admin Web (Section 40.8), the only
     * intended caller, isn't built. Exercised only by {@code ReconciliationServiceTest}. */
    @Transactional
    public boolean investigate(Long reconciliationId) {
        Reconciliation reconciliation = repository.findById(reconciliationId)
                .orElseThrow(() -> new IllegalStateException("reconciliation " + reconciliationId + " not found"));
        boolean applied = reconciliation.investigate();
        if (!applied) {
            log.warn("reconciliation {} was not OPEN (status={}) — cannot move to INVESTIGATING",
                    reconciliationId, reconciliation.getStatus());
        }
        return applied;
    }

    /** Same unreachable-until-Admin-Web caveat as {@link #investigate}. */
    @Transactional
    public boolean resolve(Long reconciliationId, Long resolvedBy) {
        Reconciliation reconciliation = repository.findById(reconciliationId)
                .orElseThrow(() -> new IllegalStateException("reconciliation " + reconciliationId + " not found"));
        boolean applied = reconciliation.resolve(resolvedBy, Instant.now());
        if (!applied) {
            log.warn("reconciliation {} was not INVESTIGATING (status={}) — cannot resolve directly from OPEN",
                    reconciliationId, reconciliation.getStatus());
        }
        return applied;
    }
}
