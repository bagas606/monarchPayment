package id.ppob1.sim.repository;

import id.ppob1.sim.domain.LocalState;
import id.ppob1.sim.domain.Ppob1Transaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Ppob1TransactionRepository extends JpaRepository<Ppob1Transaction, Long> {

    Optional<Ppob1Transaction> findByResellerTxnId(String resellerTxnId);

    Optional<Ppob1Transaction> findByPpob2OrderId(String ppob2OrderId);

    /**
     * A recon run's working set: everything not yet reconciled, excluding the states there is
     * nothing to ask PPOB2 about. {@code REJECTED} is the main one — no upstream order was ever
     * created, so an inquiry would be asking about an order that does not exist.
     *
     * <p>Keyed on {@code reconSettledAt}, not on whether the order is still open. The first
     * version of this query excluded every terminal state, which made
     * {@code ReconVerdict.WEBHOOK_NEVER_ARRIVED} almost unreachable — reaching a terminal state is
     * exactly what dropped a transaction out. See {@code V2__recon_settled_at.sql}.
     */
    List<Ppob1Transaction> findByReconSettledAtIsNullAndLocalStateNotIn(List<LocalState> states);

    List<Ppob1Transaction> findAllByOrderByIdDesc();

    /**
     * One business day's transactions, as a half-open {@code [start, end)} instant range.
     *
     * <p>A range rather than {@code created_at::date = :date} on purpose: that cast runs in the
     * Postgres <em>session</em> timezone, which the JDBC driver takes from the JVM default, so the
     * same data would group differently depending on which host ran the query. Verified directly —
     * {@code 2026-10-10T23:30:00Z} casts to {@code 2026-10-11} under {@code Asia/Jakarta} and
     * {@code 2026-10-10} under {@code UTC}. {@code CollectionSnapshotService} resolves the day in
     * an explicit zone instead, so the boundary is a decision in code rather than a property of
     * the deployment. Half-open so an event at exactly midnight belongs to one day only.
     */
    List<Ppob1Transaction> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant start, Instant end);
}
