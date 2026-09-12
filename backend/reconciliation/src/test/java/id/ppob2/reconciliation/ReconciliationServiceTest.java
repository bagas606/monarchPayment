package id.ppob2.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.reconciliation.repository.ReconciliationRepository;
import id.ppob2.sharedkernel.money.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * {@link ReconciliationService#investigate}/{@link ReconciliationService#resolve} are unreachable
 * from any real path in this codebase — Admin Web (Section 40.8), the only intended caller, isn't
 * built yet — so this is the only thing that will ever exercise Section 38.2's "must be moved to
 * INVESTIGATING then RESOLVED" guard, including that OPEN can't resolve directly.
 *
 * <p><b>This covers the state-machine guards only, not the database write path.</b> {@code
 * investigate}/{@code resolve} mutate the entity in place and rely on JPA dirty-checking to flush
 * at commit — with a mocked repository and no {@code EntityManager}, the mutation this test
 * observes only ever touches the in-memory object, never Postgres. Given this exact class of gap
 * (a mutation that looks applied in Java and never lands) has bitten this codebase before — see
 * {@code ParentOrderTransitionService.markPaid}'s Javadoc — that distinction matters: until
 * Admin Web exists and drives these methods for real, whether the entity mutation these guards
 * perform actually persists is unverified, not merely untested-by-e2e.
 */
class ReconciliationServiceTest {

    private final ReconciliationRepository repository = mock(ReconciliationRepository.class);
    private final ReconciliationService service = new ReconciliationService(repository);

    @Test
    void openComputesSignedDiscrepancyAsActualMinusExpected() {
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Reconciliation reconciliation = service.open(ReconciliationType.PAYMENT_VS_SETTLEMENT,
                LocalDate.of(2026, 9, 2), 42L, Money.of(20000L), Money.of(15000L));

        assertThat(reconciliation.getStatus()).isEqualTo(ReconciliationStatus.OPEN);
        assertThat(reconciliation.getDiscrepancy()).isEqualTo(Money.of(-5000L));
    }

    @Test
    void cannotResolveDirectlyFromOpen() {
        Reconciliation reconciliation = new Reconciliation(ReconciliationType.PAYMENT_VS_SETTLEMENT,
                LocalDate.of(2026, 9, 2), 42L, Money.of(20000L), Money.of(15000L), Money.of(-5000L));
        given(repository.findById(1L)).willReturn(java.util.Optional.of(reconciliation));

        boolean resolved = service.resolve(1L, 99L);

        assertThat(resolved).isFalse();
        assertThat(reconciliation.getStatus()).isEqualTo(ReconciliationStatus.OPEN);
    }

    @Test
    void investigateThenResolveSucceeds() {
        Reconciliation reconciliation = new Reconciliation(ReconciliationType.PAYMENT_VS_SETTLEMENT,
                LocalDate.of(2026, 9, 2), 42L, Money.of(20000L), Money.of(15000L), Money.of(-5000L));
        given(repository.findById(1L)).willReturn(java.util.Optional.of(reconciliation));

        assertThat(service.investigate(1L)).isTrue();
        assertThat(service.resolve(1L, 99L)).isTrue();
        assertThat(reconciliation.getStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
        assertThat(reconciliation.getResolvedBy()).isEqualTo(99L);
        assertThat(reconciliation.getResolvedAt()).isNotNull();
    }
}
