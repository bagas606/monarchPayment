package id.ppob2.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.payment.repository.PaymentRepository;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.settlement.SettlementAllocationService;
import id.ppob2.settlement.SettlementIngestionService;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The settlement window, which is the part of this orchestrator that was host-dependent.
 *
 * <p>Both queries it drives used to take a {@code LocalDate} and filter {@code paid_at::date},
 * whose cast runs in the Postgres <em>session</em> timezone — set by the JDBC driver from the JVM
 * default. The same payment data therefore produced a different {@code expected_amount} depending
 * on the deploying host's {@code TZ}, and so a different {@code MATCHED}/{@code DISCREPANCY}
 * verdict. These tests assert the two properties that replaced it: the window is derived from the
 * configured business zone, and <b>both</b> queries get the identical bounds.
 *
 * <p>The second is a correctness requirement rather than tidiness — the two are the denominator and
 * the numerators of one apportionment, and BR-REC-002 requires the allocations to sum to
 * {@code actual_amount} exactly. Two windows would break that invariant at allocation write time,
 * far from the cause.
 */
class SettlementIngestionOrchestratorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ParentOrderRepository parentOrderRepository = mock(ParentOrderRepository.class);
    private final SettlementIngestionService settlementIngestionService = mock(SettlementIngestionService.class);
    private final SettlementAllocationService settlementAllocationService = mock(SettlementAllocationService.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);

    private SettlementIngestionOrchestrator orchestratorIn(String zone) {
        given(paymentRepository.sumSuccessfulAmountPaidBetween(any(), any())).willReturn(BigDecimal.valueOf(100000));
        given(parentOrderRepository.sumSuccessfulPaymentAmountByPartnerPaidBetween(any(), any()))
                .willReturn(List.of());
        Settlement settlement = mock(Settlement.class);
        given(settlement.isDiscrepancy()).willReturn(false);
        given(settlementIngestionService.ingest(any(), anyString(), any(), any(), any())).willReturn(settlement);

        return new SettlementIngestionOrchestrator(Clock.system(ZoneId.of(zone)), paymentRepository,
                parentOrderRepository, settlementIngestionService, settlementAllocationService, reconciliationService);
    }

    @Test
    void theWindowIsAHalfOpenRangeInTheConfiguredBusinessZone() {
        orchestratorIn("UTC").ingest(DAY, "BATCH-1", Money.of(100000L), Money.of(0L));

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).sumSuccessfulAmountPaidBetween(start.capture(), end.capture());

        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        // Half-open: a payment at exactly midnight belongs to one settlement window only. A
        // BETWEEN would put it in two.
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
    }

    @Test
    void aDifferentBusinessZoneMovesTheWindow() {
        // Jakarta is UTC+7, so its 2026-10-10 runs 2026-10-09T17:00Z .. 2026-10-10T17:00Z. A
        // payment at 23:30 UTC on the 10th falls OUTSIDE this window and inside the next day's --
        // which is precisely the seven-hour shift that used to depend on the host instead.
        orchestratorIn("Asia/Jakarta").ingest(DAY, "BATCH-1", Money.of(100000L), Money.of(0L));

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).sumSuccessfulAmountPaidBetween(start.capture(), end.capture());

        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-09T17:00:00Z"));
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-10-10T17:00:00Z"));
    }

    @Test
    void bothQueriesGetTheIdenticalWindow() {
        // The BR-REC-002 invariant depends on this: per-partner sums are weighted against the
        // total, so a different window for either one makes the allocations stop summing to
        // actual_amount -- and it would surface as a rejected allocation, not as a window bug.
        orchestratorIn("Asia/Jakarta").ingest(DAY, "BATCH-1", Money.of(100000L), Money.of(0L));

        ArgumentCaptor<Instant> totalStart = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> totalEnd = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).sumSuccessfulAmountPaidBetween(totalStart.capture(), totalEnd.capture());

        ArgumentCaptor<Instant> partnerStart = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> partnerEnd = ArgumentCaptor.forClass(Instant.class);
        verify(parentOrderRepository)
                .sumSuccessfulPaymentAmountByPartnerPaidBetween(partnerStart.capture(), partnerEnd.capture());

        assertThat(partnerStart.getValue()).isEqualTo(totalStart.getValue());
        assertThat(partnerEnd.getValue()).isEqualTo(totalEnd.getValue());
    }
}
