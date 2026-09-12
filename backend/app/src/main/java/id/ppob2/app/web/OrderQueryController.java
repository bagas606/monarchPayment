package id.ppob2.app.web;

import id.ppob2.order.CancelOrderResult;
import id.ppob2.order.OrderDetailResult;
import id.ppob2.order.OrderQueryService;
import id.ppob2.order.ParentOrderTransitionService;
import id.ppob2.order.PaymentDetailResult;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.channel.ChannelContextHolder;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Sections 23.5-23.7: {@code GET /api/v1/orders/{order_id}}, {@code
 * .../orders/{order_id}/payment}, {@code POST .../orders/{order_id}/cancel}. Resolves the
 * caller's numeric {@code partner_id} the same way {@link CreateOrderController} does — `order`
 * has no edge to `partner` (Section 20.2), so that join lives here, at the composition root.
 *
 * <p>Every lookup is scoped to the resolved {@code partner_id}: a mismatch (order exists but
 * belongs to a different partner) is reported identically to a genuinely unknown {@code
 * order_id} — {@code ORDER_NOT_FOUND} (404) — never a distinguishing 403, so one partner cannot
 * probe for another partner's order numbers.
 */
@RestController
public class OrderQueryController {

    private final PartnerRepository partnerRepository;
    private final OrderQueryService orderQueryService;
    private final ParentOrderTransitionService transitionService;

    public OrderQueryController(PartnerRepository partnerRepository,
                                 OrderQueryService orderQueryService,
                                 ParentOrderTransitionService transitionService) {
        this.partnerRepository = partnerRepository;
        this.orderQueryService = orderQueryService;
        this.transitionService = transitionService;
    }

    @GetMapping("/api/v1/orders/{order_id}")
    public ResponseEntity<OrderDetailResponse> getOrder(@PathVariable("order_id") String orderId) {
        Long partnerId = resolveCallerPartnerId();
        OrderDetailResult result = orderQueryService.getOrderDetail(partnerId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Unknown order_id: " + orderId));

        return ResponseEntity.ok(new OrderDetailResponse(
                result.orderNo(),
                result.state().name(),
                result.parentAmount(),
                result.paymentStatus() != null ? result.paymentStatus().name() : null,
                new FulfillmentSummary(result.totalChild(), result.successChild(), result.failedChild()),
                result.updatedAt()));
    }

    @GetMapping("/api/v1/orders/{order_id}/payment")
    public ResponseEntity<PaymentDetailResponse> getPayment(@PathVariable("order_id") String orderId) {
        Long partnerId = resolveCallerPartnerId();
        PaymentDetailResult result = orderQueryService.getPaymentDetail(partnerId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Unknown order_id: " + orderId));

        return ResponseEntity.ok(new PaymentDetailResponse(
                result.orderNo(), result.status() != null ? result.status().name() : null,
                result.paidAt(), result.pgReference()));
    }

    @PostMapping("/api/v1/orders/{order_id}/cancel")
    public ResponseEntity<CancelOrderResponse> cancelOrder(@PathVariable("order_id") String orderId) {
        Long partnerId = resolveCallerPartnerId();
        Long parentOrderId = orderQueryService.resolveOwnedOrderId(partnerId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Unknown order_id: " + orderId));

        CancelOrderResult result = transitionService.cancel(parentOrderId);
        if (result == CancelOrderResult.NOT_CANCELLABLE) {
            throw new ApiException(ErrorCode.ORDER_NOT_CANCELLABLE, "Order is no longer in a cancellable state.");
        }

        return ResponseEntity.ok(new CancelOrderResponse(orderId, "CANCELLED"));
    }

    private Long resolveCallerPartnerId() {
        ChannelContext channelContext = ChannelContextHolder.get();
        Partner partner = partnerRepository.findByCode(channelContext.partnerId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "Authenticated client resolved to an unknown partner."));
        return partner.getId();
    }

    public record FulfillmentSummary(int totalChild, int success, int failed) {
    }

    public record OrderDetailResponse(
            String orderId,
            String state,
            Money parentAmount,
            String paymentStatus,
            FulfillmentSummary fulfillmentSummary,
            Instant updatedAt
    ) {
    }

    public record PaymentDetailResponse(String orderId, String status, Instant paidAt, String pgReference) {
    }

    public record CancelOrderResponse(String orderId, String state) {
    }
}
