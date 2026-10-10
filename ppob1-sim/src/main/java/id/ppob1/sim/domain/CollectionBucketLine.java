package id.ppob1.sim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigInteger;

/** One bucket's line within a {@link CollectionSnapshot}. Maps to {@code ppob1_collection_bucket}. */
@Entity
@Table(name = "ppob1_collection_bucket")
public class CollectionBucketLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", nullable = false, length = 32)
    private CollectionBucket bucket;

    @Column(name = "txn_count", nullable = false)
    private int txnCount;

    @Column(name = "gross", nullable = false, precision = 18, scale = 0)
    private BigInteger gross;

    protected CollectionBucketLine() {
    }

    CollectionBucketLine(CollectionBucket bucket, int txnCount, BigInteger gross) {
        this.bucket = bucket;
        this.txnCount = txnCount;
        this.gross = gross;
    }

    public Long getId() {
        return id;
    }

    public CollectionBucket getBucket() {
        return bucket;
    }

    public int getTxnCount() {
        return txnCount;
    }

    public BigInteger getGross() {
        return gross;
    }
}
