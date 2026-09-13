-- PRD Section 23.8: "retry-with-backoff on non-2xx (max attempts configurable, e.g., 5 attempts
-- over 24h)". No such table is named in Section 22 — webhook_event (22.24) is an append-only log
-- (one row per attempt, no mutable state), which is the wrong shape for tracking a delivery's
-- in-progress retry schedule (attempt count, next-attempt-at). This is a new table for that
-- reason, not an invented substitute for a documented one.
CREATE TABLE webhook_delivery (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_order_id      BIGINT       NOT NULL,
    event_type           VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    attempt_count        INTEGER      NOT NULL,
    next_attempt_at      TIMESTAMPTZ,
    last_attempt_at      TIMESTAMPTZ  NOT NULL,
    last_failure_reason  TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most one in-flight delivery tracker per (order, event type) — the orchestrator's own first
-- attempt already happened synchronously before a row here is ever created, so a duplicate would
-- mean the same terminal-state notification being scheduled twice for the same order.
CREATE UNIQUE INDEX webhook_delivery_order_event_uk ON webhook_delivery (parent_order_id, event_type);

-- The sweep job's only query shape: "PENDING rows due now", bounded and ordered by nothing in
-- particular beyond that — matching parent_order's own expiry-sweep index precedent.
CREATE INDEX webhook_delivery_pending_idx ON webhook_delivery (status, next_attempt_at);

CREATE TRIGGER webhook_delivery_set_updated_at
    BEFORE UPDATE ON webhook_delivery
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
