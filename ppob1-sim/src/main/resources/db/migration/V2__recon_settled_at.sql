-- Fixes a design bug in the first cut of ReconciliationService, found by running the two-sided
-- harness rather than by reading the code.
--
-- The working set was "every transaction whose local_state is not terminal". That looked obviously
-- right and made the WEBHOOK_NEVER_ARRIVED verdict almost unreachable: reaching a terminal state
-- is precisely what excluded a transaction, so the verdict could only ever fire in the narrow
-- window between a terminal transition and the next run. In the harness it fired zero times
-- against a run where four transactions had just settled -- the first run reported `examined: 0`.
--
-- The real question a recon run asks is not "is this transaction still open?" but "has this
-- transaction been reconciled yet?" -- which is a property of the reconciliation, not of the order.
-- A settled sale still needs to be looked at ONCE, to confirm PPOB2 agrees and to notice if PPOB1
-- was never told. After that there is nothing further to learn, so it drops out.

ALTER TABLE ppob1_transaction ADD COLUMN recon_settled_at TIMESTAMPTZ;

-- Partial index: the working-set query only ever asks for the unsettled rows, and this keeps a
-- run's cost proportional to open business rather than to all history -- which was the one good
-- property of the broken version, kept.
CREATE INDEX ppob1_transaction_unreconciled_idx ON ppob1_transaction (id) WHERE recon_settled_at IS NULL;
