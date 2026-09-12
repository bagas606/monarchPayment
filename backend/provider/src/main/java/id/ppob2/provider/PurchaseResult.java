package id.ppob2.provider;

public record PurchaseResult(PurchaseStatus status, String providerReference, String failureReason) {

    public static PurchaseResult success(String providerReference) {
        return new PurchaseResult(PurchaseStatus.SUCCESS, providerReference, null);
    }

    public static PurchaseResult failed(String reason) {
        return new PurchaseResult(PurchaseStatus.FAILED, null, reason);
    }

    public static PurchaseResult timeout(String reason) {
        return new PurchaseResult(PurchaseStatus.TIMEOUT, null, reason);
    }

    public boolean isRetryable() {
        return status == PurchaseStatus.TIMEOUT;
    }

    public boolean isSuccess() {
        return status == PurchaseStatus.SUCCESS;
    }
}
