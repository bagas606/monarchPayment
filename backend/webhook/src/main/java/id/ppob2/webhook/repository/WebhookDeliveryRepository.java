package id.ppob2.webhook.repository;

import id.ppob2.webhook.domain.WebhookDelivery;
import id.ppob2.webhook.domain.WebhookDeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    Optional<WebhookDelivery> findByParentOrderIdAndEventType(Long parentOrderId, String eventType);

    /** {@link Limit} bounds each sweep tick's work — same reasoning as
     * {@code ParentOrderRepository.findByStateAndExpiresAtBefore}'s Javadoc: an unbounded query
     * here would let one slow tick's batch grow without limit as delivery volume grows. */
    List<WebhookDelivery> findByStatusAndNextAttemptAtBefore(WebhookDeliveryStatus status, Instant instant, Limit limit);
}
