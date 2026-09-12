-- PRD Section 22.24. `payment_event` (Section 22.18) already exists from V5.

CREATE TABLE webhook_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    direction    VARCHAR(8)   NOT NULL,
    source       VARCHAR(32)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    dedup_key    VARCHAR(128),
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX webhook_event_dedup_idx ON webhook_event (dedup_key);
