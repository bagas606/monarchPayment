package id.ppob2.app.webhook;

import id.ppob2.webhook.OutboundWebhookDeliveryResult;
import id.ppob2.webhook.OutboundWebhookSender;
import id.ppob2.webhook.WebhookDeliveryTracker;
import id.ppob2.webhook.WebhookEventRecorder;
import id.ppob2.webhook.domain.WebhookDirection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PRD Section 23.8: notifies the partner (PPOB1) of an {@code ORDER_STATUS_CHANGED} event once
 * fulfillment reaches a terminal state. Lives in `app` — `webhook` has no edge to `order` or
 * `partner` (Section 20.2), so resolving {@code parent_order.callback_url} and
 * {@code partner.webhook_secret} ({@link WebhookDeliveryTargetResolver}) is a composition-root
 * concern, the same shape as {@code OrderFulfillmentReconciliationOrchestrator} right next to it
 * in the dispatch listener.
 *
 * <p><b>Retry</b>: {@link #sendOrderStatusChanged} makes exactly one synchronous attempt (kept —
 * most deliveries succeed immediately, and there is no reason to defer the happy path to a sweep
 * job); on failure it schedules a retry via {@link WebhookDeliveryTracker} instead of only
 * logging. {@link #attempt} itself — resolve target, send, log the attempt to
 * {@code webhook_event} — is the single shared path both the first attempt and every later retry
 * (from {@code WebhookRetrySweepJob}) go through, so there is exactly one place that can resolve
 * the target or record an attempt, not two copies to keep in sync.
 *
 * <p>Trigger coverage is still partial: only the post-dispatch terminal states
 * (SUCCESS/PARTIAL_FAILED/FAILED) fire this; CANCELLED/EXPIRED/REFUNDED transitions and the QR
 * expiry sweep's own state change (Section 48.3's diagram) do not — flagged in the README, not
 * silently left implied as done.
 *
 * <p>{@link #attempt} itself is not transactional: resolving the target is a separate
 * {@code REQUIRES_NEW} bean (see its Javadoc for why that propagation is load-bearing here, not
 * just convention), the HTTP call runs outside any transaction (same reasoning as
 * {@code FulfillmentExecutionService}'s provider call — an unreachable partner endpoint is the
 * normal case here, not exceptional, and must not hold a connection open), and
 * {@link WebhookEventRecorder#recordIndependently} persists in its own {@code REQUIRES_NEW}
 * transaction afterward — not the plain {@link WebhookEventRecorder#record}, which relies on an
 * ambient transaction this call site doesn't have (see that method's Javadoc for why the first
 * attempt using it silently lost every row).
 */
@Component
public class OutboundWebhookOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OutboundWebhookOrchestrator.class);

    private final WebhookDeliveryTargetResolver targetResolver;
    private final OutboundWebhookSender sender;
    private final WebhookEventRecorder webhookEventRecorder;
    private final WebhookDeliveryTracker deliveryTracker;

    public OutboundWebhookOrchestrator(WebhookDeliveryTargetResolver targetResolver,
                                        OutboundWebhookSender sender,
                                        WebhookEventRecorder webhookEventRecorder,
                                        WebhookDeliveryTracker deliveryTracker) {
        this.targetResolver = targetResolver;
        this.sender = sender;
        this.webhookEventRecorder = webhookEventRecorder;
        this.deliveryTracker = deliveryTracker;
    }

    public void sendOrderStatusChanged(Long parentOrderId) {
        OutboundWebhookAttemptResult result = attempt(parentOrderId, "ORDER_STATUS_CHANGED");
        if (result.noTarget() || result.success()) {
            return;
        }
        log.warn("Outbound webhook delivery failed for parent_order {}, scheduling a retry: {}",
                parentOrderId, result.failureReason());
        deliveryTracker.scheduleFirstRetry(parentOrderId, "ORDER_STATUS_CHANGED", result.failureReason());
    }

    /**
     * One delivery attempt: resolve the current target fresh (not cached from a prior attempt —
     * a partner's {@code callback_url}/{@code webhook_secret} could change between retries, and
     * the order's own state is already terminal by the time this is ever reachable, so re-reading
     * it is cheap and always correct), send, and log the outcome to {@code webhook_event}
     * regardless of success. Used by both {@link #sendOrderStatusChanged}'s first attempt and
     * {@code WebhookRetrySweepJob}'s later retries — the only two callers.
     */
    public OutboundWebhookAttemptResult attempt(Long parentOrderId, String eventType) {
        try {
            Optional<WebhookDeliveryTarget> target = targetResolver.resolve(parentOrderId);
            if (target.isEmpty()) {
                return OutboundWebhookAttemptResult.ofNoTarget();
            }
            WebhookDeliveryTarget t = target.get();

            OutboundWebhookDeliveryResult result = sender.send(t.callbackUrl(), t.webhookSecret(), t.orderNo(), t.state());

            webhookEventRecorder.recordIndependently(WebhookDirection.OUTBOUND, t.partnerCode(), eventType,
                    result.requestBody(), result.success() ? "SUCCESS" : "FAILED", null);

            return result.success()
                    ? OutboundWebhookAttemptResult.ofSuccess()
                    : OutboundWebhookAttemptResult.ofFailure(result.failureReason());
        } catch (Exception e) {
            log.error("Unexpected error while attempting outbound webhook delivery for parent_order {}", parentOrderId, e);
            return OutboundWebhookAttemptResult.ofFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
