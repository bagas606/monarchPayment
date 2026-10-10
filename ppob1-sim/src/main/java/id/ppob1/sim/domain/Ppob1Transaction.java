package id.ppob1.sim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.time.Instant;

/**
 * PPOB1's record of one sale, and its belief about what PPOB2 did with it. Maps to
 * {@code ppob1_transaction}.
 *
 * <p>{@code localState} and {@code ppob2State} are both kept, and that redundancy is the point:
 * {@code localState} is what PPOB1 will act on (bill the customer, chase a refund) and
 * {@code ppob2State} is the last thing PPOB2 actually said, stored verbatim and un-translated. A
 * single column would make every disagreement unrepresentable — overwriting belief with truth is
 * what reconciliation is supposed to *decide* to do, not something storage should do silently.
 */
@Entity
@Table(name = "ppob1_transaction")
public class Ppob1Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reseller_txn_id", nullable = false, unique = true, length = 64)
    private String resellerTxnId;

    @Column(name = "product_code", nullable = false, length = 64)
    private String productCode;

    @Column(name = "amount", nullable = false, precision = 18, scale = 0)
    private BigInteger amount;

    @Column(name = "customer_reference", nullable = false)
    private String customerReference;

    @Column(name = "ppob2_order_id", length = 64)
    private String ppob2OrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "local_state", nullable = false, length = 32)
    private LocalState localState;

    /** A {@code String}, not an enum: this is PPOB2's vocabulary, and a value PPOB1 has no enum
     * constant for is exactly the case that must survive being stored. Translating on the way in
     * would discard the only evidence of what PPOB2 actually reported. */
    @Column(name = "ppob2_state", length = 32)
    private String ppob2State;

    @Column(name = "qr_payload")
    private String qrPayload;

    @Column(name = "qr_expires_at")
    private Instant qrExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_source", length = 16)
    private StateSource lastSource;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "rejection_code", length = 64)
    private String rejectionCode;

    @Column(name = "rejection_message")
    private String rejectionMessage;

    /** When a reconciliation run concluded there was nothing further to learn about this
     * transaction. Null means it is still in the recon working set. See
     * {@code V2__recon_settled_at.sql} for why "has it been reconciled" is the right question
     * rather than "is it still open". */
    @Column(name = "recon_settled_at")
    private Instant reconSettledAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Ppob1Transaction() {
    }

    /** Starts in {@link LocalState#SUBMITTING}: the row is written before PPOB2 is called, so a
     * failure mid-call leaves something to reconcile rather than nothing. */
    public Ppob1Transaction(String resellerTxnId, String productCode, BigInteger amount, String customerReference) {
        this.resellerTxnId = resellerTxnId;
        this.productCode = productCode;
        this.amount = amount;
        this.customerReference = customerReference;
        this.localState = LocalState.SUBMITTING;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** PPOB2 accepted the order. */
    public void acceptedByPpob2(String orderId, String ppob2State, String qrPayload, Instant qrExpiresAt) {
        this.ppob2OrderId = orderId;
        this.qrPayload = qrPayload;
        this.qrExpiresAt = qrExpiresAt;
        applyPpob2State(ppob2State, StateSource.CREATE_RESPONSE);
    }

    /** PPOB2 refused it. No upstream order exists, so there is nothing to inquire about later. */
    public void rejectedByPpob2(String errorCode, String message) {
        this.localState = LocalState.REJECTED;
        this.rejectionCode = errorCode;
        this.rejectionMessage = message;
        this.lastSource = StateSource.CREATE_RESPONSE;
        this.lastSyncedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Records what PPOB2 reported and re-derives PPOB1's belief from it.
     *
     * <p>Deliberately <b>not</b> guarded by a state machine. PPOB1 is downstream: it does not get
     * to tell PPOB2 that a transition is illegal, and refusing a backwards-looking update would
     * only make PPOB1's belief diverge further from the upstream truth it is trying to track.
     * Webhook delivery is also unordered in principle, so "backwards" is not even reliably wrong.
     * What PPOB1 can do is keep the evidence — {@code lastSource} records whether this came from a
     * webhook or an inquiry, and {@code ppob1_inbound_webhook} keeps every callback — so a
     * surprising sequence is reconstructable after the fact.
     */
    public void applyPpob2State(String ppob2State, StateSource source) {
        this.ppob2State = ppob2State;
        this.localState = LocalState.fromPpob2State(ppob2State);
        this.lastSource = source;
        this.lastSyncedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Used when PPOB2 no longer recognises an order id PPOB1 holds — see {@link LocalState#UNKNOWN}. */
    public void markUnknown() {
        this.localState = LocalState.UNKNOWN;
        this.lastSource = StateSource.INQUIRY;
        this.lastSyncedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getResellerTxnId() {
        return resellerTxnId;
    }

    public String getProductCode() {
        return productCode;
    }

    public BigInteger getAmount() {
        return amount;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public String getPpob2OrderId() {
        return ppob2OrderId;
    }

    public LocalState getLocalState() {
        return localState;
    }

    public String getPpob2State() {
        return ppob2State;
    }

    public String getQrPayload() {
        return qrPayload;
    }

    public Instant getQrExpiresAt() {
        return qrExpiresAt;
    }

    public StateSource getLastSource() {
        return lastSource;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public String getRejectionCode() {
        return rejectionCode;
    }

    public String getRejectionMessage() {
        return rejectionMessage;
    }

    public Instant getReconSettledAt() {
        return reconSettledAt;
    }

    /**
     * Marks this transaction as reconciled — a run has confirmed PPOB2's answer and there is
     * nothing further to learn, so later runs skip it.
     *
     * <p>Set even for a verdict that needed attention, provided re-running could not change it: a
     * {@code WEBHOOK_NEVER_ARRIVED} finding on a settled order is reported once and is then a fact,
     * not a condition to re-detect every hour. Re-reporting it forever would bury the verdicts that
     * do change. An {@code ORPHANED_SUBMIT} is the opposite case and is deliberately left unsettled:
     * it is unresolved business, and the next run must look again.
     */
    public void reconSettled(Instant at) {
        this.reconSettledAt = at;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
