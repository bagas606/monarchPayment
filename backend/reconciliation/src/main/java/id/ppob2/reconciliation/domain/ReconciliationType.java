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
    PAYMENT_VS_PG,
    PAYMENT_VS_SETTLEMENT,
    ORDER_VS_FULFILLMENT,
    PROVIDER_VS_REPORT,
    MARGIN_EXPECTED_VS_ACTUAL
}
