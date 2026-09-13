package id.ppob2.provider;

/** Section 27.1's {@code inquire(String providerReference)} — used for the "inquiry-before-retry"
 * step Section 34.1 calls for on ambiguous failures (e.g. connection reset after the request was
 * already sent). {@code status} is expected to be {@code SUCCESS} or {@code FAILED} — a confirmed
 * answer from the provider's side; {@code TIMEOUT} means the inquiry call itself was inconclusive
 * (the provider couldn't say either way), a third case {@code FulfillmentExecutionService} handles
 * separately from a confirmed failure rather than collapsing into it. */
public record InquiryResult(String providerReference, PurchaseStatus status) {
}
