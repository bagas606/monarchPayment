package id.ppob2.app.reconciliation;

import id.ppob2.order.LatePaymentOnUntransitionableOrderEvent;
import id.ppob2.payment.PaymentCallbackAnomalyEvent;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the reconciliation records PRD Section 25.2 requires for callback anomalies — the half of
 * that section that was specified but never built, on the (by now outdated) grounds that the
 * `reconciliation` module did not exist yet. Lives in `app` for the same Section 20.2 reason as
 * {@link OrderFulfillmentReconciliationOrchestrator}: neither `payment` nor `order` has an edge to
 * `reconciliation`, so each publishes an event it owns and the composition root joins them up.
 *
 * <p>Three real gaps, each verified against a running app on 2026-09-27 before being closed:
 * <ul>
 *   <li>A SUCCESS callback for an {@code EXPIRED} payment logged a WARN and opened nothing.</li>
 *   <li>A FAILED callback after a terminal SUCCESS was <em>completely</em> silent — no log, no
 *       record, {@code 200 Successful} returned — because {@code Payment.markFailed()}'s return
 *       value was discarded at the call site.</li>
 *   <li>A payment confirmation for a {@code CANCELLED} order marked the payment SUCCESS and posted
 *       a full payment-ledger CREDIT (funds collected) while the order stayed {@code CANCELLED}
 *       with zero child orders (nothing delivered), and recorded nothing anywhere.</li>
 * </ul>
 *
 * <h2>Which reconciliation type, and why</h2>
 * Payment-level anomalies use {@link ReconciliationType#PAYMENT_VS_PG} keyed on {@code payment.id}
 * — that type is literally "our payment record vs what the PG reports", and this is the first thing
 * in the codebase to open one (the README previously listed it as unwired). The order-level late
 * confirmation uses {@link ReconciliationType#ORDER_VS_FULFILLMENT} keyed on {@code
 * parent_order.id} instead, matching {@link OrderFulfillmentReconciliationOrchestrator}'s existing
 * key convention so both writers of that type agree on what {@code reference_id} means and share
 * its {@code hasOpenDiscrepancy} idempotency check. Mixing both types onto one {@code reference_id}
 * space was rejected precisely because {@code reconciliation} has no FK to disambiguate it.
 *
 * <h2>Value convention — and why it is not simply "billed vs reported"</h2>
 * {@code reconciliation} has no reason/notes column, so {@code expected}, {@code actual} and the
 * derived {@code discrepancy} are the <em>only</em> things an operator sees in Admin Web; the
 * {@link PaymentCallbackAnomalyEvent.Kind} survives only in a log line. The first version of this
 * class recorded {@code expected = payment.amount} and {@code actual = reportedAmount} for every kind,
 * which made the two most dangerous anomalies — a SUCCESS callback for an EXPIRED payment, and a
 * refund landing after goods were delivered — both show up as <b>discrepancy 0</b>. An OPEN row with a
 * zero discrepancy is indistinguishable from noise, so the most expensive cases were the ones most
 * likely to be resolved unread. TC-BE-028 asks for the <em>correct</em> discrepancy value.
 *
 * <p>So the two columns mean "collected according to our payment row" versus "collected according to
 * the PG", and {@code discrepancy} (actual − expected) reads as "how much money the PG believes
 * changed hands that our books do not agree with":
 *
 * <table><caption>Payment-level records</caption>
 * <tr><th>Kind (and payment state)</th><th>expected</th><th>actual</th><th>discrepancy</th></tr>
 * <tr><td>SUCCESS_ON_TERMINAL_PAYMENT, payment EXPIRED/FAILED</td><td>0</td><td>reported</td>
 *     <td><b>+amount</b> — funds collected against an order we abandoned</td></tr>
 * <tr><td>SUCCESS_ON_TERMINAL_PAYMENT, payment already SUCCESS</td><td>payment.amount</td>
 *     <td>reported</td><td>0 — a second success; genuinely nothing new was collected</td></tr>
 * <tr><td>TERMINAL_STATUS_AFTER_SUCCESS (04/05/06 after SUCCESS)</td><td>payment.amount</td>
 *     <td>0</td><td><b>−amount</b> — we booked a payment the PG now reverses</td></tr>
 * <tr><td>AMOUNT_MISMATCH</td><td>payment.amount</td><td>reported</td>
 *     <td>the actual shortfall/excess the PG claims</td></tr>
 * <tr><td>AMOUNT_UNVERIFIED</td><td>payment.amount</td><td>payment.amount</td>
 *     <td>0 — honest here: the payment WAS applied in full, only the check was skipped</td></tr>
 * </table>
 *
 * <p>For the order-level record, {@code expected} is {@code parent_amount} (what the customer paid
 * for) and {@code actual} is 0 (nothing was fulfilled) — the same shape {@code
 * ORDER_VS_FULFILLMENT} already uses for a fully-failed order.
 */
@Component
public class PaymentCallbackAnomalyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackAnomalyOrchestrator.class);

    private final ReconciliationService reconciliationService;

    public PaymentCallbackAnomalyOrchestrator(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * Plain {@code @EventListener} (synchronous, joining the publisher's transaction) rather than
     * {@code @TransactionalEventListener(AFTER_COMMIT)}: {@code PaymentCallbackService} publishes
     * from inside its own request-thread {@code @Transactional}, and committing the discrepancy
     * record together with that callback's {@code payment_event} dedup row is what makes this
     * idempotent under Ayolinx's documented redelivery — a redelivered callback never reaches the
     * publishing branch, because {@code dedup_key} stops it first.
     */
    @EventListener
    public void onPaymentCallbackAnomaly(PaymentCallbackAnomalyEvent event) {
        // Deliberately NO hasOpenDiscrepancy guard here, unlike the order-level listener below.
        // Redeliveries are already stopped upstream by payment_event.dedup_key, so the guard bought
        // nothing for its stated purpose — and it actively suppressed the case that matters most: in
        // the degraded mode AMOUNT_UNVERIFIED exists for (our amount-format assumption turns out
        // wrong, so every payment opens a record), a later 04/05/06 reversal for that same payment
        // would have been swallowed with an INFO line. Distinct anomalies on one payment are distinct
        // findings; the whole point of this class is that none of them stays silent.
        Money expected = reconciliationExpected(event);
        Money actual = reconciliationActual(event);
        reconciliationService.open(ReconciliationType.PAYMENT_VS_PG, LocalDate.now(), event.paymentId(),
                expected, actual);
        log.warn("Opened PAYMENT_VS_PG discrepancy for payment {} (parent_order {}): {} pg_status={} "
                        + "payment_status={} billed={} reported={} -> recorded expected={} actual={}",
                event.paymentId(), event.parentOrderId(), event.kind(), event.pgStatus(),
                event.paymentStatus(), event.expectedAmount(), event.reportedAmount(), expected, actual);
    }

    /** See the class Javadoc's value-convention table — "collected according to our payment row". */
    private Money reconciliationExpected(PaymentCallbackAnomalyEvent event) {
        return switch (event.kind()) {
            // Unless the payment already reached SUCCESS, our row says it resolved having collected
            // nothing at all — so "expected" is zero, and the PG's amount becomes the whole surplus.
            case SUCCESS_ON_TERMINAL_PAYMENT ->
                    "SUCCESS".equals(event.paymentStatus()) ? event.expectedAmount() : Money.ZERO;
            case TERMINAL_STATUS_AFTER_SUCCESS, AMOUNT_MISMATCH, AMOUNT_UNVERIFIED -> event.expectedAmount();
        };
    }

    /** See the class Javadoc's value-convention table — "collected according to the PG". */
    private Money reconciliationActual(PaymentCallbackAnomalyEvent event) {
        return switch (event.kind()) {
            // The PG says it collected this. Falls back to the billed amount when the reported amount
            // could not be parsed, so the row still shows the money as collected rather than
            // understating it as zero — an understated surplus is the error that hides a real loss.
            case SUCCESS_ON_TERMINAL_PAYMENT, AMOUNT_MISMATCH ->
                    event.reportedAmount() != null ? event.reportedAmount() : event.expectedAmount();
            // The PG is reversing/declining a payment we already booked: it now holds nothing for us.
            case TERMINAL_STATUS_AFTER_SUCCESS -> Money.ZERO;
            // The payment was applied in full; only the amount CHECK was skipped.
            case AMOUNT_UNVERIFIED -> event.expectedAmount();
        };
    }

    /**
     * {@code REQUIRES_NEW}, unlike the payment-level listener above — but be precise about why,
     * because the obvious reason is the wrong one. {@code markPaid} runs under {@code REQUIRES_NEW}
     * itself (it is reached from a {@code @TransactionalEventListener(AFTER_COMMIT)} callback, where
     * a write under plain REQUIRED silently never lands — see its Javadoc). This listener is a plain
     * synchronous {@code @EventListener}, so by the time it runs there IS already a live transaction
     * it could have joined; plain REQUIRED would in fact work here.
     *
     * <p>It stays {@code REQUIRES_NEW} so the discrepancy record survives independently of whatever
     * else that outer transaction does: this row is the only trace that funds were collected against
     * an order that will never be fulfilled, and it is worth more than the one extra pooled
     * connection it costs on a path that only fires when a payment confirmation loses a race with
     * cancellation or expiry. Verified by reading the row back out of Postgres, not by a unit test.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLatePaymentOnUntransitionableOrder(LatePaymentOnUntransitionableOrderEvent event) {
        if (reconciliationService.hasOpenDiscrepancy(ReconciliationType.ORDER_VS_FULFILLMENT, event.parentOrderId())) {
            log.info("parent_order {} already has an OPEN/INVESTIGATING ORDER_VS_FULFILLMENT record — skipping "
                    + "the late-payment record", event.parentOrderId());
            return;
        }
        reconciliationService.open(ReconciliationType.ORDER_VS_FULFILLMENT, LocalDate.now(), event.parentOrderId(),
                event.parentAmount(), Money.ZERO);
        log.warn("Opened ORDER_VS_FULFILLMENT discrepancy for parent_order {}: payment confirmed while order was {} "
                        + "— funds collected, nothing fulfilled, needs manual review",
                event.parentOrderId(), event.orderState());
    }
}
