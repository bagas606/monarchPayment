-- PPOB1's OWN books. Separate database from PPOB2's, not a separate schema in it: the whole point
-- of this simulator is that the two systems share nothing but the Section 23 HTTP contract, and a
-- shared database would let reconciliation cheat by joining to the truth it is supposed to be
-- discovering over the wire.
--
-- Everything here is PPOB1's *belief* about a transaction. The distinction is the reason
-- reconciliation exists at all: a reseller's record of what it sold is not evidence of what the
-- upstream actually did, and the gap between the two is what a recon run is for.

CREATE TABLE ppob1_transaction (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- PPOB1's own primary reference, the one its downstream customer quotes. Sent to PPOB2 as
    -- metadata.reseller_txn_id and reused as the Idempotency-Key, so a retried submit cannot
    -- create a second order upstream (Section 23.2 requires the header on every POST).
    reseller_txn_id     VARCHAR(64)  NOT NULL UNIQUE,
    product_code        VARCHAR(64)  NOT NULL,
    amount              NUMERIC(18,0) NOT NULL,
    customer_reference  VARCHAR(255) NOT NULL,
    -- NULL until PPOB2 answers. A row that has none is the single most important thing a recon run
    -- can find: money may have been taken upstream for an order we cannot name. See local_state
    -- SUBMITTING below.
    ppob2_order_id      VARCHAR(64),
    local_state         VARCHAR(32)  NOT NULL,
    -- The last state PPOB2 told us, verbatim and un-translated, from either a webhook or an
    -- inquiry. Kept alongside local_state rather than replacing it so a disagreement between what
    -- we believe and what we were told stays visible instead of being overwritten.
    ppob2_state         VARCHAR(32),
    qr_payload          TEXT,
    qr_expires_at       TIMESTAMPTZ,
    -- How we last learned ppob2_state: WEBHOOK, INQUIRY, or CREATE_RESPONSE. A terminal state
    -- known only by INQUIRY means the webhook never arrived, which is itself a finding.
    last_source         VARCHAR(16),
    last_synced_at      TIMESTAMPTZ,
    -- Set when PPOB2 refuses the order outright (422 UNSUPPORTED_AMOUNT, 409 conflict, ...), so a
    -- rejection is a recorded outcome rather than an absent row.
    rejection_code      VARCHAR(64),
    rejection_message   TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ppob1_transaction_local_state_idx ON ppob1_transaction (local_state);
CREATE INDEX ppob1_transaction_ppob2_order_id_idx ON ppob1_transaction (ppob2_order_id);

-- Every inbound Section 23.8 webhook, accepted or not. A rejected one is the row that matters most
-- here: if PPOB1 silently dropped a badly-signed callback, nobody would ever know someone had
-- tried, and a signature scheme nobody can observe failing is not a control.
CREATE TABLE ppob1_inbound_webhook (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type         VARCHAR(64),
    ppob2_order_id     VARCHAR(64),
    state              VARCHAR(32),
    -- Section 23.8 puts a nonce in the body and PPOB2 repeats it in the X-Nonce header. Unique so
    -- a redelivery is rejected as a duplicate by the database rather than by a check we might
    -- forget — PPOB2 retries on any non-2xx (4 scheduled retries after the first attempt), so
    -- duplicates are the expected case, not an anomaly.
    nonce              VARCHAR(128) UNIQUE,
    signature_valid    BOOLEAN      NOT NULL,
    verdict            VARCHAR(32)  NOT NULL,
    raw_body           TEXT         NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ppob1_inbound_webhook_order_idx ON ppob1_inbound_webhook (ppob2_order_id);
CREATE INDEX ppob1_inbound_webhook_verdict_idx ON ppob1_inbound_webhook (verdict);

-- One row per transaction per recon run. Append-only by convention (nothing updates it), so a run
-- is a dated snapshot of what disagreed and can be compared against the previous one.
CREATE TABLE ppob1_recon_entry (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id          UUID         NOT NULL,
    run_at          TIMESTAMPTZ  NOT NULL,
    transaction_id  BIGINT       NOT NULL REFERENCES ppob1_transaction(id),
    reseller_txn_id VARCHAR(64)  NOT NULL,
    ppob2_order_id  VARCHAR(64),
    -- What we believed before the run, and what PPOB2 said when asked. Both recorded even on a
    -- MATCH, so the row is readable on its own without re-deriving the comparison.
    local_state     VARCHAR(32)  NOT NULL,
    ppob2_state     VARCHAR(32),
    verdict         VARCHAR(32)  NOT NULL,
    detail          TEXT
);

CREATE INDEX ppob1_recon_entry_run_idx ON ppob1_recon_entry (run_id);
CREATE INDEX ppob1_recon_entry_verdict_idx ON ppob1_recon_entry (verdict);
