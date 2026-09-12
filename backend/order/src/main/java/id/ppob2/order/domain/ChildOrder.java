package id.ppob2.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import id.ppob2.sharedkernel.money.Money;

/**
 * Maps to the `child_order` table, PRD Section 22.16. {@code providerSkuId} is a plain FK
 * (matching {@code provider_sku.id} in `catalog`) rather than a JPA relation, per this codebase's
 * established rule of not taking a compile dependency across a module boundary Section 20.2
 * doesn't grant — `order` has no edge to `catalog`.
 *
 * <p>{@code faceValue} (this child order's total contribution to {@code parent_amount} — per-unit
 * face value times {@code quantity}) is not part of Section 22.16's documented schema; added so
 * ORDER_VS_FULFILLMENT reconciliation can sum successful children's value without an unsafe
 * after-the-fact join back to {@code decomposition_component} (see the V14 migration comment).
 */
@Entity
@Table(name = "child_order")
public class ChildOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_order_id", nullable = false)
    private Long parentOrderId;

    @Column(name = "provider_sku_id", nullable = false)
    private Long providerSkuId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChildOrderState state;

    @Column(name = "provider_transaction_id")
    private Long providerTransactionId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "face_value", nullable = false)
    private Money faceValue;

    protected ChildOrder() {
    }

    public ChildOrder(Long parentOrderId, Long providerSkuId, int quantity, int sequenceNo, Money faceValue) {
        this.parentOrderId = parentOrderId;
        this.providerSkuId = providerSkuId;
        this.quantity = quantity;
        this.sequenceNo = sequenceNo;
        this.state = ChildOrderState.PENDING;
        this.attemptCount = 0;
        this.faceValue = faceValue;
    }

    public Long getId() {
        return id;
    }

    public Long getParentOrderId() {
        return parentOrderId;
    }

    public Long getProviderSkuId() {
        return providerSkuId;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public ChildOrderState getState() {
        return state;
    }

    public Long getProviderTransactionId() {
        return providerTransactionId;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Money getFaceValue() {
        return faceValue;
    }

    /** True only from PENDING — mirrors {@code Payment.markSuccess}'s guard against re-applying a
     * transition that already happened, e.g. two overlapping dispatch attempts for the same
     * child order.
     *
     * <p><b>Whoever builds the Section 34.1 compensating-retry action (returning a
     * {@code PARTIAL_FAILED}/{@code FAILED} child order to {@code PENDING} for re-dispatch) must
     * NOT reset {@code attempt_count} back to 0 when doing so.</b> {@code
     * FulfillmentExecutionService} derives each dispatch attempt's {@code provider_transaction}
     * idempotency key from the value this method returns (see its Javadoc) specifically so a
     * retried attempt gets a new key instead of colliding with the earlier failed attempt's row
     * via {@code ON CONFLICT DO NOTHING} — resetting the counter would silently reproduce that
     * collision and let a retry's outcome get recorded against the wrong (stale, FAILED)
     * provider_transaction row. */
    public boolean markExecuting() {
        if (this.state != ChildOrderState.PENDING) {
            return false;
        }
        this.state = ChildOrderState.EXECUTING;
        this.attemptCount++;
        return true;
    }

    /** True only from EXECUTING. {@code providerTransactionId} is null-safe: a dispatch that
     * never reached the provider (e.g. Section 34.1's compensating-workflow gap, see fulfillment
     * README notes) still needs a terminal state without a real transaction record. */
    public boolean markSuccess(Long providerTransactionId) {
        if (this.state != ChildOrderState.EXECUTING) {
            return false;
        }
        this.state = ChildOrderState.SUCCESS;
        this.providerTransactionId = providerTransactionId;
        return true;
    }

    public boolean markFailed(Long providerTransactionId) {
        if (this.state != ChildOrderState.EXECUTING) {
            return false;
        }
        this.state = ChildOrderState.FAILED;
        this.providerTransactionId = providerTransactionId;
        return true;
    }

    /** True only from FAILED — the Admin Web compensating retry (Section 34.1). Deliberately does
     * NOT reset {@code attempt_count}: see {@link #markExecuting}'s Javadoc for why resetting it
     * would let a retried attempt collide with the earlier failed attempt's {@code
     * provider_transaction} row via {@code ON CONFLICT DO NOTHING} instead of getting its own row. */
    public boolean resetForRetry() {
        if (this.state != ChildOrderState.FAILED) {
            return false;
        }
        this.state = ChildOrderState.PENDING;
        return true;
    }
}
