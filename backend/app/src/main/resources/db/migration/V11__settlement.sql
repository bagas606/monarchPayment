-- PRD Section 22.22

CREATE TABLE settlement (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_date  DATE          NOT NULL,
    pg_reference     VARCHAR(128),
    expected_amount  NUMERIC(18,0) NOT NULL,
    actual_amount    NUMERIC(18,0),
    fee_amount       NUMERIC(18,0),
    status           VARCHAR(16)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Not specified by Section 22.22 — added as a protective safeguard, and deliberately scoped
    -- to settlement_date ALONE (not settlement_date + pg_reference): expected_amount is computed
    -- as the sum of ALL SUCCESS payments for the date (Section 37.1's "settlement window" is
    -- simplified to a single calendar day), so a second batch for a date that already has a
    -- settlement row would compare its own partial actual_amount against the SAME cumulative
    -- day-total expected_amount the first batch already matched against — guaranteed DISCREPANCY
    -- regardless of whether the second batch's numbers are actually correct, plus a second
    -- Settlement Ledger CREDIT for money already credited once. One settlement batch per date is
    -- enforced as an explicit assumption rather than left as a silent miscomparison; see
    -- SettlementIngestionService's Javadoc for what a real multi-batch day would need instead.
    CONSTRAINT settlement_date_uk UNIQUE (settlement_date)
);

CREATE INDEX settlement_date_idx ON settlement (settlement_date);

CREATE TRIGGER settlement_set_updated_at
    BEFORE UPDATE ON settlement
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
