package id.ppob1.sim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One transaction's result in one recon run. Maps to {@code ppob1_recon_entry}.
 *
 * <p>{@code localState} and {@code ppob2State} record the values <em>as compared</em>, before the
 * run applied any correction. Without that, a run that fixed a mismatch would leave a row saying
 * {@code STATE_MISMATCH} whose two state columns agree — which reads like a bug in the recon logic
 * rather than like a repaired discrepancy.
 */
@Entity
@Table(name = "ppob1_recon_entry")
public class ReconEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "reseller_txn_id", nullable = false, length = 64)
    private String resellerTxnId;

    @Column(name = "ppob2_order_id", length = 64)
    private String ppob2OrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "local_state", nullable = false, length = 32)
    private LocalState localState;

    @Column(name = "ppob2_state", length = 32)
    private String ppob2State;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 32)
    private ReconVerdict verdict;

    @Column(name = "detail")
    private String detail;

    protected ReconEntry() {
    }

    public ReconEntry(UUID runId, Instant runAt, Ppob1Transaction transaction, LocalState comparedLocalState,
                       String comparedPpob2State, ReconVerdict verdict, String detail) {
        this.runId = runId;
        this.runAt = runAt;
        this.transactionId = transaction.getId();
        this.resellerTxnId = transaction.getResellerTxnId();
        this.ppob2OrderId = transaction.getPpob2OrderId();
        this.localState = comparedLocalState;
        this.ppob2State = comparedPpob2State;
        this.verdict = verdict;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public Instant getRunAt() {
        return runAt;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getResellerTxnId() {
        return resellerTxnId;
    }

    public String getPpob2OrderId() {
        return ppob2OrderId;
    }

    public LocalState getLocalState() {
        return localState;
    }

    public String getPpob2State() {
        return ppob2State;
    }

    public ReconVerdict getVerdict() {
        return verdict;
    }

    public String getDetail() {
        return detail;
    }
}
