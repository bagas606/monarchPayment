package id.ppob2.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.webhook.domain.WebhookDelivery;
import id.ppob2.webhook.domain.WebhookDeliveryStatus;
import id.ppob2.webhook.repository.WebhookDeliveryRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Backoff config under test is {@code "1,2,3"} (3 delays -> 4 total attempts) specifically so the
 * exhaustion boundary is reachable in a handful of asserts, not the real default's 5.
 */
class WebhookDeliveryTrackerTest {

    private final WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
    private final WebhookDeliveryTracker tracker = new WebhookDeliveryTracker(repository, "1,2,3");

    @Test
    void scheduleFirstRetryPersistsANewRowWithTheFirstBackoffDelay() {
        given(repository.findByParentOrderIdAndEventType(1L, "ORDER_STATUS_CHANGED")).willReturn(Optional.empty());
        Instant before = Instant.now();

        tracker.scheduleFirstRetry(1L, "ORDER_STATUS_CHANGED", "connection refused");

        org.mockito.ArgumentCaptor<WebhookDelivery> captor = org.mockito.ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(repository).save(captor.capture());
        WebhookDelivery saved = captor.getValue();
        assertThat(saved.getParentOrderId()).isEqualTo(1L);
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getNextAttemptAt()).isAfter(before.plusSeconds(59)).isBefore(before.plusSeconds(61));
    }

    @Test
    void scheduleFirstRetryDoesNotDuplicateAnExistingDelivery() {
        given(repository.findByParentOrderIdAndEventType(1L, "ORDER_STATUS_CHANGED"))
                .willReturn(Optional.of(new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "x", Instant.now())));

        tracker.scheduleFirstRetry(1L, "ORDER_STATUS_CHANGED", "connection refused");

        verify(repository, never()).save(any());
    }

    @Test
    void scheduleFirstDeliveryPersistsARowThatHasNotBeenAttempted() {
        given(repository.findByParentOrderIdAndEventType(1L, "ORDER_STATUS_CHANGED")).willReturn(Optional.empty());
        Instant before = Instant.now();

        tracker.scheduleFirstDelivery(1L, "ORDER_STATUS_CHANGED");

        org.mockito.ArgumentCaptor<WebhookDelivery> captor = org.mockito.ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(repository).save(captor.capture());
        WebhookDelivery saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        // Zero, and this is the whole point of the second constructor: reusing the four-argument
        // one would claim an attempt nothing made, costing this delivery one of its attempts.
        assertThat(saved.getAttemptCount()).isZero();
        assertThat(saved.getLastAttemptAt()).isNull();
        assertThat(saved.getLastFailureReason()).isNull();
        // Due now, not after a backoff: there has been no failure to back off from.
        assertThat(saved.getNextAttemptAt()).isBetween(before, Instant.now());
    }

    @Test
    void scheduleFirstDeliveryWillNotCreateASecondRowForTheSameOrderAndEvent() {
        // Not defensive tidiness: this runs from an AFTER_COMMIT callback, where Spring swallows
        // whatever is thrown, so a constraint violation would surface as a framework log line
        // naming neither the order nor the cause.
        given(repository.findByParentOrderIdAndEventType(1L, "ORDER_STATUS_CHANGED"))
                .willReturn(Optional.of(new WebhookDelivery(1L, "ORDER_STATUS_CHANGED")));

        tracker.scheduleFirstDelivery(1L, "ORDER_STATUS_CHANGED");

        verify(repository, never()).save(any());
    }

    /**
     * The arithmetic is shared by both origins and branches on neither, so the only thing keeping
     * the attempt budget right is the starting count. With 3 configured delays the budget is 4
     * total attempts either way: this path spends all 4 in the sweep, while a
     * {@code scheduleFirstRetry} row has already spent one synchronously and gets 3 more (asserted
     * in the test above). Off by one here and an expiry callback silently gets one fewer chance.
     */
    @Test
    void aNeverAttemptedDeliveryGetsTheFullAttemptBudgetInTheSweep() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED");
        given(repository.findById(10L)).willReturn(Optional.of(delivery));

        tracker.recordAttemptResult(10L, false, "attempt 1 failed");
        assertThat(delivery.getStatus()).as("after 1st failed attempt").isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastAttemptAt()).as("no longer unattempted").isNotNull();

        tracker.recordAttemptResult(10L, false, "attempt 2 failed");
        tracker.recordAttemptResult(10L, false, "attempt 3 failed");
        assertThat(delivery.getStatus()).as("after 3rd failed attempt").isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isNotNull();

        tracker.recordAttemptResult(10L, false, "attempt 4 failed");
        assertThat(delivery.getStatus()).as("after 4th (final) failed attempt").isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(delivery.getAttemptCount()).isEqualTo(4);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void successfulRetryMarksDelivered() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "x", Instant.now());
        given(repository.findById(10L)).willReturn(Optional.of(delivery));

        tracker.recordAttemptResult(10L, true, null);

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    /**
     * The exact boundary advisor-class bugs live at in this codebase: with 3 configured backoff
     * delays (max 4 total attempts), the delivery must still be PENDING after attempts 2 and 3
     * fail, and only become EXHAUSTED after attempt 4 fails — not one attempt earlier or later.
     */
    @Test
    void exhaustsExactlyAfterBackoffListSizePlusOneFailedAttemptsNotBeforeOrAfter() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "attempt 1 failed", Instant.now());
        given(repository.findById(10L)).willReturn(Optional.of(delivery));

        tracker.recordAttemptResult(10L, false, "attempt 2 failed");
        assertThat(delivery.getStatus()).as("after 2nd failed attempt").isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getNextAttemptAt()).isNotNull();

        tracker.recordAttemptResult(10L, false, "attempt 3 failed");
        assertThat(delivery.getStatus()).as("after 3rd failed attempt").isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        assertThat(delivery.getNextAttemptAt()).isNotNull();

        tracker.recordAttemptResult(10L, false, "attempt 4 failed");
        assertThat(delivery.getStatus()).as("after 4th (final) failed attempt").isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(delivery.getAttemptCount()).isEqualTo(4);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void recordAttemptResultOnAnAlreadyTerminalDeliveryIsIgnoredNotReopened() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "ORDER_STATUS_CHANGED", "x", Instant.now());
        delivery.markDelivered(Instant.now());
        given(repository.findById(10L)).willReturn(Optional.of(delivery));

        tracker.recordAttemptResult(10L, false, "should be ignored");

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void rejectsBlankBackoffConfigAtConstructionRatherThanFailingLaterAtRuntime() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WebhookDeliveryTracker(repository, ""))
                .isInstanceOf(RuntimeException.class);
    }
}
