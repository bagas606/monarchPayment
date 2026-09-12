package id.ppob2.order;

import id.ppob2.order.domain.ParentOrder;

/** {@code isNew = false} means the idempotency key matched an existing, identical request
 * (Section 23.4 idempotent replay) — the caller must not attempt to create a second payment. */
public record OrderCreationOutcome(ParentOrder order, boolean isNew) {
}
