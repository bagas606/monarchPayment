package id.ppob2.app.reconciliation;

import id.ppob2.order.ChildOrderService;
import id.ppob2.order.DecompositionExhaustedEvent;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 38.1's "Order vs Fulfillment" reconciliation type: "Parent/child order final states
 * vs expected all-success criterion" — "Detect silent partial failures." Lives in `app` because
 * Section 20.2 grants `reconciliation` no edge to `order`, so this is the same composition-root
 * shape as {@code SettlementIngestionOrchestrator}, except this one must run {@code REQUIRES_NEW}
 * rather than in one synchronous transaction: it's called from {@code FulfillmentDispatchListener}
 * (an {@code AFTER_COMMIT} listener's call tree), not from a request thread.
 *
 * <p><b>Two triggers, not one.</b> {@link #reconcile} is called from {@code
 * FulfillmentDispatchListener} and measures a shortfall against the child orders that ran.
 * {@link #onDecompositionExhausted} covers the paid order that never produced a child order at all
 * (BR-DEC exhaustion → {@code REFUND_PENDING}), which the dispatch path structurally cannot see.
 * Both write the same type with the same {@code reference_id} convention and share its
 * {@code hasOpenDiscrepancy} guard.
 *
 * <p><b>Value definition</b>: {@code expectedValue} is {@code parent_order.parent_amount} — the
 * BR-DEC invariant (Section 28.2) guarantees this equals the sum of every child order's
 * contribution. {@code actualValue} is the sum of {@code ChildOrder.faceValue} over only the
 * children that reached {@code SUCCESS}; a {@code FAILED} or unresolvable child contributes 0.
 * Only opened when this leaves a non-zero shortfall (i.e., the order did not reach {@code
 * SUCCESS}) — a fully successful order has nothing to reconcile, same treatment as settlement's
 * {@code MATCHED} status never opening a record.
 *
 * <p><b>Stuck orders are skipped, not reconciled against.</b> If {@code completeFulfillment} left
 * the order at {@code FULFILLING} (its stuck-order guard — some child order still {@code
 * PENDING}/{@code EXECUTING}), the numbers here would be mid-flight and meaningless; this logs and
 * returns, the same treatment {@code ParentOrderTransitionService.expirePaymentPending} gives an
 * order in an unexpected state, rather than opening a reconciliation record for a fulfillment run
 * that hasn't actually finished.
 *
 * <p><b>Idempotent per parent order</b>: checks {@code ReconciliationService.hasOpenDiscrepancy}
 * first, so an authorized Section 34.1 retry re-entering this same dispatch path for a parent
 * order that already has an OPEN/INVESTIGATING record doesn't open a second one. (No DB-level
 * partial-unique-index backstop yet, unlike {@code provider_price_active_idx} — flagged in the
 * README; this path has no concurrent-writer risk today since fulfillment dispatch for a given
 * parent order isn't parallelized.)
 */
@Component
public class OrderFulfillmentReconciliationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentReconciliationOrchestrator.class);

    private final ParentOrderRepository parentOrderRepository;
    private final ChildOrderService childOrderService;
    private final ReconciliationService reconciliationService;

    public OrderFulfillmentReconciliationOrchestrator(ParentOrderRepository parentOrderRepository,
                                                        ChildOrderService childOrderService,
                                                        ReconciliationService reconciliationService) {
        this.parentOrderRepository = parentOrderRepository;
        this.childOrderService = childOrderService;
        this.reconciliationService = reconciliationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(Long parentOrderId) {
        ParentOrder order = parentOrderRepository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for reconciliation"));

        if (order.getState() == OrderState.FULFILLING) {
            log.warn("parent_order {} still FULFILLING after dispatch returned (stuck-order signal already logged by "
                    + "completeFulfillment) — skipping ORDER_VS_FULFILLMENT reconciliation rather than comparing mid-flight numbers",
                    parentOrderId);
            return;
        }

        if (order.getState() != OrderState.PARTIAL_FAILED && order.getState() != OrderState.FAILED) {
            // Covers SUCCESS (nothing to reconcile) and any other terminal/non-terminal state
            // this dispatch-driven path shouldn't act on. REFUND_PENDING is genuinely unreachable
            // *here* — ChildOrdersReadyEvent never fires on the no-pattern path — which is exactly
            // why it needs its own trigger rather than being covered by silence; see
            // #onDecompositionExhausted below.
            return;
        }

        if (reconciliationService.hasOpenDiscrepancy(ReconciliationType.ORDER_VS_FULFILLMENT, parentOrderId)) {
            log.info("parent_order {} already has an OPEN/INVESTIGATING ORDER_VS_FULFILLMENT record — skipping", parentOrderId);
            return;
        }

        List<ChildOrder> childOrders = childOrderService.findByParentOrderId(parentOrderId);
        Money actualValue = childOrders.stream()
                .filter(c -> c.getState() == ChildOrderState.SUCCESS)
                .map(ChildOrder::getFaceValue)
                .reduce(Money.ZERO, Money::add);

        reconciliationService.open(ReconciliationType.ORDER_VS_FULFILLMENT, LocalDate.now(), parentOrderId,
                order.getParentAmount(), actualValue);
    }

    /**
     * BR-DEC exhaustion: a paid order found no eligible pattern, so it went straight to
     * {@code REFUND_PENDING} without ever producing a child order. {@link #reconcile} cannot cover
     * this — it hangs off {@code ChildOrdersReadyEvent}, which never fires on that path — so this
     * is the trigger for it.
     *
     * <p>{@code expected} is {@code parent_amount} (what the customer paid for) and {@code actual}
     * is {@link Money#ZERO} (nothing was fulfilled), giving a {@code -parent_amount} discrepancy:
     * the same values, type and {@code reference_id} convention {@code
     * PaymentCallbackAnomalyOrchestrator#onLatePaymentOnUntransitionableOrder} uses for the other
     * funds-collected-nothing-delivered route, so both read identically in Admin Web.
     *
     * <p>Plain {@code @EventListener} + {@code REQUIRES_NEW}, matching that sibling listener
     * exactly: the publisher ({@code ParentOrderTransitionService#selectPatternOrRefund}) is itself
     * {@code REQUIRES_NEW} off an {@code AFTER_COMMIT} callback, so a live transaction exists to
     * join, and this record stays in its own so it survives independently of whatever else that
     * transaction does. An open record against an order still showing {@code PAID} (if the outer
     * transaction were to roll back) is the safe direction to fail: the money was collected either
     * way, and the whole point of this row is that a human sees that.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDecompositionExhausted(DecompositionExhaustedEvent event) {
        if (reconciliationService.hasOpenDiscrepancy(ReconciliationType.ORDER_VS_FULFILLMENT, event.parentOrderId())) {
            log.info("parent_order {} already has an OPEN/INVESTIGATING ORDER_VS_FULFILLMENT record — skipping "
                    + "the BR-DEC exhaustion record", event.parentOrderId());
            return;
        }
        reconciliationService.open(ReconciliationType.ORDER_VS_FULFILLMENT, LocalDate.now(), event.parentOrderId(),
                event.parentAmount(), Money.ZERO);
        log.warn("Opened ORDER_VS_FULFILLMENT discrepancy for parent_order {}: paid {} but no eligible "
                        + "decomposition pattern — funds collected, nothing fulfilled, refund owed, needs manual review",
                event.parentOrderId(), event.parentAmount());
    }
}
