package id.ppob1.sim.domain;

/**
 * The outcome of comparing one PPOB1 transaction against PPOB2's answer for it.
 *
 * <p>Ordered worst-first in {@code severity()} rather than alphabetically, because a recon report
 * is read top-down by someone deciding what to do before the end of the day, and the ordering is
 * the recommendation.
 */
public enum ReconVerdict {

    /**
     * PPOB1 holds a {@link LocalState#SUBMITTING} row with no {@code ppob2_order_id} — the submit
     * never completed, so PPOB1 does not know whether an order exists upstream. The worst verdict
     * here: if the order does exist, a customer can pay a QR for a transaction PPOB1 cannot see,
     * and PPOB1 would owe a delivery it has no record of owing.
     *
     * <p>Mechanically recoverable, which is why it is worth isolating: {@code reseller_txn_id} was
     * sent as the Idempotency-Key, so re-submitting returns the original order (PPOB2's
     * {@code parent_order_idem_uk}) instead of creating a second one.
     */
    ORPHANED_SUBMIT,

    /**
     * PPOB1 and PPOB2 disagree about a transaction both can see. The classic money case is PPOB1
     * believing {@code AWAITING_PAYMENT} while PPOB2 reports {@code SUCCESS}: delivered upstream,
     * unbilled downstream.
     */
    STATE_MISMATCH,

    /**
     * PPOB2 does not recognise an order id PPOB1 holds. Either PPOB1 recorded an id that was never
     * real, or the order belongs to another partner — PPOB2 scopes every lookup by the calling
     * partner, so an id leaked from elsewhere reads as not-found rather than as forbidden.
     */
    MISSING_AT_PPOB2,

    /**
     * The states agree and are terminal, PPOB2 <em>does</em> fire {@code ORDER_STATUS_CHANGED} for
     * this state, and PPOB1 still only learned it by asking ({@link StateSource#INQUIRY}) — the
     * callback never arrived. Not a money discrepancy, which is why it ranks below the three above,
     * but it is the one verdict that detects the push integration having silently degraded to
     * polling: the books stay right while the mechanism keeping them right has stopped working.
     *
     * <p>Scoped to the states PPOB2 actually pushes (its {@code OutboundWebhookOrchestrator} fires
     * only on post-dispatch outcomes — SUCCESS, PARTIAL_FAILED, FAILED). A cancelled or expired
     * order is never pushed <em>by design</em>, so flagging those would report PPOB2's documented
     * trigger coverage as a fault on every run and bury the findings that matter; they come back
     * as {@link #MATCH} with the reason recorded in the entry's detail.
     */
    WEBHOOK_NEVER_ARRIVED,

    /** Agreed, and not yet terminal upstream. Nothing to do; recorded so a run's output is a
     * complete census rather than only its exceptions. */
    IN_PROGRESS,

    /** Agreed and terminal, and PPOB1 was told by webhook. The healthy outcome. */
    MATCH;

    /** 0 is worst. Used to sort a run's entries so the rows needing action are first. */
    public int severity() {
        return ordinal();
    }

    /** True for the verdicts an operator must act on. {@link #WEBHOOK_NEVER_ARRIVED} counts: the
     * money is right, but an integration that has stopped pushing will eventually produce a
     * mismatch that nobody notices until a customer complains. */
    public boolean needsAttention() {
        return this == ORPHANED_SUBMIT || this == STATE_MISMATCH || this == MISSING_AT_PPOB2
                || this == WEBHOOK_NEVER_ARRIVED;
    }
}
