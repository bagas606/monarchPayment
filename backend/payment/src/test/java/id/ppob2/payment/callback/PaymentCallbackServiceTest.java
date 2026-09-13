package id.ppob2.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import id.ppob2.ledger.LedgerService;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
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

    private static Payment pendingPayment(String pgReference) {
        return new Payment(1L, pgReference, "qr-payload", Money.of(15000L), PaymentStatus.PENDING, Instant.now().plusSeconds(600));
    }

    private static String successJson(String orderNo, String ayolinxRef) {
        return statusJson(orderNo, ayolinxRef, "00");
    }

    private static String statusJson(String orderNo, String ayolinxRef, String status) {
        return """
                {
                  "callbackType": "QRIS",
                  "additionalInfo": {"channel": "BNC_QRIS"},
                  "amount": {"currency": "IDR", "value": "15000.00"},
                  "latestTransactionStatus": "%s",
                  "originalPartnerReferenceNo": "%s",
                  "originalReferenceNo": "%s",
                  "finishedTime": "2024-09-18T06:36:36+00:00"
                }
                """.formatted(status, orderNo, ayolinxRef);
    }
}
