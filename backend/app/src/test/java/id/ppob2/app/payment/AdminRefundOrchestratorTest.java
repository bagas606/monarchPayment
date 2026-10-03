package id.ppob2.app.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.payment.PaymentRefundService;
import id.ppob2.payment.domain.Payment;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.payment.gateway.RefundResult;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Covers the decision logic around a refund — which mode, and every reason to refuse — rather than
 * the writes themselves ({@link RefundRecorder}, driven e2e against a real Postgres).
 *
 * <p>The assertions that matter most are the ones proving nothing was written on a refusal: a
 * refund is the only irreversible money movement in this system, so "refused and recorded nothing"
 * and "refused but posted a reversal anyway" are the two outcomes worth keeping apart.
 */
class AdminRefundOrchestratorTest {

    private final ParentOrderRepository parentOrderRepository = mock(ParentOrderRepository.class);
    private final PaymentRefundService paymentRefundService = mock(PaymentRefundService.class);
    private final RefundRecorder refundRecorder = mock(RefundRecorder.class);
    private final AdminRefundOrchestrator orchestrator = new AdminRefundOrchestrator(
            parentOrderRepository, paymentRefundService, refundRecorder);

    private static ParentOrder orderIn(OrderState state) {
        ParentOrder order = new ParentOrder("ORD-1", 1L, 1L, "client-1", 1L, Money.of(10000L),
                "API", "idem-1", "cust-ref", Instant.now());
        order.transitionTo(OrderState.PAYMENT_PENDING);
        if (state == OrderState.PAYMENT_PENDING) {
            return order;
        }
        order.transitionTo(OrderState.PAID);
        order.transitionTo(OrderState.REFUND_PENDING);
        if (state != OrderState.REFUND_PENDING) {
            order.transitionTo(state);
        }
        return order;
    }

    private static Payment paymentIn(PaymentStatus status) {
        Payment payment = new Payment(1L, "PG-REF", "qr", Money.of(10000L),
                PaymentStatus.PENDING, Instant.now().plusSeconds(900));
        if (status != PaymentStatus.PENDING) {
            payment.markSuccess(Instant.now());
        }
        return payment;
    }

    private void givenRefundableOrder() {
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(orderIn(OrderState.REFUND_PENDING)));
        given(paymentRefundService.findByParentOrderId(1L)).willReturn(Optional.of(paymentIn(PaymentStatus.SUCCESS)));
    }

    @Test
    void outOfBandModeRequiresTheOperatorsExternalReference() {
        givenRefundableOrder();
        given(paymentRefundService.gatewayExecutesRefunds()).willReturn(false);

        assertThatThrownBy(() -> orchestrator.refund(1L, "no pattern", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("external_reference");

        verify(refundRecorder, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void outOfBandModeRecordsTheTrimmedExternalReference() {
        givenRefundableOrder();
        given(paymentRefundService.gatewayExecutesRefunds()).willReturn(false);

        orchestrator.refund(1L, "no pattern", "  BANK-9912  ");

        verify(refundRecorder).record(eq(1L), any(), eq("no pattern"), eq("BANK-9912"), eq(false));
        // Never asked a gateway that cannot do it.
        verify(paymentRefundService, never()).executeAtGateway(any(), anyString());
    }

    @Test
    void gatewayModeRejectsAnExternalReferenceAsAmbiguous() {
        // Accepting both would leave it unclear which reference was recorded.
        givenRefundableOrder();
        given(paymentRefundService.gatewayExecutesRefunds()).willReturn(true);

        assertThatThrownBy(() -> orchestrator.refund(1L, "r", "BANK-9912"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be omitted");

        verify(paymentRefundService, never()).executeAtGateway(any(), anyString());
        verify(refundRecorder, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void gatewayModeRecordsThePgsOwnRefundReference() {
        givenRefundableOrder();
        given(paymentRefundService.gatewayExecutesRefunds()).willReturn(true);
        given(paymentRefundService.executeAtGateway(any(), anyString()))
                .willReturn(new RefundResult(true, "PG-REFUND-77", null));

        orchestrator.refund(1L, "ops decision", null);

        verify(refundRecorder).record(eq(1L), any(), eq("ops decision"), eq("PG-REFUND-77"), eq(true));
    }

    @Test
    void aDeclinedGatewayRefundWritesNothingAndLeavesTheOrderRefundable() {
        givenRefundableOrder();
        given(paymentRefundService.gatewayExecutesRefunds()).willReturn(true);
        given(paymentRefundService.executeAtGateway(any(), anyString()))
                .willReturn(new RefundResult(false, null, "refund window expired"));

        assertThatThrownBy(() -> orchestrator.refund(1L, "r", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFUND_FAILED_AT_GATEWAY)
                .hasMessageContaining("still refundable");

        verify(refundRecorder, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aGatewaySuccessWithNoReferenceIsTreatedAsAFailure() {
        // Nothing to reconcile the refund against later, so recording it would be worse than
        // refusing — and refusing keeps the order refundable.
        givenRefundableOrder();
        given(paymentRefundService.gatewayExecutesRefunds()).willReturn(true);
        given(paymentRefundService.executeAtGateway(any(), anyString()))
                .willReturn(new RefundResult(true, "  ", null));

        assertThatThrownBy(() -> orchestrator.refund(1L, "r", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFUND_FAILED_AT_GATEWAY)
                .hasMessageContaining("no refund reference");

        verify(refundRecorder, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void refusesAnyOrderNotAlreadyQueuedForRefund() {
        // Deliberately narrow: moving a PARTIAL_FAILED/FAILED order into REFUND_PENDING is a
        // separate Ops decision (Section 33.2) this endpoint does not make.
        for (OrderState state : new OrderState[]{
                OrderState.PAYMENT_PENDING, OrderState.REFUNDED}) {
            given(parentOrderRepository.findById(1L)).willReturn(Optional.of(orderIn(state)));

            assertThatThrownBy(() -> orchestrator.refund(1L, "r", "REF"))
                    .as("state %s", state)
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_REFUNDABLE);
        }
        verify(refundRecorder, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void refusesWhenNoFundsWereEverCollected() {
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(orderIn(OrderState.REFUND_PENDING)));
        given(paymentRefundService.findByParentOrderId(1L)).willReturn(Optional.of(paymentIn(PaymentStatus.PENDING)));

        assertThatThrownBy(() -> orchestrator.refund(1L, "r", "REF"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_REFUNDABLE)
                .hasMessageContaining("no collected funds");

        verify(refundRecorder, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void refusesAnOrderWithNoPaymentRowAtAll() {
        given(parentOrderRepository.findById(1L)).willReturn(Optional.of(orderIn(OrderState.REFUND_PENDING)));
        given(paymentRefundService.findByParentOrderId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.refund(1L, "r", "REF"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nothing was ever collected");
    }

    @Test
    void anUnknownOrderIsANotFoundRatherThanARefusal() {
        given(parentOrderRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.refund(99L, "r", "REF"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
    }
}
