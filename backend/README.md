# PPOB2 Backend

Modular monolith for the PPOB2 Core Backend, per [`docs/PRD-PPOB2.md`](../docs/PRD-PPOB2.md).
Java 21, Spring Boot 3.3, Gradle (Kotlin DSL) multi-module build, PostgreSQL, Flyway.

## What this file is, and is not

This is the **engineering record**: for each slice, what is actually real versus scaffolded, every
deliberate design trade-off and why it was chosen, and the dated evidence behind every behaviour
claimed to work. It is long (~2000 lines) and *chronological* — newer entries append rather than
rewrite, so an entry can be superseded by a later one. When two entries disagree, the later one wins
and says so.

It is not the place to start. Read in this order:

| You want | Go to |
|---|---|
| Orientation, quickstart, go-live blockers | [root `README.md`](../README.md) |
| What the system is *supposed* to do | [`docs/PRD-PPOB2.md`](../docs/PRD-PPOB2.md) — every `Section N` reference below points here |
| Whether a given `TC-*` case is verified | [`docs/TEST-STATUS.md`](../docs/TEST-STATUS.md) — all 53 IDs in one table |
| Running the e2e matrices | [`scripts/e2e/README.md`](scripts/e2e/README.md) |

**How to search this file.** Look up a PRD test-case ID (`TC-BE-013`), a class name
(`PaymentCallbackService`), or a PRD section number (`Section 25.2`) — the record is written so that
each of those lands you on the relevant entry. `docs/TEST-STATUS.md` is the index by test ID; this
file is the evidence behind it.

**A note on gaps.** Where the PRD asks for something this codebase does not do, the gap is named
next to the thing it affects rather than left implicit. That is deliberate: several defects found
here were originally *specified* and half-built, and the half that was missing had been recorded as
a gap — which is how they were found again. Treat every "flagged as a gap" sentence as a real,
open item, not as hedging.

## Module layout

One Gradle module per PRD Section 20.1 module, plus:

- `shared-kernel` — cross-cutting types every module may depend on: `Money` (Section 21.1),
  `ChannelContext` (Section 18.1), the standard error envelope (Section 50.1), and the
  HMAC signing utility (Section 23.2).
- `app` — the composition root / deployable Spring Boot application. Owns Flyway migrations,
  the HTTP layer (controllers, security filters), and wires every module together. This is also
  where the "API / Channel Adapters" box from the Section 19 architecture diagram lives.

Module dependencies are wired as real Gradle `project(...)` dependencies matching the allowed-
dependency graph in Section 20.2 — a module that isn't supposed to see another module's code
simply won't compile against it. Cross-module entity relationships are stored as plain `Long`
foreign keys rather than JPA `@ManyToOne` joins, so no module needs an undocumented compile
dependency just to reference another module's row by id.

## What's implemented vs. scaffolded

Three vertical slices have real behavior end-to-end, all verified against a real Postgres
instance (`docker compose up -d && ./gradlew :app:bootRun`, then exercised with signed `curl`
requests — not just compiled). This mattered in practice: the webhook slice below shipped with
two bugs that compiled cleanly, passed a mocked-repository test, and only surfaced against a real
database — see that slice's notes.

- **`GET /api/v1/config/supported-amounts`** (Section 23.3) — HMAC request authentication
  (Section 23.2), the standard error envelope (Section 50.1).
- **`POST /api/v1/orders`** (Section 23.4) — the *synchronous* portion only, per Section 35.1's
  explicit sync/async boundary ("synchronous only up to QR issuance"). Covers amount validation
  (supported-amount table + the Section 25.3 QRIS ceiling), idempotency (`parent_order_idem_uk`:
  replay returns the original order, a same-key-different-body request gets
  `409 IDEMPOTENCY_KEY_CONFLICT`), the Order State Machine's `CREATED → PAYMENT_PENDING`
  transition (Section 33.2), and QR issuance through a `PaymentGateway` (Section 25.1)
  abstraction.
