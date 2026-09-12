package id.ppob2.reconciliation.domain;

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
import java.time.LocalDate;

/**
 * Maps to the `reconciliation` table, PRD Section 22.23. {@code resolvedBy} is a plain nullable
 * {@code Long}, not a JPA relation to `admin_user` — Section 22.23 names that FK, but the `admin`
 * module (and `admin_user` table) don't exist yet in this codebase, same treatment as every other
 * cross-module reference here.
 */
@Entity
@Table(name = "reconciliation")
public class Reconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "recon_type", nullable = false, length = 32)
    private ReconciliationType reconType;

    @Column(name = "recon_date", nullable = false)
    private LocalDate reconDate;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "expected_value", precision = 18, scale = 0)
    private Money expectedValue;

    @Column(name = "actual_value", precision = 18, scale = 0)
    private Money actualValue;

    @Column(precision = 18, scale = 0)
    private Money discrepancy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReconciliationStatus status;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Reconciliation() {
    }

    public Reconciliation(ReconciliationType reconType, LocalDate reconDate, Long referenceId,
                           Money expectedValue, Money actualValue, Money discrepancy) {
        this.reconType = reconType;
        this.reconDate = reconDate;
        this.referenceId = referenceId;
        this.expectedValue = expectedValue;
        this.actualValue = actualValue;
        this.discrepancy = discrepancy;
        this.status = ReconciliationStatus.OPEN;
    }

    public Long getId() {
        return id;
    }

    public ReconciliationType getReconType() {
        return reconType;
    }

    public LocalDate getReconDate() {
        return reconDate;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public Money getExpectedValue() {
        return expectedValue;
    }

    public Money getActualValue() {
        return actualValue;
    }

    public Money getDiscrepancy() {
        return discrepancy;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public Long getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    /** True only from OPEN. */
    public boolean investigate() {
        if (this.status != ReconciliationStatus.OPEN) {
            return false;
        }
        this.status = ReconciliationStatus.INVESTIGATING;
        return true;
    }

    /** True only from INVESTIGATING — Section 38.2's "must be moved to INVESTIGATING then
     * RESOLVED" means OPEN cannot resolve directly, it must pass through investigation first. */
    public boolean resolve(Long resolvedBy, Instant resolvedAt) {
        if (this.status != ReconciliationStatus.INVESTIGATING) {
            return false;
        }
        this.status = ReconciliationStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        return true;
    }
}
