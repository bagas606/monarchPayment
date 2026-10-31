# Test status — all 54 PRD test-case IDs

One table per matrix, covering every ID in [`PRD-PPOB2.md`](PRD-PPOB2.md) Sections 52 (`TC-BE-*`),
53 (`TC-PROP-*`) and 54 (`TC-ADM-*`). This file answers "is X actually tested, and how do I know?"
The *evidence* — the HTTP statuses, the row states, the reasoning behind each deliberate deviation —
lives in [`../backend/README.md`](../backend/README.md); searching it for an ID finds the entry.

Last full pass: **2026-10-24** (V29: partner secrets encrypted, pattern generator, catalog and configuration
administration), each group starting from a wiped schema (all 29 Flyway migrations re-applied from zero), in the
documented order: 272 + 65 unit tests, `run-core.sh` 184/184, `run-backoffice-read.sh` 95/95,
`run-partner-secrets.sh` 34/34, `run-routing-and-sweeps.sh` 15/15, `run-refund-gateway.sh` 15/15, `run-ppob1.sh` 126/126,
`run-admin-auth.sh` 101/101, `run-pattern-admin.sh` 84/84, `run-catalog-admin.sh` 83/83 and `ui-smoke.cjs` 74/74.
(The 65 PPOB1 unit tests were last run on 2026-10-22; nothing in `ppob1-sim/` changed except comments and a script.)
The 2026-10-22 pass before it had 27 migrations and 238 unit tests. The pass below it, from **2026-10-10**, was against a wiped schema (all 25 migrations re-applied from
zero) with the app booted on the default profile. Everything tagged **R** re-passed unchanged —
177/177 core, 15/15 routing-and-sweeps, and 15/15 gateway-mode on its own clean boot. That pass
moved two rows, both **R\*** → **R**, and for the same underlying reason — the reconciliation read
side made the queue enumerable, so what previously needed a psql prompt or a hand-seeded fixture is
now scriptable: `TC-ADM-015`'s full `OPEN → INVESTIGATING → RESOLVED` lifecycle, and `TC-ADM-004`'s
FINANCE grant-and-boundary pair. The `V23` slice later the same day sharpened `TC-BE-004`/`TC-BE-005`
without moving a tag, and left `TC-ADM-006` at **X** on purpose — see those rows.

The 2026-10-03 pass is the reason this file warns you to be suspicious of anything not tagged
**R**: it moved `TC-BE-018`, which had been tagged **P** for a claim whose *other* half turned out
to be an open defect — a paid order with no eligible pattern collected the money and opened no
reconciliation record. The `backend/README.md` entries for both dates have the detail.

## What the tags mean

Be suspicious of any claim here that is not tagged **R**.

| Tag | Meaning |
|---|---|
| **R** | Re-verified in the 2026-10-10 pass, and asserted by [`../backend/scripts/e2e/`](../backend/scripts/e2e/README.md), so it is re-checkable by running the scripts |
| **R\*** | Verified live in a dated pass, but by hand rather than by a script — usually because it needs an action the scripts cannot take without breaking every later assertion (stopping Postgres), genuine concurrency, or a fixture the seed does not create |
| **P** | Passing on **prior** dated evidence only. Not re-run in this pass. Trust it less than **R** — and read what that evidence actually covered, not just the row's title: `TC-BE-018` sat here with an accurate half-claim hiding a money-visibility defect |
| **U** | Covered by a unit test only. No end-to-end path exists to drive it |
| **X** | **Not implemented.** There is nothing to test |

Counts: **R 40 · R\* 5 · P 1 · U 2 · X 6** — 54 total (recounted 2026-10-24; the earlier line had not kept up).

Only `TC-BE-011` is still **P**, and it stays there for a reason no test pass can remove: it needs
the real gateway's RSA path. The 2026-10-03 pass cleared the other three (`TC-BE-034`,
`TC-ADM-004`, `TC-ADM-014`) by driving them live.

## Section 52 — Backend (`TC-BE-*`)

