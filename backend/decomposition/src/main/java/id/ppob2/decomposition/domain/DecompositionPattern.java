package id.ppob2.decomposition.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps to the `decomposition_pattern` table, PRD Section 22.10 — the hybrid representation from
 * Section 21.3: {@code components} is the JSONB full-pattern snapshot (source of truth for the
 * payload), while {@link DecompositionComponent} rows are the normalized reverse-index table.
 * {@code @JdbcTypeCode(SqlTypes.JSON)} is required on the JSONB field — see this session's
 * webhook-slice notes on why {@code columnDefinition} alone silently binds as varchar.
 */
@Entity
@Table(name = "decomposition_pattern")
public class DecompositionPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "generation_id", nullable = false)
    private Long generationId;

    @Column(name = "parent_amount", nullable = false, precision = 18, scale = 0)
    private Money parentAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String components;

    @Column(name = "component_count", nullable = false)
    private int componentCount;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "pattern_hash", nullable = false, length = 64)
    private String patternHash;

    @Column(name = "structural_status", nullable = false, length = 16)
    private String structuralStatus;

    protected DecompositionPattern() {
    }

    public Long getId() {
        return id;
    }

    public Long getGenerationId() {
        return generationId;
    }

    public Money getParentAmount() {
        return parentAmount;
    }

    public boolean isStructurallyValid() {
        return "VALID".equals(structuralStatus);
    }
}
