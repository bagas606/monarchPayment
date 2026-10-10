# PPOB1 — the reseller side, simulated

PPOB1 is the channel partner on the other end of [PRD Section 23](../docs/PRD-PPOB2.md): it sells
top-ups to its own customers and buys them from PPOB2 over the Open API. The PRD names it
throughout — Section 23.8 is literally titled *"Outbound Webhook: PPOB2 → PPOB1"*, and the dev seed
creates `api_client` `ppob1-client` for it — but until now nothing in this repository played that
part. PPOB2's partner-facing surface was only ever driven by `backend/scripts/e2e/call.sh`, a shell
script that signs a request and asserts on the response.

This is the other half: a running system with **its own database, its own books, and its own idea of
what happened**, which then has to reconcile that against PPOB2's.

## Why it is a separate process and a separate database

Both are load-bearing, not tidiness.

- **Its own Gradle build** (not a module under `backend/`). PPOB1 is a different company's system
  here. Adding it to `backend/settings.gradle.kts` would put it inside the Section 20.2 module graph
  that `ArchUnitRulesTest` enforces, where it would either declare edges it has no business having
  or sit as an orphan module with every internal class on its compile classpath. The isolation is
  what guarantees PPOB1 can only reach PPOB2 the way a real reseller can: signed HTTP.
- **No dependency on `shared-kernel`**, and in particular **its own implementation of the Section
  23.2 signature** ([`Ppob2RequestSigner`](src/main/java/id/ppob1/sim/client/Ppob2RequestSigner.java)).
  Reusing PPOB2's `HmacSigner` would make every signature agree by construction: the tests would
  pass whether or not the scheme they exercise is the one the PRD publishes, because both sides
  would be wrong in the same direction. Two independent implementations can disagree, and that is
  the point. `Ppob2RequestSignerTest` pins the output against vectors computed with `openssl dgst`,
  by a tool that has never seen this codebase.
- **Its own Postgres database** (`ppob1`, not a schema inside `ppob2`). Reconciliation here is
  supposed to discover PPOB2's state over the wire. A shared database would let it cheat with a
  join, and then the one thing that can actually be wrong in production — the integration — would be
  the one thing never tested.

## What it does

| | |
|---|---|
| **Section 23.3–23.7** | Signs and calls all five Open API endpoints: supported amounts, create order, get order, get payment, cancel |
| **Section 23.8** | Hosts the `callback_url` and verifies PPOB2's signature independently, recording every callback including the refused ones |
| **Its own books** | `ppob1_transaction` holds PPOB1's *belief* about each sale, alongside PPOB2's last word, stored verbatim |
| **Reconciliation** | Compares its books against PPOB2's answers, transaction by transaction, repairs stale beliefs and reports what needs a human |
| **Gross-collected book** | Per business day, a census of what PPOB1 believes PPOB2 collected on the orders PPOB1 originated — frozen as a dated snapshot. One-sided by design; see below |

