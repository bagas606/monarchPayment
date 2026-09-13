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
    PG_UNAVAILABLE(503),

    /** Not part of Section 23.9's partner-facing table above — Section 37.1 defines no error
     * codes of its own for settlement report ingestion, an internal ops endpoint, not a partner
     * one. Added so a duplicate-date resubmission gets a clean 409 instead of a raw 500 from the
     * underlying `settlement_date_uk` constraint violation. */
    SETTLEMENT_ALREADY_INGESTED(409),

    /** Not part of Section 23.9's table either — Section 34.1's Admin Web compensating retry is
     * an internal ops action. Covers every reason a retry can't proceed: the child order isn't
     * {@code FAILED}, its parent isn't {@code PARTIAL_FAILED}, or it lost a race with another
     * concurrent state change between lookup and retry. */
    CHILD_ORDER_NOT_RETRYABLE(409),

    /** Not part of Section 23.9's partner-facing table — Section 42.2's RBAC denial for an
     * authenticated admin who lacks the specific permission a `@PreAuthorize`-gated admin action
     * requires ("not merely be an admin"). Returned by {@code SecurityConfig}'s custom
     * {@code AccessDeniedHandler}, not {@code GlobalExceptionHandler}, since Spring Security's
     * {@code ExceptionTranslationFilter} intercepts {@code AccessDeniedException} before it can
     * ever reach a {@code @RestControllerAdvice}. */
    PERMISSION_DENIED(403);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
