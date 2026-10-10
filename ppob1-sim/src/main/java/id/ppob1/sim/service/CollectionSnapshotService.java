package id.ppob1.sim.service;

import id.ppob1.sim.domain.CollectionBucket;
import id.ppob1.sim.domain.CollectionSnapshot;
import id.ppob1.sim.domain.CollectionSnapshot.BucketTally;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.repository.CollectionSnapshotRepository;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PPOB1's own gross-collected book: for one business day, a census of what PPOB1 believes PPOB2
 * collected on the orders PPOB1 originated.
 *
 * <p><b>One-sided by construction, and it makes no calls to PPOB2.</b> This is the half of
 * settlement reconciliation that needs no agreement between the two companies, because it is
 * computed entirely from PPOB1's own books. The other half — a figure of PPOB2's to compare it
 * against — does not exist to be fetched: PPOB2's only two settlement endpoints
 * ({@code POST /internal/settlement/ingest} and {@code GET /admin/settlements/{id}/allocations})
 * are both behind admin RBAC rather than the Section 23 Open API, and the latter returns every
 * partner's figures, so exposing it as-is would leak one reseller's volume to another. Building
 * that comparison is a contract decision, not a coding one; see {@code ppob1-sim/README.md}.
 *
 * <h2>Three known divergences from PPOB2's own day-grouping</h2>
 * Named here because the whole purpose of this figure is eventual comparability, and a figure whose
 * basis differs silently is worse than no figure at all. PPOB2 computes a settlement's
 * {@code expected_amount} with
 * {@code SELECT SUM(amount) FROM payment WHERE status='SUCCESS' AND paid_at::date = :date}.
 * <ol>
 *   <li><b>{@code created_at} vs {@code paid_at} — unavoidable.</b> PPOB1 groups by when it made
 *       the sale, because it never learns when the customer paid: Section 23.6 exposes
 *       {@code paid_at}, but nothing in the Section 23.8 callback carries it and PPOB1 stores no
 *       such field. An order sold at 23:50 and paid at 00:10 therefore lands on different days in
 *       the two systems. Closing this would mean a Section 23.6 call per transaction, which is a
 *       real option but a different design.</li>
 *   <li><b>Both sides must now be configured to the same zone — and both default to UTC.</b>
 *       PPOB2's boundary used to be whatever the deploying host's timezone was
 *       ({@code paid_at::date} casts in the Postgres session timezone, which the JDBC driver takes
 *       from the JVM default, not from {@code hibernate.jdbc.time_zone}), which is why PPOB1
 *       originally matched an <em>observed</em> accident rather than a decision. That has since
 *       been fixed on PPOB2's side — it now resolves its settlement window from an explicit
 *       {@code ppob2.business-zone}, defaulting to UTC. So this is no longer a divergence but a
 *       configuration pairing: set {@code ppob2.business-zone} and {@code ppob1.business-zone} to
 *       the same value and the day boundaries agree by construction. They are deliberately two
 *       settings, because these are two companies here and nothing enforces the pairing across the
 *       wire — which is itself worth knowing before changing either.</li>
 *   <li><b>Settlement windows are not calendar days anyway.</b> Section 37.1 flags the real T+N
 *       schedule and cutoff rules as "must be verified against latest Ayolinx contract", and
 *       PPOB2's own repository comment calls its single-calendar-day grouping "a placeholder
 *       window, not a modeled one". Both sides are approximating the same unknown.</li>
 * </ol>
 * {@code ppob1.business-zone} therefore defaults to {@code UTC}, matching PPOB2's own default so
 * the two agree out of the box. Change one and the other must change too: a Jakarta business day on
 * one side only reintroduces a seven-hour offset, on top of the unavoidable
 * {@code created_at}-vs-{@code paid_at} difference in (1).
 */
@Service
public class CollectionSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(CollectionSnapshotService.class);

    private final Ppob1TransactionRepository transactionRepository;
    private final CollectionSnapshotRepository snapshotRepository;
    private final ZoneId businessZone;

    public CollectionSnapshotService(Ppob1TransactionRepository transactionRepository,
                                      CollectionSnapshotRepository snapshotRepository,
                                      @Value("${ppob1.business-zone:UTC}") String businessZone) {
        this.transactionRepository = transactionRepository;
        this.snapshotRepository = snapshotRepository;
        this.businessZone = ZoneId.of(businessZone);
    }

    public ZoneId businessZone() {
        return businessZone;
    }

    /**
     * Computes and persists one day's census.
     *
     * <p>The day is resolved to a half-open {@code [start, end)} instant range in
     * {@link #businessZone} and queried against {@code created_at}. Deliberately not a SQL
     * {@code created_at::date = :date}: that cast runs in the session timezone, which is the exact
     * ambiguity divergence (2) above describes on PPOB2's side, and repeating it here would make
     * PPOB1's own figure depend on which host happened to run the query. Half-open rather than
     * {@code BETWEEN} so an event at exactly midnight belongs to one day only.
     */
    @Transactional
    public CollectionSnapshot snapshot(LocalDate businessDate) {
        Instant start = businessDate.atStartOfDay(businessZone).toInstant();
        Instant end = businessDate.plusDays(1).atStartOfDay(businessZone).toInstant();

        List<Ppob1Transaction> transactions =
                transactionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start, end);

        Map<CollectionBucket, BucketTally> tally = new EnumMap<>(CollectionBucket.class);
        for (Ppob1Transaction transaction : transactions) {
            CollectionBucket bucket = CollectionBucket.of(transaction.getLocalState());
            tally.compute(bucket, (key, existing) ->
                    (existing == null ? BucketTally.EMPTY : existing).plus(transaction.getAmount()));
        }

        CollectionSnapshot snapshot = snapshotRepository.save(
                new CollectionSnapshot(businessDate, businessZone, tally, Instant.now()));

        log.info("Collection snapshot for {} ({}): {} transaction(s), gross {}, provisional={}",
                businessDate, businessZone, snapshot.getTotalCount(), snapshot.getTotalGross(),
                snapshot.isProvisional());
        return snapshot;
    }

    /** The most recent snapshot for a date. Later snapshots supersede earlier ones for reading,
     * while the earlier rows stay on file as what was believed then. */
    @Transactional(readOnly = true)
    public Optional<CollectionSnapshot> latestFor(LocalDate businessDate) {
        return snapshotRepository.findFirstByBusinessDateOrderByIdDesc(businessDate);
    }

    @Transactional(readOnly = true)
    public List<CollectionSnapshot> history() {
        return snapshotRepository.findAllByOrderByIdDesc();
    }
}
