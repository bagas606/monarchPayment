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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * Section 23.4's {@code metadata} object (V23), whose one documented key is
     * {@code reseller_txn_id} — the partner's own id for the transaction this order fulfils. Before
     * V23 the field was bound by {@code CreateOrderController} and then dropped; see that migration
     * for why the column is a generic object rather than a {@code reseller_txn_id} column.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is required, not decorative: {@code columnDefinition}
     * alone silently binds the value as varchar and the insert fails against a {@code jsonb} column
     * — the same trap recorded on {@code DecompositionPattern.components} and
     * {@code WebhookEvent.payload}, which are this codebase's two existing JSONB mappings.
     *
     * <p><b>The values are preserved; the bytes are not.</b> The request body is parsed into a
     * {@code Map}, re-serialized, and then normalized again by {@code jsonb} itself, which reorders
     * keys and drops insignificant whitespace (the same caveat {@code AdminRefundController} and
     * {@code AdminFulfillmentController} record about {@code audit_log.after_state}). So this column
     * must never be used to recompute a Section 23.2 request signature: the hash is over the bytes
     * received, and these are not those bytes.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    /** DB-trigger-managed (confirmed via {@code \d parent_order}: {@code
     * parent_order_set_updated_at BEFORE UPDATE}) — read-only here (`insertable = false,
     * updatable = false`) since the trigger, not the application, is the source of truth for this
     * column's value on every write. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected ParentOrder() {
    }

    public ParentOrder(String orderNo, Long channelId, Long partnerId, String clientId, Long productId,
                        Money parentAmount, String orderSource, String idempotencyKey,
                        String customerReference, Instant createdAt) {
        this(orderNo, channelId, partnerId, clientId, productId, parentAmount, orderSource, idempotencyKey,
                customerReference, createdAt, null, null);
    }

    public ParentOrder(String orderNo, Long channelId, Long partnerId, String clientId, Long productId,
                        Money parentAmount, String orderSource, String idempotencyKey,
                        String customerReference, Instant createdAt, String callbackUrl, String metadata) {
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
        this.metadata = metadata;
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

    /** The stored {@code metadata} JSON, or {@code null} when the order was created without any —
     * SQL NULL, never the JSON text {@code "null"}; see {@code CreateOrderController}. */
    public String getMetadata() {
        return metadata;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setPatternId(Long patternId) {
        this.patternId = patternId;
    }

    /**
     * True if this row represents the exact same logical request (idempotent replay, Section 23.4)
     * rather than a genuine {@code IDEMPOTENCY_KEY_CONFLICT}. Section 22.15 has no stored request
     * hash, so equality is judged on the fields that would differ between two distinct requests.
     *
     * <p><b>{@code metadata} is deliberately not compared, following the precedent {@code
     * callback_url} already set.</b> V16 added that column and left this check alone; V23 adds
     * {@code metadata} and does the same. The line both sit on the far side of is what the order
     * <em>is</em> — product, amount, who it is for — versus how the caller annotated or wants to be
     * told about it. Only the former decides what gets fulfilled and billed, and only the former
     * can make two requests genuinely different orders. A 409 is the one answer a client cannot
     * recover from without changing its own key, so it is reserved for that.
     *
     * <p>The corollary, which is the part worth knowing: a replay carrying <em>different</em>
     * metadata is still a replay, and {@code ParentOrderCreationService} returns the existing row
     * untouched — so the stored metadata is whatever the first accepted request carried. First write
     * wins, by omission rather than by a rule written anywhere, so it is written here. That is the
     * right way round: a replay must not mutate an order, and support reading this column needs it
     * to say what the order was created with.
     */
    public boolean matchesRequest(Long productId, Money parentAmount, String customerReference) {
        return this.productId.equals(productId)
                && this.parentAmount.equals(parentAmount)
                && java.util.Objects.equals(this.customerReference, customerReference);
    }
}
