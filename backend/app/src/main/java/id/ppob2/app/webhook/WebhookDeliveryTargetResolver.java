package id.ppob2.app.webhook;

import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated into its own bean — not a private/self-invoked method on {@link
 * OutboundWebhookOrchestrator} — for the same proxy-bypass reason {@code ProviderTransactionRecorder}
 * is separate from {@code FulfillmentExecutionService}: a self-invoked {@code @Transactional}
 * method silently ignores the annotation, since Spring's AOP proxy is never re-entered.
 *
 * <p>{@code REQUIRES_NEW} here is not just "the usual AFTER_COMMIT rule" — it is load-bearing for
 * correctness, not just convention. A real run found a plain (non-{@code REQUIRES_NEW}) read here
 * returning {@code state=FULFILLING} moments after this codebase's own log line proved the
 * terminal-state transition had already committed {@code PARTIAL_FAILED} in its own separate
 * {@code REQUIRES_NEW} transaction. {@code REQUIRES_NEW} fixed it, confirmed by a follow-up run.
 * The exact mechanism is not pinned down — {@code open-in-view} is explicitly {@code false} in
 * this app's config, which rules out the obvious Open-Session-In-View explanation — so treat this
 * as an empirically-verified fix, not a fully explained one; see the README for the full account.
 */
@Component
public class WebhookDeliveryTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryTargetResolver.class);

    private final ParentOrderRepository parentOrderRepository;
    private final PartnerRepository partnerRepository;

    public WebhookDeliveryTargetResolver(ParentOrderRepository parentOrderRepository, PartnerRepository partnerRepository) {
        this.parentOrderRepository = parentOrderRepository;
        this.partnerRepository = partnerRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<WebhookDeliveryTarget> resolve(Long parentOrderId) {
        ParentOrder order = parentOrderRepository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for webhook delivery"));

        if (order.getState() != OrderState.SUCCESS && order.getState() != OrderState.PARTIAL_FAILED
                && order.getState() != OrderState.FAILED) {
            return Optional.empty();
        }

        String callbackUrl = order.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Optional.empty();
        }

        Partner partner = order.getPartnerId() != null ? partnerRepository.findById(order.getPartnerId()).orElse(null) : null;
        if (partner == null || partner.getWebhookSecret() == null || partner.getWebhookSecret().isBlank()) {
            log.error("parent_order {} has callback_url set but no resolvable partner.webhook_secret "
                    + "(partner_id={}) — cannot sign an outbound request, skipping delivery rather than sending unsigned",
                    parentOrderId, order.getPartnerId());
            return Optional.empty();
        }

        return Optional.of(new WebhookDeliveryTarget(callbackUrl, partner.getWebhookSecret(), partner.getCode(), order.getOrderNo(), order.getState().name()));
    }
}
