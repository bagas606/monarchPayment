package id.ppob2.order;

import id.ppob2.payment.PaymentConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * `order` already depends on `payment` (Section 20.2), so listening for a payment-module-owned
 * event class requires no dependency `payment` doesn't already grant — see
 * {@link PaymentConfirmedEvent}'s Javadoc for why this is a listener rather than a direct call.
 * {@code AFTER_COMMIT} specifically: this must not fire until the payment-SUCCESS write (and the
 * payment_event dedup insert guarding it) are durably committed, since a rollback there must not
 * leave the order incorrectly marked PAID.
 */
@Component
public class PaymentConfirmedEventListener {

    private final ParentOrderTransitionService transitionService;

    public PaymentConfirmedEventListener(ParentOrderTransitionService transitionService) {
        this.transitionService = transitionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        boolean paid = transitionService.markPaid(event.parentOrderId());
        if (paid) {
            // Section 48.2's sequence diagram: ORD->RTE:selectPattern happens immediately after
            // markPaid, in the same async pipeline triggered by the payment webhook — not a
            // separate step the caller has to remember to invoke. Likewise, child-order creation
            // and dispatch begin immediately once a pattern is selected (Section 33.2's
            // DECOMPOSITION_SELECTED -> FULFILLING trigger is "child orders created & dispatch
            // begins", not a separately-triggered step).
            boolean patternSelected = transitionService.selectPatternOrRefund(event.parentOrderId());
            if (patternSelected) {
                transitionService.createChildOrdersAndBeginFulfillment(event.parentOrderId());
            }
        }
    }
}
