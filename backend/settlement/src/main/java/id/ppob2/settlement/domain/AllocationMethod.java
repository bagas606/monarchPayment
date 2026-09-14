package id.ppob2.settlement.domain;

/**
 * PRD Section 37.3. Which of the two attribution methods produced a {@link
 * SettlementPartnerAllocation} row.
 *
 * <p>{@code EXACT} requires the Ayolinx settlement report to carry per-transaction line items so
 * each settled line can be joined back to its originating {@code payment} and that payment's
 * {@code parent_order.partner_id} directly -- no apportionment. Section 37.1 flags the real report
 * format as "file/API, format TBD -- must be verified," and this codebase has no data model for a
 * line-itemed report today, so nothing currently produces {@code EXACT} allocations; it exists on
 * this enum so {@link SettlementPartnerAllocation} and its consumers (Admin Web, Section 41.8) do
 * not need a schema change the day a line-itemed report becomes available -- only a new producer.
 *
 * <p>{@code PRO_RATA} is what {@link id.ppob2.settlement.SettlementAllocationService} actually
 * computes today: each partner's share of a batch-total settlement, apportioned by that partner's
 * share of {@code expected_amount}, using largest-remainder rounding.
 */
public enum AllocationMethod {
    EXACT,
    PRO_RATA
}
