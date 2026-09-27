# monarchPayment — PPOB2 Platform

PPOB2 is a payment + fulfillment platform for game top-ups: a partner creates an order, pays a
dynamic QRIS code through Ayolinx, and the paid amount is decomposed into one or more provider SKUs
that are purchased upstream and reconciled afterwards.

This repository currently contains the **backend** — a Java 21 / Spring Boot 3.3 modular monolith
(Gradle Kotlin DSL, PostgreSQL, Flyway). There is no frontend, and no Admin Web UI beyond a REST
slice.

> **Status: not ready for production.** Several vertical slices are real and verified end-to-end
> against a live Postgres, but the platform cannot serve live traffic yet — most importantly there
> is **no real upstream provider integration at all**, so the `prod` Spring profile deliberately
> refuses to start. See [Go-live blockers](#go-live-blockers).

## Where to find things

Five documents, each with one job. Read them in this order.

| Document | What it is | When you want it |
|---|---|---|
| **this file** | Entry point: orientation, quickstart, status, blockers | first |
| [`docs/PRD-PPOB2.md`](docs/PRD-PPOB2.md) | The specification — business rules (`BR-*`), functional requirements (`FR-*`), the full data model, and the `TC-*` test matrices. **The source of truth for what the system is supposed to do.** | deciding what *should* happen |
| [`docs/TEST-STATUS.md`](docs/TEST-STATUS.md) | One table covering all 53 PRD test-case IDs: verified, verified-earlier, unit-only, or not implemented — and where the evidence lives | asking "is X actually tested?" |
| [`backend/README.md`](backend/README.md) | The engineering record: module-by-module notes on what is real vs. scaffolded, every deliberate design trade-off, and the dated evidence behind each verified behaviour. Long and chronological by design. | deciding what the code *does*, and why |
| [`backend/scripts/e2e/README.md`](backend/scripts/e2e/README.md) | Mechanics of the end-to-end harness | running the matrices yourself |

Conventions those documents share:

- **`Section N`** always refers to a section of `docs/PRD-PPOB2.md`.
- **`TC-BE-*` / `TC-ADM-*` / `TC-PROP-*`** are PRD test-case IDs. Searching `backend/README.md`
  for an ID finds the evidence for it.
- A **gap** means "the PRD asks for this and the code does not do it", stated deliberately rather
  than left implicit. Gaps are named in `backend/README.md` next to the thing they affect.

## Quickstart

Needs Docker and a JDK 21.

```bash
cd backend
docker compose up -d        # Postgres 16 on localhost:5432
./gradlew :app:bootRun      # foreground; Flyway migrates from zero, then the app blocks
```

`bootRun` does not return. Once it logs `Started Ppob2Application`, seed from a **second terminal** —
not before, because Flyway creates the tables during that boot:

```bash
cd backend
docker exec -i backend-postgres-1 psql -U ppob2 -d ppob2 -v ON_ERROR_STOP=1 -1 \
  < scripts/e2e/dev-seed.sql
```

There is no automatic seed. `scripts/e2e/dev-seed.sql` creates everything the test matrices need —
a partner and API client, a product with supported amounts, four provider SKUs with prices, six
decomposition patterns, and an Admin Web user. See
[`backend/README.md`](backend/README.md#running-locally) for signing a request, simulating a
payment callback, and the dev-only failure-injection knobs.

## Testing

```bash
cd backend
./gradlew build                          # 27 unit / slice test classes + ArchUnit
scripts/e2e/run-core.sh                  # 62 assertions against a running app + real Postgres
scripts/e2e/run-routing-and-sweeps.sh    # 13 assertions (waits on the 60s expiry-sweep tick)
```

**A green `./gradlew build` is not evidence that the money paths work.** Three of the defects found
on 2026-09-27 were invisible to the unit suite: the bounded-retry loop had never run against real
HTTP, a `FAILED`-after-`SUCCESS` payment callback was silently swallowed, and a paid-but-cancelled
order recorded nothing anywhere. The e2e scripts exist for exactly that class of bug — they drive
signed HTTP against a real app and then assert on the resulting database rows.

## Go-live blockers

None of these are bugs. They are unbuilt scope, and each is a product decision rather than
something a test pass can close.

1. **No real upstream provider integration.** `StubGameProviderAdapter` is the only `GameProvider`
   and is `@Profile("!prod")`, so booting with `SPRING_PROFILES_ACTIVE=prod` fails at context
   startup with no such bean. That is deliberate — failing loudly beats silently issuing top-ups
   nobody fulfils — but it means the platform cannot serve production traffic in any form.
2. **No pattern-generation engine.** Section 29's Rust offline generator (DP + K-Best + B&B) does
   not exist, so `decomposition_pattern` rows must be hand-seeded and `TC-PROP-001..005` cannot be
   run at all.
3. **Single instance only.** Nothing is Redis-backed: the replay-protection nonce store is an
   in-memory map and the QR expiry sweep has no leader election, so two app instances would not
   coordinate.
4. **Partner API secrets are stored in plaintext.** Section 22.3 names the column `secret_hash` and
   says the secret is never stored, but HMAC verification requires the server to reproduce the MAC
   with the same secret. Needs a reversible-encryption (KMS) decision from the PRD owner —
   Section 73.3's open-assumptions list.
5. **Most of Admin Web does not exist.** `TC-ADM-006..011` — transaction search, parent/child
   drilldown, pricing update, configuration update, pattern viewing, generation trigger — have no
   implementation to test.
6. **No daily re-scoring job.** Runtime routing reads `pattern_economics.score`, which Section 30.1
   says an offline daily job recomputes. That job does not exist, so scores must be seeded.
7. **No pattern invalidation writer.** `decomposition_pattern.structural_status` is only ever read
   (filtered to `VALID`); nothing writes it, so Section 30.3 invalidation never fires. Runtime
   routing does exclude patterns with disabled SKUs, so the effect is covered while the mechanism
   is not.
8. **The inbound callback amount format is unverified.** A genuine Ayolinx sandbox callback was
   received, but its `amount` field was never recorded against an order with a known billed amount.
   Capture one and pin it in `AyolinxCallbackPayloadTest` before go-live; until then the payment
   path treats an unparseable amount as "apply the payment, flag it for a human" rather than
   refusing money over an unproven assumption.
