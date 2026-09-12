package id.ppob2.app.webhook;

/** Everything {@link OutboundWebhookOrchestrator} needs to attempt one delivery, resolved by
 * {@link WebhookDeliveryTargetResolver} before any HTTP call is made. */
public record WebhookDeliveryTarget(String callbackUrl, String webhookSecret, String partnerCode, String orderNo, String state) {
}
