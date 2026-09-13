package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.app.fulfillment.AdminChildOrderRetryOrchestrator;
import id.ppob2.app.fulfillment.RetryOutcome;
import id.ppob2.audit.AuditService;
import id.ppob2.order.ChildOrderService;
import id.ppob2.order.domain.ChildOrder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
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

    @PostMapping("/admin/child-orders/{id}/retry")
    @PreAuthorize("hasAuthority('retry:execute')")
    public ResponseEntity<?> retry(@PathVariable Long id, @AuthenticationPrincipal AdminPrincipal principal,
                                    HttpServletRequest request) {
        Optional<ChildOrder> existing = childOrderService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String before = snapshot(existing.get());

        RetryOutcome outcome = retryOrchestrator.retry(id);

        auditService.recordAdminAction(principal.getAdminUserId(), "CHILD_ORDER_RETRY", "CHILD_ORDER", id,
                before, snapshot(outcome), request.getRemoteAddr());

        return ResponseEntity.ok(Map.of(
                "childOrderId", outcome.childOrderId(),
                "childState", outcome.childState().name(),
                "parentOrderId", outcome.parentOrderId(),
                "parentState", outcome.parentState().name()));
    }

    private String snapshot(ChildOrder childOrder) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "state", childOrder.getState().name(), "attemptCount", childOrder.getAttemptCount()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String snapshot(RetryOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "childState", outcome.childState().name(), "parentState", outcome.parentState().name()));
        } catch (Exception e) {
            return "{}";
        }
    }
}
