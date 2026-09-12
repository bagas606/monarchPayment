-- PRD Section 22.7

CREATE TABLE provider_price (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_sku_id  BIGINT         NOT NULL REFERENCES provider_sku(id),
    pricing_version  INT            NOT NULL,
    provider_cost    NUMERIC(18,0)  NOT NULL,
    effective_from   TIMESTAMPTZ    NOT NULL,
    effective_until  TIMESTAMPTZ,
    CONSTRAINT provider_price_sku_version_uk UNIQUE (provider_sku_id, pricing_version)
);

-- Enforces "at most one active version per SKU" at the database level, not just in
-- ProviderPriceService's read-then-write — a partial unique index on the FK alone (the
-- effective_until IS NULL filter means every indexed row has the same effective_until, so
-- indexing provider_sku_id alone is sufficient to make it unique among active rows).
CREATE UNIQUE INDEX provider_price_active_idx ON provider_price (provider_sku_id)
    WHERE effective_until IS NULL;
