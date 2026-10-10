package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.audit.AuditService;
import id.ppob2.reconciliation.ReconciliationQuery;
import id.ppob2.reconciliation.ReconciliationQueryService;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.reconciliation.repository.ReconciliationRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Section 41.9 / 38.2: the Admin Web reconciliation discrepancy-resolution workflow
 * (OPEN → INVESTIGATING → RESOLVED). This is the first real caller of
 * {@code ReconciliationService.investigate}/{@code resolve} in this codebase — both were
 * previously flagged as "unreachable from any real path," exercised only by
 * {@code ReconciliationServiceTest}'s mocked-repository guard tests.
 *
 * <p>The read side Section 41.9 also names — and FR-REC-003 states as "record <em>and expose</em>"
 * — was flagged as still-open scope by an earlier revision of this comment, and is now built:
 * {@link #list} and {@link #detail} below. One filtered list endpoint serves all three of Section
 * 41.9's "views" (payment / provider / settlement reconciliation) via {@code recon_type}; see
 * {@code ReconciliationQueryService} for why that is one endpoint and not three. Both GETs are
 * read-only and therefore record nothing to {@code audit_log}, the same reasoning (and the same
 * precedent) as {@link AdminSettlementController}: Section 43's "Recorded" requirement is about
 * admin <em>mutations</em>. That is load-bearing for TC-ADM-013's 1:1 audit-row claim, which an
 * audited GET would quietly break.
 *
 * <p>Still not built, named rather than implied: Section 41.9's resolution notes. Its bullet reads
 * "OPEN → INVESTIGATING → RESOLVED, with notes and resolver identity" — resolver identity is
 * recorded ({@code resolved_by}/{@code resolved_at}), notes are not. {@code reconciliation} has no
 * notes column and {@link #resolve} takes no body, so an operator can say *that* they resolved a
 * discrepancy but not *why* — the same gap TC-ADM-012 closed for retries, still open here.
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
    private final ReconciliationQueryService reconciliationQueryService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminReconciliationController(ReconciliationRepository reconciliationRepository,
                                          ReconciliationService reconciliationService,
                                          ReconciliationQueryService reconciliationQueryService,
                                          AuditService auditService,
                                          ObjectMapper objectMapper) {
        this.reconciliationRepository = reconciliationRepository;
        this.reconciliationService = reconciliationService;
        this.reconciliationQueryService = reconciliationQueryService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * The discrepancy queue (Section 38.2's "visible", FR-REC-003's "expose"). Every filter is
     * optional; with none, it is the whole table newest-first.
     *
     * <p>Query parameters are named explicitly with {@code @RequestParam("...")} because the app's
     * global {@code spring.jackson.property-naming-strategy: SNAKE_CASE} applies to JSON
     * (de)serialization only — it has no effect on query-string binding, so a parameter relying on
     * it would silently arrive null and the filter would be ignored rather than rejected. The
     * response bodies below go through Jackson and so are snake_case as usual.
     *
     * <p>{@code status} and {@code recon_type} are repeatable ({@code ?status=OPEN&status=INVESTIGATING}).
     * That is what makes "the open work queue" expressible in one call: Section 38.2 defines
     * OPEN and INVESTIGATING as the two unresolved states, so an operator's queue is both of them,
     * and a single-valued filter would force two requests and a client-side merge.
     *
     * <p>No {@code Pageable} argument, no {@code Page} in the response. The former would accept a
     * client-supplied {@code sort} property and turn a typo into an unmapped {@code 500}
     * ({@code PropertyReferenceException}); the latter serializes Spring Data's {@code PageImpl},
     * whose JSON shape Spring Data itself warns is unstable across versions — a partner-visible
     * contract should not change because a dependency did.
     */
    @GetMapping("/admin/reconciliations")
    @PreAuthorize("hasAuthority('reconciliation:view')")
    public ResponseEntity<ReconciliationListResponse> list(
            @RequestParam(name = "recon_type", required = false) List<ReconciliationType> reconTypes,
            @RequestParam(name = "status", required = false) List<ReconciliationStatus> statuses,
            @RequestParam(name = "reference_id", required = false) Long referenceId,
            @RequestParam(name = "from_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "to_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {

        // Rejected rather than returned empty: an inverted range is a contradiction, and an empty
        // page on a discrepancy queue reads as "nothing is wrong" — the most expensive possible
        // way to render a typo.
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'from_date' (" + fromDate + ") must not be after 'to_date' (" + toDate + ").");
        }

        ReconciliationQuery query =
                ReconciliationQuery.of(reconTypes, statuses, referenceId, fromDate, toDate, page, size);
        Page<Reconciliation> found = reconciliationQueryService.search(query);

        return ResponseEntity.ok(new ReconciliationListResponse(
                found.getContent().stream().map(ReconciliationView::of).toList(),
                query.page(), query.size(), found.getTotalElements(), found.getTotalPages()));
    }

    /**
     * Single-record detail, for the drilldown an operator does before deciding between
     * {@link #investigate} and a refund. Returns the same projection as a list row — there is no
     * richer per-record detail to show, because {@code reconciliation} carries no notes and no
     * history; the referent itself is fetched from its own endpoint using {@code reference_type} +
     * {@code reference_id}.
     */
    @GetMapping("/admin/reconciliations/{id}")
    @PreAuthorize("hasAuthority('reconciliation:view')")
    public ResponseEntity<ReconciliationView> detail(@PathVariable Long id) {
        return reconciliationQueryService.findById(id)
                .map(reconciliation -> ResponseEntity.ok(ReconciliationView.of(reconciliation)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * {@code page}/{@code size} echo the <em>effective</em> values after clamping (see
     * {@link ReconciliationQuery#of}), not what the caller asked for — a client that requests
     * {@code size=10000} needs to know it received at most {@link ReconciliationQuery#MAX_PAGE_SIZE}
     * rows, or it will read a truncated first page as the complete queue. {@code totalElements}
     * is the count matching the filter, which is what tells it whether to page at all.
     */
    public record ReconciliationListResponse(
            List<ReconciliationView> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }

    /**
     * {@code referenceType} is derived from {@code reconType} rather than stored — see
     * {@link ReconciliationType#referenceType()} for why that mapping is not optional information:
     * {@code reference_id} is polymorphic with no discriminator column, so a row on its own does
     * not say what it points at. It is {@code null} only for {@code PROVIDER_VS_REPORT}, which
     * nothing opens.
     *
     * <p>{@code expectedValue}/{@code actualValue}/{@code discrepancy} are nullable in the table
     * and stay nullable here. {@code discrepancy} is the signed {@code actual - expected} the
     * detector computed, and is the field that makes a row legible: Section 38.2 requires the
     * record, and a negative value is money collected and not delivered (TC-BE-018's
     * {@code -10000}), a positive one money received and not expected (TC-BE-031's {@code +20000}).
     */
    public record ReconciliationView(
            Long id,
            String reconType,
            String referenceType,
            Long referenceId,
            String reconDate,
            Money expectedValue,
            Money actualValue,
            Money discrepancy,
            String status,
            Long resolvedBy,
            String resolvedAt
    ) {
        static ReconciliationView of(Reconciliation reconciliation) {
            return new ReconciliationView(
                    reconciliation.getId(),
                    reconciliation.getReconType().name(),
                    reconciliation.getReconType().referenceType(),
                    reconciliation.getReferenceId(),
                    reconciliation.getReconDate().toString(),
                    reconciliation.getExpectedValue(),
                    reconciliation.getActualValue(),
                    reconciliation.getDiscrepancy(),
                    reconciliation.getStatus().name(),
                    reconciliation.getResolvedBy(),
                    reconciliation.getResolvedAt() == null ? null : reconciliation.getResolvedAt().toString());
        }
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
