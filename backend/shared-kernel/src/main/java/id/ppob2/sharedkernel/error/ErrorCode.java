package id.ppob2.sharedkernel.error;

/** Standard error codes per PRD Section 23.9. */
public enum ErrorCode {
    VALIDATION_ERROR(400),
    SIGNATURE_INVALID(401),
    TIMESTAMP_OUT_OF_RANGE(401),
    CLIENT_SUSPENDED(403),
    ORDER_NOT_FOUND(404),
    IDEMPOTENCY_KEY_CONFLICT(409),
    ORDER_NOT_CANCELLABLE(409),
    UNSUPPORTED_AMOUNT(422),
    RATE_LIMITED(429),
    INTERNAL_ERROR(500),
    PG_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
