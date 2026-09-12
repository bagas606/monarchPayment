package id.ppob2.payment.domain;

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

/** Maps to the `payment` table, PRD Section 22.17. 1:1 with a parent order (MVP assumption). */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_order_id", nullable = false, unique = true)
    private Long parentOrderId;

    @Column(name = "pg_reference", length = 128)
    private String pgReference;

    @Column(nullable = false, length = 32)
    private String method;

    @Column(name = "qr_payload", columnDefinition = "text")
    private String qrPayload;

    @Column(nullable = false, precision = 18, scale = 0)
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected Payment() {
    }

    public Payment(Long parentOrderId, String pgReference, String qrPayload, Money amount,
                    PaymentStatus status, Instant expiresAt) {
        this.parentOrderId = parentOrderId;
        this.pgReference = pgReference;
        this.method = "QRIS_DYNAMIC";
        this.qrPayload = qrPayload;
        this.amount = amount;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getParentOrderId() {
        return parentOrderId;
    }

    public String getPgReference() {
        return pgReference;
    }

    public String getMethod() {
        return method;
    }

    public String getQrPayload() {
        return qrPayload;
    }

    public Money getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    /** True only from PENDING — Section 25.2's out-of-order-callback rule: a terminal payment
     * status is never silently overwritten by a later callback. */
    public boolean markSuccess(Instant paidAt) {
        if (this.status != PaymentStatus.PENDING) {
            return false;
        }
        this.status = PaymentStatus.SUCCESS;
        this.paidAt = paidAt;
        return true;
    }

    public boolean markFailed() {
        if (this.status != PaymentStatus.PENDING) {
            return false;
        }
        this.status = PaymentStatus.FAILED;
        return true;
    }

    /** True only from PENDING — Section 33.2's {@code PAYMENT_PENDING -> EXPIRED} ("QR TTL
     * elapsed, no payment received"). Guarded the same as {@link #markSuccess}/{@link
     * #markFailed}: a payment that already resolved (e.g. a callback that arrived in the narrow
     * window before the expiry sweep ran) must never be overwritten to EXPIRED. */
    public boolean markExpired() {
        if (this.status != PaymentStatus.PENDING) {
            return false;
        }
        this.status = PaymentStatus.EXPIRED;
        return true;
    }
}
