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

    /** Not part of Section 23.9's partner-facing table — a request for a path this service does
     * not route at all. Previously fell through {@code GlobalExceptionHandler}'s catch-all {@code
     * Exception} handler and was reported as a {@code 500 INTERNAL_ERROR} with a full stack trace
     * in the log, which both violates HTTP semantics and buries genuine incidents under routine
     * scanner/typo traffic. */
    NOT_FOUND(404),

    /** Not part of Section 23.9's table either — a known path invoked with the wrong HTTP method
     * (e.g. {@code GET /api/v1/orders}, which is POST-only). Same previously-a-500 class of bug as
     * {@link #NOT_FOUND}. */
    METHOD_NOT_ALLOWED(405),

    /** Not part of Section 23.9's table either — the datastore is unreachable, so the request
     * genuinely cannot be served now but is safe for the partner to retry later. Distinguished
     * from {@link #INTERNAL_ERROR} deliberately: partners treat 5xx as retryable, and a 500 on a
     * request that can never succeed (malformed body, unknown path) invites an infinite retry
     * loop, while a 503 on a transient outage is exactly the signal they should act on.
     * Distinct from {@link #PG_UNAVAILABLE}, which is specifically the payment gateway. */
    SERVICE_UNAVAILABLE(503),

    /** Not part of Section 23.9's partner-facing table above — Section 37.1 defines no error
     * codes of its own for settlement report ingestion, an internal ops endpoint, not a partner
     * one. Added so a duplicate-date resubmission gets a clean 409 instead of a raw 500 from the
     * underlying `settlement_date_uk` constraint violation. */
    SETTLEMENT_ALREADY_INGESTED(409),

    /** Not part of Section 23.9's table either -- Section 37.3's per-partner allocation invariant
     * (BR-REC-002: partner allocations MUST sum to exactly {@code actual_amount}/{@code
     * fee_amount}). Thrown by {@code SettlementAllocationService} when the pro-rata computation
     * cannot satisfy that invariant (e.g. contributing partners sum to a zero expected amount) --
     * surfaced as a real error the operator must investigate, never silently rounded away. */
    SETTLEMENT_ALLOCATION_INVALID(500),

    /** Not part of Section 23.9's table either — Section 34.1's Admin Web compensating retry is
     * an internal ops action. Covers every reason a retry can't proceed: the child order isn't
     * {@code FAILED}, its parent isn't {@code PARTIAL_FAILED}, or it lost a race with another
     * concurrent state change between lookup and retry. */
    CHILD_ORDER_NOT_RETRYABLE(409),

    /** Not part of Section 23.9's table — Section 33.2's {@code REFUND_PENDING -> REFUNDED} is an
     * authorized internal ops action. Covers every reason a refund can't proceed: the order isn't
     * {@code REFUND_PENDING}, it has no payment row, that payment isn't {@code SUCCESS} (so there
     * are no collected funds to reverse), or it lost a race with a concurrent refund. */
    ORDER_NOT_REFUNDABLE(409),

    /** The gateway was asked to execute a refund and declined, errored, or reported success with
     * no refund reference. {@code 502}, not {@code 500}: the failure is the PG's, nothing was
     * written, the order is still refundable, and the operator's next step is to retry or fall back
     * to an out-of-band refund rather than to file a bug. Distinct from
     * {@link #ORDER_NOT_REFUNDABLE} precisely so those two next steps are distinguishable. */
    REFUND_FAILED_AT_GATEWAY(502),

    /** Not part of Section 23.9's partner-facing table — Section 42.2's RBAC denial for an
     * authenticated admin who lacks the specific permission a `@PreAuthorize`-gated admin action
     * requires ("not merely be an admin"). Returned by {@code SecurityConfig}'s custom
     * {@code AccessDeniedHandler}, not {@code GlobalExceptionHandler}, since Spring Security's
     * {@code ExceptionTranslationFilter} intercepts {@code AccessDeniedException} before it can
     * ever reach a {@code @RestControllerAdvice}. */
    PERMISSION_DENIED(403),

    /** Admin Web login or session (Section 42.3). One code for "wrong username", "wrong password",
     * "account locked" and "session expired" on purpose - distinguishing them tells an attacker which
     * usernames exist and which are under lockout. */
    AUTHENTICATION_FAILED(401),

    /** A TOTP code that did not verify, was already used, or arrived after the session ran out of attempts. */
    MFA_CODE_INVALID(401),

    /** Enrolment attempted for an account that already has MFA. Replacing a second factor from a live
     * session would let a hijacked session lock the real owner out, so it is refused. */
    MFA_ALREADY_ENROLLED(409),

    /** The session is not in a state that allows the call (e.g. verifying MFA on an active session). */
    SESSION_STATE_INVALID(409),

    /** A pattern generation is already running; one at a time (Section 40.2), the API never queues. */
    GENERATION_IN_PROGRESS(409),

    /** The generation cannot be activated in its current state (not built and validated, or already active). */
    GENERATION_NOT_ACTIVATABLE(409),

    /** Activating would leave some (product, amount) with no eligible pattern that the current generation serves.
     * Refused unless the admin gives an explicit reason, because an uncovered amount cannot be fulfilled at all. */
    COVERAGE_REGRESSION(409),

    /** A catalog change that conflicts with existing data (duplicate code, a value that would break live patterns, ...). */
    CATALOG_CONFLICT(409);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
