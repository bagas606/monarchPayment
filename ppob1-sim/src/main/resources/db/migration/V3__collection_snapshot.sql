-- PPOB1's own gross-collected book: per business day, what PPOB1 believes PPOB2 collected on the
-- orders PPOB1 originated. One-sided by construction -- nothing here is reconciled against PPOB2,
-- because PPOB2 exposes no partner-facing settlement figure to reconcile against (its two
-- settlement endpoints are both behind admin RBAC, not the Open API). See ppob1-sim/README.md.
--
-- Deliberately NOT called a receivable. That PPOB1 is owed this money is a commercial term the PRD
-- never states: there is no partner commission or margin model in it, FR-REC-004 says the
-- per-partner allocation "does NOT constitute or trigger a payout to the partner", and BR-REC-002
-- says which party bears the MDR "must be verified against each partner's contract". PPOB2 also
-- pays its upstream providers out of the same collected funds. So the quantity recorded here is
-- "gross collected on PPOB1-originated orders" -- which is the thing comparable to
-- settlement_partner_allocation.gross_amount, and nothing more.

CREATE TABLE ppob1_collection_snapshot (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_date  DATE          NOT NULL,
    -- The zone the business day was cut in, stored with the figure rather than assumed from
    -- config. A snapshot taken under one zone and read back under another would otherwise be
    -- silently incomparable -- and the zone is exactly what decides which day a late-evening sale
    -- belongs to.
    zone_id        VARCHAR(64)   NOT NULL,
    total_count    INTEGER       NOT NULL,
    total_gross    NUMERIC(18,0) NOT NULL,
    -- True when the day still contains IN_FLIGHT or INDETERMINATE transactions, i.e. the figure
    -- can still move. Stored rather than derived so a snapshot read months later still says
    -- whether it was final when taken.
    provisional    BOOLEAN       NOT NULL,
    computed_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Append-only, and deliberately NOT unique on business_date: taking a second snapshot of the same
-- day is the point. The transaction book mutates -- a reconciliation run repairs stale beliefs --
-- so a figure computed today can legitimately differ from the same day computed tomorrow. Each row
-- freezes what PPOB1 believed at one moment, which is what you need when PPOB2 disagrees with a
-- figure you quoted last week.
CREATE INDEX ppob1_collection_snapshot_date_idx ON ppob1_collection_snapshot (business_date, id DESC);

-- Normalised rather than 14 columns on the parent: a new LocalState means a new bucket, and that
-- should be a row, not a migration.
CREATE TABLE ppob1_collection_bucket (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_id BIGINT        NOT NULL REFERENCES ppob1_collection_snapshot(id) ON DELETE CASCADE,
    bucket      VARCHAR(32)   NOT NULL,
    txn_count   INTEGER       NOT NULL,
    gross       NUMERIC(18,0) NOT NULL,
    CONSTRAINT ppob1_collection_bucket_uk UNIQUE (snapshot_id, bucket)
);

CREATE INDEX ppob1_collection_bucket_snapshot_idx ON ppob1_collection_bucket (snapshot_id);
