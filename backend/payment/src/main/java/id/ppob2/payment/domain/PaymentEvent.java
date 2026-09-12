package id.ppob2.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps to the `payment_event` table, PRD Section 22.18 — the PG-callback dedup/audit record
 * for one `payment`. {@code dedup_key}'s unique constraint is the enforcement mechanism for
 * Section 48.5's "duplicate callback acknowledged, not reprocessed" rule. */
@Entity
@Table(name = "payment_event")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(name = "dedup_key", nullable = false, length = 128)
    private String dedupKey;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaymentEvent() {
    }

    public PaymentEvent(Long paymentId, String eventType, String rawPayload, boolean signatureValid,
                         String dedupKey, Instant receivedAt) {
        this.paymentId = paymentId;
        this.eventType = eventType;
        this.rawPayload = rawPayload;
        this.signatureValid = signatureValid;
        this.dedupKey = dedupKey;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }
}
