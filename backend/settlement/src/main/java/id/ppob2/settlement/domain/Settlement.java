package id.ppob2.settlement.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Maps to the `settlement` table, PRD Section 22.22. */
@Entity
@Table(name = "settlement")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "pg_reference", length = 128)
    private String pgReference;

    @Column(name = "expected_amount", nullable = false, precision = 18, scale = 0)
    private Money expectedAmount;

    @Column(name = "actual_amount", precision = 18, scale = 0)
    private Money actualAmount;

    @Column(name = "fee_amount", precision = 18, scale = 0)
    private Money feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SettlementStatus status;

    protected Settlement() {
    }

    public Settlement(LocalDate settlementDate, String pgReference, Money expectedAmount, Money actualAmount,
                       Money feeAmount, SettlementStatus status) {
        this.settlementDate = settlementDate;
        this.pgReference = pgReference;
        this.expectedAmount = expectedAmount;
        this.actualAmount = actualAmount;
        this.feeAmount = feeAmount;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public String getPgReference() {
        return pgReference;
    }

    public Money getExpectedAmount() {
        return expectedAmount;
    }

    public Money getActualAmount() {
        return actualAmount;
    }

    public Money getFeeAmount() {
        return feeAmount;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public boolean isDiscrepancy() {
        return status == SettlementStatus.DISCREPANCY;
    }
}
