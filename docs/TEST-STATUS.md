# Test status — all 54 PRD test-case IDs

One table per matrix, covering every ID in [`PRD-PPOB2.md`](PRD-PPOB2.md) Sections 52 (`TC-BE-*`),
53 (`TC-PROP-*`) and 54 (`TC-ADM-*`). This file answers "is X actually tested, and how do I know?"
The *evidence* — the HTTP statuses, the row states, the reasoning behind each deliberate deviation —
lives in [`../backend/README.md`](../backend/README.md); searching it for an ID finds the entry.

Last full pass: **2026-09-27**, against a wiped Postgres volume (all 20 Flyway migrations re-applied
from zero) with the app booted on the default profile.

## What the tags mean

Be suspicious of any claim here that is not tagged **R**.

| Tag | Meaning |
|---|---|
| **R** | Re-verified in the 2026-09-27 pass, and asserted by [`../backend/scripts/e2e/`](../backend/scripts/e2e/README.md), so it is re-checkable by running two scripts |
| **R\*** | Verified live in that same pass, but by hand rather than by a script — usually because it needs an action the scripts cannot take without breaking every later assertion (stopping Postgres) or genuine concurrency |
| **P** | Passing on **prior** dated evidence only. Not re-run in this pass. Trust it less than **R** |
| **U** | Covered by a unit test only. No end-to-end path exists to drive it |
| **X** | **Not implemented.** There is nothing to test |

Counts: **R 28 · R\* 4 · P 5 · U 1 · X 16** — 54 total.

## Section 52 — Backend (`TC-BE-*`)

