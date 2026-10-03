package id.ppob2.order;

import id.ppob2.sharedkernel.money.Money;

/**
 * Published when a <em>paid</em> parent order finds no eligible decomposition pattern — PRD
 * Section 33.2's BR-DEC exhaustion case, {@code PAID -> REFUND_PENDING}.
 *
 * <p>This is the third route into the same dangerous end state {@link
 * LatePaymentOnUntransitionableOrderEvent} exists for, and it was the one left uncovered: the
 * payment reaches {@code SUCCESS} and a full CREDIT is posted to the payment ledger (the funds
 * were collected), the order carries zero child orders (nothing was delivered), and — before this
 * event — no reconciliation record existed anywhere, so nothing in Admin Web would ever have
 * surfaced that the customer paid and received nothing. Verified live on 2026-10-03: a 10,000
 * order for an amount with no seeded pattern left {@code payment SUCCESS} + a 10,000 ledger CREDIT
 * against {@code REFUND_PENDING} with zero children and zero reconciliation rows.
 *
 * <p>The {@code REFUND_PENDING} state name is not itself that record. It marks the order as owing
 * a refund, but nothing scans for it (there is no refund job — Section 73.3), and the operator
 * queue Admin Web actually surfaces is {@code reconciliation}. An order sitting in
 * {@code REFUND_PENDING} with no reconciliation row is money collected that no human is told
 * about.
 *
 * <p>Published by `order` rather than handled in place for the same Section 20.2 reason as
 * {@link LatePaymentOnUntransitionableOrderEvent}: `order` has no edge to `reconciliation`, so the
 * composition root ({@code app}) opens the record — see {@code
 * OrderFulfillmentReconciliationOrchestrator#onDecompositionExhausted}.
 */
public record DecompositionExhaustedEvent(Long parentOrderId, Money parentAmount) {
}
