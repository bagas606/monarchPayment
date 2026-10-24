-- Backoffice read surfaces: transaction drill-down (Section 41.2), payment/callback views (41.7) and
-- the audit log browser (41.12). Two permissions are new; one existing grant is widened.
--
-- V19's standing instruction is honoured: each new permission gets its own explicit SUPER_ADMIN grant.
INSERT INTO permission (code) VALUES ('payment:view'), ('audit:view');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code = 'SUPER_ADMIN' AND p.code IN ('payment:view', 'audit:view');

-- order:view -> FINANCE, RECONCILIATION. V24 withheld it from them and said exactly when to revisit:
-- "the moment [the parent/child] drilldown exists, FINANCE and RECONCILIATION need a way in and this
-- is the permission to grant them". A reconciliation row points at its order through reference_id; the
-- drill-down is how they open it. TECH_SUPPORT stays withheld: its Section 42.1 scope still names
-- config and generation monitoring, not transactions.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code IN ('FINANCE', 'RECONCILIATION') AND p.code = 'order:view';

-- payment:view -> OPERATIONS, FINANCE, RECONCILIATION. Payment detail and callback history (including
-- rejected, invalid-signature attempts "for security review", Section 41.7) are money-and-security
-- data, so this is deliberately narrower than order:view: not CUSTOMER_SERVICE (a lookup role, and a
-- payer's PG reference is not something it needs), not VIEWER, not TECH_SUPPORT.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code IN ('OPERATIONS', 'FINANCE', 'RECONCILIATION') AND p.code = 'payment:view';

-- audit:view -> SUPER_ADMIN only. No Section 42.1 scope text mentions the audit log, and it records
-- every admin's activity (including who attempted to log in as whom). Widening it later is one INSERT;
-- narrowing it after people rely on it is a conversation.
