package id.ppob2.payment.gateway;

import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;

/** Section 25.1/25.2: request to open a Dynamic QRIS transaction for a parent order. */
public record PaymentCreateRequest(
        String orderNo,
        Money amount,
        Instant expiresAt
) {
}
