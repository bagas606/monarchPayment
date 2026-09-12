package id.ppob2.webhook.domain;

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

/**
 * Maps to the `webhook_event` table, PRD Section 22.24 — the generic inbound/outbound webhook
 * log across every source (Ayolinx, providers, PPOB1), distinct from the payment-module-owned
 * `payment_event` (Section 22.18) which is specifically the PG-callback dedup/idempotency record
 * for a single `payment` row. This table has no FK to `payment`, so it can log a callback even
 * when the payload doesn't resolve to a known payment (e.g. an unrecognized pg_reference, or a
 * signature that failed verification) — cases `payment_event` cannot represent.
 *
 * <p>Section 22.24 does not list a timestamp column, which would make the log useless for its
 * stated purpose; {@code receivedAt} is added here following the same pattern as
 * {@code payment_event.received_at} (Section 22.18) and the general Section 22 rule that every
 * table carries a creation timestamp.
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private WebhookDirection direction;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "dedup_key", length = 128)
    private String dedupKey;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(WebhookDirection direction, String source, String eventType, String payload,
                         String status, String dedupKey, Instant receivedAt) {
        this.direction = direction;
        this.source = source;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.dedupKey = dedupKey;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }
}
