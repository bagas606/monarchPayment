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
     * Correctly signed but names an order id PPOB1 has no record of. PPOB1 answers {@code 200} and
     * records it without applying anything: the signature proves it came from PPOB2, so retrying
     * cannot make PPOB1 recognise it, and a retry storm would bury the row that says a real
     * integration gap exists. The expected cause is a transaction stuck in
     * {@link LocalState#SUBMITTING} — PPOB2 created the order, the response never reached PPOB1,
     * and the callback is the first PPOB1 has heard of it.
     */
    UNKNOWN_ORDER,

    /** Signed, but the body is not parseable as an {@code ORDER_STATUS_CHANGED} event, or is
     * missing {@code order_id}/{@code state}. PPOB1 answers {@code 400}: a malformed body will not
     * become well-formed on retry, and a 2xx would hide it. */
    MALFORMED
}
