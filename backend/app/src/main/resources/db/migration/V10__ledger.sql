-- PRD Section 22.21

CREATE TABLE ledger_entry (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_type     VARCHAR(16)   NOT NULL,
    reference_type  VARCHAR(32)   NOT NULL,
    reference_id    BIGINT        NOT NULL,
    entry_type      VARCHAR(16)   NOT NULL,
    amount          NUMERIC(18,0) NOT NULL,
    -- Section 22.21 specifies CHAR(3); VARCHAR(3) is used instead for the same reason as
    -- decomposition_pattern.pattern_hash (V8) — Hibernate 6 schema-validate maps a plain String
    -- @Column to VARCHAR by default, and Postgres's CHAR (bpchar) doesn't match without an extra
    -- @JdbcTypeCode annotation. Functionally identical for a fixed-length currency code.
    currency        VARCHAR(3)    NOT NULL DEFAULT 'IDR',
    description     VARCHAR(255),
    posted_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entry_ref_idx ON ledger_entry (reference_type, reference_id);
CREATE INDEX ledger_entry_type_idx ON ledger_entry (ledger_type);

-- Section 22.21: "Append-only — no UPDATE/DELETE permitted at application level (enforce via DB
-- role privileges / triggers)." This is the trigger half of that sentence — real enforcement, not
-- just an application-layer convention (LedgerService/LedgerEntryRepository never expose an
-- update/delete path either, but this is what actually stops a mistake from succeeding).
CREATE OR REPLACE FUNCTION reject_ledger_entry_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only (Section 22.21) — % is not permitted; post a reversing/adjusting entry instead', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_reject_update
    BEFORE UPDATE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_entry_mutation();

CREATE TRIGGER ledger_entry_reject_delete
    BEFORE DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_entry_mutation();
