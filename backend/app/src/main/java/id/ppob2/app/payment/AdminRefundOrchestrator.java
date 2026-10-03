package id.ppob2.app.payment;

import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.payment.PaymentRefundService;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.payment.gateway.RefundResult;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PRD Section 33.2's {@code REFUND_PENDING -> REFUNDED} transition, composed across `order`,
 * `payment` and `ledger` — none of which can see the others' halves (Section 20.2), so this is a
 * composition-root concern, the same shape as {@code AdminChildOrderRetryOrchestrator} next door.
 *
 * <p>Closes go-live blocker 8 as the 2026-10-03 pass recorded it: {@code REFUND_PENDING} was
 * reachable (BR-DEC exhaustion, {@code TC-BE-018}) and nothing could ever move an order out of it.
 * The obligation was recorded on an operator's queue and never discharged.
 *
 * <h2>The ordering is the design</h2>
 * <ol>
 *   <li><b>Validate, before anything external or persistent happens.</b> Order must be
 *       {@code REFUND_PENDING}; payment must exist and be {@code SUCCESS}. Both are rejections
 *       ({@code 409}), not failures, and neither writes anything.</li>
 *   <li><b>Call the PG, outside any transaction</b>, if and only if it says it supports refunds.
 *       A decline aborts here, having written nothing.</li>
 *   <li><b>Write everything in one transaction</b>, delegated to {@link RefundRecorder} — a
 *       separate bean specifically so {@code @Transactional} applies at all; see its Javadoc for
 *       the self-invocation trap this originally fell into. A partial refund record is the one
 *       outcome that would be worse than refusing.</li>
 * </ol>
 *
 * <h2>The window this does not close, stated rather than hidden</h2>
 * In gateway-executed mode the PG call (step 2) commits nothing, and the transaction (step 3) can
 * still fail afterwards — so money can have left the PG while this system shows the order still
 * {@code REFUND_PENDING}. That window cannot be closed without a two-phase protocol the PG does
 * not offer. It is survivable rather than silent: the gateway's refund reference is logged at ERROR
 * by {@code PaymentRefundService}, the reconciliation row stays {@code OPEN}, and re-running the
 * refund is safe — {@code Payment.markRefunded} and {@code payment_event_dedup_uk} both reject a
 * duplicate, so the retry records the refund without double-posting the reversal. Note that today
 * this window is unreachable in production anyway: no real gateway answers
 * {@code supportsRefund() == true}, so every production refund takes the out-of-band path, where
 * the money moved before this method was ever called.
 */
@Component
public class AdminRefundOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AdminRefundOrchestrator.class);

    private final ParentOrderRepository parentOrderRepository;
    private final PaymentRefundService paymentRefundService;
    private final RefundRecorder refundRecorder;

    public AdminRefundOrchestrator(ParentOrderRepository parentOrderRepository,
                                    PaymentRefundService paymentRefundService,
                                    RefundRecorder refundRecorder) {
        this.parentOrderRepository = parentOrderRepository;
        this.paymentRefundService = paymentRefundService;
        this.refundRecorder = refundRecorder;
    }

    /**
     * @param externalReference the operator's evidence that the money moved out-of-band. Required
     *     when the gateway cannot execute refunds, rejected as meaningless when it can (the PG's
     *     own reference is authoritative there) — so the caller cannot pass one and be left
     *     wondering which reference was recorded.
     */
    public RefundOutcome refund(Long parentOrderId, String reason, String externalReference) {
        ParentOrder order = parentOrderRepository.findById(parentOrderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND,
                        "parent_order " + parentOrderId + " not found."));

        if (order.getState() != OrderState.REFUND_PENDING) {
            throw new ApiException(ErrorCode.ORDER_NOT_REFUNDABLE,
                    "parent_order " + parentOrderId + " is " + order.getState()
                            + ", not REFUND_PENDING. Only an order already queued for refund can be refunded; "
                            + "moving a PARTIAL_FAILED/FAILED order into REFUND_PENDING is a separate Ops decision "
                            + "(Section 33.2) that this endpoint deliberately does not make.");
        }

        Payment payment = paymentRefundService.findByParentOrderId(parentOrderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_REFUNDABLE,
                        "parent_order " + parentOrderId + " has no payment row — nothing was ever collected."));

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new ApiException(ErrorCode.ORDER_NOT_REFUNDABLE,
                    "payment " + payment.getId() + " is " + payment.getStatus()
                            + ", not SUCCESS — there are no collected funds to reverse.");
        }

        boolean gatewayExecuted = paymentRefundService.gatewayExecutesRefunds();
        String refundReference;

        if (gatewayExecuted) {
            if (externalReference != null && !externalReference.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "This gateway executes refunds itself, so 'external_reference' must be omitted — the "
                                + "gateway's own refund reference is the authoritative one and is what gets recorded.");
            }
            RefundResult result = paymentRefundService.executeAtGateway(payment, reason);
            if (!result.success()) {
                // Nothing written, nothing moved: the order stays REFUND_PENDING and remains
                // refundable. Surfaced as 502, not 500 — the failure is the PG's, and the operator's
                // next step is to retry or fall back to an out-of-band refund, not to file a bug.
                throw new ApiException(ErrorCode.REFUND_FAILED_AT_GATEWAY,
                        "The payment gateway refused the refund for payment " + payment.getId()
                                + ": " + result.failureReason() + ". The order is unchanged and still refundable.");
            }
            refundReference = result.refundReference();
            if (refundReference == null || refundReference.isBlank()) {
                // A gateway that reports success without a reference leaves nothing to reconcile
                // the refund against later, which defeats the point of recording it.
                throw new ApiException(ErrorCode.REFUND_FAILED_AT_GATEWAY,
                        "The payment gateway reported a successful refund for payment " + payment.getId()
                                + " but returned no refund reference, so it cannot be recorded or reconciled.");
            }
        } else {
            if (externalReference == null || externalReference.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "This gateway cannot execute refunds (Section 73.3's open question 5 — Ayolinx's public API "
                                + "has no refund endpoint), so the refund must be performed out-of-band and a "
                                + "non-blank 'external_reference' supplied as evidence that the money actually moved.");
            }
            refundReference = externalReference.trim();
        }

        return refundRecorder.record(parentOrderId, payment.getId(), reason, refundReference, gatewayExecuted);
    }

    /** Read helper for the controller's before-snapshot, so it does not reach into `payment` itself. */
    public Optional<Payment> findPayment(Long parentOrderId) {
        return paymentRefundService.findByParentOrderId(parentOrderId);
    }
}
