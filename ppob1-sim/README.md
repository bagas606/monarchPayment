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

What it does **not** do yet: settlement. See [Not built](#not-built-yet).

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

# 2. THE ONE EXTRA STEP — see below. Without it no webhook is ever delivered.
psql -h localhost -U ppob2 -d ppob2 \
  -c "UPDATE partner SET webhook_secret='ppob1-webhook-secret' WHERE code='PPOB1'"

# 3. PPOB1's database, then PPOB1 on :8081
createdb -h localhost -U ppob2 ppob1     # once
cd ../ppob1-sim && ./gradlew bootRun
```

### The one extra step, and the gap it exposes

`backend/scripts/e2e/dev-seed.sql` creates partner `PPOB1` but leaves `partner.webhook_secret`
**NULL**. PPOB2 handles that correctly — `WebhookDeliveryTargetResolver` logs an error and skips
delivery rather than signing with nothing — so the consequence is silent: **no webhook is ever
delivered, by any order, in a stock dev environment.**

Which means PPOB2's entire outbound webhook path had never once been exercised end-to-end before
this simulator existed. The unit tests cover the sender; the e2e harness never had a receiver. That
is the same profile as every money defect this repository has found: compiles, has a mocked test,
has never run.

Setting the secret is left as a manual step rather than added to the seed on purpose, because
`backend/`'s own assertions depend on absolute `audit_log` counts and a seeded callback would start
changing `webhook_event` rows under them. Worth revisiting together with the seed, not unilaterally.

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

## Testing

```bash
cd ppob1-sim
./gradlew test                      # 39 unit tests, 4 classes
scripts/e2e/run-ppob1.sh            # 65 assertions, both systems running
```

`run-ppob1.sh` is a genuinely two-sided run: PPOB1 sells through PPOB2's signed API, PPOB2 fulfils,
PPOB2 calls back, PPOB1 verifies the signature with its own implementation, and both databases are
asserted on. `scripts/e2e/wh.sh` forges callbacks PPOB2 will never send — a wrong signature, an
unknown order, a malformed body — because those branches decide whether the receiver is a control or
just a parser.

## What building this found

Everything below came out of running it, not reading the code.

1. **PPOB2's outbound webhook had never been delivered to anything.** See
   [the extra step](#the-one-extra-step-and-the-gap-it-exposes). It works — the harness now proves a
   full round trip with an independently verified signature — but nothing had ever established that.

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

6. **A harness bug that would have passed wrongly.** Nineteen assertions used `grep -c`, which counts
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

- **Settlement.** The requested scope for this slice was transactions, webhook and reconciliation,
  and settlement is the honest next step rather than something to fake. It needs a decision first:
  PPOB2 has **no partner-facing settlement endpoint** — `GET /admin/settlements/{id}/allocations` is
  behind admin RBAC, not the Open API, and Section 37.3 scopes PPOB2's settlement work to
  *attribution only*, explicitly not disbursement. So PPOB1 computing "what I believe I owe PPOB2
  per day from my own successful orders" is straightforward and useful on its own, but reconciling
  it against PPOB2's figures requires either a new partner-facing endpoint on PPOB2 or an agreed
  report format. That is a contract decision, not a coding one.
- **PPOB1's own customer-facing side.** It has no downstream API, no customer records and no billing
  — it models the reseller's integration with PPOB2, not its whole business.
- **Retry of its own submits.** A transport failure leaves the transaction `SUBMITTING` for
  reconciliation to report; nothing automatically re-sends it. Deliberate for now: automatic
  re-submission of a money-moving call deserves its own backoff and cap design, and
  `ORPHANED_SUBMIT` makes the queue visible in the meantime.
- **A genuine orphaned-submit reproduction.** The harness stages that one in PPOB1's own books,
  because reproducing it faithfully needs PPOB2 to stop answering mid-call. Everything else in
  `run-ppob1.sh` is driven for real, including the broken-push-integration case.
