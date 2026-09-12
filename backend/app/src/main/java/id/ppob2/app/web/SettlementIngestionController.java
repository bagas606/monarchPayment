package id.ppob2.app.web;

import id.ppob2.app.settlement.SettlementIngestionOrchestrator;
import id.ppob2.settlement.domain.Settlement;
import id.ppob2.sharedkernel.money.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigInteger;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
public class SettlementIngestionController {

    private final SettlementIngestionOrchestrator orchestrator;

    public SettlementIngestionController(SettlementIngestionOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/internal/settlement/ingest")
    public ResponseEntity<SettlementIngestionResponse> ingest(@RequestBody SettlementIngestionRequest request) {
        Money actualAmount = Money.of(request.actualAmount());
        Money feeAmount = request.feeAmount() != null ? Money.of(request.feeAmount()) : null;

        Settlement settlement = orchestrator.ingest(request.settlementDate(), request.pgReference(), actualAmount, feeAmount);

        return ResponseEntity.ok(new SettlementIngestionResponse(
                settlement.getId(), settlement.getStatus().name(), settlement.getExpectedAmount(), actualAmount, feeAmount));
    }

    public record SettlementIngestionRequest(
            @NotNull LocalDate settlementDate,
            @NotBlank String pgReference,
            @NotNull BigInteger actualAmount,
            BigInteger feeAmount
    ) {
    }

    public record SettlementIngestionResponse(
            Long settlementId,
            String status,
            Money expectedAmount,
            Money actualAmount,
            Money feeAmount
    ) {
    }
}
