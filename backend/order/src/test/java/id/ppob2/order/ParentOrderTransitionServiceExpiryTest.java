package id.ppob2.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.decomposition.PatternComponentQueryService;
import id.ppob2.decomposition.PatternSelectionOrchestrator;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.payment.repository.PaymentRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@code ParentOrderTransitionService.expirePaymentPending}'s guard — skip anything not
 * {@code PAYMENT_PENDING} — is the only thing standing between the QR expiry sweep and expiring
 * an order a customer already paid for. The end-to-end run for the QR-expiry slice only ever
 * exercised one non-`PAYMENT_PENDING` state (`REFUND_PENDING`, reached via BR-DEC exhaustion with
 * no pattern seeded); it never reached `PAID`/`FULFILLING`/`SUCCESS` — states that need a
 * decomposition pattern seeded to reach at all — and `SUCCESS` is the one where the guard failing
 * would be worst: {@code OrderStateMachine} has no {@code SUCCESS -> EXPIRED} edge, so
 * {@code transitionTo} would throw rather than the guard cleanly returning false, aborting the
 * rest of that sweep tick's batch. This fills that gap with a Mockito test reaching every state
 * class for free — mocked repository, so this verifies guard behavior, not persistence (the e2e
 * run already proved the PAYMENT_PENDING write path lands against real Postgres).
 */
class ParentOrderTransitionServiceExpiryTest {

    private final ParentOrderRepository orderRepository = mock(ParentOrderRepository.class);
    private final PatternSelectionOrchestrator patternSelectionOrchestrator = mock(PatternSelectionOrchestrator.class);
    private final PatternComponentQueryService patternComponentQueryService = mock(PatternComponentQueryService.class);
    private final ChildOrderService childOrderService = mock(ChildOrderService.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ParentOrderTransitionService service = new ParentOrderTransitionService(
            orderRepository, patternSelectionOrchestrator, patternComponentQueryService, childOrderService,
            paymentRepository, eventPublisher);

    private static ParentOrder paymentPendingOrder() {
        ParentOrder order = new ParentOrder("ORD-1", 1L, 1L, "client-1", 1L, Money.of(20000L),
                "API", "idem-1", "cust-ref", Instant.now());
        order.transitionTo(OrderState.PAYMENT_PENDING);
        return order;
    }

    @Test
    void expiresAPaymentPendingOrderAndItsPayment() {
        ParentOrder order = paymentPendingOrder();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        Payment payment = new Payment(1L, "PG-REF", "qr", Money.of(20000L), PaymentStatus.PENDING, Instant.now());
        given(paymentRepository.findByParentOrderId(1L)).willReturn(Optional.of(payment));

        boolean expired = service.expirePaymentPending(1L);

        assertThat(expired).isTrue();
        assertThat(order.getState()).isEqualTo(OrderState.EXPIRED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void doesNotTouchAnOrderThatAlreadyReachedSuccess() {
        ParentOrder order = paymentPendingOrder();
        order.transitionTo(OrderState.PAID);
        order.setPatternId(1L);
        order.transitionTo(OrderState.DECOMPOSITION_SELECTED);
        order.transitionTo(OrderState.FULFILLING);
        order.transitionTo(OrderState.SUCCESS);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        boolean expired = service.expirePaymentPending(1L);

        assertThat(expired).isFalse();
        assertThat(order.getState()).isEqualTo(OrderState.SUCCESS);
        verify(paymentRepository, never()).findByParentOrderId(any());
    }

    @Test
    void expiresTheOrderButLeavesAnAlreadySuccessfulPaymentAlone() {
        // The race the sweep must tolerate: a payment callback commits SUCCESS between the
        // sweep's query and this transition running, but the order row read here still shows
        // PAYMENT_PENDING (e.g. read just before that commit).
        ParentOrder order = paymentPendingOrder();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        Payment payment = new Payment(1L, "PG-REF", "qr", Money.of(20000L), PaymentStatus.SUCCESS, Instant.now());
        given(paymentRepository.findByParentOrderId(1L)).willReturn(Optional.of(payment));

        boolean expired = service.expirePaymentPending(1L);

        assertThat(expired).isTrue();
        assertThat(order.getState()).isEqualTo(OrderState.EXPIRED);
        // markExpired() on an already-SUCCESS payment returns false and is logged, not thrown —
        // the payment row is left exactly as it was.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }
}
