package id.ppob2.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.channel.ChannelType;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Idempotency (Section 23.4) seen from the one angle V23 changes: what a replay does to the
 * annotation fields — {@code metadata}, and {@code callback_url} before it.
 *
 * <p>Neither takes part in {@code ParentOrder.matchesRequest}, which means two things that are easy
 * to assume and were never asserted: a replay carrying different metadata is a <b>replay</b>, not an
 * {@code IDEMPOTENCY_KEY_CONFLICT}; and it leaves the stored metadata as the first accepted request
 * set it. The second is first-write-wins by omission — nothing writes to the existing row — so it is
 * the kind of behaviour that changes the day someone adds an innocuous-looking {@code save()} to
 * that branch. That is what these tests are for.
 */
class ParentOrderCreationServiceTest {

    private static final ChannelContext CONTEXT =
            new ChannelContext(ChannelType.RESELLER_API, "PPOB1", "ppob1-client", null, "ppob1-client", null);

    private final ParentOrderRepository repository = mock(ParentOrderRepository.class);
    private final OrderNumberGenerator orderNumberGenerator = mock(OrderNumberGenerator.class);

    private final ParentOrderCreationService service =
            new ParentOrderCreationService(repository, orderNumberGenerator);

    private static CreateOrderCommand command(String metadata, Money amount, String customerReference) {
        return new CreateOrderCommand(CONTEXT, 1L, 1L, 1L, "MOBILE_LEGENDS", "GAME_TOPUP",
                amount, customerReference, "PPOB1-TX-98213", "https://ppob1.example.com/cb", metadata);
    }

    private static ParentOrder existingOrder(String metadata) {
        return new ParentOrder("ORD-20261010-000007", 1L, 1L, "ppob1-client", 1L, Money.of(20_000L),
                "RESELLER_API", "PPOB1-TX-98213", "GAMEID-777",
                Instant.parse("2026-10-10T03:00:00Z"), "https://ppob1.example.com/cb", metadata);
    }

    @Test
    void anewOrderCarriesTheMetadataItWasCreatedWith() {
        given(repository.findByClientIdAndIdempotencyKey("ppob1-client", "PPOB1-TX-98213"))
                .willReturn(Optional.empty());
        given(orderNumberGenerator.next()).willReturn("ORD-20261010-000007");
        given(repository.save(any(ParentOrder.class))).willAnswer(call -> call.getArgument(0));

        OrderCreationOutcome outcome =
                service.createOrGetExisting(command("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}",
                        Money.of(20_000L), "GAMEID-777"));

        assertThat(outcome.isNew()).isTrue();
        ArgumentCaptor<ParentOrder> saved = ArgumentCaptor.forClass(ParentOrder.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMetadata()).isEqualTo("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}");
    }

    @Test
    void aReplayWithDifferentMetadataIsStillAReplay() {
        // Section 23.9's IDEMPOTENCY_KEY_CONFLICT is the one answer a client cannot recover from
        // without changing its own key, so it is reserved for requests that are genuinely different
        // orders. An annotation the caller varies between attempts (a retry counter, a timestamp)
        // is not that.
        given(repository.findByClientIdAndIdempotencyKey("ppob1-client", "PPOB1-TX-98213"))
                .willReturn(Optional.of(existingOrder("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}")));

        OrderCreationOutcome outcome = service.createOrGetExisting(
                command("{\"reseller_txn_id\":\"PPOB1-TX-98213\",\"attempt\":2}",
                        Money.of(20_000L), "GAMEID-777"));

        assertThat(outcome.isNew()).isFalse();
        assertThat(outcome.order().getOrderNo()).isEqualTo("ORD-20261010-000007");
    }

    @Test
    void aReplayDoesNotRewriteTheStoredMetadata() {
        ParentOrder stored = existingOrder("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}");
        given(repository.findByClientIdAndIdempotencyKey("ppob1-client", "PPOB1-TX-98213"))
                .willReturn(Optional.of(stored));

        OrderCreationOutcome outcome = service.createOrGetExisting(
                command("{\"reseller_txn_id\":\"SOMETHING-ELSE\"}", Money.of(20_000L), "GAMEID-777"));

        // First write wins. Support reading this column needs it to say what the order was created
        // with, and a replay must not mutate an order.
        assertThat(outcome.order().getMetadata()).isEqualTo("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}");
        verify(repository, never()).save(any(ParentOrder.class));
    }

    @Test
    void aReplayWithADifferentAmountIsStillAConflict() {
        // The complement, so the test above cannot be read as "metadata made the check lax": the
        // fields that decide what gets fulfilled and billed still produce a 409.
        given(repository.findByClientIdAndIdempotencyKey("ppob1-client", "PPOB1-TX-98213"))
                .willReturn(Optional.of(existingOrder("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}")));

        assertThatThrownBy(() -> service.createOrGetExisting(
                command("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}", Money.of(40_000L), "GAMEID-777")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
}
