package id.ppob2.settlement;

import id.ppob2.ledger.LedgerService;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.settlement.domain.SettlementStatus;
import id.ppob2.settlement.repository.SettlementRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 37.1/37.2: ingests one settlement report line, compares the PG-reported {@code
 * actual_amount} against the {@code expected_amount} the caller computed from `payment` records
 * (this module has no compile dependency on `payment` — Section 20.2 grants `settlement` only a
 * `ledger` edge — so the app-layer caller resolves that sum, the same composition-root pattern
 * used for fulfillment's provider_id resolution), and posts a Settlement Ledger entry.
 *
 * <p>Posts unconditionally on every successful ingestion, {@code MATCHED} or {@code DISCREPANCY}
 * alike — Section 36.1 assigns the Settlement Ledger to "funds actually settling from PG/acquirer
 * to PPOB2's bank account," and a discrepancy is about the *amount* being wrong, not about
 * whether money landed at all. Unlike the Order Ledger (deliberately left unposted — see the
 * README), there is no competing candidate event for "funds actually settled" and no double-count
 * risk in posting here.
 *
 * <p>No re-ingestion/upsert semantics: the migration's {@code UNIQUE(settlement_date)} constraint
 * rejects a second report for the same date outright rather than this class trying to guess
 * whether a resend is a correction (update in place, needing a reversing ledger entry) or an
 * accidental replay. Section 37.1 doesn't describe reports being resent, and building that branch
 * would mean untested code deciding whether a financial ledger entry gets written twice — flagged
 * as a gap in the README instead of guessed at here.
 *
 * <p>The constraint is scoped to {@code settlement_date} alone, not {@code (settlement_date,
 * pg_reference)}, because {@code expected_amount} (computed by the `app`-layer caller) is the sum
 * of every {@code SUCCESS} payment for that date — a second, different-`pg_reference` batch on
 * the same date would compare its own partial {@code actual_amount} against that same cumulative
 * day-total {@code expected_amount} the first batch already matched, guaranteeing {@code
 * DISCREPANCY} regardless of whether the second batch's numbers are correct, and would post a
 * second Settlement Ledger credit for money already credited once. A real multi-batch day needs a
 * {@code payment -> settlement} link (Section 22.17 has none) to scope each batch's expected sum
 * to only the payments it actually covers — not built; this constraint makes "one batch per date"
 * an explicit, enforced assumption instead of a silent miscomparison.
 */
@Service
public class SettlementIngestionService {

    private final SettlementRepository settlementRepository;
    private final LedgerService ledgerService;

    public SettlementIngestionService(SettlementRepository settlementRepository, LedgerService ledgerService) {
        this.settlementRepository = settlementRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public Settlement ingest(LocalDate settlementDate, String pgReference, Money expectedAmount,
                              Money actualAmount, Money feeAmount) {
        SettlementStatus status = actualAmount.equals(expectedAmount) ? SettlementStatus.MATCHED : SettlementStatus.DISCREPANCY;

        Settlement settlement = new Settlement(settlementDate, pgReference, expectedAmount, actualAmount, feeAmount, status);
        settlement = settlementRepository.save(settlement);

        ledgerService.post(LedgerType.SETTLEMENT, "SETTLEMENT", settlement.getId(), LedgerEntryType.CREDIT,
                actualAmount, "Settlement batch " + pgReference + " for " + settlementDate);

        return settlement;
    }
}
