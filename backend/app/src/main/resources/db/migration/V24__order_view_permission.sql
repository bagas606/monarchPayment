-- Gates GET /admin/orders (the reverse lookup V23's column exists to serve). Section 42.2 names
-- `order:view` in its own list of fine-grained permissions, and V19 deliberately left it unseeded
-- with the reason spelled out: "Only the permissions this codebase actually gates something with
-- today ... seeding a permission nothing checks would be reference data pretending to be
-- enforcement." Something now checks it, so it is seeded here rather than invented.
INSERT INTO permission (code) VALUES ('order:view');

-- V19's standing instruction, honoured here rather than rediscovered: "Any migration that adds a
-- new permission MUST also add its own explicit SUPER_ADMIN grant, the same way this one does."
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code = 'SUPER_ADMIN' AND p.code = 'order:view';

-- Granted to the roles whose Section 42.1 scope text names a transaction-reading activity:
--   OPERATIONS        "Transaction investigation, retry, provider status, quota management"
--   CUSTOMER_SERVICE  "Read-mostly transaction lookup, limited investigation notes"
-- CUSTOMER_SERVICE is the decisive one. Section 42.1 gives the most junior role with any read scope
-- at all an explicit transaction lookup, which settles what kind of surface this is: an order
-- lookup is the baseline read of this platform, not a privileged one.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code IN ('OPERATIONS', 'CUSTOMER_SERVICE') AND p.code = 'order:view';

-- GRANTED TO VIEWER -- the opposite of what V22 did two migrations ago, so the divergence is argued
-- rather than left to look like an inconsistency.
--
-- V22 withheld `reconciliation:view` from VIEWER on the qualifier in "Read-only across permitted
-- modules", and its deciding sentence was: "this endpoint is not a transaction lookup. Every row in
-- the queue is, by construction, money that does not add up ... 'Can see every open financial
-- exposure' is a Finance-shaped grant that happens to be implemented as a read."
--
-- That sentence argues the other way here. This endpoint *is* a transaction lookup -- the literal
-- phrase Section 42.1 uses for CUSTOMER_SERVICE -- and it resolves one id a partner already holds
-- into the order it already placed. It reveals no aggregate, no exposure, and nothing the
-- requesting partner does not know. If "permitted modules" means anything for VIEWER, transactions
-- is the least contentious candidate in the PRD; withholding this too would make VIEWER a role
-- that can, by construction, never read anything, which is not what Section 42.1 describes.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code = 'VIEWER' AND p.code = 'order:view';

-- WITHHELD from FINANCE, RECONCILIATION and TECH_SUPPORT. This reads oddly next to the VIEWER grant
-- above -- FINANCE is plainly the more trusted role -- so the reasoning: Section 42.1 does not rank
-- roles by trust, it describes each one's scope, and these three scopes name modules rather than
-- transactions ("Ledger, settlement, margin, reconciliation", "Reconciliation module full access,
-- ledger/settlement read", "Provider/catalog config, decomposition/generation monitoring"). Reading
-- a reseller's order by its own reference is not in any of them.
--
-- Revisit when TC-ADM-007's parent/child drilldown is built: a reconciliation row points at an
-- order through `reference_id` with no way to open it (see ReconciliationType.referenceType()), so
-- the moment that drilldown exists, FINANCE and RECONCILIATION need a way in and this is the
-- permission to grant them -- not a widening of one they already hold.
