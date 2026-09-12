package id.ppob2.fulfillment;

/**
 * Result of {@link ProviderTransactionRecorder#record}. {@code newlyInserted} distinguishes a
 * fresh row from one that already existed (an internal Section 27.2 retry re-entering this same
 * idempotency key) — {@link FulfillmentExecutionService} must only post a Provider/Fulfillment
 * Ledger DEBIT the first time, or a re-dispatch would double-post against an append-only,
 * no-UPDATE/DELETE table.
 */
public record RecordedProviderTransaction(Long providerTransactionId, boolean newlyInserted) {
}
