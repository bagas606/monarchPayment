package id.ppob2.decomposition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Maps to the `pattern_generation` table, PRD Section 22.9. In production this row is written
 * by the offline Rust engine (Section 29); for this slice it is seed data identifying which
 * generation is the currently `ACTIVE` one that {@code decomposition_pattern} rows belong to. */
@Entity
@Table(name = "pattern_generation")
public class PatternGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "triggered_by", nullable = false, length = 32)
    private String triggeredBy;

    @Column(nullable = false, length = 16)
    private String scope;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "pattern_count")
    private Long patternCount;

    @Column(columnDefinition = "text")
    private String notes;

    protected PatternGeneration() {
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
