package id.ppob2.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Maps to the `pattern_usage` table, PRD Section 22.13. Read-only projection here — writes go
 * through {@code UsageTrackingService}'s conflict-safe upsert, not this entity's setters,
 * following the same lesson as {@code PaymentEventRepository.insertIfAbsent}: a naive
 * read-then-increment-then-save race under concurrent order creation would lose updates. */
@Entity
@Table(name = "pattern_usage")
public class PatternUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pattern_id", nullable = false)
    private Long patternId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "daily_usage", nullable = false)
    private long dailyUsage;

    @Column(name = "lifetime_usage", nullable = false)
    private long lifetimeUsage;

    protected PatternUsage() {
    }

    public Long getPatternId() {
        return patternId;
    }

    public long getDailyUsage() {
        return dailyUsage;
    }
}
