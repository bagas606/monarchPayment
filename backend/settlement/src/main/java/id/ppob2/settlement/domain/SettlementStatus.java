package id.ppob2.settlement.domain;

/**
 * PRD Section 22.22. {@code PENDING} is schema-supported but unreachable in this codebase's
 * ingestion flow — {@link id.ppob2.settlement.SettlementIngestionService} always resolves
 * directly to {@code MATCHED}/{@code DISCREPANCY} in one shot, since it models "here's the PG
 * settlement report, process it now" rather than a two-phase flow where an expected-only row is
 * created ahead of the actual report arriving.
 */
public enum SettlementStatus {
    PENDING,
    MATCHED,
    DISCREPANCY
}
