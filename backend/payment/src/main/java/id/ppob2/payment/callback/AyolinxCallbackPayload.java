package id.ppob2.payment.callback;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Parsed shape of an inbound Ayolinx QRIS callback (doc.ayolinx.id/api-299374923, "Payment
 * Notify" for {@code qr-mpm-notify}), verified against the public docs — no sandbox credential
 * exists yet to confirm this against a real callback, so still flagged for a final check once
 * one arrives.
 *
 * <p>{@code @JsonNaming(LowerCamelCaseStrategy)} is required here specifically: the app's global
 * Jackson config ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}, for our own
 * partner-facing JSON) would otherwise make the shared {@code ObjectMapper} bean expect
 * {@code original_partner_reference_no} instead of Ayolinx's actual {@code
 * originalPartnerReferenceNo} — this override wins over the global default for this class only.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AyolinxCallbackPayload(
        String callbackType,
        AdditionalInfo additionalInfo,
        Amount amount,
        String customerNumber,
        String latestTransactionStatus,
        String originalPartnerReferenceNo,
        String originalReferenceNo,
        String finishedTime
) {
    /** {@code payment.pg_reference} is our own {@code orderNo} (see
     * {@code AyolinxPaymentGateway}'s Javadoc) — Ayolinx's notify body carries it back as
     * {@code originalPartnerReferenceNo}. */
    public String pgReference() {
        return originalPartnerReferenceNo;
    }

    /**
     * There is no event-id field in this payload, and the same transaction legitimately produces
     * multiple callbacks across its status progression (01 Initiated -> 02 Paying -> 00 Success,
     * say) — deriving a dedup key from the reference alone would make the first non-terminal
     * callback consume the dedup slot and cause the real terminal callback to be dropped as a
     * "duplicate". Composing with the status makes each distinct transition its own dedup key,
     * while still collapsing genuine retries of the *same* status.
     */
    public String dedupKey() {
        return originalReferenceNo + ":" + latestTransactionStatus;
    }

    /** doc-5923355's status table: {@code 00} = Success. */
    public boolean isSuccess() {
        return "00".equals(latestTransactionStatus);
    }

    /** {@code 06} = Failed. */
    public boolean isFailed() {
        return "06".equals(latestTransactionStatus);
    }

    /** {@code 05} = Canceled. No {@code PaymentStatus}/order-state-machine transition exists for
     * this yet (same gap already noted for FAILED in {@code PaymentCallbackService}) — surfaced
     * separately from {@link #isNonTerminal()} so it isn't mistaken for an expected mid-flow
     * status while that gap remains open. */
    public boolean isCancelled() {
        return "05".equals(latestTransactionStatus);
    }

    /** {@code 01} Initiated / {@code 02} Paying / {@code 03} Pending / {@code 07} Not found —
     * expected non-terminal states, not an unrecognized status. */
    public boolean isNonTerminal() {
        return latestTransactionStatus != null
                && (latestTransactionStatus.equals("01") || latestTransactionStatus.equals("02")
                    || latestTransactionStatus.equals("03") || latestTransactionStatus.equals("07"));
    }

    public Instant paidAt() {
        if (finishedTime == null || finishedTime.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(finishedTime).toInstant();
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(String currency, String value) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdditionalInfo(String channel, String bankCode, String bankName, String errorCode,
                                  Amount feeMoney, String rRN, String trxType, String userName) {
    }
}
