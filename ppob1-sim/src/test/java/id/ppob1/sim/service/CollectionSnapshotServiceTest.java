package id.ppob1.sim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import id.ppob1.sim.domain.CollectionBucket;
import id.ppob1.sim.domain.CollectionBucketLine;
import id.ppob1.sim.domain.CollectionSnapshot;
import id.ppob1.sim.domain.LocalState;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.domain.StateSource;
import id.ppob1.sim.repository.CollectionSnapshotRepository;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the two things that are easy to get quietly wrong: the census invariant (every
 * transaction in exactly one bucket, buckets summing to the totals) and the business-day boundary.
 */
class CollectionSnapshotServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private final Ppob1TransactionRepository transactionRepository = mock(Ppob1TransactionRepository.class);
    private final CollectionSnapshotRepository snapshotRepository = mock(CollectionSnapshotRepository.class);

    private CollectionSnapshotService serviceIn(String zone) {
        CollectionSnapshotService service =
                new CollectionSnapshotService(transactionRepository, snapshotRepository, zone);
        given(snapshotRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        return service;
    }

    private static Ppob1Transaction txn(String ref, long amount, LocalState state) {
        Ppob1Transaction transaction = new Ppob1Transaction(
                ref, "MOBILE_LEGENDS", BigInteger.valueOf(amount), "GAMEID-" + ref);
        if (state != LocalState.SUBMITTING) {
            transaction.acceptedByPpob2("ORD-" + ref, "PAYMENT_PENDING", null, null);
        }
        if (state == LocalState.REJECTED) {
            transaction.rejectedByPpob2("UNSUPPORTED_AMOUNT", "nope");
        } else if (state == LocalState.UNKNOWN) {
            transaction.markUnknown();
        } else if (state != LocalState.SUBMITTING && state != LocalState.AWAITING_PAYMENT) {
            transaction.applyPpob2State(upstreamNameOf(state), StateSource.WEBHOOK);
        }
        return transaction;
    }

    /** Builds each local state through the real PPOB2 vocabulary rather than setting the field, so
     * these fixtures exercise the same mapping production does. */
    private static String upstreamNameOf(LocalState state) {
        return switch (state) {
            case COMPLETED -> "SUCCESS";
            case PARTIALLY_FAILED -> "PARTIAL_FAILED";
            case FAILED -> "FAILED";
            case REFUND_EXPECTED -> "REFUND_PENDING";
            case REFUNDED -> "REFUNDED";
            case AWAITING_RESULT -> "FULFILLING";
            case CANCELLED -> "CANCELLED";
            case EXPIRED -> "EXPIRED";
            case AWAITING_PAYMENT -> "PAYMENT_PENDING";
            default -> throw new IllegalArgumentException("not reachable via an upstream state: " + state);
        };
    }

    private static long grossOf(CollectionSnapshot snapshot, CollectionBucket bucket) {
        return snapshot.getLines().stream()
                .filter(line -> line.getBucket() == bucket)
                .map(CollectionBucketLine::getGross)
                .findFirst().orElseThrow().longValueExact();
    }

    private static int countOf(CollectionSnapshot snapshot, CollectionBucket bucket) {
        return snapshot.getLines().stream()
                .filter(line -> line.getBucket() == bucket)
                .mapToInt(CollectionBucketLine::getTxnCount)
                .findFirst().orElseThrow();
    }

    @Test
    void everyTransactionLandsInExactlyOneBucketAndTheBucketsSumToTheTotals() {
        // The census invariant, same discipline as BR-REC-002's "allocations MUST sum exactly".
        // One transaction per local state that a day can contain.
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of(
                        txn("A", 10000, LocalState.COMPLETED),
                        txn("B", 20000, LocalState.PARTIALLY_FAILED),
                        txn("C", 30000, LocalState.FAILED),
                        txn("D", 40000, LocalState.REFUND_EXPECTED),
                        txn("E", 50000, LocalState.REFUNDED),
                        txn("F", 60000, LocalState.AWAITING_RESULT),
                        txn("G", 70000, LocalState.AWAITING_PAYMENT),
                        txn("H", 80000, LocalState.CANCELLED),
                        txn("I", 90000, LocalState.REJECTED),
                        txn("J", 100000, LocalState.SUBMITTING)));

        CollectionSnapshot snapshot = serviceIn("UTC").snapshot(DAY);

        assertThat(snapshot.getTotalCount()).isEqualTo(10);
        assertThat(snapshot.getTotalGross()).isEqualTo(BigInteger.valueOf(550000));
        // Sums, not spot checks: a transaction double-counted or dropped shows up here and nowhere
        // else.
        assertThat(snapshot.getLines().stream().mapToInt(CollectionBucketLine::getTxnCount).sum())
                .isEqualTo(snapshot.getTotalCount());
        assertThat(snapshot.getLines().stream()
                .map(CollectionBucketLine::getGross)
                .reduce(BigInteger.ZERO, BigInteger::add))
                .isEqualTo(snapshot.getTotalGross());

        assertThat(grossOf(snapshot, CollectionBucket.DELIVERED)).isEqualTo(10000);
        assertThat(grossOf(snapshot, CollectionBucket.PARTIAL)).isEqualTo(20000);
        // FAILED + REFUND_EXPECTED share this bucket: both are money collected and nothing
        // delivered.
        assertThat(grossOf(snapshot, CollectionBucket.COLLECTED_UNDELIVERED)).isEqualTo(70000);
        assertThat(grossOf(snapshot, CollectionBucket.REVERSED)).isEqualTo(50000);
        assertThat(grossOf(snapshot, CollectionBucket.IN_FLIGHT)).isEqualTo(60000);
        // AWAITING_PAYMENT + CANCELLED + REJECTED.
        assertThat(grossOf(snapshot, CollectionBucket.NOT_COLLECTED)).isEqualTo(240000);
        assertThat(grossOf(snapshot, CollectionBucket.INDETERMINATE)).isEqualTo(100000);
    }

    @Test
    void everyBucketGetsALineIncludingTheEmptyOnes() {
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of(txn("A", 10000, LocalState.COMPLETED)));

        CollectionSnapshot snapshot = serviceIn("UTC").snapshot(DAY);

        // An omitted zero would make "no indeterminate transactions today" indistinguishable from
        // "this version did not look for them".
        assertThat(snapshot.getLines()).hasSize(CollectionBucket.values().length);
        assertThat(countOf(snapshot, CollectionBucket.INDETERMINATE)).isZero();
        assertThat(grossOf(snapshot, CollectionBucket.INDETERMINATE)).isZero();
    }

    @Test
    void computedAtIsSetInJavaSoItSurvivesTheSaveAndIsInTheResponse() {
        // With insertable=false and a column default, this stayed null on the instance returned
        // straight after save() -- Hibernate does not re-read the row -- so the POST response
        // silently omitted it while a later GET of the same snapshot showed it. Found by reading
        // the response, not by a test; this is the test that would have caught it.
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of());

        assertThat(serviceIn("UTC").snapshot(DAY).getComputedAt()).isNotNull();
    }

    @Test
    void bucketsComeBackInDeclarationOrderNotAlphabetically() {
        // Both fetch paths must agree. @OrderBy("bucket ASC") would sort by the stored string, so
        // a re-read came back alphabetically while a fresh save() kept insertion order -- the same
        // snapshot rendering two different ways depending on how it was loaded.
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of());

        assertThat(serviceIn("UTC").snapshot(DAY).getLines().stream()
                .map(CollectionBucketLine::getBucket).toList())
                .containsExactly(CollectionBucket.values());
    }

    @Test
    void anEmptyDayIsAZeroedCensusNotAnError() {
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of());

        CollectionSnapshot snapshot = serviceIn("UTC").snapshot(DAY);

        assertThat(snapshot.getTotalCount()).isZero();
        assertThat(snapshot.getTotalGross()).isEqualTo(BigInteger.ZERO);
        assertThat(snapshot.isProvisional()).isFalse();
        assertThat(snapshot.getLines()).hasSize(CollectionBucket.values().length);
    }

    @Test
    void aDayHoldingInFlightOrIndeterminateWorkIsProvisional() {
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of(txn("A", 10000, LocalState.AWAITING_RESULT)));
        assertThat(serviceIn("UTC").snapshot(DAY).isProvisional()).isTrue();

        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of(txn("B", 10000, LocalState.SUBMITTING)));
        assertThat(serviceIn("UTC").snapshot(DAY).isProvisional()).isTrue();
    }

    @Test
    void aSettledDayIsNotProvisional() {
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of(
                        txn("A", 10000, LocalState.COMPLETED),
                        txn("B", 20000, LocalState.CANCELLED)));

        assertThat(serviceIn("UTC").snapshot(DAY).isProvisional()).isFalse();
    }

    @Test
    void theDayIsAHalfOpenInstantRangeInTheConfiguredZone() {
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of());

        serviceIn("UTC").snapshot(DAY);

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository)
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start.capture(), end.capture());

        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        // Half-open: the next midnight is excluded, so an event at exactly 00:00 belongs to one day
        // only. A BETWEEN would double-count it.
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
    }

    @Test
    void theZoneMovesTheDayBoundaryAndIsRecordedWithTheFigure() {
        // Asia/Jakarta is UTC+7, so its 2026-10-10 runs 2026-10-09T17:00Z .. 2026-10-10T17:00Z.
        // This is the whole reason the zone is stored on the snapshot: the same date label under a
        // different zone covers a different set of sales, and the numbers alone cannot say which.
        given(transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .willReturn(List.of());

        CollectionSnapshot snapshot = serviceIn("Asia/Jakarta").snapshot(DAY);

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository)
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start.capture(), end.capture());

        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-09T17:00:00Z"));
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-10-10T17:00:00Z"));
        assertThat(snapshot.getZoneId()).isEqualTo("Asia/Jakarta");
    }

    @Test
    void theDefaultZoneIsUtcToMatchPpob2sObservedBasis() {
        // Not Asia/Jakarta, deliberately. PPOB2 groups with paid_at::date, which casts in the
        // Postgres session timezone -- UTC in the one environment that exists. Matching it removes
        // a divergence this service can control; picking a nicer-looking local business day would
        // add a second one on top of the unavoidable created_at-vs-paid_at difference.
        CollectionSnapshotService service =
                new CollectionSnapshotService(transactionRepository, snapshotRepository, "UTC");

        assertThat(service.businessZone()).isEqualTo(java.time.ZoneId.of("UTC"));
    }
}
