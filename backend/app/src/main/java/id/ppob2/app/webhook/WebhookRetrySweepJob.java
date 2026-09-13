package id.ppob2.app.webhook;

import id.ppob2.webhook.WebhookDeliveryTracker;
import id.ppob2.webhook.domain.WebhookDelivery;
import id.ppob2.webhook.domain.WebhookDeliveryStatus;
import id.ppob2.webhook.repository.WebhookDeliveryRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PRD Section 23.8 / 40.4's "Webhook Retry Sweep" job. Lives in `app`, not `webhook` — same
 * reason {@link OutboundWebhookOrchestrator} does: retrying requires re-resolving the delivery
 * target ({@code parent_order.callback_url}, {@code partner.webhook_secret}), which needs the
 * `order`/`partner` join `webhook` has no Section 20.2 edge for. Same {@code @Scheduled} +
 * bounded-batch *shape* as {@code QrExpirySweepJob}, registered on the same
 * {@code SchedulingConfig}'s {@code @EnableScheduling} — but NOT the same safety claim; see the
 * concurrency note below, which does not carry over from that job's Javadoc despite the identical
 * shape.
 *
 * <p><b>Not safe for more than one app instance yet</b> — unlike {@code QrExpirySweepJob}, whose
 * own Javadoc correctly argues its race is benign ("both would write the identical target state").
 * Here, two instances racing on the same due row would both call {@link
 * OutboundWebhookOrchestrator#attempt} — meaning the partner receives the same
 * {@code ORDER_STATUS_CHANGED} webhook twice — and both {@link
 * WebhookDeliveryTracker#recordAttemptResult} calls would load-increment-save the same row with no
 * optimistic-locking guard (no {@code @Version} on {@code WebhookDelivery}), so one attempt's
 * accounting could silently overwrite the other's. Not a bug today (this codebase runs one
 * instance, no distributed lock infrastructure exists — same simplification as nonce storage and
 * the routing candidate cache elsewhere). The shape of the eventual fix is {@code SELECT ... FOR
 * UPDATE SKIP LOCKED} on {@code findByStatusAndNextAttemptAtBefore}, not a distributed lock,
 * since Postgres already serializes row access for free once instances stop reading the same
 * uncommitted row as "available."
 *
 * <p><b>Shares a scheduler thread with {@code QrExpirySweepJob}</b> — Spring's default
 * {@code @Scheduled} thread pool is a single thread, and {@code fixedDelay} waits for a tick to
 * finish before scheduling the next (of either job). A batch of up to {@code BATCH_SIZE} slow or
 * timing-out deliveries can occupy that one thread for a while, delaying the next
 * {@code QrExpirySweepJob} tick and, with it, unpaid orders past their QR TTL. Fixed by giving the
 * scheduler a second thread ({@code spring.task.scheduling.pool.size: 2} in application.yml) so
 * the two jobs don't contend — not by making either job's own transaction handling different,
 * which was never the actual constraint.
 *
 * <p>Not transactional itself, deliberately: each delivery's HTTP attempt
 * ({@link OutboundWebhookOrchestrator#attempt}) must not hold a connection open, and each
 * delivery's resulting state change ({@link WebhookDeliveryTracker#recordAttemptResult}) commits
 * in its own short {@code REQUIRES_NEW} transaction — one slow or unreachable partner endpoint in
 * a batch must not roll back or block the *database writes* for the others (this is about
 * transaction scope, not the scheduler-thread contention noted above, which is a separate concern
 * with a separate fix).
 */
@Component
public class WebhookRetrySweepJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetrySweepJob.class);

    /** Same reasoning as {@code QrExpirySweepJob.BATCH_SIZE}: bounds each tick's work. */
    private static final int BATCH_SIZE = 200;

    private final WebhookDeliveryRepository deliveryRepository;
    private final OutboundWebhookOrchestrator orchestrator;
    private final WebhookDeliveryTracker deliveryTracker;

    public WebhookRetrySweepJob(WebhookDeliveryRepository deliveryRepository,
                                 OutboundWebhookOrchestrator orchestrator,
                                 WebhookDeliveryTracker deliveryTracker) {
        this.deliveryRepository = deliveryRepository;
        this.orchestrator = orchestrator;
        this.deliveryTracker = deliveryTracker;
    }

    @Scheduled(fixedDelayString = "${ppob2.webhook.retry-sweep-interval-ms:60000}")
    public void sweep() {
        List<WebhookDelivery> due = deliveryRepository.findByStatusAndNextAttemptAtBefore(
                WebhookDeliveryStatus.PENDING, Instant.now(), Limit.of(BATCH_SIZE));

        if (due.isEmpty()) {
            return;
        }

        log.info("Webhook retry sweep: retrying {} pending delivery(ies)", due.size());
        for (WebhookDelivery delivery : due) {
            OutboundWebhookAttemptResult result = orchestrator.attempt(delivery.getParentOrderId(), delivery.getEventType());
            if (result.noTarget()) {
                // The target that made this deliverable in the first place (callback_url /
                // webhook_secret) is no longer resolvable — treated as a failed attempt, not
                // silently dropped, so it still counts toward exhaustion instead of retrying
                // forever against something that can never succeed.
                deliveryTracker.recordAttemptResult(delivery.getId(), false, "delivery target no longer resolvable");
            } else {
                deliveryTracker.recordAttemptResult(delivery.getId(), result.success(), result.failureReason());
            }
        }
    }
}
