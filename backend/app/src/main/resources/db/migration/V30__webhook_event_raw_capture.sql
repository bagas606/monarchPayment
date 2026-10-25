-- Inbound callbacks are signed over the exact request bytes, but webhook_event.payload is JSONB, which drops
-- whitespace and reorders keys, and the signature headers were not stored at all. These two columns keep what is
-- needed to re-verify a signature or investigate a rejection later. NULL for outbound rows and for rows written
-- before this migration.
ALTER TABLE webhook_event
    ADD COLUMN raw_body    TEXT,
    ADD COLUMN raw_headers JSONB;
