package id.ppob2.sharedkernel.channel;

/**
 * Request-scoped holder for the resolved {@link ChannelContext}, set by the channel adapter
 * (Section 19 "API / Channel Adapters" layer) after successful authentication, and read by
 * downstream application services so no channel-specific service variant is ever needed.
 */
public final class ChannelContextHolder {

    private static final ThreadLocal<ChannelContext> CURRENT = new ThreadLocal<>();

    private ChannelContextHolder() {
    }

    public static void set(ChannelContext context) {
        CURRENT.set(context);
    }

    public static ChannelContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