- **`POST /internal/webhooks/ayolinx`** (Section 25.2/48.2/48.5) — the inbound QRIS payment
  callback: signature verification, dedup (`payment_event.dedup_key`), `Payment → SUCCESS`, and
  the `PAYMENT_PENDING → PAID` order transition. `payment` and `order` have no dependency edge
  toward each other in either direction (Section 20.2's graph only grants `order → payment`), so
  the `PAID` transition is driven by `payment` publishing a `PaymentConfirmedEvent` that `order`
  listens for — see that class's Javadoc. Two real bugs were found only by testing this against
  real Postgres, not by compiling or mocking:
  - A `String` field mapped `columnDefinition = "jsonb"` binds as `varchar` under Hibernate 6
    unless annotated `@JdbcTypeCode(SqlTypes.JSON)` — every JSONB column in this codebase
    (`PaymentEvent.rawPayload`, `WebhookEvent.payload`, `AuditLog.beforeState`/`afterState`) needs
    that annotation, not just `columnDefinition`.
  - Catching `DataIntegrityViolationException` around a `saveAndFlush` that violates a unique
    constraint does not save you: per the JPA spec, a failed flush marks the *whole transaction*
    rollback-only inside Hibernate, regardless of whether your code catches the translated
    exception. The fix was a conflict-safe native `INSERT ... ON CONFLICT DO NOTHING`
    (`PaymentEventRepository.insertIfAbsent`), not better exception handling. If you're tempted to
    dedupe via try/catch anywhere else in this codebase, read that method's Javadoc first.
  - Relatedly, `ParentOrderTransitionService.markPaid` needs `Propagation.REQUIRES_NEW` — it's
    called from a `@TransactionalEventListener(phase = AFTER_COMMIT)` callback, and a plain
    `@Transactional` there silently no-ops (no exception, no log, no DB write). This was verified
    both ways against real Postgres; don't remove it without re-verifying the same way.

- **Decomposition & routing** (Section 28–33.2's `PAID → DECOMPOSITION_SELECTED`/`REFUND_PENDING`
  transition) — once a payment is confirmed, `ParentOrderTransitionService.selectPatternOrRefund`
  asks `decomposition` to look up pre-generated candidate patterns for the order's
  `(product, parent_amount)`, `routing` filters/scores them and picks the best eligible one, and
  the result either attaches a `pattern_id` and moves to `DECOMPOSITION_SELECTED`, or — on
  BR-DEC exhaustion (no eligible pattern) — moves to `REFUND_PENDING`. `pattern_usage`/`sku_usage`
  counters (Section 32) are incremented for whichever pattern gets selected.
  - Module assignment: `PatternEconomics`/`PatternUsage`/`SkuUsage` (Sections 22.12–22.14) live in
    `pricing`, not `decomposition`, since margin/economics computation and usage counters are
    `pricing`'s stated responsibility even though they FK into `decomposition_pattern`.
    `PatternCandidate` (the type `decomposition` hands to `routing`) is defined in `routing`
    itself, not `decomposition` — Section 20.2's graph runs `decomposition → routing` only, never
    the reverse, so the shared type has to live on the side both can see.
  - **Section 28.2's hard invariant is enforced at runtime**: `PatternLookupService` sums each
    candidate's components' *current* `provider_sku.face_value × quantity` and drops (with a
    loud warning log) any pattern that doesn't sum exactly to the order's `parent_amount`, before
    it's ever handed to `RoutingService`. This is checked against the live SKU value, not
    `decomposition_component.face_value`'s generation-time snapshot — a mismatch between the two
    values is itself the structural-drift signal Section 30.3 describes, and checking the
    snapshot alone would validate that the pattern was self-consistent when generated without
    protecting the money that's actually about to move. Verified end-to-end against real Postgres
    by seeding a pattern whose components summed to 20,000 for a 30,000 order — it was correctly
    rejected and the order routed to `REFUND_PENDING` rather than attaching the mis-valued
    pattern; a correctly-summing pattern for a 20,000 order was, in the same run, correctly
    selected. This check was missing from the first version of this slice — added after review.
  - Three real bugs were found only by testing against real Postgres:
    - `decomposition_pattern.pattern_hash` was migrated as PRD-specified `CHAR(64)`, but a plain
      `@Column(length = 64)` String field defaults to Hibernate's VARCHAR type code regardless of
      `columnDefinition`, so schema-validation failed either way `CHAR`/`columnDefinition` were
      combined. Fixed by migrating the column as `VARCHAR(64)` instead (functionally identical for
      a fixed-length hex digest) — see the migration's inline comment.
    - `ParentOrderTransitionService.markPaid` and the new `selectPatternOrRefund` both run inside
      the same `@TransactionalEventListener(phase = AFTER_COMMIT)` callback invocation
      (`PaymentConfirmedEventListener`) and both need `Propagation.REQUIRES_NEW` for the same
      reason described above for `markPaid` — the second instance of this bug happened because an
      earlier version of this method's Javadoc incorrectly reasoned that being the *second* call
      in the method made it "an ordinary top-level transaction"; being anywhere inside that
      callback is what matters, not call order. It surfaced as `TransactionRequiredException` on
      the `@Modifying` usage-tracking upserts the first time this ran end-to-end, which a mocked
      test would not have caught.
    - A prior review round proposed removing `markPaid`'s `REQUIRES_NEW`, attributing its
      necessity entirely to the (now-fixed) webhook-dedup transaction-poisoning bug. Re-running the
      identical end-to-end scenario with that dedup bug already fixed reproduced the original
      silent-no-op exactly, disproving that theory — see `markPaid`'s Javadoc for the full account.
      Don't downgrade either method's propagation without repeating that same real-Postgres check.

- **Fulfillment / child order dispatch** (Section 33.2's `DECOMPOSITION_SELECTED → FULFILLING →
  SUCCESS`/`PARTIAL_FAILED`/`FAILED`, Section 34) — once a pattern is selected,
  `ParentOrderTransitionService.createChildOrdersAndBeginFulfillment` expands its components
  (via `decomposition`) into `child_order` rows (Section 22.16) and transitions to `FULFILLING`,
  then publishes `ChildOrdersReadyEvent`. `fulfillment`'s `FulfillmentExecutionService` dispatches
  each child order against a `GameProvider` (Section 27.1) — bounded retry for `TIMEOUT` (Section
  27.2, 1 initial + 2 retries), one `provider_transaction` row per dispatch *attempt* — then calls
  `completeFulfillment`, which applies Section 34.1's BR-ORD business-success criterion (all
  children `SUCCESS` → parent `SUCCESS`; some → `PARTIAL_FAILED`; none → `FAILED`) — the exact
  invariant `OrderStateMachine`'s NOTE flagged as unenforceable by the state table alone back in
  the decomposition/routing slice.
  - **Module boundary decision**: `child_order_id → provider_id` (needed for `provider_transaction`,
    Section 22.19) requires resolving `provider_sku.provider_id`, but neither `order` nor
    `fulfillment` has a `catalog` edge in Section 20.2's graph. Resolution happens in a new
    `app`-layer listener (`FulfillmentDispatchListener`), the same composition-root pattern
    `CreateOrderController` already uses for its own catalog/partner join — `order` publishes
    `ChildOrdersReadyEvent` with just `(childOrderId, providerSkuId, quantity)`, `app` resolves
    `providerId` via `catalog.ProviderSkuRepository` and calls into `fulfillment`. A `provider_sku`
    that vanishes between pattern selection and dispatch is marked `FAILED` directly
    (`ChildOrderService.markUnresolvable`) rather than silently dropped, so it can't leave a
    `child_order` stuck `PENDING` forever.
  - **Idempotency key scoping matters**: `provider_transaction`'s unique constraint is
    `(provider_id, idempotency_key)` (Section 22.19), and the key is `child-{childOrderId}-attempt-
    {attemptCount}` — scoped to the *dispatch attempt*, not just the child order. Section 27.2's
    internal timeout/5xx retries reuse the same key (correct: same logical purchase attempt,
    retried), but a later, separate dispatch call for the same child order (Section 33.2's
    authorized `PARTIAL_FAILED`/`FAILED → SUCCESS` retry) gets a new key from the next
    `attempt_count` value — otherwise it would silently no-op against the earlier failed attempt's
    row via `ON CONFLICT DO NOTHING` instead of recording the new outcome. **This only holds if
    whoever builds the not-yet-existing Admin Web retry action never resets `attempt_count` back
    to 0** when returning a child order to `PENDING` — flagged with a `<b>` warning directly on
    `ChildOrder.markExecuting()`'s Javadoc, since that's where a future reader would be looking.
    Verified with a Mockito unit test (`FulfillmentExecutionServiceTest`) simulating two dispatch
    attempts for the same child order, since exercising this for real would need the retry action
    this slice doesn't build.
  - A real bug was found only by running this end-to-end against Postgres: `ProviderTransactionRepository.insertIfAbsent`
    is a `@Modifying` native query, which throws `jakarta.persistence.TransactionRequiredException`
    without an active transaction. `FulfillmentExecutionService` is deliberately *not*
    `@Transactional` (the provider call is external I/O and must not hold a DB connection open —
    worse under Section 27.2's retry backoff), so the insert has to live in its own transactional
    bean, `ProviderTransactionRecorder` — a private `@Transactional` method on the same class
    would not have worked either, since Spring's proxy-based AOP doesn't intercept self-invoked
    calls. Every write reachable from `ChildOrdersReadyEvent`'s `AFTER_COMMIT` listener chain
    (`ChildOrderService.markExecuting`/`recordOutcome`/`markUnresolvable`,
    `ParentOrderTransitionService.createChildOrdersAndBeginFulfillment`/`completeFulfillment`,
    `ProviderTransactionRecorder.record`) uses `Propagation.REQUIRES_NEW` from the start this time,
    applying the lesson from the two `REQUIRES_NEW` bugs found in the previous two slices rather
    than rediscovering it a third time.
  - `selectPatternOrRefund` (previous slice) and the new `createChildOrdersAndBeginFulfillment`
    are deliberately two separate `REQUIRES_NEW` transactions/states (`DECOMPOSITION_SELECTED`
    then `FULFILLING`), not collapsed into one — Section 33.2 gives them distinct triggers, and
    collapsing them would mean a failure while creating child orders rolls back to `PAID` instead
    of leaving the order observably at `DECOMPOSITION_SELECTED`, losing the ability to tell
    "routing never found a pattern" apart from "routing succeeded but dispatch setup failed".
  - Verified end-to-end against real Postgres for all three Section 33.2 outcomes, using a
    2-component pattern and the dev-only `StubGameProviderAdapter` failure-injection property (see
    "Running locally"): both children succeed → parent `SUCCESS`; one fails → `PARTIAL_FAILED`
    with the succeeding child still `SUCCESS`; both fail → `FAILED`.

- **Ledger posting** (Section 36) — `ledger`'s `LedgerService.post(...)` is the one sanctioned way
  to write a `ledger_entry` row (Section 22.21), and it's wired at exactly one point: Section
  33.2's `PAYMENT_PENDING → PAID` transition, in `PaymentCallbackService`, right after
  `payment.markSuccess(...)` succeeds — a `PAYMENT`-type `CREDIT` entry for `payment.amount`,
  inside the same `@Transactional` as the state change itself (not `REQUIRES_NEW`: this is the one
  write in the recent slices where that reflex is wrong, since the ledger entry must commit
  *atomically with* the payment status change, not after it in a separate transaction — no `PAID`
  payment without its ledger entry, and no entry for a payment that rolled back).
  - **Deliberately the only posting point implemented.** Section 33.2's Side Effect column names
    exactly two ledger-posting triggers for the flow this codebase covers: this one, and
    `FULFILLING → SUCCESS`'s "Ledger: provider/fulfillment entries posted". The second was
    scoped out — see the gap below — rather than posted with a wrong amount.
  - **Immutability is enforced at the database level, not just by convention.** Section 22.21 says
    to "enforce via DB role privileges / triggers"; `V10__ledger.sql` adds a trigger that rejects
    both UPDATE and DELETE on `ledger_entry` with an explicit error, rather than relying on
    `LedgerEntryRepository` simply not exposing an update path. Verified directly against Postgres:
    both `UPDATE ledger_entry SET amount = 1` and `DELETE FROM ledger_entry` were rejected by the
    trigger, and sending the same webhook callback twice produced exactly one `ledger_entry` row
    (the existing `payment_event` dedup path returns `DUPLICATE_IGNORED` before the code ever
    reaches the ledger post, so no double-post path exists here).
  - A real bug was found only by running this end-to-end: Section 22.21 specifies `currency
    CHAR(3)`, migrated as `CHAR(3)`, but a plain `@Column(length = 3)` String field defaults to
    Hibernate's VARCHAR type code — the exact same class of mismatch as `pattern_hash` (`CHAR(64)`)
    in the decomposition/routing slice. Fixed the same way: migrated as `VARCHAR(3)` instead, with
    an explanatory comment.
  - `LedgerServiceTest` covers the one branch the end-to-end run couldn't reach: `post` rejecting
    a non-positive amount (negative and exactly `Money.ZERO`) without touching the repository —
    every real caller so far only ever passes an already-positive `payment.amount`, so this guard
    was otherwise dead code as far as any executed path was concerned, on a table where a wrong
    row can only be corrected by a reversing entry, never fixed in place.

- **Settlement report ingestion** (Section 37.1/37.2) — `POST /internal/settlement/ingest` takes
  one settlement report line, resolves `expected_amount` by summing `SUCCESS` payments for the
  report's date (a native query — see `PaymentRepository.sumSuccessfulAmountForDate`'s Javadoc for
  why JPQL `SUM` over a `Money`-converted column is the wrong tool here), compares it against the
  report's `actual_amount`, and calls `SettlementIngestionService.ingest` to persist a `Settlement`
  row (Section 22.22) and post a Settlement Ledger entry.
  - **Request shape is invented**, same treatment as `AyolinxCallbackPayload`: Section 37.1
    explicitly flags the real Ayolinx settlement report format as "file/API, format TBD — must be
    verified." Flagged inline, not a contract.
  - **Module boundary decision**: `settlement` has no compile dependency on `payment` (Section
    20.2 grants `settlement` only a `ledger` edge), so `expected_amount` is resolved in `app` —
    the same composition-root pattern used for fulfillment's `provider_id` resolution and order
    creation's catalog/partner join. `SettlementIngestionService` itself only compares two amounts
    it's handed and persists the result; it never queries `payment` directly.
  - **Posts to the Settlement Ledger unconditionally**, `MATCHED` or `DISCREPANCY` alike — Section
    36.1 assigns that ledger to "funds actually settling from PG/acquirer," and a discrepancy is
    about the *amount* being wrong, not about whether money landed. Verified end-to-end: a
    deliberately-wrong `actual_amount` still produced a `SETTLEMENT`/`CREDIT` ledger entry for the
    reported (not expected) amount, alongside the `DISCREPANCY` status. This is a different call
    from the Order Ledger, which is deliberately NOT posted anywhere (see below) — the difference
    is that Section 36.1 names exactly one candidate event for "funds actually settled" and there's
    no risk of double-counting the same money in two ledgers, unlike the Order Ledger case.
  - **No upsert / re-ingestion handling, on purpose, and only one batch per date.** A
    migration-level `UNIQUE(settlement_date)` constraint (not specified by Section 22.22 — an
    added protective safeguard, flagged as such in the migration) rejects a second report for a
    date that already has one. Designing update-in-place semantics for a corrected resend was
    considered and rejected: Section 37.1 never describes reports being resent, and building that
    branch would mean untested code deciding whether a financial ledger entry gets posted twice —
    the same shape of risk as the fulfillment slice's `attempt_count`-reset hazard, except here it
    would have been built rather than merely documented as a constraint on a future caller. The
    constraint is scoped to the date alone, not `(settlement_date, pg_reference)`, because
    `expected_amount` sums *every* `SUCCESS` payment for the date — a second, differently-referenced
    batch on the same date would compare its own partial `actual_amount` against that same
    cumulative day-total and be structurally guaranteed `DISCREPANCY` regardless of whether its
    numbers were actually correct, while also posting a second Settlement Ledger credit for money
    already credited once. Verified end-to-end: re-posting a second batch for an already-settled
    date fails with a constraint violation (surfaced as a 500 for now — see gaps) and neither a
    duplicate `settlement` row nor a duplicate `ledger_entry` is created, since both writes share
    the same transaction.
  - `SettlementStatus.PENDING` is schema-supported (Section 22.22) but unreachable in this one-shot
    ingestion flow, which always resolves directly to `MATCHED`/`DISCREPANCY` — there's no
    two-phase "expected row created, actual report arrives later" flow here.
  - Verified end-to-end against real Postgres: two `SUCCESS` payments plus one `PENDING` payment
    on the same date, ingested with the correct sum → `MATCHED`, `expected_amount` correctly
    excludes the `PENDING` payment; a second date ingested with a deliberately wrong
    `actual_amount` → `DISCREPANCY`, ledger entry still posted for the actual (wrong) amount; a
    third ingestion re-using an existing `(settlement_date, pg_reference)` → rejected, no
    duplicate rows anywhere.
  - `SettlementIngestionServiceTest` covers the MATCHED/DISCREPANCY branching and confirms the
    ledger post always uses `actual_amount`, not `expected_amount` — the one property that
    mattered enough to pin at the unit level alongside the e2e run.

- **Reconciliation — `PAYMENT_VS_SETTLEMENT` only** (Section 38.1/38.2) — `ReconciliationService`
  is the generic entry point for opening, investigating, and resolving a `reconciliation` record
  (Section 22.23). Of Section 38.1's five reconciliation types, exactly one is wired: whenever
  settlement ingestion (see above) produces `DISCREPANCY`, an `OPEN` `PAYMENT_VS_SETTLEMENT`
  record is created in the *same transaction* as the settlement row and its ledger entry.
  - **Composed synchronously in `app`, not via an event — a deliberate change from the
    payment→order/order→fulfillment pattern.** Those events exist because the publisher has no
    path to the consumer at all (no edge either direction) and the work is a genuinely separate
    async pipeline stage (Section 35.1). Here `app` already depends on `payment`, `settlement`,
    and `reconciliation`, and there's no external I/O forcing the transaction apart — so
    `SettlementIngestionOrchestrator` just calls all three synchronously in one `@Transactional`
    method. The reasoning matters: Section 38.2 says "**every** discrepancy is persisted to
    reconciliation," which reads as an invariant. An `AFTER_COMMIT`-listener version would let a
    committed `DISCREPANCY` settlement exist with no reconciliation record and no surfaced error
    if the listener's write failed — silently violating that invariant, the same failure shape
    this codebase has already been bitten by twice elsewhere. Making it one transaction means a
    failure is a 500 the operator retries, not a silent gap.
  - `recon_date` is the **settlement's** date (`settlement.getSettlementDate()`), not
    `LocalDate.now()` — Section 22.23 calls it "batch date," and for this reconciliation type the
    batch is the settlement batch; using the ingestion timestamp would break grouping by batch
    when a report is ingested late.
  - `discrepancy` is signed (`actual_value - expected_value`, Section 22.23's own definition), not
    guarded to be positive like `ledger_entry.amount` — a shortfall (the case Finance cares about
    most) is negative by construction, and `Reconciliation`/`ReconciliationService` don't reject
    it. Verified end-to-end: a report short by 5,000 produced `discrepancy = -5000`.
  - Verified end-to-end against real Postgres: a settlement with a deliberately-wrong
    `actual_amount` produced exactly one `OPEN` `PAYMENT_VS_SETTLEMENT` reconciliation row with
    `recon_date` matching the settlement date and the correct signed discrepancy; a second,
    correctly-matching settlement for a different date produced **zero** new reconciliation rows —
    the check that would have caught an unconditionally-firing trigger.
  - `investigate`/`resolve` enforce Section 38.2's "must be moved to `INVESTIGATING` then
    `RESOLVED`" — `resolve` rejects a direct `OPEN → RESOLVED` jump. Both are unreachable from any
    real path in this codebase (Admin Web, Section 40.8, the only intended caller, isn't built),
    so `ReconciliationServiceTest` is the only thing that exercises either method. **It covers the
    guards only, not the database write path** — with a mocked repository, the entity mutation
    `investigate`/`resolve` perform (relying on JPA dirty-checking to flush at commit) is only ever
    observed in-memory, never against Postgres. Whether that mutation actually persists is
    unverified until something drives these methods against a real database — flagged rather than
    assumed, given this exact class of gap (looks applied in Java, never lands) already bit this
    codebase once with `markPaid`.

- **QR expiry sweep** (Section 48.3, Section 33.2's `PAYMENT_PENDING → EXPIRED`) — `order`'s
  `QrExpirySweepJob` runs on a `@Scheduled` tick (default every 60s, `ppob2.order.expiry-sweep-
  interval-ms`), finds `PAYMENT_PENDING` orders whose `expires_at` has passed, and transitions
  each to `EXPIRED` via `ParentOrderTransitionService.expirePaymentPending`. Picked as the next
  slice specifically because it closes the one remaining hole in the order/payment flow built
  first, rather than opening a new subsystem — no new external contract, no new module dependency
  edge needed.
  - **Bounded per tick.** `ParentOrderRepository.findByStateAndExpiresAtBefore` takes a `Limit`
    (200 per run) — an unbounded query would load every expired order into memory on the first run
    after any period of accumulation. The sweep is idempotent and runs repeatedly, so draining
    across ticks is the intended behavior, not a limitation worked around.
  - **Also expires the linked `payment` row**, not just the order. Section 22.17 has an `EXPIRED`
    payment status; leaving it `PENDING` forever would make `GET /orders/{id}/payment` (Section
    23.6) and the Payment-vs-PG/Payment-vs-Settlement reconciliation types see a stale row for an
    order that's actually terminal. `Payment.markExpired()` mirrors the existing
    `markSuccess`/`markFailed` guard (only from `PENDING`).
  - **The guard that matters most**: `expirePaymentPending` checks `order.getState() !=
    PAYMENT_PENDING` before touching anything, which is what stops a payment confirmed in the
    narrow window between the sweep's query and this transition running from being expired out
    from under the customer. Verified end-to-end: an order paid (and left `REFUND_PENDING` by
    BR-DEC exhaustion, no pattern seeded in that run) with its `expires_at` forced into the past
    was left completely untouched by the next sweep tick — state and payment status unchanged —
    while a genuinely-unpaid order with the same forced `expires_at` correctly reached `EXPIRED`
    with its payment row also `EXPIRED`. `ParentOrderTransitionServiceExpiryTest` (Mockito) fills
    the states that e2e run couldn't reach without seeding a decomposition pattern — `SUCCESS`
    specifically, since `OrderStateMachine` has no `SUCCESS -> EXPIRED` edge, so a guard failure
    there would throw and abort the rest of that sweep tick's batch rather than cleanly skip — and
    the exact race the sweep exists to tolerate: an order still read as `PAYMENT_PENDING` whose
    payment already committed `SUCCESS` a moment earlier, where `markExpired()` must return false
    and log, never throw.
  - `@EnableScheduling` lives on its own `app`-layer `SchedulingConfig`, kept separate from
    `JpaConfig` for the same reason that class is already separate: avoid pulling infrastructure a
    `@WebMvcTest` slice doesn't need into its context. Confirmed the three existing `@WebMvcTest`
    classes still pass after adding it.
  - No distributed lock / leader election across app instances for the sweep — same class of
    simplification as nonce storage and routing's candidate cache. Benign here specifically: two
    instances racing on the same order would both write the identical target state (`EXPIRED`),
    not a real conflict — not worth solving before there's more than one instance.

- **`provider_price`** (Section 22.7, versioned provider cost per SKU) — `pricing`'s
  `ProviderPrice` entity, `ProviderPriceRepository`, and `ProviderPriceService`. Built first among
  the remaining backlog (`lanjutkan semua`) because it's pure foundational data with no dependency
  on anything else still missing, and it unblocks two previously-flagged gaps: Provider/Fulfillment
  Ledger posting (needs a real cost to debit) and `MARGIN_EXPECTED_VS_ACTUAL` reconciliation (needs
  actual vs. projected cost).
  - **References `catalog.provider_sku` by id only**, not by JPA relationship — Section 20.2's
    graph grants no `pricing -> catalog` edge (only `decomposition -> pricing` and `routing ->
    catalog`/`routing -> pricing`), so the FK lives in the migration only, matching the existing
    `SkuUsage`/`PatternUsage` pattern already in this module.
  - **"Exactly one active version per SKU" is a real Postgres constraint, not just
    `ProviderPriceService` discipline.** `provider_price_active_idx` is a partial unique index on
    `provider_sku_id` `WHERE effective_until IS NULL` — verified directly against real Postgres:
    inserting a second `effective_until IS NULL` row for the same `provider_sku_id` was rejected
    with `duplicate key value violates unique constraint "provider_price_active_idx"`.
    `ProviderPriceService.setPrice` closes the current active row (via a JPQL bulk update,
    `ProviderPriceRepository.closeActive`) before inserting the next version, in one transaction,
    so this index is never even momentarily violated by that path.
  - **Same `CHAR`-class migration bug hit again, different shape**: `providerCost` was first typed
    as a plain `long` field, which Hibernate maps to `BIGINT` — schema-validation failed against
    the PRD's `NUMERIC(18,0)` column (`wrong column type... found [numeric], but expecting
    [bigint]`). Fixed by typing the field as `Money` instead of `long`, which routes through
    `shared-kernel`'s `@Converter(autoApply = true) MoneyConverter` (`Money <-> BigInteger`) the
    same way `LedgerEntry.amount` already does against its own `NUMERIC(18,0)` column — the fix
    was "use the established Money-typed field," not a migration-side workaround.
  - **`setPrice`'s write path is unverified against real Postgres — flagged, not assumed.** Unlike
    `getActiveCost` (a plain derived-query read, structurally the same as `SkuUsageRepository`'s
    already-proven queries), `setPrice` has no real caller yet: seeding a price is done via direct
    SQL for now (`INSERT INTO provider_price ...`), the same way decomposition patterns are seeded
    since pattern generation isn't built either. `ProviderPriceServiceTest` (Mockito) covers the
    version-numbering and close-before-insert sequencing logic only. This will get real exercise
    once an admin/pricing-management use case calls it (candidate: Admin Web); until then it's the
    same class of gap as `ReconciliationService.investigate`/`resolve`.
  - Seed used for verification and left in the dev database:
    ```sql
    INSERT INTO provider_price (provider_sku_id, pricing_version, provider_cost, effective_from)
      VALUES (1, 1, 18000, now());
    ```

- **Provider/Fulfillment Ledger posting** (Section 36.1, unblocked by `provider_price` above) —
  `fulfillment`'s new `ProviderLedgerPoster` posts a DEBIT after a successful child-order purchase.
  - **Cost resolution lives in `app`, the ledger post itself lives in `fulfillment`** — two
    different module-boundary answers for two different joins. Section 20.2 grants `fulfillment ->
    ledger` directly, so the post is a normal call from `fulfillment`; it grants no `fulfillment ->
    pricing` edge, so `FulfillmentDispatchListener` resolves `ProviderPriceService.getActiveCost`
    the same way it already resolves `provider_id` from `catalog`, and carries the result on
    `ResolvedChildOrder.providerCost`.
  - **A SKU with no active `provider_price` is now treated exactly like a SKU that fails to
    resolve at all** — added to `unresolvableChildOrderIds` rather than dispatched with an unknown
    cost. Chosen over "dispatch anyway, skip the ledger post" because the latter would silently
    under-report the Provider Ledger with no record that anything was skipped.
  - **Guarded against double-posting.** `ProviderTransactionRecorder.record` now returns
    `RecordedProviderTransaction(providerTransactionId, newlyInserted)` — `newlyInserted` comes
    straight from `insertIfAbsent`'s row count. `FulfillmentExecutionService` only posts when the
    purchase succeeded **and** this dispatch attempt was the one that actually inserted the
    `provider_transaction` row; an internal Section 27.2 retry re-entering the same idempotency key
    hits `ON CONFLICT DO NOTHING` and must not post a second DEBIT into a no-UPDATE/DELETE table.
    This was an advisor-caught design issue, fixed before any code was written for it — the first
    draft would have posted from inside `ProviderTransactionRecorder.record` itself, which cannot
    distinguish a fresh insert from a no-op.
  - **`ProviderLedgerPoster` is its own `REQUIRES_NEW` bean**, same reasoning as
    `ProviderTransactionRecorder`: `FulfillmentExecutionService` is deliberately non-transactional
    (external I/O), `LedgerService.post` is `Propagation.MANDATORY`, and this whole call tree runs
    inside an `AFTER_COMMIT` listener.
  - Verified end-to-end against real Postgres with a single run designed to hit three risks at
    once: a two-component pattern where one `provider_sku` (quantity 2, cost 18,000/unit) has an
    active price and the other has none. Result — `child_order` 1 (qty 2) reached `SUCCESS` with
    one `provider_transaction` row and exactly one `ledger_entry` (`ledger_type='PROVIDER'`,
    `entry_type='DEBIT'`, `amount=36000` — proving the per-unit cost is actually multiplied by
    quantity, not just copied); `child_order` 2 (no active price) was marked `FAILED` via the
    unresolvable path with no `provider_transaction` and no ledger entry at all; the parent order
    correctly landed on `PARTIAL_FAILED`. `FulfillmentExecutionServiceTest` adds Mockito coverage
    for the two cases that specific run couldn't isolate on its own: a failed purchase posts
    nothing, and a re-entrant (`newlyInserted=false`) record posts nothing even though the purchase
    itself succeeded.
  - `provider_balance` (Section 22.20 — topup/debit/adjustment/threshold-alerting) is not built;
    this only posts the ledger-entry side of Section 36.1, same class of gap as Order Ledger
    posting below.
  - **`ProviderPriceService.getActiveCost` runs unwrapped inside the `AFTER_COMMIT` listener** —
    it's a read, so the write-visibility problem that forces `REQUIRES_NEW` everywhere else in this
    call tree doesn't apply, and it needs no transaction of its own. Confirmed against the bootRun
    log for the verification run above, not just inferred from the result: the exact line
    `child_order 2 references provider_sku 2 with no active provider_price` appears for SKU 2
    specifically, while SKU 1 resolved to 18000 and produced the correct 36000 debit — so this is a
    confirmed read path, not a coincidental `PARTIAL_FAILED` from an unrelated failure.

- **`ORDER_VS_FULFILLMENT` reconciliation** (Section 38.1: "Parent/child order final states vs
  expected all-success criterion" — "Detect silent partial failures") — a new `app`-layer
  `OrderFulfillmentReconciliationOrchestrator`, called right after `fulfillmentExecutionService
  .dispatch(...)` returns in `FulfillmentDispatchListener`. Same composition-root shape as
  `SettlementIngestionOrchestrator` (`reconciliation` has no edge to `order`), but `REQUIRES_NEW`
  rather than synchronous — this call tree runs inside an `AFTER_COMMIT` listener, not a request
  thread.
  - **Value definition, decided up front rather than left implicit**: `expectedValue =
    parent_order.parent_amount` (the BR-DEC invariant, Section 28.2, guarantees this equals the
    sum of every child order's contribution); `actualValue` = sum of `ChildOrder.faceValue` over
    only the children that reached `SUCCESS`. Only opened when the order did **not** reach
    `SUCCESS` — a fully successful order has nothing to reconcile, same treatment settlement gives
    `MATCHED`.
  - **`child_order` gained a `face_value` column not in Section 22.16's documented schema** (V14 /
    V15 migrations) — populated once, at creation, from `catalog.provider_sku.face_value` (per
    Section 22.6, "Value contribution toward parent_amount," unambiguously per-unit) times
    quantity. This was **not** the first design: the initial plan was to re-derive each child
    order's value later by joining `decomposition_component` on `provider_sku_id`, which advisor
    caught as unsafe — nothing constrains a pattern to at most one component per SKU, so that join
    can silently pick the wrong row or double-count. Worse, `decomposition_component.face_value`'s
    own semantics for `quantity > 1` turned out to be undefined by any precedent or constraint in
    this codebase (`PatternLookupService` already ignores it in favor of the live
    `provider_sku.face_value` for this exact reason — see its Javadoc). `PatternComponentDto` and
    `PatternComponentQueryService` (both in `decomposition`) were changed to resolve and carry the
    live per-unit value instead of the ambiguous snapshot.
  - **A stuck order (`FULFILLING`, `completeFulfillment`'s own guard for children still
    `PENDING`/`EXECUTING`) is skipped, not reconciled against** — the numbers would be mid-flight
    and meaningless. Same treatment `expirePaymentPending` gives an unexpected state.
  - **Idempotent per parent order** via `ReconciliationService.hasOpenDiscrepancy` (checked before
    `open`) — but this guard is **verified by Mockito unit test only, not end-to-end**. Section
    33.2's authorized retry (the only real trigger for a second dispatch of the same parent order)
    is an Admin Web action that doesn't exist yet, so there is no real path to drive a genuine
    duplicate through this code — same class of gap as `setPrice`. No DB-level backstop either
    (unlike `provider_price_active_idx`); flagged rather than built, since there's no
    concurrent-writer risk today (fulfillment dispatch for a given parent order isn't
    parallelized).
  - Verified end-to-end against real Postgres: a fresh order against the same two-component
    pattern used for the ledger slice (SKU 1 qty 2 @ 20000/unit = 40000, SKU 2 qty 1 = 20000,
    parent_amount 60000) reached `PARTIAL_FAILED`, and the resulting `reconciliation` row is
    exactly the expected fixture — `expected_value=60000`, `actual_value=40000`,
    `discrepancy=-20000`, `status=OPEN`. `OrderFulfillmentReconciliationOrchestratorTest` (Mockito)
    covers the branches that one run can't isolate: `FULFILLING` skip, a fully successful order
    opening nothing, `REFUND_PENDING` opening nothing, and the duplicate-open guard.
  - The parent order from the earlier Provider/Fulfillment Ledger slice's e2e run (`parent_order`
    1) predates the `face_value` column and had been left at the `DEFAULT 0` backfill value; its
    two child orders were updated to their real values (40000 / 20000) for consistency, though no
    reconciliation record was retroactively generated for it (this orchestrator didn't exist when
    that dispatch ran).

- **Outbound webhook delivery** (Section 23.8, `ORDER_STATUS_CHANGED`) — `callback_url` (Section
  23.4's documented Create Order request field, previously accepted and silently dropped — a
  named gap, not an invented one) is now stored on `parent_order` (V16 migration) and threaded
  through `CreateOrderCommand` → `ParentOrderCreationService`. A new `partner.webhook_secret`
  column (V16) holds "PPOB1's registered secret" — undocumented in Section 22.2, same caveat as
  `api_client.secret_hash`: verifying an HMAC requires the actual secret, not a one-way hash, so
  despite the name this must be the reversible signing secret; worth raising with the PRD owner.
  - **Scope, held deliberately narrow at the time**: exactly one delivery attempt per
    terminal-state transition, outcome recorded in `webhook_event` (`direction=OUTBOUND`). Section
    23.8's "5 attempts over 24h" retry-with-backoff schedule was **not** built in this slice —
    **closed in a later slice, see "Webhook Retry Sweep" below** (persisted attempt count,
    scheduled re-driver; dead-letter/inquiry fallback still isn't built, since that means PPOB1
    polling our Open API, not something we push). Trigger coverage was partial at the time of this
    entry: only the post-dispatch terminal states (`SUCCESS`/`PARTIAL_FAILED`/`FAILED`, fired from
    `FulfillmentDispatchListener` right after the reconciliation orchestrator) sent a webhook;
    `CANCELLED`/`EXPIRED`/`REFUNDED` transitions and the QR expiry sweep's own state change
    (Section 48.3's diagram) did not. **`EXPIRED` was added by the 2026-10-10 entry below**, on a
    second path (`OrderExpiredWebhookListener`, which queues rather than sends); `CANCELLED` and
    `REFUNDED` still do not fire, the first deliberately.
  - **The body gained a sixth field later**, `reseller_txn_id`, which Section 23.8 does not
    document — see the 2026-10-10 entry on the callback carrying the reseller's own id for why, and why
    only that one key of `metadata` is echoed.
  - **`webhook`'s new `OutboundWebhookSender`** signs with the exact same `HmacSigner` scheme as
    inbound Open API auth (Section 23.2), using the callback URL's own path component. Uses
    `WebClient` (the `spring-boot-starter-webflux` dependency this module already declared but
    never used) purely as a blocking HTTP client with a 5s timeout — no reactive chain elsewhere.
  - **`app`'s new `WebhookDeliveryTargetResolver` + `OutboundWebhookOrchestrator`** — same
    composition-root shape as `OrderFulfillmentReconciliationOrchestrator` (`webhook` has no edge
    to `order`/`partner`). A partner with no `webhook_secret` is treated as undeliverable and
    logged at ERROR — never sends unsigned.
  - **Two real bugs found only by driving this end-to-end against real Postgres and a real local
    HTTP receiver — both the same root cause seen from two sides: a write or read reached from
    inside the `AFTER_COMMIT` call tree cannot rely on Spring's default transaction handling.**
    1. *Stale read.* The first version read `ParentOrder` with a plain (non-`@Transactional`)
       repository call. A real run returned `state=FULFILLING` from that read *after* the log line
       proving `ParentOrderTransitionService` had already committed `PARTIAL_FAILED` moments
       earlier in its own `REQUIRES_NEW` transaction — an empirical fact, confirmed by log
       timestamps, not a guess. The exact mechanism is **not** confirmed: `open-in-view: false` is
       set in `application.yml`, which rules out the obvious Open-Session-In-View explanation, and
       no alternative mechanism was pinned down before moving on — this is deliberately reported as
       "what happened and what fixed it," not "why." Fixed by moving the read into
       `WebhookDeliveryTargetResolver.resolve`, its own `@Transactional(REQUIRES_NEW)` method in a
       **separate bean** (not a private method on the orchestrator — self-invocation would silently
       skip the proxy, the same class of bug this codebase already hit with
       `ProviderTransactionRecorder`).
    2. *Lost write.* After fixing the read, the delivery attempt itself succeeded (verified against
       the real local receiver) but the `webhook_event` row never appeared — `WebhookEventRecorder
       .record()` calling `repository.save()` with no ambient transaction returned normally but
       persisted nothing. `record()` had only ever been called from inside `PaymentCallbackService
       .processCallback`'s own `@Transactional` method before; this was the first call site with no
       surrounding transaction at all. Fixed by adding `WebhookEventRecorder.recordIndependently`,
       `@Transactional(REQUIRES_NEW)`, for callers with no ambient transaction — `record()` itself
       is untouched so the inbound path (where the row correctly rolls back with the payment
       processing it logs) keeps its existing behavior.
    Both fixes are covered by the same established rule already documented elsewhere in this
    README ("any write reachable from inside a `@TransactionalEventListener(AFTER_COMMIT)` call
    tree needs `REQUIRES_NEW`") — this slice is the first time that rule turned out to apply to a
    *read* as well, and the first time a previously-safe helper (`WebhookEventRecorder.record`)
    became unsafe when called from a new, less-privileged call site.
  - **Verified end-to-end against real Postgres and a real local HTTP receiver** (a small Python
    `http.server` script), covering both branches:
    - *Success*: an order with `callback_url` pointing at the local receiver reached
      `PARTIAL_FAILED`; the receiver logged the exact incoming request (path, `X-Timestamp`,
      `X-Nonce`, `X-Signature`, body); **the signature was independently recomputed from the
      captured timestamp/nonce/body with `openssl` and matched byte-for-byte** — proving the
      signing scheme is correct, not just that a POST arrived. `webhook_event` row: `direction
      =OUTBOUND`, `status=SUCCESS`.
    - *Failure*: an order with `callback_url` pointing at a port with nothing listening reached the
      same terminal state; delivery failed with `Connection refused`, was caught (never propagated
      out of the listener), and `webhook_event` recorded `status=FAILED` with the failure reason in
      the payload. The HTTP request to `/internal/webhooks/ayolinx` still returned in ~0.24s in
      both cases — the failure path doesn't hang the caller.
  - `WebhookDeliveryTargetResolverTest` (Mockito) covers the guard branches that single run
    couldn't isolate on its own: non-terminal state, missing `callback_url` (null and blank), no
    resolvable partner, and a partner with no `webhook_secret`.

- **Admin Web — authentication + reconciliation actions** (Section 41.9 / 42 / 43) — the first
  real code in the `admin` module. Deliberately scoped to exactly what closes a gap flagged three
  times earlier in this README ("`ReconciliationService.investigate`/`resolve` are unreachable
  from any real path"), not the full Section 41 surface (dashboards, transaction lists, catalog
  CRUD, etc. — all still unbuilt).
  - **Real authentication, not a stub.** A genuine `admin_user` table (V17 migration, Section
    22.26's columns) backs a `SecurityFilterChain` for `/admin/**` using HTTP Basic +
    `DaoAuthenticationProvider` + `BCryptPasswordEncoder` (`admin`'s new `AdminUserDetailsService`,
    `AdminPrincipal`). Section 42's RBAC (fine-grained permission checks) is now built — see
    "Admin RBAC" below. **Not built at the time of this slice, since built (2026-10-22, see below)**: Section 42.3's
    session management (idle + absolute timeout, revocation) and MFA. HTTP Basic is now off by default.
    `admin_user.last_login_at` is mapped but never written — schema-only, not silently "maintained."
  - **`AdminReconciliationController`** (`app.web`, matching this codebase's established
    composition-root-owns-controllers convention) exposes `POST /admin/reconciliations/{id}
    /investigate` and `.../resolve`. The acting admin's id comes from the authenticated
    `AdminPrincipal`, never a request body field — a resolve action can't be attributed to
    whoever the caller claims to be. Every mutation is recorded to `audit_log` via the
    already-existing `AuditService.recordAdminAction` (built earlier in the session, previously
    unused) with real before/after status snapshots — Section 43's "Recorded" requirement, closing
    another previously-unexercised piece of code.
  - **Verified end-to-end against real Postgres** with the four cases that actually discriminate
    real security from a reachable-but-unguarded endpoint:
    1. No credentials → `401`, target row unchanged.
    2. Wrong password → `401` — proves BCrypt verification is real, not a provider that accepts
       anything (a passing case 3 alone wouldn't prove this).
    3. Valid credentials → `investigate` on an `OPEN` row → `INVESTIGATING`, with an `audit_log`
       row carrying `actor_type=ADMIN_USER`, the real `actor_id`, and
       `{"status":"OPEN",...} → {"status":"INVESTIGATING",...}`.
    4. `resolve` on that same row → `RESOLVED` with `resolved_by` set to the authenticated admin's
       id; then `resolve` called directly on a **different**, still-`OPEN` row → `applied:false`,
       row left untouched. This is the exact guard `ReconciliationServiceTest` could only prove
       against a mocked repository — now proven against a real database.
  - Read-side Section 41.9 views (list/detail/discrepancy browsing) are not built — this slice is
    the mutation path only.
  - **Follow-up (same auth mechanism, applied to a second endpoint)**: `/internal/settlement/**`
    was a `permitAll` chain since the settlement slice, flagged there as needing "real authn/authz
    (or an Admin-Web-gated trigger)" once one existed. With `adminFilterChain` now real, it was
    widened to also match `/internal/settlement/**` rather than left open — verified: an
    unauthenticated `POST /internal/settlement/ingest` now returns `401`, and the same request
    with valid admin credentials still ingests correctly (`200`, real `DISCREPANCY`/`MATCHED`
    response body unchanged).

- **Admin RBAC** (Section 42.1 / 42.2) closes the RBAC gap the Admin Web slice above flagged:
  every `ACTIVE` admin_user could previously call every `/admin/**` action with no per-permission
  check. Section 42.3 (MFA, idle+absolute session timeout) was explicitly out of scope here — a separate gap, not
  silently folded into this one; it was closed on 2026-10-22 (see the "Admin sessions and TOTP MFA" entry).
  - **New tables** (V19 migration): `role`, `permission`, `admin_user_role`, `role_permission`
    (Section 22.26). Seeded: all 7 Section 42.1 roles as reference data, but only the 4 permission
    codes this codebase actually gates something with today (`retry:execute`,
    `reconciliation:investigate`, `reconciliation:resolve`, `settlement:ingest`) — Section 42.2
    names several more as examples (`order:view`, `config:edit`, ...); seeding a permission nothing
    checks would be reference data pretending to be enforcement. The role→permission mapping is
    this codebase's interpretation of Section 42.1's descriptive "Typical Scope" text (not a
    literal table) — see the migration file's comment for the per-role reasoning, including why
    `settlement:ingest` goes to `FINANCE` and not `RECONCILIATION` (whose scope says "settlement
    *read*"). **`SUPER_ADMIN`'s grants are enumerated explicitly, not a cross join to `permission`**
    — a cross join would silently hand SUPER_ADMIN every future permission a later migration adds
    without that migration's author ever deciding so; any migration adding a new permission must
    also add its own explicit SUPER_ADMIN grant row.
  - **No `admin_user_role` rows are seeded** — who holds which role is operational data, same
    treatment as `admin_user` itself. This means a fresh deploy has every admin locked out of all
    four gated actions until someone assigns roles — correct-by-default, not a bug, but worth
    knowing before assuming a fresh environment is broken.
  - **Mechanism**: `AdminUserRepository.findPermissionCodes` (native 3-table join, no `Role`/
    `Permission` JPA entities — same precedent as `ProviderTransactionRepository.insertIfAbsent`)
    resolves an admin_user's granted permission codes; `AdminPrincipal` exposes them as plain
    (non-`ROLE_`-prefixed) `GrantedAuthority`s alongside the existing `ROLE_ADMIN`; each gated
    controller method carries `@PreAuthorize("hasAuthority('...')")`
    (`SecurityConfig`'s new `@EnableMethodSecurity`).
  - **Real bug found and fixed during live verification**: a `@PreAuthorize` denial throws
    `AuthorizationDeniedException` from inside the controller's AOP proxy — `DispatcherServlet`'s
    own exception resolution (which backs `GlobalExceptionHandler`'s `@RestControllerAdvice`)
    resolves that *before* the exception can ever propagate up the filter chain to
    `ExceptionTranslationFilter`. A `SecurityConfig`-level `AccessDeniedHandler` bean was built
    first (the textbook-correct place for this), wired via `.exceptionHandling(...)`, and observed
    live to **never fire** — every denial surfaced as a raw `500 INTERNAL_ERROR` instead of `403`.
    Fixed by adding `GlobalExceptionHandler.handlePermissionDenied` (an
    `@ExceptionHandler(AccessDeniedException.class)`, since `AuthorizationDeniedException` extends
    it) and deleting the dead filter-level handler entirely, rather than leaving an unreachable
    branch. Worth remembering for any *future* `@PreAuthorize` usage in this codebase: the 403
    envelope belongs in `GlobalExceptionHandler`, not a `SecurityConfig` `AccessDeniedHandler` —
    the latter only ever fires for a URL-level `authorizeHttpRequests` denial (a different call
    site, earlier in the filter chain, before `DispatcherServlet` is even entered).
  - `adminFilterChain`'s existing `hasRole("ADMIN")` URL-level check is now provably unable to
    deny anyone by itself: every `AdminPrincipal` always carries `ROLE_ADMIN` unconditionally, so
    that check can only ever reject unauthenticated/wrong-credential requests (401) — the
    permission-level gating is what does real work now.
  - **Verified end-to-end against real Postgres**, discriminating real gating from a
    misconfigured/typo'd permission string (a same-shaped 403 would result from either):
    1. An admin with `OPERATIONS` (only `retry:execute`) → retry succeeds (`200`), and
       `audit_log` records the real actor — denied calls never reach the controller body, so
       nothing is written to `audit_log` for them (checked directly).
    2. The same `OPERATIONS` admin → `reconciliation:investigate` → `403 PERMISSION_DENIED`, and
       the target `reconciliation` row's `status`/`resolved_by` are provably untouched (the AOP
       proxy blocks the call before the method body, hence before any repository write, ever
       runs).
    3. A fresh admin with **no** role assigned (the fresh-deploy default) → `403` on all four
       gated endpoints.
    4. The same no-role admin, after being granted `SUPER_ADMIN` → all four endpoints succeed
       (`200`/domain-level response, never `PERMISSION_DENIED`) — this is what actually proves the
       permission strings in the `@PreAuthorize` annotations match the seeded `permission.code`
       values exactly; four denials alone can't distinguish "correctly denied" from "misspelled
       permission code," only a subsequent grant-and-succeed can.
    5. `X-Correlation-Id` sent on a denied request is echoed back unchanged inside the `403`
       body's `correlation_id` field (same `GlobalExceptionHandler.correlationId` fallback path
       every other error response already uses — no new correlation-id plumbing was needed once
       the fix moved into `GlobalExceptionHandler`).
  - One unit test (`AdminPrincipalTest`) covers `getAuthorities()` shape directly; it does not
    (and can't, on its own) prove `@PreAuthorize` is actually wired — that's what the live
    verification above is for.
  - Dev-DB note: verifying this required rotating `admin1`'s `password_hash` to a locally-known
    dev password and adding a second admin user (`noroleadmin`) with no roles, following the same
    pattern already noted below under "Running locally" — neither hash nor either plaintext
    password is committed anywhere; a future session picking up this same dev database will need
    to re-rotate both via the same `bcrypt.hashpw` + `UPDATE`/`INSERT` commands.

- **Settlement ingest audit** closes the inconsistency the Admin RBAC slice above introduced:
  `SettlementIngestionController.ingest` carries `@PreAuthorize("hasAuthority('settlement:ingest')")`
  — formally an admin action — but had no `AuditService` call at all, unlike the other three
  `@PreAuthorize`-gated endpoints. **The snapshot shape differs from those three, and is documented
  as such in the controller's Javadoc**: the other three take a `before` snapshot of an entity that
  already exists, then compare against `after`. Here, the `settlement` row doesn't exist until
  `SettlementIngestionOrchestrator.ingest` returns — there is nothing to snapshot beforehand. This
  records post-hoc instead: `beforeState` is always `null`, `targetId` is the newly-created
  settlement's id, `afterState` is the resulting settlement. Verified live: one ingest as an admin
  with `settlement:ingest` produced an `audit_log` row with the real `actor_id` and the correct new
  `target_id` — see `SELECT ... FROM audit_log ORDER BY id DESC LIMIT 1` after ingesting.

- **Module architecture check** (`ModuleArchitectureTest`, `app` module, new `com.tngtech.archunit`
  test dependency) — the smaller, previously-flagged half of the "no ArchUnit enforcement" gap.
  One rule so far: every `@Entity` must live in a `..domain..` package. Runs from `app` because
  it's the only module wired to every other module (Section 20.2's composition root) and can
  therefore import the whole `id.ppob2` package tree in one `ClassFileImporter` scan. Passes today
  — every real `@Entity` in this codebase already followed the convention (two grep matches for
  `@Entity` outside `domain` turned out to be a Javadoc mention and an unrelated `@EntityScan`
  annotation, not a violation). Deliberately just the one rule: the Section 20.2 module-level graph
  itself is already enforced, more cheaply, by the Gradle project-dependency edges — duplicating
  that in ArchUnit form isn't a new risk closed, just the same one enforced twice.

- **Inquiry-before-retry** (Section 34.1's third failure class: "connection reset after the
  request was already sent") closes the gap this README used to flag as "`GameProvider.inquire()`
  exists on the interface for fidelity but nothing calls it."
  - **New `PurchaseStatus.AMBIGUOUS`**, distinct from `TIMEOUT` (idempotent-safe, auto-retried) and
    `FAILED` (terminal, never retried): a purchase whose actual outcome the caller genuinely
    doesn't know. Neither of the existing two treatments is safe for it — blind retry risks a real
    double-purchase if it actually succeeded; treating it as terminal-failed risks silently eating
    a purchase that actually went through and never posting its ledger debit.
  - **`FulfillmentExecutionService.resolveAmbiguous`** calls `GameProvider.inquire(idempotencyKey)`
    — the idempotency key, not a provider reference, since the ambiguous result carries no
    provider-issued reference (the response that would have carried one was lost); the idempotency
    key is the only identifier both sides share to look the attempt up by. Three inquiry outcomes,
    handled distinctly rather than collapsed into two: a confirmed `SUCCESS` returns as a normal
    `PurchaseResult.success(...)`; a confirmed `FAILED` returns as `failed(...)`; a `TIMEOUT` (the
    inquiry call itself being inconclusive — a third case, not a confirmed failure) is logged at
    ERROR as needing manual review, since this codebase has no further automated escalation once
    inquiry itself can't disambiguate.
  - **Deliberately narrower than Section 34.1's literal phrasing** ("inquiry-before-retry ...
    before deciding whether to retry"): a confirmed `FAILED` is this attempt's terminal outcome,
    not fed back into the automatic-retry loop — even though a retry at that point would in fact
    be idempotent-safe (inquiry just proved nothing happened). Section 27.2's bounded automatic
    retries stay reserved for the ordinary TIMEOUT class; an ambiguous failure is an unusual event
    worth surfacing, not silently absorbing into another invisible auto-retry. The existing Admin
    Web retry action (`AdminChildOrderRetryOrchestrator`) is the actual recovery path for this
    case — the same mechanism Section 33.2's `PARTIAL_FAILED -> SUCCESS` transition already uses.
  - **A confirmed-success is recorded through the exact same `provider_transaction` + ledger-debit
    path a normal direct success uses — deliberately not a separate "mark SUCCESS" shortcut** that
    sets `ChildOrder.state = SUCCESS` directly and bypasses that bookkeeping. The provider genuinely
    fulfilled the purchase in this scenario, so skipping the ledger debit would leave a real charge
    with no `provider_transaction` row and no debit — an unaudited spend, a Section 36.1 violation.
    What inquiry-before-retry actually avoids is calling `purchase()` a second time for the same
    attempt (the real double-purchase risk); it was never meant to skip recording a purchase that
    did happen.
  - **`StubGameProviderAdapter`** gets a new `ambiguous-provider-sku-ids` dev-only knob (same shape
    as the existing `fail-provider-sku-ids`): a configured provider_sku id returns `AMBIGUOUS`
    deterministically from `purchase()`, and `inquire()` always confirms `SUCCESS` with a freshly
    synthesized `STUBPROV-INQUIRY-...` reference — not the caller's own idempotency key echoed
    back, since a real provider's inquiry response carries its own transaction reference, never
    the caller's.
  - **Verified end-to-end against real Postgres**, with the check that actually discriminates
    correct inquiry-resolution from a silent double-purchase or an unaudited spend: not "did the
    child order reach `SUCCESS`" (that passes either way), but **exactly one** `provider_transaction`
    row and **exactly one** ledger `DEBIT` for the child order whose SKU was configured ambiguous.
    Created an order for `provider_sku_id=2` configured as ambiguous
    (`PPOB2_FULFILLMENT_STUBPROVIDER_AMBIGUOUSPROVIDERSKUIDS=2`), paid it via a signed callback,
    and confirmed: the child order reached `SUCCESS`; its `provider_transaction.provider_reference`
    was the synthesized `STUBPROV-INQUIRY-...` value (proving the inquiry path ran, not a normal
    purchase); exactly one `provider_transaction` row and one `ledger_entry` (`DEBIT`) exist for it;
    and the app log showed the exact sequence (`Ambiguous purchase result...` →
    `Inquiry confirmed ... actually succeeded`).

- **`MARGIN_EXPECTED_VS_ACTUAL` reconciliation** (Section 38.1: "pattern_economics projected
  net_contribution vs actual computed post-fact from real provider cost/payment fee" — "Detect
  margin erosion, pricing drift") — a new `app`-layer `MarginReconciliationOrchestrator`, wired
  into the same `FulfillmentDispatchListener` call site as the other two orchestrators.
  - **Compares gross profit, not net contribution — a deliberate narrowing of Section 38.1's
    literal wording, not an oversight.** `net_contribution` has `payment_fee` subtracted, but no
    per-order PG fee exists anywhere in this codebase (only `settlement.fee_amount`, at
    daily-batch granularity), and `direct_cost` — a term Section 22.12's own formula references —
    has no column at all. Comparing net figures with an assumed fee of 0 would flag *every* order
    as a "discrepancy" of at least the projected fee, making the type useless as a drift signal.
    This compares `pattern_economics.gross_profit` (projected) against `parent_amount -
    actual_provider_cost` (actual) instead — both are real, both exclude the unavailable fee term,
    and the difference means exactly what Section 38.1 wants: provider-cost drift. The full net
    comparison remains blocked on per-order fee attribution and the missing `direct_cost` column —
    neither is inventable from what exists.
  - **Fires only on `SUCCESS`, never `PARTIAL_FAILED`/`FAILED`** — this was the first design
    instinct (advisor-corrected before any code was written): `pattern_economics` projects a
    *fully fulfilled* pattern, and comparing it against a partially- or wholly-failed order
    produces a nonsensical result — a bigger failure means less cost, which against the same full
    `parent_amount` numerator looks like a bigger margin *gain* (worked through with the actual
    fixture: a fully `FAILED` order would show a phantom +36,500 "margin improvement"). The refund
    that would offset a failed order's revenue isn't built, and `ORDER_VS_FULFILLMENT` already
    covers the failure case. This type covers successes only, where "same shape as projected"
    actually holds.
  - **Actual provider cost comes from `ledger_entry` (the Provider/Fulfillment Ledger's real
    DEBITs for this order's successful children), not the current `provider_price`.** Re-deriving
    cost from today's active price would be wrong if the price changed since purchase — exactly
    the drift this type exists to catch, so the comparison can't use the same input on both sides.
  - **Compares against the `pattern_economics` snapshot in force on-or-before the order's own
    creation date**, not the latest one, so a later re-scoring run can't retroactively redefine
    "expected" for an order already placed. Ordering by `snapshot_date` alone is safe from ties:
    `pattern_economics_uk UNIQUE(pattern_id, snapshot_date)` guarantees at most one row per
    pattern per date.
  - **Verified end-to-end against real Postgres with a pair of runs designed to prove
    discrimination, not just that the type can open a record**: after pricing the previously-
    unpriced second SKU (unblocking a full `SUCCESS` for the two-component pattern used
    throughout this README), an order was driven to `SUCCESS` with real ledger debits of 36,000
    and 15,000 (51,000 total) against `parent_amount` 60,000 — actual gross profit 9,000, compared
    against the existing (stale, pre-provider_price-era) projection of 24,000, correctly opening a
    discrepancy of exactly **-15,000**, computed and predicted before the query ran, then matched.
    `pattern_economics.gross_profit` was then updated to 9,000 (matching the now-known actual) and
    a second, otherwise-identical order was driven through: it also reached `SUCCESS`, and
    **no new reconciliation record opened** — proving the zero-drift case is a true no-op, not
    that the code merely always fires. **Note for future readers**: `reconciliation` row 11 (the
    drift case) references `pattern_economics` for pattern 1, which was mutated in place
    immediately afterward for the zero-drift test — querying that pattern's *current*
    `gross_profit` today will show 9,000, matching row 11's own `actual_value`, which can look
    like the discrepancy was spurious. It wasn't: it was computed against 24,000, the value in
    force at the time, before the update.
  - `MarginReconciliationOrchestratorTest` (Mockito) covers branches that pair of runs didn't
    reach: `PARTIAL_FAILED` skip, no pattern selected, no `pattern_economics` snapshot at all, and
    the duplicate-open guard.

- **Open API order lookups & cancel** (Sections 23.5-23.7: `GET /api/v1/orders/{order_id}`,
  `GET /api/v1/orders/{order_id}/payment`, `POST /api/v1/orders/{order_id}/cancel`) — the three
  PRD-documented Open API endpoints that weren't part of the original `POST /api/v1/orders` slice.
  New `order.OrderQueryService` composes `ParentOrder` + `Payment` + `ChildOrder` data (`order`
  already has the Section 20.2 edges to both); a new `OrderQueryController` in `app` resolves the
  caller's numeric `partner_id` the same way `CreateOrderController` does, since `order` has no
  edge to `partner`.
  - **Every lookup is scoped to the resolved `partner_id`, not `client_id`.** A partner can
    register multiple `api_client` rows (Section 22.3); scoping on `client_id` would hide a
    partner's own orders placed through a different client. A `partner_id` mismatch (order exists,
    belongs to a different partner) is reported identically to a genuinely unknown `order_id` —
    `404 ORDER_NOT_FOUND`, never a distinguishing `403` — so one partner cannot use these endpoints
    to probe for another partner's order numbers by guessing order IDs. Verified end-to-end: a
    second partner + `api_client` was seeded (`PPOB3`/`ppob3-client`) and used to `GET`/`cancel` an
    order owned by the original `PPOB1` partner — all three requests returned `404
    ORDER_NOT_FOUND`, not `403` or the order's real state.
  - **`cancel` uses plain `@Transactional` (REQUIRED), not `REQUIRES_NEW`** — unlike every
    `ParentOrderTransitionService` method reached from the `AFTER_COMMIT` fulfillment pipeline,
    this one runs synchronously from a controller handling a direct partner request, matching
    `expirePaymentPending`'s existing reasoning exactly (see that method's Javadoc).
  - **Cancel does not touch the linked `payment` row.** Section 22.17 has no `CANCELLED` payment
    status, and a `PAYMENT_PENDING` order may already have a `PENDING` payment at the gateway. If a
    payment confirmation arrives after cancellation, `markPaid`'s existing late-callback guard
    (order no longer transitionable) already stops it from forcing the order back to `PAID` —
    collected-funds handling for that race is Section 25.2's reconciliation territory, not built
    here.
  - **`fulfillment_summary`'s three counts don't necessarily sum** while an order is still
    `FULFILLING` — a child order can be `PENDING`/`EXECUTING` (neither success nor failed yet).
    Documented on `OrderDetailResult` so an integrator doesn't wrongly treat
    `total_child - success` as `failed`.
  - `ParentOrder.updatedAt` was added as a new read-only (`insertable = false, updatable = false`)
    mapping of the `updated_at` column. Confirmed (`\d parent_order`, not assumed) that the
    `parent_order_set_updated_at BEFORE UPDATE` trigger already maintains it — no entity read it
    before this slice. Schema validation passed on boot (`TIMESTAMPTZ → Instant` already matches
    `created_at`'s mapping), and the end-to-end run below shows it moving on its own: order 13's
    `updated_at` was `12:42:23.016743Z` right after creation and `12:42:43.374860Z` after cancel,
    with nothing in this codebase's Java writing that column.
  - **A `GET .../payment` on an order with no payment row yet returns 200, not 404** — with every
    payment field *absent* from the JSON body (Jackson drops `null`s by default; verified this is
    literally what comes back — `{"order_id":"..."}`, not `{"order_id":"...","status":null,...}`),
    same as `payment_status` already does on `GET .../orders/{id}` for the same situation. An
    integrator should check "is `status` present," not "is `status` null." First version of this
    slice collapsed the no-payment case into `ORDER_NOT_FOUND` —
    caught before commit: a `CREATED` order (real, owned by the caller; `ParentOrderCreationService`
    deliberately commits it before the gateway call so a failure there leaves it behind) is not the
    same as a nonexistent or another-partner's order, and reporting it identically to the
    cross-partner case would have actively misled the order's own owner. `getOrderDetail`'s
    `payment_status: null` handling was already doing the right thing for the same situation; this
    now matches it.
  - Verified end-to-end against real Postgres: `GET` on both a `PAYMENT_PENDING` order (fresh,
    created via `POST /api/v1/orders` for this test) and an existing `SUCCESS` order (2/2/0
    fulfillment summary); `GET .../payment` on both; `cancel` on the `PAYMENT_PENDING` order
    (200 → `CANCELLED`), then `cancel` again on the same now-cancelled order (409
    `ORDER_NOT_CANCELLABLE`) — plus the cross-partner 404s above. The cross-partner check seeded a
    second, real `partner`/`channel`/`api_client` row (`PPOB3`/`PPOB3-CHANNEL`/`ppob3-client`) into
    the same dev database used by every prior slice's cumulative seed data — it's now a permanent
    second tenant in that database, not a torn-down fixture; a later reader querying `partner`
    should expect two rows, not treat the second as accidental leftover.

- **Settlement ingestion hardening + Admin Web compensating retry** — two independent fixes to
  previously-flagged gaps (Section 34.1 / 37.1), done together as one slice.
  - **`/internal/settlement/ingest` is now gated behind admin auth**, not `permitAll` — same
    `SecurityConfig.adminFilterChain` as `/admin/**`. Section 37.1 still doesn't specify how a real
    settlement report arrives (file/API), so this covers "an authenticated admin triggers
    ingestion," not a real machine-to-machine ingestion contract.
  - **A duplicate settlement report for an already-ingested date now returns a clean `409
    SETTLEMENT_ALREADY_INGESTED`**, not a raw 500. `SettlementIngestionService.ingest` checks
    `SettlementRepository.existsBySettlementDate` proactively before inserting. The
    `settlement_date_uk` constraint stays as the backstop for the narrow concurrent-double-POST
    race between that check and the insert — a genuine race there still surfaces as a raw 500,
    deliberately: translating every `DataIntegrityViolationException` in this codebase to a
    client-facing "conflict" would misreport a real bug (e.g. a NOT NULL/FK violation elsewhere) as
    caller error, so this fix is scoped to the one named, understood constraint, not a blanket
    exception-handler change. Verified against real Postgres: ingesting `2026-09-20` twice returned
    `200` then `409`; ingesting a fresh date (`2026-09-21`) immediately after still returned a
    normal `200`.
  - **`POST /admin/child-orders/{id}/retry`** (new `AdminFulfillmentController` +
    `AdminChildOrderRetryOrchestrator` in `app`) closes "no Admin Web retry/compensation action" —
    resets a `FAILED` child order to `PENDING` (never resetting `attempt_count`; see
    `ChildOrder.resetForRetry`'s Javadoc for why) and re-dispatches it through the unchanged
    `FulfillmentExecutionService.dispatch`, then calls the new `ParentOrderTransitionService
    .completeRetry` to drive the PRD's `PARTIAL_FAILED -> SUCCESS` edge (Section 33.2) once every
    child order is `SUCCESS`.
    - Only retryable from `child_order.state == FAILED` with `parent_order.state ==
      PARTIAL_FAILED` — anything else is a clean `409 CHILD_ORDER_NOT_RETRYABLE`, not an
      `IllegalStateException` from the state machine.
    - `completeRetry` checks `state == SUCCESS` explicitly, not "not FAILED" —
      `ChildOrderState.COMPENSATED` exists in the enum but nothing sets it yet; if a future
      compensation action does, this predicate needs revisiting so a compensated child order
      neither blocks `SUCCESS` forever nor gets silently counted as if it had succeeded.
    - Verified end-to-end against real Postgres in both directions: retrying `child_order` 2
      (parent order 1, `PARTIAL_FAILED`) against the default stub config reached `SUCCESS`,
      correctly flipping the parent to `SUCCESS` too — `attempt_count` went from 1 to 2, and a
      *second*, distinct `provider_transaction` row (`child-2-attempt-2`) was created rather than
      colliding with the first attempt's key. Restarting with
      `PPOB2_FULFILLMENT_STUBPROVIDER_FAILPROVIDERSKUIDS=2` and retrying `child_order` 4 (same
      shape) reproduced a still-`FAILED` outcome with the parent correctly remaining
      `PARTIAL_FAILED` — proving the retry can genuinely fail, not just always succeed.
    - `AdminChildOrderRetryOrchestratorTest` (Mockito) covers every rejection branch the e2e runs
      didn't need to reach: child not `FAILED`, parent not `PARTIAL_FAILED`, `resetForRetry` losing
      a race, and the unresolvable-SKU/no-active-price dispatch path.

- **Gradle `api(...)` → `implementation(...)` for every inter-module project dependency** — closes
  the gap flagged since the fulfillment slice: `api(project(...))` re-exports a dependency's
  classes to every *transitive* consumer, silently permitting compile-time access beyond what
  Section 20.2's graph actually grants (e.g. `order` could already compile against `catalog`
  classes it has no documented edge to, because `decomposition` re-exposed them via `api`).
  Converted every `project(...)` dependency across all 16 non-`app` modules (`app` itself already
  used `implementation` throughout) in one pass, then verified empirically rather than reasoning it
  through by hand: `./gradlew clean compileJava compileTestJava` succeeded with zero errors on the
  first try, and the full test suite plus an app boot + a real API call afterward all still passed.
  A clean compile was the actual test of the hypothesis, not a hand-checked survey of which public
  signatures leak which types — "does the multi-module build still compile" is the only thing that
  actually determines whether an `api` edge is load-bearing, and the empirical answer here was that
  none of them were: this codebase's few genuine cross-module type leaks (e.g. `ChildOrderService
  .createForPattern` taking `decomposition`'s `PatternComponentDto`) are only ever called by code
  *inside the same module*, never by an external consumer relying on the transitive re-export.
  - Left external-library `api(...)` declarations (`spring-boot-starter-data-jpa`, etc.) untouched
    — Section 20.2's graph governs module-to-module edges, not third-party library visibility, and
    those weren't part of the flagged gap.
  - Several edges turned out to be entirely unused end to end, not merely over-visible: e.g.
    `order -> channel`, `order -> partner`, `order -> ledger`, `order -> audit`, `payment ->
    configuration`, `provider -> audit`, `reconciliation`'s edges to `ledger`/`settlement`/
    `payment`/`provider`, `routing -> provider`/`configuration`, `webhook -> audit`, `fulfillment ->
    audit`, and `configuration -> audit` — none referenced anywhere in that module's main or test
    source. Left declared (now as `implementation`, harmless either way) rather than removed: these
    match Section 20.2's *permitted* graph even where nothing has needed them yet, and a future
    slice using one is a one-line change, not evidence the current declaration was wrong. Worth
    revisiting if this list grows rather than shrinks.
  - The already-flagged ArchUnit check (package-level enforcement, e.g. "no `@Entity` outside a
    module's `domain` package") is still not built — this fix closes the module-level Gradle leak
    the same bullet named, not the separate package-level gap next to it.

- **Real `AyolinxPaymentGateway`** (`payment/gateway/ayolinx`) replaces the invented placeholder
  contract with one built against doc.ayolinx.id's actual public API reference (Generate/Query
  QRIS, Access Token B2B, Cancel QRIS, Payment Notify) — the docs are public and readable without
  registering; only the *credentials* to call the sandbox require merchant registration + KYB
  (see the chat history / ask again if this needs re-explaining). Selected via
  `ppob2.payment.gateway=ayolinx` (default `stub`) rather than a `prod` profile flip, so it can be
  booted and exercised — including watching it fail at the actual HTTP call — in any environment.
  - **`payment.pg_reference` is our own `orderNo`, not an Ayolinx-issued reference** — despite
    Section 22.17's column comment calling it an "Ayolinx transaction ref." Generate QRIS's
    response carries no Ayolinx-side reference at all (only `qrContent`/`partnerReferenceNo`/
    `expiredDate`), and both Query QRIS and Cancel QRIS key on `originalPartnerReferenceNo` — i.e.
    ours. See `AyolinxPaymentGateway`'s class Javadoc for the full reasoning.
  - **Three distinct signing schemes**, none of which match `HmacSigner` (kept untouched — it's
    HMAC-SHA256/hex/newline-delimited, shared by partner-facing Section 23.2 auth and the outbound
    webhook sender): the B2B access token request is RSA-SHA256 over `clientKey|timestamp` signed
    with our own private key; every other API call is HMAC-SHA512 over
    `METHOD:PATH:ACCESS_TOKEN:SHA256(BODY):TIMESTAMP` keyed by `client_secret`; inbound callback
    verification is RSA-SHA256 over `METHOD:ROUTE:SHA256(BODY):TIMESTAMP` verified against
    Ayolinx's public key. All three live in the new (package-private) `AyolinxSigner`, tested with
    known-answer/round-trip tests against a throwaway in-test RSA keypair — not a real credential.
  - **`AyolinxCallbackPayload` was rewritten** to the real nested QRIS-notify shape
    (`additionalInfo`/`amount` objects, `latestTransactionStatus` status codes `00`–`07`,
    `originalPartnerReferenceNo`/`originalReferenceNo`, no event-id field at all) and annotated
    `@JsonNaming(LowerCamelCaseStrategy)` — **without this override, the app's global
    `spring.jackson.property-naming-strategy: SNAKE_CASE` (set for our own partner-facing JSON)
    would make the shared `ObjectMapper` bean expect `original_partner_reference_no` instead of
    Ayolinx's real `originalPartnerReferenceNo`, silently failing to bind every field.** Caught and
    fixed before it shipped; same fix applied to the new `AyolinxWebhookController.NotifyResponse`
    for the same reason (Ayolinx expects `responseCode`/`responseMessage`, not the global
    `response_code`/`response_message`) — verified with a real curl round-trip, not just a test.
  - **Dedup key changed from a nonexistent `event_id` to `originalReferenceNo + ":" +
    latestTransactionStatus`.** Ayolinx's real callback body has no event-id field, and the same
    transaction legitimately produces multiple callbacks across a status progression (`01`
    Initiated → `02` Paying → `00` Success) — the old placeholder scheme (dedup by reference alone)
    would have let the first non-terminal callback burn the dedup slot and caused the real `00`
    terminal callback to be dropped as `DUPLICATE_IGNORED`, silently never marking the payment
    paid. `PaymentCallbackServiceTest.statusProgressionCallbacksAreAllProcessedAndTheFinalSuccessIsNotDroppedAsADuplicate`
    exercises exactly this sequence.
  - **Two real bugs found and fixed while building this, neither caught by a passing test until
    added deliberately**:
    1. The bean-selection guard for `StubQrisPaymentGateway` was first changed from
       `@Profile("!prod")` to `@ConditionalOnProperty(..., matchIfMissing = true)` alone, which
       reintroduced exactly the hazard the original guard existed to prevent: an unconfigured
       `prod` deployment (property absent for any reason) would silently fall through to loading
       the stub and issuing QR codes no customer can pay, rather than failing to start. Fixed by
       keeping **both** guards — `@Profile("!prod")` *and* `@ConditionalOnProperty(havingValue =
       "stub")`, no `matchIfMissing` — so a `prod` boot with no working gateway now fails loudly
       (no bean satisfies `PaymentGateway`) instead of degrading silently.
    2. `POST /internal/webhooks/ayolinx` with a syntactically-invalid-JSON body (not just
       Ayolinx-schema-invalid — actual garbage) returned a bare `500 INTERNAL_ERROR` instead of the
       documented `400`/`401` + JSON body, because `webhookEventRecorder.record(...)` tried to
       insert the raw non-JSON string into `webhook_event.payload`, a `json`-typed column, which
       Postgres rejects with `PSQLException`. This mattered specifically *because* of the
       always-return-a-body fix below: Ayolinx retries an unacknowledged/wrongly-shaped response up
       to 4× over 1h, so a malformed body used to guarantee the worst possible outcome (unparseable
       retries of something that can never succeed). Fixed with `PaymentCallbackService
       .recordablePayload(...)`, which wraps non-JSON input as a JSON string literal before
       recording it. Verified live: `curl -d 'not-json'` now returns `401` (no signature) with
       `{"responseCode":"4015600","responseMessage":"Unauthorized"}`, and a validly-*signed* garbage
       body returns `400`/`{"responseCode":"4005600","responseMessage":"Bad Request"}` — both
       previously 500'd.
  - **`refund()` stays `UnsupportedOperationException`, deliberately** — no refund endpoint exists
    in the public docs found. `qr-mpm-cancel` ("Cancel QRIS") voids an *unpaid* QR, which is a
    different operation from refunding a settled payment; mapping one onto the other would be a
    silent semantic lie. Status `04 Refunded` exists in the callback's own status table, so the
    mechanism exists on Ayolinx's side — just not as a documented callable endpoint.
  - **Unverified-against-a-real-sandbox assumptions — check these first once real credentials
    exist**, since none of them could be confirmed without one:
    - `amount.value` is formatted as `"<whole rupiah>.00"` (SNAP-convention decimal string) —
      `Money` itself is whole-Rupiah only (Section 21.1), so this is an assumption about Ayolinx's
      side, not ours.
    - The inbound callback signature's `ROUTE` component
      (`ppob2.payment.ayolinx.callback-route`, defaulting to Ayolinx's documented QRIS notify path
      `/v1/qr/qr-mpm-notify`) — neither "Callback description" nor the Payment Notify page state
      whether Ayolinx signs against their own documented path or against *our* registered callback
      URL's path when calling us.
    - The `4xx/5xx` response codes this app sends back to Ayolinx on rejection
      (`AyolinxWebhookController.responseCode`, e.g. `4015600`/`4005600`) are a placeholder
      following the one worked example's shape (`2005600`/"Successful") — the real non-success
      codes aren't published.
    - The `00`/`01`/`02`/`03`/`04`/`05`/`06`/`07` status-code table (Success/Initiated/Paying/
      Pending/Refunded/Canceled/Failed/Not found) came from a secondary "Callback description" doc
      page, not the primary QRIS Payment Notify schema page itself.
    - `X-EXTERNAL-ID` (documented "numeric, unique within the same day") uses the current epoch
      millisecond — satisfies both constraints cheaply but wasn't validated against Ayolinx.
    - `expiredDate` in Generate QRIS's response is documented only as "seconds timestamp," which
      is ambiguous against every other ISO-with-offset timestamp field in the same API — rather
      than guess a parse, this gateway echoes back the caller's own requested `expiresAt` instead
      (same as the stub already did).
  - Achievable without a sandbox credential, and done: known-answer signature tests
    (`AyolinxSignerTest`), real-shaped-JSON deserialization against the app's actual SNAKE_CASE
    global `ObjectMapper` config (`AyolinxCallbackPayloadTest`), the full callback service including
    the status-progression dedup scenario (`PaymentCallbackServiceTest`), graceful-failure behavior
    against an unreachable host (`AyolinxPaymentGatewayTest`), single-`PaymentGateway`-bean
    resolution on boot in both `gateway=stub` (default) and `gateway=ayolinx` modes, and a full live
    round-trip against real Postgres in stub mode (a real-shaped, correctly-signed SUCCESS callback
    correctly marked a `PENDING` payment `SUCCESS`, posted exactly one `PAYMENT`/`CREDIT` ledger
    entry, and a byte-identical resend was correctly ignored as a duplicate with no second row of
    either kind). At the time this slice shipped: **not achievable and not claimed: any actual
    round-trip against Ayolinx's sandbox** — merchant registration was assumed to require KYB.
    **Superseded below** — sandbox registration turned out to be plain self-service signup, no KYB
    gate, and the real round-trip has since been done.

- **Real sandbox round-trip against `sandbox.ayolinx.id` — the "not achievable without KYB" gap
  above is closed.** Merchant sandbox registration at `merchant.ayolinx.id` needed no KYB at all —
  self-service signup straight into a "Demo Mode" account, immediately capable of registering
  integration material. This produced the first genuine end-to-end confirmation of the whole
  outbound path built blind from public docs above.
  - **Ayolinx's onboarding flow itself confirms the three-signing-scheme design was read
    correctly**: the portal's "Integration Material" form asks the merchant to submit their own
    RSA public key (pasted as `-----BEGIN PUBLIC KEY-----` PEM — generated locally via `openssl
    genpkey`/`openssl rsa -pubout`, private key never leaving the local machine), and in exchange
    auto-issues a `Customer No` (`28798` in this run) and Ayolinx's own RSA public key (for
    verifying inbound callbacks) — independently matching `AyolinxSigner`'s already-built
    `signAccessTokenRequest` (signed with the merchant's own private key) and `verifyCallback`
    (verified against Ayolinx's public key) without either side having been designed by looking at
    the other.
  - **`client-key`/`client-secret` come from a separate "API Key Sandbox" panel**, revealed only
    after an emailed-OTP step (`SCf7598...`/`SS4d6cb...`-shaped values in this run) — these feed
    `AyolinxTokenService`'s `client-key` and `AyolinxSigner.signApiRequest`'s HMAC-SHA512 key,
    confirming that scheme's shape too, distinct from the RSA-keyed token request.
  - **`POST /api/v1/orders` (Section 23.4), with `ppob2.payment.gateway=ayolinx` and all five
    `ppob2.payment.ayolinx.*` credential properties pointed at the real sandbox, produced `201
    CREATED`** with a `qr_payload` that decodes as a genuine EMV/QRIS string carrying real BNC
    acquirer data (`ID.CO.BANKNEOCOMMERCE.WWW`, merchant name, city, the exact requested
    `parent_amount`) — not the stub's synthetic payload. Zero `WARN`/`ERROR` log lines across the
    whole call: the RSA-SHA256 B2B token request and the HMAC-SHA512 `qr-mpm-generate` request both
    succeeded on the first attempt against a freshly-registered sandbox identity. `payment.pg_reference`
    persisted as our own `orderNo`, confirming the "no Ayolinx-side reference in the response" reading
    of the docs (see the reference-identity note above) against a real response, not just the docs.
  - **Inbound callback path: round-tripped, and the exact gap is now narrowed.** The QRIS channel's
    `Callback URL` was set (via the portal's per-channel-product configuration under "Payment
    Channel" / "Integrations") to this app's `/internal/webhooks/ayolinx`, tunneled to the public
    internet via a `cloudflared` quick tunnel and confirmed reachable (`/actuator/health` returned
    `200` through the tunnel). Ayolinx's sandbox "Demo Mode" auto-completes an issued dynamic QR as
    paid after a short delay (no real wallet/scan needed) and **did deliver a genuine inbound
    callback** to that URL a few minutes later — `webhook_event` recorded it (`direction=INBOUND`,
    `source=AYOLINX`) with a real, non-synthetic payload: `latestTransactionStatus="00"`,
    `originalPartnerReferenceNo` matching our `orderNo`, `originalReferenceNo` matching the portal's
    own transaction id, and an `additionalInfo` block carrying real-shaped fields (`RRN`, `channel`,
    `paymentNtb`, `settleDate`, plus a few not modeled in `AyolinxCallbackPayload.AdditionalInfo` —
    harmless, since `@JsonIgnoreProperties(ignoreUnknown = true)` already covers that). This
    confirms the callback payload shape assumed from public docs is correct, and that the tunnel +
    routing + JSON deserialization path all work end-to-end against a real Ayolinx-originated
    request — previously a completely untested leg.
    **Signature verification initially rejected it** (`SIGNATURE_INVALID`, HTTP 401,
    `webhook_event.status=FAILED`). This was *not* a missing-key problem — the portal-issued
    `PPOB2_AYOLINX_PUBLIC_KEY_PEM` (Ayolinx's own public key, captured at registration, see above)
    was confirmed loaded and is definitionally the correct key for this purpose. A control
    experiment — signing a same-shaped callback body with our own keypair as a stand-in and posting
    it to the same endpoint after a clean app restart — also failed verification, which ruled out a
    stale-process/config-loading issue and isolated the mismatch to the **`ROUTE` component of the
    signed string** (`METHOD:ROUTE:SHA256_HEX(BODY):TIMESTAMP`, `AyolinxSigner.verifyCallback`):
    `callback-route` used to default to the *documented* `/v1/qr/qr-mpm-notify` per Section 25.2,
    which was never confirmed to be the exact literal string Ayolinx signs against for a callback
    sent to a *merchant-supplied* URL rather than a fixed Ayolinx-hosted path.

    **Root-caused and fixed the same day.** `PaymentCallbackService` was given a one-line debug log
    of the real `X-SIGNATURE`/`X-TIMESTAMP` headers on rejection (they weren't captured anywhere
    before), and a temporary probe in `AyolinxPaymentGateway.verifyCallbackSignature` replayed that
    *same real signature* against a short list of candidate route strings using the actual
    `AyolinxSigner.verifyCallback` — no guessing, no re-derivation, just asking "does this literal
    string verify". The very first real rejected callback answered it: **`/internal/webhooks/ayolinx`
    — this app's own registered callback path — is what Ayolinx signs against**, not any
    Ayolinx-hosted doc path. `callback-route`'s default was corrected accordingly (`application.yml`),
    the probe removed (it did its job), and the Javadocs updated to state this as confirmed rather
    than assumed.

    **Verified fixed, twice, for real**: after restarting with the corrected default, using the
    Ayolinx sandbox portal's own "Transaction Simulator" ("Mark success" button on a pending
    transaction) to trigger a fresh callback for `ORD-20260914-000004` produced
    `webhook_event.status=RECEIVED` (not `FAILED`), `payment.status=SUCCESS`, `paid_at` populated,
    and a `PAYMENT` ledger `CREDIT` entry posted — the full inbound path, for the first time, working
    end-to-end. Separately, and unprompted, **Ayolinx's own webhook retry mechanism** (per
    doc.ayolinx.id/api-299374923, redelivery on a non-2xx response) redelivered the *earlier* failed
    callback for `ORD-20260914-000003` a few seconds later, and it now succeeded too — confirming the
    fix without any manual replay on that one. Both orders landed in `REFUND_PENDING` rather than
    fully fulfilled, which is the *correct* PRD behavior here, not a new bug: `E2E_PRODUCT` (the test
    fixture SKU) has no real `decomposition_pattern` rows, so `ParentOrderTransitionService` logged
    "No eligible decomposition pattern ... BR-DEC exhaustion, routing to REFUND_PENDING" — exactly
    `TC-BE-018`'s expected result, now also confirmed for real as a side effect of this run.
  - **Credential handling**: the RSA keypair was generated locally and only the public half was
    ever transmitted anywhere (to the Ayolinx portal, itself not a secret by definition); the
    `client-key`/`client-secret` and the generated private key were placed directly into local
    process environment variables for this run, never committed to the repository or written
    anywhere under version control.

- **PRD Section 52 (`TC-BE-*`) order-creation test matrix, run for real against the sandbox** —
  Section 52 lists positive and negative test cases for order creation and payment; the ones that
  don't require a real inbound callback were driven end-to-end against `POST /api/v1/orders` with
  `ppob2.payment.gateway=ayolinx` and live sandbox credentials (not mocked/stubbed), all matching
  the PRD's expected result exactly:

  | Test Case | Result |
  |---|---|
  | TC-BE-001/006 — create order, supported amount | `201`, `PAYMENT_PENDING`, real QRIS payload |
  | TC-BE-004 — duplicate request, same Idempotency-Key + same body | Same `order_id` returned, `201` both times |
  | TC-BE-005 — idempotency conflict, same key + different body | `409 IDEMPOTENCY_KEY_CONFLICT` |
  | TC-BE-002 — unsupported amount | `422 UNSUPPORTED_AMOUNT` |
  | TC-BE-003 — amount above QRIS ceiling | `422 UNSUPPORTED_AMOUNT` ("exceeds the QRIS transaction ceiling") |

  `TC-BE-008` (payment success callback → `payment` row `SUCCESS`, ledger entry posted) is now also
  confirmed for real, for the same reason as the bullet above (the `callback-route` fix unblocked
  it) — see that entry for the full account, including `TC-BE-018`'s incidental real confirmation.
  `TC-BE-010` (duplicate callback) and `TC-BE-012` (invalid signature) are now confirmed too:

  | Test Case | Result |
  |---|---|
  | TC-BE-012 — garbage `X-SIGNATURE` | `401`, `webhook_event.status=FAILED`, no payment/order state change |
  | TC-BE-010 — exact replay of a real, validly-signed callback | `200` (signature still verifies — same body/timestamp/signature triple), but `payment_event`'s `dedup_key` unique constraint held: only 1 row for that key exists, `payment.paid_at` unchanged, not reprocessed |

  `TC-BE-010`'s replay needed the *exact* original bytes Ayolinx sent (a byte-for-byte difference
  would fail signature verification, not exercise dedup logic at all) — `webhook_event.payload` is
  `jsonb`, which reformats/reorders on storage, so a one-line temporary log of the raw body
  (alongside the existing header log, removed again immediately after use) captured it verbatim from
  a real "Mark success"-triggered callback for replay.

  **`TC-BE-009` (failed payment callback) confirmed too, via a substitute-key HTTP round-trip** —
  the sandbox's "Mark success"/"Check Transaction" simulator has no equivalent for a failed payment
  (there is no genuine way to make Ayolinx sign a `latestTransactionStatus=06` body without an
  actual failed real-money transaction, which Demo Mode doesn't produce), so this couldn't be tested
  with Ayolinx's real signing key. Instead, `ppob2.payment.ayolinx.public-key-pem` was temporarily
  pointed at *our own* generated test public key (private half already held locally — see the RSA
  keypair from the "Real sandbox round-trip" entry), a `06`-status callback body was signed with the
  matching private key using the same `AyolinxSigner.verifyCallback` scheme
  (`METHOD:ROUTE:SHA256_HEX(BODY):TIMESTAMP`, confirmed `ROUTE`), and posted to the real
  `/internal/webhooks/ayolinx` endpoint against a real order. Result: `payment.status=FAILED`,
  `parent_order.state` stayed `PAYMENT_PENDING` (matching `PaymentCallbackService`'s own comment —
  no `PAYMENT_PENDING → FAILED` order transition is defined; the order is left to expire naturally),
  and no ledger entry was posted — exactly `TC-BE-009`'s expected result. This exercises the real
  HTTP/Jackson/JPA path end-to-end, short of Ayolinx's own signature (unavoidable without a genuine
  failed transaction); the app was restarted back onto Ayolinx's real public key immediately after.
  **`TC-BE-011` (stale-timestamp replay) — tested, found broken, and fixed.** Section 25.2 calls
  for "Replay protection: Timestamp window + nonce/dedup_key, same pattern as Section 23.2" for the
  inbound callback path, but `AyolinxSigner.verifyCallback`/
  `AyolinxPaymentGateway.verifyCallbackSignature` only checked that the signature was valid for
  whatever `X-TIMESTAMP` the caller supplied — there was no check anywhere in this path that the
  timestamp was *recent*. Proven with the same substitute-key technique as `TC-BE-009` above: a
  callback body signed with a genuinely 1-hour-old `X-TIMESTAMP` (the signature itself was valid —
  computed over that exact timestamp string) was accepted and fully processed:
  `payment.status=SUCCESS`, a `PAYMENT` ledger `CREDIT` entry posted, identical to a fresh callback.

  **Fixed**: `AyolinxPaymentGateway.verifyCallbackSignature` now rejects a callback whose
  `X-TIMESTAMP` drifts more than `CALLBACK_TIMESTAMP_WINDOW` (1 hour) from the server's clock,
  mirroring `HmacAuthenticationFilter#checkTimestampWindow`'s existing pattern for Section 23.2's
  outbound-facing partner API — adapted from epoch-millis to Ayolinx's ISO-8601-with-offset
  `X-TIMESTAMP` format. The window is deliberately generous (not the outbound filter's 5 minutes):
  doc.ayolinx.id/api-299374923 documents callback redelivery "up to 4x over 1h" on a non-2xx
  response, and this app's own retry-redelivery was independently observed for real (see the
  `TC-BE-008` entry above) — too tight a window would reject Ayolinx's own legitimate retries, not
  just genuine replay attempts. A malformed timestamp fails closed (treated as out-of-window).
  Re-ran the exact same attack against the fix: same 1-hour-old signed body, same endpoint, same
  real order — now correctly rejected (`401`, `webhook_event.status=FAILED`,
  `payment.status` stayed `PENDING`, no ledger entry). Covered by a new unit test
  (`verifyCallbackSignatureRejectsAValidlySignedButStaleTimestamp`); the existing
  `verifyCallbackSignatureAcceptsAGenuineAyolinxSignedCallback` test's fixed historical timestamp
  was switched to a dynamic "now" value since it would otherwise now fail this same check for an
  unrelated reason.

  **`TC-BE-007` (QR expiry sweep) is now confirmed too, two ways.** Organically: several orders in
  this run (`ORD-...-000001`, `...-000002`) simply reached their real 15-minute `QRIS_EXPIRY` TTL
  over the course of testing and were picked up by `QrExpirySweepJob`'s next 60-second tick with no
  intervention — visible in the app log as `"QR expiry sweep: expiring N parent_order(s) past their
  QR TTL"`. Deliberately, for a fast and precisely-timed repro: a fresh order
  (`ORD-20260914-000011`) was created, then its `parent_order.expires_at` backdated by one minute
  directly in Postgres (a standard TTL-testing technique — it exercises the exact same
  `findByStateAndExpiresAtBefore` query and `expirePaymentPending` transition the real 15-minute
  wait would, just without waiting 15 real minutes for it) — the very next sweep tick (within ~10s)
  transitioned it to `parent_order.state=EXPIRED` **and** `payment.status=EXPIRED`, matching this
  test case's expected result exactly. `TC-BE-033/034` (settlement allocation) was already covered
  by `SettlementAllocationServiceTest` plus the real-Postgres run documented above.

- **Full test cycle, 2026-09-27 — every remaining `TC-BE-*` / `TC-ADM-*` case run for real, nine
  pre-existing defects found and fixed, plus four defects in those fixes caught on review.** Run
  against a wiped Postgres volume (all 20 migrations re-applied from zero, clean), the app booted on
  the default profile, `./gradlew build` green throughout. The end-to-end scripts this produced
  (`scripts/e2e/`) finish 75/75 on a clean slate.

  **Newly confirmed PASS this run** (real signed HTTP + `psql` row inspection, not reasoning):

  | TC | Evidence |
  |---|---|
  | `TC-BE-013` | New `timeout-provider-sku-ids` knob: 3 attempt log lines (`attempt 1/3`, `2/3`, `3/3`), **exactly one** `provider_transaction` row (`child-2-attempt-1`, status `TIMEOUT`), child `FAILED` after exhaustion, parent `FAILED`, **no** PROVIDER ledger debit |
  | `TC-BE-014` | Child on the injected-failure SKU → `FAILED` with `attempt_count=1`. Not a missing retry: `FAILED` is the non-retryable class per Section 27.2, and only `TIMEOUT` is retried — the exhaustion half is `TC-BE-013` above |
  | `TC-BE-015` | Two levels. DB: a duplicate `(provider_id, idempotency_key)` insert is rejected by `provider_transaction_idem_uk`. App: two **concurrent** admin retries on the same child order produced exactly one new row (`child-4-attempt-2`) and `attempt_count` 1→2, never 3 — `markExecuting`'s guard held |
  | `TC-BE-016` | `40000 -> [A ok, B fail]` → parent `PARTIAL_FAILED`, never silently `SUCCESS` |
  | `TC-BE-017` | `20000 -> [A]` → parent `SUCCESS`, `provider_transaction` `SUCCESS` |
  | `TC-BE-020` | SKU quota exhausted (`quota_daily=2` vs `daily_usage=4`): the higher-scored pattern (99) became ineligible and routing picked the alternate (50) |
  | `TC-BE-021` | `pattern_economics.eligible=false` + negative `net_contribution`: excluded, alternate selected |
  | `TC-BE-023` | SKU `INACTIVE` → every pattern containing it excluded **at routing**. The Section 30.3 *invalidation* half is still a gap — see below |
  | `TC-BE-028` | `ORDER_VS_FULFILLMENT` rows opened automatically with correct values (`20000` vs `0`; `40000` vs `20000` for the partial), status `OPEN` |
  | `TC-BE-029` / `TC-BE-030` | `200` + `CANCELLED` from `PAYMENT_PENDING`; `409 ORDER_NOT_CANCELLABLE` from `SUCCESS`, state unchanged |
  | Section 34.1 | Ambiguous purchase → `inquire()` confirmed success → child `SUCCESS` via `resolveAmbiguous`, `purchase()` never called twice |

  **Bugs found and fixed.** Each was reproduced first, fixed, then re-reproduced. Numbered 1–7 here;
  two more (`04`/`05` terminal statuses, and the missing `private-key-pem` check) are in the review
  pass below, which also corrected four defects in these very fixes:

  1. **Every framework-level rejection returned `500 INTERNAL_ERROR`** with a full stack trace.
     An unknown path, a POST-only route called with `GET`, a malformed JSON body and a missing
     `Idempotency-Key` header **all** returned `500`. Only application-thrown `ApiException`s
     mapped correctly. This is a partner-facing correctness bug, not cosmetics: Section 23.1 tells
     partners to retry on `5xx`, so a permanently-failing request was inviting an infinite retry
     loop, and every scanner hitting an unknown URL logged an `ERROR` that buries real incidents.
     Fixed in `GlobalExceptionHandler` (`NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405,
     `VALIDATION_ERROR` 400). Re-verified: all six cases now return the correct status in the
     Section 50.1 envelope.
  2. **`TC-BE-032`'s anomaly was completely silent.** A `FAILED` callback arriving after terminal
     `SUCCESS`: `Payment.markFailed()` correctly returns `false` and the `SUCCESS` was never
     overwritten (that guard was always right) — but `PaymentCallbackService` **discarded the
     return value**, so there was no log line, no record, and a `200 Successful` acknowledgement.
     Section 25.2 requires "logged and flagged for manual review".
  3. **`TC-BE-031`'s reconciliation record did not exist.** A late `SUCCESS` callback after
     `EXPIRED` correctly did not auto-fulfil, but only logged a WARN whose own text said
     *"needs a reconciliation record once that module exists"* — a stale claim; the
     `reconciliation` module has existed since the reconciliation slice.
     **The `CANCELLED` variant was materially worse and had never been tested**: the payment DID
     reach `SUCCESS` and a full `CREDIT` was posted to the payment ledger (funds collected), the
     order stayed `CANCELLED` with zero child orders (nothing delivered), and nothing was recorded
     anywhere — so no Admin Web view would ever have surfaced that a customer paid and got nothing.
  4. **A callback could claim any amount and be believed.** `processCallback` never compared the
     callback's own amount with `payment.amount`. Reproduced: a validly-signed `SUCCESS` callback
     claiming `"1.00"` against a 20,000 order marked the payment `SUCCESS`, posted a **20,000**
     `CREDIT`, and fulfilled **20,000** of goods. Now a *parsed* amount that disagrees is not applied
     at all (shipping goods against an amount the PG never says it collected is the one outcome an
     operator cannot undo) and opens a discrepancy instead. An amount this code cannot parse is
     handled differently and deliberately so — see the amount-guard rebalancing below, which reviewed
     and corrected this fix.

     Bugs 2–4 are fixed together: `payment` publishes `PaymentCallbackAnomalyEvent` and `order`
     publishes `LatePaymentOnUntransitionableOrderEvent` (Section 20.2 grants neither module an
     edge to `reconciliation`), and `PaymentCallbackAnomalyOrchestrator` in `app` opens the record —
     the same composition-root shape as `OrderFulfillmentReconciliationOrchestrator`. This is also
     the **first** writer of `ReconciliationType.PAYMENT_VS_PG`, which this README previously
     listed as unwired. Re-verified, each row read back out of Postgres:

     | Case | Record opened (values as finally recorded — see the discrepancy-semantics fix below) |
     |---|---|
     | Amount mismatch (20000 billed, 1 reported) | `PAYMENT_VS_PG`, expected 20000, actual 1, **discrepancy −19999**, `OPEN`; payment stayed `PENDING`, zero ledger rows, zero child orders |
     | `04`/`05`/`06` after a terminal `SUCCESS` | `PAYMENT_VS_PG`, `TERMINAL_STATUS_AFTER_SUCCESS`, expected 20000, actual 0, **discrepancy −20000**; payment stayed `SUCCESS` with `paid_at` unchanged |
     | `SUCCESS` after `EXPIRED` | `PAYMENT_VS_PG`, `SUCCESS_ON_TERMINAL_PAYMENT`, expected 0, actual 20000, **discrepancy +20000** (a surplus: funds collected against an order we abandoned) |
     | Unparseable amount | `PAYMENT_VS_PG`, `AMOUNT_UNVERIFIED`, expected 20000, actual 20000, discrepancy 0 — the payment *was* applied in full; the record says only that the check was skipped |
     | `SUCCESS` on a `CANCELLED` order | `ORDER_VS_FULFILLMENT`, expected 20000, actual 0, **discrepancy −20000**, `OPEN` |

     All four still answer `200` to Ayolinx deliberately — it redelivers anything it considers
     unacknowledged, and redelivering a mismatched callback would not make it match. Idempotency
     comes from `payment_event.dedup_key`, which stops a redelivery before it reaches these
     branches, so the payment-level listener commits in the callback's own transaction; the
     order-level one needs `REQUIRES_NEW` for the reason `markPaid`'s Javadoc documents at length
     (it runs in an `AFTER_COMMIT` call tree) — verified by reading the row back from Postgres,
     not by a passing unit test.
  5. **`TC-BE-027` failed on three counts.** With Postgres stopped under a running app, a signed
     `POST /orders` hung for the **full 30s** (Hikari's default `connectionTimeout`) and then
     returned `500` — with Spring Boot's **default** error body, not the Section 50.1 envelope, so
     a partner parsing `error_code` got nothing. `/actuator/health` hung past 20s. Fixed:
     `connection-timeout: 5000`, a new `SERVICE_UNAVAILABLE` (503) code, and — critically — a
     `DataAccessException` catch **inside `HmacAuthenticationFilter`**, because that filter's
     `api_client` lookup is the first datastore touch of every request and fails *before*
     `DispatcherServlet`, where no `@RestControllerAdvice` can see it. Re-verified: **6s**, `503
     SERVICE_UNAVAILABLE` in the proper envelope, health `DOWN`/503 in 5s, recovery without an app
     restart, and **zero** orphan/partial rows. The "no partial writes" half always passed.
  6. **A misconfigured real gateway booted healthy.** Every `ayolinx.*` credential defaults to
     empty (it must — the stub is the default), so `ppob2.payment.gateway=ayolinx` with no secrets
     started up fine and failed per-request instead, as a customer-visible order error rather than
     an alert. Worst of these is a blank `notification-url`: the Generate QRIS call then registers
     no NOTIFICATION `urlParams` at all, so Ayolinx never calls back and **no payment is ever
     confirmed**. Added a `@PostConstruct` check (not a constructor check — `AyolinxPaymentGatewayTest`
     deliberately builds a blank-config instance to assert `verifyCallbackSignature` fails closed,
     and validating in the constructor deleted that test's ability to exist; six tests went red on
     the first attempt). Verified: the context now fails with the missing property names listed.
  7. **`ppob2.security.signature-window-ms` was dead config.** Declared in `application.yml` as
     "5 minutes, PRD Section 23.2" and read by nothing — `HmacAuthenticationFilter` used a
     hardcoded constant, so the property looked adjustable and silently was not. Now bound.

  **Reviewing the above found four more things, all fixed in the same pass:**

  - **The out-of-order fix initially covered only `06`.** `AyolinxCallbackPayload`'s own status table
    treats `04 Refunded` and `05 Canceled` as terminal too, and both were broken differently: `05`
    only logged, and `04` was in **no status bucket at all**, so a refund landing on a payment whose
    goods this platform had already delivered fell through to the "Unrecognized status" WARN and was
    answered `200` with nothing recorded — the same silent class as the original bug. The check is now
    written once over `isTerminalNonSuccess()` rather than per status code, because enumerating status
    codes one at a time is exactly what produced three differently-broken branches. The anomaly kind
    is `TERMINAL_STATUS_AFTER_SUCCESS` (was `FAILED_AFTER_SUCCESS`), and a unit test now loops over
    all three codes.
  - **The amount guard was rebalanced, because its own premise is unverified.** Every amount check run
    on 2026-09-27 used callbacks this repo's own harness generated, so they agreed by construction.
    The **inbound** amount format has never actually been captured: a genuine sandbox callback was
    received (see the "Inbound callback path" entry above) but its `amount` field was not recorded
    against an order with a known billed amount. A strict guard on an unproven format would turn one
    wrong assumption into a **total payment outage** — every real payment stuck `PENDING`. So the two
    cases are now separated:

    | Callback amount | Behaviour | Why |
    |---|---|---|
    | parses, disagrees with `payment.amount` | payment **not** applied, `AMOUNT_MISMATCH` opened | a disagreement between two parsed numbers is a confident finding about money |
    | parses, agrees | applied normally | — |
    | absent or unparseable | payment **is** applied, `AMOUNT_UNVERIFIED` opened | "this code could not parse the field" is an admission about *our* assumption, not a finding about the money; a human is told rather than the payment refused |

    Grouping separators are stripped rather than rejected for the same reason. Sub-rupiah values are
    still treated as unparseable, never rounded — IDR has no sub-rupiah denomination, so a fractional
    value means the field does not mean what this code assumes. **Before go-live, capture a real
    callback's `amount` for an order with a known billed amount and pin it in
    `AyolinxCallbackPayloadTest`**; until then the inbound format is assumed, and that test says so.
  - **The startup validation missed `private-key-pem`.** It checked the four properties
    `AyolinxPaymentGateway` itself reads, but `AyolinxTokenService` needs the private key to sign the
    B2B access-token request — so a deployment with the other four set and the private key blank would
    still have booted "healthy" and failed every order. Same `@PostConstruct` check added there.
  - **`dev-seed.sql` was shipping inside the production bootJar.** It was written to
    `app/src/main/resources/db/seed/`, which put a `SUPER_ADMIN` row with a known password hash on the
    deployable artifact's classpath. Flyway would not have executed it (`locations` is
    `classpath:db/migration`), but "one config property away" is not a boundary worth having. Moved to
    `scripts/e2e/dev-seed.sql`; verified absent from `app-0.1.0-SNAPSHOT.jar`.

  **A second review pass found four defects in the round-2 code itself** — all four invisible to the
  end-to-end suite, for one instructive reason: the harness asserted `count(*) = 1` on the
  reconciliation table and never once looked at what the row actually *said*. Asserting that a record
  exists is not asserting that it is legible.

  - **The two most dangerous anomalies were being recorded with a discrepancy of `0`.**
    `reconciliation` has no reason/notes column, so `expected`, `actual` and the derived `discrepancy`
    are the *only* things an operator sees in Admin Web — the anomaly `Kind` survives only in a log
    line. Recording `expected = payment.amount, actual = reportedAmount` for every kind meant a
    `SUCCESS` callback on an `EXPIRED` payment (funds collected for an abandoned order) and a refund
    landing after goods were delivered **both showed 20000/20000 → discrepancy 0**, which reads as
    noise and gets resolved unread. The columns now mean "collected per our payment row" vs "collected
    per the PG", so a surplus reads `+20000` and a reversal reads `−20000`; `TC-BE-028` asks for the
    *correct* discrepancy value, and it now gets one. Pinned by
    `PaymentCallbackAnomalyOrchestratorTest` and by discrepancy assertions in the e2e scripts.
  - **`hasOpenDiscrepancy` on the payment-level listener suppressed real reversals.** Redeliveries are
    already stopped upstream by `payment_event.dedup_key`, so the guard bought nothing for its stated
    purpose — while actively swallowing the case that matters most: in the degraded mode
    `AMOUNT_UNVERIFIED` exists for (the format assumption turns out wrong, so *every* payment opens a
    record), a later `04`/`05`/`06` for that payment would have been dropped with an `INFO` line.
    Removed from the payment-level path; kept on the order-level one, where repeated late callbacks for
    one order genuinely are the same finding.
  - **Tolerating grouping separators contradicted the rule it was added under.** `.replace(",", "")`
    turns `"20.000,00"` — the pt/id/de convention, dot grouping and comma decimal — into `"20.000"`
    into **`Money 20`**. Not `null`: a *confident wrong number*, which then trips `AMOUNT_MISMATCH` and
    refuses a correctly paid 20,000 order. Being lenient about an unverified format is precisely how
    you end up blocking money on a guess. Only two unambiguous shapes are accepted now
    (`1234[.00]` and `1,234,567[.00]`); everything else returns `null` and routes to
    `AMOUNT_UNVERIFIED`.
  - **A `06` on an `EXPIRED`/`FAILED` payment opened a `TERMINAL_STATUS_AFTER_SUCCESS` record.** The
    inline comment said "terminal but NOT SUCCESS" and the code then published the after-success kind.
    Neither side collected money in that case — it is just a failed attempt on a lapsed QR — so it
    would have produced one meaningless `OPEN` row per expired order and taught operators to ignore the
    whole reconciliation type. Now logged at `INFO` and acknowledged, with no record. `TC-BE-009` was
    re-verified end-to-end after this restructuring, since its only prior e2e evidence predated it.

  **Also verified, since `TC-BE-027` had only been tested on `/api/v1/**`:** a callback posted to
  `/internal/webhooks/ayolinx` during a database outage returns **`503` in ~6s**, not a `200`
  acknowledgement — so Ayolinx redelivers rather than the confirmation being silently lost. (Minor
  known inconsistency: that 503 carries this codebase's `ErrorResponse` envelope, not Ayolinx's
  `{responseCode, responseMessage}` shape. It is still a JSON body on a non-2xx, which is what
  triggers redelivery.)

  Also fixed, smaller: the retry loop logged `"…timed out, retrying"` on its *final* attempt too,
  telling an operator mid-incident to wait for an attempt that would never come. Spotted only
  because `TC-BE-013` finally exercised that loop end-to-end.

  **Regression re-run after the callback-service changes**, all still passing: `TC-BE-002`
  (422), `TC-BE-003` (422, ceiling), `TC-BE-004` (same `order_id` twice), `TC-BE-005` (409),
  `TC-BE-009` (payment `FAILED`, no fulfilment, no ledger), `TC-BE-010` (replay: `paid_at`
  unchanged, exactly 1 `payment_event` for the dedup key, 1 child order, 1 ledger row),
  `TC-BE-012` (401, `webhook_event` `FAILED`), `TC-BE-016`/`017`, `TC-ADM-003`/`013`/`015`.

  **Still not implemented** — features, not bugs, and each is a go-live scope decision, not
  something this run could close:
  - `TC-BE-019` **pattern-level** quota: `RoutingService` enforces SKU quota only; nothing reads
    `pattern_usage` for eligibility, and `decomposition_pattern` has no quota column.
  - `TC-BE-023`'s Section 30.3 **invalidation**: `structural_status` is only ever *read* (filtered
    to `'VALID'`); nothing in the codebase ever writes it. Confirmed: a pattern whose only SKU is
    `INACTIVE` stayed `VALID`. Runtime routing does exclude it, so the effect is covered while the
    mechanism is not.
  - `TC-BE-024` / `TC-BE-025` (generation validation failure, rollback) and `TC-PROP-001..005`:
    all require the Rust offline pattern-generation engine, which does not exist.
  - `TC-BE-026` (Redis down): **nothing is Redis-backed** — `NonceStore` is an in-memory map and
    pattern lookup goes straight to Postgres. The test case is vacuous as written, but the reason
    it is vacuous is itself the finding: replay protection and the expiry sweep have no
    cross-instance coordination, so this is **single-instance-only** until Redis lands.
  - `TC-ADM-006..011` (transaction search, parent/child drilldown, pricing update, configuration
    update, pattern viewing, generation trigger): no implementation to test.

- **`TC-ADM-002` / `TC-ADM-005` / `TC-ADM-012` — the three Admin Web gaps this README has flagged
  twice are now closed and re-verified** (2026-09-27). All three were "specified, half-built":
  the blocking always worked, the recording did not.

  | TC | Before | After |
  |---|---|---|
  | `TC-ADM-002` | `401` correctly, `audit_log` **empty** — no authentication-event listener existed at all | `401` + `ADMIN_LOGIN_FAILED` row: attempted username, `BadCredentialsException`, client IP. `actor_id` is null, not invented — by definition the credentials did not resolve to an account |
  | `TC-ADM-005` | `403` correctly, `audit_log` **empty** — `AuditService` was only ever called on success paths | `403 PERMISSION_DENIED` + `ADMIN_PERMISSION_DENIED` row with the **real** `actor_id` (the admin is authenticated; only unauthorized), method and path |
  | `TC-ADM-012` | permission enforced, but the endpoint took **no body**, so `audit_log` recorded *that* a retry happened and by whom, never *why* | `reason` required and validated before any state change: `400 VALIDATION_ERROR` when absent **or blank** (accepting blank would satisfy the signature and not the control), and recorded in `audit_log.after_state` |

  `AdminSecurityAuditListener` listens to the abstract `AbstractAuthenticationFailureEvent`, not
  just `AuthenticationFailureBadCredentialsEvent`: a disabled/locked `admin_user` raises a
  different subclass and is just as security-relevant, and the original gap was partly a failure to
  enumerate cases. The submitted password is never recorded, not even on failure — a mistyped
  password is frequently another account's correct one. Auditing a denial can never turn a clean
  `403` into a `500`: a recording failure is logged and swallowed.

  Known limit, stated rather than implied: the denial audit covers `@PreAuthorize` denials (which
  is `TC-ADM-005`'s exact case) because those reach `DispatcherServlet`'s exception resolution. A
  URL-level denial thrown by `AuthorizationFilter` before the servlet is entered reaches no
  `@RestControllerAdvice` and is **not** audited. `audit_log` after the run held exactly one row
  per action across both success and denial paths — the 1:1 ratio the risk register asked for,
  previously 0 on the denied side.

- **PRD Section 54 (`TC-ADM-*`) Admin Web test matrix, run for real** — this codebase has no
  browser-based Admin Web (Section 54's own "Method" column names Playwright/Cypress; none exists
  here), so "Admin Web" in practice was (until 2026-10-22: sessions + MFA, then a browser console) real HTTP Basic Auth against the actual `/admin/**` REST
  endpoints and RBAC (`SecurityConfig.adminFilterChain`, Section 42's permission model) — the same
  adaptation this README's own "Running locally" section already documents for exercising Admin Web
  manually. Seeded four throwaway `admin_user` rows (`e2e_viewer`/VIEWER, `e2e_ops`/OPERATIONS,
  `e2e_finance`/FINANCE, `e2e_suspended`/SUSPENDED, plus rotating the existing `e2e_admin`'s
  password) via `pgcrypto`'s `crypt()`, same technique and same "never committed" discipline this
  README already documents elsewhere for admin seeding — against real data (`reconciliation` id 1,
  `settlement` id 1 from earlier in this session):

  | Test Case | Result |
  |---|---|
  | TC-ADM-001 — login, valid credentials | `200` on an authorized admin call |
  | TC-ADM-002 — login, invalid credentials | `401` — rejected correctly, **but see gap below** (audit half closed 2026-09-27) |
  | TC-ADM-003 — RBAC: VIEWER attempts a mutating action | `403 PERMISSION_DENIED`, blocked |
  | TC-ADM-004 — RBAC: FINANCE accesses reconciliation module | `200`, granted per the seeded permission matrix |
  | TC-ADM-005 — permission: user without `retry:execute` attempts retry | `403 PERMISSION_DENIED` — blocked correctly, **but see gap below** |
  | TC-ADM-012 — retry authorization: user *with* `retry:execute` | Permission granted, request reaches business logic (`404` for a nonexistent child order id — proves the AOP proxy let it through, not a stub) |
  | TC-ADM-013 — audit log completeness | Exactly 1 new `audit_log` row per successful mutation (`RECONCILIATION_INVESTIGATE`, `RECONCILIATION_RESOLVE`), correct `actor_id` per acting admin, zero rows for the denied/not-found attempts above |
  | TC-ADM-014 — settlement display | `200`, real per-partner allocation breakdown returned (gross/fee/net/reconciling total) — see gap below for what this doesn't cover |
  | TC-ADM-015 — reconciliation handling | Full `OPEN → INVESTIGATING → RESOLVED` lifecycle driven for real on `reconciliation` id 1, `resolved_by` correctly recorded as the resolving admin's id |

  **Two real gaps found, not fixed in that pass** (same "tested for real, found a real spec-vs-code
  gap" pattern as `TC-BE-011`). **Both were closed on 2026-09-27 — see the `TC-ADM-002` /
  `TC-ADM-005` / `TC-ADM-012` entry above for what they now do and how it was re-verified.** The
  original findings are kept below as the record of what was actually observed:
  - **`TC-ADM-002`/`TC-ADM-005`'s "…audit-logged" half is not implemented.** Both test cases
    explicitly expect a *denied* attempt to still produce an audit trail ("failure audit-logged" /
    "audit-logged as denied attempt"), but `AdminReconciliationController`/`AdminFulfillmentController`
    only call `AuditService.recordAdminAction` on success, and a `@PreAuthorize` denial or a Basic
    Auth failure never reaches the controller body at all — there is no
    `AuthenticationEventPublisher`/failed-login listener and no catch-path for a denied permission
    check wired to `AuditService` anywhere in this codebase. `audit_log` confirms it: zero rows for
    either the invalid-login or the permission-denied attempts above.
  - **`TC-ADM-012`'s "…+ reason text" half is not implemented.** `AdminFulfillmentController.retry`
    takes no reason/justification parameter at all — only `@PreAuthorize("hasAuthority('retry:execute')")`
    is enforced, not the reason-text requirement this row also specifies.

  **Six test cases have no corresponding implementation to test at all** — `TC-ADM-006`
  (transaction search), `TC-ADM-007` (parent-child drilldown), `TC-ADM-008` (pricing update),
  `TC-ADM-009` (configuration update), `TC-ADM-010` (pattern viewing), `TC-ADM-011` (generation
  activation). None of these have a controller anywhere in `app/src/main/java/id/ppob2/app/web` —
  confirmed by listing every `@RestController` in the module, not just their absence from what's
  wired behind `/admin/**`. `TC-ADM-014`'s literal wording ("Expected vs actual vs fee … for a given
  settlement_date") is also only partially covered: the endpoint that exists shows the per-partner
  allocation breakdown (`Section 37.3`/`41.8`, added earlier this session), not a settlement-level
  expected/actual/fee view — there is no `GET` for the raw `settlement` row's own fields.

- **Webhook Retry Sweep** (Section 23.8 / 40.4) closes the gap this README used to flag as "no
  delivery/retry machinery, no persisted delivery-attempt-count, and no scheduled re-driver" —
  `OutboundWebhookOrchestrator.sendOrderStatusChanged` still makes one synchronous attempt (most
  deliveries succeed immediately; no reason to defer that), but on failure now schedules a retry
  via the new `webhook_delivery` table (`WebhookDeliveryTracker`) instead of only logging a
  warning. `WebhookRetrySweepJob` (`app`, same `@Scheduled` + bounded-batch shape as
  `QrExpirySweepJob`) picks up due `PENDING` rows and retries them through the *same* `attempt(...)`
  method the first try used — one shared path, not two copies of resolve/send/record to keep in
  sync.
  - **New table, not a Section 22 one**: `webhook_event` (22.24) is an append-only log (one row
    per attempt, no mutable state) — the wrong shape for a retry schedule's attempt count and
    next-attempt-at. `webhook_delivery` holds that instead; both tables get written for the same
    logical delivery by design, not a duplication to reconcile.
  - **Backoff is configurable, not the attempt count directly**: `ppob2.webhook.retry-backoff-minutes`
    (default `5,60,240,1080`) lists the delay *between* attempts; total attempts = list length + 1
    (the first synchronous one). Default yields 5 total attempts spanning ~23h, matching Section
    23.8's "e.g. 5 attempts over 24h" without hardcoding it. Verified booting with this exact
    default (no env override) to confirm the comma-parsing doesn't fail at bean construction —
    every prior boot during development had overridden it for faster iteration.
  - **`spring.task.scheduling.pool.size: 2`** was added because `WebhookRetrySweepJob` and
    `QrExpirySweepJob` would otherwise share Spring's default single-thread scheduler — a batch of
    slow/timing-out webhook deliveries (up to 200 per tick, each bounded by a 5s HTTP timeout)
    could occupy that one thread long enough to delay the next expiry-sweep tick, leaving unpaid
    orders sitting past their QR TTL. Not a hypothetical: caught by advisor review before this
    shipped, not by any test.
  - **Not safe for more than one app instance** — two instances racing on the same due row would
    both attempt delivery (partner gets a duplicate `ORDER_STATUS_CHANGED`) and both update the
    same `webhook_delivery` row with no optimistic-locking guard. Flagged in
    `WebhookRetrySweepJob`'s Javadoc with the eventual fix's shape (`SELECT ... FOR UPDATE SKIP
    LOCKED`) — deliberately *not* claimed benign the way `QrExpirySweepJob`'s identical-shape race
    is, since writing the same target state twice (that job) and sending a partner the same
    webhook twice (this job) are different risk classes. Not built — single-instance-only, same
    simplification as the in-memory nonce store and routing candidate cache elsewhere.
  - **`webhook_delivery_order_event_uk`** (unique on `parent_order_id, event_type`) assumes at most
    one `ORDER_STATUS_CHANGED` notification is ever owed per order — true today. If a future slice
    also notifies PPOB1 after `AdminChildOrderRetryOrchestrator`'s `PARTIAL_FAILED -> SUCCESS`
    retry (it arguably should), a second notification for the same order will collide with this
    constraint and be silently dropped — flagged in `WebhookDelivery`'s Javadoc, same treatment as
    the `ChildOrderState.COMPENSATED` predicate flag elsewhere.
  - Verified end-to-end against real Postgres, all three reachable outcomes: an order whose
    partner endpoint was reachable on the first try created no `webhook_delivery` row at all
    (nothing to schedule); an order whose endpoint stayed down the whole time reached
    `EXHAUSTED` at exactly attempt 5, with 5 matching `FAILED` rows in `webhook_event`; an order
    whose endpoint came back up mid-retry-cycle reached `DELIVERED`, and the test HTTP receiver
    confirmed it received the correctly-signed payload. Verified with a tuned
    `retry-sweep-interval-ms`/`retry-backoff-minutes` for fast iteration, not the production
    defaults — the boot-with-defaults check above is what confirms the real config value parses.
  - **Dev-DB fixture trap hit while testing this, unrelated to the retry sweep itself**: an order
    reached `REFUND_PENDING` ("no eligible decomposition pattern") for two different reasons
    layered on top of each other in this long-lived dev database — (1) `provider_sku.face_value`
    had drifted since the pattern was seeded, tripping Section 28.2's invariant check correctly
    (fixed by restoring `face_value` so `quantity × face_value` sums back to the pattern's
    `parent_amount`), and (2) `pattern_economics` is looked up by `snapshot_date = LocalDate.now()`
    (`RoutingService`, no daily re-scoring job exists — already a known gap), so a snapshot dated
    yesterday makes every order route to `REFUND_PENDING` with no more specific hint than "no
    eligible pattern." Fixed for this dev DB by inserting today's snapshot as a copy of the
    existing one (`INSERT INTO pattern_economics (...) SELECT ..., CURRENT_DATE, ... FROM
    pattern_economics WHERE snapshot_date = '<last date>'`) — that inserted row is still in the
    dev DB. Documented here so the next person hitting `REFUND_PENDING` unexpectedly checks this
    before assuming new code broke something.

Everything after that — the two remaining reconciliation types and the rest of Section 41's Admin
Web surface — is unbuilt; those are candidates for the next slice. Every other module directory
exists with a correct `build.gradle.kts` and dependency edges, but no domain code yet — that's
intentional groundwork, not a placeholder to delete.

Known gaps to close before this is production-real:

- **Two of the five Section 38.1 reconciliation types remain blocked, and neither is closeable
  from inside this repo**: `PAYMENT_VS_PG` and `PROVIDER_VS_REPORT` both need an external report
  format Section 37.1/38.1 explicitly mark "TBD — must be verified against contract" — there is no
  real ingestion source to build against, only one to invent, which this codebase has consistently
  avoided doing elsewhere (e.g. settlement's own report shape carries the same caveat).
- **No offline pattern-generation engine exists.** Section 28's Rust decomposition engine that
  populates `pattern_generation`/`decomposition_pattern`/`decomposition_component` is not part of
  this backend and hasn't been built anywhere yet — patterns must be seeded manually for now (see
  the example under "Running locally"). Same for the daily re-scoring job that's supposed to
  populate `pattern_economics.score`/`eligible` (Section 30.1) — `RoutingService` reads those
  columns but nothing computes them.
- **`decomposition_pattern`/`pattern_generation` have no `product_id` column**, despite Section
  29.2 stating patterns are generated "independently" per `(product, parent_amount)` pair.
  `PatternLookupService` works around this by filtering candidates after the fact via each
  component's `provider_sku.product_id`, rather than inventing an undocumented column — flagged
  inline, worth raising with the PRD owner.
- **Provider-level aggregate daily quota is not enforced.** `RoutingService` only checks
  `provider_sku.quota_daily` per SKU; `provider` has no quota column of its own (only
  `rate_limit_per_min`, a rate limit, not a daily quota), so a provider-wide cap would need to be
  computed as an aggregate over its SKUs — not implemented in this slice.
- **Section 31.3's weighted scoring formula is not implemented here, by design** — `RoutingService`
  only reads the pre-computed `pattern_economics.score` and picks the max. Recomputing the formula
  is the (not-yet-built) offline daily job's job, not runtime routing's, per Section 31.1.
- ~~Load-balancing / tie-break (Section 31.4) is not implemented~~ — **this entry was a false PRD
  attribution and has been retracted, not built.** On closer reading, Section 31.4 ("Purpose of
  Variation") is a compliance/legitimate-purpose statement — routing variation must never be used
  for audit/AML evasion — not a runtime tie-break specification. The actual "spread volume across
  providers" requirement is Section 31.3's `w_loadbalance * load_balance_score` term **inside** the
  composite score formula, which the (not-yet-built) offline daily re-score job is responsible for
  computing into `pattern_economics.score` — this is the same gap as "No offline pattern-generation
  engine exists" above, not a separate one. `RoutingService` picking the single highest pre-computed
  score deterministically is *correct* per Section 31.1, not a shortfall: once the offline job
  exists and produces a real `load_balance_score`, that's where volume-spreading is supposed to
  happen. Adding a runtime weighted-random (or any other) tie-break on top would double-count load
  balancing through two uncoordinated mechanisms once the real score exists — worse than the
  current gap, not a fix for it.
- **`RoutingServiceTest` uses reflection to populate its `ProviderSku`/`Provider`/`SkuUsage` test
  doubles**, since those entities have no public constructors/setters for test-only field
  population. Ugly but deliberate — the quota-exceeded case it covers isn't exercised anywhere
  else; worth revisiting if entity test-double ergonomics become a recurring pain point.
- **The Section 28.2 invariant check is read-time, not attach-time.** `PatternLookupService`
  verifies the allocation sum when candidates are looked up; the actual attach
  (`order.setPatternId(...)`) happens slightly later in the same transaction, after `routing`
  has picked one. A `provider_sku.face_value` update committed in that narrow window wouldn't be
  caught. Low risk (face values don't change often, and the read isn't locked); this slice's
  fulfillment dispatch does not re-verify it either — flagged again below.
- **No Order Ledger posting anywhere.** Section 36.1 names an Order Ledger ("entries tied to
  parent/child order lifecycle events, e.g. order value recognition"), but Section 33.2's Side
  Effect column — the authoritative trigger list this codebase has followed for every ledger/state
  decision so far — never names a transition that posts to it. Posting one anyway (e.g. alongside
  the Payment Ledger entry at `PAID`) was considered and deliberately rejected: two `CREDIT`
  entries for the same money at the same instant, in ledgers Section 36.2's traceability chain
  doesn't disambiguate between, reads as a double-count to anyone auditing this later. Needs a PRD
  decision on where "order value recognition" actually happens before this is built.
- **"Settlement window" is a single calendar day, and exactly one batch per day is supported** —
  not a real T+N schedule with contract-defined cutoff times/timezones (Section 37.1 flags the
  actual schedule as unverified against the Ayolinx contract), and not multiple intraday batches
  either: the `UNIQUE(settlement_date)` constraint above turns "more than one batch a day" into a
  hard rejection rather than a silent wrong comparison. A payment that settles into a different
  bucket under a real cutoff rule (e.g. confirmed at 23:58, settling as part of the next day's
  batch), or a PG that reports multiple batches per day, both need a `payment -> settlement` link
  (Section 22.17 has none) to scope each batch's expected sum to only the payments it covers —
  not built.
- **No circuit breaker, rate limiting, or health check** (Section 27.2) — `StubGameProviderAdapter`
  is called directly and unconditionally; a real provider integration needs all three before this
  is production-real, same class of gap as the deferred pattern-generation engine.
- ~~No inquiry-before-retry for ambiguous failures~~ — closed, see "Inquiry-before-retry" below.
- **No per-provider adapter registry.** Section 27.1 describes `ProviderAAdapter`/`ProviderBAdapter`
  per real provider; this slice has exactly one generic `StubGameProviderAdapter` bean used for
  every `provider_id`, mirroring `StubQrisPaymentGateway`'s single-bean precedent. There's no
  second real provider yet to make a registry meaningful.
- **No distributed lock per `child_order_id`** (Section 34.1) — the `provider_transaction`
  unique constraint is the only guard against concurrent duplicate dispatch (no Redis in this
  codebase, same simplification already made for nonce storage and routing's candidate cache).
- **Sequential dispatch only** — Section 34.1's default ("sequential-per-provider, parallel-
  across-providers") isn't implemented; `FulfillmentExecutionService` dispatches every child order
  for a parent one at a time regardless of provider.
- **`api_client.secret_hash`** (Section 22.3) is documented as a one-way hash, but HMAC
  verification requires the server to reproduce the client's MAC, which a one-way hash can't do.
  `HmacAuthenticationFilter` currently treats the stored value as the verification secret
  directly — flagged inline with a `NOTE:` comment. This needs a decision from whoever owns the
  PRD (reversible/KMS-backed encryption is the likely fix) before going further.
- **Nonce replay protection** (`NonceStore`) is in-memory. Section 19.1 designates Redis for
  this; an in-memory store does not protect against replay once there's more than one app
  instance.
- **A real `AyolinxPaymentGateway` now exists** (see the narrative entry above) but has never
  round-tripped against Ayolinx's actual sandbox — that needs merchant registration + KYB first
  (no self-service credentials). Until that happens, `StubQrisPaymentGateway` (gated behind both
  `@Profile("!prod")` and `ppob2.payment.gateway=stub`, the default) remains what actually runs in
  every environment that's been exercised so far. No payment will actually settle against a real
  bank until the real gateway is verified against real credentials.
- **`metadata`** on `POST /orders` is accepted and silently dropped — Section 22.15's
  `parent_order` schema has no column for it, and no built feature reads it back. (`callback_url`
  *is* persisted and used — see the outbound webhook slice above; only `metadata` remains a gap.)
- **`OrderStateMachine`** enforces which *states* may follow one another, but Section 34.1's real
  `FULFILLING`/`PARTIAL_FAILED → SUCCESS` invariant is child-order-count-shaped ("parent SUCCESS
  requires ALL child orders SUCCESS"), not state-shaped — the state machine cannot see that on its
  own. `ParentOrderTransitionService.completeFulfillment` now enforces it (see the fulfillment
  slice notes above) by checking every child order's state before choosing `SUCCESS`/
  `PARTIAL_FAILED`/`FAILED`, including a loud error log (not a silent no-op) if any child order is
  still `PENDING`/`EXECUTING` when completion is requested — a stuck-order signal.
- ~~No `ArchUnit` test enforces the Section 20.2 module graph at the package level~~ — closed, see
  "Module architecture check" below. The Gradle project-dependency graph still enforces the
  module-level graph itself (the larger risk); this closes only the smaller, previously-unenforced
  package-level convention.
- **`AyolinxCallbackPayload`'s shape is now built against Ayolinx's real public API docs**, not
  invented — see the narrative entry above for what changed and what's still genuinely unverified
  (the callback signature's `ROUTE`, exact non-success response codes, etc.). The
  `X-Ayolinx-Signature`/HMAC-SHA256 scheme remains `StubQrisPaymentGateway`-only, a local-testing
  convenience (see "Running locally" below) — it is not part of the real `AyolinxPaymentGateway`,
  which uses Ayolinx's actual RSA-SHA256 callback verification instead.
- **The `STALE_TERMINAL_STATE` warning (Section 25.2's "flag for manual review") fires at most
  once per callback `event_id`.** A retried out-of-order callback with the *same* `event_id` takes
  the `DUPLICATE_IGNORED` path on retry instead, since the dedup row it wrote on its first arrival
  is now real. With no `reconciliation` module yet, that one log line is the only trace of a stale
  callback — plan for that when building `reconciliation`, don't assume the warning is durable.
- **`webhookEventRecorder.record(...)` joining `PaymentCallbackService.processCallback`'s own
  transaction is deliberate, not a gap** — checked while investigating whether to reuse the
  `recordIndependently()`/`REQUIRES_NEW` fix built for the *outbound* webhook path here too.
  `record()`'s own Javadoc explicitly documents joining as correct for this inbound path: rolling
  the receipt log back together with a failed `payment.markSuccess`/ledger-post is the wanted
  behavior, not a bug, because those two writes must commit atomically (Section 22.24 has no FK to
  `payment` specifically so it *can* record callbacks that never resolve to a payment at all — but
  once one does resolve, its receipt shouldn't outlive a rollback of the processing it describes).
  Switching this call site to `recordIndependently()` would decouple the receipt row from
  `payment_event.dedup_key`'s own rollback, risking a *duplicate* `RECEIVED` row on a later retry
  of the same event — worse, not better. Left as-is; this entry replaces an earlier, vaguer version
  of this note that read as an open gap when the code already had a considered answer.

- **Per-partner settlement allocation** (PRD Section 37.3 / 22.28, added when the platform's
  downstream reseller topology grew beyond a single partner) — `settlement`'s new
  `SettlementAllocationService.allocateProRata` attributes one platform-aggregate `settlement`
  batch across the partner(s) whose payments contributed to it, persisting `settlement_partner_
  allocation` rows (V20 migration). Wired into `SettlementIngestionOrchestrator`, in the same
  transaction as the settlement/reconciliation writes it already composes — allocation is meant to
  be computed for every settlement, not a best-effort side effect.
  - **Only `PRO_RATA` is implemented, `EXACT` is not** — see `AllocationMethod`'s Javadoc. `EXACT`
    needs the Ayolinx settlement report to carry per-transaction lines so each can be joined
    straight back to its `payment`/`parent_order.partner_id`; Section 37.1 still flags that report
    format as unverified, and this codebase has no data model for a line-itemed report to build
    `EXACT` against. Adding it later is a new producer of `SettlementPartnerAllocation` rows, not a
    schema change — `allocation_method` already distinguishes the two.
  - **Per-partner input is resolved in `order`, not `settlement` or `payment`** — `payment` carries
    no `partner_id` at all (Section 22.17 has none; attribution lives on `parent_order`), and
    Section 20.2 grants `order -> payment` but nothing the other way and nothing from `settlement`
    to either — so `ParentOrderRepository.sumSuccessfulPaymentAmountByPartnerForDate` (a native
    query joining `parent_order`/`payment` by table name, same "FK lives outside the JPA
    relationship" precedent as `ProviderPrice.providerSkuId`) lives on the permitted side of that
    edge, and `SettlementIngestionOrchestrator` — already the composition root for `payment`/
    `settlement`/`reconciliation` — is where the result is handed to `SettlementAllocationService`.
  - **Largest-remainder rounding, not floor-and-drop or floor-and-give-it-all-to-one-partner.**
    Section 21.1 prohibits float money, so a naive per-partner `actual_amount * weight / total`
    leaves integer remainder units unaccounted for; `distributeByLargestRemainder` floors every
    partner's share via `BigInteger.divideAndRemainder`, then hands the leftover units — always
    strictly fewer than the number of partners, which follows algebraically from `sum(weight) =
    total` rather than being merely assumed — to the partners with the largest remainders,
    ascending `partner_id` breaking ties for determinism. Gross (`actual_amount`) and fee
    (`fee_amount`) are distributed independently through the same routine, since a fee-pool
    remainder and a gross-pool remainder don't need to land on the same partner.
  - **BR-REC-002's sum invariant is checked explicitly after the fact**, not merely trusted from the
    arithmetic: `allocateProRata` re-sums every computed share and compares against
    `settlement.actual_amount`/`fee_amount` before persisting, throwing `SETTLEMENT_ALLOCATION_
    INVALID` (a new `ErrorCode`, 500 — an invariant failure here is a bug to investigate, not a
    caller error) rather than persisting a total that's merely close.
  - **Re-computation replaces rather than accumulates** — `deleteBySettlementId` runs unconditionally
    at the top of `allocateProRata`, before either the empty-partner-map or zero-total-expected
    early exits, so a settlement that later has zero contributing partners (shouldn't happen today,
    every order is `RESELLER_API`, but not schema-prevented) ends up with an empty allocation set
    rather than a stale one left over from a previous run.
  - **No partner-facing endpoint** — `AdminSettlementController`'s `GET /admin/settlements/{id}/
    allocations` is Admin Web only (Section 41.8), gated behind the existing `settlement:ingest`
    permission rather than a new `settlement:view` (flagged in that controller's Javadoc as worth
    splitting out if a broader settlement read surface gets built later). Attribution is recorded
    for reporting; it does not disburse anything to the partner — see Section 37.3's explicit scope
    note and the "Gap" note this leaves below.
  - **Verified end-to-end against a real Postgres instance and a real HTTP call**, not just
    `SettlementAllocationServiceTest`'s mocked-repository cases. This development environment had
    no JDK 21 (only 8 and 17) and no local Postgres when the code was first written — both gaps
    closed for this verification: JDK 21 installed as a portable zip (the MSI installer's UAC
    prompt couldn't be approved non-interactively, so the no-install distribution was used
    instead), Postgres started from the `docker-compose.yml` already checked into this repo. All 20
    migrations applied cleanly, including V20.
    - Seeded two partners directly via SQL (bypassing the not-yet-built order-creation flow, the
      same pragmatic shortcut this README already uses elsewhere for un-built upstream pipelines):
      Partner A with a `SUCCESS` payment of 33,000, Partner B with 67,000, both dated the same
      settlement day. `POST /internal/settlement/ingest` with `actual_amount=100001,
      fee_amount=1` deliberately chosen so neither pool divides evenly by the 33,000:67,000
      weighting — a real test of the rounding rule, not just the tie-break case
      `SettlementAllocationServiceTest` already covers with equal weights.
    - Result, read back from `GET /admin/settlements/{id}/allocations` **and** independently via
      `SELECT` against `settlement_partner_allocation` directly (not trusting the API response
      alone): Partner A `gross=33000/fee=0/net=33000`, Partner B `gross=67001/fee=1/net=67000`.
      Partner B — the larger weight, hence the larger remainder — correctly won both leftover
      units; `SUM(gross_amount)=100001` and `SUM(fee_allocated)=1` matched `settlement.actual_amount`/
      `fee_amount` exactly, confirmed by a direct `SUM(...)` query, not recomputed in application
      code. The existing `ledger_entry` (`SETTLEMENT`/`CREDIT`/100001) and `reconciliation`
      (`PAYMENT_VS_SETTLEMENT`/`OPEN`, since 100001 ≠ the 100000 expected) rows were also confirmed
      present and unaffected by this slice's addition, in the same transaction.
    - **A real, pre-existing bug (not introduced by this slice) surfaced during this run, and
      since fixed**: `SettlementIngestionController.SettlementIngestionRequest` declared plain
      camelCase fields (`actualAmount`, `pgReference`, ...) with no `@JsonNaming` override, but
      `application.yml` configures `spring.jackson.property-naming-strategy: SNAKE_CASE` globally
      — the same mismatch `AyolinxCallbackPayload` already works around with its own
      `@JsonNaming(LowerCamelCaseStrategy.class)`. A camelCase request body — the shape anyone
      would reach for from the Java field names — silently deserialized to an all-null record
      (Jackson found no matching `snake_case` property for any field), which then NPE'd inside
      `Money.of(null)` rather than failing with a clear validation error. Only visible by actually
      calling the endpoint over HTTP; `SettlementIngestionServiceTest` tests the service layer
      directly and never touches Jackson, so it could not have caught this. Confirmed live by
      resending the identical request with `snake_case` keys (`settlement_date`, `actual_amount`,
      ...), which ingested correctly.
      - **Fixed**: both `SettlementIngestionRequest` and `SettlementIngestionResponse` now carry
        their own `@JsonNaming(LowerCamelCaseStrategy.class)`, the same mechanism
        `AyolinxCallbackPayload` uses for the opposite reason (there, to accept a fixed *external*
        camelCase contract despite the global default; here, so this internal ops endpoint's
        request/response shape matches its own Java field names instead of silently depending on
        a global default no caller has reason to know about). Applied to the response too, not
        just the request, so the endpoint's input/output casing stays symmetric.
      - **`SettlementIngestionControllerTest`** (new) proves this the way `AyolinxCallbackPayloadTest`
        already proves the equivalent fix for the payment webhook slice: against an
        `ObjectMapper` configured exactly like the app's real bean (`SNAKE_CASE` + `findAndRegisterModules()`
        for `LocalDate`), the request's own camelCase JSON now deserializes correctly; the old
        `snake_case` workaround shape now fails loudly (`UnrecognizedPropertyException`) instead
        of silently producing an all-null record — a strictly better failure mode than the bug
        itself; and the response serializes back out in the same camelCase shape. All three cases
        verified with real JDK 21 (now installed, portable zip, alongside the already-installed 8
        and 17) — no toolchain override needed for this one. Full existing suite (26 test classes,
        the 25 from before plus this new one) still passes.
    - `order`/`payment`/`settlement`/`reconciliation`/`ledger` migrations and the native
      `sumSuccessfulPaymentAmountByPartnerForDate` join query are therefore now real-database
      verified, not merely unit-tested — the caveat this bullet previously carried no longer
      applies. `SettlementAllocationServiceTest`'s five cases (even split, the uneven three-way
      split whose tie-break lands on the lowest `partner_id`, a single partner, an empty partner
      map, and the zero-total-expected rejection) and the full existing suite (25 test classes)
      still pass unchanged.
  - **Gap, not built**: an actual payout/disbursement to each partner's own bank account. Section
    37.3 scopes this deliberately narrow — attribution only — and Section 7 lists a future
    payout/disbursement capability as its own not-yet-specified feature, with its own
    authorization, timing, and ledger-posting design.

- **Full test cycle, 2026-10-03 — `TC-BE-018`'s money-visibility half was a real, open defect**
  (`DecompositionExhaustedEvent`, `OrderFulfillmentReconciliationOrchestrator#onDecompositionExhausted`).
  The whole suite was re-run from zero: 27 unit/slice test classes green, then both e2e scripts
  against a wiped volume (all 20 Flyway migrations re-applied) with the app on the default profile
  and the three injection knobs. Everything previously tagged **R** re-passed unchanged — 62/62 and
  13/13 — which is the context for the one thing that did not hold up.
  - **The defect.** `TC-BE-018` was tagged **P** on the strength of "order → `REFUND_PENDING`
    (BR-DEC exhaustion)", and that half was true. Driving it for real against a 10,000 order (a
    supported amount with no seeded pattern) showed the other half: `payment` reached `SUCCESS`, a
    full 10,000 CREDIT was posted to the payment ledger — *the funds were collected* — the order
    carried zero child orders, and **`reconciliation` was empty**. Confirmed by surveying every
    order in the database by state: `PARTIAL_FAILED`, `FAILED` and the paid-`CANCELLED` case each
    had their `ORDER_VS_FULFILLMENT` row; `REFUND_PENDING` alone had none.
  - **Why it survived the 2026-09-27 pass.** This is the *same* funds-collected-nothing-delivered
    hole that pass found and closed for `CANCELLED` (see `LatePaymentOnUntransitionableOrderEvent`),
    reached by a different route — so the fix went in where that route was, and this one was never
    driven. `TC-BE-018` carried prior evidence for the state transition and nobody re-read what
    that evidence actually covered. The tag was accurate and still hid the bug; that is the
    argument for the **R** tag meaning *asserted by a script*, not *someone checked once*.
  - **Why the code looked correct.** `OrderFulfillmentReconciliationOrchestrator.reconcile`'s state
    guard named `REFUND_PENDING` explicitly and called it "unreachable here in practice since
    `ChildOrdersReadyEvent` never fires on that path". That reasoning was *right* — and it is the
    whole problem: the state was correctly excluded from the dispatch-driven path and then covered
    by nothing else, so a deliberate, documented exclusion read as deliberate, documented handling.
    The guard is unchanged; its comment now says why the state needs its own trigger instead of
    implying none is owed.
  - **The fix** mirrors the already-proven sibling route exactly rather than inventing a shape:
    `order` publishes `DecompositionExhaustedEvent` on the exhaustion branch (it has no Section 20.2
    edge to `reconciliation`), and `app`'s orchestrator opens `ORDER_VS_FULFILLMENT` keyed on
    `parent_order.id` with `expected = parent_amount` / `actual = 0`, behind the same
    `hasOpenDiscrepancy` guard. Plain `@EventListener` + `REQUIRES_NEW`, for the reason
    `onLatePaymentOnUntransitionableOrder` documents: the publisher is itself `REQUIRES_NEW` off an
    `AFTER_COMMIT` callback, so a live transaction exists to join, and this row is worth keeping in
    its own. `REFUND_PENDING` is not itself the operator queue — nothing scans for it, there is no
    refund job (Section 73.3), and the queue Admin Web surfaces is `reconciliation`.
  - **Reproduced after the fix, not just asserted**: the identical 10,000 order now leaves
    `payment SUCCESS` + 10,000 CREDIT + `REFUND_PENDING` **and** an `OPEN ORDER_VS_FULFILLMENT` row
    reading `expected 10000 / actual 0 / discrepancy -10000`, with a WARN naming the refund as
    owed. The row was then driven `OPEN → INVESTIGATING → RESOLVED` through the real admin
    endpoints (`resolved_by` recorded), so it is actionable and not merely present — which also
    re-verified `TC-ADM-015` live in this pass.
  - **Now script-asserted, so the tag means something**: six new assertions in `run-core.sh`
    (`TC-BE-018`, taking it 62 → 68) cover the state, the absent pattern, zero children, *that the
    funds were collected*, and the discrepancy value. Two new unit tests cover the listener's open
    and its duplicate guard (123 → 125 tests). The "funds WERE collected" assertion is deliberate:
    without it the case passes for the wrong reason the day the payment path changes.
  - **Still open, deliberately**: no `ORDER_STATUS_CHANGED` webhook fires for `REFUND_PENDING`.
    That is the pre-existing partial-trigger-coverage gap `OutboundWebhookOrchestrator`'s Javadoc
    already names (`CANCELLED`/`EXPIRED`/`REFUNDED` didn't fire either — `EXPIRED` does as of the
    2026-10-10 entry below), not something this fix introduced, and widening it is a separate
    change. There was also, at the time of this entry, no
    reconciliation *listing* endpoint for an operator to browse, so the row was correct and
    queryable but not visible to anyone without a psql prompt — closed by the 2026-10-10 entry
    below. (That gap was attributed here to `TC-ADM-006..011`, which was wrong: those IDs are
    transaction search, drilldown, pricing, configuration, patterns and generation. The
    reconciliation read side has no `TC-ADM-*` ID of its own — the PRD's traceability matrix ties
    Admin Web's reconciliation views to `TC-BE-028`.)

- **Refund execution** (Section 33.2's `REFUND_PENDING → REFUNDED`, FR-PAY-007, Section 42.2's
  `refund:initiate`) — `POST /admin/parent-orders/{id}/refund`, `AdminRefundOrchestrator`,
  `RefundRecorder`, `PaymentRefundService`, `V21__refund_execution.sql`. Discharges the obligation
  the 2026-10-03 pass made visible: `REFUND_PENDING` was a reachable state (BR-DEC exhaustion,
  `TC-BE-018`) that nothing could ever move an order out of — no job, no endpoint, no permission.
  - **Two modes, chosen by capability rather than assumption.** `PaymentGateway` gained
    `supportsRefund()`, making FR-PAY-007's "subject to ... PG capability" and Section 25.2's
    "subject to Ayolinx's refund capability/window" something callers branch on instead of
    discovering by catching `UnsupportedOperationException`:
    - **Gateway-executed** — we call `PaymentGateway.refund()` and record the PG's own refund
      reference. `external_reference` is *refused* in this mode, because the PG's reference is the
      authoritative one and accepting both would leave it ambiguous which was recorded.
    - **Recorded out-of-band** — the only mode reachable in any bootable production configuration,
      because `AyolinxPaymentGateway.supportsRefund()` returns false: Ayolinx's public API has no
      refund endpoint, and `qr-mpm-cancel` voids an *unpaid* QR rather than refunding a settled
      payment (Section 73.3's open question 5, still open). A human already moved the money and
      supplies the reference proving it; `external_reference` is mandatory, enforced server-side.
      A refund recorded with no evidence it happened is the one outcome worse than no refund.
  - **Not a silent fallback.** A gateway that claims the capability and then declines produces
    `502 REFUND_FAILED_AT_GATEWAY` and writes nothing — the order stays `REFUND_PENDING` and
    remains refundable. Degrading to "record it as out-of-band" would book a reversal for money
    still sitting at the PG. A reported success carrying no refund reference is also treated as a
    failure: there would be nothing to reconcile the refund against afterwards.
  - **The reversal, per Section 33.2's "Ledger: reversing entry posted"** — a `PAYMENT` ledger
    `DEBIT` of the full amount, so the payment ledger for that payment nets to zero. A DEBIT of a
    positive amount, not a negative CREDIT: Section 22.21 carries direction in `entry_type` and
    `LedgerService` rejects a non-positive amount outright.
  - **Three independent idempotency guards**, because this is the only irreversible money movement
    in the system: the order's `REFUND_PENDING` check, `Payment.markRefunded`'s `SUCCESS`-only
    guard (whose direction is the opposite of `markSuccess`/`markFailed`/`markExpired` on purpose —
    a refund is only meaningful for a payment that actually collected money), and
    `payment_event_dedup_uk` on a deterministic `refund:{paymentId}` key. A replayed refund answers
    `409` and the e2e run asserts there is still exactly one DEBIT and one `REFUND` event after it.
  - **A transaction bug caught before it shipped, worth recording because it would have been
    invisible.** The commit step was first written as a `@Transactional protected` method on
    `AdminRefundOrchestrator`, called as `this.commit(...)`. Spring's transaction advice lives on a
    proxy, so a self-invocation bypasses it entirely: the code compiled, the mocked test passed, and
    at runtime each write would have committed in its own transaction — exactly the partial refund
    record the ordering exists to prevent, with nothing anywhere to show it. Extracted to
    `RefundRecorder`, a separate bean, because crossing a bean boundary is what makes
    `@Transactional` apply at all. Same family as `markPaid`'s documented propagation bug: the
    transaction configuration in this codebase is load-bearing and has been gotten wrong before.
  - **Deliberately narrow.** Only an order already in `REFUND_PENDING` can be refunded. Moving a
    `PARTIAL_FAILED`/`FAILED` order into `REFUND_PENDING` is Section 33.2's separate "Ops decision
    per SOP" edge and this endpoint does not make it; a `SUCCESS` order is refused outright (`409`).
  - **RBAC — `refund:initiate` granted to SUPER_ADMIN, FINANCE and RECONCILIATION**, on an explicit
    product decision. The RECONCILIATION grant is a **recorded deviation** from this repo's own
    reading of Section 42.1, not an oversight: V19 withheld `settlement:ingest` from that role
    because its scope says "settlement read", and by the same reasoning it would not hold a refund
    permission either. The counter-argument that carried is that `REFUND_PENDING` orders surface as
    `ORDER_VS_FULFILLMENT` rows in the reconciliation queue, so that role is the one actually
    working the queue, and splitting "can see the refund is owed" from "can discharge it" would put
    two roles in every refund. The reasoning and the revisit condition are in `V21`'s own comments.
  - **Audit, per Section 25.2's "only permitted through Admin Web with authorization + audit"** —
    `PARENT_ORDER_REFUND` with actor, IP, before/after state, a **mandatory non-blank `reason`**
    (BR-ADM-001; same control as `TC-ADM-012`'s retry reason, and blank is rejected rather than
    accepted) and the refund reference. Rejections are not audited, matching the
    `CHILD_ORDER_NOT_RETRYABLE` / `ORDER_NOT_CANCELLABLE` precedent; a *denied* attempt is already
    recorded as `ADMIN_PERMISSION_DENIED`.
  - **Both modes driven end-to-end**, not just unit-tested — 20 new `run-core.sh` assertions for the
    out-of-band mode (68 → 90, including that the ledger nets to zero and that a replay changes
    nothing) and a new `scripts/e2e/run-refund-gateway.sh` (15) for the gateway path. The gateway
    branch needs `PPOB2_PAYMENT_STUBGATEWAY_SUPPORTSREFUND=true`, a dev-only knob in the same family
    as `StubGameProviderAdapter`'s injection knobs and added for the same reason: no real gateway
    answers `true`, so without it that branch would be unreachable in every environment this
    codebase can boot, and "compiles, has a mocked test, has never run" is the profile of every
    money defect this harness has found. That script *asserts* the knob is active before testing
    anything, because against a normally-booted app every case would pass for the wrong reason.
  - **Found while writing those assertions: `jsonb` does not preserve key order.** Both
    `payment_event.raw_payload` and `audit_log.after_state` are `jsonb`, so Postgres normalises
    whitespace and reorders keys on storage — a `LIKE '%"mode":"GATEWAY"%'` pattern failed against a
    perfectly correct row. Assertions now read fields with `->>`. This also makes a comment
    `AdminFulfillmentController` has carried since the retry slice false (it claimed `LinkedHashMap`
    kept "the key order stable and predictable in the stored JSON"); corrected in both controllers
    rather than copied into the new one.
  - **Gap, not built**: no actual disbursement. The out-of-band mode is a *record* of a human's bank
    transfer or PG-dashboard action, which is why it demands their reference — Section 7 scopes
    payout/disbursement as its own unspecified feature, and Section 71's Refund SOP (authorization
    chain, ledger-reversal verification) is a go-live prerequisite. Also no partner notification:
    `ORDER_STATUS_CHANGED` still does not fire for `REFUNDED`, the same partial-trigger-coverage gap
    `OutboundWebhookOrchestrator` flagged for `CANCELLED`/`EXPIRED` (of which `EXPIRED` was closed
    on 2026-10-10, `CANCELLED` deliberately not).

- **Reconciliation read side, 2026-10-10** (FR-REC-003's "record **and expose**", Section 38.2's
  "**visible** and actionable in Admin Web", Section 41.9) — `GET /admin/reconciliations`,
  `GET /admin/reconciliations/{id}`, `ReconciliationQueryService`, `ReconciliationQuery`,
  `V22__reconciliation_view_permission.sql`. The write half had existed since the RBAC slice:
  `investigate`/`resolve` move a record **by id**, and nothing could produce an id. Every
  reconciliation row quoted anywhere in this file was located with a psql prompt, including the
  `TC-BE-018` record whose whole point was that an operator should be able to act on it. A queue
  nobody can enumerate is not an operational control, so this is the half that was missing.
  - **One filtered endpoint, not three.** Section 41.9 names a payment, a provider and a settlement
    reconciliation "view"; they differ only in which `recon_type` values they show. Filters:
    `recon_type` and `status` (both repeatable — `?status=OPEN&status=INVESTIGATING` is the whole
    unresolved queue in one call, since Section 38.2 defines exactly those two as non-terminal),
    `reference_id`, and an inclusive `from_date`/`to_date` range.
  - **`reference_type` is derived and is the field that makes a row readable.** `reference_id` is a
    plain nullable `BIGINT` with no FK and no discriminator column (Section 22.23 defines it that
    way because it is polymorphic), so a row saying `reference_id=31` does not say whether that is
    parent_order 31 or payment 31 — a distinction invisible in SQL and actively dangerous in a UI.
    `ReconciliationType.referenceType()` answers it, with each value read off the single
    `ReconciliationService.open` call site that produces that type rather than inferred from the
    name. `PAYMENT_VS_SETTLEMENT` is the one that would have been gotten wrong by reading the name:
    it points at the **settlement batch**, not a payment. `PROVIDER_VS_REPORT` returns `null`
    deliberately — nothing in this codebase opens that type, so no call site has ever decided what
    its `reference_id` means, and a plausible-looking answer there would be an invention a UI would
    then trust. A unit test fails if a sixth type is added without that decision.
  - **A new `reconciliation:view` permission, granted to SUPER_ADMIN, FINANCE and RECONCILIATION.**
    This is the precedent `AdminSettlementController` asked for when it reused `settlement:ingest`
    for a single read endpoint and said to revisit "as its own `:view` permission" once the read
    surface grew. Reusing `reconciliation:investigate` to gate a *read* would also invert Section
    42.1's own distinction — it grants FINANCE "reconciliation (read + resolve)", naming read as
    separable — and would mean no role could ever be shown the queue without also being given the
    power to advance records through it.
  - **VIEWER is deliberately withheld, and this is the one call here that cuts against the PRD's
    literal wording.** Section 42.1 describes VIEWER as "read-only across permitted modules, no
    mutation", and this permission is exactly that. It is withheld on the qualifier in the same
    sentence — "permitted modules" is never specified, so the grant is a judgement either way — and
    on what the rows actually are: every one is, by construction, money that does not add up
    (`TC-BE-018`'s −10,000 shortfall, `TC-BE-031`'s +20,000 surplus). "Can see every open financial
    exposure" is a Finance-shaped grant implemented as a read, not a general read. `V22`'s comments
    carry the reasoning and the revisit condition; the e2e run asserts the grant set is exactly
    those three roles, so widening it silently is not possible.
  - **Paging is clamped, and the clamp is echoed.** Fixed `id DESC` sort with no client-supplied
    sort property (an unknown one raises `PropertyReferenceException` — an unmapped `500` on a
    client typo — and `id` is a total order on an append-only queue, so paging cannot repeat or
    skip a row the way a sort on the non-unique `recon_date` can). No `Pageable` argument and no
    `Page` in the response body, whose JSON shape Spring Data itself warns is unstable across
    versions. `size` is capped at 200 and the **effective** value is returned, because a client
    that asked for 10,000 and silently got 200 would read a truncated first page as the complete
    queue.
  - **An inverted date range is a `400`, not an empty page.** On a discrepancy queue, empty reads as
    "nothing is wrong" — the most expensive possible way to render a typo. An unparseable
    `recon_type` or date is also a `400` in the standard envelope, via the
    `MethodArgumentTypeMismatchException` mapping `GlobalExceptionHandler` already had.
  - **Neither GET touches `audit_log`**, same reasoning and precedent as `AdminSettlementController`
    (Section 43's "Recorded" requirement is about admin *mutations*). That is load-bearing rather
    than cosmetic: `TC-ADM-013` asserts absolute 1:1 audit-row counts, so a GET that audited would
    break an unrelated test case, and only once someone browsed the queue. Asserted in both the
    unit test and the e2e run.
  - **A harness bug in my own new assertions, caught by checking rather than by a failure.** The
    first version asserted each filter as "API total == SQL count for the same predicate", which
    looked rigorous and proved nothing: every record this suite opens is `OPEN` and dated today, so
    `status=OPEN`, `status=OPEN&status=INVESTIGATING` and a today-to-today date range all returned
    12 of 12 — an endpoint ignoring those parameters entirely would have passed all three. Verified
    by querying the distribution by hand. Fixed by making the fixture discriminating (one record is
    moved to `INVESTIGATING` first, through the real endpoint) and by asserting each filter
    **excludes** something: `OPEN` is now strictly fewer than all rows, the repeated-`status` union
    is strictly larger than either side (which a last-wins binding would fail), `PROVIDER_VS_REPORT`
    returns none against a non-empty table, and a range ending yesterday returns none.
  - **`TC-ADM-015` is now script-asserted end to end**, which is why its tag moved **R\*** → **R**.
    The full `OPEN → INVESTIGATING → RESOLVED` lifecycle runs in `run-core.sh`, asserting
    `resolved_by` is the acting `admin_user` (never a client-supplied id), that `resolved_at` is
    set, that the resolver is surfaced by the API, that the record moves between the two filter
    results on either side of the transition, and that a second `resolve` applies nothing —
    Section 38.2's sequence is a sequence, not a set. It had been **R\*** (verified by hand)
    because nothing could enumerate the queue to script it.
  - **`TC-ADM-004` also moves R\* → R.** It was **R\*** only because "the fixture seeds no FINANCE
    user"; `run-core.sh` already seeds `viewer1` inline, so `finance1` is seeded the same way. That
    exercises V22's FINANCE grant over real HTTP (200 on the list, 200 on a detail, 200 on
    `investigate`) rather than only reading the grant back out of SQL, and keeps the boundary that
    makes those 200s mean something: 403 on the `retry:execute`-gated endpoint, since a role that
    reached everything would answer 200 there too.
  - **58 new `run-core.sh` assertions** (90 → 148) and 22 new unit tests across three classes
    (`ReconciliationTypeTest`, `ReconciliationQueryTest`, `AdminReconciliationControllerTest`;
    142 → 164). Full pass re-run from zero against a wiped schema (all 22 Flyway migrations
    re-applied): 148/148 core, 13/13 routing-and-sweeps, and 15/15 gateway-mode on its own clean
    boot.
  - **A cross-script bug in my own assertions, caught by running the next script.** The FINANCE
    case first targeted `min(id) where status='OPEN'`, which is the record for the F16 partial order
    — the exact record `run-routing-and-sweeps.sh` asserts is still `OPEN` for `TC-BE-028`. Moving
    it made that script fail on the next run. Now pinned to the record this script's own refund
    block opens. Worth recording because the e2e scripts share one seeded database by design, so
    "pick whatever record matches" is never safe across them.
  - **A noted deviation, found and asserted rather than silently relied on**: `resolve` audits its
    own no-op. A second `resolve` on an already-RESOLVED record answers `200` with
    `applied=false`, changes nothing — and still writes a second `RECONCILIATION_RESOLVE` row,
    because the controller's audit call is unconditional. `TC-ADM-013` reads "every mutating admin
    action produces exactly one audit_log entry", and this one mutated nothing;
    `AdminFulfillmentController`'s precedent ("a rejected attempt that changed nothing isn't an
    admin action to record") would not audit it. The difference is that a non-retryable child order
    is *rejected* with a `409` while this answers `200`. Left as-is deliberately — an operator
    trying to resolve an already-resolved discrepancy is itself worth seeing in the trail, and
    changing it is an audit-policy decision rather than a fix — but now asserted in both
    directions, so a future change to that policy fails the suite instead of quietly altering what
    `TC-ADM-013` counts.
  - **Three stale comments corrected** rather than left to mislead: `ReconciliationService`'s
    `investigate`/`resolve` and `ReconciliationServiceTest`'s class Javadoc both still said these
    methods were "unreachable from any real path — Admin Web, the only intended caller, isn't
    built", and `AdminReconciliationController`'s said it "does not build the read-side list/detail
    views Section 41.9 also names". The `backend/README.md` entry above also attributed the missing
    listing endpoint to `TC-ADM-006..011`; those IDs are transaction search, drilldown, pricing,
    configuration, patterns and generation. The reconciliation read side has no `TC-ADM-*` ID of its
    own — the PRD's traceability matrix ties Admin Web's reconciliation views to `TC-BE-028`.
  - **Gap, not built**: Section 41.9's resolution **notes**. Its bullet reads "OPEN → INVESTIGATING
    → RESOLVED, with notes and resolver identity" — resolver identity is recorded, notes are not.
    `reconciliation` has no notes column and `resolve` takes no body, so an operator can record
    *that* they resolved a discrepancy but not *why*: precisely the gap `TC-ADM-012` closed for
    retries, still open here. That is the next thing to build on this surface.

- **Two PPOB2 gaps found from the outside, 2026-10-10** — by building `ppob1-sim/`, the reseller
  side of the Section 23 contract, as a running system with its own database that can only reach
  this one over signed HTTP. Full detail and the rest of that slice's findings are in
  [`../ppob1-sim/README.md`](../ppob1-sim/README.md); recorded here because this file is where a
  gap belongs, next to the thing it affects.
  - **Section 23.8's outbound webhook had never been delivered to anything.** `V16` added
    `partner.webhook_secret` and `scripts/e2e/dev-seed.sql` was never updated to fill it.
    `WebhookDeliveryTargetResolver` handles a NULL secret correctly — it logs an error and skips
    rather than sending unsigned — so the consequence was completely silent: **no outbound webhook
    was ever delivered, by any order, in any environment that fixture builds.** The sender had unit
    tests, the harness had no receiver, so the whole path had never run end to end. Exactly the
    profile of every money defect found here: compiles, has a mocked test, has never run. The seed
    now sets the secret, which is safe for this directory's assertions because a webhook is only
    delivered when the *order* carries a `callback_url` and `mkorder` never sends one — checked, not
    assumed, and re-verified at 148/148 core and 13/13 routing. The path is now driven for real: a
    full round trip whose signature `ppob1-sim` verifies with its own independent implementation of
    Section 23.2.
  - **The settlement day boundary depends on the deploying host's timezone, and nothing pins it.**
    `PaymentRepository.sumSuccessfulAmountForDate` and
    `ParentOrderRepository.sumSuccessfulPaymentAmountByPartnerForDate` both group with
    `paid_at::date`. That cast runs in the **Postgres session** timezone, which the PG JDBC driver
    takes from the JVM default — *not* from `spring.jpa.properties.hibernate.jdbc.time_zone`, which
    only governs how Hibernate binds parameters. Verified directly against this container's
    Postgres: the instant `2026-10-10T23:30:00Z` casts to `2026-10-11` under `Asia/Jakarta` and
    `2026-10-10` under `UTC`. So the same payment data yields a different `expected_amount` per
    settlement date depending on the host's `TZ` — which decides `MATCHED` vs `DISCREPANCY`,
    whether a `PAYMENT_VS_SETTLEMENT` reconciliation record opens, and how
    `settlement_partner_allocation` apportions. An Indonesian deployment running `Asia/Jakarta` is
    entirely plausible, and it would silently group settlements seven hours off from this
    environment. **Fixed by the 2026-10-10 entry below**; it was left open at the time of this one
    because it is a PPOB2 configuration decision rather than part of the PPOB1 slice.
  - **`metadata.reseller_txn_id` is accepted and silently discarded.** Section 23.4's documented
    Create Order body carries it, `CreateOrderController`'s request record binds it, and
    `parent_order` has no `metadata` column at all — so it goes nowhere. This is the same shape as
    the `callback_url` gap `V16` fixed, whose own comment names the pattern ("already accepted and
    silently dropped"). The cost is concrete rather than cosmetic: PPOB2 support cannot tie an order
    back to the reseller's own reference, so every cross-company investigation has to start by
    asking PPOB1 to map its id to ours. Not fixed here — it is a schema change on the PPOB2 side and
    was outside the requested scope — but `run-ppob1.sh` asserts the column's absence, so the
    assertion flips the day someone adds it. **Fixed by the 2026-10-10 `V23` entry below**, and the
    assertion did flip: `run-ppob1.sh` now checks that the column holds PPOB1's own id for the order
    PPOB1 was given.

- **One business-day boundary for the platform, 2026-10-10** (`BusinessTimeConfig`,
  `ppob2.business-zone`). Closes the timezone finding recorded in the entry above. Before this,
  PPOB2 had no business-day definition at all — it had the JVM's default timezone, used implicitly
  in two different ways, and nothing anywhere said so.
  - **The two shapes of the bug.** Seven `LocalDate.now()` call sites (daily usage counters, quota
    enforcement, `reconciliation.recon_date`) took the host's zone. And two settlement queries
    grouped with `paid_at::date`, whose cast runs in the **Postgres session** timezone — which the
    PG JDBC driver sets from the JVM default, *not* from
    `spring.jpa.properties.hibernate.jdbc.time_zone`. That last point is the trap: the setting a
    reader would reasonably assume covered this governs only how Hibernate binds parameters.
  - **Reproduced at the layer it lived in, not argued about.** A bare JDBC connection, same
    database, same query, two JVM timezones:

    | `TZ` | PG session `TimeZone` | `'2026-10-10T23:30:00Z'::timestamptz::date` |
    |---|---|---|
    | `UTC` | `UTC` | `2026-10-10` |
    | `Asia/Jakarta` | `Asia/Jakarta` | `2026-10-11` |

    So the driver really does propagate the JVM zone to the session, and the cast really does follow
    it. One payment, two settlement days, decided by an environment variable nobody set deliberately.
  - **Why it matters beyond tidiness.** `expected_amount` decides `MATCHED` vs `DISCREPANCY`, which
    decides whether a `PAYMENT_VS_SETTLEMENT` reconciliation record opens, which feeds
    `settlement_partner_allocation`. An Indonesian deployment running `Asia/Jakarta` is not a
    hypothetical, and it would have grouped settlements seven hours off from this environment with
    nothing to show that anything had changed. Separately, `UsageTrackingService` writes daily usage
    and `RoutingService` reads it to enforce `provider_sku.quota_daily`: they happened to agree
    (both took the JVM default), but nothing held them together, and a quota checked against a
    counter nobody increments is enforcement that has silently stopped enforcing.
  - **The fix: one `java.time.Clock` bean, injected.** `LocalDate.now(clock)` is the idiomatic way
    to make "today" a dependency rather than a global, it needs no change to `shared-kernel` (which
    is deliberately free of framework dependencies), and it makes the affected unit tests able to
    pin a date with `Clock.fixed(...)`. That last part is not incidental: the previous tests
    asserted against `LocalDate.now()`, which is a test that *cannot fail* for this bug, because
    both sides of the comparison move together. The new ones fix the clock at **23:30 UTC**, an
    instant whose Jakarta date is the next day, so a regression to a host-zone lookup changes the
    date they see.
  - **The settlement queries lost their `LocalDate` overloads entirely**, rather than being
    deprecated, so no caller can reintroduce the host dependence. They now take an explicit
    half-open `[start, end)` instant range. `SettlementIngestionOrchestrator` computes that window
    **once** and passes the identical bounds to both the total and the per-partner query — a
    correctness requirement, not tidiness: those two are the denominator and the numerators of one
    apportionment, and BR-REC-002 requires the allocations to sum to `actual_amount` exactly. Two
    windows would break that invariant at allocation write time, far from the cause. Asserted
    directly in `SettlementIngestionOrchestratorTest`.
  - **Two sites that were already explicit UTC moved too** — `OrderNumberGenerator`'s date part and
    `MarginReconciliationOrchestrator`'s `orderDate`. Neither was a bug (both were host-independent
    already), but leaving them hardcoded while everything else became configurable would have
    created a *new* inconsistency: set `Asia/Jakarta` and an order booked on the Jakarta 11th would
    have carried a `10` in its order number. Safe to move because the order-number sequence is
    global and never reset per day, so changing the date label cannot collide with an earlier order.
  - **Default `UTC`, deliberately not `Asia/Jakarta`.** UTC reproduces the behaviour this codebase
    already had, so making the boundary explicit does not silently move anyone's settlement figures.
    `Asia/Jakarta` is very likely the correct business value — but Section 37.1 flags the settlement
    schedule and cutoff rules as "must be verified against latest Ayolinx contract", and picking the
    plausible answer here would dress an unverified assumption up as a decision. Set
    `PPOB2_BUSINESS_ZONE=Asia/Jakarta` once that cutoff is confirmed. Not to be confused with
    `ppob2.payment.ayolinx.timezone`, which only formats timestamps in Ayolinx API payloads.
  - **Verified end-to-end with the JVM pinned at `Asia/Jakarta` throughout**, which is the
    configuration the old code got wrong — confirmed from the boot log's own `+07:00` timestamps,
    not assumed:

    | `ppob2.business-zone` | payment `paid_at` | settlement date ingested | `expected_amount` | status |
    |---|---|---|---|---|
    | `UTC` | `2026-10-10T23:30Z` | `2026-10-10` | **20000** | `MATCHED` |
    | `Asia/Jakarta` | `2026-10-12T23:30Z` | `2026-10-12` | **0** | `DISCREPANCY` + `OPEN` recon record |
    | `Asia/Jakarta` | `2026-10-12T23:30Z` | `2026-10-13` | **20000** | `MATCHED` |

    The first row is the old defect's exact scenario: with `TZ=Asia/Jakarta` the previous code cast
    that `paid_at` to the 11th and would have reported `expected_amount = 0` → `DISCREPANCY` on a
    settlement that in fact matched. The second and third rows show the boundary now moving purely
    with the property while the host stays fixed — and the second also shows the operational harm
    the old host-dependence could cause in silence.
  - **Incidental confirmation**: an attempt to delete a `SETTLEMENT` ledger row while resetting the
    reproduction was refused by the database — `ledger_entry is append-only (Section 22.21) — DELETE
    is not permitted`. The trigger from `V10` doing its job, and the whole statement rolled back
    with it.
  - **Gap, not built**: the two zones are independent settings on the two systems (PPOB2's
    `ppob2.business-zone`, PPOB1's `ppob1.business-zone`). They must be set to the same value and
    nothing enforces the pairing across the wire — it cannot be, since these are two companies in
    this architecture. Both default to UTC so they agree out of the box.

- **`metadata.reseller_txn_id` is stored, indexed and looked up, 2026-10-10** (`V23`, `V24`,
  `AdminOrderController`). Closes the `metadata` finding recorded in the PPOB1 entry above. The
  field had been bound by `CreateOrderController`'s request record since the first slice and passed
  nowhere; `parent_order` had no column for it.
  - **A generic `metadata jsonb` column, not a `reseller_txn_id` column.** Section 23.4 sends an
    object, and the narrow version would have to grow a column per key — until it did, every other
    key would still be accepted and dropped, which is the same gap in a smaller shape. It would also
    freeze one partner's naming convention into PPOB2's schema. The speed the dedicated column would
    have bought back comes from a functional index, `parent_order_reseller_txn_idx ON parent_order
    ((metadata ->> 'reseller_txn_id'))`, and `run-core.sh` asserts the planner actually uses it —
    a functional index only serves the expression it was declared with, so a drift between the
    migration and the repository query would degrade to a sequential scan of every order ever
    placed, silently.
  - **Deliberately not a unique index.** `reseller_txn_id` is the reseller's own counter: unique
    within one reseller at best, guaranteed by nothing PPOB2 can see. A unique index would let one
    partner's choice of string make PPOB2 **reject another partner's legitimate order** — refusing
    money over a third party's annotation, as an opaque constraint violation inside the create-order
    path. PPOB2's duplicate protection for this already exists and is the right one:
    `parent_order_idem_uk UNIQUE (client_id, idempotency_key)`, scoped per client, and PPOB1 sends
    `reseller_txn_id` as its `Idempotency-Key` precisely so that key does the deduplication. The
    consequence is handled rather than ignored: the lookup is one-to-many, so it returns a list and
    names each match's partner, and a unit test covers the collision because the realistic fixture
    has one partner and would never reach it.
  - **Storing is not the same as accepting.** Before `V23` the field went nowhere, so its size cost
    nothing; now it is durable storage whose size the partner chooses, on a table every settlement
    and reconciliation query reads. Bounded at 2000 characters — fifty times Section 23.4's own
    example — and checked explicitly in the controller rather than with a `@Size`, for the reason in
    the next bullet.
  - **A second defect, found by fixing the first: `@Valid` was missing on `POST /api/v1/orders`.**
    `CreateOrderRequest` has carried `@NotBlank`/`@NotNull` since the first slice and Spring had
    never run either — the annotations were decorative. A body omitting `parent_amount` reached
    `Money.of(null)` and came back **`500 INTERNAL_ERROR`**, a status Section 23.1 tells partners to
    retry, for a request no retry can fix. Fixed rather than filed because this slice adds a
    hand-checked bound to the same record, and a hand-checked bound above three unenforced
    annotations is worse than either one alone.

    The `500` is observed, not deduced from reading `Money.of`. Reproducible in a minute: delete the
    `@Valid`, run `CreateOrderControllerTest.aMissingParentAmountIsRejectedAsValidationNotAsAServerError`,
    and it fails with `Status expected:<400> but was:<500>`. Restore it and the same test passes.
  - **And a third, caused by fixing the second.** Turning on `@Valid` made a
    `GlobalExceptionHandler` branch reachable for the first time — `MethodArgumentNotValidException`
    was handled by the group handler that echoes `ex.getMessage()`. The first request to reach it
    answered with the controller class name, its generic return type, its full parameter list, and
    the rejected value quoted back:

    ```
    Validation failed for argument [0] in public org.springframework.http.ResponseEntity<
    id.ppob2.app.web.CreateOrderController$CreateOrderResponse> id.ppob2.app.web
    .CreateOrderController.createOrder(...): [Field error in object 'createOrderRequest' on
    field 'productCode': rejected value [  ]; codes [NotBlank...]]
    ```

    That publishes this service's package layout to whoever sent a bad request, and echoes partner
    payload content — the exact thing `handleUnreadableBody` already refuses to do, for the same
    stated reason. Split into its own handler, which reports through the envelope's `details` list
    (Section 50.1's `field`/`issue` pair, until now populated by nothing in this codebase) with
    field names translated through the app's own configured Jackson naming strategy, so a partner is
    told about `parent_amount` and not `parentAmount`:

    ```json
    {"error_code":"VALIDATION_ERROR","message":"Request body failed validation.",
     "details":[{"field":"parent_amount","issue":"must not be null"}], ...}
    ```

    Asserted as an *absence* in both the unit test and `run-core.sh` — the envelope looking right
    does not prove the leak is gone.
  - **Idempotency: `metadata` takes no part in the conflict check, following the precedent
    `callback_url` set.** `V16` added that column and left `ParentOrder.matchesRequest` alone; this
    does the same. The line both sit on the far side of is what the order *is* — product, amount, who
    it is for — versus how the caller annotated it. A `409` is the one answer a client cannot recover
    from without changing its own key, so it is reserved for requests that are genuinely different
    orders. The corollary, which was first-write-wins by omission and is now written down and
    asserted: a replay carrying different metadata is a replay, and the stored value stays whatever
    the first accepted request set.
  - **`order:view`, and VIEWER gets it** — the opposite of what `V22` decided for
    `reconciliation:view` two migrations earlier, so the divergence is argued in `V24` rather than
    left looking like an inconsistency. `V22`'s deciding sentence was "this endpoint is not a
    transaction lookup … 'can see every open financial exposure' is a Finance-shaped grant that
    happens to be implemented as a read." That sentence argues the other way here: this endpoint *is*
    a transaction lookup — the literal phrase Section 42.1 uses for `CUSTOMER_SERVICE`, the most
    junior role with any read scope — and it resolves one id the partner already holds into the order
    it already placed. Withheld from `FINANCE`, `RECONCILIATION` and `TECH_SUPPORT`, whose Section
    42.1 scopes name modules rather than transactions; revisit when `TC-ADM-007`'s drilldown is
    built, since a reconciliation row points at an order with no way to open it.
  - **Not `TC-ADM-006`**, which stays **X**. That test case is Admin Web transaction search: many
    filters, paging, a result grid. `GET /admin/orders` here has exactly one filter and *requires*
    it. It takes that path because extending it later beats replacing it, and the required parameter
    is what keeps today's version honest — without it the endpoint would mean "every order on the
    platform, newest first", with no partner scope, which is a far larger decision than this one.
  - **Not one line of PPOB1 changed.** `Ppob2Client` has sent `metadata.reseller_txn_id` since it
    was written; the gap was entirely on this side of the wire. `run-ppob1.sh`'s two assertions were
    flipped from stating the gap to stating the round trip, and its idempotency case can now count by
    the reseller's own id instead of routing around the missing column through `customer_reference`.
  - **Verified**: 189 unit tests, `run-core.sh` 177/177 (was 148), `run-routing-and-sweeps.sh`
    13/13, `run-ppob1.sh` 99/99, `run-refund-gateway.sh` 15/15 — the last one re-run this time
    rather than reasoned about, because every e2e script drives the order-creation path this slice
    changes.

- **The callback carries the reseller's own id, 2026-10-10** (`OutboundWebhookSender.payload`,
  `ppob1-sim`'s `WebhookIngestService`). Closes the Section 23.8 gap the `V23` entry above named as
  still open.
  - **What it is for.** With `order_id` alone, acting on a callback requires the partner to have
    successfully stored *our* order number. A partner whose books lost it — for any reason — receives
    a correctly signed message about one of its own sales and can do nothing with it:
    `WebhookVerdict.UNKNOWN_ORDER`, recorded and set aside. Echoing back the id the partner itself
    chose removes that dependency, and lets a partner key its whole callback handling on its own
    reference rather than ours.
  - **The recovery it enables, and the headline this entry originally claimed and should not
    have.** The first draft said this makes "the orphaned submit recover itself". That is wrong, and
    tracing it is worth recording because the mistake is the natural reading. When a create response
    is lost, PPOB1 stores **no `qr_payload`** (only its `acceptedByPpob2` sets one), so no customer
    ever pays, so the upstream order is swept to `EXPIRED` — and `EXPIRED` fires no Section 23.8
    callback at all. Confirmed from two independent places: `sendOrderStatusChanged` has exactly one
    caller (`FulfillmentDispatchListener`, post-dispatch terminal states), and
    `WebhookDeliveryTargetResolver` separately refuses every state but SUCCESS / PARTIAL_FAILED /
    FAILED. **The ordinary lost-response orphan expires unpaid, produces no callback, and still needs
    the recon-plus-re-submit remedy.** (True as written on the day of this entry. The 2026-10-10
    entry below widens the trigger to `EXPIRED` for exactly this reason, which is what finally
    brings that orphan into reach.)

    What the recovery does reach is the cases where PPOB1 holds no id for an order that is
    nonetheless *live*, so a callback actually comes:
    - **A `REJECTED` row whose order exists anyway** — a `409 IDEMPOTENCY_KEY_CONFLICT` is the one
      refusal PPOB2 issues while an order for that key does exist. This is the strongest case for
      the whole mechanism, not an afterthought: `ReconciliationService` excludes `REJECTED` from its
      working set, so recon will never look at that row again and the callback is the only thing
      left that can correct it.
    - **A row whose id was lost after the order went live** — a failed outcome write, a restored
      backup, an operational repair. PPOB1 kept the sale and lost the pointer.
  - **An additive extension of a documented body.** Section 23.8's example has five fields; the
    callback now sends a sixth, `reseller_txn_id`, so a partner parsing only the documented five is
    unaffected. Recorded as a deliberate contract change in the sender's Javadoc rather than left
    for a reader to discover against the PRD.
  - **Only that one key, never the whole `metadata` object.** The column holds whatever a partner
    chose to put there; this body is persisted on *both* sides of the wire (`webhook_event` here,
    `ppob1_inbound_webhook` there) and is in flight to a URL the partner supplied. Echoing the lot
    would copy any incidental personal data a partner annotates orders with into two more places.
  - **Read back out of the column by SQL, not remembered from the create request**, because a retry
    sweep may send the callback hours later in a different process — and by the same `->>` expression
    the admin lookup matches on (`ParentOrderRepository.RESELLER_TXN_ID`, now a single constant both
    queries share). That is correctness, not tidiness: `JsonNode.asText()` on an object node returns
    the empty string, so a partner sending a non-string would get a callback echoing something the
    lookup could never find.
  - **The guard is what makes matching on a reseller's id safe, and it is not defensive
    boilerplate.** That id is unique in PPOB1 but *not* necessarily upstream:
    `parent_order_idem_uk` is `(client_id, idempotency_key)` and Section 22.3 lets one partner hold
    several `api_client` rows, so the same reseller id sent through two of them produces two
    distinct orders. Adopting blindly would overwrite the order PPOB1 is tracking — one silent
    write, two orders, one record. Hence `WebhookVerdict.ORDER_ID_CONFLICT`, which applies nothing,
    and `Ppob1Transaction.adoptPpob2OrderId` throwing if an id is already held, so a future caller
    cannot bypass the service-level check.
  - **Guarded on `ppob2_order_id IS NULL`, not on the local state.** The orphan (`SUBMITTING`) is
    the case worth building for; `REJECTED` also qualifies and is narrower but real, since a
    `409 IDEMPOTENCY_KEY_CONFLICT` is the one refusal PPOB2 issues while an order for that key does
    exist. It matters more than its frequency suggests because `ReconciliationService` excludes
    `REJECTED` from its working set — recon will never look at that row again, so the callback is
    the only thing that can correct it. Such a row keeps its `rejection_code`: a `COMPLETED`
    transaction still carrying one is the entire finding, and clearing it would make the row
    indistinguishable from an ordinary recovery.
  - **The verdict is read only after the signature verifies**, which is what makes it safe to act
    on at all — it is an authenticated echo of PPOB1's own id, not a lookup key a caller chooses.
    Otherwise anyone who could reach the endpoint could attach an order id of their choosing to any
    open sale. Asserted directly: a forged-signature callback carrying a valid reseller id adopts
    nothing and never reaches the second lookup.
  - **Driven as a real round trip, with PPOB2's own row as the oracle.** `run-ppob1.sh` submits,
    stages a *live* order whose id PPOB1 then lost (not a lost create response — see above), pays,
    and lets PPOB2 build and sign the callback itself. It then asserts
    `webhook_event.payload->>'reseller_txn_id'` on *PPOB2's* side before asserting anything about
    PPOB1's, so the test cannot pass on PPOB1 inventing the match. The same sequence returned
    `UNKNOWN_ORDER` before this change, so the verdict assertion discriminates. The
    `ORDER_ID_CONFLICT` case goes through `wh.sh`, which gained an optional `RESELLER_TXN_ID` to
    keep its "mirrors `OutboundWebhookSender` exactly" claim true: two upstream orders for one
    reseller id needs a second `api_client`, which the fixture does not have.
  - **A harness bug caught by running it, and it pointed at the scope error above.** The first
    version of "recon now agrees instead of reporting an orphan" counted `ORPHANED_SUBMIT` across
    the whole run and failed — correctly, as it turned out: `E2E-ORPHAN-1` earlier in the script has
    no upstream order at all, so no callback will ever come for it and it stays an orphan forever.
    Rescoped to the recovered transaction's own recon entry (`MATCH`), plus an assertion that that
    orphan *is* still flagged in the same run — which both proves the detector still works and
    states the scope in the harness itself: the callback recovers a lost pointer, not every orphan.
  - **`OutboundWebhookSender` had no unit test at all**, which is why the case that matters most
    here was previously undriveable by any means: an order with no metadata must send *no such key*,
    and every order the harness places carries one. Payload construction is now a package-private
    method so the shape can be asserted without a `WebClient`. The trap it pins: Jackson's
    `NON_NULL` inclusion does not apply to map entries, and this class deliberately uses its own
    bare `ObjectMapper`, so `put(key, null)` would have serialized `"reseller_txn_id":null` — a key
    asserting the reseller has no id.
  - **Gap, and it is what bounds everything above**: callbacks fire only on post-dispatch terminal
    states (SUCCESS / PARTIAL_FAILED / FAILED). `CANCELLED` and `EXPIRED` send nothing — and
    `EXPIRED` is not an edge case here, it is the **default outcome** of a lost create response,
    since PPOB1 holds no QR for such an order and nobody can pay it. Widening the trigger to
    `EXPIRED` would let the callback reach the ordinary orphan too; that is a Section 23.8 scope
    decision, not part of this slice. **Closed by the entry below**, which is the follow-up this
    bullet asked for.
  - **Verified**: 193 PPOB2 unit tests (was 189) and 64 PPOB1 (was 56), `run-core.sh` 177/177,
    `run-routing-and-sweeps.sh` 13/13, `run-ppob1.sh` 113/113 (was 99), `run-refund-gateway.sh`
    15/15 — all five on their own clean boots against a wiped schema, since both systems change here.

- **`EXPIRED` now notifies the partner, 2026-10-10** (`OrderExpiredEvent`,
  `OrderExpiredWebhookListener`, `V25`). Section 48.3's diagram shows an `ORDER_STATUS_CHANGED`
  webhook beside the QR-expiry state change; this codebase had shown that half as deliberately
  not-built since the webhook slice. It is the follow-up the entry above asked for, and it is what
  makes the reseller-id echo reach the case it was wanted for.
  - **Why `EXPIRED` and not "all the remaining states".** An expiry is the *default outcome of a
    lost create response*: the reseller's write of our `order_id` never lands, so it has no QR to
    show anybody, so nobody pays, so the sweep expires the order. That reseller is holding a sale it
    cannot name against an order it cannot see, and with no callback for `EXPIRED` there was nothing
    that could ever tell it otherwise — its only route back was a human reading a reconciliation
    report. `CANCELLED` is still deliberately absent, and not for symmetry: a cancellation is the
    partner's own request (Section 23.7), so there is no state it could learn from being told.
    `REFUNDED`/`REFUND_PENDING` stay absent too — admin-driven, no partner-facing contract yet.
  - **It queues the delivery instead of sending it, and that is the main design decision here.**
    `FulfillmentDispatchListener` sends synchronously and schedules a retry only on failure. Copying
    that would have been wrong, because of the *call context* rather than the message:
    `expirePaymentPending` runs per-order inside `QrExpirySweepJob`'s loop over up to 200 orders, so
    an `AFTER_COMMIT` send puts up to 200 sequential HTTP calls in one sweep tick, on the scheduler
    thread. Against a reachable partner that is milliseconds each; against an unreachable one it is
    200 × the sender's 5s timeout — roughly **seventeen minutes during which no further orders
    expire**. And the failure correlates with the harm: a partner being down is exactly when a
    backlog of unpaid orders is likeliest.

    That contention is not hypothetical — `application.yml` already sets
    `spring.task.scheduling.pool.size: 2` *specifically* so a slow webhook batch in
    `WebhookRetrySweepJob` cannot delay `QrExpirySweepJob`. Sending from inside the expiry sweep
    would move the same problem inside the thread that mitigation protects, where a second thread
    does not help. So the listener hands the delivery to `WebhookDeliveryTracker` and
    `WebhookRetrySweepJob` makes every attempt including the first, on its own thread, in its own
    bounded batch. The cost is up to one sweep interval (60s) of latency on a notification nothing
    waits on in real time. That trade is the argument: a minute of delay on an expiry notice against
    the expiry sweep itself stalling.
  - **`V25` exists because of that choice.** `webhook_delivery` was built for one shape — a delivery
    whose synchronous first attempt had already failed — so `attempt_count` started at `1` and
    `last_attempt_at` was `NOT NULL`. A never-attempted delivery has neither. Reusing the existing
    constructor would have claimed an attempt nothing made, costing every expiry callback one of its
    five attempts, and put a timestamp in `last_attempt_at` that nothing attempted at. So: a second
    constructor with `attempt_count = 0`, and `DROP NOT NULL` on that column so
    `attempt_count = 0 AND last_attempt_at IS NULL` is expressible rather than faked. Both origins
    still reach five total attempts (1 sync + 4 swept, or 0 + 5 swept) through the same unbranched
    arithmetic, which is asserted at the exhaustion boundary from both starting counts.
  - **The guard that stops this flooding the table.** Most expired orders have no `callback_url` at
    all — `run-core.sh`'s and `run-routing-and-sweeps.sh`'s orders send none, and Section 22.15
    permits a null `partner_id` for future consumer channels. The listener resolves the target first
    (a read, no HTTP) and schedules only if there is one. Without that, every such order would get a
    `webhook_delivery` row the sweep then retries five times over ~23 hours against nothing, since
    it counts `noTarget` as a failed attempt. `run-routing-and-sweeps.sh` asserts the absence
    directly on TC-BE-007's own expired order.
  - **The listener never throws — and the reason I first wrote down was wrong.** The natural
    justification is "an exception would abort the sweep's batch". Checked against spring-tx
    6.1.13's bytecode instead of assumed, and it is false:
    `TransactionSynchronizationUtils.invokeAfterCompletion` wraps every synchronization in
    `catch (Throwable)`, logs `"TransactionSynchronization.afterCompletion threw exception"`, and
    carries on — so the loop never sees it and the batch expires either way.

    The catch is still worth having, for two smaller reasons: a log line that names the order and
    says the order *is* expired and only the notification was lost (the framework's names neither),
    and independence from a framework detail that is phase-specific — move the listener to
    `BEFORE_COMMIT`, which looks like the safer choice, and a throw *would* propagate and roll back
    the expiry itself. Two tests pin it (a resolver failure and a duplicate-key failure).
  - **One gate, mirrored on both sides.** The resolver's state check became
    `NOTIFIABLE_STATES`, and `ppob1-sim`'s `ReconciliationService.PUSHED_BY_PPOB2` is its mirror;
    they must agree or `WEBHOOK_NEVER_ARRIVED` becomes either blind or pure noise. `EXPIRED` was
    added to both. The discriminating test is an `EXPIRED` learned by *inquiry* — one learned by
    webhook is `MATCH` either way, so only the inquiry case can tell the two configurations apart.
  - **A side effect named rather than discovered later**: because the delivery is queued, there is a
    window of up to a minute after an expiry in which a PPOB1 recon run reports
    `WEBHOOK_NEVER_ARRIVED` that is *correct about the facts and wrong about the cause* — the
    callback is merely still in flight. That failure class already existed for a delivery awaiting
    retry; this widens the window. Recorded on `ReconVerdict.WEBHOOK_NEVER_ARRIVED`.
  - **Still out of reach, so the scope claim stays honest**: an orphan whose upstream order stayed
    `CREATED` (PPOB2 answered `PG_UNAVAILABLE`, so it never reached `PAYMENT_PENDING`, is never
    swept and never expires), and one whose submit never arrived at all. Both still need the
    recon-plus-re-submit remedy, and `run-ppob1.sh` asserts the second stays an orphan in the same
    run that asserts the first case recovering.
  - **Driven end to end**: `run-ppob1.sh` stages the real lost create response — including the
    **null `qr_payload`**, which is the field that makes such an order unpayable and therefore the
    one a weaker fixture would quietly keep — forces the TTL past, and waits out both sweeps. It
    asserts PPOB2's own `webhook_event` payload (`state=EXPIRED` plus the reseller id), that the
    delivery went through `webhook_delivery` with `attempt_count = 1` rather than inline, then
    PPOB1's adoption, `EXPIRED` state, `WEBHOOK` source, `RECOVERED_BY_RESELLER_ID` verdict, and the
    recon entry turning `MATCH`.
  - **Verified**: 203 PPOB2 unit tests (was 193) and 65 PPOB1 (was 64), `run-core.sh` 177/177,
    `run-routing-and-sweeps.sh` 15/15 (was 13), `run-ppob1.sh` 126/126 (was 113),
    `run-refund-gateway.sh` 15/15.

- **2026-10-22 - Backoffice read APIs (Sections 41.2, 41.7, 41.12).** `GET /admin/parent-orders`
  (+ `/{id}`), `/admin/child-orders`, `/admin/payments` (+ `/{id}`), `/admin/payment-events`,
  `/admin/webhook-events`, `/admin/audit-logs`. All in `app/web`, reading through `app/admin`.
  - **Plain SQL with explicit column lists, not the JPA entities.** The entities these views span carry
    columns that must never reach a screen - `payment.qr_payload`, `provider_transaction.request_payload /
    response_payload`, `payment_event.raw_payload`, `webhook_event.payload`. A `SELECT` that never names
    them cannot leak them whatever a later edit adds to a DTO; it is also one statement per view rather
    than lazy-association N+1. Optional filters go through `SqlFilter`, which has no method that accepts a
    column or operator from a request - values are bind parameters only.
  - **`has_more` instead of a total.** Lists fetch `size + 1` rows; there is no `count(*)`. `size` is
    clamped to 100 and the clamped value is echoed.
  - **The timeline is derived, and says so (`"derived": true`).** There is no state-history table, so
    automatic transitions (`PAID -> FULFILLING -> SUCCESS`) are not individually timestamped anywhere.
    What is shown is what was actually recorded: creation, each payment callback, paid/refunded times,
    each webhook delivery attempt, every admin action in `audit_log` against the order or a child, and the
    last update. `ORDER_CREATED` is pinned first because `payment.paid_at` is stored to the second and on
    a fast payment sorts a few hundred ms *before* the order it paid for.
  - **Rejected callbacks are in `webhook_event`, not `payment_event`.** A first draft read
    `payment_event.signature_valid = false` for Section 41.7's "rejected / invalid-signature attempts" and
    the live data showed 22 rows, all valid: a callback whose signature fails never reaches
    `payment_event`; Section 22.24 logs it only in `webhook_event` with status `FAILED`. The security
    view is `/admin/webhook-events?status=FAILED`. Found by looking at the rows before trusting the column name.
  - **`actor_type` is `ADMIN_USER`, not `ADMIN`.** The username join used the wrong literal and would have
    returned a null `actor_username` for every row; caught by reading `audit_log` before writing the join.
  - **Redaction is a backstop.** `JsonRedactor` masks secret-looking keys (`*secret*`, `*password*`,
    `*hash*`, `*token*`, `*signature*`, `*api_key*`, `*otp*`) at any depth in audit states, order metadata
    and pattern components; unparseable JSON is replaced by `[unparseable]` rather than echoed. The order's
    `callback_url` is reduced to scheme://host:port, because a path or query can carry a token.
  - **`V26`** adds `payment:view` and `audit:view`, and grants `order:view` to FINANCE and RECONCILIATION -
    the grant `V24` said to make "the moment the parent/child drilldown exists". `payment:view` goes to
    OPERATIONS/FINANCE/RECONCILIATION (narrower than `order:view`: callback history is money-and-security
    data); `audit:view` is SUPER_ADMIN only (no Section 42.1 scope names the audit log). TECH_SUPPORT still
    has none of the three. `run-core.sh`'s "FINANCE lacks order:view" assertion encoded V24 and was
    inverted deliberately; the boundary it guarded is now TECH_SUPPORT.
  - **Type-mismatch leak closed.** `handleBadRequest` echoed `ex.getMessage()`, which for a bad typed
    filter is `Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long'; For input
    string: "abc"` and for a missing parameter names `method parameter type String`. Split into three
    handlers with fixed wording; `GlobalExceptionHandlerBadRequestTest` asserts on the real exception
    objects (their `getMessage()` is what used to be sent). Admin list endpoints made this easy to reach,
    which is why it was fixed here rather than left as the known-pre-existing item.
  - **Closes** `TC-ADM-006` (search by `customer_reference`) and `TC-ADM-007` (parent -> payment ->
    pattern -> child orders -> provider transactions in one response).
  - **Verified**: 213 PPOB2 unit tests (was 203); `run-core.sh` 178/178 (177 + the TECH_SUPPORT boundary assertion that replaced the FINANCE one); new `run-backoffice-read.sh`
    95/95 - RBAC matrix over five roles, paging/clamp/`has_more`, every filter against the database,
    bad-parameter 400s with an absence check for Java types, and planted secrets (callback URL token,
    metadata api key, audit `password_hash`) asserted *absent* from the response.

- **2026-10-22 - Admin sessions and TOTP MFA (Section 42.3).** `POST /admin/auth/login`,
  `/admin/auth/mfa/verify`, `/admin/auth/mfa/enroll`, `/admin/auth/mfa/confirm`, `/admin/auth/logout`,
  `GET /admin/auth/me`, `POST /admin/auth/sessions/revoke`. Logic in `admin/.../session`, HTTP in `app`.
  - **HTTP Basic is now OFF by default - and that is the point, not a side effect.** Basic carries no
    TOTP, so while it is accepted MFA is bypassed for whoever uses it, including the SUPER_ADMIN /
    FINANCE / RECONCILIATION accounts Section 42.3 makes MFA mandatory for. `ppob2.admin.basic-auth-enabled`
    (env `PPOB2_ADMIN_BASIC_AUTH_ENABLED`) turns it back on for the dev seed and the suites that
    authenticate with `curl -u`; booting with it on logs a WARN. `deploy/docker-compose.prod.yml` pins it
    `false`. `run-core.sh`, `run-refund-gateway.sh` and `run-backoffice-read.sh` now source
    `preflight-basic.sh`, which stops with the flag's name instead of letting a default boot produce dozens
    of unrelated-looking 401 failures.
  - **Opaque tokens in a table, not JWTs.** Section 42.3 requires sessions "revocable on logout /
    administrative action"; a JWT cannot be revoked without a server-side list, and then the list is the
    session store. Only the SHA-256 of a token is stored (`admin_session.token_hash`), so a database read
    yields nothing usable. Asserted: no row contains the raw token, one contains its hash.
  - **Idle (30 min) slides; absolute (8 h) never does.** Both are properties so `run-admin-auth.sh` can
    set 6 s / 15 s and watch them fire: a session used every 4 s for 12 s survives the idle limit, then
    dies at 15 s with `revoked_reason = EXPIRED_ABSOLUTE`. A password-only session lives 5 minutes.
  - **A pending session authenticates as `ROLE_PENDING_MFA` and nothing else**, carrying the account's
    identity but none of its permissions, so no URL rule can accidentally open data to it. **Promotion
    rotates the token** (old row revoked as `PROMOTED`): the credential that existed before the second
    factor was proven must not be the one that outlives it.
  - **TOTP is hand-written (RFC 6238, HMAC-SHA1)** and checked against the RFC's Appendix B vectors, all six.
    Replay: `admin_user.mfa_last_step` must be strictly exceeded, so a code seen once is dead for its
    whole window (asserted over HTTP: the same code on a second login -> 401). Attempts: five wrong codes
    revoke the session, after which even the right code is refused. The secret is AES-256-GCM encrypted at
    rest with a key from configuration (`ppob2.admin.mfa.encryption-key`), never stored plain; with no key,
    enrolment is refused (503) rather than falling back to plaintext.
  - **Mandatory MFA is a login state, not a flag.** A SUPER_ADMIN/FINANCE/RECONCILIATION account with no
    enrolment gets `MFA_ENROLLMENT_REQUIRED`: the session may enrol and nothing else, and `mfa_enabled` is set
    only after a confirming code. Re-enrolling an enrolled account is a 409: replacing the second factor
    from a live session would let a hijacked session lock the real owner out.
  - **Cookie vs Bearer.** The browser UI gets an `HttpOnly; SameSite=Strict` cookie and the token is not in
    the body unless `return_token: true`. A cookie is ambient, so state-changing requests carrying one need
    `X-CSRF-Token` equal to a value derived from the token; `Authorization: Bearer` is not ambient and is
    exempt. Spring's own CSRF filter stays off (it is session-based).
  - **Brute force.** Five wrong passwords lock the account for 15 minutes; a locked account answers the
    same generic 401 for the *right* password, so lockout is not an oracle. Unknown user and wrong
    password return the identical message and cost the same (a dummy BCrypt check).
  - **Audited**, per 42.3: failures still flow through Spring's failure event to the existing
    `AdminSecurityAuditListener`; new rows for `ADMIN_LOGIN_SUCCESS`, `ADMIN_MFA_CHALLENGE_SUCCESS/FAILED`,
    `ADMIN_MFA_ENROLLMENT_STARTED/FAILED`, `ADMIN_MFA_ENROLLED`, `ADMIN_LOGOUT`, `ADMIN_SESSIONS_REVOKED`.
    The submitted password, the TOTP secret and codes are asserted absent from `audit_log`.
  - **Found only by running it:** the first live enrolment answered "Start enrolment first" right after
    starting one. `Resolved` carries entities from the filter's transaction; with open-in-view off they
    are detached by the time a controller calls the service, and a change to a detached entity is silently
    not saved - the audit row was written, the secret was not. Every service method taking a `Resolved`
    now re-loads it inside its own transaction. The mocked-repository unit tests could not have seen it:
    a mock returns the same instance. `V27` also first used `CHAR(64)`, which Hibernate's schema validation
    rejects against a `String` (the same trap `V8` documents); it is `VARCHAR(64)`.
  - **A bug an adversarial review found after everything was green**: reloading during the MFA step locked
    the user out of signing in again. `permitAll` only affects authorization - `AdminSessionAuthenticationFilter`
    still ran on `/admin/auth/login`, resolved the live pending cookie and demanded its CSRF header, which a
    freshly loaded page does not have; the user got a 403 until the pending session timed out (5 min).
    Reproduced first (the new `run-admin-auth.sh` case answered 403 against the old jar), then fixed by
    having the filter skip the login URL (login creates a session; it does not depend on one), and
    asserted at both levels: over HTTP, and in the browser as a reload mid-enrolment.
  - **Not done, on purpose:** MFA reset for a locked-out user (a DB operation, by design), per-IP rate
    limiting at the edge, and `Secure`-cookie behaviour is covered by config only (the e2e boots with
    `cookie-secure=false` because it runs over plain HTTP).
  - **Scheduler pool 2 -> 3.** `AdminSecurityStartupCheck.purge` (hourly `DELETE` of dead sessions) is a third
    `@Scheduled` job; `spring.task.scheduling.pool.size` is documented as one thread per job so the expiry
    sweep is never starved, so it was raised rather than left silently violated.
  - **`V27`** adds `admin_session`, four `admin_user` columns and the `session:revoke` permission
    (SUPER_ADMIN only, explicit grant per V19).
  - **Verified**: 238 PPOB2 unit tests (was 213) incl. RFC 6238 vectors and a clock-driven session state
    machine; new `run-admin-auth.sh` 101/101 on its own instance (Basic off, 6 s / 15 s / 8 s timeouts; 89 of
    those are session/MFA, 9 the UI's headers and `/me`, 3 the re-login-with-a-live-pending-cookie regression), run several times; `run-core.sh` 178/178, `run-backoffice-read.sh` 95/95 and `run-routing-and-sweeps.sh` 15/15 on a
    Basic-enabled boot.

- **2026-10-22 - Backoffice UI.** Static files under `app/src/main/resources/static/backoffice/`, served
  by the app itself at `/backoffice/`: sign-in (with MFA challenge and mandatory enrolment), orders (list,
  filters, detail with payment / pattern / children / deliveries / derived timeline), refund and child retry
  through dialogs that demand a reason, payments, webhook log (rejected callbacks), reconciliation
  (investigate / resolve), audit log, and session revocation. Sections are shown only if the account holds
  the permission, and the server checks again on every call.
  - **No build step, no dependencies, nothing loaded from another origin.** Plain ES modules; a Java build
    stays one JVM build and the Dockerfile did not change. A payment back-office should not execute a script
    or load a font from a CDN, so there is none.
  - **Same origin, so the cookie session just works** - and so the CSP can say `default-src 'none'; script-src
    'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'`, with no inline script or style
    anywhere. That header set (plus `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`) is applied by its
    own security filter chain for `/backoffice/**` and asserted by `run-admin-auth.sh`.
  - **It builds the DOM, never HTML.** `dom.js` has no `innerHTML` path; partner-controlled text (customer
    reference, order metadata, callback host) goes in as `textContent`. `ui-smoke.cjs` plants
    `<img src=x onerror=window.__xss=1>` as a customer reference and asserts it is shown as literal text, ran no
    code, and created no element.
  - **The session token never touches script.** It is an `HttpOnly` cookie. After a reload the page has no way
    to compute its CSRF value, so `GET /admin/auth/me` now returns it (safe: a cross-site page can neither
    send the cookie under `SameSite=Strict` nor read the response).
  - **Found by looking, not by asserting:** a screenshot showed the literal text "null" under the delivery
    table. The page used the DOM's native `append`, which renders a `null` argument as the string "null", for
    every optional section (`cond ? node : null`); `dom.js` now has `put`, and `ui-smoke.cjs` asserts no stray
    `null` / `undefined` / `[object` text on each screen. That assertion was shown to discriminate by
    restoring the bug and watching it fail. A second bug the first browser run found: `login.js` called a
    function that used a `const` declared further down (a temporal-dead-zone error) and rendered nothing at
    all - invisible to every HTTP-level test.
  - **Driven in a real browser**: `scripts/e2e/ui-smoke.cjs` (Playwright + the preinstalled Chromium), 46
    assertions: sign-in failure, MFA enrolment with a TOTP computed in the test, filtering (state lives in the
    URL), drill-down, reload keeps the session, a refund through its dialog (empty reason refused client-side,
    one audit row with the typed reason against the right admin), investigate, the rejected-callback view,
    the audit log, sign-out invalidating the cookie, and no console errors beyond the 401s the run causes
    itself. Dark mode and a 390px viewport were looked at in screenshots, not asserted.
  - **Not built, deliberately:** catalog / pricing / configuration editing (Sections 41.3-41.6, 41.11), the
    dashboard (41.1), and reconciliation resolution notes. This is a console over the read APIs and the admin
    actions that already existed, not the whole of Section 41.
  - **Not verified:** the Docker image (Docker Hub rate-limited every pull in this environment) and Caddy's
    handling of the `/backoffice` allow-list and the `redir / /backoffice/` line.
  - **Verified, whole repository, from an empty database in the documented order**: 238 PPOB2 and 65 PPOB1
    unit tests; `run-core.sh` 178/178, `run-backoffice-read.sh` 95/95, `run-routing-and-sweeps.sh` 15/15
    (Basic-enabled boot); `ui-smoke.cjs` 46/46 and `run-admin-auth.sh` 101/101 (Basic off, packaged jar);
    `run-refund-gateway.sh` 15/15 (own boot); `run-ppob1.sh` 126/126 (both systems live).

## Running locally

The short version is in the [root README's quickstart](../README.md#quickstart); this section adds
what that one omits — signing a partner request by hand, simulating a payment callback, and the
dev-only failure-injection knobs.

```bash
docker compose up -d          # starts Postgres on localhost:5432
./gradlew :app:bootRun
```

The app validates its schema against Flyway migrations (`spring.jpa.hibernate.ddl-auto: validate`)
rather than letting Hibernate generate DDL, per Section 22's explicit column types
(`NUMERIC(18,0)`, `TIMESTAMPTZ`, `GENERATED ALWAYS AS IDENTITY`).

There is no automatic seed data. One runnable fixture covers everything the test matrices below
need — channel / partner / api_client / product / supported_amount, four provider SKUs (one per
`StubGameProviderAdapter` outcome), their mandatory `provider_price` rows, six decomposition
patterns with economics, and an Admin Web user:

```bash
docker exec -i backend-postgres-1 psql -U ppob2 -d ppob2 -v ON_ERROR_STOP=1 -1 \
  < scripts/e2e/dev-seed.sql
```

It resolves every id by lookup instead of hardcoding, and it lives under `scripts/` rather than
`src/main/resources/` on purpose — it briefly did live there, which packaged a `SUPER_ADMIN` row with
a known password hash into the production bootJar. Flyway would not have run it, but that is one
config property away from being the only thing that stopped it.

It is a file rather than a README snippet on purpose too: **the snippet that used to live here had silently rotted in two ways**, both
found on 2026-09-27 by running it verbatim against a fresh database.

- It inserted into `provider (provider_code, ...)`. That column has never existed — it is `code`
  (`V7__catalog_provider_and_sku.sql`). The statement simply errored out.
- It predated `V13__provider_price.sql`, and never inserted a `provider_price` row. Without one,
  `FulfillmentDispatchListener` refuses to dispatch (`"references provider_sku N with no active
  provider_price — flagging unresolvable rather than dispatching with an unknown cost"`), so every
  child order went straight to `FAILED` and the parent to `FAILED` — while this README told the
  reader to expect `SUCCESS`. The app's behaviour was correct; the documentation was not.

The seeded `api_client.secret_hash` holds the HMAC secret verbatim, not a hash — see
`HmacAuthenticationFilter`'s NOTE. That is a flagged open assumption (Section 73.3) and a real
go-live blocker, not a property of this fixture.

Then sign a request per Section 23.2 (`HmacSigner` in `shared-kernel` implements the exact
canonical string) and call:

```
GET /api/v1/config/supported-amounts?product_code=MOBILE_LEGENDS
```

or create an order (also needs an `Idempotency-Key` header, Section 23.1):

```
POST /api/v1/orders
{"product_code":"MOBILE_LEGENDS","parent_amount":20000,"customer_reference":"GAMEID-123456"}
```

then simulate Ayolinx confirming that payment (this exercises `PaymentCallbackService` against
`StubQrisPaymentGateway`'s local-testing signature scheme — the default in every environment that's
been exercised so far; it is not the real `AyolinxPaymentGateway`'s RSA scheme, see above) — look
up `payment.pg_reference` for the order you just created (with the real gateway this equals the
order's own `order_no`; with the stub it's a synthetic `STUB-<uuid>`), sign the body with
`dev-webhook-secret` (or `PPOB2_AYOLINX_WEBHOOK_SECRET` if you set one) the same way `HmacSigner`
does, and call — note the real (nested, camelCase) Ayolinx notify shape, not the old flat one:

```
POST /internal/webhooks/ayolinx
X-Ayolinx-Signature: <hex HMAC-SHA256(secret, raw body)>
{
  "callbackType": "QRIS",
  "additionalInfo": {"channel": "BNC_QRIS"},
  "amount": {"currency": "IDR", "value": "20000.00"},
  "latestTransactionStatus": "00",
  "originalPartnerReferenceNo": "<payment.pg_reference from the row above>",
  "originalReferenceNo": "AYO-TEST-REF-1",
  "finishedTime": "2026-09-12T03:15:00+00:00"
}
```

The response body is `{"responseCode":"2005600","responseMessage":"Successful"}` on success (or a
`4xx` + JSON body on rejection) — Ayolinx requires a JSON body on every response, not just a status
code, and retries an unacknowledged one up to 4× over 1h.

The order should move to `PAID`, then — since no decomposition pattern is seeded yet — on to
`REFUND_PENDING` (BR-DEC exhaustion). To see it reach `DECOMPOSITION_SELECTED` instead, seed a
matching pattern first (note the components must sum exactly to `parent_amount`, per Section
28.2 — this is now enforced at runtime, see above):

`dev-seed.sql` above already seeds all of this (pattern `a`: `20000 -> [ML-20000-A x1]`), plus a
`provider_price` row per SKU without which dispatch refuses to run at all.

Once `DECOMPOSITION_SELECTED` is reached, fulfillment dispatch begins automatically (Section 33.2)
and — with `StubGameProviderAdapter` (`@Profile("!prod")`) as the only `GameProvider` — every
child order succeeds by default, taking the order to `SUCCESS`. To see `PARTIAL_FAILED`/`FAILED`
instead, seed a **2-component** pattern (so one child order can fail while the other succeeds) and
start `bootRun` with the dev-only failure-injection property, keyed by `provider_sku_id`:

```sql
INSERT INTO provider_sku (provider_id, product_id, provider_sku_code, face_value, status)
  VALUES (1, 1, 'ML-20000-B', 20000, 'ACTIVE'); -- id 2, alongside provider_sku id 1 above
INSERT INTO decomposition_pattern (generation_id, parent_amount, components, component_count, total_quantity, pattern_hash)
  VALUES (1, 40000,
    '[{"provider_sku_id":1,"quantity":1,"face_value":20000},{"provider_sku_id":2,"quantity":1,"face_value":20000}]',
    2, 2, repeat('b', 64)); -- id 3
INSERT INTO decomposition_component (pattern_id, provider_sku_id, quantity, face_value) VALUES
  (3, 1, 1, 20000), (3, 2, 1, 20000);
INSERT INTO pattern_economics (pattern_id, snapshot_date, provider_cost_total, gross_profit, gross_margin_pct,
    payment_fee, net_contribution, net_margin_pct, score, eligible)
  VALUES (3, CURRENT_DATE, 36000, 4000, 10.0, 500, 3500, 8.75, 90.0, true);
INSERT INTO supported_amount (product_category, amount) VALUES ('GAME_TOPUP', 40000);
```

```bash
# Relaxed binding strips hyphens from property segments, not underscores between them — this
# exact env var name is the one that actually binds to ppob2.fulfillment.stub-provider.fail-provider-sku-ids.
# A naive all-underscores name (…STUB_PROVIDER_FAIL_PROVIDER_SKU_IDS) silently does NOT bind.
PPOB2_FULFILLMENT_STUBPROVIDER_FAILPROVIDERSKUIDS=2 ./gradlew :app:bootRun
```

An order for `parent_amount: 40000` then reaches `PARTIAL_FAILED` (child order for provider_sku 1
`SUCCESS`, provider_sku 2 `FAILED`); setting the property to `1,2` instead produces `FAILED`.

The same relaxed-binding rule applies to the inquiry-before-retry test knob:

```bash
PPOB2_FULFILLMENT_STUBPROVIDER_AMBIGUOUSPROVIDERSKUIDS=2 ./gradlew :app:bootRun
```

A child order for the configured provider_sku gets `PurchaseStatus.AMBIGUOUS` from every
`purchase()` call; `StubGameProviderAdapter.inquire()` always confirms `SUCCESS`, so the child
order still reaches `SUCCESS` — but via `FulfillmentExecutionService.resolveAmbiguous`, not a
direct purchase. Check `provider_transaction.provider_reference` for that child order: a
`STUBPROV-INQUIRY-...` value (not a plain `STUBPROV-...` one) confirms the inquiry path actually
ran.

The fourth knob is on the *payment* gateway rather than the provider, and it exists to make the
gateway-executed refund branch reachable at all — no real gateway answers `supportsRefund() == true`
(see the refund slice above):

```bash
PPOB2_PAYMENT_STUBGATEWAY_SUPPORTSREFUND=true ./gradlew :app:bootRun
```

With it set, `POST /admin/parent-orders/{id}/refund` calls the stub gateway and records its
`STUB-REFUND-...` reference, and supplying an `external_reference` is *refused* as ambiguous.
Without it (every normal boot, and every production configuration), the same endpoint *requires*
an `external_reference` and records the refund as performed out-of-band. `scripts/e2e/run-refund-gateway.sh`
asserts which mode is active before it tests anything, because against the wrong boot every one of
its cases would pass for the wrong reason.

Refunding by hand needs an order in `REFUND_PENDING`, which a paid order for an amount with no
seeded pattern reaches on its own (`10000` in `dev-seed.sql`):

```bash
curl -X POST http://localhost:8080/admin/parent-orders/24/refund \
  -u superadmin:admin123 -H "Content-Type: application/json" \
  -d '{"reason":"BR-DEC exhaustion, ticket OPS-7","external_reference":"BANK-TRANSFER-4421"}'
```

Then check that the payment ledger for that payment nets to zero — the reversing entry is a
positive-amount `DEBIT`, not a negative `CREDIT`:

```sql
SELECT entry_type, amount FROM ledger_entry
WHERE ledger_type = 'PAYMENT' AND reference_id = <payment_id> ORDER BY id;
```

The moment any order reaches `PAID` (regardless of what happens after), check the Payment Ledger
entry it should have produced:

```sql
SELECT ledger_type, reference_type, reference_id, entry_type, amount, currency, description
FROM ledger_entry ORDER BY id DESC LIMIT 1;
```

`ledger_entry` is append-only at the database level, not just by application convention — this
will reject even a superuser `UPDATE`/`DELETE`:

```sql
UPDATE ledger_entry SET amount = 1 WHERE id = 1; -- ERROR: ledger_entry is append-only ...
```

Once at least one payment has reached `PAID` on a given date, ingest a (invented-shape) settlement
report for that date — no auth required on this endpoint yet (see gaps):

```bash
curl -X POST http://localhost:8080/internal/settlement/ingest \
  -H "Content-Type: application/json" \
  -d '{"settlement_date":"2026-09-12","pg_reference":"AYOLINX-BATCH-1","actual_amount":20000,"fee_amount":500}'
```

`expected_amount` is computed server-side from the sum of that date's `SUCCESS` payments — passing
an `actual_amount` that doesn't match produces `status: "DISCREPANCY"` (and still posts a
Settlement Ledger entry for the reported amount, and opens a `PAYMENT_VS_SETTLEMENT`
`reconciliation` row in the same transaction — see below); ingesting a second report for a date
that already has one fails on the `UNIQUE(settlement_date)` constraint.

```sql
SELECT recon_type, recon_date, reference_id, expected_value, actual_value, discrepancy, status
FROM reconciliation ORDER BY id DESC LIMIT 1;
```

To see the QR expiry sweep without waiting out the real TTL, start with a short interval and force
an order's `expires_at` into the past:

```bash
PPOB2_ORDER_EXPIRYSWEEPINTERVALMS=5000 ./gradlew :app:bootRun
```

```sql
UPDATE parent_order SET expires_at = now() - interval '1 hour' WHERE order_no = 'ORD-...';
-- within ~5s: state -> EXPIRED, and the linked payment row -> EXPIRED too — but only if it was
-- still PAYMENT_PENDING; an order already PAID (or beyond) is left completely untouched.
```

To exercise Admin Web, seed an `admin_user` with a real BCrypt hash of a password you choose (there
is no signup endpoint — Section 41 doesn't specify one either, admin provisioning is presumably
out-of-band/DB-seeded). **Never commit a real admin credential pair or its hash** — generate your
own locally and keep it out of source control:

```bash
python -c "import bcrypt; print(bcrypt.hashpw(b'<your-password>', bcrypt.gensalt(rounds=10)).decode())"
```

```sql
INSERT INTO admin_user (username, password_hash) VALUES ('admin1', '<paste hash here>');
```

> **HTTP Basic is off by default** since Section 42.3's sessions landed (see the 2026-10-22 entry below).
> The `curl -u` examples here need the app booted with `PPOB2_ADMIN_BASIC_AUTH_ENABLED=true`, which also
> bypasses MFA - local development only. The real login is `POST /admin/auth/login`.

```bash
curl -X POST -u admin1:<your-password> http://localhost:8080/admin/reconciliations/1/investigate
curl -X POST -u admin1:<your-password> http://localhost:8080/admin/reconciliations/1/resolve
curl -X POST -u admin1:<your-password> http://localhost:8080/internal/settlement/ingest -H "Content-Type: application/json" -d '{"settlement_date":"2026-09-22","pg_reference":"BATCH-X","actual_amount":1000}'
curl -X POST -u admin1:<your-password> http://localhost:8080/admin/child-orders/4/retry
```

To exercise Section 42's RBAC (rather than just authentication), assign roles — a fresh
`admin_user` starts with **no** roles and is `403 PERMISSION_DENIED` on all four gated actions
above until you do:

```sql
INSERT INTO admin_user_role (admin_user_id, role_id) SELECT <admin_user id>, id FROM role WHERE code = 'OPERATIONS';       -- retry:execute only
INSERT INTO admin_user_role (admin_user_id, role_id) SELECT <admin_user id>, id FROM role WHERE code = 'SUPER_ADMIN';      -- all four permissions
```

**Dev note, not a credential to reuse**: this session's own end-to-end verification (documented
above under "Admin RBAC") rotated the `admin1` row's `password_hash` mid-session to test against a
locally-known password, and added a second row (`noroleadmin`, initially no roles, later granted
`SUPER_ADMIN`) using the same locally-known password — neither hash nor either plaintext password
is committed anywhere, and a future session picking up this same dev database will find passwords
neither it nor this file knows. Re-run the `bcrypt.hashpw` command above and
`UPDATE admin_user SET password_hash = '<new hash>' WHERE username = '<admin1|noroleadmin>'`
rather than guessing.

To exercise outbound webhook delivery, pass a `callback_url` when creating an order and point it at
a receiver you control (a real PPOB1 endpoint isn't available in dev):

```json
{"product_code":"MOBILE_LEGENDS","parent_amount":60000,"customer_reference":"GAMEID-1","callback_url":"http://localhost:9091/webhooks/ppob2"}
```

and seed that partner's signing secret (there is no Admin Web UI for this yet — Section 41
doesn't name one either):

```sql
UPDATE partner SET webhook_secret = 'dev-outbound-secret' WHERE code = 'PPOB1';
```

## Build & test

```bash
./gradlew build   # compiles every module, packages app/build/libs/app-*.jar (bootJar)
./gradlew test    # unit tests (Money, HmacSigner) + a WebMvcTest slice per endpoint
```

A green build is **not** evidence that the money paths work. Three of the bugs found on 2026-09-27
were invisible to this suite and would have stayed invisible: the Section 27.2 retry loop had never
run against real HTTP, a `FAILED`-after-`SUCCESS` callback was silently swallowed, and a
paid-but-`CANCELLED` order recorded nothing anywhere. The 2026-10-03 pass added a fourth of the
same kind — a paid order with no eligible pattern recorded nothing anywhere either — and the refund
slice added a fifth that a mocked test *would* have passed: a `@Transactional` self-invocation that
silently gave each write its own transaction. The end-to-end matrices run against a real app and a
real Postgres, and assert on the resulting rows:

```bash
scripts/e2e/run-core.sh                  # 90 assertions
scripts/e2e/run-routing-and-sweeps.sh    # 13 assertions (waits on the 60s expiry-sweep tick)
scripts/e2e/run-refund-gateway.sh        # 15 assertions; needs PPOB2_PAYMENT_STUBGATEWAY_SUPPORTSREFUND=true
```

See [`scripts/e2e/README.md`](scripts/e2e/README.md) for the prerequisites (fresh seed, the three
injection knobs and their exact env-var spelling) and for what these deliberately do not cover, and
[`docs/TEST-STATUS.md`](../docs/TEST-STATUS.md) for the per-ID status of all 54 PRD test cases.
