-- PRD Section 22.9, 22.10, 22.11, 22.12, 22.13, 22.14

CREATE TABLE pattern_generation (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status        VARCHAR(16)  NOT NULL,
    triggered_by  VARCHAR(32)  NOT NULL,
    scope         VARCHAR(16)  NOT NULL,
    started_at    TIMESTAMPTZ  NOT NULL,
    completed_at  TIMESTAMPTZ,
    activated_at  TIMESTAMPTZ,
    pattern_count BIGINT,
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX pattern_generation_status_idx ON pattern_generation (status);

CREATE TRIGGER pattern_generation_set_updated_at
    BEFORE UPDATE ON pattern_generation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE decomposition_pattern (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    generation_id     BIGINT        NOT NULL REFERENCES pattern_generation(id),
    parent_amount     NUMERIC(18,0) NOT NULL,
    components        JSONB         NOT NULL,
    component_count   INT           NOT NULL,
    total_quantity    INT           NOT NULL,
    -- Section 22.10 specifies CHAR(64); VARCHAR(64) is used instead because Hibernate 6's
    -- schema-validate mode maps a plain String @Column to VARCHAR by default, and Postgres's
    -- CHAR (bpchar) doesn't match that without an extra @JdbcTypeCode annotation — functionally
    -- identical for a fixed-length SHA-256 hex digest.
    pattern_hash      VARCHAR(64)   NOT NULL,
    structural_status VARCHAR(16)   NOT NULL DEFAULT 'VALID',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT decomposition_pattern_hash_uk UNIQUE (generation_id, pattern_hash)
);

CREATE INDEX decomposition_pattern_amount_gen_idx ON decomposition_pattern (generation_id, parent_amount);

CREATE TRIGGER decomposition_pattern_set_updated_at
    BEFORE UPDATE ON decomposition_pattern
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE decomposition_component (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pattern_id       BIGINT        NOT NULL REFERENCES decomposition_pattern(id),
    provider_sku_id  BIGINT        NOT NULL REFERENCES provider_sku(id),
    quantity         INT           NOT NULL,
    face_value       NUMERIC(18,0) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX decomposition_component_pattern_idx ON decomposition_component (pattern_id);
CREATE INDEX decomposition_component_sku_idx ON decomposition_component (provider_sku_id);

CREATE TRIGGER decomposition_component_set_updated_at
    BEFORE UPDATE ON decomposition_component
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE pattern_economics (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pattern_id           BIGINT        NOT NULL REFERENCES decomposition_pattern(id),
    snapshot_date        DATE          NOT NULL,
    provider_cost_total  NUMERIC(18,0) NOT NULL,
    gross_profit         NUMERIC(18,0) NOT NULL,
    gross_margin_pct     NUMERIC(7,4)  NOT NULL,
    payment_fee          NUMERIC(18,0) NOT NULL,
    net_contribution     NUMERIC(18,0) NOT NULL,
    net_margin_pct       NUMERIC(7,4)  NOT NULL,
    score                NUMERIC(10,4) NOT NULL,
    eligible             BOOLEAN       NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pattern_economics_uk UNIQUE (pattern_id, snapshot_date)
);

CREATE INDEX pattern_economics_lookup_idx ON pattern_economics (snapshot_date, eligible, score DESC);

CREATE TRIGGER pattern_economics_set_updated_at
    BEFORE UPDATE ON pattern_economics
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE pattern_usage (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pattern_id     BIGINT      NOT NULL REFERENCES decomposition_pattern(id),
    usage_date     DATE        NOT NULL,
    daily_usage    BIGINT      NOT NULL DEFAULT 0,
    lifetime_usage BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pattern_usage_uk UNIQUE (pattern_id, usage_date)
);

CREATE TRIGGER pattern_usage_set_updated_at
    BEFORE UPDATE ON pattern_usage
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE sku_usage (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_sku_id  BIGINT      NOT NULL REFERENCES provider_sku(id),
    usage_date       DATE        NOT NULL,
    daily_usage      BIGINT      NOT NULL DEFAULT 0,
    lifetime_usage   BIGINT      NOT NULL DEFAULT 0,
    remaining_quota  INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sku_usage_uk UNIQUE (provider_sku_id, usage_date)
);

CREATE TRIGGER sku_usage_set_updated_at
    BEFORE UPDATE ON sku_usage
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
