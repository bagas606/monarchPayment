package id.ppob2.decomposition.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps to the `decomposition_component` table, PRD Section 22.11 — the normalized reverse-index
 * (SKU → pattern) support table alongside {@link DecompositionPattern}'s JSONB snapshot. */
@Entity
@Table(name = "decomposition_component")
public class DecompositionComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pattern_id", nullable = false)
    private Long patternId;

    @Column(name = "provider_sku_id", nullable = false)
    private Long providerSkuId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "face_value", nullable = false, precision = 18, scale = 0)
    private Money faceValue;

    protected DecompositionComponent() {
    }

    public Long getPatternId() {
        return patternId;
    }

    public Long getProviderSkuId() {
        return providerSkuId;
    }

    public int getQuantity() {
        return quantity;
    }
}
