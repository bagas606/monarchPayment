#!/usr/bin/env bash
# The whole end-to-end pass, in the one order the suites support, each group on a freshly wiped and re-seeded
# database. This is what CI runs (.github/workflows/ci.yml) and what to run locally before a release; the individual
# run-*.sh scripts stay usable on their own and their headers say what each needs.
#
#   PPOB2_JAR    PPOB2 bootJar        (default backend/app/build/libs/app-0.1.0-SNAPSHOT.jar)
#   PPOB1_JAR    ppob1-sim bootJar    (default ppob1-sim/build/libs/ppob1-sim-0.1.0-SNAPSHOT.jar)
#   LOG_DIR      where app and suite logs go (default /tmp/ppob2-e2e)
#   E2E_SKIP     space-separated suite names to skip: core backoffice-read partner-secrets routing refund ppob1
#                admin-auth pattern-admin catalog-admin ui-smoke
#   Postgres     reached as the other scripts do (q.sh): PPOB2_PSQL, a running backend-postgres-1 container, or
#                psql against $PGHOST/$PGPORT as ppob2/ppob2. ppob1's database (named ppob1) is created if missing.
#   ui-smoke     needs node and the playwright package on NODE_PATH; skipped with a notice if it is not there.
#
# Exit status is 0 only if every suite that ran passed AND at least one ran. A suite that cannot start counts as failed.
SP="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SP/../../.." && pwd)"
JAR="${PPOB2_JAR:-$ROOT/backend/app/build/libs/app-0.1.0-SNAPSHOT.jar}"
JAR1="${PPOB1_JAR:-$ROOT/ppob1-sim/build/libs/ppob1-sim-0.1.0-SNAPSHOT.jar}"
LOG_DIR="${LOG_DIR:-/tmp/ppob2-e2e}"
Q="$SP/q.sh"
export PGPASSWORD="${PGPASSWORD:-ppob2}"
mkdir -p "$LOG_DIR"

# One key ring for the whole run, generated here and never written anywhere but this process's environment.
export PPOB2_SECRET_KEYS="k1:$(python3 -c 'import os,base64;print(base64.b64encode(os.urandom(32)).decode())')"
export PPOB2_SECRET_ACTIVE_KEY_ID=k1

APP_PID=""; P1_PID=""
stop_pid() { [ -n "${!1}" ] && kill "${!1}" 2>/dev/null && wait "${!1}" 2>/dev/null; eval "$1=''"; }
cleanup() { stop_pid APP_PID; stop_pid P1_PID; }
trap cleanup EXIT

skipped() { case " ${E2E_SKIP:-} " in *" $1 "*) return 0;; esac; return 1; }
ran=0; failed=()
declare -A RESULT

wait_health() { # url pid
  for _ in $(seq 1 90); do
    curl -s "$1/actuator/health" | grep -q '"UP"' && return 0
    kill -0 "$2" 2>/dev/null || return 1
    sleep 2
  done; return 1
}

wipe() { $Q "drop schema public cascade; create schema public" >/dev/null 2>&1; }

