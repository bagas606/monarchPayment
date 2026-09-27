package id.ppob2.payment.callback;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import id.ppob2.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Parsed shape of an inbound Ayolinx QRIS callback (doc.ayolinx.id/api-299374923, "Payment
 * Notify" for {@code qr-mpm-notify}). Confirmed 2026-09-14 against a genuine callback delivered by
 * Ayolinx's sandbox (Demo Mode auto-completes an issued QR and calls back the registered URL): the
 * fields this record models ({@code callbackType}, {@code latestTransactionStatus},
 * {@code originalPartnerReferenceNo}, {@code originalReferenceNo}, {@code amount},
 * {@code customerNumber}) all deserialized correctly, and unmodeled {@code additionalInfo} fields
 * from the real payload were tolerated by {@code @JsonIgnoreProperties(ignoreUnknown = true)} as
 * intended. Signature verification of that same callback failed — see
 * {@code AyolinxPaymentGateway}'s Javadoc and {@code backend/README.md} for that separate,
 * still-open issue; it is unrelated to this class's parsing, which is now real-traffic-verified.
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
    private static final java.util.regex.Pattern PLAIN_AMOUNT =
            java.util.regex.Pattern.compile("^\\d+(\\.\\d{1,2})?$");
    private static final java.util.regex.Pattern GROUPED_AMOUNT =
            java.util.regex.Pattern.compile("^\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?$");

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

    /** {@code 04} = Refunded. Previously in no bucket at all, so a refund notification fell through
     * to {@code PaymentCallbackService}'s "Unrecognized status" WARN and was answered {@code 200}
     * with nothing recorded — for a payment whose goods this platform has already delivered. Bucketed
     * explicitly so {@link #isTerminalNonSuccess()} can catch it. */
    public boolean isRefunded() {
        return "04".equals(latestTransactionStatus);
    }

    /**
     * Any terminal status that is not a success: {@code 04} Refunded, {@code 05} Canceled, {@code 06}
     * Failed. Exists so the "arrived after a terminal SUCCESS" anomaly check can be written once over
     * the whole class instead of per status code — the original bug was exactly that {@code 06} was
     * the only one anybody thought to handle, and enumerating cases one at a time is what produced
     * three differently-broken branches.
     */
    public boolean isTerminalNonSuccess() {
        return isRefunded() || isCancelled() || isFailed();
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

    /**
     * The amount Ayolinx says was actually paid, as {@link Money} (whole rupiah), or {@code null}
     * when the field is absent or unparseable. Ayolinx sends a decimal string ({@code "20000.00"})
     * while {@code payment.amount} is a whole-rupiah {@code NUMERIC(18,0)} — the same asymmetry
     * {@code AyolinxPaymentGateway.rupiahToAyolinxAmount} handles on the outbound side, mirrored
     * here for the inbound direction.
     *
     * <p>A non-zero fractional part is treated as unparseable rather than truncated: IDR has no
     * sub-rupiah denomination, so a fractional value means the field does not mean what this code
     * assumes, and silently rounding it would be exactly the kind of quiet money bug the caller's
     * amount check exists to catch.
     */
    public Money reportedAmount() {
        if (amount == null || amount.value() == null || amount.value().isBlank()) {
            return null;
        }
        // Only two shapes are accepted, and everything else returns null (-> AMOUNT_UNVERIFIED,
        // which applies the payment and tells a human, rather than refusing money over a guess):
        //   1234  /  1234.00        plain, dot as the DECIMAL separator -- Ayolinx's documented form
        //   1,234,567.00            en-US grouping
        //
        // An earlier version of this method just did `.replace(",", "")` and handed the rest to
        // BigDecimal, on the reasoning that tolerating separators is safer than rejecting them. That
        // was self-defeating, and provably so: "20.000,00" (the pt/id/de convention, where dot groups
        // and comma is the decimal separator) becomes "20.000" becomes **Money 20**. Not null -- a
        // confident wrong number, which would then trip AMOUNT_MISMATCH and refuse a correctly paid
        // 20,000 order. Being lenient about the format is exactly how you end up blocking money on a
        // guess, which is the thing this whole area is supposed to avoid. Ambiguity must become null.
        String raw = amount.value().trim();
        if (!PLAIN_AMOUNT.matcher(raw).matches() && !GROUPED_AMOUNT.matcher(raw).matches()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.replace(",", ""));
            if (value.stripTrailingZeros().scale() > 0) {
                return null;
            }
            return Money.of(value.toBigIntegerExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
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
