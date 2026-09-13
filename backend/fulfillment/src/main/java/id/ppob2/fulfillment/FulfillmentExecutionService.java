package id.ppob2.fulfillment;

import id.ppob2.order.ChildOrderService;
import id.ppob2.order.ParentOrderTransitionService;
import id.ppob2.provider.GameProvider;
import id.ppob2.provider.InquiryResult;
import id.ppob2.provider.PurchaseRequest;
import id.ppob2.provider.PurchaseResult;
import id.ppob2.provider.PurchaseStatus;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PRD Section 34: executes every child order for a parent order once {@code order} has created
 * them and moved to {@code FULFILLING} (Section 33.2). Deliberately NOT itself
 * {@code @Transactional} — the actual provider call ({@link #executeWithRetry}) is external I/O
 * and must not hold a database transaction/connection open for its duration (worse under Section
 * 27.2's bounded retries with backoff). Persistence happens in short, separate
 * {@code REQUIRES_NEW} calls into {@code order}'s {@link ChildOrderService} before and after the
 * call, and into {@link ProviderTransactionRecorder} for the transaction record itself — that
 * recorder exists as its own bean (not a private method here) because its write is a
 * {@code @Modifying} native query, which throws {@code TransactionRequiredException} without an
 * active transaction; a private/self-invoked {@code @Transactional} method on this class
 * wouldn't work either, since self-invocation bypasses Spring's proxy.
 *
 * <p>Called from the `app`-layer listener on {@code ChildOrdersReadyEvent}, which is itself a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — every write this class triggers,
 * directly or via {@code order}'s services, uses {@code REQUIRES_NEW} for the reason documented
 * on {@code ParentOrderTransitionService.markPaid}: this codebase proved twice that a plain
 * {@code @Transactional} anywhere in an AFTER_COMMIT callback's call tree silently no-ops.
 *
 * <p>Sequential dispatch only — Section 34.1's default is "sequential-per-provider, parallel-
 * across-providers"; this slice does neither concurrency control nor per-provider rate limiting
 * (Section 27.2), flagged as a gap in the README rather than built, since there's no second real
 * provider yet to make the distinction meaningful.
 */
@Service
public class FulfillmentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentExecutionService.class);

    /** Section 27.2: "max 2 additional attempts" — 1 initial + 2 retries. */
    private static final int MAX_ATTEMPTS = 3;

    private final GameProvider gameProvider;
    private final ProviderTransactionRecorder providerTransactionRecorder;
    private final ProviderLedgerPoster providerLedgerPoster;
    private final ChildOrderService childOrderService;
    private final ParentOrderTransitionService parentOrderTransitionService;

    public FulfillmentExecutionService(GameProvider gameProvider,
                                        ProviderTransactionRecorder providerTransactionRecorder,
                                        ProviderLedgerPoster providerLedgerPoster,
                                        ChildOrderService childOrderService,
                                        ParentOrderTransitionService parentOrderTransitionService) {
        this.gameProvider = gameProvider;
        this.providerTransactionRecorder = providerTransactionRecorder;
        this.providerLedgerPoster = providerLedgerPoster;
        this.childOrderService = childOrderService;
        this.parentOrderTransitionService = parentOrderTransitionService;
    }

    public void dispatch(Long parentOrderId, List<ResolvedChildOrder> resolved, List<Long> unresolvableChildOrderIds) {
        for (Long childOrderId : unresolvableChildOrderIds) {
            log.error("child_order {} references a provider_sku that could not be resolved at dispatch time "
                    + "(vanished between pattern selection and dispatch?) — marking FAILED without calling any provider", childOrderId);
            childOrderService.markUnresolvable(childOrderId);
        }

        for (ResolvedChildOrder command : resolved) {
            processOne(command);
        }

        parentOrderTransitionService.completeFulfillment(parentOrderId);
    }

    private void processOne(ResolvedChildOrder command) {
        Optional<Integer> attemptNumber = childOrderService.markExecuting(command.childOrderId());
        if (attemptNumber.isEmpty()) {
            return;
        }

        // The idempotency key is scoped to this *dispatch attempt* (Section 33.2's
        // PARTIAL_FAILED -> SUCCESS authorized retry is a later, separate dispatch attempt for
        // the same child order and must get its own provider_transaction row, not silently
        // no-op against the earlier failed attempt's key via the ON CONFLICT DO NOTHING below).
        // Section 27.2's *internal* timeout/5xx retries below reuse this same key on purpose —
        // that's the correct idempotent-retry semantics: same logical purchase attempt, retried.
        String idempotencyKey = "child-" + command.childOrderId() + "-attempt-" + attemptNumber.get();

        PurchaseResult result = executeWithRetry(command, idempotencyKey);

        RecordedProviderTransaction recorded = providerTransactionRecorder.record(
                command.providerSkuId(), command.quantity(), command.childOrderId(), command.providerId(), idempotencyKey, result);

        // Only the dispatch attempt that actually inserted the provider_transaction row posts the
        // debit — a re-entry of this same idempotency key (Section 27.2's internal retry) hits
        // ON CONFLICT DO NOTHING in insertIfAbsent and must not double-post into an append-only
        // ledger with no UPDATE/DELETE. A failed purchase debits nothing (Section 36.1).
        if (result.isSuccess() && recorded.newlyInserted()) {
            providerLedgerPoster.postPurchaseDebit(recorded.providerTransactionId(), command.providerCost(), command.quantity());
        }
        childOrderService.recordOutcome(command.childOrderId(), result.isSuccess(), recorded.providerTransactionId());
    }

    /** Bounded automatic retry only for the idempotent-safe TIMEOUT class (Section 27.2) — a
     * terminal FAILED response is not retried. An {@code AMBIGUOUS} response (Section 34.1:
     * "connection reset after the request was already sent") is never blindly retried either —
     * that risks a real double-purchase if it actually went through — it is resolved via {@link
     * #resolveAmbiguous} instead. Invariant: this method never *returns* an {@code AMBIGUOUS}
     * result — {@link #resolveAmbiguous} always converts it to a concrete {@code SUCCESS}/{@code
     * FAILED} before returning, so {@code AMBIGUOUS} never reaches {@code
     * ProviderTransactionRecorder} or any persisted column. */
    private PurchaseResult executeWithRetry(ResolvedChildOrder command, String idempotencyKey) {
        PurchaseResult result = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            result = gameProvider.purchase(new PurchaseRequest(command.providerSkuId(), command.quantity(), idempotencyKey));
            if (result.isAmbiguous()) {
                return resolveAmbiguous(command, idempotencyKey, result);
            }
            if (!result.isRetryable()) {
                return result;
            }
            log.warn("Provider purchase attempt {}/{} timed out for child_order {} (idempotency_key={}), retrying",
                    attempt, MAX_ATTEMPTS, command.childOrderId(), idempotencyKey);
            backoff(attempt);
        }
        return result;
    }

    /**
     * Section 34.1's inquiry-before-retry step. {@code idempotencyKey} is passed as the {@code
     * providerReference} argument to {@link GameProvider#inquire} — the ambiguous {@link
     * PurchaseResult} carries no provider-issued reference (the response that would have carried
     * one was lost), so the idempotency key is the only identifier both sides share to look the
     * attempt up by.
     *
     * <p>A confirmed {@code SUCCESS} is recorded exactly like any other successful purchase — the
     * same {@code provider_transaction} row + ledger debit {@link #processOne} already posts for a
     * direct success, via the normal {@code PurchaseResult.success(...)} return path, not a
     * separate "mark SUCCESS" shortcut. The provider genuinely fulfilled this purchase; skipping
     * that bookkeeping would leave a real provider charge with no {@code provider_transaction} row
     * and no ledger debit — an unaudited spend, a Section 36.1 violation. What inquiry-before-retry
     * *does* avoid is calling {@link GameProvider#purchase} a second time for the same attempt,
     * which is the actual double-purchase risk Section 34.1 is guarding against.
     *
     * <p><b>Deliberately narrower than Section 34.1's literal phrasing</b> ("inquiry-before-retry
     * ... before deciding whether to retry"): a confirmed {@code FAILED} here is treated as this
     * attempt's terminal outcome, not fed back into {@link #executeWithRetry}'s automatic-retry
     * loop, even though a retry at that point would in fact be idempotent-safe (inquiry just
     * proved nothing happened). Section 27.2's bounded automatic retries stay reserved for the
     * narrow, ordinary TIMEOUT class; an ambiguous failure is by definition an unusual event
     * (Section 34.1's own example is a connection reset) worth surfacing rather than silently
     * absorbing into another invisible auto-retry. The existing Admin Web retry action
     * ({@code AdminChildOrderRetryOrchestrator}, {@code POST /admin/child-orders/{id}/retry}) is
     * the actual recovery path for this case — the same authorized-retry mechanism Section 33.2's
     * {@code PARTIAL_FAILED -> SUCCESS} transition already uses, not a new mechanism invented here.
     *
     * <p>A {@code TIMEOUT} inquiry result means the inquiry itself was inconclusive — a third case,
     * not a confirmed failure — logged distinctly at ERROR since it needs manual review (this
     * codebase has no further automated escalation once inquiry itself fails to disambiguate); it
     * is treated the same as a confirmed {@code FAILED} for the return value, but never conflated
     * with one in the log or the recorded failure reason.
     */
    private PurchaseResult resolveAmbiguous(ResolvedChildOrder command, String idempotencyKey, PurchaseResult ambiguousResult) {
        log.warn("Ambiguous purchase result for child_order {} (idempotency_key={}): {} — querying provider via inquire() "
                        + "before deciding the outcome",
                command.childOrderId(), idempotencyKey, ambiguousResult.failureReason());

        InquiryResult inquiry = gameProvider.inquire(idempotencyKey);

        if (inquiry.status() == PurchaseStatus.SUCCESS) {
            log.info("Inquiry confirmed child_order {} (idempotency_key={}) actually succeeded (provider_reference={})",
                    command.childOrderId(), idempotencyKey, inquiry.providerReference());
            return PurchaseResult.success(inquiry.providerReference());
        }
        if (inquiry.status() == PurchaseStatus.FAILED) {
            return PurchaseResult.failed("Ambiguous purchase, inquiry confirmed not completed: " + ambiguousResult.failureReason());
        }

        log.error("Inquiry itself was inconclusive for child_order {} (idempotency_key={}) — provider could not confirm "
                        + "either way; needs manual review, not further automated retry",
                command.childOrderId(), idempotencyKey);
        return PurchaseResult.failed("Ambiguous purchase, inquiry was inconclusive (needs manual review): "
                + ambiguousResult.failureReason());
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
