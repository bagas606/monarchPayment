package id.ppob2.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import id.ppob2.app.web.AdminOrderController.OrderLookupResponse;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The reverse lookup that closes the {@code metadata.reseller_txn_id} gap: reseller's id → PPOB2's
 * order.
 *
 * <p>Two of these cases exist because of how the lookup can go wrong rather than how it goes right.
 * {@link #twoPartnersMayShareOneResellerTxnIdAndBothAreNamed} covers the collision V23 deliberately
 * left possible by not making the index unique — the realistic fixture has one partner, so it would
 * never be driven end to end, and the shape it would have failed in (an {@code Optional} finder
 * throwing on two rows) is a 500 on a support lookup. {@link #aBlankIdIsRejectedRatherThanMatched}
 * covers {@code ?reseller_txn_id=}, which is a present-but-empty parameter rather than a missing
 * one, so {@code required = true} does not catch it.
 */
class AdminOrderControllerTest {

    private final ParentOrderRepository parentOrderRepository = mock(ParentOrderRepository.class);
    private final PartnerRepository partnerRepository = mock(PartnerRepository.class);

    private final AdminOrderController controller =
            new AdminOrderController(parentOrderRepository, partnerRepository);

    /** The app's real Jackson bean is SNAKE_CASE ({@code application.yml}); serializing through
     * anything else would not show what an Admin Web client receives. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** {@code id} is DB-generated, so no constructor sets it — and it is the field an operator uses
     * next, so a fixture leaving it null would assert the projection minus its handle. */
    private static ParentOrder withId(long id, ParentOrder order) {
        try {
            Field field = ParentOrder.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
            return order;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ParentOrder order(long id, String orderNo, Long partnerId, String clientId, String metadata) {
        return withId(id, new ParentOrder(orderNo, 1L, partnerId, clientId, 1L, Money.of(20_000L),
                "RESELLER_API", "PPOB1-TX-98213", "GAMEID-777",
                Instant.parse("2026-10-10T03:00:00Z"), null, metadata));
    }

    @Test
    void resolvesAResellersOwnIdToPpob2sOrder() {
        given(parentOrderRepository.findByResellerTxnId("PPOB1-TX-98213")).willReturn(List.of(
                order(7L, "ORD-20261010-000007", 1L, "ppob1-client",
                        "{\"reseller_txn_id\":\"PPOB1-TX-98213\"}")));
        given(partnerRepository.findAllById(List.of(1L)))
                .willReturn(List.of(partner(1L, "PPOB1")));

        OrderLookupResponse body = ok(controller.lookupByResellerTxnId("PPOB1-TX-98213"));

        assertThat(body.count()).isEqualTo(1);
        assertThat(body.truncated()).isFalse();
        assertThat(body.items()).singleElement().satisfies(match -> {
            assertThat(match.orderId()).isEqualTo("ORD-20261010-000007");
            assertThat(match.partnerCode()).isEqualTo("PPOB1");
            // Handed back as stored, so a key PPOB2 has no reader for still reaches the operator.
            assertThat(match.metadata()).isEqualTo("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}");
        });
    }

    @Test
    void twoPartnersMayShareOneResellerTxnIdAndBothAreNamed() {
        // V23 leaves the index non-unique on purpose: reseller_txn_id is each reseller's own
        // counter, and PPOB2 must not refuse one partner's order because another reused a string.
        // The cost lands here -- the lookup is one-to-many, and the answer is only usable if each
        // match says whose it is.
        given(parentOrderRepository.findByResellerTxnId("TX-1")).willReturn(List.of(
                order(9L, "ORD-B", 2L, "other-client", "{\"reseller_txn_id\":\"TX-1\"}"),
                order(4L, "ORD-A", 1L, "ppob1-client", "{\"reseller_txn_id\":\"TX-1\"}")));
        given(partnerRepository.findAllById(List.of(2L, 1L)))
                .willReturn(List.of(partner(2L, "PPOB3"), partner(1L, "PPOB1")));

        OrderLookupResponse body = ok(controller.lookupByResellerTxnId("TX-1"));

        assertThat(body.count()).isEqualTo(2);
        assertThat(body.items()).extracting("orderId", "partnerCode")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ORD-B", "PPOB3"),
                        org.assertj.core.groups.Tuple.tuple("ORD-A", "PPOB1"));
    }

    @Test
    void aTruncatedResultSaysSoInsteadOfLookingComplete() {
        // The repository asks for MAX_RESULTS + 1 precisely so this is detectable. A hundred rows
        // with no flag would be read as all of them, which on a support lookup means concluding an
        // id maps to a hundred orders when it maps to more.
        List<ParentOrder> many = new ArrayList<>();
        for (int i = 0; i <= AdminOrderController.MAX_RESULTS; i++) {
            many.add(order(i + 1, "ORD-" + i, 1L, "ppob1-client", "{\"reseller_txn_id\":\"TX-DUP\"}"));
        }
        given(parentOrderRepository.findByResellerTxnId("TX-DUP")).willReturn(many);
        given(partnerRepository.findAllById(List.of(1L))).willReturn(List.of(partner(1L, "PPOB1")));

        OrderLookupResponse body = ok(controller.lookupByResellerTxnId("TX-DUP"));

        assertThat(body.count()).isEqualTo(AdminOrderController.MAX_RESULTS);
        assertThat(body.truncated()).isTrue();
    }

    @Test
    void anUnknownIdIsAnEmptyResultNotAnError() {
        // Section 23.9's ORDER_NOT_FOUND is a partner-facing code for a partner's own order. An
        // operator searching a reseller id that PPOB2 never received has asked a valid question
        // whose answer is "none" -- and "none" is itself the finding, since it means the reseller
        // believes it sent something PPOB2 has no record of.
        given(parentOrderRepository.findByResellerTxnId("NOPE")).willReturn(List.of());

        OrderLookupResponse body = ok(controller.lookupByResellerTxnId("NOPE"));

        assertThat(body.count()).isZero();
        assertThat(body.items()).isEmpty();
        assertThat(body.resellerTxnId()).isEqualTo("NOPE");
        // No partner lookup at all when there is nothing to name.
        verifyNoInteractions(partnerRepository);
    }

    @Test
    void aBlankIdIsRejectedRatherThanMatched() {
        assertThatThrownBy(() -> controller.lookupByResellerTxnId("  "))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        // Nothing was queried: an empty-string match is a legal JSONB comparison that would have
        // returned whichever orders carry one, and an operator would read that as the answer.
        verifyNoInteractions(parentOrderRepository);
    }

    @Test
    void anOrderWithNoPartnerStillResolves() {
        // Section 22.15 allows a null partner_id for future consumer channels. Every order today
        // has one, so this is the branch a fixture would never reach.
        given(parentOrderRepository.findByResellerTxnId("TX-NOPARTNER")).willReturn(List.of(
                order(11L, "ORD-C", null, "web-client", "{\"reseller_txn_id\":\"TX-NOPARTNER\"}")));

        OrderLookupResponse body = ok(controller.lookupByResellerTxnId("TX-NOPARTNER"));

        assertThat(body.items()).singleElement()
                .satisfies(match -> assertThat(match.partnerCode()).isNull());
        verifyNoInteractions(partnerRepository);
    }

    @Test
    void theResponseIsSnakeCaseAsAnAdminWebClientSeesIt() throws Exception {
        given(parentOrderRepository.findByResellerTxnId("PPOB1-TX-98213")).willReturn(List.of(
                order(7L, "ORD-20261010-000007", 1L, "ppob1-client",
                        "{\"reseller_txn_id\":\"PPOB1-TX-98213\"}")));
        given(partnerRepository.findAllById(List.of(1L))).willReturn(List.of(partner(1L, "PPOB1")));

        String json = objectMapper.writeValueAsString(ok(controller.lookupByResellerTxnId("PPOB1-TX-98213")));

        assertThat(json).contains("\"reseller_txn_id\":\"PPOB1-TX-98213\"");
        assertThat(json).contains("\"partner_code\":\"PPOB1\"");
        assertThat(json).contains("\"order_id\":\"ORD-20261010-000007\"");
        // Money renders as a bare integer (@JsonValue on Money), not an object.
        assertThat(json).contains("\"parent_amount\":20000");
    }

    private static Partner partner(long id, String code) {
        Partner partner = new Partner(code, code, 1L, "ACTIVE", null);
        try {
            Field field = Partner.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(partner, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return partner;
    }

    private static OrderLookupResponse ok(ResponseEntity<OrderLookupResponse> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }
}
