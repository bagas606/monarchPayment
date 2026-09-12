-- Section 23.4's documented Create Order request body includes "callback_url", but Section
-- 22.15's parent_order schema has no column for it — CreateOrderController already accepted and
-- silently dropped it (flagged in an earlier slice). Filling that gap.
ALTER TABLE parent_order ADD COLUMN callback_url VARCHAR(500);

-- Section 23.8 requires signing outbound requests with "PPOB1's registered secret," but no
-- documented table (partner, api_client) has a column for it — a real gap, not an invented
-- feature. Same caveat as api_client.secret_hash: verifying/reproducing an HMAC requires the
-- actual secret, not a one-way hash, so despite the name this must be the reversible signing
-- secret; worth raising with the PRD owner same as that column was.
ALTER TABLE partner ADD COLUMN webhook_secret VARCHAR(255);