| ID | Test case | Tag | Notes |
|---|---|---|---|
| TC-BE-001 | Create order, supported amount | **R** | `201`, `PAYMENT_PENDING` |
| TC-BE-002 | Create order, unsupported amount | **R** | `422 UNSUPPORTED_AMOUNT` |
| TC-BE-003 | Amount above the QRIS ceiling | **R** | `422`, "exceeds the QRIS transaction ceiling" |
| TC-BE-004 | Duplicate request, same key + same body | **R** | Same `order_id` both times |
| TC-BE-005 | Idempotency conflict, same key + different body | **R** | `409 IDEMPOTENCY_KEY_CONFLICT` |
| TC-BE-006 | Payment created | **R** | `payment` row, QR payload returned, `PAYMENT_PENDING` |
| TC-BE-007 | QR expiry sweep | **R** | `parent_order` **and** `payment` both → `EXPIRED` on the 60s tick |
| TC-BE-008 | Payment success callback | **R** | Order → `PAID`, exactly one payment-ledger `CREDIT` |
| TC-BE-009 | Payment failed callback | **R** | `payment` `FAILED`, no fulfilment, no ledger row, **and no bogus anomaly record** — re-verified after the callback branch was restructured |
| TC-BE-010 | Callback duplication (same `dedup_key`) | **R** | `200`, `paid_at` unchanged, exactly 1 `payment_event`, 1 ledger row |
| TC-BE-011 | Callback replay (stale timestamp) | **P** | Confirmed 2026-09-14 as a real gap, then closed by a 1-hour timestamp window. Needs the real gateway's RSA path, which the stub harness cannot exercise |
| TC-BE-012 | Invalid signature on callback | **R** | `401`, `webhook_event.status=FAILED`, no state change |
| TC-BE-013 | Provider timeout during fulfilment | **R** | 3 attempt log lines, exactly **one** `provider_transaction` (status `TIMEOUT`), child `FAILED` after exhaustion, **no** provider ledger debit. Needed a new `timeout-provider-sku-ids` injection knob — before it, this retry loop had never run outside a unit test |
| TC-BE-014 | Provider failure (explicit error) | **R** | Child `FAILED` at `attempt_count=1`. **Not** a missing retry: Section 27.2 retries only the `TIMEOUT` class; `FAILED` is terminal. The exhaustion half is TC-BE-013 |
| TC-BE-015 | Duplicate fulfilment execution (race) | **R\*** | Two *concurrent* admin retries produced exactly one new `provider_transaction` and `attempt_count` 1→2, never 3. Also holds at the DB level via `provider_transaction_idem_uk` |
| TC-BE-016 | Partial fulfilment (1 of 2 children fail) | **R** | Parent → `PARTIAL_FAILED`, never silently `SUCCESS` |
| TC-BE-017 | All children succeed | **R** | Parent → `SUCCESS` |
| TC-BE-018 | No valid pattern for `parent_amount` | **P** | Order → `REFUND_PENDING` (BR-DEC exhaustion) |
| TC-BE-019 | Pattern quota exhausted | **X** | `RoutingService` enforces **SKU** quota only. Nothing reads `pattern_usage` for eligibility, and `decomposition_pattern` has no quota column |
| TC-BE-020 | SKU quota exhausted | **R** | `quota_daily=1` against `daily_usage=4`: the higher-scored pattern became ineligible and routing picked the alternate |
| TC-BE-021 | Negative-margin pattern | **R** | `eligible=false` → excluded, alternate selected |
| TC-BE-022 | Provider price changed, no denomination change | **U** | `ProviderPriceServiceTest`. No e2e path: there is no endpoint to change a price (TC-ADM-008 is unbuilt), and "no structural regeneration" is vacuously true because no generator exists |
| TC-BE-023 | SKU disabled | **R** (partial) | Routing **does** exclude every pattern containing the disabled SKU. The Section 30.3 *invalidation* half is a gap: `structural_status` is only ever read, so the pattern stays `VALID`. The harness asserts that gap explicitly, so it flips the day someone implements it |
| TC-BE-024 | Generation validation failure | **X** | No pattern-generation engine |
| TC-BE-025 | Generation rollback | **X** | No pattern-generation engine |
| TC-BE-026 | Redis down | **X** | Nothing is Redis-backed. The case is vacuous as written, but *why* it is vacuous is the finding — see blocker 3 in the root README |
| TC-BE-027 | Database failure (simulated) | **R\*** | `503 SERVICE_UNAVAILABLE` in the standard envelope in ~6s (was: 30s hang → a non-envelope `500`), health `DOWN` in 5s, recovery with no app restart, zero orphan rows. Also checked on `/internal/webhooks/ayolinx`, which must return non-2xx so Ayolinx redelivers rather than the confirmation being lost. Run by hand: stopping Postgres mid-script would break every later assertion |
| TC-BE-028 | Reconciliation discrepancy (seeded mismatch) | **R** | `ORDER_VS_FULFILLMENT` opened automatically with the correct discrepancy (`-20000` for a half-fulfilled 40,000 order), status `OPEN` |
| TC-BE-029 | Cancel order in a cancellable state | **R** | `200`, → `CANCELLED` |
| TC-BE-030 | Cancel order in `SUCCESS` | **R** | `409 ORDER_NOT_CANCELLABLE`, state unchanged |
| TC-BE-031 | Late callback after order `EXPIRED` | **R** | Order stays `EXPIRED`, no fulfilment, **and** a `PAYMENT_VS_PG` record with a `+20000` surplus. The `CANCELLED` variant is also covered and was the more dangerous one: funds were collected and a ledger `CREDIT` posted while nothing was delivered and nothing recorded |
| TC-BE-032 | Out-of-order terminal callbacks | **R** | `SUCCESS` never overwritten, and the anomaly is now recorded with a `-20000` discrepancy. Covers `06` Failed, `05` Canceled **and** `04` Refunded — all three were previously broken differently |
| TC-BE-033 | Settlement attribution via per-transaction report lines (`EXACT`) | **X** | **Not implemented.** `SettlementAllocationService` implements `PRO_RATA` only; `EXACT` needs the Ayolinx settlement report to carry per-transaction lines, a format Section 37.1 flags as unverified and for which this codebase has no data model. Previously mis-tagged **P** here — the prior evidence covered the pro-rata path (TC-BE-034), not this one |
| TC-BE-034 | Settlement attribution from a batch total (`PRO_RATA`), uneven split | **P** | Largest-remainder rounding verified against a real Postgres and a real HTTP ingest call: with an indivisible pool the larger-weighted partner correctly won both leftover units and `SUM(gross_amount)=100001` / `SUM(fee_allocated)=1` matched `settlement.actual_amount` / `fee_amount` exactly. `SettlementAllocationServiceTest` covers the rounding rule directly (even split, uneven split with ascending-id tie-break, single partner, no partners, zero total). Not re-run in the 2026-09-27 pass, and not driven by the e2e scripts |

