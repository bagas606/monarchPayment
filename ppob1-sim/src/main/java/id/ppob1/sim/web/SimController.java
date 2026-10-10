package id.ppob1.sim.web;

import id.ppob1.sim.client.Ppob2Client;
import id.ppob1.sim.client.Ppob2Response;
import id.ppob1.sim.domain.InboundWebhook;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.domain.ReconEntry;
import id.ppob1.sim.domain.ReconVerdict;
import id.ppob1.sim.repository.InboundWebhookRepository;
import id.ppob1.sim.service.ReconciliationService;
import id.ppob1.sim.service.TransactionService;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator surface for driving the simulation — PPOB1's own "reseller console", under
 * {@code /sim}. Everything here is PPOB1 acting as a partner; nothing here talks to PPOB2's
 * database.
 *
 * <p>Deliberately unauthenticated: this is a single-operator simulator on a developer machine, and
 * adding a second credential scheme would only obscure the one that matters (Section 23.2, between
 * PPOB1 and PPOB2). Said out loud rather than left implied, because an unauthenticated endpoint
 * that submits orders would be a serious defect in anything shipped — see the README.
 */
@RestController
public class SimController {

    private final TransactionService transactionService;
    private final ReconciliationService reconciliationService;
    private final InboundWebhookRepository webhookRepository;
    private final Ppob2Client ppob2Client;

    public SimController(TransactionService transactionService,
                          ReconciliationService reconciliationService,
                          InboundWebhookRepository webhookRepository,
                          Ppob2Client ppob2Client) {
        this.transactionService = transactionService;
        this.reconciliationService = reconciliationService;
        this.webhookRepository = webhookRepository;
        this.ppob2Client = ppob2Client;
    }

    /** Section 23.3, passed through so an operator can see what PPOB2 will accept before
     * submitting an amount it will refuse. */
    @GetMapping("/sim/supported-amounts")
    public ResponseEntity<String> supportedAmounts(@RequestParam("product_code") String productCode) {
        Ppob2Response response = ppob2Client.supportedAmounts(productCode);
        return ResponseEntity.status(response.answered() ? response.httpStatus() : 502)
                .body(response.answered() ? response.body() : "{\"error\":\"" + response.transportError() + "\"}");
    }

    /**
     * Sell one top-up. {@code reseller_txn_id} is optional; when omitted a unique one is generated,
     * which is the right default for ad-hoc driving but <em>not</em> what a real reseller would do —
     * a generated id makes the submit non-idempotent from the caller's point of view, so a client
     * that retries this endpoint creates a second sale. A real caller supplies its own.
     */
    @PostMapping("/sim/transactions")
    public ResponseEntity<TransactionView> submit(@RequestBody SubmitRequest body) {
        String resellerTxnId = body.resellerTxnId() != null && !body.resellerTxnId().isBlank()
                ? body.resellerTxnId().trim()
                : "PPOB1-TX-" + UUID.randomUUID().toString().substring(0, 12);

        Ppob1Transaction transaction = transactionService.submit(
                resellerTxnId,
                body.productCode() == null ? "MOBILE_LEGENDS" : body.productCode(),
                body.amount(),
                body.customerReference() == null ? resellerTxnId : body.customerReference());

        return ResponseEntity.ok(TransactionView.of(transaction));
    }

    @GetMapping("/sim/transactions")
    public ResponseEntity<List<TransactionView>> list() {
        return ResponseEntity.ok(transactionService.findAll().stream().map(TransactionView::of).toList());
    }

