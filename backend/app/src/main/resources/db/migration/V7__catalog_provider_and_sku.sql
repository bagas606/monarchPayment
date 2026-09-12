-- PRD Section 22.5, 22.6

CREATE TABLE provider (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(255) NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    timeout_ms          INT          NOT NULL DEFAULT 8000,
    rate_limit_per_min  INT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT provider_code_uk UNIQUE (code)
);

CREATE TRIGGER provider_set_updated_at
    BEFORE UPDATE ON provider
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE provider_sku (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id        BIGINT        NOT NULL REFERENCES provider(id),
    product_id         BIGINT        NOT NULL REFERENCES product(id),
    provider_sku_code  VARCHAR(64)   NOT NULL,
    face_value         NUMERIC(18,0) NOT NULL,
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    quota_daily        INT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT provider_sku_uk UNIQUE (provider_id, provider_sku_code)
);

CREATE INDEX provider_sku_product_idx ON provider_sku (product_id);

CREATE TRIGGER provider_sku_set_updated_at
    BEFORE UPDATE ON provider_sku
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
