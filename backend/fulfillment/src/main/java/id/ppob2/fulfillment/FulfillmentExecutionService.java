package id.ppob2.fulfillment;

import id.ppob2.order.ChildOrderService;
import id.ppob2.order.ParentOrderTransitionService;
import id.ppob2.provider.GameProvider;
import id.ppob2.provider.PurchaseRequest;
import id.ppob2.provider.PurchaseResult;
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
     * terminal FAILED response is not retried. No inquiry-before-retry step for ambiguous
     * failures (there is no such failure class in this stub); flagged as a gap. */
    private PurchaseResult executeWithRetry(ResolvedChildOrder command, String idempotencyKey) {
        PurchaseResult result = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            result = gameProvider.purchase(new PurchaseRequest(command.providerSkuId(), command.quantity(), idempotencyKey));
            if (!result.isRetryable()) {
                return result;
            }
            log.warn("Provider purchase attempt {}/{} timed out for child_order {} (idempotency_key={}), retrying",
                    attempt, MAX_ATTEMPTS, command.childOrderId(), idempotencyKey);
            backoff(attempt);
        }
        return result;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
