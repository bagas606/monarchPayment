-- PRD Section 22.23

CREATE TABLE reconciliation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recon_type      VARCHAR(32)   NOT NULL,
    recon_date      DATE          NOT NULL,
    reference_id    BIGINT,
    expected_value  NUMERIC(18,0),
    actual_value    NUMERIC(18,0),
    discrepancy     NUMERIC(18,0),
    status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    -- Section 22.23 names this FK -> admin_user.id; the `admin` module and admin_user table
    -- don't exist yet, so this is a plain unconstrained column, same treatment as every other
    -- cross-module reference in this codebase.
    resolved_by     BIGINT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX reconciliation_type_date_idx ON reconciliation (recon_type, recon_date);

CREATE TRIGGER reconciliation_set_updated_at
    BEFORE UPDATE ON reconciliation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
