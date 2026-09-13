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
