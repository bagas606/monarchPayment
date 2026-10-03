package id.ppob2.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the refund invariants that are cheap to assert in isolation and expensive to discover in
 * production: the direction of the reversing ledger entry, the deterministic dedup key that makes a
 * second REFUND event impossible at the database, the {@code SUCCESS}-only guard, and the refusal
 * to record a refund with no reference. The end-to-end behaviour (both modes, against a real
 * Postgres) is driven by {@code scripts/e2e/run-core.sh}; this does not re-prove persistence.
 */
class PaymentRefundServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentEventRepository paymentEventRepository = mock(PaymentEventRepository.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentRefundService service = new PaymentRefundService(
            paymentRepository, paymentEventRepository, paymentGateway, ledgerService);

    /**
     * {@code payment.id} is database-generated, so a constructed entity has a null one — which
     * would make the dedup-key assertion below pass vacuously against {@code "refund:null"}. Set
     * it reflectively so the key being asserted is a real one.
     */
    private static Payment paymentIn(PaymentStatus status) {
        Payment payment = new Payment(7L, "STUB-PG-REF", "qr", id.ppob2.sharedkernel.money.Money.of(20000L),
                PaymentStatus.PENDING, Instant.now().plusSeconds(900));
        try {
            java.lang.reflect.Field id = Payment.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(payment, 42L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Payment.id field moved — update this helper", e);
        }
        if (status == PaymentStatus.SUCCESS || status == PaymentStatus.REFUNDED) {
            payment.markSuccess(Instant.now());
        } else if (status == PaymentStatus.FAILED) {
            payment.markFailed();
        } else if (status == PaymentStatus.EXPIRED) {
            payment.markExpired();
        }
        if (status == PaymentStatus.REFUNDED) {
            payment.markRefunded(Instant.now());
        }
        return payment;
    }

    @Test
    void postsAReversingDebitOfTheFullAmountAndARefundEventWithADeterministicDedupKey() {
        Payment payment = paymentIn(PaymentStatus.SUCCESS);
        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

        boolean applied = service.refund(1L, "no pattern, refunding", "BANK-TRANSFER-9912", false);

        assertThat(applied).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();
        // DEBIT, not a negative CREDIT: Section 22.21 carries direction in entry_type and
        // LedgerService rejects a non-positive amount outright.
        verify(ledgerService).post(eq(LedgerType.PAYMENT), eq("PAYMENT"), anyLong(),
                eq(LedgerEntryType.DEBIT), eq(id.ppob2.sharedkernel.money.Money.of(20000L)), anyString());

        ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(paymentEventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("REFUND");
        // Deterministic per payment, so payment_event_dedup_uk is what stops a second REFUND row.
        assertThat(event.getValue().getDedupKey()).isEqualTo("refund:" + payment.getId());
        assertThat(event.getValue().getRawPayload())
                .contains("\"mode\":\"OUT_OF_BAND\"")
                .contains("BANK-TRANSFER-9912")
                .contains("no pattern, refunding");
    }

    @Test
    void recordsGatewayModeDistinctlyFromOutOfBand() {
        given(paymentRepository.findById(1L)).willReturn(Optional.of(paymentIn(PaymentStatus.SUCCESS)));

        service.refund(1L, "r", "PG-REFUND-55", true);

        ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(paymentEventRepository).save(event.capture());
        assertThat(event.getValue().getRawPayload()).contains("\"mode\":\"GATEWAY\"");
    }

    @Test
    void refusesToReverseAPaymentThatNeverCollectedAnything() {
        // The whole point of markRefunded's SUCCESS-only guard: a DEBIT against an EXPIRED or
        // FAILED payment would reverse funds that were never credited.
        for (PaymentStatus status : new PaymentStatus[]{
                PaymentStatus.PENDING, PaymentStatus.FAILED, PaymentStatus.EXPIRED}) {
            PaymentRepository repo = mock(PaymentRepository.class);
            PaymentEventRepository events = mock(PaymentEventRepository.class);
            LedgerService ledger = mock(LedgerService.class);
            PaymentRefundService svc = new PaymentRefundService(repo, events, paymentGateway, ledger);
            given(repo.findById(1L)).willReturn(Optional.of(paymentIn(status)));

            assertThat(svc.refund(1L, "r", "REF", false)).as("status %s", status).isFalse();

            verify(ledger, never()).post(any(), anyString(), anyLong(), any(), any(), anyString());
            verify(events, never()).save(any());
        }
    }

    @Test
    void refusesASecondRefundOfAnAlreadyRefundedPayment() {
        given(paymentRepository.findById(1L)).willReturn(Optional.of(paymentIn(PaymentStatus.REFUNDED)));

        assertThat(service.refund(1L, "r", "REF", false)).isFalse();

        verify(ledgerService, never()).post(any(), anyString(), anyLong(), any(), any(), anyString());
        verify(paymentEventRepository, never()).save(any());
    }

    @Test
    void refusesToRecordARefundWithNoReference() {
        // A refund with no reference is unreconcilable afterwards, which defeats recording it at
        // all. Rejected before the payment is even loaded.
        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.refund(1L, "r", blank, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refund reference is required");
        }
        verify(paymentRepository, never()).findById(anyLong());
    }

    @Test
    void gatewayFailureIsReportedAsAFailureRatherThanPropagated() {
        // A thrown PG error and an explicit decline are indistinguishable as to whether money
        // moved, and the caller's response to both is identical, so they are normalised here.
        given(paymentGateway.supportsRefund()).willReturn(true);
        given(paymentGateway.refund(any(RefundRequest.class))).willThrow(new RuntimeException("connection reset"));

        RefundResult result = service.executeAtGateway(paymentIn(PaymentStatus.SUCCESS), "r");

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("connection reset");
    }

    @Test
    void callingTheGatewayWithoutCheckingCapabilityIsAProgrammerError() {
        given(paymentGateway.supportsRefund()).willReturn(false);

        assertThatThrownBy(() -> service.executeAtGateway(paymentIn(PaymentStatus.SUCCESS), "r"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(paymentGateway, never()).refund(any());
    }
}
