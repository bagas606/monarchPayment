package id.ppob2.ledger.repository;

import id.ppob2.ledger.domain.LedgerEntry;
import id.ppob2.ledger.domain.LedgerType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Extends {@link JpaRepository} for the free query methods, but nothing in this codebase should
 * ever call its inherited {@code deleteXxx}/{@code save}-over-an-existing-id paths on a
 * {@code LedgerEntry} — {@link id.ppob2.ledger.LedgerService#post} is the only sanctioned way to
 * write a row, and the database-level trigger (see {@code V10__ledger.sql}) is what actually
 * stops a mistake here from succeeding, not this comment.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** Used by {@code MarginReconciliationOrchestrator} to sum the actual provider cost really
     * debited for a set of {@code provider_transaction} ids — the authoritative record of what
     * was paid, rather than re-deriving it from the (possibly since-repriced) current
     * {@code provider_price}. */
    List<LedgerEntry> findByLedgerTypeAndReferenceTypeAndReferenceIdIn(
            LedgerType ledgerType, String referenceType, List<Long> referenceIds);
}