# boot_ppob2 [ENV=VAL ...]: wipe, start the app with the stub-provider knobs, wait, seed. Extra env overrides the defaults.
boot_ppob2() {
  stop_pid APP_PID
  wipe
  env PPOB2_ADMIN_BASIC_AUTH_ENABLED=true \
      PPOB2_FULFILLMENT_STUBPROVIDER_FAILPROVIDERSKUIDS=2 PPOB2_FULFILLMENT_STUBPROVIDER_AMBIGUOUSPROVIDERSKUIDS=3 \
      PPOB2_FULFILLMENT_STUBPROVIDER_TIMEOUTPROVIDERSKUIDS=4 "$@" \
      java -jar "$JAR" > "$LOG_DIR/app-$BOOT_N.log" 2>&1 &
  APP_PID=$!
  BOOT_N=$((BOOT_N + 1))
  wait_health http://localhost:8080 "$APP_PID" || { echo "  PPOB2 did not come up; see $LOG_DIR/app-$((BOOT_N - 1)).log"; return 1; }
  # dev-seed.sql goes in through the same path the suites use for SQL (q.sh takes -c; psql -f via -f is not wrapped).
  if [ -n "${PPOB2_PSQL-}" ]; then $PPOB2_PSQL -v ON_ERROR_STOP=1 -1 -q -f "$SP/dev-seed.sql" >/dev/null
  elif command -v docker >/dev/null 2>&1 && [ "$(docker inspect -f '{{.State.Running}}' "${PPOB2_PG_CONTAINER:-backend-postgres-1}" 2>/dev/null)" = true ]; then
    docker exec -i "${PPOB2_PG_CONTAINER:-backend-postgres-1}" psql -U ppob2 -d ppob2 -v ON_ERROR_STOP=1 -1 -q < "$SP/dev-seed.sql" >/dev/null
  else psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" -U ppob2 -d ppob2 -v ON_ERROR_STOP=1 -1 -q -f "$SP/dev-seed.sql" >/dev/null; fi
}
BOOT_N=1

# suite NAME COMMAND...: runs one suite, records pass/fail from its exit status, keeps its output.
suite() {
  local name="$1"; shift
  if skipped "$name"; then RESULT[$name]="skipped"; return; fi
  echo "== $name =="
  ran=$((ran + 1))
  "$@" > "$LOG_DIR/$name.log" 2>&1
  local rc=$?
  local line; line="$(grep -E 'PASS: *[0-9]+ +FAIL: *[0-9]+' "$LOG_DIR/$name.log" | tail -1 | tr -s ' ')"
  # A suite passes only if it exits 0, printed its result line, AND that line reports zero failures: not every script's
  # exit status reflects its failure count, so the count in the output is checked too.
  local nfail; nfail="$(printf '%s' "$line" | sed -n 's/.*FAIL: *\([0-9][0-9]*\).*/\1/p')"
  if [ $rc -eq 0 ] && [ "${nfail:-x}" = "0" ]; then RESULT[$name]="ok  $line"; else RESULT[$name]="FAILED (exit $rc) ${line:-no result line}"; failed+=("$name"); grep -E '^  FAIL' "$LOG_DIR/$name.log" | head -20; fi
  echo "   ${RESULT[$name]}"
}

[ -f "$JAR" ] || { echo "no PPOB2 jar at $JAR (./gradlew :app:bootJar)"; exit 2; }
$Q "select 1" -t >/dev/null 2>&1 || { echo "Postgres is not reachable through q.sh; see its header"; exit 2; }

# ---- group 1: the shared-state suites, in their documented order -------------------------------------------------------
if ! skipped core || ! skipped backoffice-read || ! skipped partner-secrets || ! skipped routing; then
  boot_ppob2 || { failed+=("boot:group1"); }
  suite core            "$SP/run-core.sh"
  suite backoffice-read "$SP/run-backoffice-read.sh"      # reads what run-core left behind
  suite partner-secrets "$SP/run-partner-secrets.sh"
  suite routing         "$SP/run-routing-and-sweeps.sh"   # ~70 s: waits on the 60 s expiry-sweep tick
fi

# ---- group 2: gateway-mode refunds need their own boot flag --------------------------------------------------------------
if ! skipped refund; then
  boot_ppob2 PPOB2_PAYMENT_STUBGATEWAY_SUPPORTSREFUND=true || failed+=("boot:refund")
  suite refund "$SP/run-refund-gateway.sh"
fi

