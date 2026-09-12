package id.ppob2.app.reconciliation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.ledger.repository.LedgerEntryRepository;
import id.ppob2.order.ChildOrderService;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.pricing.domain.PatternEconomics;
import id.ppob2.pricing.repository.PatternEconomicsRepository;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mocked-repository test covering branches the real end-to-end run (documented in
 * backend/README.md, which proved a genuine drift case AND a zero-drift no-op case against real
 * Postgres with real ledger entries) didn't need to isolate on its own: non-SUCCESS states, no
 * pattern selected, no pattern_economics snapshot to compare against, and the duplicate-open
 * guard.
 */
class MarginReconciliationOrchestratorTest {

    private final ParentOrderRepository parentOrderRepository = mock(ParentOrderRepository.class);
    private final ChildOrderService childOrderService = mock(ChildOrderService.class);
    private final PatternEconomicsRepository patternEconomicsRepository = mock(PatternEconomicsRepository.class);
    private final LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
    private final MarginReconciliationOrchestrator orchestrator = new MarginReconciliationOrchestrator(
            parentOrderRepository, childOrderService, patternEconomicsRepository, ledgerEntryRepository, reconciliationService);

    private static ParentOrder orderInState(OrderState state, Long patternId) {
        ParentOrder order = new ParentOrder("ORD-1", 1L, 1L, "client-1", 1L, Money.of(60000L),
                "API", "idem-1", "cust-ref", Instant.now());
        order.transitionTo(OrderState.PAYMENT_PENDING);
        order.transitionTo(OrderState.PAID);
        if (patternId == null) {
            order.transitionTo(OrderState.REFUND_PENDING);
            return order;
        }
        order.setPatternId(patternId);
        order.transitionTo(OrderState.DECOMPOSITION_SELECTED);
        order.transitionTo(OrderState.FULFILLING);
        if (state != OrderState.FULFILLING) {
            order.transitionTo(state);
        }
        return order;
    }

    @Test
    void partialFailedOrderIsSkippedEntirely() {
        ParentOrder order = orderInState(OrderState.PARTIAL_FAILED, 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
        verify(patternEconomicsRepository, never()).findTopByPatternIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(any(), any());
    }

    @Test
    void noPatternSelectedIsSkipped() {
        ParentOrder order = orderInState(OrderState.SUCCESS, null);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
    }

    @Test
    void noPatternEconomicsSnapshotIsSkipped() {
        ParentOrder order = orderInState(OrderState.SUCCESS, 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        given(patternEconomicsRepository.findTopByPatternIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(eq(1L), any(LocalDate.class)))
                .willReturn(Optional.empty());

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
        verify(childOrderService, never()).findByParentOrderId(anyLong());
    }

    @Test
    void doesNotOpenASecondRecordWhileOneIsAlreadyOpen() {
        ParentOrder order = orderInState(OrderState.SUCCESS, 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        PatternEconomics economics = mock(PatternEconomics.class);
        given(patternEconomicsRepository.findTopByPatternIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(eq(1L), any(LocalDate.class)))
                .willReturn(Optional.of(economics));
        given(reconciliationService.hasOpenDiscrepancy(ReconciliationType.MARGIN_EXPECTED_VS_ACTUAL, 1L)).willReturn(true);

        orchestrator.reconcile(1L);

        verify(reconciliationService, never()).open(any(), any(), anyLong(), any(), any());
        verify(childOrderService, never()).findByParentOrderId(anyLong());
    }

    @Test
    void mismatchedProjectionOpensADiscrepancy() {
        ParentOrder order = orderInState(OrderState.SUCCESS, 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        PatternEconomics economics = mock(PatternEconomics.class);
        given(economics.getGrossProfit()).willReturn(Money.of(30000L));
        given(patternEconomicsRepository.findTopByPatternIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(eq(1L), any(LocalDate.class)))
                .willReturn(Optional.of(economics));
        given(reconciliationService.hasOpenDiscrepancy(ReconciliationType.MARGIN_EXPECTED_VS_ACTUAL, 1L)).willReturn(false);
        ChildOrder success = mock(ChildOrder.class);
        given(success.getState()).willReturn(ChildOrderState.SUCCESS);
        given(success.getProviderTransactionId()).willReturn(100L);
        given(childOrderService.findByParentOrderId(1L)).willReturn(List.of(success));
        // A realistic fixture: FulfillmentExecutionService/ProviderLedgerPoster always posts a
        // DEBIT for a newly-inserted successful purchase (verified in the fulfillment slice), so
        // a SUCCESS child with a provider_transaction_id but no ledger entry cannot occur.
        id.ppob2.ledger.domain.LedgerEntry debit = mock(id.ppob2.ledger.domain.LedgerEntry.class);
        given(debit.getAmount()).willReturn(Money.of(36000L));
        given(ledgerEntryRepository.findByLedgerTypeAndReferenceTypeAndReferenceIdIn(any(), any(), any()))
                .willReturn(List.of(debit));

        orchestrator.reconcile(1L);

        // actual = parentAmount(60000) - actualCost(36000) = 24000, which does NOT match the
        // 30000 projection above.
        verify(reconciliationService).open(eq(ReconciliationType.MARGIN_EXPECTED_VS_ACTUAL), any(), eq(1L),
                eq(Money.of(30000L)), eq(Money.of(24000L)));
    }
}
