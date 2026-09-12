package id.ppob2.order;

import id.ppob2.decomposition.PatternComponentDto;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.repository.ChildOrderRepository;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns {@code child_order} row lifecycle (Section 22.16) on behalf of both `order` (creation,
 * Section 33.2's {@code DECOMPOSITION_SELECTED -> FULFILLING} trigger) and, indirectly, the
 * `app`-layer fulfillment dispatch listener that drives execution (Section 34) — `fulfillment`
 * calls into this service rather than owning the entity itself, since `order -> fulfillment` has
 * no edge in Section 20.2's graph but `fulfillment -> order` does.
 *
 * <p>{@code markExecuting}/{@code recordOutcome} are {@code REQUIRES_NEW} because every call path
 * into this service from the fulfillment dispatch pipeline originates inside a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} callback
 * ({@code ChildOrdersReadyEvent} in `app`) — this codebase proved twice already (payment
 * confirmation, pattern selection) that a plain {@code @Transactional} anywhere in that call tree
 * silently no-ops instead of writing. Applying {@code REQUIRES_NEW} here from the start avoids
 * rediscovering that bug a third time.
 */
@Service
public class ChildOrderService {

    private static final Logger log = LoggerFactory.getLogger(ChildOrderService.class);

    private final ChildOrderRepository repository;

    public ChildOrderService(ChildOrderRepository repository) {
        this.repository = repository;
    }

    /** Called from within {@code ParentOrderTransitionService.createChildOrdersAndBeginFulfillment}'s
     * own transaction — no separate propagation needed here since it shares that caller's tx. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ChildOrderRef> createForPattern(Long parentOrderId, List<PatternComponentDto> components) {
        int sequence = 1;
        List<ChildOrderRef> refs = new java.util.ArrayList<>();
        for (PatternComponentDto component : components) {
            Money faceValue = component.faceValue().multiply(component.quantity());
            ChildOrder childOrder = new ChildOrder(parentOrderId, component.providerSkuId(), component.quantity(), sequence++, faceValue);
            repository.save(childOrder);
            refs.add(new ChildOrderRef(childOrder.getId(), component.providerSkuId(), component.quantity()));
        }
        return refs;
    }

    public List<ChildOrder> findByParentOrderId(Long parentOrderId) {
        return repository.findByParentOrderIdOrderBySequenceNo(parentOrderId);
    }

    public Optional<ChildOrder> findById(Long childOrderId) {
        return repository.findById(childOrderId);
    }

    /** Admin Web compensating retry (Section 34.1). A separate {@code REQUIRES_NEW} write from
     * the dispatch that follows it, same granularity as every other single-field state change in
     * this service — the caller (an `app`-layer orchestrator) resolves provider/cost and calls
     * {@code FulfillmentExecutionService.dispatch} afterward, itself unchanged. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resetForRetry(Long childOrderId) {
        ChildOrder childOrder = repository.findById(childOrderId)
                .orElseThrow(() -> new IllegalStateException("child_order " + childOrderId + " not found"));
        return childOrder.resetForRetry();
    }

    /** @return the resulting attempt_count, used to build this dispatch attempt's provider-transaction
     * idempotency key — or empty if the child order wasn't PENDING (already executing/terminal). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Integer> markExecuting(Long childOrderId) {
        ChildOrder childOrder = repository.findById(childOrderId)
                .orElseThrow(() -> new IllegalStateException("child_order " + childOrderId + " not found"));
        if (!childOrder.markExecuting()) {
            log.warn("child_order {} was not PENDING (state={}) — skipping duplicate dispatch attempt",
                    childOrderId, childOrder.getState());
            return Optional.empty();
        }
        return Optional.of(childOrder.getAttemptCount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(Long childOrderId, boolean success, Long providerTransactionId) {
        ChildOrder childOrder = repository.findById(childOrderId)
                .orElseThrow(() -> new IllegalStateException("child_order " + childOrderId + " not found"));
        boolean applied = success ? childOrder.markSuccess(providerTransactionId) : childOrder.markFailed(providerTransactionId);
        if (!applied) {
            log.warn("child_order {} was not EXECUTING (state={}) when recording outcome — ignoring", childOrderId, childOrder.getState());
        }
    }

    /** For the `app`-layer dispatch listener's defensive path (Section on resolution failure): a
     * {@code provider_sku} that vanished between pattern selection and dispatch has no provider to
     * call, so this marks the child order FAILED directly without ever reaching EXECUTING/dispatch —
     * still terminal, so {@code ParentOrderTransitionService.completeFulfillment}'s all-terminal
     * invariant isn't left permanently unsatisfied by a stuck PENDING row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnresolvable(Long childOrderId) {
        ChildOrder childOrder = repository.findById(childOrderId)
                .orElseThrow(() -> new IllegalStateException("child_order " + childOrderId + " not found"));
        childOrder.markExecuting();
        boolean applied = childOrder.markFailed(null);
        if (!applied) {
            log.warn("child_order {} could not be marked FAILED for unresolvable dispatch (state={})",
                    childOrderId, childOrder.getState());
        }
    }
}
