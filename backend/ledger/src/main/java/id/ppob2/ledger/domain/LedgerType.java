package id.ppob2.ledger.domain;

/** PRD Section 36.1's four separate, append-only ledgers. */
public enum LedgerType {
    ORDER,
    PAYMENT,
    PROVIDER,
    SETTLEMENT
}
