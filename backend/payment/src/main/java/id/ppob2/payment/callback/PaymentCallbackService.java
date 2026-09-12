package id.ppob2.payment.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.ledger.LedgerService;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.payment.PaymentConfirmedEvent;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.gateway.PaymentGateway;
import id.ppob2.payment.repository.PaymentRepository;
import id.ppob2.webhook.WebhookEventRecorder;
import id.ppob2.webhook.domain.WebhookDirection;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 25.2 / 48.2 / 48.5: inbound Ayolinx QRIS callback processing. Verifies the
 * signature, logs every attempt to {@code webhook_event} regardless of outcome (Section 22.24
 * has no FK to `payment`, so it can record callbacks that never resolve to a known payment),
 * de-duplicates via {@code payment_event.dedup_key}, and — on a fresh SUCCESS — marks the
 * payment SUCCESS, posts a Payment Ledger entry (Section 33.2's {@code PAYMENT_PENDING -> PAID}
 * "Ledger: payment ledger entry posted" side effect — the only ledger posting point Section 33.2
 * names for the order/payment flow this codebase implements so far; see the README for why an
 * Order Ledger entry is deliberately NOT also posted here), and publishes
 * {@link PaymentConfirmedEvent} for `order` to react to.
 */
@Service
public class PaymentCallbackService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackService.class);
    private static final String SOURCE = "AYOLINX";

    private final PaymentGateway paymentGateway;
    private final PaymentRepository paymentRepository;
    private final PaymentEventDeduplicator paymentEventDeduplicator;
    private final WebhookEventRecorder webhookEventRecorder;
    private final LedgerService ledgerService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public PaymentCallbackService(PaymentGateway paymentGateway,
                                   PaymentRepository paymentRepository,
                                   PaymentEventDeduplicator paymentEventDeduplicator,
                                   WebhookEventRecorder webhookEventRecorder,
                                   LedgerService ledgerService,
                                   ApplicationEventPublisher eventPublisher,
                                   ObjectMapper objectMapper) {
        this.paymentGateway = paymentGateway;
        this.paymentRepository = paymentRepository;
        this.paymentEventDeduplicator = paymentEventDeduplicator;
        this.webhookEventRecorder = webhookEventRecorder;
        this.ledgerService = ledgerService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentCallbackOutcome processCallback(String rawBody, Map<String, String> headers) {
        boolean signatureValid = paymentGateway.verifyCallbackSignature(rawBody, headers);

        if (!signatureValid) {
            webhookEventRecorder.record(WebhookDirection.INBOUND, SOURCE, "CALLBACK", rawBody, "FAILED", null);
            return PaymentCallbackOutcome.SIGNATURE_INVALID;
        }

        AyolinxCallbackPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, AyolinxCallbackPayload.class);
        } catch (Exception e) {
            webhookEventRecorder.record(WebhookDirection.INBOUND, SOURCE, "CALLBACK", rawBody, "FAILED", null);
            return PaymentCallbackOutcome.MALFORMED;
        }

        webhookEventRecorder.record(WebhookDirection.INBOUND, SOURCE, "CALLBACK", rawBody, "RECEIVED", payload.eventId());

        Optional<Payment> maybePayment = paymentRepository.findByPgReference(payload.pgReference());
        if (maybePayment.isEmpty()) {
            log.warn("Ayolinx callback for unknown pg_reference={}", payload.pgReference());
            return PaymentCallbackOutcome.UNKNOWN_REFERENCE;
        }
        Payment payment = maybePayment.get();

        boolean isNewEvent = paymentEventDeduplicator.recordIfNew(payment.getId(), "CALLBACK_RECEIVED", rawBody, payload.eventId());
        if (!isNewEvent) {
            // Section 48.5: the unique constraint on dedup_key IS the idempotency mechanism.
            return PaymentCallbackOutcome.DUPLICATE_IGNORED;
        }

        if (payload.isSuccess()) {
            Instant paidAt = payload.paidAt() != null ? payload.paidAt() : Instant.now();
            if (!payment.markSuccess(paidAt)) {
                // Section 25.2: "out-of-order terminal-state callbacks ... logged and flagged for
                // manual review, never silently overwrite a terminal SUCCESS". No `reconciliation`
                // module exists yet to open a discrepancy record — this log is the interim marker.
                log.warn("Ignoring SUCCESS callback for payment {} already in terminal state {}",
                        payment.getId(), payment.getStatus());
                return PaymentCallbackOutcome.STALE_TERMINAL_STATE;
            }
            // MANDATORY, inside this method's own @Transactional (REQUIRED) — not REQUIRES_NEW.
            // Unlike the AFTER_COMMIT-callback writes elsewhere in this codebase, this must commit
            // atomically WITH payment.markSuccess: no PAID payment without its ledger entry, and
            // no entry if this transaction rolls back for any other reason.
            ledgerService.post(LedgerType.PAYMENT, "PAYMENT", payment.getId(), LedgerEntryType.CREDIT,
                    payment.getAmount(), "QRIS payment confirmed for parent_order " + payment.getParentOrderId());
            eventPublisher.publishEvent(new PaymentConfirmedEvent(payment.getParentOrderId(), paidAt));
            return PaymentCallbackOutcome.PROCESSED;
        }

        if (payload.isFailed()) {
            payment.markFailed();
            // Section 33.2 has no PAYMENT_PENDING -> FAILED order transition for a failed QRIS
            // attempt; the order is left to expire naturally via the (not-yet-built) QR expiry
            // sweep job (Section 48.3) rather than transitioned here.
            return PaymentCallbackOutcome.PROCESSED;
        }

        log.warn("Unrecognized Ayolinx callback status '{}' for pg_reference={}", payload.status(), payload.pgReference());
        return PaymentCallbackOutcome.PROCESSED;
    }
}
