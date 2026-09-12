package id.ppob2.order;

import id.ppob2.configuration.SupportedAmountQueryService;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.payment.PaymentCreationOutcome;
import id.ppob2.payment.PaymentService;
import id.ppob2.payment.domain.Payment;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * PRD Section 18.1: the single entry point every channel (present or future) must call,
 * parameterized by {@link id.ppob2.sharedkernel.channel.ChannelContext} — never a
 * channel-specific service variant. This slice implements the synchronous portion of Create
 * Order (Section 23.4) only: validation, idempotency, and QR issuance. Section 35.1 draws the
 * sync/async boundary right here — payment confirmation and fulfillment happen later, off the
 * inbound webhook, not in this call.
 */
@Service
public class OrderApplicationService {

    /** Section 25.3: independent ceiling check regardless of the supported-amount table,
     * flagged there as "must be verified against latest QRIS regulation/Ayolinx contract". */
    private static final Money QRIS_CEILING = Money.of(10_000_000L);

    private static final Duration QRIS_EXPIRY = Duration.ofMinutes(15);

    private final SupportedAmountQueryService supportedAmountQueryService;
    private final ParentOrderCreationService creationService;
    private final ParentOrderTransitionService transitionService;
    private final PaymentService paymentService;

    public OrderApplicationService(SupportedAmountQueryService supportedAmountQueryService,
                                    ParentOrderCreationService creationService,
                                    ParentOrderTransitionService transitionService,
                                    PaymentService paymentService) {
        this.supportedAmountQueryService = supportedAmountQueryService;
        this.creationService = creationService;
        this.transitionService = transitionService;
        this.paymentService = paymentService;
    }

    public CreateOrderResult createOrder(CreateOrderCommand command) {
        validateAmount(command.productCategory(), command.parentAmount());

        OrderCreationOutcome outcome = creationService.createOrGetExisting(command);
        ParentOrder order = outcome.order();

        if (!outcome.isNew()) {
            return replayExistingOrder(order);
        }

        Instant expiresAt = order.getCreatedAt().plus(QRIS_EXPIRY);
        PaymentCreationOutcome paymentOutcome = paymentService.createQrisPayment(
                order.getId(), order.getOrderNo(), order.getParentAmount(), expiresAt);

        if (!paymentOutcome.success()) {
            // Section 25.2 "PG unavailable": the CREATED row from ParentOrderCreationService's
            // own transaction has already committed and is left as-is for retry; only the
            // customer-facing request fails.
            throw new ApiException(ErrorCode.PG_UNAVAILABLE,
                    "Unable to create QRIS payment: " + paymentOutcome.failureReason());
        }

        transitionService.markPaymentPending(order.getId(), paymentOutcome.expiresAt());

        return new CreateOrderResult(
                order.getOrderNo(),
                OrderState.PAYMENT_PENDING,
                order.getParentAmount(),
                paymentOutcome.qrPayload(),
                paymentOutcome.expiresAt(),
                order.getCreatedAt());
    }

    private CreateOrderResult replayExistingOrder(ParentOrder order) {
        Payment payment = paymentService.findByParentOrderId(order.getId())
                .orElse(null);
        return new CreateOrderResult(
                order.getOrderNo(),
                order.getState(),
                order.getParentAmount(),
                payment != null ? payment.getQrPayload() : null,
                payment != null ? payment.getExpiresAt() : order.getExpiresAt(),
                order.getCreatedAt());
    }

    private void validateAmount(String productCategory, Money parentAmount) {
        if (parentAmount.isGreaterThan(QRIS_CEILING)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_AMOUNT,
                    "Requested amount exceeds the QRIS transaction ceiling.");
        }
        boolean supported = supportedAmountQueryService.activeAmountsForCategory(productCategory)
                .stream()
                .anyMatch(amount -> amount.equals(parentAmount));
        if (!supported) {
            throw new ApiException(ErrorCode.UNSUPPORTED_AMOUNT,
                    "Requested amount is not in the active supported-amount configuration.");
        }
    }
}
