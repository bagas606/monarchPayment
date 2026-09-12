package id.ppob2.order.domain;

/** Child order states, PRD Section 22.16. */
public enum ChildOrderState {
    PENDING,
    EXECUTING,
    SUCCESS,
    FAILED,
    COMPENSATED
}
