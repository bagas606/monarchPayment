package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.app.admin.AdminPatternReader;
import id.ppob2.app.admin.AdminPatternReader.GenerationDetail;
import id.ppob2.app.admin.AdminPatternReader.GenerationRow;
import id.ppob2.app.admin.AdminPatternReader.PatternDetail;
import id.ppob2.app.admin.AdminPatternReader.PatternRow;
import id.ppob2.app.admin.AdminTransactionReader.Page;
import id.ppob2.app.admin.Paging;
import id.ppob2.audit.AuditService;
import id.ppob2.decomposition.generation.PatternGenerationService;
import id.ppob2.decomposition.generation.PatternGenerationService.Activation;
import id.ppob2.decomposition.scoring.PatternScoringService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section 41.4 (decomposition) and the pattern half of 41.5: trigger and monitor generations, look patterns up, activate or
 * roll back. Reads need {@code pattern:view}; starting a build {@code pattern:generate}; activating, rolling back and
 * discarding {@code pattern:activate} (Section 42.2 lists activation among the critical, minimal-role operations).
 *
 * <p>Every mutation is audited with the acting admin and the facts that matter (what was served before and after, and the
 * coverage change). Failed attempts are not, matching the other admin actions: nothing changed.
 */
@RestController
public class AdminPatternController {

    private final PatternGenerationService generations;
    private final PatternScoringService scoring;
    private final AdminPatternReader reader;
    private final AuditService auditService;
    private final ObjectMapper mapper;
    private final Clock businessClock;

    public AdminPatternController(PatternGenerationService generations, PatternScoringService scoring, AdminPatternReader reader,
                                  AuditService auditService, ObjectMapper mapper, Clock businessClock) {
        this.generations = generations;
        this.scoring = scoring;
        this.reader = reader;
        this.auditService = auditService;
        this.mapper = mapper;
        this.businessClock = businessClock;
    }

    @GetMapping("/admin/pattern-generations")
    @PreAuthorize("hasAuthority('pattern:view')")
    public ResponseEntity<Page<GenerationRow>> list(@RequestParam(name = "page", required = false) Integer page,
                                                    @RequestParam(name = "size", required = false) Integer size) {
        return ResponseEntity.ok(reader.listGenerations(Paging.of(page, size)));
    }

    @GetMapping("/admin/pattern-generations/{id}")
    @PreAuthorize("hasAuthority('pattern:view')")
    public ResponseEntity<GenerationDetail> detail(@PathVariable("id") long id) {
        return ResponseEntity.ok(reader.generation(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Generation not found.")));
    }

    @PostMapping("/admin/pattern-generations")
    @PreAuthorize("hasAuthority('pattern:generate')")
    public ResponseEntity<?> start(@AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest request) {
        long id = generations.begin("ADMIN:" + principal.getAdminUserId());
        auditService.recordAdminAction(principal.getAdminUserId(), "PATTERN_GENERATION_START", "PATTERN_GENERATION", id,
                "{}", "{\"status\":\"BUILDING\",\"scope\":\"FULL\"}", request.getRemoteAddr());
        generations.launch(id);
        return ResponseEntity.accepted().body(Map.of("generation_id", id, "status", "BUILDING"));
    }

    @PostMapping("/admin/pattern-generations/{id}/activate")
    @PreAuthorize("hasAuthority('pattern:activate')")
    public ResponseEntity<?> activate(@PathVariable("id") long id, @RequestBody(required = false) ReasonBody body,
                                      @AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest request) throws Exception {
        String reason = body == null || body.reason() == null ? null : body.reason().trim();
        Long before = reader.activeGenerationId().orElse(null);
        Activation a = generations.activate(id, reason);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("active_generation_id", id);
        after.put("superseded_generation_id", a.superseded());
        after.put("lost_coverage", a.lostCoverage().size());
        after.put("gained_coverage", a.gainedCoverage().size());
        if (reason != null && !reason.isEmpty()) {
            after.put("reason", reason);
        }
        auditService.recordAdminAction(principal.getAdminUserId(), "PATTERN_GENERATION_ACTIVATE", "PATTERN_GENERATION", id,
                mapper.writeValueAsString(Map.of("active_generation_id", before == null ? "none" : before)),
                mapper.writeValueAsString(after), request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("active_generation_id", id, "superseded_generation_id", a.superseded() == null ? "" : a.superseded(),
                "lost_coverage", a.lostCoverage(), "gained_coverage", a.gainedCoverage()));
    }

    @PostMapping("/admin/pattern-generations/{id}/discard")
    @PreAuthorize("hasAuthority('pattern:activate')")
    public ResponseEntity<?> discard(@PathVariable("id") long id, @AuthenticationPrincipal AdminPrincipal principal,
                                     HttpServletRequest request) {
        generations.discard(id);
        auditService.recordAdminAction(principal.getAdminUserId(), "PATTERN_GENERATION_DISCARD", "PATTERN_GENERATION", id,
                "{\"status\":\"VALIDATING\"}", "{\"status\":\"FAILED\"}", request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("generation_id", id, "status", "FAILED"));
    }

    /** Re-prices and re-scores the ACTIVE generation for today now, instead of waiting for the nightly pass (Section 30.2). */
    @PostMapping("/admin/pattern-economics/rescore")
    @PreAuthorize("hasAuthority('pattern:generate')")
    public ResponseEntity<?> rescore(@AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest request) {
        long gen = reader.activeGenerationId().orElseThrow(() -> new ApiException(ErrorCode.GENERATION_NOT_ACTIVATABLE,
                "There is no ACTIVE generation to re-score."));
        int rows = scoring.rescore(gen, LocalDate.now(businessClock));
        auditService.recordAdminAction(principal.getAdminUserId(), "PATTERN_ECONOMICS_RESCORE", "PATTERN_GENERATION", gen,
                "{}", "{\"rows\":" + rows + "}", request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("generation_id", gen, "patterns_scored", rows));
    }

    @GetMapping("/admin/patterns")
    @PreAuthorize("hasAuthority('pattern:view')")
    public ResponseEntity<Page<PatternRow>> patterns(
            @RequestParam(name = "generation_id", required = false) Long generationId,
            @RequestParam(name = "parent_amount", required = false) Long parentAmount,
            @RequestParam(name = "product_id", required = false) Long productId,
            @RequestParam(name = "structural_status", required = false) String structuralStatus,
            @RequestParam(name = "eligible", required = false) Boolean eligible,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ResponseEntity.ok(reader.listPatterns(generationId, parentAmount, productId,
                structuralStatus == null || structuralStatus.isBlank() ? null : structuralStatus.trim(), eligible, Paging.of(page, size)));
    }

    @GetMapping("/admin/patterns/{id}")
    @PreAuthorize("hasAuthority('pattern:view')")
    public ResponseEntity<PatternDetail> pattern(@PathVariable("id") long id) {
        return ResponseEntity.ok(reader.pattern(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pattern not found.")));
    }

    public record ReasonBody(String reason) {
    }
}
