package id.ppob2.webhook;

import id.ppob2.webhook.domain.WebhookDelivery;
import id.ppob2.webhook.domain.WebhookDeliveryStatus;
import id.ppob2.webhook.repository.WebhookDeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the retry backoff schedule (PRD Section 23.8: "5 attempts over 24h", configurable) and
 * every write to {@code webhook_delivery} — see {@link WebhookDelivery}'s Javadoc for why that
 * table exists alongside {@link WebhookEventRecorder}'s per-attempt log rather than replacing it.
 *
 * <p>{@code ppob2.webhook.retry-backoff-minutes} lists the delay *between* attempts, not the
 * attempts themselves: the first (synchronous, made before this class is ever involved) attempt
 * plus one retry per configured delay gives a total attempt count of
 * {@code backoffMinutes.size() + 1}. The default {@code 5,60,240,1080} (minutes) yields 5 total
 * attempts spanning ~23h — matching the PRD's "e.g." example without hardcoding it.
 */
@Service
public class WebhookDeliveryTracker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryTracker.class);

    private final WebhookDeliveryRepository repository;
    private final List<Integer> backoffMinutes;

    public WebhookDeliveryTracker(WebhookDeliveryRepository repository,
                                   @Value("${ppob2.webhook.retry-backoff-minutes:5,60,240,1080}") String backoffMinutesCsv) {
        this.repository = repository;
        this.backoffMinutes = Arrays.stream(backoffMinutesCsv.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();
        if (this.backoffMinutes.isEmpty()) {
            throw new IllegalArgumentException("ppob2.webhook.retry-backoff-minutes must list at least one delay");
        }
    }

    /**
     * Called once, right after the orchestrator's own synchronous first attempt fails. Guarded
     * against creating a second row for the same (order, event type) — the synchronous attempt
     * only ever runs once per terminal-state transition, so a duplicate here would mean this
     * method was called twice for the same logical delivery, not a legitimate second delivery.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleFirstRetry(Long parentOrderId, String eventType, String failureReason) {
        if (repository.findByParentOrderIdAndEventType(parentOrderId, eventType).isPresent()) {
            log.warn("webhook_delivery already exists for parent_order {} / event_type {} — not scheduling a duplicate",
                    parentOrderId, eventType);
            return;
        }
        Instant nextAttemptAt = Instant.now().plus(Duration.ofMinutes(backoffMinutes.get(0)));
        repository.save(new WebhookDelivery(parentOrderId, eventType, failureReason, nextAttemptAt));
    }

    /**
     * Called by the sweep job after each retry attempt. {@code REQUIRES_NEW} for the same reason
     * every other write reached from an {@code AFTER_COMMIT}-shaped call tree in this codebase
     * uses it — the sweep job itself is not transactional (its HTTP attempt must not hold a
     * connection open), so each delivery's result gets its own short, real transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttemptResult(Long deliveryId, boolean success, String failureReason) {
        WebhookDelivery delivery = repository.findById(deliveryId)
                .orElseThrow(() -> new IllegalStateException("webhook_delivery " + deliveryId + " vanished during retry sweep"));

        Instant now = Instant.now();
        if (success) {
            if (!delivery.markDelivered(now)) {
                log.warn("Ignoring successful retry for webhook_delivery {}: already in terminal state", deliveryId);
            }
            return;
        }

        int attemptNumberJustMade = delivery.getAttemptCount() + 1;
        int maxAttempts = backoffMinutes.size() + 1;
        Instant nextAttemptAt = attemptNumberJustMade >= maxAttempts
                ? null
                : now.plus(Duration.ofMinutes(backoffMinutes.get(attemptNumberJustMade - 1)));

        boolean applied = delivery.recordFailure(now, failureReason, nextAttemptAt);
        if (!applied) {
            log.warn("Ignoring failed retry for webhook_delivery {}: already in terminal state", deliveryId);
        } else if (delivery.getStatus() == WebhookDeliveryStatus.EXHAUSTED) {
            log.warn("webhook_delivery {} exhausted after {} attempts (parent_order_id={}, event_type={}): {}",
                    deliveryId, attemptNumberJustMade, delivery.getParentOrderId(), delivery.getEventType(), failureReason);
        }
    }
}
