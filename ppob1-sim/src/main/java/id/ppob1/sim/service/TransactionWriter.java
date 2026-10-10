package id.ppob1.sim.service;

import com.fasterxml.jackson.databind.JsonNode;
import id.ppob1.sim.client.Ppob2Response;
import id.ppob1.sim.domain.LocalState;
import id.ppob1.sim.domain.Ppob1Transaction;
import id.ppob1.sim.repository.Ppob1TransactionRepository;
import java.math.BigInteger;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two database writes that bracket {@link TransactionService#submit}'s HTTP call, in a bean of
 * their own.
 *
 * <p><b>Why a separate bean and not two methods on {@code TransactionService}.</b> Spring's
 * transaction advice lives on a proxy, so a {@code @Transactional} method invoked as
 * {@code this.method(...)} from inside the same bean is never intercepted — the annotation is
 * silently ignored and the code runs with whatever transaction the caller had, which here is none.
 * It compiles, it passes a mocked test, and in production each write commits outside the
 * transaction it was supposed to be in. This was written that way first and caught before it ran;
 * it is the same trap PPOB2 hit with {@code AdminRefundOrchestrator}, fixed there the same way by
 * extracting {@code RefundRecorder}. Crossing a bean boundary is what makes the annotation apply
 * at all.
 *
 * <p>{@code REQUIRES_NEW} rather than the default {@code REQUIRED}: {@link TransactionService}
 * deliberately has no transaction of its own, but a future caller wrapping {@code submit} in one
 * would silently re-join these two writes to it and reintroduce exactly the "roll the row back on
 * a timeout" failure the split exists to prevent. {@code REQUIRES_NEW} makes the separation a
 * property of these methods instead of a property of their callers.
 */
@Component
public class TransactionWriter {

    private static final Logger log = LoggerFactory.getLogger(TransactionWriter.class);

    private final Ppob1TransactionRepository repository;

    public TransactionWriter(Ppob1TransactionRepository repository) {
        this.repository = repository;
    }

    /** Commits the sale in {@link LocalState#SUBMITTING} before PPOB2 is called, so that a call
     * which never answers leaves something to reconcile rather than nothing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Ppob1Transaction persistSubmitting(String resellerTxnId, String productCode, long amount,
                                               String customerReference) {
        return repository.save(new Ppob1Transaction(
                resellerTxnId, productCode, BigInteger.valueOf(amount), customerReference));
    }

    /**
     * Applies PPOB2's answer to {@code POST /api/v1/orders}.
     *
     * <p>Three outcomes, and the first two both deliberately leave the row {@code SUBMITTING}:
     * <ul>
     *   <li><b>No answer</b> (timeout, connection refused) — PPOB1 does not know whether an order
     *       exists. That is information, not an absence of it, and {@code SUBMITTING} is where it
     *       is recorded.</li>
     *   <li><b>{@code 2xx} with no {@code order_id}</b> — a contract violation. PPOB2 may well have
     *       created the order, so writing a confident {@code REJECTED} here would lose it.</li>
     *   <li><b>An error answer</b> ({@code 422}, {@code 409}, {@code 401}) — PPOB2 definitely did
     *       not create an order, so {@code REJECTED} with the code is the truth and the
     *       transaction needs no further reconciliation.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Ppob1Transaction applyCreateOutcome(Long transactionId, Ppob2Response response) {
        Ppob1Transaction transaction = repository.findById(transactionId).orElseThrow(
                () -> new IllegalStateException("ppob1_transaction " + transactionId + " vanished mid-submit"));

        if (!response.answered()) {
            log.warn("No answer from PPOB2 for reseller_txn_id {} — left SUBMITTING for reconciliation: {}",
                    transaction.getResellerTxnId(), response.transportError());
            return transaction;
        }

        if (!response.isSuccess()) {
            transaction.rejectedByPpob2(response.errorCode(), response.errorMessage());
            return repository.save(transaction);
        }

        JsonNode json = response.json();
        if (json == null || !json.hasNonNull("order_id")) {
            log.error("PPOB2 answered {} for reseller_txn_id {} with no order_id — contract violation, "
                            + "left SUBMITTING rather than assuming either outcome: {}",
                    response.httpStatus(), transaction.getResellerTxnId(), response.body());
            return transaction;
        }

        JsonNode payment = json.get("payment");
        transaction.acceptedByPpob2(
                json.get("order_id").asText(),
                json.hasNonNull("state") ? json.get("state").asText() : null,
                payment != null && payment.hasNonNull("qr_payload") ? payment.get("qr_payload").asText() : null,
                payment != null && payment.hasNonNull("expires_at")
                        ? parseInstant(payment.get("expires_at").asText()) : null);
        return repository.save(transaction);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            // PPOB2 sends ISO-8601; an unparseable value is worth losing the field over, not the
            // order. The QR expiry is a convenience for PPOB1's own UI, not a correctness input.
            return null;
        }
    }
}
