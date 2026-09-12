package id.ppob2.app.reconciliation;

import id.ppob2.ledger.domain.LedgerEntry;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.ledger.repository.LedgerEntryRepository;
import id.ppob2.order.ChildOrderService;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.pricing.domain.PatternEconomics;
import id.ppob2.pricing.repository.PatternEconomicsRepository;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 38.1's "Expected margin vs Actual margin" reconciliation type: "pattern_economics
 * projected net_contribution vs actual computed post-fact from real provider cost/payment fee" —
 * "Detect margin erosion, pricing drift." Lives in `app`: `reconciliation` has no edge to `order`,
 * `pricing`, or `ledger`'s read side beyond what {@code ledger} itself grants it (Section 20.2
 * grants {@code reconciliation -> ledger}, but resolving the *order's pattern and successful
 * children* — needed to know which ledger entries are "this order's actual cost" — needs `order`,
 * which `reconciliation` cannot reach). Same composition-root shape as
 * {@code OrderFulfillmentReconciliationOrchestrator} right next to it.
 *
 * <p><b>Gross, not net — a deliberate narrowing of Section 38.1's literal wording.</b>
 * {@code pattern_economics.net_contribution} has {@code payment_fee} subtracted, but no per-order
 * PG fee exists anywhere in this codebase (only {@code settlement.fee_amount}, at daily-batch
 * granularity) and {@code direct_cost} — a term Section 22.12's own formula references — has no
 * column at all. Comparing net figures with an assumed fee of 0 would flag every single order as
 * a "discrepancy" of at least the projected fee, making the type useless as a drift signal. This
 * compares {@code pattern_economics.gross_profit} (projected) against {@code parent_amount -
 * actual_provider_cost} (actual) instead — both are real numbers, and the difference means
 * exactly what Section 38.1 wants: provider-cost drift between pattern generation and fulfillment.
 * Flagged in the README as the gross-level subset of the named comparison.
 *
 * <p><b>SUCCESS only — not PARTIAL_FAILED/FAILED.</b> {@code pattern_economics} projects a fully
 * fulfilled pattern's economics; comparing it against a partially- or wholly-failed order's
 * actual cost produces a nonsensical result (a failed child costs nothing, so a bigger failure
 * looks like a bigger margin *gain* against the full {@code parent_amount} — the refund that
 * would offset that isn't built). {@link OrderFulfillmentReconciliationOrchestrator} already
 * covers failures; this type covers successes only, where the "same shape as projected" premise
 * actually holds.
 *
 * <p><b>Actual cost comes from {@code ledger_entry}, not current {@code provider_price}.</b> The
 * ledger already recorded the true cost paid at purchase time (Section 36.1's Provider/Fulfillment
 * Ledger); re-deriving it from the *current* active price would be wrong if the price changed
 * since — exactly the kind of drift this reconciliation type exists to catch, so it must not be
 * the same input being compared against itself.
 *
 * <p>Compares against the {@code pattern_economics} snapshot in force on-or-before the order's
 * creation date, not the latest one — a later re-scoring run must not retroactively redefine
 * "expected" for an order already placed. Ordering by {@code snapshot_date} alone (no secondary
 * key) is safe from ties: {@code pattern_economics_uk UNIQUE(pattern_id, snapshot_date)} means
 * at most one row can exist per pattern per date.
 */
@Component
public class MarginReconciliationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MarginReconciliationOrchestrator.class);

    private final ParentOrderRepository parentOrderRepository;
    private final ChildOrderService childOrderService;
    private final PatternEconomicsRepository patternEconomicsRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ReconciliationService reconciliationService;

    public MarginReconciliationOrchestrator(ParentOrderRepository parentOrderRepository,
                                             ChildOrderService childOrderService,
                                             PatternEconomicsRepository patternEconomicsRepository,
                                             LedgerEntryRepository ledgerEntryRepository,
                                             ReconciliationService reconciliationService) {
        this.parentOrderRepository = parentOrderRepository;
        this.childOrderService = childOrderService;
        this.patternEconomicsRepository = patternEconomicsRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.reconciliationService = reconciliationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(Long parentOrderId) {
        ParentOrder order = parentOrderRepository.findById(parentOrderId)
                .orElseThrow(() -> new IllegalStateException("parent_order " + parentOrderId + " not found for margin reconciliation"));

        if (order.getState() != OrderState.SUCCESS || order.getPatternId() == null) {
            return;
        }

        LocalDate orderDate = LocalDate.ofInstant(order.getCreatedAt(), ZoneOffset.UTC);
        PatternEconomics economics = patternEconomicsRepository
                .findTopByPatternIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(order.getPatternId(), orderDate)
                .orElse(null);
        if (economics == null) {
            log.warn("parent_order {} has no pattern_economics snapshot on or before {} for pattern {} — "
                    + "skipping margin reconciliation, nothing to compare against", parentOrderId, orderDate, order.getPatternId());
            return;
        }

        if (reconciliationService.hasOpenDiscrepancy(ReconciliationType.MARGIN_EXPECTED_VS_ACTUAL, parentOrderId)) {
            log.info("parent_order {} already has an OPEN/INVESTIGATING MARGIN_EXPECTED_VS_ACTUAL record — skipping", parentOrderId);
            return;
        }

        List<Long> providerTransactionIds = childOrderService.findByParentOrderId(parentOrderId).stream()
                .filter(c -> c.getState() == ChildOrderState.SUCCESS)
                .map(ChildOrder::getProviderTransactionId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Money actualProviderCost = providerTransactionIds.isEmpty() ? Money.ZERO
                : ledgerEntryRepository.findByLedgerTypeAndReferenceTypeAndReferenceIdIn(
                        LedgerType.PROVIDER, "PROVIDER_TRANSACTION", providerTransactionIds).stream()
                    .map(LedgerEntry::getAmount)
                    .reduce(Money.ZERO, Money::add);

        Money actualGrossProfit = order.getParentAmount().subtract(actualProviderCost);
        Money expectedGrossProfit = economics.getGrossProfit();

        if (actualGrossProfit.equals(expectedGrossProfit)) {
            return;
        }

        reconciliationService.open(ReconciliationType.MARGIN_EXPECTED_VS_ACTUAL, LocalDate.now(), parentOrderId,
                expectedGrossProfit, actualGrossProfit);
    }
}
