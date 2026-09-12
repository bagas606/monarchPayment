package id.ppob2.app.reconciliation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.order.ChildOrderService;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mocked-repository test covering the branches the real end-to-end run (documented in
 * backend/README.md) couldn't reach in one pass: a stuck {@code FULFILLING} order, a fully
 * successful order (nothing to reconcile), and the duplicate-open guard. The e2e run already
 * proved the "first OPEN record" write path against real Postgres with correct expected/actual
 * values (60000/40000/-20000 for a two-component pattern with one failed child) — this only adds
 * the branches that run doesn't exercise, mocked repository so it does not re-prove persistence.
 */
class OrderFulfillmentReconciliationOrchestratorTest {

    private final ParentOrderRepository parentOrderRepository = mock(ParentOrderRepository.class);
    private final ChildOrderService childOrderService = mock(ChildOrderService.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
    private final OrderFulfillmentReconciliationOrchestrator orchestrator = new OrderFulfillmentReconciliationOrchestrator(
            parentOrderRepository, childOrderService, reconciliationService);

    private static ParentOrder orderInState(OrderState state) {
        ParentOrder order = new ParentOrder("ORD-1", 1L, 1L, "client-1", 1L, Money.of(60000L),
                "API", "idem-1", "cust-ref", Instant.now());
        order.transitionTo(OrderState.PAYMENT_PENDING);
        if (state == OrderState.REFUND_PENDING) {
            // Reached via BR-DEC exhaustion — never enters FULFILLING at all.
            order.transitionTo(OrderState.PAID);
            order.transitionTo(state);
            return order;
        }
        order.transitionTo(OrderState.PAID);
        order.setPatternId(1L);
        order.transitionTo(OrderState.DECOMPOSITION_SELECTED);
        order.transitionTo(OrderState.FULFILLING);
        if (state != OrderState.FULFILLING) {
            order.transitionTo(state);
        }
        return order;
    }

    @Test
    void skipsAStillFulfillingOrderRatherThanComparingMidFlightNumbers() {
        ParentOrder order = orderInState(OrderState.FULFILLING);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
        verify(childOrderService, never()).findByParentOrderId(anyLong());
    }

    @Test
    void aFullySuccessfulOrderOpensNothing() {
        ParentOrder order = orderInState(OrderState.SUCCESS);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
    }

    @Test
    void aRefundPendingOrderOpensNothingEither() {
        // REFUND_PENDING (BR-DEC exhaustion, no pattern ever selected) is not a fulfillment
        // discrepancy — FULFILLING is never entered on that path, so there is nothing to compare.
        ParentOrder order = orderInState(OrderState.REFUND_PENDING);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
    }

    @Test
    void doesNotOpenASecondRecordWhileOneIsAlreadyOpen() {
        ParentOrder order = orderInState(OrderState.PARTIAL_FAILED);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        given(reconciliationService.hasOpenDiscrepancy(ReconciliationType.ORDER_VS_FULFILLMENT, 1L)).willReturn(true);

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
        verify(childOrderService, never()).findByParentOrderId(anyLong());
    }

    @Test
    void partialFailureOpensWithExpectedMinusSuccessfulActual() {
        ParentOrder order = orderInState(OrderState.PARTIAL_FAILED);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        given(reconciliationService.hasOpenDiscrepancy(ReconciliationType.ORDER_VS_FULFILLMENT, 1L)).willReturn(false);
        ChildOrder success = mock(ChildOrder.class);
        given(success.getState()).willReturn(id.ppob2.order.domain.ChildOrderState.SUCCESS);
        given(success.getFaceValue()).willReturn(Money.of(40000L));
        ChildOrder failed = mock(ChildOrder.class);
        given(failed.getState()).willReturn(id.ppob2.order.domain.ChildOrderState.FAILED);
        given(childOrderService.findByParentOrderId(1L)).willReturn(List.of(success, failed));

        orchestrator.reconcile(1L);

        verify(reconciliationService).open(ReconciliationType.ORDER_VS_FULFILLMENT, java.time.LocalDate.now(), 1L,
                Money.of(60000L), Money.of(40000L));
    }
}
