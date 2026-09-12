package id.ppob2.order;

import id.ppob2.payment.domain.PaymentStatus;
import java.time.Instant;

/** PRD Section 23.6's response shape. */
public record PaymentDetailResult(
        String orderNo,
        PaymentStatus status,
        Instant paidAt,
        String pgReference
) {
}
