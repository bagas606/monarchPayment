package id.ppob1.sim.domain;

/**
 * How one of PPOB1's transactions contributes to a day's <b>gross collected</b> figure.
 *
 * <p><b>Why buckets rather than a single number.</b> Which of these counts toward what PPOB1 is
 * owed is a commercial term, and the PRD does not state it: there is no partner commission or
 * margin model anywhere in it, FR-REC-004 says the per-partner allocation "does NOT constitute or
 * trigger a payout to the partner", and BR-REC-002 says which party bears the MDR "must be verified
 * against each partner's contract". Collapsing these into one total would mean encoding a choice
 * nobody has made — and a figure PPOB1 could not defend when PPOB2 disagrees with it. Reported as a
 * census instead: every transaction for the day lands in exactly one bucket, and whoever negotiates
 * the contract decides which buckets to add up.
 *
 * <p><b>The classification is "was the customer's money collected", not "was it delivered."</b>
 * That is deliberate and matches what this figure will one day be compared against:
 * {@code PaymentRepository.sumSuccessfulAmountForDate} filters on {@code payment.status =
 * 'SUCCESS'} alone, independent of the order's state. So upstream, an order that failed
 * fulfilment still contributes to the settlement's {@code expected_amount} — its payment
 * succeeded. Bucketing by delivery instead would understate PPOB1's figure against the very number
 * it is meant to be comparable with.
 */
public enum CollectionBucket {

    /** Paid and fully delivered. The only bucket nobody will argue about. */
    DELIVERED,

    /**
     * Paid in full, delivered in part ({@code PARTIAL_FAILED} upstream). The customer paid one QR
     * for the whole amount, so the whole amount was collected — but PPOB1 has an unresolved claim
     * for the undelivered portion, and PPOB2 has no partial-refund mechanism to settle it with.
     * Kept separate from {@link #DELIVERED} for exactly that reason.
     */
    PARTIAL,

    /**
     * Collected and nothing delivered: {@code FAILED}, and {@code REFUND_EXPECTED} where PPOB2
     * owes a refund it has not yet executed.
     *
     * <p>Still counted as collected, which looks wrong at first glance and is not. The payment
     * succeeded, so this money is in PPOB2's settlement upstream; and PPOB2's refund execution is
     * a human moving money out-of-band (its own go-live blocker), so {@code REFUND_EXPECTED} can
     * sit here for a long time with the funds genuinely collected and un-returned.
     */
    COLLECTED_UNDELIVERED,

    /**
     * Collected and then refunded. Separated from {@link #COLLECTED_UNDELIVERED} because this is
     * the one bucket that drops <em>out</em> of PPOB2's own figure: {@code Payment.markRefunded}
     * moves the status from {@code SUCCESS} to {@code REFUNDED}, so a refunded payment stops
     * matching {@code sumSuccessfulAmountForDate}. Folding it in with the others would put PPOB1's
     * total permanently above PPOB2's by the refunded amount.
     */
    REVERSED,

    /** Paid, still being fulfilled upstream. Collected, but the day is not finished — a snapshot
     * containing any of these is provisional. */
    IN_FLIGHT,

    /** Nothing was collected: awaiting payment, cancelled, expired, or refused by PPOB2 outright. */
    NOT_COLLECTED,

    /**
     * PPOB1 cannot tell — {@code SUBMITTING} and {@code UNKNOWN}.
     *
     * <p>Not folded into {@link #NOT_COLLECTED}, which is the tempting and wrong choice. An
     * orphaned submit may well have a live QR upstream that a customer has already paid; treating
     * it as "nothing collected" would understate the day silently, which is the failure mode a
     * reconciliation figure exists to prevent. A non-zero count here makes the snapshot
     * provisional and is a prompt to resolve those transactions, not a number to quietly drop.
     */
    INDETERMINATE;

    /**
     * Total over {@link LocalState} — every state maps, which is what makes a snapshot a census
     * rather than a filter. {@code CollectionBucketTest} fails if a state is added without a
     * decision here, and there is deliberately no {@code default} branch for the same reason.
     */
    public static CollectionBucket of(LocalState state) {
        return switch (state) {
            case COMPLETED -> DELIVERED;
            case PARTIALLY_FAILED -> PARTIAL;
            case FAILED, REFUND_EXPECTED -> COLLECTED_UNDELIVERED;
            case REFUNDED -> REVERSED;
            case AWAITING_RESULT -> IN_FLIGHT;
            case AWAITING_PAYMENT, CANCELLED, EXPIRED, REJECTED -> NOT_COLLECTED;
            case SUBMITTING, UNKNOWN -> INDETERMINATE;
        };
    }

    /** True for the buckets whose presence means the day's figure may still move. */
    public boolean makesSnapshotProvisional() {
        return this == IN_FLIGHT || this == INDETERMINATE;
    }
}
