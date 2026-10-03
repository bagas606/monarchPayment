package id.ppob2.app.payment;

import id.ppob2.order.ParentOrderTransitionService;
import id.ppob2.order.domain.OrderState;
import id.ppob2.payment.PaymentRefundService;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database effect of a refund, in exactly one transaction: payment → {@code REFUNDED}, the
 * reversing ledger DEBIT, the {@code payment_event} row, and the order's
 * {@code REFUND_PENDING -> REFUNDED} transition.
 *
 * <h2>Why this is its own bean and not a method on {@link AdminRefundOrchestrator}</h2>
 * It was written there first, as a {@code @Transactional protected} method the orchestrator called
 * as {@code this.commit(...)}. That does not work, and it fails in the worst possible way: Spring's
 * transaction advice lives on a proxy, so a self-invocation inside the same object bypasses it
 * entirely. The code would compile, a mocked-repository test would pass, and at runtime each write
 * would commit in its own transaction — exactly the partial-refund record the orchestrator's
 * ordering exists to prevent, with no error anywhere to show it. Crossing a bean boundary is what
 * makes {@code @Transactional} actually apply.
 *
 * <p>The same trap, from the other direction, is documented on
 * {@code ParentOrderTransitionService.markPaid}: a write under the wrong propagation that "silently
 * never reached the database". Transaction configuration in this codebase is load-bearing and has
 * been gotten wrong before; it is not decoration.
 *
 * <p>{@link PaymentRefundService#refund} is {@code MANDATORY} and
 * {@link ParentOrderTransitionService#markRefunded} is {@code REQUIRED}, so both join this
 * method's transaction rather than opening their own.
 */
@Component
public class RefundRecorder {

    private static final Logger log = LoggerFactory.getLogger(RefundRecorder.class);

    private final PaymentRefundService paymentRefundService;
    private final ParentOrderTransitionService transitionService;

    public RefundRecorder(PaymentRefundService paymentRefundService,
                           ParentOrderTransitionService transitionService) {
        this.paymentRefundService = paymentRefundService;
        this.transitionService = transitionService;
    }

    @Transactional
    public RefundOutcome record(Long parentOrderId, Long paymentId, String reason,
                                 String refundReference, boolean gatewayExecuted) {
        if (!paymentRefundService.refund(paymentId, reason, refundReference, gatewayExecuted)) {
            // Lost a race with a concurrent refund of the same order, between the orchestrator's
            // validation and here. 409 rather than 500 — the end state is correct, just not ours
            // to claim, and nothing further was written.
            throw new ApiException(ErrorCode.ORDER_NOT_REFUNDABLE,
                    "payment " + paymentId + " was already refunded concurrently; nothing further was recorded.");
        }
        if (!transitionService.markRefunded(parentOrderId)) {
            // Unreachable in practice (the state was checked upstream and this is one transaction),
            // but throwing rather than returning rolls the ledger reversal back with it. A refunded
            // payment whose order never left REFUND_PENDING would be a worse record than an
            // outright failure.
            throw new ApiException(ErrorCode.ORDER_NOT_REFUNDABLE,
                    "parent_order " + parentOrderId + " left REFUND_PENDING concurrently; the refund was rolled back.");
        }

        log.warn("Refund completed for parent_order {} (payment {}, {}): ref={}",
                parentOrderId, paymentId, gatewayExecuted ? "gateway-executed" : "recorded out-of-band", refundReference);

        return new RefundOutcome(parentOrderId, paymentId, OrderState.REFUNDED, PaymentStatus.REFUNDED,
                refundReference, gatewayExecuted);
    }
}
