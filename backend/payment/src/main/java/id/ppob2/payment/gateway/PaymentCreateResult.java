package id.ppob2.payment.gateway;

import java.time.Instant;

public record PaymentCreateResult(
        boolean success,
        String pgReference,
        String qrPayload,
        Instant expiresAt,
        String failureReason
) {
    public static PaymentCreateResult success(String pgReference, String qrPayload, Instant expiresAt) {
        return new PaymentCreateResult(true, pgReference, qrPayload, expiresAt, null);
    }

    public static PaymentCreateResult failure(String reason) {
        return new PaymentCreateResult(false, null, null, null, reason);
    }
}
