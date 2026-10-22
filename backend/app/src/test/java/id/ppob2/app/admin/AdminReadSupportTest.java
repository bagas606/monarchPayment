package id.ppob2.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.order.domain.OrderState;
import id.ppob2.sharedkernel.error.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdminReadSupportTest {

    @Test
    void pagingDefaultsAndClampsAndReportsTheSizeActuallyUsed() {
        assertThat(Paging.of(null, null)).isEqualTo(new Paging(0, 25));
        assertThat(Paging.of(2, 100_000).size()).as("clamped, not rejected").isEqualTo(Paging.MAX_SIZE);
        // One extra row is fetched so "has more" needs no count(*).
        assertThat(Paging.of(0, 10).fetchLimit()).isEqualTo(11);
        assertThat(Paging.of(3, 10).offset()).isEqualTo(30);
    }

    @Test
    void pagingRejectsNonsenseInsteadOfRunningIt() {
        assertThatThrownBy(() -> Paging.of(-1, 10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Paging.of(0, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Paging.of(Paging.MAX_PAGE + 1, 10)).isInstanceOf(ApiException.class);
    }

    @Test
    void enumFilterRejectionListsTheAllowedValuesAndNeverEchoesTheInput() {
        assertThat(Paging.enumValue("state", "PAID", OrderState.class)).isEqualTo(OrderState.PAID);
        assertThat(Paging.enumValue("state", "  ", OrderState.class)).isNull();
        assertThatThrownBy(() -> Paging.enumValue("state", "x'; DROP TABLE parent_order;--", OrderState.class))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PAYMENT_PENDING")
                .hasMessageNotContaining("DROP");
    }

    @Test
    void badInstantIsRejectedWithoutEchoingTheValue() {
        assertThat(Paging.instant("from", "2026-10-01T00:00:00Z")).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(Paging.instant("from", null)).isNull();
        assertThatThrownBy(() -> Paging.instant("from", "yesterday"))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("yesterday");
    }

    @Test
    void sqlFilterBindsValuesAndNeverInlinesThem() {
        SqlFilter f = new SqlFilter()
                .and("a = :a", "a", "1' OR '1'='1")
                .and("b = :b", "b", null)
                .and("c = :c", "c", 5L);
        assertThat(f.where()).isEqualTo(" WHERE a = :a AND c = :c");
        assertThat(f.where()).doesNotContain("OR").doesNotContain("1'");
        assertThat(f.params().getValue("a")).isEqualTo("1' OR '1'='1");
        assertThat(f.params().hasValue("b")).isFalse();
        assertThat(new SqlFilter().where()).isEmpty();
    }

    @Test
    void redactorMasksSecretLookingKeysAtAnyDepthAndLeavesTheRestAlone() throws Exception {
        JsonRedactor redactor = new JsonRedactor(new ObjectMapper());
        JsonNode out = redactor.redact("""
                {"reason":"ticket OPS-9","password_hash":"$2a$10$abc","nested":{"webhookSecret":"s3",
                 "items":[{"api_key":"k","amount":"10000"}]},"signature":null}""");
        assertThat(out.get("reason").asText()).isEqualTo("ticket OPS-9");
        assertThat(out.get("password_hash").asText()).isEqualTo(JsonRedactor.MASK);
        assertThat(out.at("/nested/webhookSecret").asText()).isEqualTo(JsonRedactor.MASK);
        assertThat(out.at("/nested/items/0/api_key").asText()).isEqualTo(JsonRedactor.MASK);
        assertThat(out.at("/nested/items/0/amount").asText()).isEqualTo("10000");
        assertThat(out.get("signature").isNull()).as("a null stays null: nothing to hide").isTrue();
    }

    @Test
    void redactorHandlesNullAndRefusesToEchoUnparseableContent() {
        JsonRedactor redactor = new JsonRedactor(new ObjectMapper());
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("{not json, password=hunter2").asText()).isEqualTo("[unparseable]");
    }

    @Test
    void callbackUrlIsReducedToItsOrigin() {
        assertThat(AdminTransactionReader.originOnly("https://hooks.example.com/cb/abc123?token=SECRET"))
                .isEqualTo("https://hooks.example.com");
        assertThat(AdminTransactionReader.originOnly("http://10.0.0.5:8081/x")).isEqualTo("http://10.0.0.5:8081");
        assertThat(AdminTransactionReader.originOnly(null)).isNull();
        assertThat(AdminTransactionReader.originOnly("not a url")).isEqualTo("[unparseable]");
    }
}
