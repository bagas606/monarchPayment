package id.ppob2.ledger;

import id.ppob2.ledger.domain.LedgerEntry;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.ledger.repository.LedgerEntryRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one sanctioned way to write a {@code ledger_entry} row (Section 22.21). Every producing
 * module (`order`, `payment`, `fulfillment`, `settlement` — each already granted a compile
 * dependency on `ledger` by Section 20.2's graph) calls {@link #post} directly rather than
 * touching {@code LedgerEntryRepository} itself.
 *
 * <p>{@code Propagation.MANDATORY}, not {@code REQUIRES_NEW}: unlike the AFTER_COMMIT-callback
 * writes elsewhere in this codebase, a ledger entry must be posted atomically <em>with</em> the
 * state change it records — e.g. a payment marked {@code SUCCESS} whose ledger entry then fails
 * to insert must roll the whole thing back together (no {@code PAID} payment without its ledger
 * entry, and no ledger entry for a payment that didn't actually commit). Callers are expected to
 * already be inside a transaction; {@code MANDATORY} makes that a hard requirement instead of a
 * silent assumption — a caller with no active transaction fails fast with
 * {@code IllegalTransactionStateException} rather than silently starting a new one.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository repository;

    public LedgerService(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry post(LedgerType ledgerType, String referenceType, Long referenceId,
                             LedgerEntryType entryType, Money amount, String description) {
        if (!amount.isGreaterThan(Money.ZERO)) {
            throw new IllegalArgumentException(
                    "ledger_entry.amount must be positive (Section 22.21) — sign is carried by entry_type, got " + amount);
        }
        LedgerEntry entry = new LedgerEntry(ledgerType, referenceType, referenceId, entryType, amount, "IDR",
                description, Instant.now());
        return repository.save(entry);
    }
}
