package id.ppob2.payment.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.ledger.LedgerService;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.payment.PaymentCallbackAnomalyEvent;
import id.ppob2.payment.PaymentConfirmedEvent;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.payment.gateway.PaymentGateway;
import id.ppob2.payment.repository.PaymentRepository;
import id.ppob2.sharedkernel.money.Money;
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
            // Debug aid for the open "unconfirmed callback-route signed-string" gap (see
            // AyolinxPaymentGateway's Javadoc / README) -- these headers are Ayolinx's own
            // per-request signature and timestamp, not a secret of ours, so they're safe to log
            // and are exactly what's needed to work out the real signed-string format once a
            // genuine rejected callback is captured.
            log.warn("Ayolinx callback signature verification failed. X-SIGNATURE={} X-TIMESTAMP={}",
                    headers.get("X-SIGNATURE"), headers.get("X-TIMESTAMP"));
            webhookEventRecorder.record(WebhookDirection.INBOUND, SOURCE, "CALLBACK", recordablePayload(rawBody), "FAILED", null);
            return PaymentCallbackOutcome.SIGNATURE_INVALID;
        }

        AyolinxCallbackPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, AyolinxCallbackPayload.class);
        } catch (Exception e) {
            webhookEventRecorder.record(WebhookDirection.INBOUND, SOURCE, "CALLBACK", recordablePayload(rawBody), "FAILED", null);
            return PaymentCallbackOutcome.MALFORMED;
        }

        webhookEventRecorder.record(WebhookDirection.INBOUND, SOURCE, "CALLBACK", rawBody, "RECEIVED", payload.dedupKey());

        Optional<Payment> maybePayment = paymentRepository.findByPgReference(payload.pgReference());
        if (maybePayment.isEmpty()) {
            log.warn("Ayolinx callback for unknown pg_reference={}", payload.pgReference());
            return PaymentCallbackOutcome.UNKNOWN_REFERENCE;
        }
        Payment payment = maybePayment.get();

        boolean isNewEvent = paymentEventDeduplicator.recordIfNew(payment.getId(), "CALLBACK_RECEIVED", rawBody, payload.dedupKey());
        if (!isNewEvent) {
            // Section 48.5: the unique constraint on dedup_key IS the idempotency mechanism.
            return PaymentCallbackOutcome.DUPLICATE_IGNORED;
        }

        if (payload.isSuccess()) {
            Instant paidAt = payload.paidAt() != null ? payload.paidAt() : Instant.now();

            // Amount check BEFORE markSuccess, deliberately: confirmed for real on 2026-09-27 that
            // a validly-signed SUCCESS callback claiming "1.00" against a 20,000 order was accepted
            // at full face value (payment SUCCESS, a 20,000 payment-ledger CREDIT, 20,000 of goods
            // fulfilled) because nothing ever compared the callback's amount with payment.amount.
            // A mismatch is therefore treated as an anomaly to flag, NOT as a payment to apply:
            // shipping goods against an amount the PG never says it collected is the one outcome
            // that cannot be undone later by an operator. The callback is still acknowledged 200
            // (see the controller) — Ayolinx redelivers anything it considers unacknowledged, and
            // re-delivering a mismatched callback would not make it match.
            Money reported = payload.reportedAmount();
            if (reported != null && !reported.equals(payment.getAmount())) {
                log.error("Ayolinx SUCCESS callback amount mismatch for payment {}: expected {}, callback reported {} "
                                + "— NOT marking paid, opening a PAYMENT_VS_PG discrepancy instead",
                        payment.getId(), payment.getAmount(), payload.amount().value());
                publishAnomaly(payment, PaymentCallbackAnomalyEvent.Kind.AMOUNT_MISMATCH, payload, reported);
                return PaymentCallbackOutcome.STALE_TERMINAL_STATE;
            }

            if (!payment.markSuccess(paidAt)) {
                // Section 25.2: "out-of-order terminal-state callbacks ... logged and flagged for
                // manual review, never silently overwrite a terminal SUCCESS". The reconciliation
                // record this used to only *log* about is now actually opened — see
                // PaymentCallbackAnomalyEvent. A payment sitting at EXPIRED/FAILED while the PG
                // reports SUCCESS means funds were collected for an order we will never fulfil,
                // which is precisely a PAYMENT_VS_PG discrepancy, not just a log line.
                log.warn("Ignoring SUCCESS callback for payment {} already in terminal state {}",
                        payment.getId(), payment.getStatus());
                publishAnomaly(payment, PaymentCallbackAnomalyEvent.Kind.SUCCESS_ON_TERMINAL_PAYMENT, payload, reported);
                return PaymentCallbackOutcome.STALE_TERMINAL_STATE;
            }
            // MANDATORY, inside this method's own @Transactional (REQUIRED) — not REQUIRES_NEW.
            // Unlike the AFTER_COMMIT-callback writes elsewhere in this codebase, this must commit
            // atomically WITH payment.markSuccess: no PAID payment without its ledger entry, and
            // no entry if this transaction rolls back for any other reason.
            ledgerService.post(LedgerType.PAYMENT, "PAYMENT", payment.getId(), LedgerEntryType.CREDIT,
                    payment.getAmount(), "QRIS payment confirmed for parent_order " + payment.getParentOrderId());
            eventPublisher.publishEvent(new PaymentConfirmedEvent(payment.getParentOrderId(), paidAt));

            // Unparseable amount: the payment is applied anyway (see AMOUNT_UNVERIFIED's Javadoc —
            // refusing money over an unproven format assumption of ours would be the worse failure)
            // but the amount agreement went unchecked, so a human is told. Published AFTER the
            // ledger post so the record describes a payment that really was applied.
            if (reported == null) {
                log.error("Ayolinx SUCCESS callback for payment {} carried an unparseable amount ({}) — payment applied, "
                                + "but the amount could NOT be verified against payment.amount ({}); opening a discrepancy",
                        payment.getId(), payload.amount() != null ? payload.amount().value() : null, payment.getAmount());
                publishAnomaly(payment, PaymentCallbackAnomalyEvent.Kind.AMOUNT_UNVERIFIED, payload, null);
            }
            return PaymentCallbackOutcome.PROCESSED;
        }

        // Every non-success TERMINAL status, checked in one place against a payment that already
        // reached terminal SUCCESS. Written over isTerminalNonSuccess() rather than per status code
        // because handling only `06` here is precisely what left `05` merely logged and `04` falling
        // through to the "Unrecognized status" branch — three branches, three different silences.
        if (payload.isTerminalNonSuccess() && payment.getStatus() == PaymentStatus.SUCCESS) {
            log.warn("Ignoring terminal status '{}' for payment {} already in terminal state SUCCESS "
                            + "— out-of-order terminal callback, opening a PAYMENT_VS_PG discrepancy",
                    payload.latestTransactionStatus(), payment.getId());
            publishAnomaly(payment, PaymentCallbackAnomalyEvent.Kind.TERMINAL_STATUS_AFTER_SUCCESS, payload,
                    payload.reportedAmount());
            return PaymentCallbackOutcome.STALE_TERMINAL_STATE;
        }

        if (payload.isFailed()) {
            if (!payment.markFailed()) {
                // Payment.markFailed() only transitions from PENDING, so a terminal SUCCESS is
                // never overwritten — that guard was always correct. What was wrong is that this
                // call site DISCARDED its return value: confirmed for real on 2026-09-27 that a
                // FAILED callback arriving after a SUCCESS produced no log line, no record, and a
                // 200 "Successful" acknowledgement. Section 25.2 requires the opposite ("logged
                // and flagged for manual review"), because a post-SUCCESS FAILED may be a reversal
                // and the funds question is real, not cosmetic.
                // Reaching here means the payment is terminal but NOT SUCCESS — the guard above
                // already handled SUCCESS. Typically a FAILED callback for a payment the expiry sweep
                // already moved to EXPIRED, i.e. both sides agree no money changed hands. Logged and
                // acknowledged, with NO reconciliation record: an earlier version published the
                // after-success kind here, which would have opened one meaningless OPEN row per
                // expired order and taught operators to ignore the whole reconciliation type.
                log.info("Ignoring FAILED callback for payment {} already in terminal state {} — both sides "
                                + "agree no payment was collected, nothing to reconcile",
                        payment.getId(), payment.getStatus());
                return PaymentCallbackOutcome.STALE_TERMINAL_STATE;
            }
            // Section 33.2 has no PAYMENT_PENDING -> FAILED order transition for a failed QRIS
            // attempt; the order is left to expire naturally via the QR expiry sweep job
            // (Section 48.3) rather than transitioned here.
            return PaymentCallbackOutcome.PROCESSED;
        }

        if (payload.isCancelled()) {
            log.warn("Ayolinx CANCELED callback for pg_reference={} — no order-state-machine transition built for this yet", payload.pgReference());
            return PaymentCallbackOutcome.PROCESSED;
        }

        if (payload.isNonTerminal()) {
            // 01 Initiated / 02 Paying / 03 Pending / 07 Not found — expected mid-flow callbacks,
            // logged for traceability (via webhookEventRecorder above) but nothing to react to yet.
            log.debug("Ayolinx non-terminal status '{}' for pg_reference={}", payload.latestTransactionStatus(), payload.pgReference());
            return PaymentCallbackOutcome.PROCESSED;
        }

        log.warn("Unrecognized Ayolinx callback status '{}' for pg_reference={}", payload.latestTransactionStatus(), payload.pgReference());
        return PaymentCallbackOutcome.PROCESSED;
    }

    /**
     * Publishes within this method's own {@code @Transactional} (REQUIRED) so the discrepancy
     * record commits atomically with the {@code payment_event} dedup row written above. That
     * pairing is what makes the record idempotent under Ayolinx's redelivery: a redelivered
     * callback is stopped by {@code dedup_key} before reaching this branch at all, so it cannot
     * open a second record for the same callback — no {@code REQUIRES_NEW} needed here, unlike the
     * {@code AFTER_COMMIT}-driven orchestrators elsewhere in this codebase.
     */
    private void publishAnomaly(Payment payment, PaymentCallbackAnomalyEvent.Kind kind,
                                 AyolinxCallbackPayload payload, Money reportedAmount) {
        eventPublisher.publishEvent(new PaymentCallbackAnomalyEvent(
                payment.getId(), payment.getParentOrderId(), kind,
                payload.latestTransactionStatus(), payment.getStatus().name(),
                payment.getAmount(), reportedAmount));
    }

    /**
     * {@code webhook_event.payload} is a {@code json} column — an arbitrary, possibly not
     * even syntactically-valid-JSON {@code rawBody} (a caller sending garbage, or a stray plain-
     * text body) fails that column's own validation and throws a {@code PSQLException} the moment
     * this tries to record the very "this callback was rejected" audit row it exists to write.
     * Wrapping non-JSON input as a JSON string literal keeps the exact bytes recoverable while
     * guaranteeing the insert succeeds regardless of what was sent.
     */
    private String recordablePayload(String rawBody) {
        try {
            objectMapper.readTree(rawBody);
            return rawBody;
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(rawBody);
            } catch (Exception unwritable) {
                return "\"<unrecordable payload>\"";
            }
        }
    }
}
