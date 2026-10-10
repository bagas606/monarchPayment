package id.ppob1.sim.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One business day's gross-collected census, frozen at the moment it was computed. Maps to
 * {@code ppob1_collection_snapshot}.
 *
 * <p>Multiple snapshots per date are expected, not a bug: the transaction book mutates — a
 * reconciliation run repairs stale beliefs — so the same day legitimately yields a different figure
 * tomorrow. Each row records what PPOB1 believed at one instant, which is what is needed when PPOB2
 * disagrees with a figure quoted last week.
 */
@Entity
@Table(name = "ppob1_collection_snapshot")
public class CollectionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Stored with the figure rather than read from config at display time: the zone is what
     * decides which day a late-evening sale belongs to, so a snapshot cut under one zone and read
     * back under another would be silently incomparable. */
    @Column(name = "zone_id", nullable = false, length = 64)
    private String zoneId;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "total_gross", nullable = false, precision = 18, scale = 0)
    private BigInteger totalGross;

    @Column(name = "provisional", nullable = false)
    private boolean provisional;

    /** Set in Java, not left to the column default. With {@code insertable = false} the value
     * stayed null on the instance returned straight after {@code save()} — Hibernate does not
     * re-read the row — so the POST response silently omitted it (the app's
     * {@code default-property-inclusion: non_null} dropped the null), while a later GET of the
     * same snapshot showed it. Found by looking at the response rather than by a test. The column
     * default stays in the migration as a backstop. */
    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    /** No {@code @OrderBy("bucket ASC")}: that sorts by the stored string, so a re-read would
     * come back alphabetically while the instance returned from a fresh {@code save()} keeps
     * insertion order — the same snapshot rendering in two different orders depending on how it
     * was fetched. {@link #getLines()} imposes one order for both paths instead, and it is the
     * enum's declaration order, which runs delivered → undelivered → cannot-tell rather than
     * alphabetically. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private List<CollectionBucketLine> lines = new ArrayList<>();

    protected CollectionSnapshot() {
    }

    /**
     * Builds the census from a per-bucket tally.
     *
     * <p>Every bucket gets a line, including the empty ones. A report that omitted zero buckets
     * would make "no indeterminate transactions today" indistinguishable from "this version did not
     * look for them" — and the whole value of a census is that the absent categories are visible.
     *
     * <p>{@code provisional} is derived here rather than passed in, so it cannot disagree with the
     * lines it is supposed to describe.
     */
    public CollectionSnapshot(LocalDate businessDate, ZoneId zone, Map<CollectionBucket, BucketTally> tally,
                               Instant computedAt) {
        this.businessDate = businessDate;
        this.zoneId = zone.getId();
        this.computedAt = computedAt;

        int count = 0;
        BigInteger gross = BigInteger.ZERO;
        boolean stillMoving = false;
        for (CollectionBucket bucket : CollectionBucket.values()) {
            BucketTally line = tally.getOrDefault(bucket, BucketTally.EMPTY);
            this.lines.add(new CollectionBucketLine(bucket, line.count(), line.gross()));
            count += line.count();
            gross = gross.add(line.gross());
            if (line.count() > 0 && bucket.makesSnapshotProvisional()) {
                stillMoving = true;
            }
        }
        this.totalCount = count;
        this.totalGross = gross;
        this.provisional = stillMoving;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public String getZoneId() {
        return zoneId;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public BigInteger getTotalGross() {
        return totalGross;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    /** Always in {@link CollectionBucket} declaration order, whichever way the snapshot was
     * loaded — see the field's note on why {@code @OrderBy} is absent. */
    public List<CollectionBucketLine> getLines() {
        return lines.stream()
                .sorted(java.util.Comparator.comparing(line -> line.getBucket().ordinal()))
                .toList();
    }

    /** A bucket's contribution before it becomes a persisted line. */
    public record BucketTally(int count, BigInteger gross) {
        public static final BucketTally EMPTY = new BucketTally(0, BigInteger.ZERO);

        public BucketTally plus(BigInteger amount) {
            return new BucketTally(count + 1, gross.add(amount));
        }
    }
}
