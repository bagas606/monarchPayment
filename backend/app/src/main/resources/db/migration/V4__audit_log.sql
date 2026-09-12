-- PRD Section 22.27. Append-only: no update/delete grants beyond what the app role needs.

CREATE TABLE audit_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_type   VARCHAR(16)  NOT NULL,
    actor_id     BIGINT,
    action       VARCHAR(64)  NOT NULL,
    target_type  VARCHAR(64)  NOT NULL,
    target_id    BIGINT,
    before_state JSONB,
    after_state  JSONB,
    ip_address   VARCHAR(64),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_actor_idx ON audit_log (actor_type, actor_id);
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id);
CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at);
