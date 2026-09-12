package id.ppob2.order;

import id.ppob2.decomposition.PatternComponentDto;
import id.ppob2.decomposition.PatternComponentQueryService;
import id.ppob2.decomposition.PatternSelectionOrchestrator;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.OrderStateMachine;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns Section 33.2's order state transitions from {@code PAYMENT_PENDING} onward, including
 * delegating to `decomposition` for pattern selection once an order is {@code PAID} — a
 * permitted dependency per Section 20.2 ({@code order -> decomposition}), not a layering
 * violation. {@code markPaymentPending} runs as a separate transactional boundary from {@link
 * ParentOrderCreationService} on purpose — see that class's Javadoc for why order creation and
 * the payment-pending transition must not share a transaction. */
@Service
public class ParentOrderTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ParentOrderTransitionService.class);

    private final ParentOrderRepository repository;
    private final PatternSelectionOrchestrator patternSelectionOrchestrator;
    private final PatternComponentQueryService patternComponentQueryService;
    private final ChildOrderService childOrderService;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ParentOrderTransitionService(ParentOrderRepository repository,
                                         PatternSelectionOrchestrator patternSelectionOrchestrator,
                                         PatternComponentQueryService patternComponentQueryService,
                                         ChildOrderService childOrderService,
                                         PaymentRepository paymentRepository,
                                         ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.paymentRepository = paymentRepository;
        this.patternSelectionOrchestrator = patternSelectionOrchestrator;
        this.patternComponentQueryService = patternComponentQueryService;
        this.childOrderService = childOrderService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * PRD Section 48.3 / 33.2: {@code PAYMENT_PENDING -> EXPIRED} ("QR TTL elapsed, no payment
     * received"). Called per-order from {@code QrExpirySweepJob}'s scheduled tick, never from an
     * {@code AFTER_COMMIT} callback, so plain {@code @Transactional} (REQUIRED) is correct here —
     * unlike {@link #markPaid}/{@link #selectPatternOrRefund}, this is a fresh top-level call.
     *
     * <p>Guards on {@code order.getState() != PAYMENT_PENDING} before touching anything, which is
     * what prevents expiring an order that got paid in the narrow window between the sweep's
     * query and this transition running — the one failure mode here that would actually lose a
     * customer's money if missed. Also expires the linked {@code payment} row (Section 22.17 has
     * an {@code EXPIRED} status; leaving it {@code PENDING} forever would make {@code GET
     * /orders/{id}/payment} and the Payment-vs-PG/Payment-vs-Settlement reconciliation types see a
     * stale row for an order that's actually terminal) — `order` already depends on `payment`
     * (Section 20.2), so this is a direct call, not a composition-root concern.
     */
    @Transactional
    public boolean expirePaymentPending(Long parentOrderId) {
        ParentOrder order = repository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for expiry sweep"));

        if (order.getState() != OrderState.PAYMENT_PENDING) {
            log.warn("Skipping expiry for parent_order {}: expected PAYMENT_PENDING but state is {} "
                            + "(paid or otherwise resolved after the sweep query ran)",
                    parentOrderId, order.getState());
            return false;
        }

        order.transitionTo(OrderState.EXPIRED);

        paymentRepository.findByParentOrderId(parentOrderId).ifPresentOrElse(payment -> {
            if (!payment.markExpired()) {
                log.warn("payment for parent_order {} was not PENDING (status={}) when expiring the order — leaving it as-is",
                        parentOrderId, payment.getStatus());
            }
        }, () -> log.error("parent_order {} had no payment row at expiry time — Section 22.17 expects one for every PAYMENT_PENDING order", parentOrderId));

        return true;
    }

    @Transactional
    public void markPaymentPending(Long parentOrderId, Instant expiresAt) {
        ParentOrder order = repository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " vanished mid-creation"));
        order.transitionTo(OrderState.PAYMENT_PENDING);
        order.setExpiresAt(expiresAt);
    }

    /**
     * Reacts to {@code payment.PaymentConfirmedEvent} (Section 33.2's {@code PAYMENT_PENDING ->
     * PAID} trigger). Unlike {@link #markPaymentPending}, an invalid transition here is not a
     * programmer error to throw on — it is Section 25.2's "late callback" case (the order already
     * moved to {@code EXPIRED}/{@code CANCELLED} before payment confirmation arrived). The PRD
     * response there is "do NOT auto-fulfill; create a reconciliation discrepancy record", which
     * needs the not-yet-built `reconciliation` module; this logs the case as the interim marker
     * rather than silently succeeding or throwing into the event-listener call stack.
     *
     * <p>{@code REQUIRES_NEW} is load-bearing, not defensive — verified both ways against real
     * Postgres, not just reasoned about. This method is invoked from a
     * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} callback. With plain
     * {@code @Transactional} (REQUIRED), an end-to-end run of create-order → SUCCESS callback
     * reproduced the exact original bug: no exception, no warning log, {@code canTransition}
     * silently never even logged as false — the write simply never reached the database and the
     * order stayed {@code PAYMENT_PENDING}. Restoring {@code REQUIRES_NEW} and re-running the
     * identical scenario reached {@code PAID}. (A prior theory attributed the symptom entirely to
     * an unrelated, now-fixed bug in the dedup path — that theory did not survive this direct
     * re-test with the dedup bug already fixed, so trust the transcript over the theory.) Do not
     * downgrade this to plain {@code @Transactional} without re-running that same check —
     * REQUIRES_NEW does cost a second Hikari connection per in-flight callback, which matters
     * under burst load, but the alternative is silently losing payment confirmations.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPaid(Long parentOrderId) {
        ParentOrder order = repository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for payment confirmation"));

        if (!OrderStateMachine.canTransition(order.getState(), OrderState.PAID)) {
            log.warn("Late payment confirmation for parent_order {} in non-transitionable state {} — "
                            + "Section 25.2 late-callback case, needs a reconciliation record once that module exists",
                    parentOrderId, order.getState());
            return false;
        }
        order.transitionTo(OrderState.PAID);
        return true;
    }

    /**
     * PRD Section 33.2: {@code PAID -> DECOMPOSITION_SELECTED} ("Routing module selects eligible
     * pattern") or, on BR-DEC exhaustion (no eligible pattern), {@code PAID -> REFUND_PENDING}.
     *
     * <p>{@code REQUIRES_NEW} for the same reason as {@link #markPaid}, and this one was not
     * merely reasoned about — it was gotten wrong first. This method is called directly from
     * {@link PaymentConfirmedEventListener#onPaymentConfirmed}, i.e. from *inside* the same
     * {@code @TransactionalEventListener(AFTER_COMMIT)} callback invocation as {@code markPaid},
     * not from some later, ordinary call. An initial version of this Javadoc claimed being the
     * "second call in the method" made it an "ordinary top-level transaction" — it does not;
     * being inside that callback at all is what matters, regardless of call order. Plain
     * {@code @Transactional} here failed with {@code TransactionRequiredException} on the
     * `@Modifying` usage-tracking upserts (Section 32) the first time this was run end-to-end
     * against Postgres — a mocked test would not have caught it, same as {@code markPaid}.
     *
     * <p>Deliberately stops at {@code DECOMPOSITION_SELECTED} and does not also create child
     * orders / advance to {@code FULFILLING} in the same transaction, even though both would
     * happen back-to-back in the happy path — Section 33.2 gives them distinct triggers
     * ("Routing module selects eligible pattern" vs. "Child orders created & dispatch begins"),
     * and collapsing them would mean a failure while creating child orders rolls this transaction
     * back to {@code PAID} instead of leaving the order observably at {@code DECOMPOSITION_SELECTED}
     * — losing the ability to tell "routing never found a pattern" apart from "routing succeeded
     * but dispatch setup failed". See {@link #createChildOrdersAndBeginFulfillment} for the
     * second half.
     *
     * @return true if a pattern was selected (state is now {@code DECOMPOSITION_SELECTED}), false
     * if it wasn't (state is now {@code REFUND_PENDING}) or the order wasn't eligible to begin with.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean selectPatternOrRefund(Long parentOrderId) {
        ParentOrder order = repository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for pattern selection"));

        if (order.getState() != OrderState.PAID) {
            log.warn("Skipping pattern selection for parent_order {}: expected PAID but state is {}",
                    parentOrderId, order.getState());
            return false;
        }

        Optional<Long> patternId = patternSelectionOrchestrator.selectPattern(order.getProductId(), order.getParentAmount());
        if (patternId.isPresent()) {
            order.setPatternId(patternId.get());
            order.transitionTo(OrderState.DECOMPOSITION_SELECTED);
            return true;
        } else {
            log.warn("No eligible decomposition pattern for parent_order {} (product={}, amount={}) — BR-DEC exhaustion, routing to REFUND_PENDING",
                    parentOrderId, order.getProductId(), order.getParentAmount());
            order.transitionTo(OrderState.REFUND_PENDING);
            return false;
        }
    }

    /**
     * PRD Section 33.2: {@code DECOMPOSITION_SELECTED -> FULFILLING} ("Child orders created &
     * dispatch begins"). Expands the already-selected pattern's components (via `decomposition`,
     * a permitted dependency) into {@code child_order} rows, then publishes {@link
     * ChildOrdersReadyEvent} — `order` has no edge to `catalog` or `fulfillment`, so resolving
     * {@code provider_sku_id -> provider_id} and actually dispatching happens in `app`, which
     * depends on both (see that listener's Javadoc).
     *
     * <p>{@code REQUIRES_NEW} for the same proven reason as {@link #markPaid}/{@link
     * #selectPatternOrRefund}: called from {@link PaymentConfirmedEventListener}, inside the same
     * {@code AFTER_COMMIT} callback invocation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createChildOrdersAndBeginFulfillment(Long parentOrderId) {
        ParentOrder order = repository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for fulfillment dispatch"));

        if (order.getState() != OrderState.DECOMPOSITION_SELECTED) {
            log.warn("Skipping child-order creation for parent_order {}: expected DECOMPOSITION_SELECTED but state is {}",
                    parentOrderId, order.getState());
            return;
        }

        List<PatternComponentDto> components = patternComponentQueryService.getComponents(order.getPatternId());
        if (components.isEmpty()) {
            log.error("Pattern {} selected for parent_order {} has no components at dispatch time — "
                            + "cannot fulfill; leaving order at DECOMPOSITION_SELECTED for manual investigation",
                    order.getPatternId(), parentOrderId);
            return;
        }

        List<ChildOrderRef> childOrders = childOrderService.createForPattern(parentOrderId, components);
        order.transitionTo(OrderState.FULFILLING);
        eventPublisher.publishEvent(new ChildOrdersReadyEvent(parentOrderId, childOrders));
    }

    /**
     * PRD Section 33.2/34.1: {@code FULFILLING -> SUCCESS}/{@code PARTIAL_FAILED}/{@code FAILED},
     * gated on the child-count invariant {@link id.ppob2.order.domain.OrderStateMachine}'s NOTE
     * flags as unenforceable by the state table alone ("parent SUCCESS requires ALL child orders
     * SUCCESS", Section 34.1's BR-ORD business-success criterion). Called once per parent order
     * after every child order dispatch has reached a terminal state — see
     * {@code FulfillmentExecutionService.dispatch} in `fulfillment`.
     *
     * <p>{@code REQUIRES_NEW} for the same reason as every other method in this class reached
     * from the fulfillment dispatch pipeline, which itself runs from inside an {@code
     * AFTER_COMMIT} callback ({@code ChildOrdersReadyEvent}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeFulfillment(Long parentOrderId) {
        ParentOrder order = repository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for fulfillment completion"));

        if (order.getState() != OrderState.FULFILLING) {
            log.warn("Skipping fulfillment completion for parent_order {}: expected FULFILLING but state is {}",
                    parentOrderId, order.getState());
            return;
        }

        List<ChildOrder> childOrders = childOrderService.findByParentOrderId(parentOrderId);
        long pending = childOrders.stream().filter(c -> c.getState() == ChildOrderState.PENDING || c.getState() == ChildOrderState.EXECUTING).count();
        if (pending > 0) {
            log.error("parent_order {} has {} child order(s) still PENDING/EXECUTING when completion was requested — "
                            + "this is a stuck-order signal (a dispatch call was dropped or never returned), not a benign race; "
                            + "leaving the order at FULFILLING rather than guessing a terminal state",
                    parentOrderId, pending);
            return;
        }

        long successCount = childOrders.stream().filter(c -> c.getState() == ChildOrderState.SUCCESS).count();
        if (successCount == childOrders.size()) {
            order.transitionTo(OrderState.SUCCESS);
        } else if (successCount > 0) {
            log.warn("parent_order {} PARTIAL_FAILED: {}/{} child orders succeeded", parentOrderId, successCount, childOrders.size());
            order.transitionTo(OrderState.PARTIAL_FAILED);
        } else {
            log.warn("parent_order {} FAILED: all {} child orders failed", parentOrderId, childOrders.size());
            order.transitionTo(OrderState.FAILED);
        }
    }
}
