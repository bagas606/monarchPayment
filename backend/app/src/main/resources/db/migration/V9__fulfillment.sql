-- PRD Section 22.16, 22.19

CREATE TABLE child_order (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_order_id          BIGINT      NOT NULL REFERENCES parent_order(id),
    provider_sku_id          BIGINT      NOT NULL REFERENCES provider_sku(id),
    quantity                 INT         NOT NULL,
    sequence_no              INT         NOT NULL,
    state                    VARCHAR(32) NOT NULL,
    provider_transaction_id  BIGINT,
    attempt_count            INT         NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX child_order_parent_idx ON child_order (parent_order_id);
CREATE INDEX child_order_state_idx ON child_order (state);

CREATE TRIGGER child_order_set_updated_at
    BEFORE UPDATE ON child_order
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE provider_transaction (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    child_order_id      BIGINT      NOT NULL REFERENCES child_order(id),
    provider_id         BIGINT      NOT NULL REFERENCES provider(id),
    provider_reference  VARCHAR(128),
    idempotency_key     VARCHAR(128) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    request_payload     JSONB,
    response_payload    JSONB,
    latency_ms          INT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT provider_transaction_idem_uk UNIQUE (provider_id, idempotency_key)
);

CREATE INDEX provider_transaction_child_idx ON provider_transaction (child_order_id);

CREATE TRIGGER provider_transaction_set_updated_at
    BEFORE UPDATE ON provider_transaction
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Deferred: child_order.provider_transaction_id references provider_transaction, which didn't
-- exist yet when child_order was created above (the two tables reference each other).
ALTER TABLE child_order
    ADD CONSTRAINT child_order_provider_transaction_fkey
    FOREIGN KEY (provider_transaction_id) REFERENCES provider_transaction(id);
