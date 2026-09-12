-- PRD Section 22.1, 22.2, 22.3

CREATE TABLE channel (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT channel_code_uk UNIQUE (code)
);

CREATE TRIGGER channel_set_updated_at
    BEFORE UPDATE ON channel
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE partner (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    channel_id    BIGINT REFERENCES channel(id),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    contract_ref  VARCHAR(128),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT partner_code_uk UNIQUE (code)
);

CREATE TRIGGER partner_set_updated_at
    BEFORE UPDATE ON partner
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE api_client (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_id           VARCHAR(64)  NOT NULL,
    partner_id          BIGINT NOT NULL REFERENCES partner(id),
    secret_hash         VARCHAR(255) NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    rate_limit_per_min  INT NOT NULL DEFAULT 600,
    allowed_ip_cidr     TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT api_client_client_id_uk UNIQUE (client_id)
);

CREATE INDEX api_client_partner_id_idx ON api_client (partner_id);

CREATE TRIGGER api_client_set_updated_at
    BEFORE UPDATE ON api_client
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
