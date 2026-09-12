-- PRD Section 22.15, 22.17, 22.18

CREATE SEQUENCE parent_order_no_seq;

CREATE TABLE parent_order (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no            VARCHAR(64)   NOT NULL,
    channel_id          BIGINT        NOT NULL REFERENCES channel(id),
    partner_id          BIGINT REFERENCES partner(id),
    client_id           VARCHAR(64)   NOT NULL,
    user_id             VARCHAR(64),
    product_id          BIGINT        NOT NULL REFERENCES product(id),
    parent_amount       NUMERIC(18,0) NOT NULL,
    order_source        VARCHAR(32)   NOT NULL,
    idempotency_key     VARCHAR(128)  NOT NULL,
    state               VARCHAR(32)   NOT NULL,
    pattern_id          BIGINT,
    customer_reference  VARCHAR(128),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT parent_order_idem_uk UNIQUE (client_id, idempotency_key),
    CONSTRAINT parent_order_order_no_uk UNIQUE (order_no)
);

CREATE INDEX parent_order_state_idx ON parent_order (state);
CREATE INDEX parent_order_created_idx ON parent_order (created_at);

CREATE TRIGGER parent_order_set_updated_at
    BEFORE UPDATE ON parent_order
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE payment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_order_id  BIGINT        NOT NULL REFERENCES parent_order(id),
    pg_reference     VARCHAR(128),
    method           VARCHAR(32)   NOT NULL DEFAULT 'QRIS_DYNAMIC',
    qr_payload       TEXT,
    amount           NUMERIC(18,0) NOT NULL,
    status           VARCHAR(32)   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT payment_parent_order_uk UNIQUE (parent_order_id)
);

CREATE INDEX payment_pg_reference_idx ON payment (pg_reference);

CREATE TRIGGER payment_set_updated_at
    BEFORE UPDATE ON payment
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE payment_event (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id       BIGINT       NOT NULL REFERENCES payment(id),
    event_type       VARCHAR(32)  NOT NULL,
    raw_payload      JSONB        NOT NULL,
    signature_valid  BOOLEAN      NOT NULL,
    dedup_key        VARCHAR(128) NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payment_event_dedup_uk UNIQUE (dedup_key)
);

CREATE INDEX payment_event_payment_idx ON payment_event (payment_id);
