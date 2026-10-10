package id.ppob2.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link ReconciliationType#referenceType()}. The mapping is derived, not stored, and it is
 * the only thing that says what a row's {@code reference_id} points at — {@code reconciliation}
 * has no discriminator column, so getting one of these wrong does not fail anywhere: it makes an
 * Admin Web drilldown fetch payment 7 when the discrepancy is about parent_order 7, which reads as
 * a plausible record rather than as an error.
 *
 * <p>Each expectation below is pinned to the single {@code ReconciliationService.open} call site
 * that produces that type (named in the enum's own Javadoc). The last test is the one that matters
 * most over time: it fails when a sixth type is added without a mapping decision, which is the
 * only way this file can keep being true.
 */
class ReconciliationTypeTest {

    @Test
    void paymentVsPgReferencesThePayment() {
        // PaymentCallbackAnomalyOrchestrator passes event.paymentId().
        assertThat(ReconciliationType.PAYMENT_VS_PG.referenceType()).isEqualTo("PAYMENT");
    }

    @Test
    void paymentVsSettlementReferencesTheSettlementBatchNotAPayment() {
        // SettlementIngestionOrchestrator passes settlement.getId(). The name contains "PAYMENT",
        // which is exactly how this one would get mapped wrong by reading the name instead of the
        // call site: the discrepancy is between a day's payments and one settlement batch, and the
        // row points at the batch.
        assertThat(ReconciliationType.PAYMENT_VS_SETTLEMENT.referenceType()).isEqualTo("SETTLEMENT");
    }

    @Test
    void orderVsFulfillmentReferencesTheParentOrder() {
        // OrderFulfillmentReconciliationOrchestrator (both paths) and the late-callback path in
        // PaymentCallbackAnomalyOrchestrator all pass a parentOrderId.
        assertThat(ReconciliationType.ORDER_VS_FULFILLMENT.referenceType()).isEqualTo("PARENT_ORDER");
    }

    @Test
    void marginReferencesTheParentOrderNotThePattern() {
        // MarginReconciliationOrchestrator compares a pattern_economics snapshot against actuals,
        // but opens the record against the parentOrderId, not the patternId.
        assertThat(ReconciliationType.MARGIN_EXPECTED_VS_ACTUAL.referenceType()).isEqualTo("PARENT_ORDER");
    }

    @Test
    void providerVsReportHasNoReferentBecauseNothingOpensIt() {
        // Deliberately null, not a guess: there is no provider report ingestion source in this
        // codebase, so no call site has ever decided what this type's reference_id means. A
        // plausible-looking "PROVIDER_TRANSACTION" here would be an invention that a UI would then
        // trust.
        assertThat(ReconciliationType.PROVIDER_VS_REPORT.referenceType()).isNull();
    }

    @Test
    void everyTypeEitherHasAReferentOrIsTheKnownUnopenedOne() {
        assertThat(Arrays.stream(ReconciliationType.values())
                .filter(type -> type.referenceType() == null)
                .toList())
                .as("a new reconciliation type must declare what its reference_id points at; "
                        + "PROVIDER_VS_REPORT is the only value allowed to leave it unanswered, and only "
                        + "because nothing opens it")
                .containsExactly(ReconciliationType.PROVIDER_VS_REPORT);
    }
}
