package id.ppob2.payment;

import id.ppob2.sharedkernel.money.Money;

/**
 * Published when an inbound PG callback cannot be applied to its {@code payment} row and the
 * mismatch needs a human, not a retry — PRD Section 25.2's "logged and flagged for manual review,
 * never silently overwrite a terminal SUCCESS".
 *
 * <p>Exists for the same reason as {@link PaymentConfirmedEvent}: Section 20.2's module graph
 * grants `payment` no edge to `reconciliation`, so `payment` publishes an event it owns and the
 * composition root ({@code app}) opens the reconciliation record. Verified for real on 2026-09-27
 * that all three {@link Kind}s below were previously unrecorded — two of them completely silent.
 *
 * <p>{@code reportedAmount} is nullable: an unparseable/absent callback amount is itself one of the
 * anomalies this reports ({@link Kind#AMOUNT_MISMATCH}), so there is not always a number to carry.
 */
/*
 * `paymentStatus` is the payment's state at the moment the anomaly was detected. It is carried
 * because the reconciliation values depend on it, not for display: a SUCCESS callback landing on an
 * EXPIRED payment means funds were collected that our books show nothing for, whereas the same
 * callback landing on an already-SUCCESS payment means nothing new was collected at all. The
 * orchestrator cannot tell those apart from `kind` alone, and getting it wrong is how an anomaly ends
 * up recorded with a discrepancy of zero -- which is what an operator reads as "noise, resolve it".
 */
public record PaymentCallbackAnomalyEvent(Long paymentId,
                                           Long parentOrderId,
                                           Kind kind,
                                           String pgStatus,
                                           String paymentStatus,
                                           Money expectedAmount,
                                           Money reportedAmount) {

    public enum Kind {
        /**
         * A SUCCESS callback for a payment that is no longer {@code PENDING}. Covers the PG
         * confirming payment for a QR we already gave up on ({@code EXPIRED}/{@code FAILED}) —
         * money collected against an order we will never fulfil — and a second SUCCESS for a
         * payment already {@code SUCCESS}. Previously logged at WARN only, with the code comment
         * "No `reconciliation` module exists yet"; that module does exist now.
         */
        SUCCESS_ON_TERMINAL_PAYMENT,

        /**
         * Any non-success TERMINAL callback arriving after the payment already reached terminal
         * {@code SUCCESS} — PRD Section 25.2's out-of-order terminal callback. Covers {@code 06}
         * Failed, {@code 05} Canceled and {@code 04} Refunded, all three of which were broken
         * differently: {@code 06} was silent because {@code Payment.markFailed()}'s {@code false}
         * return was discarded at the call site, {@code 05} only logged, and {@code 04} was in no
         * status bucket at all so it fell through to the "Unrecognized status" WARN. A refund or
         * cancellation landing on a payment whose goods this platform has already delivered is a
         * funds question, not a log line.
         */
        TERMINAL_STATUS_AFTER_SUCCESS,

        /**
         * The payment WAS applied, but this code could not parse the callback's own amount, so the
         * amount agreement was never actually verified. Distinct from {@link #AMOUNT_MISMATCH} on
         * purpose: a mismatch is a confident finding about money, whereas this is an admission that
         * an assumption in THIS codebase (the callback amount's numeric format, never captured from
         * real traffic against an order with a known billed amount) did not hold. Blocking the
         * payment on it would convert a wrong parsing assumption into a total payment outage, so the
         * payment proceeds and a human is told instead.
         */
        AMOUNT_UNVERIFIED,

        /**
         * The callback's own amount does not equal {@code payment.amount} (or could not be parsed
         * as whole rupiah). Confirmed for real on 2026-09-27: a validly-signed SUCCESS callback
         * claiming {@code "1.00"} against a 20,000 order was accepted at full face value — payment
         * SUCCESS, a 20,000 CREDIT posted to the payment ledger, and 20,000 of goods fulfilled.
         */
        AMOUNT_MISMATCH
    }
}
