package id.ppob2.order;

import id.ppob2.order.domain.OrderState;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;

public record CreateOrderResult(
        String orderNo,
        OrderState state,
        Money parentAmount,
        String qrPayload,
        Instant paymentExpiresAt,
        Instant createdAt
) {
}
