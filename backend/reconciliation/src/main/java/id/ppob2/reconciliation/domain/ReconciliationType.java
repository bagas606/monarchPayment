package id.ppob2.reconciliation.domain;

/** PRD Section 22.23 / 38.1's five minimum reconciliation types. {@link #PAYMENT_VS_SETTLEMENT}
 * and {@link #ORDER_VS_FULFILLMENT} are wired to something that opens a record in this codebase —
 * see the README for why the other three are blocked (no report ingestion source, or composition
 * across modules `reconciliation` has no edge to). */
public enum ReconciliationType {
    PAYMENT_VS_PG,
    PAYMENT_VS_SETTLEMENT,
    ORDER_VS_FULFILLMENT,
    PROVIDER_VS_REPORT,
    MARGIN_EXPECTED_VS_ACTUAL
}
