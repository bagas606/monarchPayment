package id.ppob2.app.fulfillment;

import id.ppob2.catalog.domain.ProviderSku;
import id.ppob2.catalog.repository.ProviderSkuRepository;
import id.ppob2.app.reconciliation.MarginReconciliationOrchestrator;
import id.ppob2.app.reconciliation.OrderFulfillmentReconciliationOrchestrator;
import id.ppob2.app.webhook.OutboundWebhookOrchestrator;
import id.ppob2.fulfillment.FulfillmentExecutionService;
import id.ppob2.fulfillment.ResolvedChildOrder;
import id.ppob2.order.ChildOrderRef;
import id.ppob2.order.ChildOrdersReadyEvent;
import id.ppob2.pricing.ProviderPriceService;
import id.ppob2.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Resolves {@code provider_sku_id -> provider_id} and hands dispatch off to `fulfillment` —
 * living here, not in `order` or `fulfillment`, because Section 20.2 grants neither of those
 * modules a `catalog` edge, and this is the "API / Channel Adapters" / composition-root layer
 * (Section 19) that already does the equivalent join for {@code CreateOrderController}. See
 * {@link ChildOrdersReadyEvent}'s Javadoc in `order` for the full reasoning.
 *
 * <p>{@code AFTER_COMMIT}: must not run until `order`'s child-order rows (and the
 * {@code DECOMPOSITION_SELECTED -> FULFILLING} transition) are durably committed — the same
 * reasoning as every other {@code AFTER_COMMIT} listener in this codebase.
 */
@Component
public class FulfillmentDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentDispatchListener.class);

    private final ProviderSkuRepository providerSkuRepository;
    private final ProviderPriceService providerPriceService;
    private final FulfillmentExecutionService fulfillmentExecutionService;
    private final OrderFulfillmentReconciliationOrchestrator reconciliationOrchestrator;
    private final MarginReconciliationOrchestrator marginReconciliationOrchestrator;
    private final OutboundWebhookOrchestrator outboundWebhookOrchestrator;

    public FulfillmentDispatchListener(ProviderSkuRepository providerSkuRepository,
                                        ProviderPriceService providerPriceService,
                                        FulfillmentExecutionService fulfillmentExecutionService,
                                        OrderFulfillmentReconciliationOrchestrator reconciliationOrchestrator,
                                        MarginReconciliationOrchestrator marginReconciliationOrchestrator,
                                        OutboundWebhookOrchestrator outboundWebhookOrchestrator) {
        this.providerSkuRepository = providerSkuRepository;
        this.providerPriceService = providerPriceService;
        this.fulfillmentExecutionService = fulfillmentExecutionService;
        this.reconciliationOrchestrator = reconciliationOrchestrator;
        this.marginReconciliationOrchestrator = marginReconciliationOrchestrator;
        this.outboundWebhookOrchestrator = outboundWebhookOrchestrator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChildOrdersReady(ChildOrdersReadyEvent event) {
        List<Long> skuIds = event.childOrders().stream().map(ChildOrderRef::providerSkuId).distinct().toList();
        Map<Long, ProviderSku> skuById = providerSkuRepository.findByIdIn(skuIds).stream()
                .collect(Collectors.toMap(ProviderSku::getId, Function.identity()));

        List<ResolvedChildOrder> resolved = new ArrayList<>();
        List<Long> unresolvable = new ArrayList<>();
        for (ChildOrderRef ref : event.childOrders()) {
            ProviderSku sku = skuById.get(ref.providerSkuId());
            if (sku == null) {
                log.error("child_order {} references provider_sku {} which no longer resolves — "
                        + "flagging unresolvable rather than dropping it silently", ref.childOrderId(), ref.providerSkuId());
                unresolvable.add(ref.childOrderId());
                continue;
            }
            Optional<Money> cost = providerPriceService.getActiveCost(ref.providerSkuId());
            if (cost.isEmpty()) {
                log.error("child_order {} references provider_sku {} with no active provider_price — "
                        + "flagging unresolvable rather than dispatching with an unknown cost", ref.childOrderId(), ref.providerSkuId());
                unresolvable.add(ref.childOrderId());
                continue;
            }
            resolved.add(new ResolvedChildOrder(ref.childOrderId(), sku.getProviderId(), ref.providerSkuId(), ref.quantity(), cost.get()));
        }

        fulfillmentExecutionService.dispatch(event.parentOrderId(), resolved, unresolvable);
        reconciliationOrchestrator.reconcile(event.parentOrderId());
        marginReconciliationOrchestrator.reconcile(event.parentOrderId());
        outboundWebhookOrchestrator.sendOrderStatusChanged(event.parentOrderId());
    }
}