# ---- group 3: PPOB1 on :8081 against its own database -------------------------------------------------------------------
if ! skipped ppob1; then
  if [ -f "$JAR1" ]; then
    boot_ppob2 || failed+=("boot:ppob1")
    # Created if missing, then wiped: PPOB1 must start from nothing like PPOB2 does.
    $Q "create database ppob1" >/dev/null 2>&1
    psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" -U ppob2 -d ppob1 -qc "drop schema public cascade; create schema public" >/dev/null 2>&1 \
      || ${PPOB2_PSQL:-false} -d ppob1 -c "drop schema public cascade; create schema public" >/dev/null 2>&1
    java -jar "$JAR1" > "$LOG_DIR/ppob1-sim.log" 2>&1 &
    P1_PID=$!
    if wait_health http://localhost:8081 "$P1_PID"; then suite ppob1 "$ROOT/ppob1-sim/scripts/e2e/run-ppob1.sh"
    else RESULT[ppob1]="FAILED (ppob1-sim did not start, see $LOG_DIR/ppob1-sim.log)"; failed+=(ppob1); ran=$((ran + 1)); fi
    stop_pid P1_PID
  else
    RESULT[ppob1]="FAILED (no ppob1-sim jar at $JAR1)"; failed+=(ppob1); ran=$((ran + 1))
  fi
fi

# ---- group 4: admin sessions + MFA boot their own instance on :8081 (so PPOB1 must be down, and it is) ----------------------
if ! skipped admin-auth; then
  [ -n "$APP_PID" ] || boot_ppob2 || failed+=("boot:admin-auth")
  suite admin-auth env PPOB2_JAR="$JAR" "$SP/run-admin-auth.sh"
fi

# ---- groups 5 and 6: generation / catalog suites replace the seeded generation, so each gets a fresh database ----------------
if ! skipped pattern-admin; then boot_ppob2 || failed+=("boot:pattern"); suite pattern-admin "$SP/run-pattern-admin.sh"; fi
if ! skipped catalog-admin; then boot_ppob2 || failed+=("boot:catalog"); suite catalog-admin "$SP/run-catalog-admin.sh"; fi

# ---- group 7: the browser, on a database run-core populated, with Basic OFF and an MFA key ---------------------------------------
if ! skipped ui-smoke; then
  if command -v node >/dev/null 2>&1 && NODE_PATH="${NODE_PATH:-}" node -e "require('playwright')" >/dev/null 2>&1; then
    boot_ppob2 || failed+=("boot:ui")
    echo "== (ui-smoke fixture: run-core) =="; "$SP/run-core.sh" > "$LOG_DIR/ui-fixture-core.log" 2>&1
    stop_pid APP_PID
    # Same database, new process: Basic off, MFA key set, cookie not Secure (plain HTTP).
    env PPOB2_ADMIN_BASIC_AUTH_ENABLED=false PPOB2_ADMIN_COOKIE_SECURE=false \
        PPOB2_ADMIN_MFA_ENCRYPTION_KEY="$(python3 -c 'import os,base64;print(base64.b64encode(os.urandom(32)).decode())')" \
        PPOB2_FULFILLMENT_STUBPROVIDER_FAILPROVIDERSKUIDS=2 PPOB2_FULFILLMENT_STUBPROVIDER_AMBIGUOUSPROVIDERSKUIDS=3 \
        PPOB2_FULFILLMENT_STUBPROVIDER_TIMEOUTPROVIDERSKUIDS=4 java -jar "$JAR" > "$LOG_DIR/app-ui.log" 2>&1 &
    APP_PID=$!
    if wait_health http://localhost:8080 "$APP_PID"; then SHOTS="${SHOTS:-}" suite ui-smoke node "$SP/ui-smoke.cjs"
    else RESULT[ui-smoke]="FAILED (app did not start)"; failed+=(ui-smoke); ran=$((ran + 1)); fi
  else
    RESULT[ui-smoke]="skipped (node + playwright not available)"; echo "== ui-smoke: skipped, playwright not on NODE_PATH =="
  fi
fi

cleanup
echo; echo "================================ summary ================================"
for n in core backoffice-read partner-secrets routing refund ppob1 admin-auth pattern-admin catalog-admin ui-smoke; do
  printf '  %-18s %s\n' "$n" "${RESULT[$n]:-not run}"
done
echo "  logs: $LOG_DIR"
if [ "$ran" -eq 0 ]; then echo "nothing ran"; exit 1; fi
[ "${#failed[@]}" -eq 0 ]
