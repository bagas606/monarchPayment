package id.ppob2.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps to the `provider` table, PRD Section 22.5. Master data only — the `GameProvider`
 * adapter/integration code (circuit breaker, health) lives in the `provider` module per
 * Section 20.1; `catalog` owns "Product, Provider, Provider SKU master data". */
@Entity
@Table(name = "provider")
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "rate_limit_per_min")
    private Integer rateLimitPerMin;

    protected Provider() {
    }

    public Provider(String code, String name, String status, int timeoutMs, Integer rateLimitPerMin) {
        this.code = code;
        this.name = name;
        this.status = status;
        this.timeoutMs = timeoutMs;
        this.rateLimitPerMin = rateLimitPerMin;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }

    /** Section 31.2 eligibility says "provider status != DOWN", but Section 22.5's enum is
     * ACTIVE/DISABLED/DEGRADED with no DOWN value — DISABLED is treated as the DOWN-equivalent
     * here. DEGRADED is intentionally still eligible (degraded, not down). */
    public boolean isRoutable() {
        return !"DISABLED".equals(status);
    }
}
