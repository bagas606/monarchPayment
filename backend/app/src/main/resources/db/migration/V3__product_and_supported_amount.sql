-- PRD Section 22.4, 22.8

CREATE TABLE product (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    category    VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT product_code_uk UNIQUE (code)
);

CREATE TRIGGER product_set_updated_at
    BEFORE UPDATE ON product
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE supported_amount (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_category  VARCHAR(64)    NOT NULL,
    amount            NUMERIC(18,0)  NOT NULL,
    status            VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    effective_from    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT supported_amount_uk UNIQUE (product_category, amount)
);

CREATE TRIGGER supported_amount_set_updated_at
    BEFORE UPDATE ON supported_amount
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
