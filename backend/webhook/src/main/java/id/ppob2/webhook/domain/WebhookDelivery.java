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
 * <h2>Two ways a row gets here, and they start in different states</h2>
 * <ul>
 *   <li><b>After a failed synchronous attempt</b> ({@link #WebhookDelivery(Long, String, String,
 *       Instant)}, the fulfillment path). {@code attemptCount} starts at {@code 1} — the attempt
 *       that just failed — and a delivery that succeeds synchronously never gets a row here at all,
 *       since there is nothing left to schedule.</li>
 *   <li><b>Scheduled with no attempt yet</b> ({@link #WebhookDelivery(Long, String)}, the
 *       {@code EXPIRED} path). {@code attemptCount} starts at {@code 0} and
 *       {@code lastAttemptAt} is {@code null}, because nothing has been attempted; the sweep job
 *       makes every attempt including the first. See {@code OrderExpiredWebhookListener} for why
 *       that path deliberately has no synchronous attempt.</li>
 * </ul>
 * Both reach five total attempts: 1 synchronous + 4 swept, or 0 + 5 swept. The arithmetic lives in
 * {@code WebhookDeliveryTracker.recordAttemptResult} and works off {@code attemptCount}, so it
 * needs no branch per origin — but it is the reason the starting count has to be honest.
 *
 * <p>One consequence worth knowing when reading this table: an {@code EXPIRED} delivery leaves a
 * {@code DELIVERED} row even when the very first attempt succeeds, while a fulfillment delivery
 * that succeeds first time leaves no row at all. So presence here does not mean "this delivery had
 * trouble" any more; {@code attempt_count} does.
 *
 * <p>{@code webhook_delivery_order_event_uk} (unique on {@code parent_order_id, event_type})
 * assumes at most one {@code ORDER_STATUS_CHANGED} notification is ever owed per order. Still true
 * with the {@code EXPIRED} path added, and worth re-checking rather than assuming: an order reaches
 * exactly one of EXPIRED / SUCCESS / PARTIAL_FAILED / FAILED, and a payment confirmation arriving
 * after expiry does not transition it (Section 25.2's late-callback guard in
 * {@code ParentOrderTransitionService.markPaid}, driven by TC-BE-031), so the two paths cannot both
 * fire for one order. If a future change also notifies on {@code AdminChildOrderRetryOrchestrator}'s
 * {@code PARTIAL_FAILED -> SUCCESS} retry path (PPOB1 arguably should hear about that too), a
 * second notification for the same order would find the old DELIVERED/EXHAUSTED row already
 * occupying the unique key and the tracker would log "not scheduling a duplicate" and silently drop
 * it. Revisit this constraint's shape (e.g. include an attempt/occurrence discriminator) before
 * wiring that path, same caution as {@code ChildOrderState.COMPENSATED}'s flagged-but-unhandled
 * predicate elsewhere in this codebase.
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

    /** Null only while {@code attemptCount} is {@code 0} — a delivery scheduled but never yet
     * attempted (V25 dropped this column's NOT NULL for exactly that state). Both mutators below
     * set it, so a row leaves that state on its first attempt and never returns. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_failure_reason", columnDefinition = "text")
    private String lastFailureReason;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected WebhookDelivery() {
    }

    /** After a failed synchronous attempt: that attempt is already counted. */
    public WebhookDelivery(Long parentOrderId, String eventType, String firstFailureReason, Instant nextAttemptAt) {
        this.parentOrderId = parentOrderId;
        this.eventType = eventType;
        this.status = WebhookDeliveryStatus.PENDING;
        this.attemptCount = 1;
        this.nextAttemptAt = nextAttemptAt;
        this.lastAttemptAt = Instant.now();
        this.lastFailureReason = firstFailureReason;
    }

    /**
     * Scheduled with nothing attempted yet, due immediately — the sweep job makes every attempt
     * including the first.
     *
     * <p>{@code attemptCount} is {@code 0} and {@code lastAttemptAt} is {@code null} because both
     * are true, not as placeholders. Reusing the four-argument constructor here would claim an
     * attempt that never happened, which would both cost this delivery one of its five attempts and
     * put a time in {@code last_attempt_at} that nothing ever attempted at.
     */
    public WebhookDelivery(Long parentOrderId, String eventType) {
        this.parentOrderId = parentOrderId;
        this.eventType = eventType;
        this.status = WebhookDeliveryStatus.PENDING;
        this.attemptCount = 0;
        // Due now rather than after a backoff: there has been no failure to back off from.
        this.nextAttemptAt = Instant.now();
        this.lastAttemptAt = null;
        this.lastFailureReason = null;
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
