package id.ppob1.sim.service;

import id.ppob1.sim.client.Ppob2Client;
import id.ppob1.sim.client.Ppob2Response;
import id.ppob1.sim.domain.LocalState;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.domain.StateSource;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PPOB1's sell-side flow: record the sale, submit it to PPOB2, record what came back.
 *
 * <p><b>{@link #submit} is deliberately not transactional</b>, and the two writes it performs live
 * in {@link TransactionWriter} instead. One {@code @Transactional} method wrapping the whole
 * submit would be wrong twice over: a timeout mid-call would roll the row back, leaving PPOB1 with
 * no record of a submit that may have created a payable order upstream (nothing can reconcile a
 * row that does not exist), and it would hold a database connection open for as long as another
 * company's server takes to answer. See {@link TransactionWriter} for why the split has to be
 * across a bean boundary rather than across two methods here.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final Ppob1TransactionRepository repository;
    private final TransactionWriter writer;
    private final Ppob2Client ppob2Client;

    public TransactionService(Ppob1TransactionRepository repository, TransactionWriter writer,
                               Ppob2Client ppob2Client) {
        this.repository = repository;
        this.writer = writer;
        this.ppob2Client = ppob2Client;
    }

    /**
     * Sell one top-up.
     *
     * <p>Re-submitting an existing {@code resellerTxnId} is allowed only while the earlier attempt
     * is still {@link LocalState#SUBMITTING} — the orphaned-submit recovery path, where PPOB2's
     * {@code Idempotency-Key} guarantees the original order comes back rather than a second one
     * being created. In any other state the existing transaction is returned untouched: re-sending
     * a sale PPOB2 has already acted on is how a reseller double-charges a customer, and the
     * caller retrying is far more likely than the sale genuinely needing to be made twice.
     */
    public Ppob1Transaction submit(String resellerTxnId, String productCode, long amount, String customerReference) {
        Optional<Ppob1Transaction> existing = repository.findByResellerTxnId(resellerTxnId);
        if (existing.isPresent() && existing.get().getLocalState() != LocalState.SUBMITTING) {
            log.info("reseller_txn_id {} already submitted and is {} — returning it unchanged, not re-sending",
                    resellerTxnId, existing.get().getLocalState());
            return existing.get();
        }

        Ppob1Transaction transaction = existing.orElseGet(
                () -> writer.persistSubmitting(resellerTxnId, productCode, amount, customerReference));

        Ppob2Response response = ppob2Client.createOrder(
                transaction.getResellerTxnId(), transaction.getProductCode(),
                transaction.getAmount().longValueExact(), transaction.getCustomerReference());

        return writer.applyCreateOutcome(transaction.getId(), response);
    }

    /**
     * Ask PPOB2 what it thinks (Section 23.5) and update PPOB1's belief.
     *
     * <p>A {@code 404}/{@code ORDER_NOT_FOUND} marks the transaction {@link LocalState#UNKNOWN}
     * rather than deleting it or leaving it be: PPOB2 scopes every order lookup to the calling
     * partner, so "not found" also covers "not yours", and both need a human.
     *
     * <p>Transactional here but not in {@link #submit} — the asymmetry is intentional. This method
     * reads a row, makes one call, writes the same row; if it fails, PPOB1's belief is simply
     * unchanged, which is always safe. A submit's failure is not safe to roll back, because the
     * side effect is at PPOB2.
     */
    @Transactional
    public Ppob1Transaction inquire(Long transactionId) {
        Ppob1Transaction transaction = repository.findById(transactionId).orElseThrow();
        if (transaction.getPpob2OrderId() == null) {
            log.debug("transaction {} has no upstream order id — nothing to inquire about",
                    transaction.getResellerTxnId());
            return transaction;
        }

        Ppob2Response response = ppob2Client.getOrder(transaction.getPpob2OrderId());
        if (!response.answered()) {
            log.warn("Inquiry for order {} got no answer: {}",
                    transaction.getPpob2OrderId(), response.transportError());
            return transaction;
        }
        if (response.httpStatus() == 404 || "ORDER_NOT_FOUND".equals(response.text("error_code"))) {
            transaction.markUnknown();
            return repository.save(transaction);
        }
        if (!response.isSuccess()) {
            log.warn("Inquiry for order {} answered {}: {}",
                    transaction.getPpob2OrderId(), response.httpStatus(), response.body());
            return transaction;
        }

        transaction.applyPpob2State(response.text("state"), StateSource.INQUIRY);
        return repository.save(transaction);
    }

    /**
     * Section 23.7 cancel. A refusal ({@code 409 ORDER_NOT_CANCELLABLE}) is followed by an inquiry
     * rather than assumed: PPOB2 refusing means the order has moved past a cancellable state, and
     * the actionable question is what it moved to. Recording "cancel failed" and nothing else
     * would leave PPOB1's belief stale at exactly the moment it is known to be wrong.
     */
    @Transactional
    public Ppob1Transaction cancel(Long transactionId) {
        Ppob1Transaction transaction = repository.findById(transactionId).orElseThrow();
        if (transaction.getPpob2OrderId() == null) {
            return transaction;
        }

        Ppob2Response response = ppob2Client.cancelOrder(transaction.getPpob2OrderId());
        if (response.isSuccess()) {
            transaction.applyPpob2State(response.text("state"), StateSource.INQUIRY);
            return repository.save(transaction);
        }

        log.info("Cancel refused for order {} ({}) — inquiring to find the real state",
                transaction.getPpob2OrderId(), response.errorCode());
        Ppob2Response current = ppob2Client.getOrder(transaction.getPpob2OrderId());
        if (current.isSuccess()) {
            transaction.applyPpob2State(current.text("state"), StateSource.INQUIRY);
            return repository.save(transaction);
        }
        return transaction;
    }

    @Transactional(readOnly = true)
    public Optional<Ppob1Transaction> find(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Ppob1Transaction> findByResellerTxnId(String resellerTxnId) {
        return repository.findByResellerTxnId(resellerTxnId);
    }

    @Transactional(readOnly = true)
    public List<Ppob1Transaction> findAll() {
        return repository.findAllByOrderByIdDesc();
    }
}
