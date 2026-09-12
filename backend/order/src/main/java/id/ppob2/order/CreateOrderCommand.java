package id.ppob2.order;

import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.money.Money;

/**
 * Input to {@link OrderApplicationService#createOrder}. {@code productId}/{@code productCategory}
 * are pre-resolved by the caller (the app/channel-adapter layer, via `catalog`) rather than
 * resolved here — `order` has no compile dependency on `catalog` per Section 20.2's allowed-
 * dependency graph, the same pattern used for supported-amount lookup in the config slice.
 */
public record CreateOrderCommand(
        ChannelContext channelContext,
        Long channelId,
        Long partnerId,
        Long productId,
        String productCode,
        String productCategory,
        Money parentAmount,
        String customerReference,
        String idempotencyKey,
        String callbackUrl
) {
}
