# PPOB2 Backend

Modular monolith scaffold for the PPOB2 Core Backend, per [`docs/PRD-PPOB2.md`](../docs/PRD-PPOB2.md).
Java 21, Spring Boot 3.3, Gradle (Kotlin DSL) multi-module build, PostgreSQL, Flyway.

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
    polling our Open API, not something we push). Trigger coverage is still partial: only the
    post-dispatch terminal states (`SUCCESS`/`PARTIAL_FAILED`/`FAILED`, fired from
    `FulfillmentDispatchListener` right after the reconciliation orchestrator) send a webhook;
    `CANCELLED`/`EXPIRED`/`REFUNDED` transitions and the QR expiry sweep's own state change
    (Section 48.3's diagram) do not, and are not covered by the retry-sweep slice either.
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
    "Admin RBAC" below. **Still not built**: Section 42.3's session management (JWT/idle+absolute
    timeout, revocation) — Basic Auth is stateless per-request instead; MFA.
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
  check. Section 42.3 (MFA, JWT/idle+absolute session timeout) is explicitly still out of scope —
  a separate, still-open gap, not silently folded into this one.
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
    either kind). **Not achievable and not claimed: any actual round-trip against Ayolinx's sandbox**
    — that needs the merchant registration + KYB step described above.

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
- **Load-balancing / tie-break (Section 31.4) is not implemented.** `RoutingService` picks the
  single highest-scored eligible pattern deterministically; it does not spread volume across
  providers on ties or near-ties, which Section 31.4 calls for to avoid concentrating volume on
  one provider.
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
- **No inquiry-before-retry for ambiguous failures** (Section 34.1) — `GameProvider.inquire()`
  exists on the interface for fidelity but nothing calls it. This slice's retry classification is
  binary (`TIMEOUT` retries, everything else is terminal); a real provider's "connection reset
  after the request was already sent" case needs the inquiry step to disambiguate before deciding
  whether to retry, which isn't built.
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
- No `ArchUnit` test enforces the Section 20.2 module graph at the package level — the Gradle
  project-dependency graph already enforces it at the module level, which is the larger risk, but
  a package-level rule (e.g. "no `@Entity` outside a module's `domain` package") is still worth
  adding.
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

## Running locally

```bash
docker compose up -d          # starts Postgres on localhost:5432
./gradlew :app:bootRun
```

The app validates its schema against Flyway migrations (`spring.jpa.hibernate.ddl-auto: validate`)
rather than letting Hibernate generate DDL, per Section 22's explicit column types
(`NUMERIC(18,0)`, `TIMESTAMPTZ`, `GENERATED ALWAYS AS IDENTITY`).

To exercise the one implemented endpoint you need a `channel` / `partner` / `api_client` /
`product` / `supported_amount` row — there is no seed data yet. Example (adjust the secret to
whatever you insert into `secret_hash` — see the gap noted above):

```sql
INSERT INTO channel (code, name) VALUES ('RESELLER_API', 'Reseller API');
INSERT INTO partner (code, name, channel_id, contract_ref) VALUES ('PPOB1', 'PPOB1', 1, NULL);
INSERT INTO api_client (client_id, partner_id, secret_hash) VALUES ('ppob1-client', 1, 'dev-secret');
INSERT INTO product (code, name, category) VALUES ('MOBILE_LEGENDS', 'Mobile Legends', 'GAME_TOPUP');
INSERT INTO supported_amount (product_category, amount) VALUES ('GAME_TOPUP', 10000), ('GAME_TOPUP', 20000);
```

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

```sql
INSERT INTO provider (provider_code, name, status, rate_limit_per_min) VALUES ('PROV1', 'Provider 1', 'ACTIVE', 8000);
INSERT INTO provider_sku (provider_id, product_id, provider_sku_code, face_value, status)
  VALUES (1, 1, 'ML-20000', 20000, 'ACTIVE');
INSERT INTO pattern_generation (status, triggered_by, scope, started_at, activated_at)
  VALUES ('ACTIVE', 'MANUAL', 'FULL', now(), now());
INSERT INTO decomposition_pattern (generation_id, parent_amount, components, component_count, total_quantity, pattern_hash)
  VALUES (1, 20000, '[{"provider_sku_id":1,"quantity":1,"face_value":20000}]', 1, 1, repeat('a', 64));
INSERT INTO decomposition_component (pattern_id, provider_sku_id, quantity, face_value) VALUES (1, 1, 1, 20000);
INSERT INTO pattern_economics (pattern_id, snapshot_date, provider_cost_total, gross_profit, gross_margin_pct,
    payment_fee, net_contribution, net_margin_pct, score, eligible)
  VALUES (1, CURRENT_DATE, 18000, 2000, 10.0, 500, 1500, 7.5, 90.0, true);
```

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
