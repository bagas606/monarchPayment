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

/**
 * Retry-schedule tracker for one outbound webhook delivery (PRD Section 23.8's "retry-with-
 * backoff on non-2xx", e.g. 5 attempts over 24h) — not a Section 22 table. {@code webhook_event}
 * (22.24) already logs every individual attempt (one row each, no FK to this table, no mutable
 * state); this table exists only to hold what {@code webhook_event}'s append-only log shape
 * can't: how many attempts have been made and when the next one is due. Both are written for the
 * same logical delivery, by design, not a duplication to reconcile — {@code webhook_event} is
 * the audit trail, this is the scheduler's own bookkeeping.
 *
 * <p>Created only when the first (synchronous) delivery attempt fails — a delivery that succeeds
 * immediately never gets a row here at all, since there is nothing left to schedule.
 * {@code attemptCount} therefore starts at {@code 1} (the failed synchronous attempt), not
 * {@code 0}.
 *
 * <p>{@code webhook_delivery_order_event_uk} (unique on {@code parent_order_id, event_type})
 * assumes at most one {@code ORDER_STATUS_CHANGED} notification is ever owed per order — true
 * today, since only {@code FulfillmentDispatchListener}'s first terminal-state transition fires
 * one. If a future change also notifies on {@code AdminChildOrderRetryOrchestrator}'s
 * {@code PARTIAL_FAILED -> SUCCESS} retry path (PPOB1 arguably should hear about that too), a
 * second notification for the same order would find the old DELIVERED/EXHAUSTED row already
 * occupying the unique key and {@code scheduleFirstRetry} would log "not scheduling a duplicate"
 * and silently drop it. Revisit this constraint's shape (e.g. include an attempt/occurrence
 * discriminator) before wiring that path, same caution as {@code ChildOrderState.COMPENSATED}'s
 * flagged-but-unhandled predicate elsewhere in this codebase.
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_order_id", nullable = false)
    private Long parentOrderId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WebhookDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    @Column(name = "last_failure_reason", columnDefinition = "text")
    private String lastFailureReason;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected WebhookDelivery() {
    }

    public WebhookDelivery(Long parentOrderId, String eventType, String firstFailureReason, Instant nextAttemptAt) {
        this.parentOrderId = parentOrderId;
        this.eventType = eventType;
        this.status = WebhookDeliveryStatus.PENDING;
        this.attemptCount = 1;
        this.nextAttemptAt = nextAttemptAt;
        this.lastAttemptAt = Instant.now();
        this.lastFailureReason = firstFailureReason;
    }

    public Long getId() {
        return id;
    }

    public Long getParentOrderId() {
        return parentOrderId;
    }

    public String getEventType() {
        return eventType;
    }

    public WebhookDeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    /** True only from {@link WebhookDeliveryStatus#PENDING} — same terminal-state guard shape as
     * {@code Payment.markSuccess}/{@code markFailed}: a delivery already resolved (delivered or
     * exhausted) must never be silently reopened by a late/duplicate sweep pass. */
    public boolean markDelivered(Instant attemptAt) {
        if (status != WebhookDeliveryStatus.PENDING) {
            return false;
        }
        this.status = WebhookDeliveryStatus.DELIVERED;
        this.attemptCount++;
        this.nextAttemptAt = null;
        this.lastAttemptAt = attemptAt;
        this.lastFailureReason = null;
        return true;
    }

    /**
     * Records one more failed attempt. The caller decides terminal-vs-retry by what it passes as
     * {@code nextAttemptAt}: a future instant to keep retrying, or {@code null} to mark
     * {@link WebhookDeliveryStatus#EXHAUSTED} (the caller compares the new attempt count against
     * its own configured max before calling this).
     *
     * @return {@code true} if applied (this delivery was {@code PENDING}), {@code false} if this
     *     was a no-op because the delivery had already reached a terminal state — same guard
     *     shape as {@link #markDelivered}. Check {@link #getStatus()} afterward to tell a
     *     newly-{@code EXHAUSTED} delivery apart from one still {@code PENDING} for another retry.
     */
    public boolean recordFailure(Instant attemptAt, String failureReason, Instant nextAttemptAt) {
        if (status != WebhookDeliveryStatus.PENDING) {
            return false;
        }
        this.attemptCount++;
        this.lastAttemptAt = attemptAt;
        this.lastFailureReason = failureReason;
        this.nextAttemptAt = nextAttemptAt;
        if (nextAttemptAt == null) {
            this.status = WebhookDeliveryStatus.EXHAUSTED;
        }
        return true;
    }
}
