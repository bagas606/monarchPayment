package id.ppob2.app.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import id.ppob2.order.OrderExpiredEvent;
import id.ppob2.webhook.WebhookDeliveryTracker;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Three properties, each of which costs something real if it breaks.
 *
 * <p>{@link #schedulesNothingWhenNoTargetResolves} is the one with teeth. Most expired orders carry
 * no {@code callback_url} at all — {@code run-core.sh}'s and {@code run-routing-and-sweeps.sh}'s
 * orders send none, and Section 22.15 permits a null {@code partner_id} for future consumer
 * channels. Scheduling unconditionally would give every one of them a {@code webhook_delivery} row
 * that {@code WebhookRetrySweepJob} then retries five times over ~23 hours against nothing, because
 * that job counts {@code noTarget} as a failed attempt.
 *
 * <p>{@link #neverThrowsOutOfTheAfterCommitCallback} is not guarding the batch, and the Javadoc
 * that said so was wrong. Verified against spring-tx 6.1.13's bytecode:
 * {@code TransactionSynchronizationUtils.invokeAfterCompletion} wraps every synchronization in
 * {@code catch (Throwable)} and continues, so the sweep's loop never sees an exception from here
 * regardless. What these two tests pin is the local guarantee — a useful log line instead of the
 * framework's generic one, and immunity to the phase being changed to {@code BEFORE_COMMIT}, where
 * a throw would propagate and roll back the expiry itself. See the listener's Javadoc.
 */
class OrderExpiredWebhookListenerTest {

    private final WebhookDeliveryTargetResolver targetResolver = mock(WebhookDeliveryTargetResolver.class);
    private final WebhookDeliveryTracker deliveryTracker = mock(WebhookDeliveryTracker.class);

    private final OrderExpiredWebhookListener listener =
            new OrderExpiredWebhookListener(targetResolver, deliveryTracker);

    private static WebhookDeliveryTarget target() {
        return new WebhookDeliveryTarget("http://ppob1.example.com/cb", "secret-123", "PPOB1",
                "ORD-1", "EXPIRED", "PPOB1-TX-1");
    }

    @Test
    void queuesTheCallbackForTheSweepRatherThanSendingItHere() {
        given(targetResolver.resolve(7L)).willReturn(Optional.of(target()));

        listener.onOrderExpired(new OrderExpiredEvent(7L));

        // Queued, not sent: no OutboundWebhookOrchestrator is even injected here, which is the
        // design — the HTTP must not run on QrExpirySweepJob's thread. See the listener's Javadoc.
        verify(deliveryTracker).scheduleFirstDelivery(7L, OrderExpiredWebhookListener.EVENT_TYPE);
    }

    @Test
    void schedulesNothingWhenNoTargetResolves() {
        given(targetResolver.resolve(7L)).willReturn(Optional.empty());

        listener.onOrderExpired(new OrderExpiredEvent(7L));

        verifyNoInteractions(deliveryTracker);
    }

    @Test
    void neverThrowsOutOfTheAfterCommitCallback() {
        given(targetResolver.resolve(7L)).willThrow(new IllegalStateException("parent_order vanished"));

        assertThatCode(() -> listener.onOrderExpired(new OrderExpiredEvent(7L))).doesNotThrowAnyException();

        verify(deliveryTracker, never()).scheduleFirstDelivery(anyLong(), anyString());
    }

    @Test
    void aFailureSchedulingTheDeliveryIsAlsoSwallowed() {
        // The realistic case: a race on webhook_delivery_order_event_uk. The order IS expired by
        // the time this runs -- only the notification is lost, and that is what the log line has to
        // say.
        given(targetResolver.resolve(7L)).willReturn(Optional.of(target()));
        org.mockito.BDDMockito.willThrow(new RuntimeException("duplicate key"))
                .given(deliveryTracker).scheduleFirstDelivery(any(), anyString());

        assertThatCode(() -> listener.onOrderExpired(new OrderExpiredEvent(7L))).doesNotThrowAnyException();
    }
}
