package id.ppob2.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Maps to the `sku_usage` table, PRD Section 22.14. Same read-only-entity-plus-upsert-writer
 * pattern as {@link PatternUsage}. */
@Entity
@Table(name = "sku_usage")
public class SkuUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_sku_id", nullable = false)
    private Long providerSkuId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "daily_usage", nullable = false)
    private long dailyUsage;

    @Column(name = "lifetime_usage", nullable = false)
    private long lifetimeUsage;

    @Column(name = "remaining_quota")
    private Integer remainingQuota;

    protected SkuUsage() {
    }

    public Long getProviderSkuId() {
        return providerSkuId;
    }

    public long getDailyUsage() {
        return dailyUsage;
    }
}
