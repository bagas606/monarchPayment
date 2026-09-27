package id.ppob2.order;

import id.ppob2.order.domain.OrderState;
import id.ppob2.sharedkernel.money.Money;

/**
 * Published when a payment confirmation arrives for a parent order that can no longer move to
 * {@code PAID} — PRD Section 25.2's late-callback case ({@code CANCELLED}/{@code EXPIRED} won the
 * race against the confirmation). The PRD response is "do NOT auto-fulfill; create a
 * reconciliation discrepancy record"; only the first half was implemented, and the second half's
 * code comment claimed the `reconciliation` module did not exist yet. It does.
 *
 * <p>This is the genuinely dangerous half of the two late-callback paths, and it was verified for
 * real on 2026-09-27 against a CANCELLED order: the payment row DID reach {@code SUCCESS} and a
 * full CREDIT was posted to the payment ledger (the funds were collected), the order stayed
 * {@code CANCELLED} with zero child orders (nothing was delivered), and no reconciliation record
 * existed anywhere — so nothing in Admin Web would ever have surfaced that the customer paid and
 * received nothing.
 *
 * <p>Published by `order` rather than handled in place for the same Section 20.2 reason as
 * {@code payment.PaymentConfirmedEvent}: `order` has no edge to `reconciliation`, so the
 * composition root ({@code app}) opens the record.
 */
public record LatePaymentOnUntransitionableOrderEvent(Long parentOrderId,
                                                       OrderState orderState,
                                                       Money parentAmount) {
}
