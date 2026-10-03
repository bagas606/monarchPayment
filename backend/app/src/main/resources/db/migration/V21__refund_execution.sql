-- PRD Section 33.2 (`REFUND_PENDING -> REFUNDED`, "Refund executed via PaymentGateway. Ledger:
-- reversing entry posted"), FR-PAY-007, Section 42.2's `refund:initiate` permission.
--
-- Closes the gap the 2026-10-03 test pass named as go-live blocker 8: `REFUND_PENDING` was a
-- reachable state (BR-DEC exhaustion, TC-BE-018) that nothing could ever move to `REFUNDED` --
-- no job, no endpoint, no permission. The obligation was recorded and never discharged.

-- Mirrors `paid_at`. Nullable: only a refunded payment has one, the same way only a paid payment
-- has a `paid_at`.
ALTER TABLE payment ADD COLUMN refunded_at TIMESTAMPTZ;

-- Section 42.2 names `refund:initiate` in its example permission list. V19 seeded only the
-- permissions something actually gated ("seeding a permission nothing checks would be reference
-- data pretending to be enforcement") -- now something does: AdminRefundController.
INSERT INTO permission (code) VALUES ('refund:initiate');

-- V19's standing instruction, honoured here rather than rediscovered: "Any migration that adds a
-- new permission MUST also add its own explicit SUPER_ADMIN grant, the same way this one does."
-- A bare cross join would silently grant every future permission to SUPER_ADMIN without the
-- adding migration's author deciding so.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'SUPER_ADMIN' AND p.code = 'refund:initiate';

-- FINANCE: Section 42.1's "reconciliation (read + resolve)" plus its unqualified "settlement"
-- scope already made it the financial-mutation role here (it alone holds `settlement:ingest`),
-- and Section 42.2 restricts refund initiation to "a minimal set of roles (typically SUPER_ADMIN
-- and the directly relevant specialist role)". FINANCE is that specialist role.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'FINANCE' AND p.code = 'refund:initiate';

-- RECONCILIATION: granted on an explicit product decision (2026-10-03), and it is a DELIBERATE
-- DEVIATION from this repo's own reading of Section 42.1, recorded here rather than left to be
-- discovered later. V19 withheld `settlement:ingest` from this role on the grounds that its
-- Section 42.1 scope says "settlement read", not write -- by that same reasoning RECONCILIATION
-- would not hold a refund permission either, since a refund is the largest financial mutation in
-- the system.
--
-- The counter-argument that carried: `REFUND_PENDING` orders surface as `ORDER_VS_FULFILLMENT`
-- rows in the reconciliation queue (see OrderFulfillmentReconciliationOrchestrator), so this role
-- is the one that actually works that queue, and Section 42.1 does give it "Reconciliation module
-- full access". Splitting "can see the refund is owed" from "can discharge it" would mean every
-- refund needs a second role involved.
--
-- Revisit this grant if Section 42.1's role scopes are ever tightened: it is the widest of the
-- three and the only one not implied by the PRD's own wording. The audit trail is the compensating
-- control -- every refund carries actor, reason and (for out-of-band refunds) an external
-- reference, per BR-ADM-001.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'RECONCILIATION' AND p.code = 'refund:initiate';