What it does **not** do yet: compare that book against PPOB2's own settlement figure. See
[Not built](#not-built-yet) — that half is blocked on a contract decision, not on code.

## Running it

Needs PPOB2 up first. From the repository root:

```bash
# 1. PPOB2 on :8080, seeded, with the injection knobs (see backend/scripts/e2e/README.md)
cd backend
PPOB2_FULFILLMENT_STUBPROVIDER_FAILPROVIDERSKUIDS=2 \
PPOB2_FULFILLMENT_STUBPROVIDER_AMBIGUOUSPROVIDERSKUIDS=3 \
PPOB2_FULFILLMENT_STUBPROVIDER_TIMEOUTPROVIDERSKUIDS=4 \
./gradlew :app:bootRun
psql -h localhost -U ppob2 -d ppob2 -f scripts/e2e/dev-seed.sql

# 2. PPOB1's database, then PPOB1 on :8081
createdb -h localhost -U ppob2 ppob1     # once
cd ../ppob1-sim && ./gradlew bootRun
```

### The seed gap this exposed

`backend/scripts/e2e/dev-seed.sql` created partner `PPOB1` and left `partner.webhook_secret`
**NULL** — V16 added the column and the fixture was never updated. PPOB2 handles that correctly:
`WebhookDeliveryTargetResolver` logs an error and skips delivery rather than signing with nothing.
So the consequence was entirely silent — **no outbound webhook was ever delivered, by any order, in
any environment that fixture builds.**

Which means PPOB2's entire Section 23.8 path had never once run end to end before this simulator
existed. The sender had unit tests; the harness had no receiver. Same profile as every money defect
this repository has found: compiles, has a mocked test, has never run.

The seed now sets it, so the step above is gone. That was checked rather than assumed to be safe
for `backend/`'s own assertions: a webhook is only delivered when the *order* carries a
`callback_url`, and `run-core.sh`'s `mkorder` never sends one, so those scripts produce no
`webhook_event` rows either way. Re-verified after the change — 148/148 core, 13/13 routing.

## Driving it

```bash
# What will PPOB2 accept?
curl 'localhost:8081/sim/supported-amounts?product_code=MOBILE_LEGENDS'

# Sell one top-up. reseller_txn_id doubles as the Idempotency-Key.
curl -X POST localhost:8081/sim/transactions -H 'Content-Type: application/json' \
  -d '{"reseller_txn_id":"PPOB1-TX-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-777"}'

# Pay the QR, using PPOB2's own callback script
cd ../backend && ORIGREF="R-1" scripts/e2e/cb.sh "$(scripts/e2e/q.sh \
  "select pg_reference from payment p join parent_order o on o.id=p.parent_order_id where o.order_no='ORD-...'" -t)" 20000 00

curl localhost:8081/sim/transactions/1     # local_state COMPLETED, last_source WEBHOOK
curl localhost:8081/sim/webhooks           # verdict APPLIED, signature_valid true

curl -X POST localhost:8081/sim/recon/run  # reconcile
```

| Endpoint | |
|---|---|
| `GET /sim/supported-amounts?product_code=` | Section 23.3, passed through |
| `POST /sim/transactions` | Sell one top-up |
| `GET /sim/transactions`, `GET /sim/transactions/{id}` | PPOB1's books |
| `POST /sim/transactions/{id}/inquire` | Section 23.5, for one transaction |
| `POST /sim/transactions/{id}/cancel` | Section 23.7 |
| `GET /sim/transactions/{id}/payment` | Section 23.6, passed through |
| `POST /sim/recon/run?include_settled=` | Run a reconciliation pass |
| `GET /sim/recon/{runId}` | A past run's entries |
| `GET /sim/webhooks` | Every callback received, refusals included |
| `POST /webhooks/ppob2` | PPOB2's callback target — not for humans |

**`/sim/**` is unauthenticated.** Deliberate: this is a single-operator simulator, and a second
credential scheme would only obscure the one that matters. It would be a serious defect in anything
shipped.

## PPOB1's own gross-collected book

Per business day: what PPOB1 believes PPOB2 collected on the orders PPOB1 originated. Computed
entirely from PPOB1's books, with **no call to PPOB2** — this is the half of settlement
reconciliation that needs no agreement between the two companies.

```bash
curl -X POST 'localhost:8081/sim/collections/snapshots?business_date=2026-10-10'
curl 'localhost:8081/sim/collections/snapshots/latest?business_date=2026-10-10'
curl localhost:8081/sim/collections/snapshots     # every snapshot ever taken
```

### It is not called a receivable, on purpose

That PPOB1 is *owed* this money is a commercial term the PRD never states. There is no partner
commission or margin model anywhere in it; FR-REC-004 says the per-partner allocation "does NOT
constitute or trigger a payout to the partner"; BR-REC-002 says which party bears the MDR "must be
verified against each partner's contract"; and PPOB2 pays its upstream providers out of the same
collected funds. So the quantity here is **gross collected on PPOB1-originated orders** — the thing
comparable to `settlement_partner_allocation.gross_amount`, and nothing more.

### A census, not a total

There is deliberately **no single "amount owed" field**. Which buckets count toward what PPOB1 is
owed is exactly the commercial term above, so a total would be inventing one. Every transaction for
the day lands in exactly one bucket instead, and whoever negotiates the contract decides which to
add up. The buckets sum to the totals — asserted in both the unit tests and the e2e run, the same
discipline as BR-REC-002's "allocations MUST sum exactly".

Classification follows **whether the customer's money was collected**, not whether it was delivered.
That is what makes the figure comparable: PPOB2's `sumSuccessfulAmountForDate` filters on
`payment.status = 'SUCCESS'` alone, independent of order state, so upstream a *failed* order's
payment still counts toward the settlement's `expected_amount`. Bucketing by delivery would
understate PPOB1 against the very number it is meant to be compared with.

| Bucket | Local states | Why it is separate |
|---|---|---|
| `DELIVERED` | `COMPLETED` | Nobody will argue about it |
| `PARTIAL` | `PARTIALLY_FAILED` | Collected in full, delivered in part — PPOB1 has a claim PPOB2 has no partial-refund mechanism to settle |
| `COLLECTED_UNDELIVERED` | `FAILED`, `REFUND_EXPECTED` | Still collected: the payment succeeded, and PPOB2's refund execution is a human moving money out-of-band |
| `REVERSED` | `REFUNDED` | The one bucket that leaves PPOB2's figure too — `markRefunded` moves the payment off `SUCCESS` |
| `IN_FLIGHT` | `AWAITING_RESULT` | Collected, day not finished — makes the snapshot provisional |
| `NOT_COLLECTED` | `AWAITING_PAYMENT`, `CANCELLED`, `EXPIRED`, `REJECTED` | No money moved |
| `INDETERMINATE` | `SUBMITTING`, `UNKNOWN` | **Not** folded into `NOT_COLLECTED`: an orphaned submit may have a live QR a customer already paid, so calling it "nothing collected" would understate the day silently |

The e2e run includes a **harness-only oracle** that sums PPOB2's `SUCCESS` payments for exactly the
orders PPOB1 knows about and asserts it equals `DELIVERED + PARTIAL + COLLECTED_UNDELIVERED +
IN_FLIGHT`. That is the cheapest available proof the bucket definitions actually match the basis
they will be reconciled against. The service itself never does this.

### Three divergences from PPOB2's day-grouping, named rather than hidden

The whole purpose of this figure is eventual comparability, so a basis that differs silently is
worse than no figure. PPOB2 computes `expected_amount` as
`SUM(amount) WHERE status='SUCCESS' AND paid_at::date = :date`.

1. **`created_at` vs `paid_at` — unavoidable.** PPOB1 groups by when it made the sale, because it
   never learns when the customer paid: Section 23.6 exposes `paid_at`, but the Section 23.8
   callback does not carry it and PPOB1 stores no such field. A sale at 23:50 paid at 00:10 lands
   on different days in the two systems. Closing it would mean a Section 23.6 call per transaction
   — a real option, a different design.
2. **PPOB2's own day boundary is not pinned.** `paid_at::date` casts in the *Postgres session*
   timezone, which the JDBC driver takes from the JVM default — not from
   `hibernate.jdbc.time_zone`, which only governs parameter binding. Verified directly:
   `2026-10-10T23:30:00Z` casts to `2026-10-11` under `Asia/Jakarta` and `2026-10-10` under `UTC`.
   So the same payment data yields different settlement days depending on the deploying host's
   timezone, which decides `expected_amount`, which decides MATCHED vs DISCREPANCY. A PPOB2
   finding, reported rather than worked around.
3. **Settlement windows are not calendar days anyway.** Section 37.1 flags the real T+N schedule as
   "must be verified against latest Ayolinx contract", and PPOB2's own repository comment calls its
   single-calendar-day grouping "a placeholder window, not a modeled one". Both sides approximate
   the same unknown.

Because of (2), `ppob1.business-zone` defaults to **UTC**, not `Asia/Jakarta`: matching the basis
observed in the one environment that exists removes a divergence PPOB1 can control, and a
nicer-looking local business day would add a second one on top of the unavoidable (1). The zone is
stored **on each snapshot**, because the same date label under a different zone covers a different
set of sales and the numbers alone cannot say which.

### Snapshots are append-only, several per day

Not unique on `business_date`, on purpose: the transaction book moves when a reconciliation run
repairs a stale belief, so the same day legitimately yields a different figure tomorrow. Each row
freezes what PPOB1 believed at one instant — which is what you need when PPOB2 disagrees with a
figure you quoted last week. `GET .../latest` returns the newest; the older rows stay on file. A
`provisional` flag travels with the figure so "today" never reads as final.

## Testing

```bash
cd ppob1-sim
./gradlew test                      # 56 unit tests, 6 classes
scripts/e2e/run-ppob1.sh            # 98 assertions, both systems running
```

`run-ppob1.sh` is a genuinely two-sided run: PPOB1 sells through PPOB2's signed API, PPOB2 fulfils,
PPOB2 calls back, PPOB1 verifies the signature with its own implementation, and both databases are
asserted on. `scripts/e2e/wh.sh` forges callbacks PPOB2 will never send — a wrong signature, an
unknown order, a malformed body — because those branches decide whether the receiver is a control or
just a parser.

## What building this found

Everything below came out of running it, not reading the code.

1. **PPOB2's outbound webhook had never been delivered to anything.** See
   [the seed gap](#the-seed-gap-this-exposed). It works — the harness now proves a full round trip
   with an independently verified signature — but nothing had ever established that, and the fixture
   guaranteed nobody would.

2. **`metadata.reseller_txn_id` is accepted and silently discarded.** Section 23.4's documented
   request body carries it, `CreateOrderController` binds it into its request record, and
   `parent_order` has no `metadata` column at all. Precisely the shape of the `callback_url` gap
   `V16__outbound_webhook.sql` fixed, whose own comment describes the pattern: *"already accepted
   and silently dropped"*. The cost is concrete — PPOB2 support cannot tie an order back to the
   reseller's own reference, so every cross-company investigation starts by asking PPOB1. PPOB1
   keeps sending it, and the harness asserts the gap so it flips the day someone closes it.

3. **Section 23.2 has two ambiguities that cost a real integrator a day each.** Both are now
   resolved in `Ppob2RequestSigner`'s Javadoc and pinned by tests. The spec says the signature may
   be *"hex or base64"* — PPOB2 accepts lowercase hex only, and base64 comes back as
   `SIGNATURE_INVALID`, indistinguishable from a wrong secret. And the signed `PATH` excludes the
   query string, which breaks exactly one endpoint (the only one with a parameter), so it reads like
   that endpoint being broken rather than like a signing rule.

4. **A design bug in my own reconciliation, found by running it.** The working set was "every
   transaction that is not in a terminal state", which looks obviously right and made the
   `WEBHOOK_NEVER_ARRIVED` verdict nearly unreachable: reaching a terminal state is exactly what
   dropped a transaction out. The first harness run reported `examined: 0` against four transactions
   that had just settled. The right question is *"has this been reconciled?"*, not *"is this still
   open?"* — a property of the reconciliation, not of the order. Fixed by
   `V2__recon_settled_at.sql`.

5. **A `@Transactional` self-invocation bug, caught before it ran** — the same one PPOB2 hit with
   `AdminRefundOrchestrator`. The two writes bracketing the submit were first written as
   `@Transactional protected` methods on `TransactionService`, called as `this.persistSubmitting(...)`.
   Spring's advice lives on a proxy, so a self-invocation is never intercepted: it compiles, a mocked
   test passes, and at runtime neither write is in the transaction it appears to be in. Extracted to
   [`TransactionWriter`](src/main/java/id/ppob1/sim/service/TransactionWriter.java).

6. **A nonce-burning hole in my own receiver, found in review.** A rejected-signature row stored
   the *header* nonce in the unique `nonce` column. So an unauthenticated caller could forge a
   request carrying the nonce of a callback not yet delivered, and PPOB2's genuine one would then
   trip `existsByNonce`, be answered `200 DUPLICATE`, and be dropped — with no retry, because `200`
   means "taken". A real event lost through the dedup path rather than the apply path, and a direct
   contradiction of this class's own stated invariant that a failed signature check applies nothing
   at all. Rejected rows now store a NULL nonce.

7. **PPOB2's settlement day boundary depends on the deploying host's timezone.** Divergence (2)
   above. `paid_at::date` casts in the Postgres session timezone, which the JDBC driver takes from
   the JVM default, so `expected_amount` — and therefore whether a settlement is MATCHED or
   DISCREPANCY, and whether a `PAYMENT_VS_SETTLEMENT` reconciliation record opens — differs between
   a UTC host and an `Asia/Jakarta` one. Nothing in PPOB2's configuration pins it. Verified with a
   direct cast under both zones.

8. **Two defects in my own snapshot code that only running it revealed.** `computed_at` was
   `insertable = false` with a column default, so it was still null on the instance returned
   straight after `save()` — Hibernate does not re-read the row — and the app's
   `default-property-inclusion: non_null` silently dropped it from the POST response while a later
   GET of the same snapshot showed it. And `@OrderBy("bucket ASC")` sorts by the stored string, so a
   re-read came back alphabetically while a fresh save kept insertion order: the same snapshot
   rendering two different ways depending on how it was loaded. Both now have regression tests.

9. **A harness bug that would have passed wrongly.** Nineteen assertions used `grep -c`, which counts
   matching *lines* — and every response here is one line of JSON, so it can report at most 1 and
   cannot tell one match from five. It surfaced as a visible failure on "all three agree" (got 1,
   wanted 3), but every "expect exactly 1" assertion would have passed just as happily with a
   duplicate row — including "duplicate wrote no second row", whose entire purpose is to count. Same
   shape as the `q.sh -t` incident `backend/scripts/e2e/README.md` warns about: suspect the harness
   first.

## Design notes worth knowing before changing anything

- **The transaction row is committed before PPOB2 is called**, in its own transaction, and the
  outcome is applied in a second one. The obvious single `@Transactional` method is wrong twice: a
  timeout mid-call would roll the row back, leaving PPOB1 with **no record** of a submit that may
  have created a payable order upstream — nothing can reconcile a row that does not exist — and it
  would hold a database connection open for as long as another company's server takes to answer.
  `LocalState.SUBMITTING` exists for exactly that window, and `ORPHANED_SUBMIT` is how a run reports
  it.
- **A timeout is not a refusal.** `Ppob2Response` distinguishes a `2xx`, an error answer, and *no
  answer at all*. A `422` means PPOB2 definitely created nothing; a timeout means PPOB1 has no idea,
  and collapsing the two is how a reseller ends up with no record of an order its customer has paid
  for.
- **`local_state` and `ppob2_state` are both kept.** One is what PPOB1 will act on, the other is
  PPOB2's last word stored verbatim and un-translated. A single column would make every disagreement
  unrepresentable — overwriting belief with truth is what reconciliation decides to do, not something
  storage should do silently.
- **An unrecognised upstream state becomes `UNKNOWN`, never a guess.** PPOB2 can add a state in a
  release PPOB1 has never seen. Throwing would fail the inquiry and leave the belief stale with no
  record of why; defaulting to "in progress" would have PPOB1 wait forever for a transition that
  already happened.
- **The webhook signature covers the raw bytes**, so the receiver takes a `String` body and never a
  bound record — Jackson round-tripping would change whitespace or key order and produce a hash that
  can never match. The raw body is persisted for the same reason.
- **Which HTTP status the receiver answers is a correctness decision.** PPOB2 retries any non-2xx
  four times over ~23h. A malformed body or an unknown order gets a 2xx-or-400 that stops the
  retries, because retrying cannot fix either and a retry storm would bury the row that records a
  real problem. See [`WebhookVerdict`](src/main/java/id/ppob1/sim/domain/WebhookVerdict.java).
- **`WEBHOOK_NEVER_ARRIVED` is scoped to the states PPOB2 actually pushes** (SUCCESS,
  PARTIAL_FAILED, FAILED). PPOB2 does not fire for CANCELLED/EXPIRED/REFUNDED by design, so flagging
  those would report its documented trigger coverage as a fault on every single run and bury the
  findings that matter.

## Not built yet

- **Comparing the gross-collected book against PPOB2's own settlement figure.** PPOB1's half is
  built (above). The other half is blocked on a contract decision, not on code: PPOB2 has **no
  partner-facing settlement endpoint** — both its settlement endpoints are behind admin RBAC rather
  than the Section 23 Open API, and `GET /admin/settlements/{id}/allocations` returns *every*
  partner's figures, so exposing it as-is would leak one reseller's volume to another. The options
  are a new partner-scoped Open API endpoint, an agreed report format, or no automated comparison
  at all; each carries policy questions (may a partner see the MDR? the platform's `actual_amount`?)
  that are commercial, not technical.

  There is also a reason to wait even once that is decided: PPOB2's own per-partner figure is
  currently an **estimate**. Section 37.3 names `EXACT` (a direct per-transaction join via
  `pg_reference`) as the preferred method, but it is unimplemented — `TC-BE-033` is tagged **X**,
  because it needs an Ayolinx settlement report carrying per-transaction lines, a format Section
  37.1 itself marks "must be verified". Only `PRO_RATA` runs. Reconciling PPOB1's figure against an
  apportionment estimate yields differences nobody can act on, which is worse than no comparison,
  because people will trust it.
- **PPOB1's own customer-facing side.** It has no downstream API, no customer records and no billing
  — it models the reseller's integration with PPOB2, not its whole business.
- **Retry of its own submits.** A transport failure leaves the transaction `SUBMITTING` for
  reconciliation to report; nothing automatically re-sends it. Deliberate for now: automatic
  re-submission of a money-moving call deserves its own backoff and cap design, and
  `ORPHANED_SUBMIT` makes the queue visible in the meantime.
- **A genuine orphaned-submit reproduction.** The harness stages the *condition* in PPOB1's own
  books, because reproducing it faithfully needs PPOB2 to stop answering mid-call. The **recovery**
  is driven for real, though, and that is the half that matters: a sale is made, PPOB1's record of
  its upstream id is then destroyed, and re-submitting under the same `reseller_txn_id` is asserted
  to return the original order with exactly one order existing at PPOB2 — so the remedy the
  `ORPHANED_SUBMIT` detail text recommends to operators is tested, not just asserted. Everything
  else in `run-ppob1.sh` is driven for real, including the broken-push-integration case.