| ID | Test case | Tag | Notes |
|---|---|---|---|
| TC-BE-001 | Create order, supported amount | **R** | `201`, `PAYMENT_PENDING` |
| TC-BE-002 | Create order, unsupported amount | **R** | `422 UNSUPPORTED_AMOUNT` |
| TC-BE-003 | Amount above the QRIS ceiling | **R** | `422`, "exceeds the QRIS transaction ceiling" |
| TC-BE-004 | Duplicate request, same key + same body | **R** | Same `order_id` both times. Sharpened 2026-10-10: "same body" is now asserted to mean the same *order* — a replay carrying different `metadata` is a replay, returns the same `order_id`, creates no second row, and does not rewrite the stored metadata |
| TC-BE-005 | Idempotency conflict, same key + different body | **R** | `409 IDEMPOTENCY_KEY_CONFLICT`. Sharpened 2026-10-10 with the boundary that makes the row mean something: `metadata` and `callback_url` are deliberately outside `ParentOrder.matchesRequest` (a `409` is unrecoverable without changing the client's own key, so it is reserved for genuinely different orders), while a different `parent_amount` under the same key still produces the `409` |
| TC-BE-006 | Payment created | **R** | `payment` row, QR payload returned, `PAYMENT_PENDING` |
| TC-BE-007 | QR expiry sweep | **R** | `parent_order` **and** `payment` both → `EXPIRED` on the 60s tick |
| TC-BE-008 | Payment success callback | **R** | Order → `PAID`, exactly one payment-ledger `CREDIT` |
| TC-BE-009 | Payment failed callback | **R** | `payment` `FAILED`, no fulfilment, no ledger row, **and no bogus anomaly record** — re-verified after the callback branch was restructured |
| TC-BE-010 | Callback duplication (same `dedup_key`) | **R** | `200`, `paid_at` unchanged, exactly 1 `payment_event`, 1 ledger row |
| TC-BE-011 | Callback replay (stale timestamp) | **P** | Confirmed 2026-09-14 as a real gap, then closed by a 1-hour timestamp window. Needs the real gateway's RSA path, which the stub harness cannot exercise |
| TC-BE-012 | Invalid signature on callback | **R** | `401`, `webhook_event.status=FAILED`, no state change |
| TC-BE-013 | Provider timeout during fulfilment | **R** | 3 attempt log lines, exactly **one** `provider_transaction` (status `TIMEOUT`), child `FAILED` after exhaustion, **no** provider ledger debit. Needed a new `timeout-provider-sku-ids` injection knob — before it, this retry loop had never run outside a unit test |
| TC-BE-014 | Provider failure (explicit error) | **R** | Child `FAILED` at `attempt_count=1`. **Not** a missing retry: Section 27.2 retries only the `TIMEOUT` class; `FAILED` is terminal. The exhaustion half is TC-BE-013 |
| TC-BE-015 | Duplicate fulfilment execution (race) | **R\*** | Re-driven live 2026-10-03: two *concurrent* admin retries on a `FAILED` child both answered `200` and were both audited (the authorization of each is a real event), but produced exactly **one** new `provider_transaction` and advanced `attempt_count` by exactly 1, never 2. The collapsing mechanism is visible in the rows — keys are `child-{id}-attempt-{n}`, so both requests computed the same `attempt-3` key and `provider_transaction_idem_uk` (unique on `provider_id, idempotency_key`) admitted one |
| TC-BE-016 | Partial fulfilment (1 of 2 children fail) | **R** | Parent → `PARTIAL_FAILED`, never silently `SUCCESS` |
| TC-BE-017 | All children succeed | **R** | Parent → `SUCCESS` |
| TC-BE-018 | No valid pattern for `parent_amount` | **R** | Order → `REFUND_PENDING` (BR-DEC exhaustion) **and** an `OPEN ORDER_VS_FULFILLMENT` record with the full `-10000` shortfall. The second half was a real defect found on 2026-10-03: the money was collected (payment `SUCCESS`, ledger CREDIT), nothing was delivered, and `reconciliation` was empty — the same hole TC-BE-031's `CANCELLED` variant had, by a different route. The prior **P** evidence covered only the state transition |
| TC-BE-019 | Pattern quota exhausted | **X** | `RoutingService` enforces **SKU** quota only. Nothing reads `pattern_usage` for eligibility, and `decomposition_pattern` has no quota column |
| TC-BE-020 | SKU quota exhausted | **R** | `quota_daily=1` against `daily_usage=4`: the higher-scored pattern became ineligible and routing picked the alternate |
| TC-BE-021 | Negative-margin pattern | **R** | `eligible=false` → excluded, alternate selected |
| TC-BE-022 | Provider price changed, no denomination change | **R** | `ProviderPriceServiceTest`, and now end to end: `run-catalog-admin.sh` changes a price through the API and asserts the new version, the re-score of only affected patterns, and that no generation was needed |
| TC-BE-023 | SKU disabled | **R** (partial) | Routing **does** exclude every pattern containing the disabled SKU. The Section 30.3 *invalidation* half is a gap: `structural_status` is only ever read, so the pattern stays `VALID`. The harness asserts that gap explicitly, so it flips the day someone implements it |
| TC-BE-024 | Generation validation failure | **X** | No pattern-generation engine |
| TC-BE-025 | Generation rollback | **X** | No pattern-generation engine |
| TC-BE-026 | Redis down | **X** | Nothing is Redis-backed. The case is vacuous as written, but *why* it is vacuous is the finding — see blocker 3 in the root README |
| TC-BE-027 | Database failure (simulated) | **R\*** | Re-driven live 2026-10-03 by stopping Postgres under a running app: `503 SERVICE_UNAVAILABLE` in the standard envelope on order creation **and** order lookup, `/actuator/health` `DOWN` (HTTP 503), and `/internal/webhooks/ayolinx` also non-2xx — which is the one that matters most, since a 2xx there would have Ayolinx treat a lost confirmation as delivered. Postgres restarted with the app untouched: health `UP` on the first poll, `201` on the next order, and zero orphan rows (the failed attempt wrote nothing; no order exists without its payment). Returned sooner than the ~6s Hikari `connection-timeout` the 2026-09-27 run saw, because a cleanly stopped Postgres refuses the connection instead of hanging. Run by hand: stopping Postgres mid-script would break every later assertion |
| TC-BE-028 | Reconciliation discrepancy (seeded mismatch) | **R** | `ORDER_VS_FULFILLMENT` opened automatically with the correct discrepancy (`-20000` for a half-fulfilled 40,000 order), status `OPEN`. Since 2026-10-10 the record is also **readable by an operator**, which is the half of FR-REC-003 ("record *and expose*") and Section 38.2 ("*visible* and actionable") this row previously could not claim: the PRD's traceability matrix maps "Admin Web reconciliation views" to this ID, and until that pass they did not exist — every reconciliation row quoted in `backend/README.md` had been found with a psql prompt. `GET /admin/reconciliations` is asserted to match SQL counts per filter, to **exclude** the rows each filter should exclude (checked specifically: the first version of these assertions passed vacuously, 12 of 12, because every seeded record is `OPEN` and same-day), to derive `reference_type` so a polymorphic `reference_id` is interpretable, to cap `size` and echo the effective value, and to write nothing to `audit_log` |
| TC-BE-029 | Cancel order in a cancellable state | **R** | `200`, → `CANCELLED` |
| TC-BE-030 | Cancel order in `SUCCESS` | **R** | `409 ORDER_NOT_CANCELLABLE`, state unchanged |
| TC-BE-031 | Late callback after order `EXPIRED` | **R** | Order stays `EXPIRED`, no fulfilment, **and** a `PAYMENT_VS_PG` record with a `+20000` surplus. The `CANCELLED` variant is also covered and was the more dangerous one: funds were collected and a ledger `CREDIT` posted while nothing was delivered and nothing recorded |
| TC-BE-032 | Out-of-order terminal callbacks | **R** | `SUCCESS` never overwritten, and the anomaly is now recorded with a `-20000` discrepancy. Covers `06` Failed, `05` Canceled **and** `04` Refunded — all three were previously broken differently |
| TC-BE-033 | Settlement attribution via per-transaction report lines (`EXACT`) | **X** | **Not implemented.** `SettlementAllocationService` implements `PRO_RATA` only; `EXACT` needs the Ayolinx settlement report to carry per-transaction lines, a format Section 37.1 flags as unverified and for which this codebase has no data model. Previously mis-tagged **P** here — the prior evidence covered the pro-rata path (TC-BE-034), not this one |
| TC-BE-034 | Settlement attribution from a batch total (`PRO_RATA`), uneven split | **R\*** | Re-driven live on 2026-10-03 with two partners weighted 350,000 / 320,000: an indivisible `actual_amount=670001` / `fee_amount=1` sent both leftover units to the larger-weighted partner (`350001`/`1` vs `320000`/`0`), and `SUM(gross_amount)=670001` / `SUM(fee_allocated)=1` matched `settlement.actual_amount` / `fee_amount` exactly (BR-REC-002). The `expected 670000` vs `actual 670001` gap also opened the `PAYMENT_VS_SETTLEMENT` record Section 38.2 requires, and the ingest was audited with its actor. `SettlementAllocationServiceTest` covers the rounding rule directly (even split, uneven split with ascending-id tie-break, single partner, no partners, zero total). By hand, not scripted: it needs a second seeded partner the fixture does not create |

## Section 53 — Decomposition property tests (`TC-PROP-*`)

| ID | Property | Tag |
|---|---|---|
| TC-PROP-001 | Invariant across random `parent_amount`s | **U** | `PatternGeneratorTest.matchesBruteForceOnRandomSmallCatalogs` (60 random catalogs/amounts compared with an independent brute force) plus `run-pattern-admin.sh`, which re-checks the sum in SQL over every generated pattern. Amounts are sampled in 1..30 in the unit test, not across 10,000..10,000,000 |
| TC-PROP-002 | Invariant across random SKU denomination sets | **U** | Same test: random face values, caps and counts; an infeasible set yields zero patterns and `infeasible=true`, never an error |
| TC-PROP-003 | `component_count <= configured_max` | **X** | Not the same property: the configured limits bound **total quantity** (hard ceiling and fewest-plus-extra), not distinct components. `PatternGeneratorTest` asserts the quantity bounds. The PRD row needs rewording or a component-count limit needs adding |
| TC-PROP-004 | Determinism for identical input + seed | **R** | `PatternGeneratorTest.sameInputGivesIdenticalOutputAndHashes` (also with the SKUs given in a different order) and `run-pattern-admin.sh`: two generations over the same catalog have identical pattern-hash sets and fingerprint. There is no seed: the algorithm uses no randomness |
| TC-PROP-005 | No duplicate `pattern_hash` per generation | **R** | `generation_id, pattern_hash` is unique in the schema, the generator never emits a duplicate (unit), and `run-pattern-admin.sh` asserts 64-hex hashes unique per generation |

All five require the Rust offline pattern-generation engine, which does not exist. The
runtime-enforced half of the mandatory invariant (`SUM(face_value × quantity) == parent_amount`) is
checked when a pattern is selected, so a hand-seeded pattern that violates it is rejected — but that
is validation of *stored* patterns, not a property test of a generator.

## Section 54 — Admin Web (`TC-ADM-*`)

Section 54's method column names Playwright/Cypress. Until 2026-10-22 none existed and these cases were driven
as HTTP Basic requests against the `/admin/**` REST endpoints. There is now a small browser console
(`/backoffice/`, driven by `backend/scripts/e2e/ui-smoke.cjs` in Chromium) and real sessions with MFA
(`run-admin-auth.sh`); the rows below still cite the Basic-auth suites where those are what asserts them.

| ID | Test case | Tag | Notes |
|---|---|---|---|
| TC-ADM-001 | Login, valid credentials (+MFA where required) | **R** | **Now actually covers the MFA half.** Until 2026-10-22 this row was backed by HTTP Basic only, which cannot carry a TOTP code. `run-admin-auth.sh` drives the real flow on a Basic-off instance: password -> `MFA_ENROLLMENT_REQUIRED` for SUPER_ADMIN -> enrol -> confirm -> active session; then password -> `MFA_REQUIRED` -> verify. Also asserted: replayed code refused, five wrong codes revoke the session, idle / absolute / pending expiry, lockout, revocation, CSRF on cookie sessions, and that no password / secret / code reaches `audit_log` |
| TC-ADM-002 | Login, invalid credentials | **R** | `401` **and** an `ADMIN_LOGIN_FAILED` audit row (attempted username, failure class, client IP). Previously `audit_log` was empty — the audit half did not exist |
| TC-ADM-003 | RBAC: VIEWER attempts a mutating action | **R** | `403 PERMISSION_DENIED` — asserted by `run-core.sh`, which drives a seeded VIEWER against a `retry:execute`-gated endpoint |
| TC-ADM-004 | RBAC: FINANCE reaches reconciliation | **R** | **Moved R\* → R on 2026-10-10**: `run-core.sh` now seeds `finance1` inline (exactly as it already did `viewer1` — the fixture seeding no FINANCE user was the only reason this was hand-driven) and asserts the grant over real HTTP: `200` on `GET /admin/reconciliations`, `200` on a detail read, `200` on `investigate` with the record verified as actually moved to `INVESTIGATING` — **and** `403` on the `retry:execute`-gated endpoint. The boundary matters as much as the grant: a role that reached everything would answer `200` there too, and then the three `200`s would prove nothing about RBAC. This is also what exercises `V22`'s FINANCE `reconciliation:view` grant over HTTP rather than only reading it back out of SQL |
| TC-ADM-005 | Permission: no `retry:execute`, attempts retry | **R** | `403` **and** an `ADMIN_PERMISSION_DENIED` row carrying the real `actor_id`. Previously unrecorded |
| TC-ADM-006 | Transaction search | **R** | `GET /admin/parent-orders?customer_reference=` (also `state`, `partner_code`, `product_code`, `order_no`, `from`/`to`, paged). `run-backoffice-read.sh` asserts the result against the database for a known reference, an unknown one, and every other filter. `GET /admin/orders` (reseller id) remains the narrow support lookup |
| TC-ADM-007 | Parent/child drilldown | **R** | `GET /admin/parent-orders/{id}`: payment (QR as a flag only), decomposition pattern with components, child orders, provider transactions, webhook deliveries, and a timeline that is explicitly *derived* (no state-history table exists). `V26` grants `order:view` to FINANCE and RECONCILIATION, as `V24` said to once this existed |
| TC-ADM-008 | Pricing update | **R** | `POST /admin/provider-skus/{id}/prices`: new `provider_price` version, previous `effective_until` set, exactly one active row, mandatory reason, audited before/after, then a re-score of exactly the patterns containing the SKU (a pattern without it keeps a hand-set score). `run-catalog-admin.sh` |
| TC-ADM-009 | Configuration update | **R** | Supported amounts (`POST/PATCH /admin/supported-amounts`, visible in the partner API at once) and typed configuration values (`PUT /admin/configuration/{key}`, range-checked, unknown key 404), audited with before/after. `run-catalog-admin.sh`, `run-pattern-admin.sh`. An amount is accepted before any pattern serves it; the response and the UI say so |
| TC-ADM-010 | Pattern viewing | **R** | `GET /admin/patterns` (by amount, product, generation, structure, eligibility) and `/{id}` with components, SKU codes and 90 days of economics. `run-pattern-admin.sh` |
| TC-ADM-011 | Generation trigger / activation | **R** | `POST /admin/pattern-generations` builds in the background; activation is one transaction (old SUPERSEDED, not deleted; one ACTIVE enforced by a unique index), refused with 409 on a coverage loss unless a reason is given, rollback is re-activation, and each step is audited. `run-pattern-admin.sh` (83 assertions), `ui-smoke.cjs` |
| TC-ADM-012 | Retry authorization + reason text | **R** | Permission enforced, and `reason` now required: `400 VALIDATION_ERROR` when absent **or blank**, recorded in `audit_log.after_state`. The endpoint previously took no body, so the log said *that* a retry happened and by whom, never *why* |
| TC-ADM-013 | Audit log completeness | **R\*** | Re-checked 2026-10-03 across the whole `audit_log` after a full pass: exactly one row per admin action with the correct `actor_id`, 1:1 on the **denied** side too (which used to be 0) — including two `ADMIN_PERMISSION_DENIED` rows from two *different* actors, and three `CHILD_ORDER_RETRY` rows for three authorized retries even though TC-BE-015's concurrent pair collapsed to one execution. The only rows without an `actor_id` are `ADMIN_LOGIN_FAILED`, by design: there is no authenticated actor, so the attempted username is recorded instead |
| TC-ADM-014 | Settlement display | **R\*** | Re-driven live on 2026-10-03: `GET /admin/settlements/{id}/allocations` returned the real per-partner breakdown for the TC-BE-034 settlement — `gross_amount`, `fee_allocated`, a derived `net_amount`, `allocation_method` and `computed_at` per partner, plus a `reconciling_gross_total` matching `actual_amount`. Section 41.8's literal per-payment expected/actual/fee view is still not built |
| TC-ADM-015 | Reconciliation handling | **R** | **Moved R\* → R on 2026-10-10**: the full `OPEN → INVESTIGATING → RESOLVED` lifecycle is now asserted by `run-core.sh` rather than driven by hand — `resolved_by` is the acting `admin_user` (never a client-supplied id), `resolved_at` is set, the resolver is surfaced by the API, the record moves between the two filter results on either side of the transition, and a second `resolve` applies nothing (Section 38.2's sequence is a sequence, not a set). It had been **R\*** for a specific reason now gone: scripting it needed a way to enumerate the queue, and until the read side existed there was none. The 2026-10-03 hand-run drove the same lifecycle against the *new* `TC-BE-018` record, which is how that fix was shown to be actionable by an operator rather than merely present in the table |

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
