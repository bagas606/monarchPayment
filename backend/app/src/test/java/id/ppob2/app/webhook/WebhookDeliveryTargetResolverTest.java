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
import id.ppob2.sharedkernel.security.KeyringSecretKeyProvider;
import id.ppob2.sharedkernel.security.SecretVault;
import java.time.Instant;
import java.util.Base64;
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
    private final SecretVault vault = new SecretVault(new KeyringSecretKeyProvider(
            "t1:" + Base64.getEncoder().encodeToString(new byte[32]), "t1"));
    private final WebhookDeliveryTargetResolver resolver = new WebhookDeliveryTargetResolver(parentOrderRepository, partnerRepository, vault);

    private String ciphertextFor(String partnerCode, String secret) {
        return vault.encrypt(secret, WebhookDeliveryTargetResolver.secretAad(partnerCode));
    }

    private static ParentOrder orderWithCallback(OrderState state, String callbackUrl, Long partnerId) {
        ParentOrder order = new ParentOrder("ORD-1", 1L, partnerId, "client-1", 1L, Money.of(60000L),
                "API", "idem-1", "cust-ref", Instant.now(), callbackUrl, null);
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

    /**
     * {@code EXPIRED} joined {@code NOTIFIABLE_STATES} on 2026-10-10. This resolver is the single
     * gate — whatever fires the orchestrator, a state absent from that set sends nothing — so it is
     * also the only place the change can be asserted at.
     */
    @Test
    void anExpiredOrderResolvesToADeliveryTarget() {
        ParentOrder order = new ParentOrder("ORD-1", 1L, 1L, "client-1", 1L, Money.of(20000L),
                "API", "idem-1", "cust-ref", Instant.now(), "http://example.com/cb", null);
        order.transitionTo(OrderState.PAYMENT_PENDING);
        order.transitionTo(OrderState.EXPIRED);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        given(parentOrderRepository.findResellerTxnIdByParentOrderId(1L)).willReturn("PPOB1-TX-1");
        Partner partner = mock(Partner.class);
        given(partner.getWebhookSecretCiphertext()).willReturn(ciphertextFor("PPOB1", "secret-123"));
        given(partner.getCode()).willReturn("PPOB1");
        given(partnerRepository.findById(1L)).willReturn(Optional.of(partner));

        Optional<WebhookDeliveryTarget> target = resolver.resolve(1L);

        assertThat(target).isPresent();
        assertThat(target.get().state()).isEqualTo("EXPIRED");
    }

    /**
     * And the boundary, so adding EXPIRED cannot be read as "every terminal state now notifies".
     * {@code CANCELLED} stays unpushed on purpose: cancellation is the partner's own request
     * (Section 23.7), so there is nothing it could learn from being told.
     */
    @Test
    void aCancelledOrderStillResolvesToNothing() {
        ParentOrder order = new ParentOrder("ORD-1", 1L, 1L, "client-1", 1L, Money.of(20000L),
                "API", "idem-1", "cust-ref", Instant.now(), "http://example.com/cb", null);
        order.transitionTo(OrderState.PAYMENT_PENDING);
        order.transitionTo(OrderState.CANCELLED);
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
        given(parentOrderRepository.findResellerTxnIdByParentOrderId(1L)).willReturn("PPOB1-TX-98213");
        Partner partner = mock(Partner.class);
        given(partner.getWebhookSecretCiphertext()).willReturn(ciphertextFor("PPOB1", "secret-123"));
        given(partner.getCode()).willReturn("PPOB1");
        given(partnerRepository.findById(1L)).willReturn(Optional.of(partner));

        Optional<WebhookDeliveryTarget> target = resolver.resolve(1L);

        assertThat(target).isPresent();
        assertThat(target.get().callbackUrl()).isEqualTo("http://example.com/cb");
        assertThat(target.get().webhookSecret()).isEqualTo("secret-123");
        assertThat(target.get().partnerCode()).isEqualTo("PPOB1");
        assertThat(target.get().orderNo()).isEqualTo("ORD-1");
        assertThat(target.get().state()).isEqualTo("PARTIAL_FAILED");
        // Read out of the column by SQL at delivery time, not remembered from the create request:
        // a retry sweep may send this hours later, in a different process.
        assertThat(target.get().resellerTxnId()).isEqualTo("PPOB1-TX-98213");
    }

    @Test
    void anOrderWithNoMetadataStillResolvesWithANullResellerId() {
        // Every order placed before V23, and any order placed without the field. The target must
        // resolve -- the callback is still owed -- carrying null, which OutboundWebhookSender turns
        // into an absent key rather than a null-valued one.
        ParentOrder order = orderWithCallback(OrderState.SUCCESS, "http://example.com/cb", 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        given(parentOrderRepository.findResellerTxnIdByParentOrderId(1L)).willReturn(null);
        Partner partner = mock(Partner.class);
        given(partner.getWebhookSecretCiphertext()).willReturn(ciphertextFor("PPOB1", "secret-123"));
        given(partner.getCode()).willReturn("PPOB1");
        given(partnerRepository.findById(1L)).willReturn(Optional.of(partner));

        Optional<WebhookDeliveryTarget> target = resolver.resolve(1L);

        assertThat(target).isPresent();
        assertThat(target.get().resellerTxnId()).isNull();
    }

    private Optional<WebhookDeliveryTarget> resolveWithStored(String stored) {
        ParentOrder order = orderWithCallback(OrderState.SUCCESS, "http://example.com/cb", 1L);
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(order));
        Partner partner = mock(Partner.class);
        given(partner.getWebhookSecretCiphertext()).willReturn(stored);
        given(partner.getCode()).willReturn("PPOB1");
        given(partnerRepository.findById(1L)).willReturn(Optional.of(partner));
        return resolver.resolve(1L);
    }

    @Test
    void aPlaintextSecretIsNeverUsedToSign() {
        // The shape every row had before V28. Sending unsigned or signing with the raw string would both be wrong.
        assertThat(resolveWithStored("ppob1-webhook-secret")).isEmpty();
    }

    @Test
    void aCiphertextBelongingToAnotherPartnerIsNeverUsedToSign() {
        assertThat(resolveWithStored(ciphertextFor("OTHER", "secret-123"))).isEmpty();
    }

    @Test
    void aCiphertextUnderAKeyThisDeploymentLacksIsNeverUsedToSign() {
        SecretVault foreign = new SecretVault(new KeyringSecretKeyProvider(
                "gone:" + Base64.getEncoder().encodeToString(new byte[32]), "gone"));
        assertThat(resolveWithStored(foreign.encrypt("secret-123", WebhookDeliveryTargetResolver.secretAad("PPOB1")))).isEmpty();
    }
}
