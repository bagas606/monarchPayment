package id.ppob2.payment;

import java.time.Instant;

/**
 * Published after a payment is durably marked SUCCESS (Section 33.2's {@code PAYMENT_PENDING ->
 * PAID} trigger). This is how `order` (which already depends on `payment`, Section 20.2) reacts
 * to a payment confirmation without `payment` needing a reverse compile dependency on `order` —
 * Section 20.2's graph grants no such edge, and Section 35 doesn't specify a mechanism, so an
 * in-process Spring application event is the chosen resolution: `payment` publishes a plain
 * event class it owns, `order` listens for it. A future high-throughput slice could replace the
 * in-process publish with a durable outbox without changing this contract's shape.
 */
public record PaymentConfirmedEvent(Long parentOrderId, Instant paidAt) {
}
