package id.ppob2.webhook;

import id.ppob2.webhook.domain.WebhookDirection;
import id.ppob2.webhook.domain.WebhookEvent;
import id.ppob2.webhook.repository.WebhookEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The generic webhook log every source writes through — Section 20.1 assigns "inbound and
 * outbound webhook handling" to this module regardless of who's calling. */
@Service
public class WebhookEventRecorder {

    private final WebhookEventRepository repository;

    public WebhookEventRecorder(WebhookEventRepository repository) {
        this.repository = repository;
    }

    /** For callers already inside their own transaction (e.g. {@code PaymentCallbackService
     * .processCallback}) — joins it, so this row rolls back together with whatever it's logging
     * if that transaction fails, which is the correct behavior for an inbound-callback audit
     * entry recorded mid-processing. */
    public void record(WebhookDirection direction, String source, String eventType, String payload,
                        String status, String dedupKey) {
        repository.save(new WebhookEvent(direction, source, eventType, payload, status, dedupKey, Instant.now()));
    }

    /** For callers with no ambient transaction to join — notably an outbound delivery attempt
     * made from an {@code AFTER_COMMIT} listener's call tree ({@code OutboundWebhookOrchestrator}),
     * after an HTTP call that must not run inside a transaction. {@code REQUIRES_NEW} makes the
     * commit explicit and real rather than relying on Spring Data's default per-method
     * transactionality, which this codebase's established rule already distrusts for any write
     * reached from that call tree (proven necessary the hard way: a first attempt without this
     * annotation called {@link #record} directly and the row was silently never persisted).
     *
     * <p>Delegates to {@link #record} via plain self-invocation, which is safe in this direction
     * only: the {@code @Transactional} annotation is on this method, the one Spring's proxy
     * actually intercepts when called from another bean. Do not "simplify" this by inlining
     * {@code record}'s body here and deleting {@code record} — the inbound path
     * ({@code PaymentCallbackService}) calls {@link #record} directly and depends on it joining
     * *that* caller's own transaction, not opening a new one. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(WebhookDirection direction, String source, String eventType, String payload,
                                     String status, String dedupKey) {
        record(direction, source, eventType, payload, status, dedupKey);
    }
}
