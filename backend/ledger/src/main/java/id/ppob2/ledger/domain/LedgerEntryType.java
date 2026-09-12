package id.ppob2.ledger.domain;

/** PRD Section 22.21: {@code amount} is always positive, sign is carried by this field. */
public enum LedgerEntryType {
    DEBIT,
    CREDIT
}
