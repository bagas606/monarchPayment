package id.ppob2.order;

/**
 * Published when the QR expiry sweep moves an unpaid order {@code PAYMENT_PENDING -> EXPIRED}
 * (PRD Section 48.3 / 33.2).
 *
 * <p>Exists so the partner can be told. Section 48.3's own diagram shows an
 * {@code ORDER_STATUS_CHANGED} webhook alongside this state change, and until now that half was
 * deliberately not built — {@code QrExpirySweepJob}'s Javadoc said so, on the correct grounds that
 * sending it needs the {@code parent_order.callback_url} / {@code partner.webhook_secret_ciphertext} join that
 * Section 20.2 gives `order` no edge for. So this follows the pattern {@link
 * DecompositionExhaustedEvent} and {@link LatePaymentOnUntransitionableOrderEvent} already set:
 * `order` states the fact, the composition root acts on it.
 *
 * <p><b>Why it was worth building.</b> {@code EXPIRED} is not a quiet end state for a reseller. It
 * is the <em>default</em> outcome of a lost create response: the reseller's own write of the
 * {@code order_id} never landed, so it has no QR to show anybody, so nobody pays, so this sweep
 * expires the order. The reseller is left holding a sale it cannot name against an order it cannot
 * see, and with no callback for {@code EXPIRED} there was nothing that could ever tell it
 * otherwise — its only route back was a human reading a reconciliation report. That is
 * {@code ppob1-sim}'s {@code ReconVerdict.ORPHANED_SUBMIT}, and this event is what lets the
 * callback close it instead.
 *
 * <p>Carries only the id. Unlike the two events above it, nothing downstream needs the amount: the
 * callback body is built from the order read fresh at delivery time (which may be minutes later,
 * from a sweep), not from anything carried here.
 */
public record OrderExpiredEvent(Long parentOrderId) {
}
