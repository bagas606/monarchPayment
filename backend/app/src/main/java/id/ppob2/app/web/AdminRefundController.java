package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.app.payment.AdminRefundOrchestrator;
import id.ppob2.app.payment.RefundOutcome;
import id.ppob2.audit.AuditService;
import id.ppob2.payment.domain.Payment;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Section 33.2's {@code REFUND_PENDING -> REFUNDED} action, and the {@code refund:initiate}
 * permission Section 42.2 names. Thin HTTP + audit layer over {@link AdminRefundOrchestrator},
 * which owns the composition — the same split as {@link AdminFulfillmentController} next to it.
 *
 * <p>Section 25.2: a refund is "only permitted through Admin Web with authorization + audit". All
 * three halves are enforced here: {@code @PreAuthorize} for authorization, a mandatory non-blank
 * {@code reason} (BR-ADM-001's "no unrestricted manual mutation of financial transaction state"),
 * and an {@code audit_log} row carrying both plus the refund reference.
 *
 * <p>The {@code reason} is required and validated before any state change, for the same reason
 * {@code TC-ADM-012} requires one on a retry: an unexplained refund is exactly what the control
 * exists to prevent, and accepting a blank one would satisfy the signature without the control.
 * A refund is the largest financial mutation in this system, so it also requires
 * {@code external_reference} whenever the gateway cannot execute the refund itself — see
 * {@link AdminRefundOrchestrator}.
 *
 * <p>Rejections ({@code 409 ORDER_NOT_REFUNDABLE}, {@code 502 REFUND_FAILED_AT_GATEWAY},
 * {@code 400 VALIDATION_ERROR}) are deliberately NOT audited, matching the precedent set by
 * {@code AdminFulfillmentController}'s {@code CHILD_ORDER_NOT_RETRYABLE} and
 * {@code OrderQueryController}'s {@code ORDER_NOT_CANCELLABLE}: an attempt that changed nothing
 * is not an admin action to record. A *denied* attempt is a different matter and is already
 * recorded as {@code ADMIN_PERMISSION_DENIED} by {@code AdminSecurityAuditListener}.
 */
@RestController
public class AdminRefundController {

    private final AdminRefundOrchestrator refundOrchestrator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminRefundController(AdminRefundOrchestrator refundOrchestrator,
                                  AuditService auditService,
                                  ObjectMapper objectMapper) {
        this.refundOrchestrator = refundOrchestrator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/admin/parent-orders/{id}/refund")
    @PreAuthorize("hasAuthority('refund:initiate')")
    public ResponseEntity<?> refund(@PathVariable Long id,
                                     @RequestBody(required = false) RefundRequestBody body,
                                     @AuthenticationPrincipal AdminPrincipal principal,
                                     HttpServletRequest request) {
        String reason = body != null && body.reason() != null ? body.reason().trim() : "";
        if (reason.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "A non-blank 'reason' is required to refund an order (Section 25.2 / BR-ADM-001).");
        }
        String externalReference = body != null ? body.externalReference() : null;

        Optional<Payment> before = refundOrchestrator.findPayment(id);
        String beforeState = before.map(this::snapshot).orElse("{}");

        RefundOutcome outcome = refundOrchestrator.refund(id, reason, externalReference);

        auditService.recordAdminAction(principal.getAdminUserId(), "PARENT_ORDER_REFUND", "PARENT_ORDER", id,
                beforeState, snapshot(outcome, reason), request.getRemoteAddr());

        return ResponseEntity.ok(Map.of(
                "parentOrderId", outcome.parentOrderId(),
                "paymentId", outcome.paymentId(),
                "orderState", outcome.orderState().name(),
                "paymentStatus", outcome.paymentStatus().name(),
                "refundReference", outcome.refundReference(),
                "gatewayExecuted", outcome.gatewayExecuted()));
    }

    /**
     * {@code externalReference} is the operator's evidence that money moved out-of-band (a bank
     * transfer reference, a PG dashboard ticket). Required when the gateway cannot execute refunds
     * — which is every production configuration today — and rejected when it can, since the PG's
     * own reference is authoritative there.
     */
    public record RefundRequestBody(String reason, String externalReference) {
    }

    private String snapshot(Payment payment) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "paymentId", payment.getId(),
                    "paymentStatus", payment.getStatus().name(),
                    "amount", payment.getAmount().toString()));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** LinkedHashMap, not Map.of, so the JSON this method <em>produces</em> is deterministic (what
     * a log line or a serializer test sees). It deliberately does not claim to control key order in
     * the stored row: {@code audit_log.after_state} is {@code jsonb}, which normalises whitespace
     * and reorders keys on storage, so nothing here can make the column preserve insertion order.
     * Read it back with {@code after_state->>'reason'} rather than a {@code LIKE} on its text — the
     * e2e assertions originally did the latter and failed against a perfectly correct row. */
    private String snapshot(RefundOutcome outcome, String reason) {
        try {
            Map<String, String> after = new LinkedHashMap<>();
            after.put("reason", reason);
            after.put("refundReference", outcome.refundReference());
            after.put("mode", outcome.gatewayExecuted() ? "GATEWAY" : "OUT_OF_BAND");
            after.put("orderState", outcome.orderState().name());
            after.put("paymentStatus", outcome.paymentStatus().name());
            return objectMapper.writeValueAsString(after);
        } catch (Exception e) {
            return "{}";
        }
    }
}
