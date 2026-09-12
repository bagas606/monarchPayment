package id.ppob2.payment.callback;

/** What the webhook controller should tell Ayolinx. Every case except {@link #SIGNATURE_INVALID}
 * and {@link #MALFORMED} acknowledges 200 — Section 48.5's duplicate-callback pattern and general
 * webhook practice both call for acking anything we've permanently classified, so the sender
 * stops retrying. */
public enum PaymentCallbackOutcome {
    PROCESSED,
    DUPLICATE_IGNORED,
    UNKNOWN_REFERENCE,
    STALE_TERMINAL_STATE,
    SIGNATURE_INVALID,
    MALFORMED
}
