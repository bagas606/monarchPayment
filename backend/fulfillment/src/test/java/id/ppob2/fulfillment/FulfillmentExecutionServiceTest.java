package id.ppob2.fulfillment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import id.ppob2.order.ChildOrderService;
import id.ppob2.order.ParentOrderTransitionService;
import id.ppob2.provider.GameProvider;
import id.ppob2.provider.PurchaseRequest;
import id.ppob2.provider.PurchaseResult;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Covers the things end-to-end Postgres verification of this slice couldn't easily exercise
 * without an Admin Web retry endpoint (not built yet): that an authorized re-dispatch of the same
 * child order gets a distinct provider_transaction row rather than silently no-opping against an
 * earlier failed attempt's idempotency key, that internal timeout retries within a single dispatch
 * attempt correctly reuse one key and produce only one recorded transaction, and that a
 * re-entrant record (an internal retry, {@code newlyInserted=false}) does not double-post to the
 * Provider Ledger.
 */
class FulfillmentExecutionServiceTest {

    private final GameProvider gameProvider = mock(GameProvider.class);
    private final ProviderTransactionRecorder providerTransactionRecorder = mock(ProviderTransactionRecorder.class);
    private final ProviderLedgerPoster providerLedgerPoster = mock(ProviderLedgerPoster.class);
    private final ChildOrderService childOrderService = mock(ChildOrderService.class);
    private final ParentOrderTransitionService parentOrderTransitionService = mock(ParentOrderTransitionService.class);
    private final FulfillmentExecutionService service = new FulfillmentExecutionService(
            gameProvider, providerTransactionRecorder, providerLedgerPoster, childOrderService, parentOrderTransitionService);

    @Test
    void reDispatchOfSameChildOrderGetsADistinctIdempotencyKey() {
        // First dispatch attempt: attempt_count becomes 1 (e.g. failed).
        given(childOrderService.markExecuting(42L)).willReturn(Optional.of(1));
        given(gameProvider.purchase(any())).willReturn(PurchaseResult.failed("stub failure"));
        given(providerTransactionRecorder.record(eq(5L), eq(1), eq(42L), eq(10L), eq("child-42-attempt-1"), any()))
                .willReturn(new RecordedProviderTransaction(100L, true));

        service.dispatch(1L, List.of(new ResolvedChildOrder(42L, 10L, 5L, 1, Money.of(1000L))), List.of());

        verify(providerTransactionRecorder).record(eq(5L), eq(1), eq(42L), eq(10L), eq("child-42-attempt-1"), any());

        // Section 33.2's authorized PARTIAL_FAILED/FAILED -> retry: same child order dispatched
        // again; attempt_count is now 2.
        given(childOrderService.markExecuting(42L)).willReturn(Optional.of(2));
        given(gameProvider.purchase(any())).willReturn(PurchaseResult.success("STUB-ref"));
        given(providerTransactionRecorder.record(eq(5L), eq(1), eq(42L), eq(10L), eq("child-42-attempt-2"), any()))
                .willReturn(new RecordedProviderTransaction(101L, true));

        service.dispatch(1L, List.of(new ResolvedChildOrder(42L, 10L, 5L, 1, Money.of(1000L))), List.of());

        verify(providerTransactionRecorder).record(eq(5L), eq(1), eq(42L), eq(10L), eq("child-42-attempt-2"), any());
        // Two distinct calls, two distinct keys — never the same key twice for two authorized attempts.
        verify(providerTransactionRecorder, times(2)).record(eq(5L), eq(1), eq(42L), eq(10L), any(), any());
        // Only the second attempt succeeded — one debit, for that attempt's provider_transaction id.
        verify(providerLedgerPoster, times(1)).postPurchaseDebit(101L, Money.of(1000L), 1);
    }

