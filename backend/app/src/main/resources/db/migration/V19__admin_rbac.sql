-- PRD Section 22.26 / Section 42: Admin RBAC. admin_user (V17) already exists; this adds the
-- role/permission layer so an authenticated admin's actions can be gated on more than
-- "is an ACTIVE admin_user", per Section 42.2's "not merely be an admin" requirement.

CREATE TABLE role (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE
);

CREATE TABLE permission (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE admin_user_role (
    admin_user_id BIGINT NOT NULL REFERENCES admin_user(id),
    role_id       BIGINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (admin_user_id, role_id)
);

CREATE TABLE role_permission (
    role_id       BIGINT NOT NULL REFERENCES role(id),
    permission_id BIGINT NOT NULL REFERENCES permission(id),
    PRIMARY KEY (role_id, permission_id)
);

-- Section 42.1's complete role list. Reference data (not per-tenant), safe to seed here — unlike
-- admin_user_role below, which is who-holds-what and stays operational data.
INSERT INTO role (code) VALUES
    ('SUPER_ADMIN'), ('OPERATIONS'), ('CUSTOMER_SERVICE'), ('FINANCE'),
    ('RECONCILIATION'), ('TECH_SUPPORT'), ('VIEWER');

-- Only the permissions this codebase actually gates something with today. Section 42.2 names
-- several more as examples (order:view, config:edit, pattern:activate, refund:initiate, ...) —
-- seeding a permission nothing checks would be reference data pretending to be enforcement.
INSERT INTO permission (code) VALUES
    ('retry:execute'),              -- POST /admin/child-orders/{id}/retry
    ('reconciliation:investigate'), -- POST /admin/reconciliations/{id}/investigate
    ('reconciliation:resolve'),     -- POST /admin/reconciliations/{id}/resolve
    ('settlement:ingest');          -- POST /internal/settlement/ingest

-- Section 42.1 gives descriptive "Typical Scope" text per role, not a literal role->permission
-- table — this mapping is this codebase's interpretation of that text, not a transcription:
--   OPERATIONS      "retry"                              -> retry:execute
--   FINANCE         "reconciliation (read + resolve)"    -> both reconciliation permissions
--   RECONCILIATION  "Reconciliation module full access"  -> both reconciliation permissions
--   FINANCE only (not RECONCILIATION) for settlement:ingest — RECONCILIATION's scope explicitly
--   says "settlement read", not write/ingest, while FINANCE's names "settlement" without that
--   qualifier.
-- CUSTOMER_SERVICE, TECH_SUPPORT, VIEWER get none of the four — their scopes don't touch retry,
-- reconciliation resolution, or settlement ingestion.
--
-- SUPER_ADMIN's codes are enumerated explicitly, not granted via a bare cross join to `permission`
-- — a bare join would silently grant every *future* permission a later migration adds without that
-- migration's author ever deciding so. Any migration that adds a new permission MUST also add its
-- own explicit SUPER_ADMIN grant, the same way this one does.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'SUPER_ADMIN'
  AND p.code IN ('retry:execute', 'reconciliation:investigate', 'reconciliation:resolve', 'settlement:ingest');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'OPERATIONS' AND p.code = 'retry:execute';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code IN ('FINANCE', 'RECONCILIATION') AND p.code IN ('reconciliation:investigate', 'reconciliation:resolve');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'FINANCE' AND p.code = 'settlement:ingest';

-- Deliberately no admin_user_role rows here — who holds which role is operational data (same
-- treatment as admin_user itself: no seeded rows, inserted per environment). A fresh deploy
-- therefore has every admin locked out of all four gated actions until someone assigns roles —
-- correct-by-default, not a bug; see backend/README.md.
