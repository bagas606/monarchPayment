package id.ppob2.payment.callback;

import java.time.Instant;

/**
 * Parsed shape of an inbound Ayolinx QRIS callback. Section 25.2 specifies the *behavior*
 * (verify signature, dedup by event id, drive the state machine) but not Ayolinx's actual JSON
 * schema — there is no contract/sandbox doc available yet. This shape is a reasonable
 * placeholder (event id, PG reference, terminal status, paid timestamp) flagged, like Section
 * 37.1's settlement report format, as "must be verified against the real Ayolinx API contract"
 * before this is production-real.
 */
public record AyolinxCallbackPayload(
        String eventId,
        String pgReference,
        String status,
        Instant paidAt
) {
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "FAILED".equalsIgnoreCase(status);
    }
}
