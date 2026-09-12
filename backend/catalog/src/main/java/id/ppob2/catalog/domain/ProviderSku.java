package id.ppob2.catalog.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps to the `provider_sku` table, PRD Section 22.6 — the Base Provider SKU (Section 28.1). */
@Entity
@Table(name = "provider_sku")
public class ProviderSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "provider_sku_code", nullable = false, length = 64)
    private String providerSkuCode;

    @Column(name = "face_value", nullable = false, precision = 18, scale = 0)
    private Money faceValue;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "quota_daily")
    private Integer quotaDaily;

    protected ProviderSku() {
    }

    public ProviderSku(Long providerId, Long productId, String providerSkuCode, Money faceValue,
                        String status, Integer quotaDaily) {
        this.providerId = providerId;
        this.productId = productId;
        this.providerSkuCode = providerSkuCode;
        this.faceValue = faceValue;
        this.status = status;
        this.quotaDaily = quotaDaily;
    }

    public Long getId() {
        return id;
    }

    public Long getProviderId() {
        return providerId;
    }

    public Long getProductId() {
        return productId;
    }

    public Money getFaceValue() {
        return faceValue;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public Integer getQuotaDaily() {
        return quotaDaily;
    }
}