## Section 53 — Decomposition property tests (`TC-PROP-*`)

| ID | Property | Tag |
|---|---|---|
| TC-PROP-001 | Invariant across random `parent_amount`s | **X** |
| TC-PROP-002 | Invariant across random SKU denomination sets | **X** |
| TC-PROP-003 | `component_count <= configured_max` | **X** |
| TC-PROP-004 | Determinism for identical input + seed | **X** |
| TC-PROP-005 | No duplicate `pattern_hash` per generation | **X** |

All five require the Rust offline pattern-generation engine, which does not exist. The
runtime-enforced half of the mandatory invariant (`SUM(face_value × quantity) == parent_amount`) is
checked when a pattern is selected, so a hand-seeded pattern that violates it is rejected — but that
is validation of *stored* patterns, not a property test of a generator.

## Section 54 — Admin Web (`TC-ADM-*`)

This codebase has no browser-based Admin Web; Section 54's own method column names
Playwright/Cypress and none exists here. "Admin Web" in practice means real HTTP Basic auth against
the `/admin/**` REST endpoints and Section 42's RBAC.

| ID | Test case | Tag | Notes |
|---|---|---|---|
| TC-ADM-001 | Login, valid credentials | **R** | Every admin assertion in the harness authenticates for real |
| TC-ADM-002 | Login, invalid credentials | **R** | `401` **and** an `ADMIN_LOGIN_FAILED` audit row (attempted username, failure class, client IP). Previously `audit_log` was empty — the audit half did not exist |
| TC-ADM-003 | RBAC: VIEWER attempts a mutating action | **R** | `403 PERMISSION_DENIED` — asserted by `run-core.sh`, which drives a seeded VIEWER against a `retry:execute`-gated endpoint |
| TC-ADM-004 | RBAC: FINANCE reaches reconciliation | **P** | `200`, per the seeded permission matrix |
| TC-ADM-005 | Permission: no `retry:execute`, attempts retry | **R** | `403` **and** an `ADMIN_PERMISSION_DENIED` row carrying the real `actor_id`. Previously unrecorded |
| TC-ADM-006 | Transaction search | **X** | No implementation |
| TC-ADM-007 | Parent/child drilldown | **X** | No implementation |
| TC-ADM-008 | Pricing update | **X** | No implementation |
| TC-ADM-009 | Configuration update | **X** | No implementation |
| TC-ADM-010 | Pattern viewing | **X** | No implementation |
| TC-ADM-011 | Generation trigger | **X** | No implementation |
| TC-ADM-012 | Retry authorization + reason text | **R** | Permission enforced, and `reason` now required: `400 VALIDATION_ERROR` when absent **or blank**, recorded in `audit_log.after_state`. The endpoint previously took no body, so the log said *that* a retry happened and by whom, never *why* |
| TC-ADM-013 | Audit log completeness | **R\*** | Exactly one row per mutation with the correct `actor_id` — and now 1:1 on the **denied** side too, which used to be 0 |
| TC-ADM-014 | Settlement display | **P** | Real per-partner allocation breakdown returned. Section 41.8's literal per-payment expected/actual/fee view is not built |
| TC-ADM-015 | Reconciliation handling | **R\*** | Full `OPEN → INVESTIGATING → RESOLVED` lifecycle, `resolved_by` recorded |

## Known deviations from the PRD's literal wording

Places where the code deliberately does something other than what a test case says, with the
reasoning recorded in `backend/README.md` rather than silently diverging:

- **TC-BE-014's "after retry exhaustion"** — an explicit provider error is terminal at the first
  attempt. Only Section 27.2's `TIMEOUT` class is idempotent-safe to retry.
- **TC-BE-032 / TC-BE-031's anomaly handling still answers `200`** to the gateway. Ayolinx
  redelivers anything it treats as unacknowledged, and redelivering a mismatched callback would not
  make it match; idempotency comes from `payment_event.dedup_key` instead.
- **An unparseable callback amount applies the payment** and opens an `AMOUNT_UNVERIFIED`
  discrepancy, rather than refusing it. The inbound amount format has never been captured from real
  traffic, and blocking money on an unproven assumption of ours would turn one wrong guess into a
  total payment outage. A *parsed* amount that disagrees does block.
