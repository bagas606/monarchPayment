package id.ppob2.provider;

/** Section 27.1: "{@code purchase(PurchaseRequest request)} — must include idempotency key." */
public record PurchaseRequest(Long providerSkuId, int quantity, String idempotencyKey) {
}