    @Test
    void internalTimeoutRetriesReuseTheSameKeyAndRecordOnlyOnce() {
        given(childOrderService.markExecuting(7L)).willReturn(Optional.of(1));
        given(gameProvider.purchase(any(PurchaseRequest.class)))
                .willReturn(PurchaseResult.timeout("t1"))
                .willReturn(PurchaseResult.timeout("t2"))
                .willReturn(PurchaseResult.success("STUB-ref"));
        given(providerTransactionRecorder.record(eq(3L), eq(2), eq(7L), eq(9L), eq("child-7-attempt-1"), any()))
                .willReturn(new RecordedProviderTransaction(200L, true));

        service.dispatch(1L, List.of(new ResolvedChildOrder(7L, 9L, 3L, 2, Money.of(500L))), List.of());

        verify(gameProvider, times(3)).purchase(any());
        verify(providerTransactionRecorder, times(1)).record(eq(3L), eq(2), eq(7L), eq(9L), eq("child-7-attempt-1"), any());
        verify(childOrderService).recordOutcome(7L, true, 200L);
        // quantity 2 at 500/unit -> 1000 total; this is exactly the multiply the PRD's "cost per
        // unit" wording requires and a quantity-1 seed would never catch.
        verify(providerLedgerPoster).postPurchaseDebit(200L, Money.of(500L), 2);
    }

    @Test
    void duplicateDispatchOfAnAlreadyExecutingChildOrderIsSkipped() {
        given(childOrderService.markExecuting(55L)).willReturn(Optional.empty());

        service.dispatch(1L, List.of(new ResolvedChildOrder(55L, 1L, 1L, 1, Money.of(100L))), List.of());

        verify(gameProvider, never()).purchase(any());
        verify(providerTransactionRecorder, never()).record(anyLong(), anyInt(), anyLong(), anyLong(), any(), any());
        verify(providerLedgerPoster, never()).postPurchaseDebit(anyLong(), any(), anyInt());
        verify(parentOrderTransitionService).completeFulfillment(1L);
    }

    @Test
    void unresolvableChildOrdersAreMarkedFailedWithoutCallingAnyProvider() {
        service.dispatch(1L, List.of(), List.of(99L));

        verify(childOrderService).markUnresolvable(99L);
        verify(gameProvider, never()).purchase(any());
        verify(parentOrderTransitionService).completeFulfillment(1L);
    }

    @Test
    void failedPurchasePostsNoLedgerEntry() {
        given(childOrderService.markExecuting(8L)).willReturn(Optional.of(1));
        given(gameProvider.purchase(any())).willReturn(PurchaseResult.failed("stub failure"));
        given(providerTransactionRecorder.record(eq(4L), eq(1), eq(8L), eq(11L), eq("child-8-attempt-1"), any()))
                .willReturn(new RecordedProviderTransaction(300L, true));

        service.dispatch(1L, List.of(new ResolvedChildOrder(8L, 11L, 4L, 1, Money.of(700L))), List.of());

        verify(providerLedgerPoster, never()).postPurchaseDebit(anyLong(), any(), anyInt());
    }

    @Test
    void reEntrantRecordDoesNotDoublePostToTheLedger() {
        // An internal retry re-entering the same idempotency key: insertIfAbsent hit ON CONFLICT
        // DO NOTHING, so newlyInserted is false even though the purchase itself succeeded.
        given(childOrderService.markExecuting(9L)).willReturn(Optional.of(1));
        given(gameProvider.purchase(any())).willReturn(PurchaseResult.success("STUB-ref"));
        given(providerTransactionRecorder.record(eq(6L), eq(1), eq(9L), eq(12L), eq("child-9-attempt-1"), any()))
                .willReturn(new RecordedProviderTransaction(400L, false));

        service.dispatch(1L, List.of(new ResolvedChildOrder(9L, 12L, 6L, 1, Money.of(900L))), List.of());

        verify(providerLedgerPoster, never()).postPurchaseDebit(anyLong(), any(), anyInt());
    }
}
