package id.ppob2.provider;

/** Outcome classes for {@link GameProvider#purchase}, Section 27.2's retry classification:
 * {@code TIMEOUT} (and, in a real adapter, 5xx) is the only idempotent-safe-retry class;
 * {@code FAILED} is terminal (e.g. the provider rejected the request outright).
 *
 * <p>{@code AMBIGUOUS} is Section 34.1's third class — "connection reset after the request was
 * already sent" — where the caller genuinely doesn't know if the purchase went through. It is
 * neither blindly retried (that risks a real double-purchase if it actually succeeded) nor treated
 * as terminal-failed (that risks silently eating a purchase that actually succeeded); it must be
 * resolved via {@link GameProvider#inquire} instead. See {@code FulfillmentExecutionService
 * .resolveAmbiguous}. */
public enum PurchaseStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    AMBIGUOUS
}
