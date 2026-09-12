package id.ppob2.order;

import id.ppob2.order.domain.OrderState;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;

/**
 * PRD Section 23.5's response shape. {@code totalChild}/{@code successChild}/{@code failedChild}
 * do not necessarily sum while the order is still {@code FULFILLING} — a child order can be
 * {@code PENDING}/{@code EXECUTING} (neither success nor failed yet), so a caller must not treat
 * {@code totalChild - successChild} as {@code failedChild}.
 */
public record OrderDetailResult(
        String orderNo,
        OrderState state,
        Money parentAmount,
        PaymentStatus paymentStatus,
        int totalChild,
        int successChild,
        int failedChild,
        Instant updatedAt
) {
}
