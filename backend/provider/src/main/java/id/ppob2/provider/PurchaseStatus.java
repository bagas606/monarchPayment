package id.ppob2.provider;

/** Outcome classes for {@link GameProvider#purchase}, Section 27.2's retry classification:
 * {@code TIMEOUT} (and, in a real adapter, 5xx) is the only idempotent-safe-retry class;
 * {@code FAILED} is terminal (e.g. the provider rejected the request outright). */
public enum PurchaseStatus {
    SUCCESS,
    FAILED,
    TIMEOUT
}
