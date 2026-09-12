-- Deviation from Section 22.16's documented schema, which has no such column. Added because
-- ORDER_VS_FULFILLMENT reconciliation (Section 38.1) needs each child order's contribution to
-- parent_amount, and re-deriving it later by joining decomposition_component on provider_sku_id
-- is not safe: nothing constrains a pattern to at most one component per SKU, so that join can
-- pick the wrong row or double-count. Storing it once, at creation, from the same
-- catalog.provider_sku.face_value PatternLookupService already trusts, avoids the unsafe join
-- entirely. Total value for this child order (per-unit face_value x quantity), not per-unit.
ALTER TABLE child_order ADD COLUMN face_value NUMERIC(18,0) NOT NULL DEFAULT 0;
