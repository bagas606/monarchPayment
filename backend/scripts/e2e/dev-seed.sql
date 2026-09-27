-- Development / test fixture for the PPOB2 backend. NOT run automatically by anything.
--
--   docker exec -i backend-postgres-1 psql -U ppob2 -d ppob2 -v ON_ERROR_STOP=1 -1 \
--     < scripts/e2e/dev-seed.sql
--
-- Deliberately lives in scripts/, NOT under app/src/main/resources/. It briefly did live there, which
-- meant this file — including a SUPER_ADMIN row with a known password hash — was packaged into the
-- production bootJar. Flyway would not have executed it (`locations` is classpath:db/migration), but
-- shipping seeded admin credentials inside the deployable artifact and relying on one config property
-- to keep them inert is not a boundary worth having.
--
-- Every id is resolved by lookup rather than hardcoded. The README used to inline a seed snippet
-- with literal ids; it drifted twice (it named a `provider.provider_code` column that has never
-- existed -- the column is `code` -- and it predated V13's `provider_price`, whose absence makes
-- every child order fail dispatch with "no active provider_price" instead of reaching SUCCESS).
-- Keeping the fixture in one runnable file is what stops that drift recurring.

INSERT INTO channel (code, name) VALUES ('RESELLER_API', 'Reseller API');
INSERT INTO partner (code, name, channel_id, contract_ref)
  SELECT 'PPOB1', 'PPOB1', id, NULL FROM channel WHERE code = 'RESELLER_API';

-- `secret_hash` holds the HMAC verification secret verbatim, not a hash: Section 23.2's signature
-- scheme requires the server to recompute the MAC with the same secret the client used, which a
-- one-way hash cannot do. See HmacAuthenticationFilter's NOTE -- this is a flagged open assumption
-- (Section 73.3) and a real go-live blocker, not a property of this fixture.
INSERT INTO api_client (client_id, partner_id, secret_hash)
  SELECT 'ppob1-client', id, 'dev-secret' FROM partner WHERE code = 'PPOB1';

INSERT INTO product (code, name, category) VALUES ('MOBILE_LEGENDS', 'Mobile Legends', 'GAME_TOPUP');
INSERT INTO supported_amount (product_category, amount) VALUES
  ('GAME_TOPUP', 10000), ('GAME_TOPUP', 20000), ('GAME_TOPUP', 40000),
  ('GAME_TOPUP', 60000), ('GAME_TOPUP', 80000), ('GAME_TOPUP', 100000);

INSERT INTO provider (code, name, status, rate_limit_per_min) VALUES ('PROV1', 'Provider 1', 'ACTIVE', 8000);

-- Four SKUs, one per StubGameProviderAdapter outcome, so each dev-only injection knob has a
-- dedicated sku and no test needs to mutate another test's fixture:
--   A -> succeeds        B -> fail-provider-sku-ids     C -> ambiguous-...     D -> timeout-...
INSERT INTO provider_sku (provider_id, product_id, provider_sku_code, face_value, status)
  SELECT pr.id, pd.id, v.code, 20000, 'ACTIVE'
  FROM provider pr, product pd,
       (VALUES ('ML-20000-A'), ('ML-20000-B'), ('ML-20000-C'), ('ML-20000-D')) AS v(code)
  WHERE pr.code = 'PROV1' AND pd.code = 'MOBILE_LEGENDS';

-- Mandatory since V13: FulfillmentDispatchListener refuses to dispatch a child order whose SKU has
-- no active provider_price rather than calling a provider with an unknown cost.
INSERT INTO provider_price (provider_sku_id, pricing_version, provider_cost, effective_from)
  SELECT id, 1, 18000, now() - interval '1 day' FROM provider_sku;

INSERT INTO pattern_generation (status, triggered_by, scope, started_at, activated_at)
  VALUES ('ACTIVE', 'MANUAL', 'FULL', now(), now());

CREATE TEMP TABLE seed_ids AS SELECT
  (SELECT id FROM pattern_generation ORDER BY id DESC LIMIT 1)         AS gen,
  (SELECT id FROM provider_sku WHERE provider_sku_code = 'ML-20000-A') AS sku_a,
  (SELECT id FROM provider_sku WHERE provider_sku_code = 'ML-20000-B') AS sku_b,
  (SELECT id FROM provider_sku WHERE provider_sku_code = 'ML-20000-C') AS sku_c,
  (SELECT id FROM provider_sku WHERE provider_sku_code = 'ML-20000-D') AS sku_d;

