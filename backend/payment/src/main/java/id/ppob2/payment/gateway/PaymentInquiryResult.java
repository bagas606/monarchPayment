package id.ppob2.payment.gateway;

import java.time.Instant;

public record PaymentInquiryResult(
        String pgReference,
        String pgStatus,
        Instant paidAt
) {
}
