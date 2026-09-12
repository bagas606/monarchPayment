package id.ppob2.payment.gateway;

import id.ppob2.sharedkernel.money.Money;

public record RefundRequest(
        String pgReference,
        Money amount,
        String reason
) {
}
