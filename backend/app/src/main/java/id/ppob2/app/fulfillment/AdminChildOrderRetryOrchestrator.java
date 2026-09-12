package id.ppob2.app.fulfillment;

import id.ppob2.catalog.domain.ProviderSku;
import id.ppob2.catalog.repository.ProviderSkuRepository;
import id.ppob2.fulfillment.FulfillmentExecutionService;
import id.ppob2.fulfillment.ResolvedChildOrder;
import id.ppob2.order.ChildOrderService;
import id.ppob2.order.ParentOrderTransitionService;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.pricing.ProviderPriceService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PRD Section 34.1's Admin Web compensating retry: returns a {@code FAILED} child order to
 * {@code PENDING} and re-dispatches it against its provider — closing the gap flagged since the
 * fulfillment slice ("the idempotency-key scheme supports it... but nothing in this codebase
 * exercises that path against real Postgres yet"). Lives in `app`, same composition-root reasoning
 * as {@link FulfillmentDispatchListener}: resolving {@code provider_sku_id -> provider_id} and the
 * active {@code provider_price} needs `catalog` and `pricing`, neither of which `order` or
 * `fulfillment` may depend on (Section 20.2).
 *
 * <p>Runs synchronously from an admin HTTP request thread, not from an {@code AFTER_COMMIT}
 * callback — {@code FulfillmentExecutionService.dispatch} makes no transactional assumption about
 * its caller, so it's reused here unchanged. Its own {@code completeFulfillment} call inside
 * {@code dispatch} no-ops for this caller (the order is {@code PARTIAL_FAILED}, not {@code
 * FULFILLING}), which is why {@link ParentOrderTransitionService#completeRetry} is called
 * separately afterward — see that method's Javadoc for the {@code PARTIAL_FAILED -> SUCCESS} edge
 * it drives.
 */
@Component
public class AdminChildOrderRetryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AdminChildOrderRetryOrchestrator.class);

    private final ChildOrderService childOrderService;
    private final ParentOrderRepository parentOrderRepository;
    private final ParentOrderTransitionService parentOrderTransitionService;
    private final ProviderSkuRepository providerSkuRepository;
    private final ProviderPriceService providerPriceService;
    private final FulfillmentExecutionService fulfillmentExecutionService;

    public AdminChildOrderRetryOrchestrator(ChildOrderService childOrderService,
                                             ParentOrderRepository parentOrderRepository,
                                             ParentOrderTransitionService parentOrderTransitionService,
                                             ProviderSkuRepository providerSkuRepository,
                                             ProviderPriceService providerPriceService,
                                             FulfillmentExecutionService fulfillmentExecutionService) {
        this.childOrderService = childOrderService;
        this.parentOrderRepository = parentOrderRepository;
        this.parentOrderTransitionService = parentOrderTransitionService;
        this.providerSkuRepository = providerSkuRepository;
        this.providerPriceService = providerPriceService;
        this.fulfillmentExecutionService = fulfillmentExecutionService;
    }

    public RetryOutcome retry(Long childOrderId) {
        ChildOrder childOrder = childOrderService.findById(childOrderId)
                .orElseThrow(() -> new IllegalStateException(
                        "child_order " + childOrderId + " vanished between controller lookup and retry"));

        if (childOrder.getState() != ChildOrderState.FAILED) {
            throw new ApiException(ErrorCode.CHILD_ORDER_NOT_RETRYABLE,
                    "child_order " + childOrderId + " is " + childOrder.getState() + ", not FAILED — nothing to retry.");
        }

        Long parentOrderId = childOrder.getParentOrderId();
        ParentOrder parentOrder = parentOrderRepository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException(
                        "parent_order " + parentOrderId + " not found for child_order " + childOrderId));

        if (parentOrder.getState() != OrderState.PARTIAL_FAILED) {
            throw new ApiException(ErrorCode.CHILD_ORDER_NOT_RETRYABLE,
                    "parent_order " + parentOrder.getOrderNo() + " is " + parentOrder.getState()
                            + ", not PARTIAL_FAILED — Section 34.1's manual retry only applies there.");
        }

        boolean reset = childOrderService.resetForRetry(childOrderId);
        if (!reset) {
            throw new ApiException(ErrorCode.CHILD_ORDER_NOT_RETRYABLE,
                    "child_order " + childOrderId + " was no longer FAILED when the retry attempt started "
                            + "(lost a race with a concurrent state change).");
        }

        Optional<ProviderSku> sku = providerSkuRepository.findById(childOrder.getProviderSkuId());
        Optional<Money> cost = sku.flatMap(s -> providerPriceService.getActiveCost(childOrder.getProviderSkuId()));

        if (sku.isPresent() && cost.isPresent()) {
            ResolvedChildOrder resolved = new ResolvedChildOrder(childOrderId, sku.get().getProviderId(),
                    childOrder.getProviderSkuId(), childOrder.getQuantity(), cost.get());
            fulfillmentExecutionService.dispatch(parentOrderId, List.of(resolved), List.of());
        } else {
            log.error("child_order {} references provider_sku {} with no resolvable provider/active price at "
                    + "retry time — marking unresolvable without calling any provider, same as initial dispatch",
                    childOrderId, childOrder.getProviderSkuId());
            fulfillmentExecutionService.dispatch(parentOrderId, List.of(), List.of(childOrderId));
        }

        parentOrderTransitionService.completeRetry(parentOrderId);

        ChildOrder after = childOrderService.findById(childOrderId).orElseThrow();
        ParentOrder parentAfter = parentOrderRepository.findById(parentOrderId).orElseThrow();
        return new RetryOutcome(childOrderId, after.getState(), parentOrderId, parentAfter.getState());
    }
}
