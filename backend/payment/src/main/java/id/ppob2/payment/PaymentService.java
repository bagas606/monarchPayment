package id.ppob2.payment;

import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.payment.gateway.PaymentCreateRequest;
import id.ppob2.payment.gateway.PaymentCreateResult;
import id.ppob2.payment.gateway.PaymentGateway;
import id.ppob2.payment.repository.PaymentRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 25.2: called synchronously during order creation, bounded by the gateway's own
 * timeout. A gateway failure here must not roll back the parent order that was already
 * committed by the caller — it is reported back as a {@link PaymentCreationOutcome} rather than
 * thrown, so {@code OrderApplicationService} decides how to respond (Section 25.2 "PG
 * unavailable" leaves the order in {@code CREATED} and surfaces {@code 503 PG_UNAVAILABLE}).
 */
@Service
public class PaymentService {

    private final PaymentGateway paymentGateway;
    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentGateway paymentGateway, PaymentRepository paymentRepository) {
        this.paymentGateway = paymentGateway;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentCreationOutcome createQrisPayment(Long parentOrderId, String orderNo, Money amount, Instant expiresAt) {
        PaymentCreateResult result = paymentGateway.createDynamicQris(new PaymentCreateRequest(orderNo, amount, expiresAt));

        if (!result.success()) {
            return PaymentCreationOutcome.failure(result.failureReason());
        }

        paymentRepository.save(new Payment(
                parentOrderId,
                result.pgReference(),
                result.qrPayload(),
                amount,
                PaymentStatus.PENDING,
                result.expiresAt()));

        return PaymentCreationOutcome.success(result.qrPayload(), result.expiresAt());
    }

    public Optional<Payment> findByParentOrderId(Long parentOrderId) {
        return paymentRepository.findByParentOrderId(parentOrderId);
    }
}
