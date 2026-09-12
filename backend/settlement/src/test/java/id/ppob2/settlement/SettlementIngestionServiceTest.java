package id.ppob2.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob2.ledger.LedgerService;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.settlement.domain.SettlementStatus;
import id.ppob2.settlement.repository.SettlementRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SettlementIngestionServiceTest {

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final SettlementIngestionService service = new SettlementIngestionService(settlementRepository, ledgerService);

    @Test
    void matchesWhenActualEqualsExpected() {
        given(settlementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Settlement result = service.ingest(LocalDate.of(2026, 9, 12), "BATCH-1", Money.of(40000L), Money.of(40000L), Money.of(500L));

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.MATCHED);
        verify(ledgerService).post(eq(LedgerType.SETTLEMENT), eq("SETTLEMENT"), any(), eq(LedgerEntryType.CREDIT),
                eq(Money.of(40000L)), any());
    }

    @Test
    void flagsDiscrepancyButStillPostsTheLedgerEntryForFundsThatActuallyLanded() {
        given(settlementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Settlement result = service.ingest(LocalDate.of(2026, 9, 12), "BATCH-2", Money.of(60000L), Money.of(55000L), Money.of(600L));

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.DISCREPANCY);
        // Section 36.1: the Settlement Ledger records funds actually settling — a discrepancy is
        // about the amount being wrong, not about whether money landed at all, so this must still
        // post, for the *actual* (reported) amount, not the expected one.
        verify(ledgerService).post(eq(LedgerType.SETTLEMENT), eq("SETTLEMENT"), any(), eq(LedgerEntryType.CREDIT),
                eq(Money.of(55000L)), any());
    }

    @Test
    void rejectsASecondReportForADateAlreadyIngestedWithoutTouchingTheLedger() {
        LocalDate date = LocalDate.of(2026, 9, 20);
        given(settlementRepository.existsBySettlementDate(date)).willReturn(true);

        assertThatThrownBy(() -> service.ingest(date, "BATCH-DUP", Money.of(40000L), Money.of(40000L), Money.of(500L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).errorCode())
                .isEqualTo(ErrorCode.SETTLEMENT_ALREADY_INGESTED);

        verify(settlementRepository, never()).save(any());
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any());
    }
}
