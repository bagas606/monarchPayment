package id.ppob2.fulfillment.domain;

/** PRD Section 22.19. {@code UNKNOWN} is reserved for the not-yet-implemented
 * inquiry-before-retry path (ambiguous failures) — nothing in this slice produces it. */
public enum ProviderTransactionStatus {
    PENDING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    UNKNOWN
}
