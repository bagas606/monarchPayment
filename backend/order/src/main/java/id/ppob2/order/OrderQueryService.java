package id.ppob2.order;

import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Sections 23.5/23.6: read-only order/payment lookups for the Open API. Every lookup is
 * scoped to the caller's resolved {@code partner_id}, not {@code client_id} — a partner can
 * register multiple {@code api_client} rows (Section 22.3), and scoping on the narrower
 * {@code client_id} would hide a partner's own orders placed through a different client. Returns
 * empty (never throws) on a {@code partner_id} mismatch, exactly like a genuinely unknown
 * {@code order_id} — the controller maps both to {@code ORDER_NOT_FOUND} (404), never a
 * distinguishing 403, so one partner cannot use this endpoint to probe for another partner's
 * order numbers.
 */
@Service
public class OrderQueryService {

    private final ParentOrderRepository parentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final ChildOrderService childOrderService;

    public OrderQueryService(ParentOrderRepository parentOrderRepository,
                              PaymentRepository paymentRepository,
                              ChildOrderService childOrderService) {
        this.parentOrderRepository = parentOrderRepository;
        this.paymentRepository = paymentRepository;
        this.childOrderService = childOrderService;
    }

    @Transactional(readOnly = true)
    public Optional<OrderDetailResult> getOrderDetail(Long callerPartnerId, String orderNo) {
        Optional<ParentOrder> maybeOrder = findOwnedOrder(callerPartnerId, orderNo);
        if (maybeOrder.isEmpty()) {
            return Optional.empty();
        }
        ParentOrder order = maybeOrder.get();

        var paymentStatus = paymentRepository.findByParentOrderId(order.getId())
                .map(Payment::getStatus)
                .orElse(null);

        List<ChildOrder> children = childOrderService.findByParentOrderId(order.getId());
        int successCount = (int) children.stream().filter(c -> c.getState() == ChildOrderState.SUCCESS).count();
        int failedCount = (int) children.stream().filter(c -> c.getState() == ChildOrderState.FAILED).count();

        return Optional.of(new OrderDetailResult(order.getOrderNo(), order.getState(), order.getParentAmount(),
                paymentStatus, children.size(), successCount, failedCount, order.getUpdatedAt()));
    }

    /**
     * Empty only means "no such order for this partner" (true 404 territory) — a {@code CREATED}
     * order that hasn't reached the payment gateway yet (a real, owned order;
     * {@code ParentOrderCreationService} deliberately commits it before that call, so a gateway
     * failure leaves it behind) still returns a result here, with every payment field {@code null}.
     * Collapsing that case into "not found" would be indistinguishable from the cross-partner 404
     * this service exists to produce, which would be actively misleading to the order's own owner.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentDetailResult> getPaymentDetail(Long callerPartnerId, String orderNo) {
        Optional<ParentOrder> maybeOrder = findOwnedOrder(callerPartnerId, orderNo);
        if (maybeOrder.isEmpty()) {
            return Optional.empty();
        }
        ParentOrder order = maybeOrder.get();

        return Optional.of(paymentRepository.findByParentOrderId(order.getId())
                .map(payment -> new PaymentDetailResult(order.getOrderNo(), payment.getStatus(),
                        payment.getPaidAt(), payment.getPgReference()))
                .orElseGet(() -> new PaymentDetailResult(order.getOrderNo(), null, null, null)));
    }

    /** Used by the cancel endpoint (Section 23.7), which needs the DB id — not the full detail
     * projection — after the same ownership check as the two read endpoints above. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveOwnedOrderId(Long callerPartnerId, String orderNo) {
        return findOwnedOrder(callerPartnerId, orderNo).map(ParentOrder::getId);
    }

    private Optional<ParentOrder> findOwnedOrder(Long callerPartnerId, String orderNo) {
        return parentOrderRepository.findByOrderNo(orderNo)
                .filter(order -> order.getPartnerId() != null && order.getPartnerId().equals(callerPartnerId));
    }
}
