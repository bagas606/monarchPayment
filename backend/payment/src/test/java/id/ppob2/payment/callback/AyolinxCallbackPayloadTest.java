package id.ppob2.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

/**
 * Deserializes against a SNAKE_CASE-configured {@link ObjectMapper} deliberately — that is the
 * app's actual shared bean configuration ({@code spring.jackson.property-naming-strategy:
 * SNAKE_CASE} in application.yml), and the whole point of this record's {@code @JsonNaming}
 * override is to still parse Ayolinx's real camelCase JSON correctly despite that global setting.
 * A test using a plain default {@code ObjectMapper} would not have caught a regression here.
 */
class AyolinxCallbackPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static final String SUCCESS_JSON = """
            {
              "callbackType": "QRIS",
              "additionalInfo": {"channel": "BNC_QRIS", "rRN": "RRN123"},
              "amount": {"currency": "IDR", "value": "15000.00"},
              "customerNumber": "0812345",
              "latestTransactionStatus": "00",
              "originalPartnerReferenceNo": "ORD-1",
              "originalReferenceNo": "AYO-REF-1",
              "finishedTime": "2024-09-18T06:36:36+00:00"
            }
            """;

    @Test
    void deserializesRealShapedCamelCaseJsonDespiteTheAppsGlobalSnakeCaseConfig() throws Exception {
        AyolinxCallbackPayload payload = objectMapper.readValue(SUCCESS_JSON, AyolinxCallbackPayload.class);

        assertThat(payload.originalPartnerReferenceNo()).isEqualTo("ORD-1");
        assertThat(payload.pgReference()).isEqualTo("ORD-1");
        assertThat(payload.latestTransactionStatus()).isEqualTo("00");
        assertThat(payload.additionalInfo().channel()).isEqualTo("BNC_QRIS");
        assertThat(payload.isSuccess()).isTrue();
    }

    /**
     * Pins the {@code amount} parse against the doc-shaped body above, because
     * {@code PaymentCallbackService} now refuses to apply a payment whose reported amount disagrees
     * with {@code payment.amount} — so a parse that silently returns {@code null} for a perfectly
     * normal Ayolinx amount would flag every real payment instead of none.
     *
     * <p><b>Honest limit of this test:</b> {@code "15000.00"} is the format taken from Ayolinx's
     * public docs and from the outbound side this codebase controls
     * ({@code AyolinxPaymentGateway.rupiahToAyolinxAmount}). A genuine inbound sandbox callback WAS
     * received and is documented in the README, but its {@code amount} field was never recorded
     * against an order with a known billed amount, so the inbound format is assumed, not verified.
     * That is why an unparseable amount deliberately does NOT block the payment — see
     * {@code PaymentCallbackAnomalyEvent.Kind.AMOUNT_UNVERIFIED}. Capture a real callback's
     * {@code amount} before go-live and pin it here.
     */
    @Test
    void reportedAmountParsesTheDocumentedDecimalStringAsWholeRupiah() throws Exception {
        AyolinxCallbackPayload payload = objectMapper.readValue(SUCCESS_JSON, AyolinxCallbackPayload.class);
        assertThat(payload.reportedAmount()).isEqualTo(id.ppob2.sharedkernel.money.Money.of(15000L));
    }

    @Test
    void reportedAmountAcceptsOnlyUnambiguousFormats() {
        var m15000 = id.ppob2.sharedkernel.money.Money.of(15000L);
        assertThat(payloadWithAmount("15000.00").reportedAmount()).isEqualTo(m15000);
        assertThat(payloadWithAmount("15000").reportedAmount()).isEqualTo(m15000);
        assertThat(payloadWithAmount("15,000.00").reportedAmount()).isEqualTo(m15000);
        assertThat(payloadWithAmount("1,234,567").reportedAmount())
                .isEqualTo(id.ppob2.sharedkernel.money.Money.of(1234567L));
    }

    /**
     * The regression this pins is the whole reason the format check is strict rather than forgiving.
     * An earlier version simply stripped commas and handed the rest to {@code BigDecimal}, reasoning
     * that tolerating separators is safer than rejecting them. Under that version
     * {@code "20.000,00"} — the pt/id/de convention, where the dot groups and the comma is the decimal
     * separator — became {@code "20.000"} became {@code Money 20}. Not null: a **confident wrong
     * number**, which {@code PaymentCallbackService} would then treat as an AMOUNT_MISMATCH and use to
     * refuse a correctly paid 20,000 order. Anything ambiguous must come back null, which routes it to
     * {@code AMOUNT_UNVERIFIED} (payment applied, human told) instead of blocking money on a guess.
     */
    @Test
    void reportedAmountReturnsNullForAmbiguousOrUnparseableFormatsRatherThanGuessing() {
        assertThat(payloadWithAmount("20.000,00").reportedAmount()).isNull();
        assertThat(payloadWithAmount("20.000").reportedAmount()).isNull();
        assertThat(payloadWithAmount("15 000,00").reportedAmount()).isNull();
        // IDR has no sub-rupiah denomination, so a fractional value means the field does not mean what
        // this code assumes -- reported as unparseable rather than rounded into a wrong number.
        assertThat(payloadWithAmount("15000.50").reportedAmount()).isNull();
        assertThat(payloadWithAmount("not-a-number").reportedAmount()).isNull();
        assertThat(payloadWithAmount("").reportedAmount()).isNull();
        assertThat(payloadWithAmount("-15000.00").reportedAmount()).isNull();
    }

    private static AyolinxCallbackPayload payloadWithAmount(String value) {
        return new AyolinxCallbackPayload("QRIS", null,
                new AyolinxCallbackPayload.Amount("IDR", value), null, "00", "ORD-1", "AYO-1", null);
    }

    @Test
    void paidAtParsesTheOffsetTimestamp() throws Exception {
        AyolinxCallbackPayload payload = objectMapper.readValue(SUCCESS_JSON, AyolinxCallbackPayload.class);
        assertThat(payload.paidAt()).isEqualTo(java.time.Instant.parse("2024-09-18T06:36:36Z"));
    }

    @Test
    void statusCodesMapToTheDocumentedTerminalAndNonTerminalBuckets() {
        assertThat(payloadWithStatus("00").isSuccess()).isTrue();
        assertThat(payloadWithStatus("06").isFailed()).isTrue();
        assertThat(payloadWithStatus("05").isCancelled()).isTrue();
        for (String pending : new String[]{"01", "02", "03", "07"}) {
            AyolinxCallbackPayload payload = payloadWithStatus(pending);
            assertThat(payload.isNonTerminal()).as("status %s", pending).isTrue();
            assertThat(payload.isSuccess()).isFalse();
            assertThat(payload.isFailed()).isFalse();
            assertThat(payload.isCancelled()).isFalse();
        }
    }

    @Test
    void dedupKeyVariesAcrossAStatusProgressionSoTheRealSuccessCallbackIsNotDroppedAsADuplicate() {
        // Same transaction (same originalReferenceNo), three callbacks as it progresses.
        AyolinxCallbackPayload initiated = payloadWithStatus("01");
        AyolinxCallbackPayload paying = payloadWithStatus("02");
        AyolinxCallbackPayload success = payloadWithStatus("00");

        assertThat(initiated.dedupKey()).isNotEqualTo(paying.dedupKey());
        assertThat(paying.dedupKey()).isNotEqualTo(success.dedupKey());
        assertThat(initiated.dedupKey()).isNotEqualTo(success.dedupKey());

        // A genuine retry of the exact same status must still collapse to the same key.
        assertThat(payloadWithStatus("00").dedupKey()).isEqualTo(success.dedupKey());
    }

    private static AyolinxCallbackPayload payloadWithStatus(String status) {
        return new AyolinxCallbackPayload("QRIS", null, null, "0812345", status, "ORD-1", "AYO-REF-1", null);
    }
}
