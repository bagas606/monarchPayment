# monarchPayment — PPOB2 Platform

PPOB2 is a payment + fulfillment platform for game top-ups: a partner creates an order, pays a
dynamic QRIS code through Ayolinx, and the paid amount is decomposed into one or more provider SKUs
that are purchased upstream and reconciled afterwards.

This repository currently contains the **backend** — a Java 21 / Spring Boot 3.3 modular monolith
(Gradle Kotlin DSL, PostgreSQL, Flyway) — and a small **backoffice console** served by that same app at
`/backoffice/` (plain JavaScript, no build step). The console covers transactions, payments, callbacks,
reconciliation and the audit log; it is not the whole of Section 41 (no catalog, pricing or configuration
editing, no dashboard).

> **Status: not ready for production.** Several vertical slices are real and verified end-to-end
> against a live Postgres, but the platform cannot serve live traffic yet — most importantly there
> is **no real upstream provider integration at all**, so the `prod` Spring profile deliberately
> refuses to start. See [Go-live blockers](#go-live-blockers).

## Where to find things

Six documents, each with one job. Read them in this order.

| Document | What it is | When you want it |
|---|---|---|
| **this file** | Entry point: orientation, quickstart, status, blockers | first |
| [`docs/PRD-PPOB2.md`](docs/PRD-PPOB2.md) | The specification — business rules (`BR-*`), functional requirements (`FR-*`), the full data model, and the `TC-*` test matrices. **The source of truth for what the system is supposed to do.** | deciding what *should* happen |
| [`docs/TEST-STATUS.md`](docs/TEST-STATUS.md) | One table covering all 54 PRD test-case IDs: verified, verified-earlier, unit-only, or not implemented — and where the evidence lives | asking "is X actually tested?" |
| [`backend/README.md`](backend/README.md) | The engineering record: module-by-module notes on what is real vs. scaffolded, every deliberate design trade-off, and the dated evidence behind each verified behaviour. Long and chronological by design. | deciding what the code *does*, and why |
| [`backend/scripts/e2e/README.md`](backend/scripts/e2e/README.md) | Mechanics of the end-to-end harness | running the matrices yourself |
| [`ppob1-sim/README.md`](ppob1-sim/README.md) | **PPOB1** — the reseller on the other side of the Section 23 contract, as a running system with its own database and its own books. What it found about PPOB2's partner-facing surface is listed there. | integrating against the Open API, or testing it from the outside |

Conventions those documents share:

- **`Section N`** always refers to a section of `docs/PRD-PPOB2.md`.
- **`TC-BE-*` / `TC-ADM-*` / `TC-PROP-*`** are PRD test-case IDs. Searching `backend/README.md`
  for an ID finds the evidence for it.
- A **gap** means "the PRD asks for this and the code does not do it", stated deliberately rather
  than left implicit. Gaps are named in `backend/README.md` next to the thing they affect.

## The two systems in this repository

`backend/` is **PPOB2**, the platform. `ppob1-sim/` is **PPOB1**, the channel partner that buys from
it — a separate Spring Boot application with its own Gradle build and its own Postgres database,
which can reach PPOB2 only over signed HTTP exactly as a real reseller does. That isolation is
deliberate and is what makes it a conformance test of PRD Section 23 rather than a second front door
onto PPOB2's internals; `ppob1-sim/README.md` explains why, and what running it revealed (starting
with: PPOB2's outbound webhook had never been delivered to anything, because the dev seed left
the partner's webhook secret unset).

## Quickstart

Needs Docker and a JDK 21.

```bash
cd backend
docker compose up -d        # Postgres 16 on localhost:5432
# The app refuses to boot without a key ring for partner secrets. Keep it: a new key later means re-provisioning.
[ -f ~/.ppob2-dev-keys ] || echo "export PPOB2_SECRET_KEYS=k1:$(openssl rand -base64 32) PPOB2_SECRET_ACTIVE_KEY_ID=k1" > ~/.ppob2-dev-keys
. ~/.ppob2-dev-keys
PPOB2_ADMIN_BASIC_AUTH_ENABLED=true ./gradlew :app:bootRun   # foreground; Flyway migrates from zero, then the app blocks
```

`bootRun` does not return. Once it logs `Started Ppob2Application`, seed from a **second terminal** —
not before, because Flyway creates the tables during that boot:

```bash
cd backend
docker exec -i backend-postgres-1 psql -U ppob2 -d ppob2 -v ON_ERROR_STOP=1 -1 \
  < scripts/e2e/dev-seed.sql
scripts/e2e/provision-secrets.sh   # the partner's HMAC + webhook secrets: encrypted, so set via the admin API, not SQL
```

There is no automatic seed. `scripts/e2e/dev-seed.sql` creates everything the test matrices need —
a partner and API client, a product with supported amounts, four provider SKUs with prices, six
decomposition patterns, and an Admin Web user. See
[`backend/README.md`](backend/README.md#running-locally) for signing a request, simulating a
payment callback, and the dev-only failure-injection knobs.

## Testing

**CI** (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request: a `unit` job (both builds, every
unit / slice / ArchUnit test) and an `e2e` job that runs the whole pass below with `backend/scripts/e2e/run-all.sh`
against a Postgres service container, the real jars and a real browser. That one script is also what to run locally before
a release, because it knows the order the suites need and re-seeds between groups:

```bash
(cd backend && ./gradlew :app:bootJar) && (cd ppob1-sim && ./gradlew bootJar)
NODE_PATH=<dir with playwright> backend/scripts/e2e/run-all.sh      # E2E_SKIP="ppob1 ui-smoke" to leave suites out
```

The suites individually:

```bash
cd backend
# The e2e suites authenticate to /admin/** with `curl -u`. HTTP Basic is OFF by default (it carries no MFA),
# so boot the app for them with:  PPOB2_ADMIN_BASIC_AUTH_ENABLED=true
./gradlew build                          # unit / slice tests + ArchUnit (272 tests)
scripts/e2e/run-core.sh                  # 178 assertions against a running app + real Postgres
scripts/e2e/run-routing-and-sweeps.sh    # 15 assertions (waits on the 60s expiry-sweep tick)
scripts/e2e/run-refund-gateway.sh        # 15 assertions; needs its own boot flag, see its header
scripts/e2e/run-backoffice-read.sh       # 95 assertions; run after run-core.sh, it reads what that left behind
scripts/e2e/run-partner-secrets.sh       # 34 assertions: secrets at rest, forged/plaintext values refused, provisioning API
scripts/e2e/run-pattern-admin.sh         # 84 assertions: generate, activate, roll back, score, configure; own fresh database, runs after run-core
scripts/e2e/run-catalog-admin.sh         # 83 assertions: catalog, pricing, supported amounts; own fresh database
scripts/e2e/run-admin-auth.sh            # 101 assertions; boots its own app on :8081 (sessions, MFA, timeouts, UI headers)
NODE_PATH=<dir with playwright> node scripts/e2e/ui-smoke.cjs   # 74 assertions in a real browser; see scripts/e2e/README.md

cd ../ppob1-sim
./gradlew test                           # 65 unit tests for the PPOB1 side
scripts/e2e/run-ppob1.sh                 # 126 assertions, both systems live; see ppob1-sim/README.md
```

**A green `./gradlew build` is not evidence that the money paths work.** Three of the defects found
on 2026-09-27 were invisible to the unit suite: the bounded-retry loop had never run against real
HTTP, a `FAILED`-after-`SUCCESS` payment callback was silently swallowed, and a paid-but-cancelled
order recorded nothing anywhere. The 2026-10-03 pass found a fourth of the same kind: a paid order
with **no eligible decomposition pattern** collected the money, delivered nothing, and opened no
reconciliation record either — the identical hole as the paid-but-cancelled one, by a different
route, in a case whose prior evidence covered only its state transition. The e2e scripts exist for
exactly that class of bug — they drive signed HTTP against a real app and then assert on the
resulting database rows.

## Go-live blockers

None of these are bugs. They are unbuilt scope, and each is a product decision rather than
something a test pass can close.

1. **No real upstream provider integration.** `StubGameProviderAdapter` is the only `GameProvider`
   and is `@Profile("!prod")`, so booting with `SPRING_PROFILES_ACTIVE=prod` fails at context
   startup with no such bean. That is deliberate — failing loudly beats silently issuing top-ups
   nobody fulfils — but it means the platform cannot serve production traffic in any form.
2. **Pattern generation is Java, not Rust, and unproven against a real catalog.** Section 29's engine now
   exists as a pure, deterministic Java function (verified against brute force) behind a build / validate /
   activate / roll-back lifecycle with a coverage gate. Measured on a synthetic catalog (3 providers x 8
   denominations x 118 amounts): 985,777 patterns in 3 min 2 s, ~500 MB peak RSS, 1.6 GB of tables. It has not
   seen a real provider catalog, and routing still loads up to K=10,000 patterns per order (about 0.7 to 1.4 s from
   callback to SUCCESS at that size) where the PRD envisions a Redis-backed candidate cache.
3. **Single instance only.** Nothing is Redis-backed: the replay-protection nonce store is an
   in-memory map and the QR expiry sweep has no leader election, so two app instances would not
   coordinate.
4. **Partner secrets are encrypted with a config-held key, not a KMS.** Since V28 the HMAC secret and the
   webhook secret are AES-256-GCM ciphertext under `PPOB2_SECRET_KEYS` (Section 22.3 says "hash", but HMAC needs
   the secret itself). Anyone who can read both the database and the app's environment can still decrypt;
   a KMS-backed `SecretKeyProvider` is the follow-up, and Section 22.3 still needs updating to say so.
5. **Parts of Admin Web are still missing.** Built: transactions, payments, audit, sessions + MFA,
   reconciliation, and (2026-10-24) catalog, pricing, supported amounts, configuration values, pattern
   generation / activation / rollback and pattern lookup. Not built: the dashboard (41.1), margin and quota
   views (41.5, 41.6), provider balance / settlement screens, and editing of user accounts and roles (done in
   SQL). The reconciliation surface (Section 41.9) can be worked from a queue: `GET /admin/reconciliations` lists and filters
   discrepancies, `GET /admin/reconciliations/{id}` drills into one, and the
   `investigate`/`resolve` actions move them. What is still missing there is Section 41.9's
   resolution **notes** — `resolve` records who and when, but not why, so a resolved discrepancy
   carries no explanation (the gap `TC-ADM-012` closed for retries). All of this is REST, not a
   browser UI; Section 54's own method column names Playwright/Cypress and none exists here.
6. **No daily re-scoring job.** Runtime routing reads `pattern_economics.score`, which Section 30.1
   says an offline daily job recomputes. That job does not exist, so scores must be seeded.
7. **No pattern invalidation writer.** `decomposition_pattern.structural_status` is only ever read
   (filtered to `VALID`); nothing writes it, so Section 30.3 invalidation never fires. Runtime
   routing does exclude patterns with disabled SKUs, so the effect is covered while the mechanism
   is not.
8. **No gateway can actually execute a refund, so every refund is a human moving money.**
   `REFUND_PENDING → REFUNDED` is now implemented (`POST /admin/parent-orders/{id}/refund`,
   `refund:initiate`, reversing ledger entry, audited) — but `AyolinxPaymentGateway.supportsRefund()`
   returns **false**, because Ayolinx's public API has no refund endpoint at all (`qr-mpm-cancel`
   voids an *unpaid* QR; it does not refund a settled payment). Section 73.3's open question 5 —
   "Ayolinx's actual refund capability, window, and process (API-driven vs manual request)" — is
   still open and Section 25.2 marks it "must be verified against contract".

   So in every configuration that can actually boot for production, the endpoint **records** a
   refund that a human already performed out-of-band and requires their `external_reference` as
   evidence; it does not move money. That is deliberate and enforced, not a gap in the
   implementation — but it means refunds remain a manual operational process with a settlement-side
   step outside this system, and the SOP in Section 71 is a go-live prerequisite rather than a
   nicety. The gateway-executed path is built and tested behind `supportsRefund()`, so confirming
   the capability with Ayolinx is a config flip plus one method, not a redesign. Section 7 still
   scopes payout/disbursement as its own unspecified feature.
9. **The inbound callback amount format is unverified.** A genuine Ayolinx sandbox callback was
   received, but its `amount` field was never recorded against an order with a known billed amount.
   Capture one and pin it in `AyolinxCallbackPayloadTest` before go-live; until then the payment
   path treats an unparseable amount as "apply the payment, flag it for a human" rather than
   refusing money over an unproven assumption.
