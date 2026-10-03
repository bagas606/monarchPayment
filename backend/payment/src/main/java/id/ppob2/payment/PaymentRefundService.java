package id.ppob2.payment;

import id.ppob2.ledger.LedgerService;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.domain.PaymentEvent;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.payment.gateway.PaymentGateway;
import id.ppob2.payment.gateway.RefundRequest;
import id.ppob2.payment.gateway.RefundResult;
import id.ppob2.payment.repository.PaymentEventRepository;
import id.ppob2.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment half of PRD Section 33.2's {@code REFUND_PENDING -> REFUNDED} ("Refund executed via
 * PaymentGateway. Ledger: reversing entry posted") and FR-PAY-007 ("refund/reversal initiation
 * subject to authorization and PG capability"). The order half, the authorization and the audit
 * row live in `app` — see {@code AdminRefundOrchestrator} — because `payment` has no Section 20.2
 * edge to `order`, `admin` or `audit`.
 *
 * <h2>Two execution modes, chosen by capability and never by assumption</h2>
 * {@link PaymentGateway#supportsRefund()} decides. Section 73.3's open question 5 is still open
 * ("Ayolinx's actual refund capability, window, and process — API-driven vs manual request"), and
 * {@code AyolinxPaymentGateway} answers {@code false} because Ayolinx's public API has no refund
 * endpoint:
 * <ul>
 *   <li><b>Gateway-executed</b> — we call {@link PaymentGateway#refund}, and the PG's own refund
 *       reference is what gets recorded. No {@code externalReference} is required or accepted,
 *       because the PG supplies the authoritative one.</li>
 *   <li><b>Recorded out-of-band</b> — the only mode reachable in production today. A human already
 *       moved the money (bank transfer, PG dashboard) and supplies the reference proving it. This
 *       service records that fact; it does not move money. The {@code externalReference} is
 *       therefore mandatory in this mode, enforced by {@link #refund} rather than left to the
 *       caller — a refund recorded with no evidence of having happened is the one outcome worse
 *       than no refund at all.</li>
 * </ul>
 * Deliberately NOT a silent fallback: if a gateway says it supports refunds and then the call
 * fails, this returns a failure and changes nothing. Degrading to "record it as out-of-band"
 * would book a reversal for money still sitting at the PG.
 *
 * <h2>Transaction shape</h2>
 * {@link #refund} is {@code MANDATORY}, the same choice and for the same reason as
 * {@link LedgerService#post}: the payment's status change and its reversing ledger entry must
 * commit together, and so must the caller's {@code parent_order} transition. A caller with no
 * active transaction fails fast instead of silently getting three independent commits. The
 * gateway HTTP call is made by {@link #executeAtGateway} <em>before</em> that transaction opens
 * (see its Javadoc) — an external call must never hold a pooled connection, the same rule
 * {@code FulfillmentExecutionService} and {@code OutboundWebhookOrchestrator} follow.
 *
 * <h2>Idempotency</h2>
 * Three layers, because this is the one irreversible money movement in the system:
 * {@link Payment#markRefunded} returns false for anything but {@code SUCCESS};
 * {@code payment_event_dedup_uk} rejects a second {@code refund:{paymentId}} row at the database;
 * and the caller's own {@code REFUND_PENDING} state check gates entry. A double-submitted refund
 * therefore cannot post a second ledger DEBIT by any path.
 */
@Service
public class PaymentRefundService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGateway paymentGateway;
    private final LedgerService ledgerService;

    public PaymentRefundService(PaymentRepository paymentRepository,
                                 PaymentEventRepository paymentEventRepository,
                                 PaymentGateway paymentGateway,
                                 LedgerService ledgerService) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.paymentGateway = paymentGateway;
        this.ledgerService = ledgerService;
    }

    public boolean gatewayExecutesRefunds() {
        return paymentGateway.supportsRefund();
    }

    public Optional<Payment> findByParentOrderId(Long parentOrderId) {
        return paymentRepository.findByParentOrderId(parentOrderId);
    }

    /**
     * Calls the PG, outside any transaction. Separate from {@link #refund} so the HTTP round trip
     * does not sit inside the transaction that writes the reversal — and so the caller can abort
     * before touching the database if the PG declines.
     *
     * <p>A {@code false} result from the PG means no money moved, so nothing should be recorded.
     * The caller is expected to stop; this method deliberately has no side effects of its own,
     * which is also why it is safe to retry.
     *
     * @throws UnsupportedOperationException if the gateway does not support refunds — a programmer
     *     error (the caller is required to check {@link #gatewayExecutesRefunds()} first), not a
     *     runtime condition to swallow.
     */
    public RefundResult executeAtGateway(Payment payment, String reason) {
        if (!paymentGateway.supportsRefund()) {
            throw new UnsupportedOperationException(
                    "Gateway does not support refunds; check gatewayExecutesRefunds() before calling this.");
        }
        try {
            return paymentGateway.refund(new RefundRequest(payment.getPgReference(), payment.getAmount(), reason));
        } catch (Exception e) {
            // An exception from the PG is indistinguishable from a decline as far as "did the money
            // move" goes, so it is reported as a failure rather than propagated: the caller's
            // response to both is identical (refuse, record nothing).
            log.error("Gateway refund call failed for payment {} (pg_reference={})",
                    payment.getId(), payment.getPgReference(), e);
            return new RefundResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Records the refund: payment → {@code REFUNDED}, a reversing ledger DEBIT, and a
     * {@code payment_event} row (Section 22.18 lists {@code REFUND} among its {@code event_type}
     * values). Must run inside the caller's transaction — see the class Javadoc.
     *
     * @param refundReference the PG's own reference in gateway-executed mode, or the operator's
     *     evidence that the money moved out-of-band. Required either way.
     * @return false if this payment was not in {@code SUCCESS} (already refunded, or never
     *     collected) and therefore nothing was written.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refund(Long paymentId, String reason, String refundReference, boolean gatewayExecuted) {
        if (refundReference == null || refundReference.isBlank()) {
            throw new IllegalArgumentException(
                    "A refund reference is required: the PG's reference when the gateway executed it, or the "
                            + "operator's external reference when it was performed out-of-band.");
        }

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("payment " + paymentId + " not found for refund"));

        PaymentStatus before = payment.getStatus();
        if (!payment.markRefunded(Instant.now())) {
            log.warn("Refusing to refund payment {}: status is {}, not SUCCESS — no funds to reverse", paymentId, before);
            return false;
        }

        // MANDATORY propagation on LedgerService.post means this shares the caller's transaction:
        // no REFUNDED payment without its reversing entry, and no reversing entry if anything
        // later in the caller's transaction rolls back. The amount is positive and the direction
        // is carried by entry_type (Section 22.21), so the reversal of the confirmation's CREDIT
        // is a DEBIT of the same amount, not a negative CREDIT.
        ledgerService.post(LedgerType.PAYMENT, "PAYMENT", payment.getId(), LedgerEntryType.DEBIT,
                payment.getAmount(), (gatewayExecuted ? "Refund executed at PG" : "Refund recorded out-of-band")
                        + " for parent_order " + payment.getParentOrderId() + " (ref " + refundReference + ")");

        // dedup_key is deterministic per payment, so payment_event_dedup_uk is the database-level
        // guarantee that one payment can carry at most one REFUND event, ever. signatureValid is
        // true because this event is self-originated (an authenticated admin action), not an
        // inbound PG message whose signature could be in question.
        paymentEventRepository.save(new PaymentEvent(payment.getId(), "REFUND",
                refundPayload(reason, refundReference, gatewayExecuted), true,
                "refund:" + payment.getId(), Instant.now()));

        log.warn("Payment {} REFUNDED ({}): amount={} parent_order={} ref={} reason={}",
                payment.getId(), gatewayExecuted ? "gateway-executed" : "recorded out-of-band",
                payment.getAmount(), payment.getParentOrderId(), refundReference, reason);
        return true;
    }

    /** Hand-built rather than via Jackson: `payment` has no ObjectMapper dependency, and this
     * payload has exactly three fields. Values are JSON-escaped because {@code reason} and
     * {@code refundReference} are operator free text. */
    private String refundPayload(String reason, String refundReference, boolean gatewayExecuted) {
        return "{\"mode\":\"" + (gatewayExecuted ? "GATEWAY" : "OUT_OF_BAND")
                + "\",\"refundReference\":\"" + escape(refundReference)
                + "\",\"reason\":\"" + escape(reason) + "\"}";
    }

    private String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
