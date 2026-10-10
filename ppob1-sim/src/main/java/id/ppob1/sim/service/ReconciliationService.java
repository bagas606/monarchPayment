package id.ppob1.sim.service;

import id.ppob1.sim.client.Ppob2Client;
import id.ppob1.sim.client.Ppob2Response;
import id.ppob1.sim.domain.LocalState;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.domain.ReconEntry;
import id.ppob1.sim.domain.ReconVerdict;
import id.ppob1.sim.domain.StateSource;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import id.ppob1.sim.repository.ReconEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PPOB1's reconciliation: compare its own books against what PPOB2 says, transaction by
 * transaction, and record every disagreement.
 *
 * <p>This is the mirror image of PPOB2's own {@code reconciliation} module, and deliberately so —
 * the point of a two-sided simulation is that <em>both</em> parties keep books and neither gets to
 * treat the other's as authoritative. PPOB2 reconciles payment against PG, order against
 * fulfillment, margin expected against actual. PPOB1 can only reconcile one thing, because one
 * thing is all the Section 23 contract exposes: <b>its belief about an order against PPOB2's
 * answer for that order.</b>
 *
 * <p><b>It asks over HTTP, every time, for every open transaction.</b> There is no shortcut to
 * PPOB2's database even though both happen to run on one developer machine — a recon that queried
 * PPOB2's tables directly would verify nothing about the integration, which is the only thing that
 * can actually be wrong in production. That is also why the two systems have separate databases.
 *
 * <p><b>It repairs as it goes, and records what it saw before repairing.</b> A mismatch means
 * PPOB1's belief is stale, and PPOB2 is authoritative for order state by definition, so leaving
 * the stale value in place would be choosing to stay wrong. The {@link ReconEntry} keeps the
 * pre-repair pair, otherwise a repaired row would read as a false positive.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /**
     * Nothing to ask PPOB2 about at all: {@code REJECTED} means PPOB2 refused the order outright,
     * so no upstream order was created and an inquiry would be asking after something that never
     * existed.
     *
     * <p>This list is deliberately NOT "every terminal state". That was the first version, and it
     * made {@link ReconVerdict#WEBHOOK_NEVER_ARRIVED} almost unreachable, since reaching a terminal
     * state is exactly what dropped a transaction out of the working set. A settled sale still
     * needs looking at once — to confirm PPOB2 agrees, and to notice if PPOB1 was never told.
     * Whether that has happened is tracked by {@code recon_settled_at}, not inferred from the
     * order's state.
     */
    private static final List<LocalState> NOTHING_TO_ASK = List.of(LocalState.REJECTED);

    /**
     * The terminal states PPOB2 actually fires {@code ORDER_STATUS_CHANGED} for — its
     * {@code OutboundWebhookOrchestrator} triggers only on the post-dispatch outcomes.
     *
     * <p>This is what makes {@link ReconVerdict#WEBHOOK_NEVER_ARRIVED} mean "the push integration
     * is broken" rather than "PPOB2 does not push this state". {@code CANCELLED}, {@code EXPIRED},
     * {@code REFUND_EXPECTED} and {@code REFUNDED} are never pushed by design, so flagging them
     * would report PPOB2's documented trigger coverage as a fault on every single run — noise that
     * would bury the findings that matter. If PPOB2 widens its coverage, this set widens with it.
     */
    private static final List<LocalState> PUSHED_BY_PPOB2 = List.of(
            LocalState.COMPLETED, LocalState.PARTIALLY_FAILED, LocalState.FAILED);

    private final Ppob1TransactionRepository transactionRepository;
    private final ReconEntryRepository reconEntryRepository;
    private final Ppob2Client ppob2Client;

    public ReconciliationService(Ppob1TransactionRepository transactionRepository,
                                  ReconEntryRepository reconEntryRepository,
                                  Ppob2Client ppob2Client) {
        this.transactionRepository = transactionRepository;
        this.reconEntryRepository = reconEntryRepository;
        this.ppob2Client = ppob2Client;
    }

    /**
     * Run one reconciliation pass over everything not yet reconciled.
     *
     * <p>{@code includeSettled} re-examines transactions a previous run already settled. Off by
     * default because it is O(all history) and normally there is nothing to learn; worth switching
     * on after an incident at PPOB2, when a state PPOB1 recorded as final may have been corrected
     * upstream — a refund executed against an order PPOB1 had already written off, for instance.
     */
    @Transactional
    public ReconRun run(boolean includeSettled) {
        UUID runId = UUID.randomUUID();
        Instant runAt = Instant.now();

        List<Ppob1Transaction> working = includeSettled
                ? transactionRepository.findAllByOrderByIdDesc()
                : transactionRepository.findByReconSettledAtIsNullAndLocalStateNotIn(NOTHING_TO_ASK);

        List<ReconEntry> entries = new ArrayList<>();
        for (Ppob1Transaction transaction : working) {
            entries.add(reconcileOne(runId, runAt, transaction));
        }

        reconEntryRepository.saveAll(entries);
        entries.sort(Comparator.comparingInt((ReconEntry e) -> e.getVerdict().severity())
                .thenComparing(ReconEntry::getResellerTxnId));

        Map<ReconVerdict, Long> byVerdict = new EnumMap<>(ReconVerdict.class);
        for (ReconEntry entry : entries) {
            byVerdict.merge(entry.getVerdict(), 1L, Long::sum);
        }
        long needingAttention = entries.stream().filter(e -> e.getVerdict().needsAttention()).count();

        log.info("Recon run {} examined {} transaction(s); {} need attention: {}",
                runId, entries.size(), needingAttention, byVerdict);
        return new ReconRun(runId, runAt, entries.size(), needingAttention, byVerdict, entries);
    }

    private ReconEntry reconcileOne(UUID runId, Instant runAt, Ppob1Transaction transaction) {
        LocalState believed = transaction.getLocalState();

        // No upstream id: the submit never completed. PPOB1 cannot ask about an order it cannot
        // name, so this is reported rather than resolved -- and it is reported first, because an
        // order may exist upstream with a QR a customer can pay.
        if (transaction.getPpob2OrderId() == null) {
            return new ReconEntry(runId, runAt, transaction, believed, transaction.getPpob2State(),
                    ReconVerdict.ORPHANED_SUBMIT,
                    "No upstream order_id. Re-submit under the same reseller_txn_id — PPOB2's "
                            + "Idempotency-Key returns the original order instead of creating a second.");
        }

        Ppob2Response response = ppob2Client.getOrder(transaction.getPpob2OrderId());

        if (!response.answered()) {
            // Explicitly NOT a discrepancy. PPOB1 learned nothing, and recording a verdict about
            // PPOB2's state on the basis of not having reached PPOB2 would be inventing evidence.
            return new ReconEntry(runId, runAt, transaction, believed, transaction.getPpob2State(),
                    ReconVerdict.IN_PROGRESS,
                    "PPOB2 unreachable this run, nothing learned: " + response.transportError());
        }

        if (response.httpStatus() == 404 || "ORDER_NOT_FOUND".equals(response.text("error_code"))) {
            transaction.markUnknown();
            transactionRepository.save(transaction);
            return new ReconEntry(runId, runAt, transaction, believed, transaction.getPpob2State(),
                    ReconVerdict.MISSING_AT_PPOB2,
                    "PPOB2 does not recognise order " + transaction.getPpob2OrderId()
                            + ". Note PPOB2 scopes lookups by partner, so this also covers 'not ours'.");
        }

        if (!response.isSuccess()) {
            return new ReconEntry(runId, runAt, transaction, believed, transaction.getPpob2State(),
                    ReconVerdict.IN_PROGRESS,
                    "PPOB2 answered " + response.httpStatus() + " (" + response.errorCode() + "), state not learned");
        }

        String upstreamState = response.text("state");
        LocalState upstreamAsLocal = LocalState.fromPpob2State(upstreamState);
        String priorPpob2State = transaction.getPpob2State();
        StateSource priorSource = transaction.getLastSource();

        if (upstreamAsLocal != believed) {
            transaction.applyPpob2State(upstreamState, StateSource.INQUIRY);
            transactionRepository.save(transaction);
            return new ReconEntry(runId, runAt, transaction, believed, priorPpob2State,
                    ReconVerdict.STATE_MISMATCH,
                    "PPOB1 believed " + believed + "; PPOB2 reports " + upstreamState
                            + " (mapped to " + upstreamAsLocal + "). PPOB1 corrected to match — PPOB2 is "
                            + "authoritative for order state.");
        }

        // The states agree. Keep the fact that we re-confirmed it, so last_synced_at advances and a
        // later run can tell a fresh agreement from a stale one.
        transaction.applyPpob2State(upstreamState, StateSource.INQUIRY);
        transactionRepository.save(transaction);

        if (!upstreamAsLocal.isTerminal()) {
            // Still open upstream, so nothing is settled: the next run must look again.
            return new ReconEntry(runId, runAt, transaction, believed, upstreamState,
                    ReconVerdict.IN_PROGRESS, "Agreed, still open upstream");
        }

        // Agreed and terminal: nothing further can be learned about this transaction, whichever
        // verdict follows. Settled before returning so later runs stay proportional to open
        // business -- including for WEBHOOK_NEVER_ARRIVED, which is a fact once reported rather
        // than a condition to re-detect hourly.
        transaction.reconSettled(runAt);
        transactionRepository.save(transaction);

        // The remaining question is whether PPOB2 ever told us, or we only found out by asking --
        // which is how a push integration silently degrading to polling gets caught. priorSource is
        // read BEFORE this run's own INQUIRY overwrote it.
        //
        // Scoped to the states PPOB2 actually pushes. Without that scoping every cancelled or
        // expired order would be flagged on every run, because PPOB2 does not fire for those by
        // design -- reporting its documented trigger coverage as a fault, forever.
        if (PUSHED_BY_PPOB2.contains(upstreamAsLocal) && priorSource != StateSource.WEBHOOK) {
            return new ReconEntry(runId, runAt, transaction, believed, upstreamState,
                    ReconVerdict.WEBHOOK_NEVER_ARRIVED,
                    "Terminal state " + upstreamState + " agreed, but PPOB1 never received a Section 23.8 "
                            + "callback for it (learned via " + priorSource + "). PPOB2 does fire "
                            + "ORDER_STATUS_CHANGED for this state, so the push integration is not working.");
        }

        if (!PUSHED_BY_PPOB2.contains(upstreamAsLocal) && priorSource != StateSource.WEBHOOK) {
            return new ReconEntry(runId, runAt, transaction, believed, upstreamState,
                    ReconVerdict.MATCH,
                    "Agreed and terminal. Learned via " + priorSource + " rather than a webhook, which is "
                            + "expected: PPOB2 does not fire ORDER_STATUS_CHANGED for " + upstreamState + ".");
        }

        return new ReconEntry(runId, runAt, transaction, believed, upstreamState,
                ReconVerdict.MATCH, "Agreed, terminal, and delivered by webhook");
    }

    @Transactional(readOnly = true)
    public List<ReconEntry> entriesOf(UUID runId) {
        return reconEntryRepository.findByRunIdOrderByVerdictAscIdAsc(runId);
    }

    /**
     * One run's outcome. {@code needingAttention} is surfaced separately from the per-verdict
     * counts so a caller (or a script) has a single number to assert on or alert against, rather
     * than having to know which verdicts are the bad ones.
     */
    public record ReconRun(
            UUID runId,
            Instant runAt,
            int examined,
            long needingAttention,
            Map<ReconVerdict, Long> byVerdict,
            List<ReconEntry> entries
    ) {
    }
}
