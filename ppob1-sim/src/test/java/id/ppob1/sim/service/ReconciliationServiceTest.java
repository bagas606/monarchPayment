package id.ppob1.sim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import id.ppob1.sim.client.Ppob2Client;
import id.ppob1.sim.client.Ppob2Response;
import id.ppob1.sim.domain.LocalState;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.domain.ReconEntry;
import id.ppob1.sim.domain.ReconVerdict;
import id.ppob1.sim.domain.StateSource;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import id.ppob1.sim.repository.ReconEntryRepository;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The verdict matrix for PPOB1's side of reconciliation. Each case is one way a reseller's books
 * and the platform's can disagree, plus the two cases that must <em>not</em> be reported as
 * disagreements.
 */
class ReconciliationServiceTest {

    private final Ppob1TransactionRepository transactionRepository = mock(Ppob1TransactionRepository.class);
    private final ReconEntryRepository reconEntryRepository = mock(ReconEntryRepository.class);
    private final Ppob2Client ppob2Client = mock(Ppob2Client.class);

    private final ReconciliationService service =
            new ReconciliationService(transactionRepository, reconEntryRepository, ppob2Client);

    /** {@code id} is DB-generated; a {@link ReconEntry} records it, so a test leaving it null would
     * not exercise the projection the report is actually built from. */
    private static Ppob1Transaction withId(long id, Ppob1Transaction transaction) {
        try {
            Field field = Ppob1Transaction.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(transaction, id);
            return transaction;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Ppob1Transaction submitting() {
        return withId(1L, new Ppob1Transaction("PPOB1-TX-1", "MOBILE_LEGENDS",
                BigInteger.valueOf(20000), "GAMEID-1"));
    }

    private static Ppob1Transaction accepted(String ppob2State) {
        Ppob1Transaction transaction = submitting();
        transaction.acceptedByPpob2("ORD-1", ppob2State, "00020101", null);
        return transaction;
    }

    private void givenWorkingSet(Ppob1Transaction... transactions) {
        given(transactionRepository.findByReconSettledAtIsNullAndLocalStateNotIn(anyList()))
                .willReturn(List.of(transactions));
    }

    private static Ppob2Response ok(String state) {
        return new Ppob2Response(200, "{\"order_id\":\"ORD-1\",\"state\":\"" + state + "\"}", null);
    }

    private ReconEntry singleEntry(ReconciliationService.ReconRun run) {
        assertThat(run.entries()).hasSize(1);
        return run.entries().get(0);
    }

    @Test
    void aSubmitWithNoUpstreamOrderIdIsTheWorstVerdictAndIsNotInquiredAbout() {
        givenWorkingSet(submitting());

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.ORPHANED_SUBMIT);
        assertThat(entry.getVerdict().severity()).isZero();
        assertThat(entry.getDetail()).contains("Idempotency-Key");
        // There is nothing to ask about: PPOB1 cannot name the order. Calling anyway would be a
        // lookup for null.
        verify(ppob2Client, never()).getOrder(any());
    }

    @Test
    void aStaleLocalBeliefIsReportedAndRepaired() {
        // The money case: delivered upstream, still unbilled downstream.
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("SUCCESS"));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.STATE_MISMATCH);
        // The entry records the states AS COMPARED, before the repair -- otherwise a repaired row
        // would read as a false positive.
        assertThat(entry.getLocalState()).isEqualTo(LocalState.AWAITING_PAYMENT);
        assertThat(entry.getPpob2State()).isEqualTo("PAYMENT_PENDING");
        // ...and the transaction itself is corrected, because PPOB2 is authoritative for order
        // state and staying wrong is not an option.
        assertThat(transaction.getLocalState()).isEqualTo(LocalState.COMPLETED);
        assertThat(transaction.getPpob2State()).isEqualTo("SUCCESS");
        verify(transactionRepository).save(transaction);
    }

    @Test
    void anOrderPpob2DoesNotRecogniseBecomesUnknown() {
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(
                new Ppob2Response(404, "{\"error_code\":\"ORDER_NOT_FOUND\"}", null));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.MISSING_AT_PPOB2);
        assertThat(transaction.getLocalState()).isEqualTo(LocalState.UNKNOWN);
        assertThat(entry.getDetail()).contains("scopes lookups by partner");
    }

    @Test
    void aPushedTerminalStateLearnedOnlyByAskingIsFlagged() {
        // Catches the push integration having silently degraded to polling: the books are right,
        // the mechanism keeping them right has stopped. SUCCESS is a state PPOB2 does fire for, so
        // learning it by inquiry means the callback never arrived.
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        transaction.applyPpob2State("SUCCESS", StateSource.INQUIRY);
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("SUCCESS"));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.WEBHOOK_NEVER_ARRIVED);
        assertThat(entry.getVerdict().needsAttention()).isTrue();
        assertThat(entry.getDetail()).contains("push integration is not working");
    }

    /**
     * {@code EXPIRED} joined {@code PUSHED_BY_PPOB2} on 2026-10-10, when PPOB2 started notifying on
     * it. This is the discriminating case: an EXPIRED learned by <em>webhook</em> is MATCH whether
     * or not the state is in that set, so only an EXPIRED learned by inquiry can tell the two
     * configurations apart. Before the change this came back MATCH with "does not fire
     * ORDER_STATUS_CHANGED for EXPIRED" in its detail.
     */
    @Test
    void anExpiredStateLearnedOnlyByAskingIsNowFlaggedToo() {
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        transaction.applyPpob2State("EXPIRED", StateSource.INQUIRY);
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("EXPIRED"));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.WEBHOOK_NEVER_ARRIVED);
        assertThat(entry.getVerdict().needsAttention()).isTrue();
        assertThat(entry.getDetail()).contains("push integration is not working");
    }

    @Test
    void aStateVerbatimPpob2NeverPushesIsNotFlaggedAsABrokenIntegration() {
        // The scoping that keeps the verdict above meaningful, and the boundary that stops EXPIRED
        // joining the set from being read as "every terminal state is pushed now". A cancel is
        // never pushed BY DESIGN -- it is PPOB1's own request, so there is nothing to learn --
        // and flagging it would report PPOB2's documented trigger coverage as a fault on every
        // single run and bury the findings that matter.
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        transaction.applyPpob2State("CANCELLED", StateSource.INQUIRY);
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("CANCELLED"));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.MATCH);
        assertThat(entry.getVerdict().needsAttention()).isFalse();
        // The reason is recorded rather than left implicit, so the row does not look like a
        // webhook-delivered match.
        assertThat(entry.getDetail()).contains("does not fire ORDER_STATUS_CHANGED for CANCELLED");
    }

    @Test
    void aTerminalTransactionIsSettledSoLaterRunsSkipItButAnOrphanIsNot() {
        // The fix for the original design bug: the working set is keyed on "has this been
        // reconciled", not "is the order still open" -- otherwise reaching a terminal state is
        // what drops a transaction out, and WEBHOOK_NEVER_ARRIVED becomes unreachable.
        Ppob1Transaction settled = accepted("PAYMENT_PENDING");
        settled.applyPpob2State("SUCCESS", StateSource.WEBHOOK);
        givenWorkingSet(settled);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("SUCCESS"));

        service.run(false);
        assertThat(settled.getReconSettledAt()).isNotNull();

        // An orphaned submit is unresolved business, so it must stay in the working set.
        Ppob1Transaction orphan = submitting();
        givenWorkingSet(orphan);
        service.run(false);
        assertThat(orphan.getReconSettledAt()).isNull();
    }

    @Test
    void anOpenTransactionIsNotSettledEither() {
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("PAYMENT_PENDING"));

        service.run(false);

        assertThat(transaction.getReconSettledAt()).isNull();
    }

    @Test
    void aTerminalStateDeliveredByWebhookIsACleanMatch() {
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        transaction.applyPpob2State("SUCCESS", StateSource.WEBHOOK);
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("SUCCESS"));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.MATCH);
        assertThat(entry.getVerdict().needsAttention()).isFalse();
    }

    @Test
    void anAgreedNonTerminalStateIsInProgressNotAMatch() {
        // MATCH is reserved for settled business. Calling an in-flight order a match would make a
        // report's healthy count meaningless.
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(ok("PAYMENT_PENDING"));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.IN_PROGRESS);
        assertThat(entry.getVerdict().needsAttention()).isFalse();
    }

    @Test
    void anUnreachablePpob2IsNotReportedAsADiscrepancy() {
        // The most important negative case. PPOB1 learned nothing, and recording a verdict about
        // PPOB2's state on the basis of not having reached PPOB2 would be inventing evidence --
        // and would make every network blip look like a books mismatch.
        Ppob1Transaction transaction = accepted("PAYMENT_PENDING");
        givenWorkingSet(transaction);
        given(ppob2Client.getOrder("ORD-1")).willReturn(Ppob2Response.noAnswer("ResourceAccessException: timeout"));

        ReconEntry entry = singleEntry(service.run(false));

        assertThat(entry.getVerdict()).isEqualTo(ReconVerdict.IN_PROGRESS);
        assertThat(entry.getDetail()).contains("nothing learned");
        assertThat(transaction.getLocalState()).isEqualTo(LocalState.AWAITING_PAYMENT);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void aRunCountsWhatNeedsAttentionAndSortsTheWorstFirst() {
        Ppob1Transaction orphan = submitting();
        Ppob1Transaction healthy = withId(2L, new Ppob1Transaction("PPOB1-TX-2", "MOBILE_LEGENDS",
                BigInteger.valueOf(20000), "GAMEID-2"));
        healthy.acceptedByPpob2("ORD-2", "PAYMENT_PENDING", null, null);
        healthy.applyPpob2State("SUCCESS", StateSource.WEBHOOK);

        givenWorkingSet(healthy, orphan);
        given(ppob2Client.getOrder("ORD-2")).willReturn(
                new Ppob2Response(200, "{\"order_id\":\"ORD-2\",\"state\":\"SUCCESS\"}", null));

        ReconciliationService.ReconRun run = service.run(false);

        assertThat(run.examined()).isEqualTo(2);
        assertThat(run.needingAttention()).isEqualTo(1);
        assertThat(run.byVerdict())
                .containsEntry(ReconVerdict.ORPHANED_SUBMIT, 1L)
                .containsEntry(ReconVerdict.MATCH, 1L);
        // A report is read top-down by someone deciding what to do today, so the ordering is the
        // recommendation -- not the order the working set happened to arrive in.
        assertThat(run.entries().get(0).getVerdict()).isEqualTo(ReconVerdict.ORPHANED_SUBMIT);
    }

    @Test
    void alreadyReconciledTransactionsAreExcludedUnlessAskedFor() {
        givenWorkingSet();
        given(transactionRepository.findAllByOrderByIdDesc()).willReturn(List.of());

        service.run(false);
        verify(transactionRepository).findByReconSettledAtIsNullAndLocalStateNotIn(anyList());

        service.run(true);
        verify(transactionRepository).findAllByOrderByIdDesc();
    }
}
