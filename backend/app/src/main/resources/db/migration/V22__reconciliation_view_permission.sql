-- PRD FR-REC-003 ("record AND EXPOSE reconciliation discrepancies for operational resolution via
-- Admin Web"), Section 38.2 ("VISIBLE and actionable in Admin Web"), Section 41.9's payment /
-- provider / settlement reconciliation views.
--
-- Closes the read half of the reconciliation workflow. The write half has existed since the RBAC
-- slice: `reconciliation:investigate` and `reconciliation:resolve` move a record by id. Nothing
-- could produce an id. An operator's only way to find an open discrepancy was a psql prompt --
-- which is how every reconciliation row in this repo's test evidence was located -- so the queue
-- was recorded and actionable but not, in any operational sense, visible.

-- A new permission rather than reusing `reconciliation:investigate`, and this is the precedent
-- AdminSettlementController's comment asked for when it did reuse one: it gated a single read
-- endpoint behind `settlement:ingest` to avoid a migration, and said in as many words "if a
-- broader settlement read surface is built later ... revisit this as its own settlement:view
-- permission rather than growing this reuse further." Reconciliation is now exactly that case: a
-- list plus a detail view. Reusing an *investigate* permission for reading would also invert
-- Section 42.1's own distinction -- it grants FINANCE "reconciliation (read + resolve)", naming
-- read as a thing one can hold separately -- and would mean no role can ever be given sight of the
-- discrepancy queue without also being given the power to advance records through it.
INSERT INTO permission (code) VALUES ('reconciliation:view');

-- V19's standing instruction, honoured here rather than rediscovered: "Any migration that adds a
-- new permission MUST also add its own explicit SUPER_ADMIN grant, the same way this one does."
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code = 'SUPER_ADMIN' AND p.code = 'reconciliation:view';

-- FINANCE and RECONCILIATION: both already hold investigate+resolve, and Section 42.1 gives them
-- "reconciliation (read + resolve)" and "Reconciliation module full access" respectively. Read is
-- the weakest permission either role's scope names, so this is the one grant in this migration
-- that needs no argument: without it, both roles can resolve records they cannot find.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code IN ('FINANCE', 'RECONCILIATION') AND p.code = 'reconciliation:view';

-- DELIBERATELY WITHHELD from VIEWER, which is the one omission here that cuts against the PRD's
-- own wording and so is recorded rather than left to be discovered. Section 42.1 describes VIEWER
-- as "Read-only across permitted modules, no mutation", and this permission is read-only with no
-- mutation -- the literal reading grants it.
--
-- It is withheld on the qualifier in that same sentence: "permitted modules". Section 42.1 never
-- says which modules are permitted to VIEWER, so the grant is a judgement either way, and this
-- codebase has consistently resolved that judgement the narrow way (V19 gave VIEWER none of its
-- four permissions; TC-ADM-003 drives a seeded VIEWER against a gated endpoint and asserts 403).
-- What tips it: this endpoint is not a transaction lookup. Every row in the queue is, by
-- construction, money that does not add up -- a shortfall where funds were collected and nothing
-- delivered (TC-BE-018), a surplus received against a cancelled order (TC-BE-031) -- and the
-- discrepancy amounts are the system's own unresolved financial exposure. "Can see every open
-- financial exposure" is a Finance-shaped grant that happens to be implemented as a read, not a
-- general read.
--
-- Revisit this when Section 42.1's "permitted modules" is actually specified, or when a
-- read-only-by-role Admin Web lands and VIEWER needs a landing page: the fix then is to grant this
-- permission to VIEWER, NOT to widen one of the two mutating permissions. OPERATIONS and
-- CUSTOMER_SERVICE are withheld on the simpler ground that neither scope mentions reconciliation
-- at all ("transaction investigation, retry, provider status, quota" / "transaction lookup"),
-- and TECH_SUPPORT's is config and generation monitoring.
