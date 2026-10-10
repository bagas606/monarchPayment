package id.ppob1.sim.domain;

/**
 * How PPOB1 last learned a transaction's upstream state. Recorded because it is a finding in its
 * own right, not bookkeeping: a transaction that reached a terminal state and whose
 * {@code last_source} is {@link #INQUIRY} is one PPOB2 never successfully called back about. That
 * is invisible if only the state is stored, and it is the difference between a push integration
 * that works and one that is silently degraded to polling.
 */
public enum StateSource {

    /** From the {@code 201} body of {@code POST /api/v1/orders}. */
    CREATE_RESPONSE,

    /** From a Section 23.8 {@code ORDER_STATUS_CHANGED} callback — the intended path. */
    WEBHOOK,

    /** From {@code GET /api/v1/orders/{order_id}}, i.e. PPOB1 had to ask. Either a recon run found
     * a gap, or the webhook never arrived. */
    INQUIRY
}
