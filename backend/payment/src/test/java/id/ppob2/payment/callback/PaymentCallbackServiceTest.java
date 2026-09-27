package id.ppob2.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Uses a real (SNAKE_CASE-configured, matching application.yml) {@link ObjectMapper} rather than
 * a mock — the whole point of exercising this through {@link PaymentCallbackService} rather than
 * {@link AyolinxCallbackPayloadTest} alone is proving the real deserialization path works
 * end-to-end. Every other collaborator is a real repository-free service call away from Postgres,
 * so those are mocked; {@link Payment} itself is exercised for real since its state-transition
 * guards (markSuccess/markFailed) are exactly what this service's terminal-state handling depends
 * on, and mocking them would hide the thing being tested (same lesson as
 * AdminChildOrderRetryOrchestratorTest's Mockito-nesting note: build real fixtures, don't mock
 * the thing under test).
 */
class PaymentCallbackServiceTest {

    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentEventDeduplicator paymentEventDeduplicator = mock(PaymentEventDeduplicator.class);
    private final WebhookEventRecorder webhookEventRecorder = mock(WebhookEventRecorder.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final PaymentCallbackService service = new PaymentCallbackService(
            paymentGateway, paymentRepository, paymentEventDeduplicator, webhookEventRecorder, ledgerService,
            eventPublisher, objectMapper);

    @BeforeEach
    void signatureIsValidByDefault() {
        given(paymentGateway.verifyCallbackSignature(anyString(), any())).willReturn(true);
    }

    @Test
    void invalidSignatureIsRecordedAndRejectedBeforeAnyParsing() {
        given(paymentGateway.verifyCallbackSignature(anyString(), any())).willReturn(false);

        PaymentCallbackOutcome outcome = service.processCallback("not even json", Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.SIGNATURE_INVALID);
        verify(webhookEventRecorder).record(eq(WebhookDirection.INBOUND), eq("AYOLINX"), eq("CALLBACK"), anyString(), eq("FAILED"), eq(null));
        verify(paymentEventDeduplicator, never()).recordIfNew(any(), anyString(), anyString(), anyString());
    }

    @Test
    void malformedJsonIsRejectedAfterSignatureButBeforeLookup() {
        PaymentCallbackOutcome outcome = service.processCallback("{not json", Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.MALFORMED);
        verify(paymentRepository, never()).findByPgReference(anyString());
    }

    @Test
    void unknownPgReferenceIsAcknowledgedButNotProcessed() {
        given(paymentRepository.findByPgReference("ORD-404")).willReturn(Optional.empty());

        PaymentCallbackOutcome outcome = service.processCallback(successJson("ORD-404", "AYO-1"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.UNKNOWN_REFERENCE);
    }

    @Test
    void duplicateCallbackIsIgnoredWithoutTouchingThePaymentOrLedger() {
        Payment payment = pendingPayment("ORD-1");
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(false);

        PaymentCallbackOutcome outcome = service.processCallback(successJson("ORD-1", "AYO-1"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.DUPLICATE_IGNORED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any());
    }

    @Test
    void successCallbackMarksPaymentPaidPostsLedgerAndPublishesConfirmation() {
        Payment payment = pendingPayment("ORD-1");
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(successJson("ORD-1", "AYO-1"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(ledgerService).post(eq(LedgerType.PAYMENT), eq("PAYMENT"), any(), eq(LedgerEntryType.CREDIT), eq(payment.getAmount()), anyString());
        verify(eventPublisher).publishEvent(any(PaymentConfirmedEvent.class));
    }

    @Test
    void staleTerminalSuccessCallbackNeverOverwritesAnAlreadyResolvedPayment() {
        Payment payment = pendingPayment("ORD-1");
        payment.markFailed();
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(successJson("ORD-1", "AYO-1"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.STALE_TERMINAL_STATE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedCallbackMarksPaymentFailedWithoutTouchingTheLedger() {
        Payment payment = pendingPayment("ORD-1");
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(statusJson("ORD-1", "AYO-1", "06"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any());
    }

    /**
     * The scenario advisor flagged: Ayolinx sends non-terminal callbacks (01 Initiated, 02
     * Paying) before the terminal 00 Success for the *same transaction*. Because {@code
     * AyolinxCallbackPayload.dedupKey()} composes reference + status, each is a distinct dedup
     * key — simulated here with a real {@link HashSet}-backed fake rather than a blanket {@code
     * willReturn(true)}, so this test actually fails if a future change collapses the keys back
     * down to just the reference.
     */
    @Test
    void statusProgressionCallbacksAreAllProcessedAndTheFinalSuccessIsNotDroppedAsADuplicate() {
        Payment payment = pendingPayment("ORD-1");
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        Set<String> seenDedupKeys = new HashSet<>();
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString()))
                .willAnswer(invocation -> seenDedupKeys.add(invocation.getArgument(3)));

        PaymentCallbackOutcome initiated = service.processCallback(statusJson("ORD-1", "AYO-1", "01"), Map.of());
        PaymentCallbackOutcome paying = service.processCallback(statusJson("ORD-1", "AYO-1", "02"), Map.of());
        PaymentCallbackOutcome success = service.processCallback(statusJson("ORD-1", "AYO-1", "00"), Map.of());

        assertThat(initiated).isEqualTo(PaymentCallbackOutcome.PROCESSED);
        assertThat(paying).isEqualTo(PaymentCallbackOutcome.PROCESSED);
        assertThat(success).isEqualTo(PaymentCallbackOutcome.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }


    // --- Section 25.2 callback anomalies. All three were verified BROKEN against a running app on
    // 2026-09-27 before these tests existed; each assertion below names what was actually observed.

    /**
     * Previously: logged a WARN and opened nothing. Live repro — a SUCCESS callback for an order
     * whose QR had already been swept to EXPIRED left {@code payment.status=EXPIRED},
     * {@code paid_at} NULL and the reconciliation table untouched, so nothing surfaced that the PG
     * believed it had collected funds.
     */
    @Test
    void successCallbackOnATerminalPaymentOpensAnAnomalyInsteadOfOnlyLogging() {
        Payment payment = pendingPayment("ORD-1");
        payment.markExpired();
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(successJson("ORD-1", "AYO-1"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.STALE_TERMINAL_STATE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any());
        assertThat(capturedAnomaly().kind())
                .isEqualTo(PaymentCallbackAnomalyEvent.Kind.SUCCESS_ON_TERMINAL_PAYMENT);
    }

    /**
     * Previously COMPLETELY silent — no log line, no record, {@code 200 Successful} returned —
     * because {@code Payment.markFailed()}'s {@code false} return was discarded at the call site.
     * The SUCCESS was never overwritten (that guard was always right); the anomaly just vanished.
     */
    /**
     * Refunded ({@code 04}) and Canceled ({@code 05}) after a terminal SUCCESS, which the first
     * version of this fix missed entirely: it handled only {@code 06}, so {@code 05} merely logged
     * and {@code 04} — a refund of funds for goods this platform has already delivered — fell
     * through to the "Unrecognized status" WARN and was answered {@code 200} with nothing recorded.
     */
    @Test
    void everyNonSuccessTerminalStatusAfterSuccessOpensAnAnomaly() {
        for (String terminal : new String[]{"04", "05", "06"}) {
            PaymentGateway gateway = mock(PaymentGateway.class);
            PaymentRepository repository = mock(PaymentRepository.class);
            PaymentEventDeduplicator dedup = mock(PaymentEventDeduplicator.class);
            LedgerService ledger = mock(LedgerService.class);
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
            given(gateway.verifyCallbackSignature(anyString(), any())).willReturn(true);
            given(dedup.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

            Payment payment = pendingPayment("ORD-1");
            payment.markSuccess(Instant.now());
            given(repository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));

            PaymentCallbackService svc = new PaymentCallbackService(gateway, repository, dedup,
                    mock(WebhookEventRecorder.class), ledger, publisher, objectMapper);

            PaymentCallbackOutcome outcome = svc.processCallback(statusJson("ORD-1", "AYO-1", terminal), Map.of());

            assertThat(outcome).as("status %s", terminal).isEqualTo(PaymentCallbackOutcome.STALE_TERMINAL_STATE);
            assertThat(payment.getStatus()).as("status %s", terminal).isEqualTo(PaymentStatus.SUCCESS);
            ArgumentCaptor<PaymentCallbackAnomalyEvent> captor =
                    ArgumentCaptor.forClass(PaymentCallbackAnomalyEvent.class);
            verify(publisher).publishEvent(captor.capture());
            assertThat(captor.getValue().kind()).as("status %s", terminal)
                    .isEqualTo(PaymentCallbackAnomalyEvent.Kind.TERMINAL_STATUS_AFTER_SUCCESS);
            verify(ledger, never()).post(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void failedCallbackAfterSuccessPreservesSuccessAndOpensAnAnomaly() {
        Payment payment = pendingPayment("ORD-1");
        payment.markSuccess(Instant.now());
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(statusJson("ORD-1", "AYO-1", "06"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.STALE_TERMINAL_STATE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(capturedAnomaly().kind()).isEqualTo(PaymentCallbackAnomalyEvent.Kind.TERMINAL_STATUS_AFTER_SUCCESS);
    }

    /**
     * Previously: accepted at full face value. Live repro — a validly-signed SUCCESS callback
     * claiming {@code "1.00"} against a 20,000 order marked the payment SUCCESS, posted a 20,000
     * CREDIT to the payment ledger and fulfilled 20,000 of goods, because nothing compared the
     * callback's amount to {@code payment.amount}.
     */
    @Test
    void successCallbackWithAMismatchedAmountIsNotAppliedAndOpensAnAnomaly() {
        Payment payment = pendingPayment("ORD-1");
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(
                amountJson("ORD-1", "AYO-1", "00", "1.00"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.STALE_TERMINAL_STATE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(PaymentConfirmedEvent.class));
        PaymentCallbackAnomalyEvent anomaly = capturedAnomaly();
        assertThat(anomaly.kind()).isEqualTo(PaymentCallbackAnomalyEvent.Kind.AMOUNT_MISMATCH);
        assertThat(anomaly.expectedAmount()).isEqualTo(Money.of(15000L));
        assertThat(anomaly.reportedAmount()).isEqualTo(Money.of(1L));
    }

    /**
     * An amount this code cannot parse is NOT allowed to block the payment — it is applied, and a
     * separate {@code AMOUNT_UNVERIFIED} discrepancy says the amount agreement went unchecked.
     *
     * <p>That asymmetry with {@code AMOUNT_MISMATCH} is the deliberate part. The exact numeric
     * format Ayolinx uses in a callback has never been captured against an order with a known
     * billed amount, so refusing money because of an unproven assumption in *our* parser would turn
     * one wrong guess into a total payment outage. A mismatch between two successfully parsed
     * numbers is a confident finding and does block; "I don't understand this field" is not.
     */
    @Test
    void successCallbackWithAnUnparseableAmountIsStillAppliedButFlaggedUnverified() {
        Payment payment = pendingPayment("ORD-1");
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(
                amountJson("ORD-1", "AYO-1", "00", "15000.50"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(ledgerService).post(eq(LedgerType.PAYMENT), eq("PAYMENT"), any(), eq(LedgerEntryType.CREDIT),
                eq(payment.getAmount()), anyString());
        assertThat(publishedAnomaly().kind()).isEqualTo(PaymentCallbackAnomalyEvent.Kind.AMOUNT_UNVERIFIED);
    }

    /**
     * Grouping separators are tolerated rather than rejected, for the same "don't guess a format and
     * then block money on the guess" reason. A thousands-separated amount that AGREES must sail
     * straight through, not land in AMOUNT_UNVERIFIED.
     */
    @Test
    void successCallbackWithAThousandsSeparatedAmountIsParsedAndAccepted() {
        Payment payment = pendingPayment("ORD-1");
        given(paymentRepository.findByPgReference("ORD-1")).willReturn(Optional.of(payment));
        given(paymentEventDeduplicator.recordIfNew(any(), anyString(), anyString(), anyString())).willReturn(true);

        PaymentCallbackOutcome outcome = service.processCallback(
                amountJson("ORD-1", "AYO-1", "00", "15,000.00"), Map.of());

        assertThat(outcome).isEqualTo(PaymentCallbackOutcome.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(eventPublisher, never()).publishEvent(any(PaymentCallbackAnomalyEvent.class));
    }

    /** Picks the anomaly out of a publisher that also saw a PaymentConfirmedEvent. */
    private PaymentCallbackAnomalyEvent publishedAnomaly() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(PaymentCallbackAnomalyEvent.class::isInstance)
                .map(PaymentCallbackAnomalyEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no PaymentCallbackAnomalyEvent was published"));
    }

    private PaymentCallbackAnomalyEvent capturedAnomaly() {
        ArgumentCaptor<PaymentCallbackAnomalyEvent> captor = ArgumentCaptor.forClass(PaymentCallbackAnomalyEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private static Payment pendingPayment(String pgReference) {
        return new Payment(1L, pgReference, "qr-payload", Money.of(15000L), PaymentStatus.PENDING, Instant.now().plusSeconds(600));
    }

    private static String successJson(String orderNo, String ayolinxRef) {
        return statusJson(orderNo, ayolinxRef, "00");
    }

    private static String statusJson(String orderNo, String ayolinxRef, String status) {
        return amountJson(orderNo, ayolinxRef, status, "15000.00");
    }

    private static String amountJson(String orderNo, String ayolinxRef, String status, String amount) {
        return """
                {
                  "callbackType": "QRIS",
                  "additionalInfo": {"channel": "BNC_QRIS"},
                  "amount": {"currency": "IDR", "value": "%s"},
                  "latestTransactionStatus": "%s",
                  "originalPartnerReferenceNo": "%s",
                  "originalReferenceNo": "%s",
                  "finishedTime": "2024-09-18T06:36:36+00:00"
                }
                """.formatted(amount, status, orderNo, ayolinxRef);
    }
}
