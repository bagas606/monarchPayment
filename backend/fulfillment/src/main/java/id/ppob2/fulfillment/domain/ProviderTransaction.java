package id.ppob2.fulfillment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps to the `provider_transaction` table, PRD Section 22.19. {@code childOrderId} and
 * {@code providerId} are plain FKs (matching `order`'s {@code child_order.id} and `catalog`'s
 * {@code provider.id}) rather than JPA relations — `fulfillment` has no compile dependency on
 * `catalog` (Section 20.2 grants none), so {@code providerId} has to arrive already resolved from
 * the `app`-layer dispatch listener, never looked up here.
 */
@Entity
@Table(name = "provider_transaction")
public class ProviderTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_order_id", nullable = false)
    private Long childOrderId;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProviderTransactionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private String requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    protected ProviderTransaction() {
    }

    public ProviderTransaction(Long childOrderId, Long providerId, String providerReference, String idempotencyKey,
                                ProviderTransactionStatus status, String requestPayload, String responsePayload,
                                Integer latencyMs) {
        this.childOrderId = childOrderId;
        this.providerId = providerId;
        this.providerReference = providerReference;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.latencyMs = latencyMs;
    }

    public Long getId() {
        return id;
    }

    public Long getChildOrderId() {
        return childOrderId;
    }

    public Long getProviderId() {
        return providerId;
    }

    public ProviderTransactionStatus getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == ProviderTransactionStatus.SUCCESS;
    }
}
