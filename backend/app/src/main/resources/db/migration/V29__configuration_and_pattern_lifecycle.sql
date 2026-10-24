-- Section 22.25 `configuration`: the store for values the PRD says must be configuration, not code
-- (BR-BUS-010, FR-RTE-003). Only OVERRIDES live here; the known keys, their types, ranges and defaults are
-- in code (ConfigKey), so a missing row means "default" and an unknown key cannot be written.
CREATE TABLE configuration (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_key    VARCHAR(128) NOT NULL,
    config_value  JSONB        NOT NULL,
    description   VARCHAR(255),
    updated_by    BIGINT REFERENCES admin_user(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT configuration_key_uk UNIQUE (config_key)
);

CREATE TRIGGER configuration_set_updated_at
    BEFORE UPDATE ON configuration
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Generation lifecycle (Section 40.2). `params` records exactly what a run used, so it can be reproduced and
-- audited (Section 29.1 asks for deterministic, reproducible generation); `report` is the per-amount outcome,
-- including amounts that could not be built at all.
ALTER TABLE pattern_generation ADD COLUMN params JSONB;
ALTER TABLE pattern_generation ADD COLUMN report JSONB;

-- Exactly one ACTIVE generation, enforced by the database. PatternLookupService.findByStatus("ACTIVE") returns an
-- Optional, so two ACTIVE rows would throw on the order path. The expression index makes every ACTIVE row collide.
CREATE UNIQUE INDEX pattern_generation_one_active ON pattern_generation ((true)) WHERE status = 'ACTIVE';

-- Section 42.2 names pattern:activate, pricing:edit and config:edit; the others are this codebase's split of
-- "catalog" and "decomposition monitoring" (Section 42.1). Role mapping below is an interpretation of the
-- "Typical Scope" text, as V19's was, and each new permission gets its own explicit SUPER_ADMIN grant (V19).
--   catalog:view     read products / providers / SKUs / prices / configuration
--   catalog:edit     create and enable/disable products, providers, SKUs; supported amounts
--   pricing:edit     new provider_price version (Section 40.3)
--   config:edit      configuration values (weights, fee, floor, generation limits)
--   pattern:view     generations, patterns, economics
--   pattern:generate start a generation
--   pattern:activate activate / roll back a generation (Section 42.2: critical, minimal roles)
INSERT INTO permission (code) VALUES
    ('catalog:view'), ('catalog:edit'), ('pricing:edit'), ('config:edit'),
    ('pattern:view'), ('pattern:generate'), ('pattern:activate');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code = 'SUPER_ADMIN'
  AND p.code IN ('catalog:view', 'catalog:edit', 'pricing:edit', 'config:edit', 'pattern:view', 'pattern:generate', 'pattern:activate');

-- TECH_SUPPORT: "Provider/catalog config, decomposition/generation monitoring" -> catalog both ways, pattern view + generate.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code = 'TECH_SUPPORT' AND p.code IN ('catalog:view', 'catalog:edit', 'pattern:view', 'pattern:generate');

-- OPERATIONS ("provider status, quota"), FINANCE ("margin"), VIEWER (read-only): read access only.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code IN ('OPERATIONS', 'FINANCE', 'VIEWER') AND p.code IN ('catalog:view', 'pattern:view');
