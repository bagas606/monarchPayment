package id.ppob2.webhook.domain;

/** `webhook_delivery.status`. Not part of PRD Section 22 (see {@link WebhookDelivery}'s Javadoc
 * for why this table exists at all). */
public enum WebhookDeliveryStatus {
    /** Still eligible for another attempt at {@code next_attempt_at}. */
    PENDING,
    /** A retry attempt succeeded. Terminal. */
    DELIVERED,
    /** Every configured attempt was used up without success. Terminal — Section 23.8's "eventual
     * fallback to inquiry-only" is not built (that's PPOB1 polling our Open API, not something
     * this codebase pushes), so this state is where a delivery is left for now. */
    EXHAUSTED
}
