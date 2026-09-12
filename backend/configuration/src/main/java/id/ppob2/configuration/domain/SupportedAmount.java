package id.ppob2.configuration.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Maps to the `supported_amount` table, PRD Section 22.8. Tiers/steps are configured data,
 * never a hard-coded computation, since they are configurable per product/category. */
@Entity
@Table(name = "supported_amount")
public class SupportedAmount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_category", nullable = false, length = 64)
    private String productCategory;

    @Column(nullable = false, precision = 18, scale = 0)
    private Money amount;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    protected SupportedAmount() {
    }

    public SupportedAmount(String productCategory, Money amount, String status, Instant effectiveFrom) {
        this.productCategory = productCategory;
        this.amount = amount;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
    }

    public Long getId() {
        return id;
    }

    public String getProductCategory() {
        return productCategory;
    }

    public Money getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
