package id.ppob2.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ReconciliationQuery#of} is the only bound on how much work one request can ask the
 * database for, so these are the tests for "a client cannot make the server page the whole table
 * into memory", not style checks on default values.
 */
class ReconciliationQueryTest {

    private static ReconciliationQuery paging(Integer page, Integer size) {
        return ReconciliationQuery.of(null, null, null, null, null, page, size);
    }

    @Test
    void absentPagingFallsBackToTheFirstPageAtTheDefaultSize() {
        ReconciliationQuery query = paging(null, null);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(ReconciliationQuery.DEFAULT_PAGE_SIZE);
    }

    @Test
    void anOversizedSizeIsCappedRatherThanHonoured() {
        assertThat(paging(0, 10_000).size()).isEqualTo(ReconciliationQuery.MAX_PAGE_SIZE);
    }

    @Test
    void sizeAtTheCapIsKeptExactly() {
        // The boundary itself must not be clamped away -- an off-by-one here would silently make
        // the advertised maximum unreachable.
        assertThat(paging(0, ReconciliationQuery.MAX_PAGE_SIZE).size()).isEqualTo(ReconciliationQuery.MAX_PAGE_SIZE);
    }

    @Test
    void nonPositiveSizeFallsBackToTheDefault() {
        // PageRequest.of rejects size < 1 with IllegalArgumentException -> an unmapped 500 on what
        // is a client mistake. Clamping here is what keeps that out of the response.
        assertThat(paging(0, 0).size()).isEqualTo(ReconciliationQuery.DEFAULT_PAGE_SIZE);
        assertThat(paging(0, -5).size()).isEqualTo(ReconciliationQuery.DEFAULT_PAGE_SIZE);
    }

    @Test
    void negativePageIsClampedToTheFirstPage() {
        // Same reason as size: PageRequest.of rejects a negative page outright.
        assertThat(paging(-1, 10).page()).isZero();
    }

    @Test
    void aSizeBelowTheCapIsPassedThroughUntouched() {
        ReconciliationQuery query = paging(3, 25);

        assertThat(query.page()).isEqualTo(3);
        assertThat(query.size()).isEqualTo(25);
    }

    @Test
    void filtersAreCarriedThroughUnchanged() {
        // Clamping must not become filtering: the only values of() is allowed to alter are the
        // paging pair.
        ReconciliationQuery query = ReconciliationQuery.of(
                List.of(ReconciliationType.ORDER_VS_FULFILLMENT),
                List.of(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING),
                42L, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-10"), 1, 10);

        assertThat(query.types()).containsExactly(ReconciliationType.ORDER_VS_FULFILLMENT);
        assertThat(query.statuses())
                .containsExactly(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING);
        assertThat(query.referenceId()).isEqualTo(42L);
        assertThat(query.fromDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(query.toDate()).isEqualTo(LocalDate.parse("2026-10-10"));
    }
}
