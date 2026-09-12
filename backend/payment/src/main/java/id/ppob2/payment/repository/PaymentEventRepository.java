package id.ppob2.payment.repository;

import id.ppob2.payment.domain.PaymentEvent;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    /**
     * Section 48.5's dedup mechanism, implemented as {@code ON CONFLICT DO NOTHING} rather than
     * "insert and catch the constraint violation": a JPA flush failure marks the whole
     * transaction rollback-only at the Hibernate level (per the JPA spec) regardless of whether
     * calling code catches the translated exception — a plain try/catch around
     * {@code saveAndFlush} looked correct, compiled, and passed a mocked-repository test, but an
     * end-to-end Postgres run of the actual duplicate-callback path (Section 48.5) surfaced
     * {@code UnexpectedRollbackException} at commit time. A conflict-safe native insert avoids
     * the exception path entirely — Postgres just no-ops the row and reports 0 affected.
     *
     * @return 1 if this dedup_key was newly inserted, 0 if it already existed.
     */
    @Modifying
    @Query(value = """
            INSERT INTO payment_event (payment_id, event_type, raw_payload, signature_valid, dedup_key, received_at)
            VALUES (:paymentId, :eventType, CAST(:rawPayload AS JSONB), :signatureValid, :dedupKey, :receivedAt)
            ON CONFLICT (dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("paymentId") Long paymentId,
                        @Param("eventType") String eventType,
                        @Param("rawPayload") String rawPayload,
                        @Param("signatureValid") boolean signatureValid,
                        @Param("dedupKey") String dedupKey,
                        @Param("receivedAt") Instant receivedAt);
}
