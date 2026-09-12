package id.ppob2.fulfillment.repository;

import id.ppob2.fulfillment.domain.ProviderTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderTransactionRepository extends JpaRepository<ProviderTransaction, Long> {

    /**
     * Section 22.19's {@code provider_transaction_idem_uk} UNIQUE(provider_id, idempotency_key)
     * IS the idempotency mechanism for concurrent/duplicate dispatch (Section 34.1) — implemented
     * as a conflict-safe native insert rather than "insert and catch the constraint violation",
     * per this codebase's established rule (see {@code PaymentEventRepository.insertIfAbsent}'s
     * Javadoc): a failed JPA flush marks the whole transaction rollback-only regardless of
     * whether the translated exception is caught.
     *
     * @return 1 if this (provider_id, idempotency_key) pair was newly inserted, 0 if it already existed.
     */
    @Modifying
    @Query(value = """
            INSERT INTO provider_transaction
                (child_order_id, provider_id, provider_reference, idempotency_key, status, request_payload, response_payload, latency_ms)
            VALUES (:childOrderId, :providerId, :providerReference, :idempotencyKey, :status, CAST(:requestPayload AS JSONB), CAST(:responsePayload AS JSONB), :latencyMs)
            ON CONFLICT (provider_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("childOrderId") Long childOrderId,
                        @Param("providerId") Long providerId,
                        @Param("providerReference") String providerReference,
                        @Param("idempotencyKey") String idempotencyKey,
                        @Param("status") String status,
                        @Param("requestPayload") String requestPayload,
                        @Param("responsePayload") String responsePayload,
                        @Param("latencyMs") Integer latencyMs);

    Optional<ProviderTransaction> findByProviderIdAndIdempotencyKey(Long providerId, String idempotencyKey);
}
