package id.ppob2.order.domain;

import id.ppob2.sharedkernel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Maps to the `parent_order` table, PRD Section 22.15. */
@Entity
@Table(name = "parent_order")
public class ParentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "partner_id")
    private Long partnerId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "parent_amount", nullable = false, precision = 18, scale = 0)
    private Money parentAmount;

    @Column(name = "order_source", nullable = false, length = 32)
    private String orderSource;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderState state;

    @Column(name = "pattern_id")
    private Long patternId;

    @Column(name = "customer_reference", length = 128)
    private String customerReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "callback_url", length = 500)
    private String callbackUrl;

    protected ParentOrder() {
    }

    public ParentOrder(String orderNo, Long channelId, Long partnerId, String clientId, Long productId,
                        Money parentAmount, String orderSource, String idempotencyKey,
                        String customerReference, Instant createdAt) {
        this(orderNo, channelId, partnerId, clientId, productId, parentAmount, orderSource, idempotencyKey,
                customerReference, createdAt, null);
    }

    public ParentOrder(String orderNo, Long channelId, Long partnerId, String clientId, Long productId,
                        Money parentAmount, String orderSource, String idempotencyKey,
                        String customerReference, Instant createdAt, String callbackUrl) {
        this.orderNo = orderNo;
        this.channelId = channelId;
        this.partnerId = partnerId;
        this.clientId = clientId;
        this.productId = productId;
        this.parentAmount = parentAmount;
        this.orderSource = orderSource;
        this.idempotencyKey = idempotencyKey;
        this.state = OrderState.CREATED;
        this.customerReference = customerReference;
        this.createdAt = createdAt;
        this.callbackUrl = callbackUrl;
    }

    public void transitionTo(OrderState next) {
        OrderStateMachine.requireTransition(this.state, next);
        this.state = next;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getClientId() {
        return clientId;
    }

    public Long getProductId() {
        return productId;
    }

    public Money getParentAmount() {
        return parentAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public OrderState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getPatternId() {
        return patternId;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setPatternId(Long patternId) {
        this.patternId = patternId;
    }

    /** True if this row represents the exact same logical request (idempotent replay, Section 23.4)
     * rather than a genuine {@code IDEMPOTENCY_KEY_CONFLICT}. Section 22.15 has no stored request
     * hash, so equality is judged on the fields that would differ between two distinct requests. */
    public boolean matchesRequest(Long productId, Money parentAmount, String customerReference) {
        return this.productId.equals(productId)
                && this.parentAmount.equals(parentAmount)
                && java.util.Objects.equals(this.customerReference, customerReference);
    }
}
