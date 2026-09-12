package id.ppob2.webhook;

/** Result of {@link OutboundWebhookSender#send} — {@code requestBody} is always populated (even
 * on failure) so the caller can log it in the {@code webhook_event} row for diagnosis. */
public record OutboundWebhookDeliveryResult(boolean success, String requestBody, String failureReason) {

    public static OutboundWebhookDeliveryResult success(String requestBody) {
        return new OutboundWebhookDeliveryResult(true, requestBody, null);
    }

    public static OutboundWebhookDeliveryResult failure(String requestBody, String failureReason) {
        return new OutboundWebhookDeliveryResult(false, requestBody, failureReason);
    }
}
