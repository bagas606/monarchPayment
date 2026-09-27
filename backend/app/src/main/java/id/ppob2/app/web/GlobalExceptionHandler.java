package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.audit.AuditService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps every controller-thrown exception to the standard error envelope, PRD Section 50.1. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(ex, correlationId(request));
        return ResponseEntity.status(ex.errorCode().httpStatus()).body(body);
    }

    /**
     * A {@code @PreAuthorize} denial (Section 42.2) throws {@code AuthorizationDeniedException} —
     * a subtype of this — from inside the controller-method AOP proxy, which {@code
     * DispatcherServlet}'s own exception resolution (backing this very {@code
     * @RestControllerAdvice}) resolves before the exception can ever propagate up the filter chain
     * to {@code ExceptionTranslationFilter}. A {@code SecurityConfig}-level {@code
     * AccessDeniedHandler} therefore never runs for a method-security denial — only for a
     * URL-level {@code authorizeHttpRequests} denial, which throws from {@code AuthorizationFilter}
     * before the servlet is ever entered. Both call sites now produce the same envelope: this
     * handler for @PreAuthorize, the filter-level handler for URL-level checks.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handlePermissionDenied(AccessDeniedException ex, HttpServletRequest request) {
        auditDeniedAttempt(request);
        ErrorResponse body = ErrorResponse.of(ErrorCode.PERMISSION_DENIED, "You do not have permission to perform this action.", correlationId(request));
        return ResponseEntity.status(ErrorCode.PERMISSION_DENIED.httpStatus()).body(body);
    }

    /**
     * PRD Section 43 / TC-ADM-005: a permission-denied admin attempt must be "blocked,
     * audit-logged as denied attempt". Blocking always worked; the audit half did not exist —
     * confirmed twice (2026-09-14 and again 2026-09-27) that {@code audit_log} had zero rows after
     * a denied {@code retry}, because {@code AuditService} was only ever called on success paths.
     *
     * <p>Unlike a failed login, the acting admin IS authenticated here, so the real {@code
     * admin_user.id} is recorded via {@link AuditService#recordAdminAction} whenever the principal
     * resolves; it falls back to {@link AuditService#recordRejectedAdminAttempt} otherwise (a
     * URL-level denial for a non-admin, where there may be no {@code AdminPrincipal} at all).
     *
     * <p>Auditing must never turn a clean 403 into a 500, so a failure to record is logged and
     * swallowed — losing an audit row is bad, but answering a denied request with a masked server
     * error would hide the denial itself, which is worse.
     *
     * <p>Known limit, stated rather than implied: this covers denials that reach {@code
     * DispatcherServlet}'s exception resolution — i.e. Spring Security's method security
     * ({@code @PreAuthorize}), which is TC-ADM-005's exact case. A URL-level denial thrown by
     * {@code AuthorizationFilter} before the servlet is entered does not reach any
     * {@code @RestControllerAdvice} and is not audited here.
     */
    private void auditDeniedAttempt(HttpServletRequest request) {
        try {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("method", request.getMethod());
            detail.put("path", request.getRequestURI());
            detail.put("outcome", "DENIED");

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Object principal = authentication != null ? authentication.getPrincipal() : null;
            if (principal instanceof AdminPrincipal admin) {
                auditService.recordAdminAction(admin.getAdminUserId(), "ADMIN_PERMISSION_DENIED",
                        "ADMIN_ENDPOINT", null, null, json(detail), request.getRemoteAddr());
            } else {
                detail.put("username", authentication != null ? String.valueOf(authentication.getName()) : "unknown");
                auditService.recordRejectedAdminAttempt("ADMIN_PERMISSION_DENIED", "ADMIN_ENDPOINT",
                        json(detail), request.getRemoteAddr());
            }
        } catch (Exception auditFailure) {
            log.error("Failed to audit a permission-denied attempt on {} {}",
                    request.getMethod(), request.getRequestURI(), auditFailure);
        }
    }

    private String json(Map<String, String> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }


    /**
     * Framework-level rejections that used to fall through to {@link #handleUnexpected} and be
     * reported as {@code 500 INTERNAL_ERROR} with a full stack trace. Confirmed for real against a
     * running app on 2026-09-27: an unknown path, a POST-only route called with GET, a malformed
     * JSON body and a missing {@code Idempotency-Key} header all returned {@code 500}. That is not
     * cosmetic — PRD Section 23.1 tells partners to retry on {@code 5xx}, so a permanently-failing
     * request (bad body, wrong method, wrong path) was inviting an infinite retry loop, and every
     * scanner hitting an unknown URL was logging an ERROR stack trace that buries real incidents.
     *
     * <p>Grouped into one handler per HTTP status rather than one per exception type: the envelope
     * and the {@code error_code} are what partners parse (Section 50.1), and these all map to the
     * same partner-visible outcome. {@code NoResourceFoundException} is the Spring 6 form (the
     * request fell through to the static-resource handler); {@code NoHandlerFoundException} is the
     * form thrown when {@code throw-exception-if-no-handler-found} is enabled — both are handled so
     * this keeps working either way.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        log.debug("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return envelope(ErrorCode.NOT_FOUND, "No endpoint " + request.getMethod() + " " + request.getRequestURI() + ".", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                 HttpServletRequest request) {
        log.debug("Method {} not supported for {}", request.getMethod(), request.getRequestURI());
        return envelope(ErrorCode.METHOD_NOT_ALLOWED, ex.getMessage(), request);
    }

    /**
     * Malformed/unreadable body, a missing required header or query parameter, a bean-validation
     * failure, or an unparseable path variable — all client mistakes, all {@code 400}, reported
     * with the same {@code VALIDATION_ERROR} code Section 23.9 already documents for this class
     * rather than inventing new codes partners don't know how to branch on.
     *
     * <p>The message is deliberately generic per exception class instead of echoing {@code
     * ex.getMessage()} for the body case: Jackson's parse messages quote the offending source
     * fragment, which for this service's endpoints can contain partner request payloads.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        log.debug("Unreadable request body on {} {}", request.getMethod(), request.getRequestURI());
        return envelope(ErrorCode.VALIDATION_ERROR, "Request body is missing or is not valid JSON.", request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.debug("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return envelope(ErrorCode.VALIDATION_ERROR, ex.getMessage(), request);
    }

    /**
     * Datastore unreachable. Kept distinct from {@link #handleUnexpected}'s {@code 500} because
     * this one IS safe and correct for a partner to retry, and because a {@code 503} is what a
     * load balancer / uptime monitor should see during a database outage.
     *
     * <p>Note the limit of this handler, confirmed for real by stopping Postgres under a running
     * app: a request whose very first datastore touch happens in {@code HmacAuthenticationFilter}
     * (the {@code api_client} lookup) fails <em>before</em> {@code DispatcherServlet} is entered, so
     * no {@code @RestControllerAdvice} can see it — that path is handled inside the filter itself.
     * This handler covers the datastore failing later, once a controller is already executing.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleDatastoreUnavailable(Exception ex, HttpServletRequest request) {
        log.error("Datastore unavailable serving {} {}: {}", request.getMethod(), request.getRequestURI(), ex.toString());
        return envelope(ErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable, retry later.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // An INTERNAL_ERROR with nothing in the log is undebuggable in production — this was
        // caught mid-slice when a genuine bug (see PaymentCallbackService) produced a silent 500.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Unexpected server error.", correlationId(request));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<ErrorResponse> envelope(ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, message, correlationId(request)));
    }

    private String correlationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return attribute != null ? attribute.toString() : "unknown";
    }
}
