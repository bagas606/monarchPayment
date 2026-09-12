package id.ppob2.app.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.catalog.domain.ProviderSku;
import id.ppob2.catalog.repository.ProviderSkuRepository;
import id.ppob2.fulfillment.FulfillmentExecutionService;
import id.ppob2.fulfillment.ResolvedChildOrder;
import id.ppob2.order.ChildOrderService;
import id.ppob2.order.ParentOrderTransitionService;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.pricing.ProviderPriceService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mocked-repository test covering branches the real end-to-end runs (documented in
 * backend/README.md — one retry reaching SUCCESS, one still failing) didn't need to isolate on
 * their own: every rejection path, and the unresolvable-SKU/no-active-price dispatch branch.
 */
class AdminChildOrderRetryOrchestratorTest {

    private final ChildOrderService childOrderService = mock(ChildOrderService.class);
    private final ParentOrderRepository parentOrderRepository = mock(ParentOrderRepository.class);
    private final ParentOrderTransitionService parentOrderTransitionService = mock(ParentOrderTransitionService.class);
    private final ProviderSkuRepository providerSkuRepository = mock(ProviderSkuRepository.class);
    private final ProviderPriceService providerPriceService = mock(ProviderPriceService.class);
    private final FulfillmentExecutionService fulfillmentExecutionService = mock(FulfillmentExecutionService.class);
    private final AdminChildOrderRetryOrchestrator orchestrator = new AdminChildOrderRetryOrchestrator(
            childOrderService, parentOrderRepository, parentOrderTransitionService,
            providerSkuRepository, providerPriceService, fulfillmentExecutionService);

    // Built as plain local variables and only handed to `given(...)` afterward — nesting a mock's
    // own `given(...)` calls inside an outer, still-open `given(...).willReturn(...)` expression
    // (e.g. as an inline argument) trips Mockito's "unfinished stubbing" detector, since the outer
    // stub isn't considered complete until `.willReturn()` runs, and no other mock's `given(...)`
    // may start in the meantime.
    private static ChildOrder childOrder(ChildOrderState state, Long parentOrderId) {
        ChildOrder childOrder = mock(ChildOrder.class);
        given(childOrder.getState()).willReturn(state);
        given(childOrder.getParentOrderId()).willReturn(parentOrderId);
        given(childOrder.getProviderSkuId()).willReturn(2L);
        given(childOrder.getQuantity()).willReturn(1);
        return childOrder;
    }

    private static ParentOrder parentOrder(OrderState state) {
        ParentOrder order = mock(ParentOrder.class);
        given(order.getState()).willReturn(state);
        given(order.getOrderNo()).willReturn("ORD-TEST");
        return order;
    }

    @Test
    void rejectsWhenChildOrderIsNotFailed() {
        ChildOrder successChild = childOrder(ChildOrderState.SUCCESS, 2L);
        given(childOrderService.findById(4L)).willReturn(Optional.of(successChild));

        assertThatThrownBy(() -> orchestrator.retry(4L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.CHILD_ORDER_NOT_RETRYABLE);

        verify(childOrderService, never()).resetForRetry(anyLong());
        verify(fulfillmentExecutionService, never()).dispatch(any(), any(), any());
    }

    @Test
    void rejectsWhenParentOrderIsNotPartialFailed() {
        ChildOrder failedChild = childOrder(ChildOrderState.FAILED, 2L);
        ParentOrder successParent = parentOrder(OrderState.SUCCESS);
        given(childOrderService.findById(4L)).willReturn(Optional.of(failedChild));
        given(parentOrderRepository.findById(2L)).willReturn(Optional.of(successParent));

        assertThatThrownBy(() -> orchestrator.retry(4L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.CHILD_ORDER_NOT_RETRYABLE);

        verify(childOrderService, never()).resetForRetry(anyLong());
    }

    @Test
    void rejectsWhenResetLosesARaceWithAConcurrentStateChange() {
        ChildOrder failedChild = childOrder(ChildOrderState.FAILED, 2L);
        ParentOrder partialFailedParent = parentOrder(OrderState.PARTIAL_FAILED);
        given(childOrderService.findById(4L)).willReturn(Optional.of(failedChild));
        given(parentOrderRepository.findById(2L)).willReturn(Optional.of(partialFailedParent));
        given(childOrderService.resetForRetry(4L)).willReturn(false);

        assertThatThrownBy(() -> orchestrator.retry(4L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.CHILD_ORDER_NOT_RETRYABLE);

        verify(fulfillmentExecutionService, never()).dispatch(any(), any(), any());
        verify(parentOrderTransitionService, never()).completeRetry(anyLong());
    }

    @Test
    void dispatchesAResolvedChildOrderWhenSkuAndActivePriceExist() {
        ChildOrder beforeRetry = childOrder(ChildOrderState.FAILED, 2L);
        ChildOrder afterRetry = childOrder(ChildOrderState.SUCCESS, 2L);
        ParentOrder beforeParent = parentOrder(OrderState.PARTIAL_FAILED);
        ParentOrder afterParent = parentOrder(OrderState.SUCCESS);
        ProviderSku sku = mock(ProviderSku.class);
        given(sku.getProviderId()).willReturn(99L);

        given(childOrderService.findById(4L)).willReturn(Optional.of(beforeRetry), Optional.of(afterRetry));
        given(parentOrderRepository.findById(2L)).willReturn(Optional.of(beforeParent), Optional.of(afterParent));
        given(childOrderService.resetForRetry(4L)).willReturn(true);
        given(providerSkuRepository.findById(2L)).willReturn(Optional.of(sku));
        given(providerPriceService.getActiveCost(2L)).willReturn(Optional.of(Money.of(15000L)));

        RetryOutcome outcome = orchestrator.retry(4L);

        verify(fulfillmentExecutionService).dispatch(eq(2L),
                eq(List.of(new ResolvedChildOrder(4L, 99L, 2L, 1, Money.of(15000L)))), eq(List.of()));
        verify(parentOrderTransitionService).completeRetry(2L);
        assertThat(outcome.childState()).isEqualTo(ChildOrderState.SUCCESS);
        assertThat(outcome.parentState()).isEqualTo(OrderState.SUCCESS);
    }

    @Test
    void dispatchesAsUnresolvableWhenNoActiveProviderPriceExists() {
        ChildOrder failedChild = childOrder(ChildOrderState.FAILED, 2L);
        ParentOrder partialFailedParent = parentOrder(OrderState.PARTIAL_FAILED);
        ProviderSku sku = mock(ProviderSku.class);

        given(childOrderService.findById(4L)).willReturn(Optional.of(failedChild), Optional.of(failedChild));
        given(parentOrderRepository.findById(2L)).willReturn(Optional.of(partialFailedParent), Optional.of(partialFailedParent));
        given(childOrderService.resetForRetry(4L)).willReturn(true);
        given(providerSkuRepository.findById(2L)).willReturn(Optional.of(sku));
        given(providerPriceService.getActiveCost(2L)).willReturn(Optional.empty());

        orchestrator.retry(4L);

        verify(fulfillmentExecutionService).dispatch(2L, List.of(), List.of(4L));
    }
}
