package id.ppob2.order;

import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PRD Section 48.3: "QR Expiry Sweep Job" — {@code findExpiredUnpaidOrders()} then transitions
 * each to {@code EXPIRED}. Lives in `order` (not `app`) because everything this needs —
 * {@code parent_order.expires_at}, the state transition — is `order`'s own data; no composition
 * with another module is required for the sweep itself. The outbound {@code ORDER_STATUS_CHANGED}
 * webhook the same diagram shows IS a composition-root concern (it needs the
 * {@code callback_url}/{@code partner.webhook_secret_ciphertext} join `order` has no Section 20.2 edge for), and was
 * for a long time deliberately not built. It is now: {@code expirePaymentPending} publishes
 * {@link OrderExpiredEvent} and {@code OrderExpiredWebhookListener} in `app` queues the delivery.
 *
 * <p>That listener deliberately <b>queues</b> rather than sends, and the reason is this job's own
 * loop: an {@code AFTER_COMMIT} send would run inline here, once per order, so one unreachable
 * partner would occupy this thread for {@code BATCH_SIZE} × the HTTP timeout and stop anything else
 * expiring. Keep that in mind before "simplifying" it into a direct call.
 *
 * <p>Requires {@code @EnableScheduling} somewhere in the application context — added on `app`'s
 * {@code SchedulingConfig}, kept separate from {@code JpaConfig} for the same reason: avoid
 * pulling scheduling infrastructure into slice tests that don't need it.
 *
 * <p>No distributed lock / leader election across app instances (same Redis-shaped simplification
 * as nonce storage and the routing candidate cache elsewhere in this codebase) — benign for this
 * specific transition even if two instances race on the same order, since both would write the
 * identical target state ({@code EXPIRED}) with no real conflict, not a correctness gap worth
 * solving before there's more than one instance.
 */
@Component
public class QrExpirySweepJob {

    private static final Logger log = LoggerFactory.getLogger(QrExpirySweepJob.class);

    /** Bounds each tick's work — see {@code ParentOrderRepository.findByStateAndExpiresAtBefore}'s
     * Javadoc for why an unbounded query here would be a problem, not just a style choice. */
    private static final int BATCH_SIZE = 200;

    private final ParentOrderRepository parentOrderRepository;
    private final ParentOrderTransitionService transitionService;

    public QrExpirySweepJob(ParentOrderRepository parentOrderRepository, ParentOrderTransitionService transitionService) {
        this.parentOrderRepository = parentOrderRepository;
        this.transitionService = transitionService;
    }

    @Scheduled(fixedDelayString = "${ppob2.order.expiry-sweep-interval-ms:60000}")
    public void sweep() {
        List<ParentOrder> expired = parentOrderRepository.findByStateAndExpiresAtBefore(
                OrderState.PAYMENT_PENDING, Instant.now(), Limit.of(BATCH_SIZE));

        if (expired.isEmpty()) {
            return;
        }

        log.info("QR expiry sweep: expiring {} parent_order(s) past their QR TTL", expired.size());
        for (ParentOrder order : expired) {
            transitionService.expirePaymentPending(order.getId());
        }
    }
}
