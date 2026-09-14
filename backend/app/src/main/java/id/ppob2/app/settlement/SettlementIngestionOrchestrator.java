package id.ppob2.app.settlement;

import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.payment.repository.PaymentRepository;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.settlement.SettlementAllocationService;
import id.ppob2.settlement.SettlementIngestionService;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes `payment` (expected_amount), `settlement` (ingestion), and `reconciliation` (Section
 * 38.2's "every discrepancy is persisted to reconciliation") into one atomic operation — none of
 * `settlement -> payment`, `settlement -> reconciliation`, or `reconciliation -> app`'s reverse
 * exist in Section 20.2's graph, but `app` depends on all three, the same composition-root
 * pattern used throughout this codebase.
 *
 * <p>Deliberately a synchronous, same-transaction call — not an event, unlike
 * {@code PaymentConfirmedEvent}/{@code ChildOrdersReadyEvent} elsewhere in this codebase. Those
 * exist because the publishing module has no path to the consumer at all (Section 20.2 grants no
 * edge in either direction) and the work is a genuinely separate downstream stage (Section 35.1's
 * async post-payment pipeline). Here, `app` already has both dependencies and there's no external
 * I/O forcing the transaction boundary apart — an {@code AFTER_COMMIT} listener whose write then
 * failed would leave a committed {@code DISCREPANCY} settlement with no reconciliation record and
 * no error surfaced anywhere, which is exactly the invariant Section 38.2 says must not happen
 * ("every discrepancy is persisted"). Settlement row, ledger entry, and reconciliation record now
 * commit together or not at all; a failure is a 500 the operator retries, not a silent gap.
 *
 * <p>Section 37.3: also resolves each contributing partner's share of {@code expected_amount} (via
 * {@code order}'s {@code payment}-join query -- `settlement` itself has no edge to either module)
 * and hands it to {@link SettlementAllocationService} for pro-rata attribution, in the same
 * transaction as the settlement/reconciliation writes above -- allocation is meant to be computed
 * for every settlement, not a best-effort side effect that can silently fail to run.
 */
@Service
public class SettlementIngestionOrchestrator {

    private final PaymentRepository paymentRepository;
    private final ParentOrderRepository parentOrderRepository;
    private final SettlementIngestionService settlementIngestionService;
    private final SettlementAllocationService settlementAllocationService;
    private final ReconciliationService reconciliationService;

    public SettlementIngestionOrchestrator(PaymentRepository paymentRepository,
                                            ParentOrderRepository parentOrderRepository,
                                            SettlementIngestionService settlementIngestionService,
                                            SettlementAllocationService settlementAllocationService,
                                            ReconciliationService reconciliationService) {
        this.paymentRepository = paymentRepository;
        this.parentOrderRepository = parentOrderRepository;
        this.settlementIngestionService = settlementIngestionService;
        this.settlementAllocationService = settlementAllocationService;
        this.reconciliationService = reconciliationService;
    }

    @Transactional
    public Settlement ingest(LocalDate settlementDate, String pgReference, Money actualAmount, Money feeAmount) {
        Money expectedAmount = Money.of(paymentRepository.sumSuccessfulAmountForDate(settlementDate).toBigInteger());

        Settlement settlement = settlementIngestionService.ingest(settlementDate, pgReference, expectedAmount, actualAmount, feeAmount);

        if (settlement.isDiscrepancy()) {
            reconciliationService.open(ReconciliationType.PAYMENT_VS_SETTLEMENT, settlement.getSettlementDate(),
                    settlement.getId(), settlement.getExpectedAmount(), settlement.getActualAmount());
        }

        Map<Long, Money> partnerExpectedAmounts = new LinkedHashMap<>();
        parentOrderRepository.sumSuccessfulPaymentAmountByPartnerForDate(settlementDate)
                .forEach(row -> partnerExpectedAmounts.put(row.getPartnerId(), Money.of(row.getAmount().toBigInteger())));
        settlementAllocationService.allocateProRata(settlement, partnerExpectedAmounts);

        return settlement;
    }
}
