package id.ppob2.reconciliation.domain;

/** PRD Section 38.2: "must be moved to INVESTIGATING then RESOLVED" — a specific two-step
 * sequence, not a status any authorized user can jump to directly. See {@link
 * Reconciliation#resolve} for the enforced guard. */
public enum ReconciliationStatus {
    OPEN,
    INVESTIGATING,
    RESOLVED
}
