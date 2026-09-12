package id.ppob2.fulfillment;

import id.ppob2.fulfillment.domain.ProviderTransaction;
import id.ppob2.fulfillment.repository.ProviderTransactionRepository;
import id.ppob2.provider.PurchaseResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the one write {@link FulfillmentExecutionService} needs into its own
 * {@code @Transactional} bean, for two reasons: (1) {@code insertIfAbsent} is a
 * {@code @Modifying} query, which throws {@code TransactionRequiredException} without an active
 * transaction — and {@code FulfillmentExecutionService}'s methods are deliberately
 * non-transactional so the external provider call doesn't hold a DB connection open (see its
 * Javadoc) — this is exactly the shape of exception this class fixed the first time this ran
 * end-to-end; (2) even adding {@code @Transactional} directly to a method on
 * {@code FulfillmentExecutionService} wouldn't have worked if called privately/self-invoked,
 * since that bypasses Spring's proxy and the annotation is silently ignored. {@code REQUIRES_NEW}
 * for the usual reason: reached from inside an {@code AFTER_COMMIT} listener's call tree.
 */
@Service
public class ProviderTransactionRecorder {

    private final ProviderTransactionRepository repository;

    public ProviderTransactionRecorder(ProviderTransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecordedProviderTransaction record(Long providerSkuId, int quantity, Long childOrderId, Long providerId, String idempotencyKey, PurchaseResult result) {
        // Section 22.19 calls this an "outbound request snapshot" — it must reconstruct what was
        // actually sent (mirroring PurchaseRequest's fields), not just repeat childOrderId, which
        // is already a column on this row.
        String requestPayload = "{\"provider_sku_id\":" + providerSkuId + ",\"quantity\":" + quantity
                + ",\"idempotency_key\":\"" + idempotencyKey + "\"}";
        String responsePayload = "{\"status\":\"" + result.status() + "\",\"provider_reference\":"
                + (result.providerReference() != null ? "\"" + result.providerReference() + "\"" : "null") + "}";
        String status = result.isSuccess() ? "SUCCESS" : result.status().name();

        int inserted = repository.insertIfAbsent(childOrderId, providerId, result.providerReference(), idempotencyKey, status,
                requestPayload, responsePayload, null);

        Long id = repository.findByProviderIdAndIdempotencyKey(providerId, idempotencyKey)
                .map(ProviderTransaction::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "provider_transaction for idempotency_key=" + idempotencyKey + " missing immediately after insertIfAbsent"));

        return new RecordedProviderTransaction(id, inserted == 1);
    }
}
