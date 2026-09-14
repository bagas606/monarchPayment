package id.ppob2.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.app.settlement.SettlementIngestionOrchestrator;
import id.ppob2.audit.AuditService;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.sharedkernel.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Section 37.1: {@code POST /internal/settlement/ingest}. The request shape here is invented
 * — Section 37.1 explicitly flags the real Ayolinx settlement report format as "file/API, format
 * TBD — must be verified" — same treatment as {@code AyolinxCallbackPayload} in the payment
 * webhook slice: a reasonable placeholder, not a contract.
 *
 * <p>Delegates to {@link SettlementIngestionOrchestrator} for the actual composition (resolving
 * {@code expected_amount} from `payment`, ingesting into `settlement`, and — on a discrepancy —
 * opening a `reconciliation` record) — see that class's Javadoc for why this needs to be one
 * atomic transaction rather than composed here field-by-field.
 *
 * <p>Section 43's "Recorded" requirement, applied here the same as the other three
 * {@code @PreAuthorize}-gated admin actions ({@code AdminFulfillmentController},
 * {@code AdminReconciliationController}) — but the snapshot shape differs from those three:
 * there, the target entity (child order / reconciliation) exists before the call, so a real
 * "before" snapshot can be taken. Here, the {@code settlement} row doesn't exist until {@link
 * SettlementIngestionOrchestrator#ingest} returns — there is nothing to snapshot beforehand, only
 * the request that produced it. This audit entry is therefore recorded post-hoc: {@code
 * beforeState} is always {@code null}, {@code targetId} is the newly-created settlement's id, and
 * {@code afterState} is the resulting settlement.
 *
 * <p><b>Real bug found only by driving this end-to-end against a real Postgres instance and a
 * real HTTP call</b> (not by {@code SettlementIngestionServiceTest}, which exercises the service
 * layer directly and never touches Jackson): the app's global Jackson config is {@code
 * spring.jackson.property-naming-strategy: SNAKE_CASE} (same as {@code AyolinxCallbackPayload}'s
 * own Javadoc already documents), but {@link SettlementIngestionRequest}/{@link
 * SettlementIngestionResponse} previously declared plain camelCase fields with no {@code
 * @JsonNaming} override. A request body shaped like the Java field names (e.g. {@code
 * "actualAmount"}) — the shape anyone reaching for these record names would naturally send —
 * silently deserialized to an all-null record instead of failing validation, since Jackson found
 * no {@code snake_case}-named property to bind any field to; that null then reached {@link
 * id.ppob2.sharedkernel.money.Money#of(java.math.BigInteger)}, which threw a bare {@code
 * NullPointerException} rather than a clear {@code VALIDATION_ERROR}. Confirmed live: a
 * camelCase-keyed request produced the NPE; the identical request re-sent with {@code
 * snake_case} keys (the shape the global config actually expects) ingested correctly. Fixed by
 * giving both records their own {@code @JsonNaming(LowerCamelCaseStrategy.class)} override — the
 * same mechanism {@code AyolinxCallbackPayload} already uses for the opposite reason (there, to
 * accept Ayolinx's fixed external camelCase contract despite the global default; here, so this
 * internal ops endpoint's request/response shape matches its own Java field names rather than
 * silently depending on a global default a caller has no reason to know about) — applied to both
 * records so the endpoint's input and output casing stay symmetric, not just the input.
 */
@RestController
public class SettlementIngestionController {

    private final SettlementIngestionOrchestrator orchestrator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public SettlementIngestionController(SettlementIngestionOrchestrator orchestrator, AuditService auditService,
                                          ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/internal/settlement/ingest")
    @PreAuthorize("hasAuthority('settlement:ingest')")
    public ResponseEntity<SettlementIngestionResponse> ingest(@RequestBody SettlementIngestionRequest request,
                                                               @AuthenticationPrincipal AdminPrincipal principal,
                                                               HttpServletRequest httpRequest) {
        Money actualAmount = Money.of(request.actualAmount());
        Money feeAmount = request.feeAmount() != null ? Money.of(request.feeAmount()) : null;

        Settlement settlement = orchestrator.ingest(request.settlementDate(), request.pgReference(), actualAmount, feeAmount);

        auditService.recordAdminAction(principal.getAdminUserId(), "SETTLEMENT_INGEST", "SETTLEMENT", settlement.getId(),
                null, snapshot(settlement), httpRequest.getRemoteAddr());

        return ResponseEntity.ok(new SettlementIngestionResponse(
                settlement.getId(), settlement.getStatus().name(), settlement.getExpectedAmount(), actualAmount, feeAmount));
    }

    private String snapshot(Settlement settlement) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", settlement.getStatus().name(),
                    "expectedAmount", settlement.getExpectedAmount().toString(),
                    "actualAmount", settlement.getActualAmount().toString()));
        } catch (Exception e) {
            return "{}";
        }
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SettlementIngestionRequest(
            @NotNull LocalDate settlementDate,
            @NotBlank String pgReference,
            @NotNull BigInteger actualAmount,
            BigInteger feeAmount
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record SettlementIngestionResponse(
            Long settlementId,
            String status,
            Money expectedAmount,
            Money actualAmount,
            Money feeAmount
    ) {
    }
}
