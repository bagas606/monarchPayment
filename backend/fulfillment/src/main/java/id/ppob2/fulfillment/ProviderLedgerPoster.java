package id.ppob2.fulfillment;

import id.ppob2.ledger.LedgerService;
import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.sharedkernel.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD Section 36.1's Provider/Fulfillment Ledger: "entries tied to provider purchase debits
 * against `provider_balance`." Section 20.2 grants {@code fulfillment -> ledger} directly, so
 * (unlike cost resolution, which needs `app` for the missing `fulfillment -> pricing` edge) this
 * post belongs in `fulfillment`, in its own {@code REQUIRES_NEW} bean for the same reason
 * {@link ProviderTransactionRecorder} is separate: {@link FulfillmentExecutionService} is
 * deliberately non-transactional, and {@code LedgerService.post} is {@code MANDATORY} — it needs
 * an active transaction to join, which this bean's own annotation supplies. {@code REQUIRES_NEW}
 * because this is reached from inside an {@code AFTER_COMMIT} listener's call tree.
 *
 * <p>{@code provider_balance} itself (Section 22.20 — topup/debit/adjustment tracking) is not
 * built; this only posts the ledger entry side, flagged in the README the same way Order Ledger
 * posting is flagged pending a PRD decision.
 */
@Service
public class ProviderLedgerPoster {

    private final LedgerService ledgerService;

    public ProviderLedgerPoster(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postPurchaseDebit(Long providerTransactionId, Money unitCost, int quantity) {
        Money totalCost = unitCost.multiply(quantity);
        ledgerService.post(LedgerType.PROVIDER, "PROVIDER_TRANSACTION", providerTransactionId,
                LedgerEntryType.DEBIT, totalCost, "Provider purchase debit");
    }
}
