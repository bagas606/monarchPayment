package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.app.fulfillment.AdminChildOrderRetryOrchestrator;
import id.ppob2.app.fulfillment.RetryOutcome;
import id.ppob2.audit.AuditService;
import id.ppob2.order.ChildOrderService;
import id.ppob2.order.domain.ChildOrder;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Section 34.1's Admin Web compensating retry action, gated behind the same admin auth as
 * {@link AdminReconciliationController} ({@code SecurityConfig.adminFilterChain}). Thin HTTP +
 * audit layer over {@link AdminChildOrderRetryOrchestrator}, which owns the actual composition —
 * same split of responsibility as the reconciliation controller next to it.
 *
 * <p>A rejection (child order not {@code FAILED}, parent not {@code PARTIAL_FAILED}) surfaces as
 * a thrown {@code ApiException} → clean {@code 409 CHILD_ORDER_NOT_RETRYABLE} via {@code
 * GlobalExceptionHandler}, and is deliberately NOT audited — same precedent as {@code
 * OrderQueryController.cancelOrder}'s {@code ORDER_NOT_CANCELLABLE}: a rejected attempt that
 * changed nothing isn't an admin action to record.
 */
@RestController
public class AdminFulfillmentController {

    private final ChildOrderService childOrderService;
    private final AdminChildOrderRetryOrchestrator retryOrchestrator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminFulfillmentController(ChildOrderService childOrderService,
                                       AdminChildOrderRetryOrchestrator retryOrchestrator,
                                       AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.childOrderService = childOrderService;
        this.retryOrchestrator = retryOrchestrator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * PRD TC-ADM-012: "Retry requires permission + reason text; recorded in audit_log." The
     * permission half has been enforced since the RBAC slice ({@code @PreAuthorize} above); the
     * reason half did not exist — the endpoint took no body at all, so {@code audit_log} recorded
     * *that* a retry happened and by whom, but never *why*, which is the field an after-the-fact
     * review of a compensating money action actually needs.
     *
     * <p>Required, not optional, and validated before any state change: an unexplained retry is
     * exactly what this requirement exists to prevent, so accepting a blank reason would satisfy
     * the signature and not the control. Rejected with {@code 400 VALIDATION_ERROR} via the
     * standard envelope rather than a bean-validation 500-shaped failure.
     */
    @PostMapping("/admin/child-orders/{id}/retry")
    @PreAuthorize("hasAuthority('retry:execute')")
    public ResponseEntity<?> retry(@PathVariable Long id,
                                    @RequestBody(required = false) RetryRequest body,
                                    @AuthenticationPrincipal AdminPrincipal principal,
                                    HttpServletRequest request) {
        String reason = body != null && body.reason() != null ? body.reason().trim() : "";
        if (reason.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "A non-blank 'reason' is required to retry a child order (PRD TC-ADM-012).");
        }

        Optional<ChildOrder> existing = childOrderService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String before = snapshot(existing.get());

        RetryOutcome outcome = retryOrchestrator.retry(id);

        auditService.recordAdminAction(principal.getAdminUserId(), "CHILD_ORDER_RETRY", "CHILD_ORDER", id,
                before, snapshot(outcome, reason), request.getRemoteAddr());

        return ResponseEntity.ok(Map.of(
                "childOrderId", outcome.childOrderId(),
                "childState", outcome.childState().name(),
                "parentOrderId", outcome.parentOrderId(),
                "parentState", outcome.parentState().name()));
    }

    /** The operator-supplied reason rides in {@code after_state} rather than a new column: Section
     * 22.27's {@code audit_log} has no free-text field, and adding one for a single caller would be
     * a migration this requirement does not need. */
    public record RetryRequest(String reason) {
    }

    private String snapshot(ChildOrder childOrder) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "state", childOrder.getState().name(), "attemptCount", childOrder.getAttemptCount()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String snapshot(RetryOutcome outcome, String reason) {
        try {
            // LinkedHashMap, not Map.of: the reason is the field a reviewer reads first, so it is
            // worth keeping the key order stable and predictable in the stored JSON.
            Map<String, String> after = new LinkedHashMap<>();
            after.put("childState", outcome.childState().name());
            after.put("parentState", outcome.parentState().name());
            after.put("reason", reason);
            return objectMapper.writeValueAsString(after);
        } catch (Exception e) {
            return "{}";
        }
    }
}
