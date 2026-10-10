package id.ppob2.app.webhook;

/**
 * Everything {@link OutboundWebhookOrchestrator} needs to attempt one delivery, resolved by
 * {@link WebhookDeliveryTargetResolver} before any HTTP call is made.
 *
 * <p>{@code resellerTxnId} is Section 23.4's {@code metadata.reseller_txn_id} for this order, or
 * {@code null} when the order carries no such metadata — which every order placed before V23 does,
 * and any order placed without the field still does. It is read back out of the column rather than
 * remembered from the create request: the callback may be sent hours later, by a retry sweep, in a
 * different process.
 */
public record WebhookDeliveryTarget(
        String callbackUrl,
        String webhookSecret,
        String partnerCode,
        String orderNo,
        String state,
        String resellerTxnId
) {
}
