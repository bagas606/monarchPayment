package id.ppob1.sim.domain;

/**
 * What PPOB1 did with one inbound Section 23.8 callback. Every verdict is persisted, including the
 * refusals — a receiver that drops a bad callback without a trace gives an operator no way to tell
 * "PPOB2 never called" from "PPOB2 called and we rejected it", which are opposite problems with
 * opposite fixes.
 *
 * <p>The HTTP status PPOB1 answers with is chosen per verdict, and that choice is load-bearing
 * because PPOB2 retries any non-2xx (four scheduled retries after the first attempt). Answering
 * non-2xx to something a retry cannot fix just burns the retry budget; answering 2xx to something
 * that should be retried loses the event.
 */
public enum WebhookVerdict {

    /** Signature verified, order known, state applied. PPOB1 answers {@code 200}. */
    APPLIED,

    /**
     * Signature verified and the nonce has been seen before. PPOB1 answers {@code 200}: this is
     * PPOB2's own retry of a delivery we already took, so it is success from its point of view and
     * re-answering non-2xx would make it retry a message it has already delivered. Idempotency
     * comes from the unique {@code nonce}, the same shape as PPOB2's own
     * {@code payment_event.dedup_key}.
     */
    DUPLICATE,

    /**
     * Signature did not verify. PPOB1 answers {@code 401} and applies <b>nothing</b> — the only
     * verdict where the state in the body is deliberately ignored. Retrying will not help if the
     * secret is wrong, but answering 2xx to an unsigned caller would mean anyone who learned an
     * order id could move PPOB1's books.
     */
    REJECTED_SIGNATURE,

    /**
     * Correctly signed, names an order id PPOB1 has no record of, <b>and</b> carries a
     * {@code reseller_txn_id} that matches a transaction holding no upstream order id at all. PPOB1
     * adopts the order id from the callback and applies the state. Answers {@code 200}.
     *
     * <p>A signed callback PPOB1 cannot place is the shape of a real problem: PPOB2 fulfilled an
     * order attributable to PPOB1, and PPOB1's books do not connect it to any sale. Before PPOB2
     * echoed the reseller's own id, this read as {@link #UNKNOWN_ORDER} — PPOB1 was being told about
     * one of its own sales and had nothing to match it on. Now it repairs itself.
     *
     * <h2>What it reaches</h2>
     * <ul>
     *   <li><b>The ordinary lost-create-response orphan — since 2026-10-10, and this is the case
     *       the whole mechanism was wanted for.</b> It did not work when the reseller id was first
     *       echoed back, and tracing why is worth keeping: a lost create response leaves no
     *       {@code qr_payload} (only {@code acceptedByPpob2} sets one), so PPOB1 has no QR to show
     *       anybody, so the order is never paid, so PPOB2 sweeps it to {@code EXPIRED} — and
     *       {@code EXPIRED} fired no callback. PPOB2 now notifies on it
     *       ({@code WebhookDeliveryTargetResolver.NOTIFIABLE_STATES}), so the callback arrives,
     *       PPOB1 matches it on its own id, and the sale it could not name is finally closed as
     *       dead. Recovery takes about seventeen minutes end to end: the 15-minute QR TTL, then
     *       PPOB2's expiry sweep, then its webhook retry sweep. Until then recon still reports
     *       {@link ReconVerdict#ORPHANED_SUBMIT}, correctly.</li>
     *   <li><b>A {@link LocalState#REJECTED} row whose order exists anyway.</b> A {@code 409}
     *       {@code IDEMPOTENCY_KEY_CONFLICT} is the one refusal PPOB2 issues while an order for that
     *       key does exist, and it is the reason the guard is on the id rather than on the state:
     *       {@code ReconciliationService} excludes {@code REJECTED} from its working set, so recon
     *       will never look at that row again. The callback is the only thing left.</li>
     *   <li><b>A row whose id was lost after the order went live</b> — a restored backup, a failed
     *       outcome write, an operational repair. PPOB1 kept the sale and lost the pointer.</li>
     * </ul>
     *
     * <h2>What it still does not reach</h2>
     * Orphans whose upstream order never becomes notifiable, so no callback is ever sent:
     * <ul>
     *   <li><b>The order that stayed {@code CREATED}</b> — PPOB2 answered
     *       {@code PG_UNAVAILABLE}, so the order row exists but never reached
     *       {@code PAYMENT_PENDING}, is never swept, and never expires.</li>
     *   <li><b>The submit that never arrived</b> — there is no upstream order to notify about at
     *       all. ({@code E2E-ORPHAN-1} in the harness is this case, and asserts it stays an
     *       orphan.)</li>
     * </ul>
     * Both still need {@link ReconVerdict#ORPHANED_SUBMIT}'s remedy: a human reading the recon
     * report and re-submitting under the same key.
     *
     * <p>A row recovered out of {@code REJECTED} keeps its {@code rejection_code} and
     * {@code rejection_message}. Clearing them would make it indistinguishable from an ordinary
     * recovery; keeping them is what records that PPOB2 refused this sale at create time and an
     * order for it nonetheless reached a terminal state, which is the only part of this worth a
     * human's attention.
     */
    RECOVERED_BY_RESELLER_ID,

    /**
     * Correctly signed, names an unknown order id, and the {@code reseller_txn_id} it carries
     * belongs to a transaction that already holds a <b>different</b> upstream order id. PPOB1
     * applies nothing and answers {@code 200}.
     *
     * <p>Not a defensive branch for an impossible case. PPOB2's idempotency key is unique per
     * {@code (client_id, idempotency_key)} and Section 22.3 lets one partner register several
     * {@code api_client} rows — so the same reseller id sent through two of a partner's clients
     * produces two distinct upstream orders, and a callback for the second would arrive naming an
     * order id PPOB1 does not hold against a reseller id it does. Adopting would overwrite the first
     * order id and lose the order PPOB1 was actually tracking: one silent write, two orders, one
     * record.
     *
     * <p>{@code 200} for {@link #UNKNOWN_ORDER}'s reason — no retry can resolve a disagreement about
     * which order an id names — and the row is what an operator needs, since the raw body holds both
     * ids.
     */
    ORDER_ID_CONFLICT,

    /**
     * Correctly signed but names an order id PPOB1 has no record of, with nothing to recover it by:
     * either no {@code reseller_txn_id} in the body (every order placed before PPOB2's V23, and any
     * order placed without the field) or one PPOB1 has never issued. PPOB1 answers {@code 200} and
     * records it without applying anything: the signature proves it came from PPOB2, so retrying
     * cannot make PPOB1 recognise it, and a retry storm would bury the row that says a real
     * integration gap exists.
     */
    UNKNOWN_ORDER,

    /** Signed, but the body is not parseable as an {@code ORDER_STATUS_CHANGED} event, or is
     * missing {@code order_id}/{@code state}. PPOB1 answers {@code 400}: a malformed body will not
     * become well-formed on retry, and a 2xx would hide it. */
    MALFORMED
}