-- Components MUST sum to parent_amount exactly (BR-DEC / Section 28.2, enforced at runtime).
--  20000 -> [A]        all children succeed              (TC-BE-017)
--  40000 -> [A, B]     one of two fails                  (TC-BE-016 / TC-BE-014)
--  60000 -> [A x2, C]  ambiguous -> inquiry              (Section 34.1)
--  80000 -> [A x4]     score 50, the ALTERNATE pattern   (TC-BE-020 / TC-BE-021)
--  80000 -> [C x4]     score 99, the preferred one tests make ineligible
-- 100000 -> [D x5]     provider timeout -> retry x3      (TC-BE-013)
INSERT INTO decomposition_pattern (generation_id, parent_amount, components, component_count, total_quantity, pattern_hash)
  SELECT gen, 20000, json_build_array(json_build_object('provider_sku_id', sku_a, 'quantity', 1, 'face_value', 20000))::text::jsonb, 1, 1, repeat('a', 64) FROM seed_ids
  UNION ALL SELECT gen, 40000, json_build_array(json_build_object('provider_sku_id', sku_a, 'quantity', 1, 'face_value', 20000), json_build_object('provider_sku_id', sku_b, 'quantity', 1, 'face_value', 20000))::text::jsonb, 2, 2, repeat('b', 64) FROM seed_ids
  UNION ALL SELECT gen, 60000, json_build_array(json_build_object('provider_sku_id', sku_a, 'quantity', 2, 'face_value', 20000), json_build_object('provider_sku_id', sku_c, 'quantity', 1, 'face_value', 20000))::text::jsonb, 2, 3, repeat('c', 64) FROM seed_ids
  UNION ALL SELECT gen, 80000, json_build_array(json_build_object('provider_sku_id', sku_a, 'quantity', 4, 'face_value', 20000))::text::jsonb, 1, 4, repeat('d', 64) FROM seed_ids
  UNION ALL SELECT gen, 80000, json_build_array(json_build_object('provider_sku_id', sku_c, 'quantity', 4, 'face_value', 20000))::text::jsonb, 1, 4, repeat('e', 64) FROM seed_ids
  UNION ALL SELECT gen, 100000, json_build_array(json_build_object('provider_sku_id', sku_d, 'quantity', 5, 'face_value', 20000))::text::jsonb, 1, 5, repeat('f', 64) FROM seed_ids;

INSERT INTO decomposition_component (pattern_id, provider_sku_id, quantity, face_value)
  SELECT p.id, i.sku_a, 1, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('a', 64)
  UNION ALL SELECT p.id, i.sku_a, 1, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('b', 64)
  UNION ALL SELECT p.id, i.sku_b, 1, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('b', 64)
  UNION ALL SELECT p.id, i.sku_a, 2, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('c', 64)
  UNION ALL SELECT p.id, i.sku_c, 1, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('c', 64)
  UNION ALL SELECT p.id, i.sku_a, 4, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('d', 64)
  UNION ALL SELECT p.id, i.sku_c, 4, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('e', 64)
  UNION ALL SELECT p.id, i.sku_d, 5, 20000 FROM decomposition_pattern p, seed_ids i WHERE p.pattern_hash = repeat('f', 64);

-- RoutingService reads `score` (pre-computed by the not-yet-built daily re-scoring job) and
-- `eligible`; both 80000 patterns are eligible so routing's choice is genuinely score-driven.
INSERT INTO pattern_economics (pattern_id, snapshot_date, provider_cost_total, gross_profit, gross_margin_pct,
    payment_fee, net_contribution, net_margin_pct, score, eligible)
  SELECT p.id, CURRENT_DATE, p.parent_amount * 0.9, p.parent_amount * 0.1, 10.0, 500,
         p.parent_amount * 0.1 - 500, 9.0,
         CASE p.pattern_hash WHEN repeat('d', 64) THEN 50.0 WHEN repeat('e', 64) THEN 99.0 ELSE 90.0 END,
         true
  FROM decomposition_pattern p;

-- Admin Web (Section 41/42) HTTP Basic user. BCryptPasswordEncoder hash of 'admin123' -- a dev
-- fixture password, never a production one.
INSERT INTO admin_user (username, password_hash)
  VALUES ('superadmin', '$2a$10$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS');
INSERT INTO admin_user_role (admin_user_id, role_id)
  SELECT u.id, r.id FROM admin_user u, role r WHERE u.username = 'superadmin' AND r.code = 'SUPER_ADMIN';
