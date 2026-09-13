package id.ppob2.webhook.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookDeliveryTest {

    @Test
    void startsAtAttemptCountOneSincePriorSynchronousAttemptAlreadyFailed() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "connection refused", Instant.now());

        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    }

    @Test
    void markDeliveredMovesToTerminalSuccessAndClearsScheduling() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "timeout", Instant.now());

        boolean applied = delivery.markDelivered(Instant.now());

        assertThat(applied).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLastFailureReason()).isNull();
    }

    @Test
    void markDeliveredIsANoOpOnAnAlreadyTerminalDelivery() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "timeout", Instant.now());
        delivery.markDelivered(Instant.now());

        boolean appliedAgain = delivery.markDelivered(Instant.now());

        assertThat(appliedAgain).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void recordFailureWithAFutureNextAttemptStaysPending() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "timeout", Instant.now());
        Instant nextAttempt = Instant.now().plusSeconds(3600);

        boolean applied = delivery.recordFailure(Instant.now(), "still timing out", nextAttempt);

        assertThat(applied).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(nextAttempt);
    }

    @Test
    void recordFailureWithNoNextAttemptMovesToExhausted() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "timeout", Instant.now());

        boolean applied = delivery.recordFailure(Instant.now(), "final failure", null);

        assertThat(applied).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void recordFailureIsANoOpOnAnAlreadyTerminalDelivery() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "timeout", Instant.now());
        delivery.recordFailure(Instant.now(), "final failure", null);

        boolean appliedAgain = delivery.recordFailure(Instant.now(), "another failure", Instant.now().plusSeconds(60));

        assertThat(appliedAgain).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
    }
}
