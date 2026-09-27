package id.ppob2.app.reconciliation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.order.LatePaymentOnUntransitionableOrderEvent;
import id.ppob2.order.domain.OrderState;
import id.ppob2.payment.PaymentCallbackAnomalyEvent;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pins the recorded {@code expected}/{@code actual} values, which are the entire operator-facing
 * output of this class: {@code reconciliation} has no reason column, so the anomaly {@code Kind}
 * survives only in a log line and these two numbers are all Admin Web shows.
 *
 * <p>These tests exist because the first version of this orchestrator recorded {@code expected =
 * payment.amount, actual = reportedAmount} for every kind, which gave the two most dangerous
 * anomalies — a SUCCESS callback for an EXPIRED payment, and a refund landing after goods were
 * delivered — a discrepancy of <b>0</b>. Every end-to-end assertion still passed, because the
 * harness checked {@code count(*) = 1} and never looked at what the row said. That is the gap this
 * file closes: asserting a record exists is not asserting it is legible.
 */
class PaymentCallbackAnomalyOrchestratorTest {

    private static final Money BILLED = Money.of(20000L);

    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
    private final PaymentCallbackAnomalyOrchestrator orchestrator =
            new PaymentCallbackAnomalyOrchestrator(reconciliationService);

    private static PaymentCallbackAnomalyEvent event(PaymentCallbackAnomalyEvent.Kind kind,
                                                      String pgStatus,
                                                      String paymentStatus,
                                                      Money reported) {
        return new PaymentCallbackAnomalyEvent(7L, 3L, kind, pgStatus, paymentStatus, BILLED, reported);
    }

    private void expectOpened(Money expected, Money actual) {
        verify(reconciliationService).open(eq(ReconciliationType.PAYMENT_VS_PG), any(LocalDate.class),
                eq(7L), eq(expected), eq(actual));
    }

    /**
     * Funds collected against an order we had already abandoned. Our payment row says EXPIRED, i.e.
     * nothing collected, so the PG's amount is pure surplus: discrepancy +20000, not 0.
     */
    @Test
    void successOnAnExpiredPaymentRecordsTheWholeAmountAsSurplus() {
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.SUCCESS_ON_TERMINAL_PAYMENT, "00", "EXPIRED", BILLED));
        expectOpened(Money.ZERO, BILLED);
    }

    /** A second SUCCESS for an already-SUCCESS payment genuinely collected nothing new: 0 is correct. */
    @Test
    void successOnAnAlreadySuccessfulPaymentRecordsNoDiscrepancy() {
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.SUCCESS_ON_TERMINAL_PAYMENT, "00", "SUCCESS", BILLED));
        expectOpened(BILLED, BILLED);
    }

    /**
     * A refund/cancel/fail landing after we booked the payment and delivered the goods. We hold a
     * payment the PG now says it does not: discrepancy −20000.
     */
    @Test
    void terminalStatusAfterSuccessRecordsTheBookedAmountAsAShortfall() {
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.TERMINAL_STATUS_AFTER_SUCCESS, "04", "SUCCESS", BILLED));
        expectOpened(BILLED, Money.ZERO);
    }

    @Test
    void amountMismatchRecordsTheActualShortfallTheGatewayClaims() {
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.AMOUNT_MISMATCH, "00", "PENDING", Money.of(1L)));
        expectOpened(BILLED, Money.of(1L));
    }

    /**
     * The payment WAS applied in full and only the amount check was skipped, so a zero discrepancy is
     * the honest value here — the record's job is to say "unverified", not to imply a loss. Recording
     * {@code actual = 0} (as the first version did) would have made every such payment look like a
     * total shortfall.
     */
    @Test
    void amountUnverifiedRecordsNoDiscrepancyBecauseThePaymentWasAppliedInFull() {
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.AMOUNT_UNVERIFIED, "00", "SUCCESS", null));
        expectOpened(BILLED, BILLED);
    }

    /**
     * An unparseable reported amount must not understate the surplus to zero: the PG says it collected
     * something, we just cannot read how much, and an understated surplus is the error that hides a
     * real loss. Falls back to the billed amount.
     */
    @Test
    void successOnATerminalPaymentWithAnUnreadableAmountFallsBackToTheBilledAmount() {
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.SUCCESS_ON_TERMINAL_PAYMENT, "00", "EXPIRED", null));
        expectOpened(Money.ZERO, BILLED);
    }

    /**
     * No {@code hasOpenDiscrepancy} suppression on the payment-level path. Redeliveries are already
     * stopped upstream by {@code payment_event.dedup_key}, and suppressing here would swallow a real
     * reversal for a payment that already has an open record — precisely the degraded mode
     * {@code AMOUNT_UNVERIFIED} exists to survive.
     */
    @Test
    void distinctAnomaliesOnOnePaymentEachOpenTheirOwnRecord() {
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.AMOUNT_UNVERIFIED, "00", "SUCCESS", null));
        orchestrator.onPaymentCallbackAnomaly(
                event(PaymentCallbackAnomalyEvent.Kind.TERMINAL_STATUS_AFTER_SUCCESS, "04", "SUCCESS", BILLED));

        verify(reconciliationService).open(eq(ReconciliationType.PAYMENT_VS_PG), any(), eq(7L), eq(BILLED), eq(BILLED));
        verify(reconciliationService).open(eq(ReconciliationType.PAYMENT_VS_PG), any(), eq(7L), eq(BILLED), eq(Money.ZERO));
        verify(reconciliationService, never()).hasOpenDiscrepancy(any(), any());
    }

    /** The order-level path keeps its idempotency guard: repeated late callbacks for one order are the
     * same finding, unlike distinct payment-level anomaly kinds. */
    @Test
    void lateOrderPaymentRecordsTheUnfulfilledAmountAndIsIdempotentPerOrder() {
        orchestrator.onLatePaymentOnUntransitionableOrder(
                new LatePaymentOnUntransitionableOrderEvent(3L, OrderState.CANCELLED, BILLED));
        verify(reconciliationService).open(eq(ReconciliationType.ORDER_VS_FULFILLMENT), any(LocalDate.class),
                eq(3L), eq(BILLED), eq(Money.ZERO));

        org.mockito.BDDMockito.given(reconciliationService
                .hasOpenDiscrepancy(ReconciliationType.ORDER_VS_FULFILLMENT, 3L)).willReturn(true);
        orchestrator.onLatePaymentOnUntransitionableOrder(
                new LatePaymentOnUntransitionableOrderEvent(3L, OrderState.CANCELLED, BILLED));
        verify(reconciliationService, org.mockito.Mockito.times(1))
                .open(eq(ReconciliationType.ORDER_VS_FULFILLMENT), any(), eq(3L), any(), any());
    }
}
