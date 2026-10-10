package id.ppob2.reconciliation.domain;

/** PRD Section 22.23 / 38.1's five minimum reconciliation types. {@link #PAYMENT_VS_SETTLEMENT},
 * {@link #ORDER_VS_FULFILLMENT} and — since 2026-09-27 — {@link #PAYMENT_VS_PG} are wired to
 * something that opens a record in this codebase; see the README for why the remaining two are
 * blocked (no report ingestion source). {@code PAYMENT_VS_PG} is opened by {@code
 * PaymentCallbackAnomalyOrchestrator} for Section 25.2's callback anomalies: a SUCCESS callback
 * against a terminal payment, a FAILED callback after a terminal SUCCESS, and a callback whose
 * amount disagrees with {@code payment.amount} — all three were verified unrecorded (two of them
 * entirely silent) before that orchestrator existed. */
public enum ReconciliationType {
    PAYMENT_VS_PG("PAYMENT"),
    PAYMENT_VS_SETTLEMENT("SETTLEMENT"),
    ORDER_VS_FULFILLMENT("PARENT_ORDER"),
    PROVIDER_VS_REPORT(null),
    MARGIN_EXPECTED_VS_ACTUAL("PARENT_ORDER");

    private final String referenceType;

    ReconciliationType(String referenceType) {
        this.referenceType = referenceType;
    }

    /**
     * What {@code reconciliation.reference_id} points at for this type, as a table-ish label for
     * the Admin Web read surface (Section 41.9). {@code reference_id} is a plain nullable
     * {@code BIGINT} with no FK and no discriminator column — Section 22.23 defines it that way
     * because it is polymorphic across types — so a reader holding a row has no way to know
     * whether {@code reference_id=7} means payment 7 or parent_order 7. That ambiguity is
     * invisible in SQL and actively dangerous in a UI, which is why the mapping lives here, on the
     * type that determines it, rather than being re-derived by each caller.
     *
     * <p>Each value below is read off the single {@code ReconciliationService.open} call site that
     * produces that type, not inferred from the name:
     * <ul>
     *   <li>{@code PAYMENT_VS_PG} — {@code PaymentCallbackAnomalyOrchestrator} passes
     *       {@code event.paymentId()}.</li>
     *   <li>{@code PAYMENT_VS_SETTLEMENT} — {@code SettlementIngestionOrchestrator} passes
     *       {@code settlement.getId()}.</li>
     *   <li>{@code ORDER_VS_FULFILLMENT} — {@code OrderFulfillmentReconciliationOrchestrator} and
     *       {@code PaymentCallbackAnomalyOrchestrator}'s late-callback path both pass a
     *       {@code parentOrderId}.</li>
     *   <li>{@code MARGIN_EXPECTED_VS_ACTUAL} — {@code MarginReconciliationOrchestrator} passes a
     *       {@code parentOrderId}.</li>
     * </ul>
     *
     * @return the referent's label, or {@code null} for {@link #PROVIDER_VS_REPORT} — deliberately
     *     unanswered rather than guessed, because nothing in this codebase opens that type (there
     *     is no provider report ingestion source; see the README). Whoever wires that ingestion
     *     decides what its {@code reference_id} means and fills this in here.
     */
    public String referenceType() {
        return referenceType;
    }
}
