package id.ppob2.app.webhook;

import id.ppob2.webhook.OutboundWebhookDeliveryResult;
import id.ppob2.webhook.OutboundWebhookSender;
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
 * <p><b>Scope of this slice</b>: exactly one delivery attempt, outcome recorded in
 * {@code webhook_event} (direction OUTBOUND). Section 23.8's "5 attempts over 24h" retry schedule
 * is not built — no persisted attempt count, no scheduled re-driver, no dead-letter/inquiry
 * fallback. Trigger coverage is also partial: only the post-dispatch terminal states
 * (SUCCESS/PARTIAL_FAILED/FAILED) fire this; CANCELLED/EXPIRED/REFUNDED transitions and the QR
 * expiry sweep's own state change (Section 48.3's diagram) do not, and are not newly covered by
 * this slice — flagged in the README, not silently left implied as done.
 *
 * <p>This method itself is not transactional: resolving the target is a separate
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

    public OutboundWebhookOrchestrator(WebhookDeliveryTargetResolver targetResolver,
                                        OutboundWebhookSender sender,
                                        WebhookEventRecorder webhookEventRecorder) {
        this.targetResolver = targetResolver;
        this.sender = sender;
        this.webhookEventRecorder = webhookEventRecorder;
    }

    public void sendOrderStatusChanged(Long parentOrderId) {
        try {
            Optional<WebhookDeliveryTarget> target = targetResolver.resolve(parentOrderId);
            if (target.isEmpty()) {
                return;
            }
            WebhookDeliveryTarget t = target.get();

            OutboundWebhookDeliveryResult result = sender.send(t.callbackUrl(), t.webhookSecret(), t.orderNo(), t.state());

            webhookEventRecorder.recordIndependently(WebhookDirection.OUTBOUND, t.partnerCode(), "ORDER_STATUS_CHANGED",
                    result.requestBody(), result.success() ? "SUCCESS" : "FAILED", null);

            if (!result.success()) {
                log.warn("Outbound webhook delivery failed for parent_order {} (order_no={}): {}",
                        parentOrderId, t.orderNo(), result.failureReason());
            }
        } catch (Exception e) {
            log.error("Unexpected error while attempting outbound webhook delivery for parent_order {}", parentOrderId, e);
        }
    }
}
