package id.ppob2.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import id.ppob2.app.web.SettlementIngestionController.SettlementIngestionRequest;
import id.ppob2.app.web.SettlementIngestionController.SettlementIngestionResponse;
import id.ppob2.sharedkernel.money.Money;
import java.math.BigInteger;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Deserializes/serializes against a SNAKE_CASE-configured {@link ObjectMapper} deliberately —
 * that is the app's actual shared bean configuration ({@code
 * spring.jackson.property-naming-strategy: SNAKE_CASE} in application.yml) — the same reasoning
 * {@code AyolinxCallbackPayloadTest} already documents for the same class of regression. A test
 * using a plain default {@code ObjectMapper} would not have caught this: it would happily bind
 * camelCase JSON to camelCase record fields regardless of whether {@code @JsonNaming} is present,
 * masking exactly the mismatch that broke {@code POST /internal/settlement/ingest} against the
 * app's real, globally-configured bean.
 *
 * <p>This is a pure Jackson-level test of the two nested records, not a {@code MockMvc}/Spring
 * context test — the bug lived entirely in the (de)serialization shape, not in any controller
 * behavior, so binding the records directly through the same {@code ObjectMapper} configuration
 * the app actually wires is sufficient to prove the fix (and would have caught the regression).
 */
class SettlementIngestionControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules() // picks up jackson-datatype-jsr310 for LocalDate, same as the app's real bean
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void deserializesTheRequestsOwnCamelCaseFieldNamesDespiteTheAppsGlobalSnakeCaseConfig() throws Exception {
        String json = """
                {
                  "settlementDate": "2026-09-14",
                  "pgReference": "E2E-BATCH-1",
                  "actualAmount": 100001,
                  "feeAmount": 1
                }
                """;

        SettlementIngestionRequest request = objectMapper.readValue(json, SettlementIngestionRequest.class);

        // Before the @JsonNaming fix, every field here deserialized to null against the app's
        // real SNAKE_CASE-configured ObjectMapper -- Jackson found no "settlement_date"/
        // "pg_reference"/"actual_amount"/"fee_amount" property to bind "settlementDate" etc. to,
        // and the null actualAmount then NPE'd inside Money.of(...) rather than failing with a
        // clear validation error.
        assertThat(request.settlementDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(request.pgReference()).isEqualTo("E2E-BATCH-1");
        assertThat(request.actualAmount()).isEqualTo(BigInteger.valueOf(100001));
        assertThat(request.feeAmount()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void rejectsLoudlyRatherThanSilentlyNullingTheOldSnakeCaseWorkaroundShape() {
        // Documents the other half of the fix's symmetry: once the record has its own
        // @JsonNaming override, the global SNAKE_CASE shape this endpoint used to require (the
        // workaround actually used to call it, end-to-end, before this fix -- see the README) no
        // longer matches either. This now fails loudly (UnrecognizedPropertyException, since
        // there's no @JsonIgnoreProperties(ignoreUnknown = true) here) rather than the silent
        // all-null deserialization the original camelCase-vs-global-SNAKE_CASE mismatch produced
        // -- a strictly better failure mode than the bug this class exists to guard against.
        String snakeCaseJson = """
                {
                  "settlement_date": "2026-09-14",
                  "pg_reference": "E2E-BATCH-1",
                  "actual_amount": 100001,
                  "fee_amount": 1
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(snakeCaseJson, SettlementIngestionRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    @Test
    void serializesTheResponseUsingTheSameCamelCaseShapeAsTheRequest() throws Exception {
        SettlementIngestionResponse response = new SettlementIngestionResponse(
                1L, "DISCREPANCY", Money.of(100000L), Money.of(100001L), Money.of(1L));

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"settlementId\":1");
        assertThat(json).contains("\"status\":\"DISCREPANCY\"");
        assertThat(json).contains("\"expectedAmount\":100000");
        assertThat(json).contains("\"actualAmount\":100001");
        assertThat(json).contains("\"feeAmount\":1");
        // The global default this endpoint used to fall back to -- confirms the response no
        // longer emits snake_case either, keeping the endpoint's request/response casing
        // symmetric rather than fixing only the input side.
        assertThat(json).doesNotContain("settlement_id", "expected_amount", "actual_amount", "fee_amount");
    }
}
