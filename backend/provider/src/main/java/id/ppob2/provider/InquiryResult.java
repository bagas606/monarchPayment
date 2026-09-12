package id.ppob2.provider;

/** Section 27.1's {@code inquire(String providerReference)} — used for the "inquiry-before-retry"
 * step Section 34.1 calls for on ambiguous failures (e.g. connection reset after the request was
 * already sent). The interface exists for fidelity to Section 27.1's exact signature; no caller
 * in this slice's fulfillment pipeline invokes it yet — see the fulfillment README notes for why
 * that path is flagged as a gap rather than implemented. */
public record InquiryResult(String providerReference, PurchaseStatus status) {
}
