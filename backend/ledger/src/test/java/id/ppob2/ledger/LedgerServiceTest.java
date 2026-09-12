package id.ppob2.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import id.ppob2.ledger.domain.LedgerEntryType;
import id.ppob2.ledger.domain.LedgerType;
import id.ppob2.ledger.repository.LedgerEntryRepository;
import id.ppob2.sharedkernel.money.Money;
import org.junit.jupiter.api.Test;

/**
 * {@link LedgerService#post} is the only sanctioned write path into an append-only,
 * database-trigger-protected table (Section 22.21) — a wrong amount that slips past its guard
 * can only be corrected with a reversing entry, never fixed in place. This is the one thing the
 * end-to-end verification of the payment-ledger slice couldn't exercise, since every real call
 * there passes an already-positive `payment.amount`.
 */
class LedgerServiceTest {

    private final LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
    private final LedgerService service = new LedgerService(repository);

    @Test
    void rejectsNegativeAmountWithoutTouchingTheRepository() {
        assertThatThrownBy(() -> service.post(LedgerType.PAYMENT, "PAYMENT", 1L, LedgerEntryType.CREDIT,
                Money.of(-5000L), "should never be written"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsZeroAmountWithoutTouchingTheRepository() {
        assertThatThrownBy(() -> service.post(LedgerType.PAYMENT, "PAYMENT", 1L, LedgerEntryType.CREDIT,
                Money.ZERO, "should never be written"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }
}
