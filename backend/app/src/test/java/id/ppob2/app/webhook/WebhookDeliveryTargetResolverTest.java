package id.ppob2.app.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mocked-repository test for the guard branches the real end-to-end run (documented in
 * backend/README.md) didn't need to exercise on its own: the e2e run proved the "everything
 * present" success and HTTP-failure paths (including an independently-recomputed HMAC signature
 * match) against real Postgres and a real local HTTP receiver. This covers what stops a delivery
 * attempt before any HTTP call is made — no callback_url, no partner, and no webhook_secret.
 */
class WebhookDeliveryTargetResolverTest {

    private final ParentOrderRepository parentOrderRepository = mock(ParentOrderRepository.class);
    private final PartnerRepository partnerRepository = mock(PartnerRepository.class);
    private final WebhookDeliveryTargetResolver resolver = new WebhookDeliveryTargetResolver(parentOrderRepository, partnerRepository);

    private static ParentOrder orderWithCallback(OrderState state, String callbackUrl, Long partnerId) {
        ParentOrder order = new ParentOrder("ORD-1", 1L, partnerId, "client-1", 1L, Money.of(60000L),
                "API", "idem-1", "cust-ref", Instant.now(), callbackUrl);
        order.transitionTo(OrderState.PAYMENT_PENDING);
        order.transitionTo(OrderState.PAID);
        if (state == OrderState.REFUND_PENDING) {
            order.transitionTo(state);
            return order;
        }
        order.setPatternId(1L);
        order.transitionTo(OrderState.DECOMPOSITION_SELECTED);
        order.transitionTo(OrderState.FULFILLING);
        order.transitionTo(state);
        return order;
    }

    @Test
    void nonTerminalStateResolvesToNothing() {
        ParentOrder order = orderWithCallback(OrderState.REFUND_PENDING, "http://example.com/cb", 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThat(resolver.resolve(1L)).isEmpty();
    }

    @Test
    void noCallbackUrlResolvesToNothing() {
        ParentOrder order = orderWithCallback(OrderState.SUCCESS, null, 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThat(resolver.resolve(1L)).isEmpty();
    }

    @Test
    void blankCallbackUrlResolvesToNothing() {
        ParentOrder order = orderWithCallback(OrderState.SUCCESS, "   ", 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThat(resolver.resolve(1L)).isEmpty();
    }

    @Test
    void noPartnerResolvesToNothing() {
        ParentOrder order = orderWithCallback(OrderState.SUCCESS, "http://example.com/cb", null);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThat(resolver.resolve(1L)).isEmpty();
    }

    @Test
    void partnerWithNoWebhookSecretResolvesToNothingRatherThanSendingUnsigned() {
        ParentOrder order = orderWithCallback(OrderState.SUCCESS, "http://example.com/cb", 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        Partner partner = new Partner("PPOB1", "PPOB1", 1L, "ACTIVE", null);
        given(partnerRepository.findById(1L)).willReturn(Optional.of(partner));

        assertThat(resolver.resolve(1L)).isEmpty();
    }

    @Test
    void everythingPresentResolvesToADeliveryTarget() {
        ParentOrder order = orderWithCallback(OrderState.PARTIAL_FAILED, "http://example.com/cb", 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        Partner partner = mock(Partner.class);
        given(partner.getWebhookSecret()).willReturn("secret-123");
        given(partner.getCode()).willReturn("PPOB1");
        given(partnerRepository.findById(1L)).willReturn(Optional.of(partner));

        Optional<WebhookDeliveryTarget> target = resolver.resolve(1L);

        assertThat(target).isPresent();
        assertThat(target.get().callbackUrl()).isEqualTo("http://example.com/cb");
        assertThat(target.get().webhookSecret()).isEqualTo("secret-123");
        assertThat(target.get().partnerCode()).isEqualTo("PPOB1");
        assertThat(target.get().orderNo()).isEqualTo("ORD-1");
        assertThat(target.get().state()).isEqualTo("PARTIAL_FAILED");
    }
}
