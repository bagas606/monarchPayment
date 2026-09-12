package id.ppob2.ledger.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to the `ledger_entry` table, PRD Section 22.21 — append-only. There are deliberately no
 * setters and no update-shaped methods on this class: once constructed and saved, a row is never
 * meant to change. Section 22.21 says to "enforce via DB role privileges / triggers" — this
 * codebase does the latter (see the {@code V10__ledger.sql} migration's
 * {@code ledger_entry_immutable} trigger, which rejects UPDATE/DELETE at the database level, not
 * just by convention in application code).
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_type", nullable = false, length = 16)
    private LedgerType ledgerType;

    @Column(name = "reference_type", nullable = false, length = 32)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private LedgerEntryType entryType;

    @Column(nullable = false, precision = 18, scale = 0)
    private Money amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(LedgerType ledgerType, String referenceType, Long referenceId, LedgerEntryType entryType,
                        Money amount, String currency, String description, Instant postedAt) {
        this.ledgerType = ledgerType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.postedAt = postedAt;
    }

    public Long getId() {
        return id;
    }

    public LedgerType getLedgerType() {
        return ledgerType;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public Money getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
