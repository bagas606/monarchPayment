package id.ppob2.sharedkernel.channel;

/**
 * PRD Section 18.1: every channel (present or future) must invoke the order entry point
 * through this single value object. No channel-specific service variants are permitted.
 */
public record ChannelContext(
        ChannelType channelType,
        String partnerId,
        String clientId,
        String userId,
        String applicationId,
        String deviceSessionId
) {
}