    @GetMapping("/sim/transactions/{id}")
    public ResponseEntity<TransactionView> get(@PathVariable Long id) {
        return transactionService.find(id)
                .map(t -> ResponseEntity.ok(TransactionView.of(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Section 23.5 inquiry for one transaction — the manual version of what a recon run does for
     * all of them. */
    @PostMapping("/sim/transactions/{id}/inquire")
    public ResponseEntity<TransactionView> inquire(@PathVariable Long id) {
        if (transactionService.find(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TransactionView.of(transactionService.inquire(id)));
    }

    /** Section 23.7. */
    @PostMapping("/sim/transactions/{id}/cancel")
    public ResponseEntity<TransactionView> cancel(@PathVariable Long id) {
        if (transactionService.find(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TransactionView.of(transactionService.cancel(id)));
    }

    /** Section 23.6 payment status, passed through unmodified. PPOB1 stores no payment state of
     * its own — the QR belongs to PPOB2's gateway and PPOB1's only interest is whether the order
     * progressed, which the order endpoint already says. */
    @GetMapping("/sim/transactions/{id}/payment")
    public ResponseEntity<String> payment(@PathVariable Long id) {
        return transactionService.find(id)
                .filter(t -> t.getPpob2OrderId() != null)
                .map(t -> {
                    Ppob2Response response = ppob2Client.getPayment(t.getPpob2OrderId());
                    return ResponseEntity.status(response.answered() ? response.httpStatus() : 502)
                            .body(response.answered() ? response.body()
                                    : "{\"error\":\"" + response.transportError() + "\"}");
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Run a reconciliation pass now. {@code include_settled} re-checks terminal transactions too
     * — see {@link ReconciliationService#run}. */
    @PostMapping("/sim/recon/run")
    public ResponseEntity<ReconRunView> runRecon(
            @RequestParam(name = "include_settled", required = false, defaultValue = "false") boolean includeSettled) {
        ReconciliationService.ReconRun run = reconciliationService.run(includeSettled);
        return ResponseEntity.ok(ReconRunView.of(run));
    }

    @GetMapping("/sim/recon/{runId}")
    public ResponseEntity<List<ReconEntryView>> reconEntries(@PathVariable UUID runId) {
        return ResponseEntity.ok(reconciliationService.entriesOf(runId).stream().map(ReconEntryView::of).toList());
    }

    /** Every callback PPOB1 has received, newest first — including the rejected ones, which is the
     * point. An empty list after a terminal order is itself the finding. */
    @GetMapping("/sim/webhooks")
    public ResponseEntity<List<WebhookView>> webhooks() {
        return ResponseEntity.ok(webhookRepository.findAllByOrderByIdDesc().stream().map(WebhookView::of).toList());
    }

    public record SubmitRequest(String resellerTxnId, String productCode, long amount, String customerReference) {
    }

    public record TransactionView(
            Long id,
            String resellerTxnId,
            String productCode,
            BigInteger amount,
            String customerReference,
            String ppob2OrderId,
            String localState,
            String ppob2State,
            String lastSource,
            Instant lastSyncedAt,
            String qrPayload,
            Instant qrExpiresAt,
            String rejectionCode,
            String rejectionMessage,
            Instant reconSettledAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        static TransactionView of(Ppob1Transaction t) {
            return new TransactionView(t.getId(), t.getResellerTxnId(), t.getProductCode(), t.getAmount(),
                    t.getCustomerReference(), t.getPpob2OrderId(), t.getLocalState().name(), t.getPpob2State(),
                    t.getLastSource() == null ? null : t.getLastSource().name(), t.getLastSyncedAt(),
                    t.getQrPayload(), t.getQrExpiresAt(), t.getRejectionCode(), t.getRejectionMessage(),
                    t.getReconSettledAt(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record ReconRunView(
            UUID runId,
            Instant runAt,
            int examined,
            long needingAttention,
            Map<String, Long> byVerdict,
            List<ReconEntryView> entries
    ) {
        static ReconRunView of(ReconciliationService.ReconRun run) {
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            for (ReconVerdict verdict : ReconVerdict.values()) {
                Long count = run.byVerdict().get(verdict);
                if (count != null) {
                    counts.put(verdict.name(), count);
                }
            }
            return new ReconRunView(run.runId(), run.runAt(), run.examined(), run.needingAttention(),
                    counts, run.entries().stream().map(ReconEntryView::of).toList());
        }
    }

    public record ReconEntryView(
            Long transactionId,
            String resellerTxnId,
            String ppob2OrderId,
            String localState,
            String ppob2State,
            String verdict,
            boolean needsAttention,
            String detail
    ) {
        static ReconEntryView of(ReconEntry e) {
            return new ReconEntryView(e.getTransactionId(), e.getResellerTxnId(), e.getPpob2OrderId(),
                    e.getLocalState().name(), e.getPpob2State(), e.getVerdict().name(),
                    e.getVerdict().needsAttention(), e.getDetail());
        }
    }

    public record WebhookView(
            Long id,
            String eventType,
            String ppob2OrderId,
            String state,
            String nonce,
            boolean signatureValid,
            String verdict,
            Instant receivedAt
    ) {
        static WebhookView of(InboundWebhook w) {
            return new WebhookView(w.getId(), w.getEventType(), w.getPpob2OrderId(), w.getState(),
                    w.getNonce(), w.isSignatureValid(), w.getVerdict().name(), w.getReceivedAt());
        }
    }
}
