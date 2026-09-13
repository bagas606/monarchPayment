package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.audit.AuditService;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.repository.ReconciliationRepository;
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
 * PRD Section 41.9 / 38.2: the Admin Web reconciliation discrepancy-resolution workflow
 * (OPEN → INVESTIGATING → RESOLVED). This is the first real caller of
 * {@code ReconciliationService.investigate}/{@code resolve} in this codebase — both were
 * previously flagged as "unreachable from any real path," exercised only by
 * {@code ReconciliationServiceTest}'s mocked-repository guard tests. This slice closes that gap
 * for the two mutating actions; it does not build the read-side list/detail views Section 41.9
 * also names (flagged as still-open scope, not silently covered).
 *
 * <p>Every mutation is recorded to {@code audit_log} via {@link AuditService#recordAdminAction}
 * (Section 43's "Recorded" requirement) with the acting admin_user's id ({@link
 * AdminPrincipal#getAdminUserId()}) — never a client-supplied actor id. Each mutating method is
 * additionally gated behind its own Section 42.2 permission via {@code @PreAuthorize} — an
 * authenticated admin without the specific permission is rejected by the AOP proxy before the
 * method body (and therefore any repository write) ever runs.
 */
@RestController
public class AdminReconciliationController {

    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationService reconciliationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminReconciliationController(ReconciliationRepository reconciliationRepository,
                                          ReconciliationService reconciliationService,
                                          AuditService auditService,
                                          ObjectMapper objectMapper) {
        this.reconciliationRepository = reconciliationRepository;
        this.reconciliationService = reconciliationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/admin/reconciliations/{id}/investigate")
    @PreAuthorize("hasAuthority('reconciliation:investigate')")
    public ResponseEntity<?> investigate(@PathVariable Long id, @AuthenticationPrincipal AdminPrincipal principal,
                                          HttpServletRequest request) {
        Optional<Reconciliation> existing = reconciliationRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String before = snapshot(existing.get());

        boolean applied = reconciliationService.investigate(id);

        Reconciliation after = reconciliationRepository.findById(id).orElseThrow();
        auditService.recordAdminAction(principal.getAdminUserId(), "RECONCILIATION_INVESTIGATE", "RECONCILIATION", id,
                before, snapshot(after), request.getRemoteAddr());

        return ResponseEntity.ok(Map.of("id", id, "applied", applied, "status", after.getStatus().name()));
    }

    @PostMapping("/admin/reconciliations/{id}/resolve")
    @PreAuthorize("hasAuthority('reconciliation:resolve')")
    public ResponseEntity<?> resolve(@PathVariable Long id, @AuthenticationPrincipal AdminPrincipal principal,
                                      HttpServletRequest request) {
        Optional<Reconciliation> existing = reconciliationRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String before = snapshot(existing.get());

        boolean applied = reconciliationService.resolve(id, principal.getAdminUserId());

        Reconciliation after = reconciliationRepository.findById(id).orElseThrow();
        auditService.recordAdminAction(principal.getAdminUserId(), "RECONCILIATION_RESOLVE", "RECONCILIATION", id,
                before, snapshot(after), request.getRemoteAddr());

        return ResponseEntity.ok(Map.of("id", id, "applied", applied, "status", after.getStatus().name()));
    }

    private String snapshot(Reconciliation reconciliation) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", reconciliation.getStatus().name(),
                    "resolvedBy", reconciliation.getResolvedBy() == null ? "" : reconciliation.getResolvedBy()));
        } catch (Exception e) {
            return "{}";
        }
    }
}
