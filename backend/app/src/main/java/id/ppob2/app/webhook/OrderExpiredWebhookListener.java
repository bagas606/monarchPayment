package id.ppob2.app.webhook;

import id.ppob2.order.OrderExpiredEvent;
import id.ppob2.webhook.WebhookDeliveryTracker;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the partner that an unpaid order expired — PRD Section 23.8's {@code ORDER_STATUS_CHANGED},
 * for the Section 48.3 state change whose notification half this codebase had deliberately left
 * unbuilt.
 *
 * <p>Lives here, not in `order`, for the reason {@code QrExpirySweepJob}'s Javadoc already gave:
 * sending needs the {@code parent_order.callback_url} / {@code partner.webhook_secret_ciphertext} join that
 * Section 20.2 grants `order` no edge for. {@code AFTER_COMMIT} because the order must be durably
 * {@code EXPIRED} before anyone is told it is.
 *
 * <h2>Why this schedules instead of sending, unlike the fulfillment path</h2>
 * {@code FulfillmentDispatchListener} calls {@code sendOrderStatusChanged}, which makes one
 * synchronous HTTP attempt and schedules a retry only on failure. Doing that here would be wrong,
 * and the difference is the <em>call context</em>, not the message:
 *
 * <p>This listener runs in the {@code AFTER_COMMIT} callback of {@code expirePaymentPending}, which
 * {@code QrExpirySweepJob} calls in a loop over up to 200 orders — on the scheduler thread, once
 * per order. A synchronous attempt would therefore put up to 200 sequential HTTP calls inside one
 * sweep tick. Against a reachable partner that is tens of milliseconds each and nobody notices;
 * against an unreachable one it is 200 × the sender's 5s timeout, roughly seventeen minutes of a
 * scheduler thread, during which <b>no further orders expire</b>. And the failure correlates with
 * the harm: a partner being down is exactly when a backlog of unpaid orders is most likely.
 *
 * <p>That contention is not hypothetical — {@code application.yml} sets
 * {@code spring.task.scheduling.pool.size} (one thread per scheduled job) specifically so a slow webhook batch in
 * {@code WebhookRetrySweepJob} cannot delay {@code QrExpirySweepJob}. Sending from inside the expiry
 * sweep would move the same problem inside the thread that mitigation protects, where a second
 * thread does not help.
 *
 * <p>So the delivery is handed to {@link WebhookDeliveryTracker#scheduleFirstDelivery} and
 * {@code WebhookRetrySweepJob} makes every attempt, including the first — on its own thread, in its
 * own bounded batch, with each attempt's bookkeeping in its own short transaction, which is what
 * that job was built for. The cost is up to one sweep interval (60s by default) of latency on a
 * notification nothing is waiting on in real time. That trade is the whole argument: a minute of
 * delay on an expiry notice, against the expiry sweep itself stalling.
 *
 * <p><b>Resolves the target first, and schedules only if there is one.</b> Most expired orders have
 * no {@code callback_url} at all — {@code run-core.sh}'s and {@code run-routing-and-sweeps.sh}'s
 * orders send none, and Section 22.15 allows a null {@code partner_id} for future consumer channels.
 * Without this check every such order would get a {@code webhook_delivery} row that the sweep then
 * retries five times over ~23 hours against nothing, since {@code WebhookRetrySweepJob} counts
 * {@code noTarget} as a failed attempt. The resolve is a read, no HTTP, so it is cheap enough to do
 * on the sweep thread.
 *
 * <p><b>Never throws — but not for the reason it looks like.</b> The obvious justification would be
 * "an exception here would abort {@code QrExpirySweepJob}'s batch", and that is false. Checked
 * against spring-tx 6.1.13's bytecode rather than assumed: {@code AFTER_COMMIT} is dispatched from
 * {@code TransactionalApplicationListenerSynchronization$PlatformSynchronization.afterCompletion},
 * whose caller {@code TransactionSynchronizationUtils.invokeAfterCompletion} wraps every
 * synchronization in {@code catch (Throwable)}, logs "TransactionSynchronization.afterCompletion
 * threw exception", and carries on to the next one. So the sweep's loop never sees it and the rest
 * of the batch expires either way.
 *
 * <p>What the catch actually buys, which is still worth it:
 * <ul>
 *   <li>A log line that names the order and says the thing an operator needs to know — the order
 *       <em>is</em> expired, only the notification was lost — instead of the framework's generic
 *       one, which names neither.</li>
 *   <li>Independence from that framework detail. It is a documented-by-implementation behaviour, not
 *       a contract, and it is phase-specific: move this listener to {@code BEFORE_COMMIT} (a
 *       plausible-looking edit, since notifying before committing sounds safer) and an exception
 *       <em>would</em> propagate — and would roll back the expiry itself, losing the state change to
 *       save the notification about it. The catch makes that edit harmless.</li>
 * </ul>
 * Either way the preference is the same one {@link OutboundWebhookOrchestrator#attempt} already
 * makes: expiring orders matters more than announcing it.
 */
@Component
public class OrderExpiredWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiredWebhookListener.class);

    static final String EVENT_TYPE = "ORDER_STATUS_CHANGED";

    private final WebhookDeliveryTargetResolver targetResolver;
    private final WebhookDeliveryTracker deliveryTracker;

    public OrderExpiredWebhookListener(WebhookDeliveryTargetResolver targetResolver,
                                        WebhookDeliveryTracker deliveryTracker) {
        this.targetResolver = targetResolver;
        this.deliveryTracker = deliveryTracker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderExpired(OrderExpiredEvent event) {
        try {
            Optional<WebhookDeliveryTarget> target = targetResolver.resolve(event.parentOrderId());
            if (target.isEmpty()) {
                log.debug("parent_order {} expired with no resolvable webhook target — nothing to notify",
                        event.parentOrderId());
                return;
            }
            deliveryTracker.scheduleFirstDelivery(event.parentOrderId(), EVENT_TYPE);
            log.info("parent_order {} expired — queued an {} callback to {} for the retry sweep",
                    event.parentOrderId(), EVENT_TYPE, target.get().partnerCode());
        } catch (Exception e) {
            log.error("Failed to queue an expiry callback for parent_order {} — the order IS expired; "
                    + "only the notification was lost", event.parentOrderId(), e);
        }
    }
}
