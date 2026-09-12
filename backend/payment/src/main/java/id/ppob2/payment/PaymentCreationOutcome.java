package id.ppob2.payment;

import java.time.Instant;

/** Result of attempting to open a QRIS payment for an order — success or the Section 25.2
 * "PG unavailable" failure path, which the caller must not treat as a rolled-back order. */
public record PaymentCreationOutcome(
        boolean success,
        String qrPayload,
        Instant expiresAt,
        String failureReason
) {
    public static PaymentCreationOutcome success(String qrPayload, Instant expiresAt) {
        return new PaymentCreationOutcome(true, qrPayload, expiresAt, null);
    }

    public static PaymentCreationOutcome failure(String reason) {
        return new PaymentCreationOutcome(false, null, null, reason);
    }
}
