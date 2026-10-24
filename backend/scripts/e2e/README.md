# End-to-end verification harness

Drives the PRD Section 52/54 test matrices against a **running app and a real Postgres** over signed
HTTP, then asserts on the resulting database rows. Nothing here mocks anything — that is the point.
Every `TC-*` result quoted in `backend/README.md`'s 2026-09-27 entry came from these two scripts.

## Why this exists as a script

Three of the bugs found on 2026-09-27 were invisible to the unit suite and would have stayed
invisible: the retry loop had never run against real HTTP, a `FAILED`-after-`SUCCESS` callback was
silently swallowed, and a paid-but-`CANCELLED` order recorded nothing anywhere. A green
`./gradlew build` was not evidence that any of those worked, and re-deriving the same checks by hand
next time would not be either.

The harness also has its own cautionary tale baked in: the first run reported 23 failures that were
all `q.sh` dropping its `-t` flag, so psql table headers leaked into every comparison. If a run comes
back with sweeping failures across unrelated cases, suspect the harness before the app.

## Running

Bring up Postgres, the app and the seed fixture as described in the
[root README's quickstart](../../../README.md#quickstart) — but start the app with the three
injection knobs below rather than a bare `bootRun`. Then, from `backend/`:

```bash
scripts/e2e/run-core.sh                  # 178 assertions
scripts/e2e/run-routing-and-sweeps.sh    # 15 assertions, ~2 min (waits on the 60s sweep tick)
```

`run-backoffice-read.sh` (95 assertions) is the odd one out: it does **not** want a fresh database. It reads what `run-core.sh` left behind (orders in every state, the rejected callback, the admin audit rows) and restores the three rows it temporarily edits. Run it right after `run-core.sh`; run alone against an empty database most of its cases would fail for want of data, not because anything is wrong.

`run-refund-gateway.sh` (15 assertions) is separate because it needs the app booted with a *fourth*
knob, `PPOB2_PAYMENT_STUBGATEWAY_SUPPORTSREFUND=true` — see its own header. It asserts that mode is
active before testing anything, since against a normally-booted app every case would pass for the
wrong reason.

Postgres does not have to be the docker-compose one. `q.sh` resolves it in three steps — an
explicit `PPOB2_PSQL` command, then the compose container if it is actually running, then a native
`psql` on `PGHOST`/`PGPORT` — so the matrices also run on a box with no Docker daemon (the
2026-10-03 and 2026-10-10 passes ran entirely against a host Postgres 16 this way). The container is *probed*, not
assumed: `docker exec` against a missing daemon writes to stderr and exits non-zero, which the
callers' `2>/dev/null` would quietly turn into empty scalars and a sweep of unexplained failures
instead of one clear error — the same failure shape as the `q.sh -t` incident below.

Both expect a **freshly seeded** database. `run-core.sh` asserts on absolute `audit_log` counts, and
`run-routing-and-sweeps.sh` mutates `provider_sku` / `pattern_economics` (restoring each afterwards),
so re-running against a dirty database gives false failures. To reset:

```bash
docker exec -i backend-postgres-1 psql -U ppob2 -d ppob2 \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
# restart the app so Flyway re-migrates, then re-seed
```

The app must be started with all three dev-only injection knobs, matching the SKU ids `dev-seed.sql`
creates (A succeeds, B fails, C is ambiguous, D times out). Note the env-var spelling: relaxed
binding strips hyphens *within* a property segment but not underscores *between* segments, so a
naive all-underscores name silently does not bind.

```bash
PPOB2_ADMIN_BASIC_AUTH_ENABLED=true \
PPOB2_FULFILLMENT_STUBPROVIDER_FAILPROVIDERSKUIDS=2 \
PPOB2_FULFILLMENT_STUBPROVIDER_AMBIGUOUSPROVIDERSKUIDS=3 \
PPOB2_FULFILLMENT_STUBPROVIDER_TIMEOUTPROVIDERSKUIDS=4 \
./gradlew :app:bootRun
```

`PPOB2_ADMIN_BASIC_AUTH_ENABLED=true` is the fourth requirement, added with Section 42.3's sessions: HTTP
Basic is off by default because it carries no MFA, and these suites authenticate with `curl -u`.
`run-core.sh`, `run-refund-gateway.sh` and `run-backoffice-read.sh` run a preflight that stops with this
flag's name if it is missing, rather than failing dozens of assertions with 401.

`run-admin-auth.sh` (101 assertions) is the opposite case: it boots **its own** instance on `:8081` with Basic
*off* and timeouts of a few seconds, because it is testing exactly that posture. Run it after `run-core.sh`
(it adds audit rows, and `run-core.sh` asserts absolute audit counts). It needs the built jar
(`./gradlew :app:bootJar`), `python3` for the TOTP codes, and takes ~3 minutes, most of it waiting for TOTP
time-steps and the timeouts to elapse.

`ui-smoke.cjs` (46 assertions) drives the backoffice UI in a real browser. It needs Node, the `playwright`
package (`NODE_PATH` pointing at it) and Chromium; the app up with HTTP Basic **off**, an MFA key set
(`PPOB2_ADMIN_MFA_ENCRYPTION_KEY`) and `PPOB2_ADMIN_COOKIE_SECURE=false` (plain HTTP), and a database
populated by `run-core.sh`. It stages its own starting state (resets `superadmin`'s MFA, puts one paid order
in `REFUND_PENDING` and one record back to `OPEN`), so it can be re-run; `SHOTS=<dir>` also writes screenshots.

## The pieces

| Script | What it does |
|---|---|
| `call.sh` | Signs a partner Open API request per Section 23.2 and calls it. Signs the path **without** the query string, because `HmacAuthenticationFilter` signs `getRequestURI()` — matching the PRD, and worth knowing before debugging a 401. |
| `cb.sh` | Posts an inbound Ayolinx QRIS callback in the real nested/camelCase shape, signed with the stub gateway's `X-Ayolinx-Signature` HMAC scheme. `ORIGREF` controls `originalReferenceNo`, which is half of `payment_event.dedup_key` — vary it to send a genuinely new callback, repeat it to test replay. `RAWAMT` puts a string into `amount.value` verbatim instead of the default "integer + `.00`"; the amount-format cases need it, and without it they pass for the wrong reason (`"20,000.00"` silently became `"20,000.00.00"`). |
| `q.sh` | `psql` passthrough, and the one place that knows how to reach Postgres (see the resolution order above). Pass `-t` for a bare scalar. |
| `run-core.sh` | Error-status mapping, order creation (`TC-BE-002..005`), fulfilment (`013`, `016`, `017`), BR-DEC exhaustion (`018` — including that the funds were collected, which is what makes the missing-record half detectable), cancel (`029`, `030`), the three callback anomalies, replay/signature (`010`, `012`), the admin audit cases (`TC-ADM-002`, `005`, `012`), and out-of-band refund execution. The refund block is deliberately **last**: its RBAC-denial case adds a second `ADMIN_PERMISSION_DENIED` row, which would break `TC-ADM-005`'s absolute count if it ran earlier. |
| `run-refund-gateway.sh` | The gateway-executed refund branch, under its own boot flag. Needs a fresh seed; makes its own `REFUND_PENDING` order. |
| `run-routing-and-sweeps.sh` | Routing eligibility (`TC-BE-020`, `021`, `023`), reconciliation values (`028`), and the sweep-dependent `TC-BE-007` / `TC-BE-031`. |

## What these do NOT cover

Per-ID status for all 54 PRD test cases, including everything below, lives in
[`docs/TEST-STATUS.md`](../../../docs/TEST-STATUS.md). The cases these scripts skip are not gaps in
the harness — there is nothing to drive:

- `TC-BE-019` (pattern-level quota), `TC-BE-023`'s invalidation half, `TC-BE-024`/`025`,
  `TC-PROP-001..005`, `TC-ADM-006..011` — all unimplemented. `run-routing-and-sweeps.sh` does assert
  the `structural_status` invalidation gap *as a gap*, so the day someone implements it, that
  assertion flips and tells them to update it.
- `TC-BE-026` (Redis down): nothing is Redis-backed.
- `TC-BE-027` (database failure): needs `docker compose stop postgres` mid-run, which would break
  every following assertion. Run it by hand — expect a prompt `503 SERVICE_UNAVAILABLE` in the
  standard envelope (~6s, Hikari's `connection-timeout`), `/actuator/health` `DOWN`, then recovery
  with no app restart and no partial rows.
- The real `AyolinxPaymentGateway`: `cb.sh` uses the stub's HMAC scheme, not Ayolinx's RSA one.
  Exercising that needs sandbox credentials.
- A **real** gateway-executed refund. `run-refund-gateway.sh` drives the branch against the stub,
  which is the only gateway that can be made to claim the capability — Ayolinx's public API has no
  refund endpoint at all (Section 73.3's open question 5), so there is nothing to drive it against
  until that contract question is answered. What the script proves is that our side of the branch
  works; it cannot prove Ayolinx's.
