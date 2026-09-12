package id.ppob2.payment.callback;

import id.ppob2.payment.repository.PaymentEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wraps {@link PaymentEventRepository#insertIfAbsent} — see that method's Javadoc for why this
 * is a conflict-safe native insert rather than "insert and catch the constraint violation".
 * Because the insert can no longer throw for the duplicate case, this can safely run in whatever
 * transaction the caller is already in; no propagation isolation is needed here (an earlier
 * version of this class used {@code REQUIRES_NEW} to isolate an exception-based approach, which
 * turned out not to fix the underlying problem either — the exception path was the problem).
 */
@Service
public class PaymentEventDeduplicator {

    private final PaymentEventRepository repository;

    public PaymentEventDeduplicator(PaymentEventRepository repository) {
        this.repository = repository;
    }

    /** @return true if this is the first time {@code dedupKey} has been seen (caller should
     * process it); false if it's a duplicate (caller must not reprocess, Section 48.5). */
    @Transactional
    public boolean recordIfNew(Long paymentId, String eventType, String rawPayload, String dedupKey) {
        int inserted = repository.insertIfAbsent(paymentId, eventType, rawPayload, true, dedupKey, Instant.now());
        return inserted > 0;
    }
}
