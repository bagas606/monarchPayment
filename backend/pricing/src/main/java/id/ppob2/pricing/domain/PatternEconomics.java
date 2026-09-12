package id.ppob2.pricing.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Maps to the `pattern_economics` table, PRD Section 22.12 — the daily economic snapshot for a
 * `decomposition_pattern` (Section 30.1). Despite the FK into `decomposition_pattern`, this
 * lives in `pricing` rather than `decomposition`: Section 20.1 assigns "margin/economics
 * computation" to `pricing`, and the runtime score this table holds is what Section 31.1's
 * routing reads — it does not recompute Section 31.3's formula live. Computing/refreshing this
 * snapshot daily is the (not-yet-built) offline re-scoring job's job; for this slice these rows
 * are seed data, read-only at runtime.
 */
@Entity
@Table(name = "pattern_economics")
public class PatternEconomics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pattern_id", nullable = false)
    private Long patternId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "provider_cost_total", nullable = false, precision = 18, scale = 0)
    private Money providerCostTotal;

    @Column(name = "gross_profit", nullable = false, precision = 18, scale = 0)
    private Money grossProfit;

    @Column(name = "gross_margin_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal grossMarginPct;

    @Column(name = "payment_fee", nullable = false, precision = 18, scale = 0)
    private Money paymentFee;

    @Column(name = "net_contribution", nullable = false, precision = 18, scale = 0)
    private Money netContribution;

    @Column(name = "net_margin_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal netMarginPct;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal score;

    @Column(nullable = false)
    private boolean eligible;

    protected PatternEconomics() {
    }

    public PatternEconomics(Long patternId, LocalDate snapshotDate, Money providerCostTotal, Money grossProfit,
                             BigDecimal grossMarginPct, Money paymentFee, Money netContribution,
                             BigDecimal netMarginPct, BigDecimal score, boolean eligible) {
        this.patternId = patternId;
        this.snapshotDate = snapshotDate;
        this.providerCostTotal = providerCostTotal;
        this.grossProfit = grossProfit;
        this.grossMarginPct = grossMarginPct;
        this.paymentFee = paymentFee;
        this.netContribution = netContribution;
        this.netMarginPct = netMarginPct;
        this.score = score;
        this.eligible = eligible;
    }

    public Long getId() {
        return id;
    }

    public Long getPatternId() {
        return patternId;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public Money getGrossProfit() {
        return grossProfit;
    }

    public BigDecimal getScore() {
        return score;
    }

    public boolean isEligible() {
        return eligible;
    }
}
