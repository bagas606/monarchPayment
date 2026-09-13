package id.ppob2.app.webhook;

/** Result of one {@link OutboundWebhookOrchestrator#attempt} call — distinguishes "nothing to
 * deliver" (no {@code callback_url}/{@code webhook_secret} resolvable, not a failure to retry)
 * from an actual delivery attempt that failed and should be retried. */
public record OutboundWebhookAttemptResult(boolean noTarget, boolean success, String failureReason) {

    public static OutboundWebhookAttemptResult ofNoTarget() {
        return new OutboundWebhookAttemptResult(true, false, null);
    }

    public static OutboundWebhookAttemptResult ofSuccess() {
        return new OutboundWebhookAttemptResult(false, true, null);
    }

    public static OutboundWebhookAttemptResult ofFailure(String reason) {
        return new OutboundWebhookAttemptResult(false, false, reason);
    }
}
