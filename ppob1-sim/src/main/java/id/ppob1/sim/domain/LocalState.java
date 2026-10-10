package id.ppob1.sim.domain;

/**
 * PPOB1's own lifecycle for a transaction — <b>not</b> a mirror of PPOB2's {@code OrderState}.
 * The two are deliberately different vocabularies, because a reseller cares about a different set
 * of distinctions than the platform does: PPOB2's {@code DECOMPOSITION_SELECTED} and
 * {@code FULFILLING} are the same thing to PPOB1 (work in progress upstream), while
 * {@link #SUBMITTING} and {@link #UNKNOWN} are states PPOB2 has no concept of and cannot report,
 * because they describe PPOB1's own uncertainty rather than the order.
 *
 * <p>Collapsing these into PPOB2's enum would quietly delete exactly the states reconciliation
 * exists to find. See {@link #SUBMITTING}.
 */
public enum LocalState {

    /**
     * Persisted <b>before</b> the HTTP call to PPOB2 is made, and the reason this enum is not
     * PPOB2's. If the request times out, the connection drops, or this process dies mid-call,
     * PPOB1 is left holding a transaction it cannot name: the order may exist upstream, with a QR
     * the customer can pay, and PPOB1 has no {@code order_id} to inquire about.
     *
     * <p>Writing the row only <em>after</em> a successful response would turn that case into no
     * row at all — unrecoverable, and invisible. A stuck {@code SUBMITTING} row is recoverable:
     * the {@code reseller_txn_id} doubles as the Idempotency-Key, so re-submitting returns the
     * same order rather than creating a second one, and a recon run reports it as
     * {@code ORPHANED_SUBMIT}.
     */
    SUBMITTING,

    /** PPOB2 accepted the order and returned a QR. Waiting on the customer to pay. */
    AWAITING_PAYMENT,

    /** Paid, and PPOB2 is decomposing or fulfilling. One state here for PPOB2's {@code PAID},
     * {@code DECOMPOSITION_SELECTED} and {@code FULFILLING}: the difference between them is
     * PPOB2's internal business and not actionable by a reseller. */
    AWAITING_RESULT,

    /** Terminal, fully delivered. The only state PPOB1 may bill its own customer for in full. */
    COMPLETED,

    /** Terminal upstream, delivered in part — PPOB2's {@code PARTIAL_FAILED}. Separate from
     * {@link #FAILED} because the commercial consequence is different: something was delivered, so
     * this needs a partial-value decision rather than a refund. */
    PARTIALLY_FAILED,

    /** Terminal, nothing delivered. */
    FAILED,

    /** PPOB1 cancelled it, or PPOB2 reports it cancelled. */
    CANCELLED,

    /** The QR expired unpaid. */
    EXPIRED,

    /**
     * PPOB2 owes a refund and has not yet paid it ({@code REFUND_PENDING}). Modelled explicitly
     * rather than folded into {@link #FAILED} because it is a receivable: PPOB2's own refund
     * execution is a human moving money out-of-band (its go-live blocker 8), so this state can
     * persist for a long time and PPOB1 must be able to list what it is owed.
     */
    REFUND_EXPECTED,

    /** The refund has been executed upstream ({@code REFUNDED}). */
    REFUNDED,

    /** PPOB2 refused the order outright — unsupported amount, idempotency conflict, bad signature.
     * No upstream order exists, so there is nothing to reconcile or collect; {@code rejection_code}
     * records which refusal it was. */
    REJECTED,

    /**
     * PPOB1 has lost track: an order id that PPOB2 no longer recognises, or a state PPOB2 reported
     * that this version of PPOB1 has no mapping for. Never set optimistically — reaching it means
     * a human has to look, which is why it is distinct from {@link #SUBMITTING} (where the next
     * step is mechanical: re-submit under the same idempotency key).
     */
    UNKNOWN;

    /** True once no further upstream transition is expected, so a recon run need not re-inquire.
     * {@link #REFUND_EXPECTED} is deliberately NOT terminal — it is waiting on PPOB2 to pay. */
    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIALLY_FAILED || this == FAILED
                || this == CANCELLED || this == EXPIRED || this == REFUNDED || this == REJECTED;
    }

    /**
     * Maps a PPOB2 {@code OrderState} name onto PPOB1's vocabulary.
     *
     * <p>Returns {@link #UNKNOWN} for anything unrecognised rather than guessing or throwing. That
     * is the deliberate choice: PPOB2 can add a state in a release PPOB1 has never seen, and the
     * two safe-looking alternatives are both worse. Throwing would make an inquiry fail and leave
     * PPOB1's belief stale with no record of why; defaulting to {@code AWAITING_RESULT} would have
     * PPOB1 wait forever for a transition that already happened. {@code UNKNOWN} is visible, is
     * reported by every recon run, and cannot be mistaken for progress.
     */
    public static LocalState fromPpob2State(String ppob2State) {
        if (ppob2State == null) {
            return UNKNOWN;
        }
        return switch (ppob2State) {
            case "CREATED", "PAYMENT_PENDING" -> AWAITING_PAYMENT;
            case "PAID", "DECOMPOSITION_SELECTED", "FULFILLING" -> AWAITING_RESULT;
            case "SUCCESS" -> COMPLETED;
            case "PARTIAL_FAILED" -> PARTIALLY_FAILED;
            case "FAILED" -> FAILED;
            case "REFUND_PENDING" -> REFUND_EXPECTED;
            case "REFUNDED" -> REFUNDED;
            case "EXPIRED" -> EXPIRED;
            case "CANCELLED" -> CANCELLED;
            default -> UNKNOWN;
        };
    }
}
