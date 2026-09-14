-- PRD Section 22.28 / 37.3
-- Records how one platform-aggregate `settlement` batch is attributed back across the partner(s)
-- whose payments contributed to it. Ayolinx settles one figure per settlement_date with no
-- partner awareness (see `settlement`, V11) -- this table is PPOB2's own computed attribution,
-- not something reported by the PG. See SettlementAllocationService for the EXACT/PRO_RATA
-- methodology and the largest-remainder rounding rule.

CREATE TABLE settlement_partner_allocation (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_id      BIGINT        NOT NULL REFERENCES settlement(id),
    partner_id         BIGINT        NOT NULL REFERENCES partner(id),
    gross_amount       NUMERIC(18,0) NOT NULL,
    fee_allocated      NUMERIC(18,0) NOT NULL,
    net_amount         NUMERIC(18,0) NOT NULL,
    allocation_method  VARCHAR(16)   NOT NULL,
    computed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- At most one allocation row per partner per settlement -- a re-computation (e.g. after a
    -- DISCREPANCY settlement moves to RESOLVED, per Section 37.3) replaces the existing rows for
    -- that settlement_id rather than accumulating duplicates.
    CONSTRAINT settlement_partner_allocation_uk UNIQUE (settlement_id, partner_id)
);

CREATE INDEX settlement_partner_allocation_settlement_idx ON settlement_partner_allocation (settlement_id);
