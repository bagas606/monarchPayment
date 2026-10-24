#!/usr/bin/env bash
# Pattern generation, activation, rollback, scoring and configuration (PRD Sections 28-31, 40.2, 41.4, 41.11).
#
# Needs a FRESHLY SEEDED database and an app booted with PPOB2_ADMIN_BASIC_AUTH_ENABLED=true and the stub-provider
# knobs of run-core.sh. Run it LAST and on its own database: it activates generated generations, which replaces the
# hand-seeded one (and its hand-set scores 50/99) that run-core.sh's routing cases depend on.
SP="$(cd "$(dirname "$0")" && pwd)"
. "$SP/preflight-basic.sh"
"$SP/provision-secrets.sh" || exit 2
CALL="$SP/call.sh"; CB="$SP/cb.sh"; Q="$SP/q.sh"
B="${PPOB2_BASE_URL:-http://localhost:8080}"
pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
H=(-H 'Content-Type: application/json')
as() { local u="$1"; shift; curl -s -o /tmp/pa.$$ -w '%{http_code}' -u "$u" "${H[@]}" "$@"; }   # prints status; body in /tmp/pa.$$
adm() { as superadmin:admin123 "$@"; }
body() { cat /tmp/pa.$$; }
jf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
mkorder() { local r; r=$(IDEMPOTENCY_KEY="$3" $CALL POST /api/v1/orders "{\"product_code\":\"MOBILE_LEGENDS\",\"parent_amount\":$1,\"customer_reference\":\"$2\"}"); echo "$r" | grep -o '"order_id":"[^"]*"' | cut -d'"' -f4; }
pgref() { scalar "select pg_reference from payment p join parent_order o on o.id=p.parent_order_id where o.order_no='$1'"; }
state() { scalar "select state from parent_order where order_no='$1'"; }
wait_built() { # generation id -> waits until it left BUILDING (max ~120s)
  for _ in $(seq 1 120); do
    [ "$(scalar "select completed_at is not null from pattern_generation where id=$1")" = t ] && return 0; sleep 1
  done; return 1
}
newgen() { adm -X POST "$B/admin/pattern-generations" >/dev/null; jf "$(body)" generation_id; }

# ---- fixture: a catalog rich enough to need real decomposition ------------------------------------------------------------
$Q "insert into provider (code, name, status, rate_limit_per_min) values ('PROV2','Provider 2','ACTIVE',8000) on conflict do nothing" >/dev/null 2>&1
$Q "insert into provider_sku (provider_id, product_id, provider_sku_code, face_value, status)
    select pr.id, pd.id, v.code, v.fv, 'ACTIVE' from provider pr, product pd, (values ('ML-10000-X',10000),('ML-50000-X',50000)) v(code,fv)
    where pr.code='PROV2' and pd.code='MOBILE_LEGENDS' on conflict do nothing" >/dev/null 2>&1
$Q "insert into provider_price (provider_sku_id, pricing_version, provider_cost, effective_from)
    select id, 1, case face_value when 10000 then 9500 else 46000 end, now() - interval '1 day' from provider_sku where provider_sku_code in ('ML-10000-X','ML-50000-X')" >/dev/null 2>&1
$Q "insert into admin_user (username,password_hash) values ('viewer1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS'),('tech1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" >/dev/null 2>&1
$Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u join role r on (u.username='viewer1' and r.code='VIEWER') or (u.username='tech1' and r.code='TECH_SUPPORT') on conflict do nothing" >/dev/null 2>&1
SEEDGEN=$(scalar "select id from pattern_generation where status='ACTIVE'")

echo "== Permissions (Section 42.2) =="
chk "anonymous: 401" "$(curl -s -o /dev/null -w '%{http_code}' $B/admin/pattern-generations)" 401
chk "VIEWER can read generations" "$(as viewer1:admin123 $B/admin/pattern-generations)" 200
chk "VIEWER cannot start one"     "$(as viewer1:admin123 -X POST $B/admin/pattern-generations)" 403
chk "VIEWER cannot edit configuration" "$(as viewer1:admin123 -X PUT $B/admin/configuration/routing.weight.margin -d '{"value":2}')" 403
chk "TECH_SUPPORT cannot activate" "$(as tech1:admin123 -X POST $B/admin/pattern-generations/$SEEDGEN/activate)" 403
chk "only SUPER_ADMIN holds pattern:activate" \
    "$(scalar "select string_agg(r.code, ',') from role_permission rp join role r on r.id=rp.role_id join permission p on p.id=rp.permission_id where p.code='pattern:activate'")" SUPER_ADMIN

echo "== Configuration (Section 41.11) =="
chk "list: 200" "$(adm $B/admin/configuration)" 200
chk "...every key listed with its default" "$(echo "$(body)" | grep -o '"is_default":true' | wc -l | tr -d ' ')" 8
chk "set a weight: 200" "$(adm -X PUT $B/admin/configuration/routing.weight.childcount -d '{"value":1.5}')" 200
chk "...stored as an override" "$(scalar "select config_value::text from configuration where config_key='routing.weight.childcount'")" 1.5
chk "...audited with before and after" \
    "$(scalar "select (after_state->>'value') || '/' || (before_state->>'value') from audit_log where action='CONFIGURATION_CHANGE' order by id desc limit 1")" "1.5/0.5"
chk "out of range: 400" "$(adm -X PUT $B/admin/configuration/routing.weight.childcount -d '{"value":1000}')" 400
chk "a fraction for an integer key: 400" "$(adm -X PUT $B/admin/configuration/generation.max_total_quantity -d '{"value":3.5}')" 400
chk "unknown key: 404" "$(adm -X PUT $B/admin/configuration/made.up.key -d '{"value":1}')" 404
chk "missing value: 400" "$(adm -X PUT $B/admin/configuration/routing.weight.childcount -d '{}')" 400
chk "a refused change stored nothing" "$(scalar "select count(*) from configuration")" 1
adm -X PUT $B/admin/configuration/routing.weight.childcount -d '{"value":0.5}' >/dev/null

echo "== Generation (Section 29 / 40.2) =="
BEFORE_ACTIVE=$(scalar "select count(*) from pattern_generation where status='ACTIVE'")
chk "TECH_SUPPORT may start a generation: 202" "$(as tech1:admin123 -X POST $B/admin/pattern-generations)" 202
G2=$(jf "$(body)" generation_id)
chk "...it answers at once with status BUILDING or already built" "$(scalar "select status in ('BUILDING','VALIDATING') from pattern_generation where id=$G2")" t
chk "a second trigger while one runs is refused: 409" \
    "$($Q "insert into pattern_generation (status, triggered_by, scope, started_at) values ('BUILDING','E2E','FULL',now())" >/dev/null 2>&1; adm -X POST $B/admin/pattern-generations)" 409
$Q "delete from pattern_generation where triggered_by='E2E'" >/dev/null 2>&1
wait_built "$G2"; chk "generation builds" "$(scalar "select status from pattern_generation where id=$G2")" VALIDATING
chk "...marked complete, waiting for a human" "$(scalar "select completed_at is not null from pattern_generation where id=$G2")" t
chk "...serving nothing yet: the seeded generation is still the only ACTIVE one" \
    "$(scalar "select id from pattern_generation where status='ACTIVE'")" "$SEEDGEN"
chk "...the parameters it used are recorded" "$(scalar "select params->>'algorithm' from pattern_generation where id=$G2")" "levelwise-bounded-search/1"
chk "...and a catalog fingerprint" "$(scalar "select length(params->>'catalog_fingerprint') from pattern_generation where id=$G2")" 64
NPAT=$(scalar "select count(*) from decomposition_pattern where generation_id=$G2")
chk "pattern_count matches the rows" "$(scalar "select pattern_count from pattern_generation where id=$G2")" "$NPAT"
chk "every supported amount got patterns (including 10000, which the seeded generation could not serve)" \
    "$(scalar "select count(distinct parent_amount) from decomposition_pattern where generation_id=$G2")" 6
chk "SECTION 28.2: every pattern's components sum to its amount, checked independently in SQL" \
    "$(scalar "select count(*) from decomposition_pattern p where generation_id=$G2 and parent_amount <> (select sum(quantity*face_value) from decomposition_component c where c.pattern_id=p.id)")" 0
chk "...the JSONB snapshot agrees with the component rows" \
    "$(scalar "select count(*) from decomposition_pattern p where generation_id=$G2 and components <> (select jsonb_agg(jsonb_build_object('provider_sku_id',provider_sku_id,'quantity',quantity,'face_value',face_value) order by provider_sku_id) from decomposition_component c where c.pattern_id=p.id)")" 0
chk "...no pattern uses a face value the SKU does not have" \
    "$(scalar "select count(*) from decomposition_component c join decomposition_pattern p on p.id=c.pattern_id join provider_sku s on s.id=c.provider_sku_id where p.generation_id=$G2 and s.face_value<>c.face_value")" 0
chk "...hashes are 64 hex and unique within the generation" \
    "$(scalar "select count(*) filter (where pattern_hash ~ '^[0-9a-f]{64}\$') = count(*) and count(distinct pattern_hash) = count(*) from decomposition_pattern where generation_id=$G2")" t
chk "...fewest child orders first for 100000 (two 50000s)" \
    "$(scalar "select min(total_quantity) from decomposition_pattern where generation_id=$G2 and parent_amount=100000")" 2
chk "...economics exist for every pattern for today" \
    "$(scalar "select count(*) from decomposition_pattern p where generation_id=$G2 and not exists (select 1 from pattern_economics e where e.pattern_id=p.id and e.snapshot_date=current_date)")" 0
chk "...the report lists each amount" "$(scalar "select jsonb_array_length(report->'products'->0->'amounts') from pattern_generation where id=$G2")" 6
chk "...none hit the limit or budget" "$(scalar "select (report->'summary'->>'amounts_at_pattern_limit') || (report->'summary'->>'amounts_budget_exhausted') from pattern_generation where id=$G2")" 00
chk "detail: 200" "$(adm $B/admin/pattern-generations/$G2)" 200
chk "list shows it" "$(echo "$(adm $B/admin/pattern-generations >/dev/null; body)" | grep -c "\"id\":$G2")" 1

echo "== Determinism (Section 29.1) =="
G3=$(newgen); wait_built "$G3"
chk "a second run over the same catalog yields identical patterns" \
    "$(scalar "select (select md5(string_agg(pattern_hash, ',' order by pattern_hash)) from decomposition_pattern where generation_id=$G2) = (select md5(string_agg(pattern_hash, ',' order by pattern_hash)) from decomposition_pattern where generation_id=$G3)")" t
chk "...and the same fingerprint" \
    "$(scalar "select (select params->>'catalog_fingerprint' from pattern_generation where id=$G2) = (select params->>'catalog_fingerprint' from pattern_generation where id=$G3)")" t

echo "== Activation, coverage, rollback (Section 40.2) =="
chk "activate a never-built id: 404" "$(adm -X POST $B/admin/pattern-generations/999999/activate)" 404
chk "activate G2: 200" "$(adm -X POST $B/admin/pattern-generations/$G2/activate)" 200
chk "...it is now the single ACTIVE generation" "$(scalar "select string_agg(id::text, ',') from pattern_generation where status='ACTIVE'")" "$G2"
chk "...tomorrow's economics were written too (routing reads today only, so the day must not start with a gap)" \
    "$(scalar "select count(*) from decomposition_pattern p where generation_id=$G2 and not exists (select 1 from pattern_economics e where e.pattern_id=p.id and e.snapshot_date=current_date+1)")" 0
chk "...the seeded one is SUPERSEDED, not deleted" "$(scalar "select status from pattern_generation where id=$SEEDGEN")" SUPERSEDED
chk "...coverage gained 10000" "$(echo "$(body)" | grep -c '"gained_coverage":\[\[[0-9]*,10000\]\]')" 1
chk "...audited" "$(scalar "select count(*) from audit_log where action='PATTERN_GENERATION_ACTIVATE' and target_id=$G2")" 1
chk "activating the ACTIVE one again: 409" "$(adm -X POST $B/admin/pattern-generations/$G2/activate)" 409
chk "activating a built-but-unfinished or failed one: 409" \
    "$($Q "insert into pattern_generation (status, triggered_by, scope, started_at) values ('FAILED','E2E','FULL',now())" >/dev/null 2>&1; adm -X POST $B/admin/pattern-generations/$(scalar "select max(id) from pattern_generation")/activate)" 409
$Q "delete from pattern_generation where triggered_by='E2E'" >/dev/null 2>&1

echo "== Orders on a generated generation =="
O=$(mkorder 10000 PA-1 "pa-$RANDOM-1"); chk "an order for 10000 (no pattern before) is created" "$([ -n "$O" ] && echo t || echo f)" t
$CB "$(pgref "$O")" 10000 00 >/dev/null; sleep 6
chk "...and is fulfilled through a generated pattern" "$(state "$O")" SUCCESS
chk "...the pattern belongs to the new generation" \
    "$(scalar "select g.id from parent_order o join decomposition_pattern p on p.id=o.pattern_id join pattern_generation g on g.id=p.generation_id where o.order_no='$O'")" "$G2"
O2=$(mkorder 100000 PA-2 "pa-$RANDOM-2"); $CB "$(pgref "$O2")" 100000 00 >/dev/null; sleep 6
chk "100000 fulfils in the fewest child orders" "$(scalar "select count(*) from child_order c join parent_order o on o.id=c.parent_order_id where o.order_no='$O2'")" 1

echo "== Re-scoring follows price changes (Section 30.2) =="
# 20000-face SKUs cost 18000 (10%). Raise PROV1's to 21000: a single-SKU pattern now loses money; two 10000s at 9500 do not.
$Q "update provider_price set effective_until=now() where effective_until is null and provider_sku_id in (select id from provider_sku where provider_sku_code like 'ML-20000-%')" >/dev/null 2>&1
$Q "insert into provider_price (provider_sku_id, pricing_version, provider_cost, effective_from) select id, 2, 21000, now() from provider_sku where provider_sku_code like 'ML-20000-%'" >/dev/null 2>&1
chk "rescore: 200" "$(adm -X POST $B/admin/pattern-economics/rescore)" 200
chk "...a 20000 pattern that now loses money is ineligible" \
    "$(scalar "select bool_or(e.eligible) from decomposition_pattern p join pattern_economics e on e.pattern_id=p.id and e.snapshot_date=current_date where p.generation_id=$G2 and p.parent_amount=20000 and p.component_count=1 and exists (select 1 from decomposition_component c join provider_sku s on s.id=c.provider_sku_id where c.pattern_id=p.id and s.provider_sku_code like 'ML-20000-%')")" f
chk "...while the two-10000 pattern stays eligible" \
    "$(scalar "select bool_and(e.eligible) from decomposition_pattern p join pattern_economics e on e.pattern_id=p.id and e.snapshot_date=current_date join decomposition_component c on c.pattern_id=p.id join provider_sku s on s.id=c.provider_sku_id where p.generation_id=$G2 and p.parent_amount=20000 and s.provider_sku_code='ML-10000-X'")" t
chk "...cost is the sum of the active prices" \
    "$(scalar "select e.provider_cost_total from pattern_economics e join decomposition_pattern p on p.id=e.pattern_id join decomposition_component c on c.pattern_id=p.id join provider_sku s on s.id=c.provider_sku_id where p.generation_id=$G2 and p.parent_amount=20000 and s.provider_sku_code='ML-10000-X' and c.quantity=2 and e.snapshot_date=current_date")" 19000
chk "...net margin percent is computed from it" \
    "$(scalar "select e.net_margin_pct from pattern_economics e join decomposition_pattern p on p.id=e.pattern_id join decomposition_component c on c.pattern_id=p.id join provider_sku s on s.id=c.provider_sku_id where p.generation_id=$G2 and p.parent_amount=20000 and s.provider_sku_code='ML-10000-X' and c.quantity=2 and e.snapshot_date=current_date")" 5.0000
O3=$(mkorder 20000 PA-3 "pa-$RANDOM-3"); $CB "$(pgref "$O3")" 20000 00 >/dev/null; sleep 6
chk "an order for 20000 is routed AWAY from the loss-making pattern" \
    "$(scalar "select count(*) from child_order c join parent_order o on o.id=c.parent_order_id join provider_sku s on s.id=c.provider_sku_id where o.order_no='$O3' and s.provider_sku_code='ML-10000-X'")" 1
chk "...and fulfils" "$(state "$O3")" SUCCESS
chk "rescore audited" "$(scalar "select count(*) from audit_log where action='PATTERN_ECONOMICS_RESCORE'")" 1
# ensure() must never overwrite: a hand-set score survives
$Q "update pattern_economics set score=777 where pattern_id=(select min(id) from decomposition_pattern where generation_id=$G2)" >/dev/null 2>&1

echo "== Coverage regression is refused (Section 31.2) =="
adm -X PUT $B/admin/configuration/generation.max_total_quantity -d '{"value":1}' >/dev/null
G4=$(newgen); wait_built "$G4"
chk "a generation built with a quantity limit of 1 covers fewer amounts" "$(scalar "select count(distinct parent_amount) from decomposition_pattern where generation_id=$G4")" 2
chk "...the report says which amounts have none" \
    "$(scalar "select (report->'summary'->>'amounts_without_patterns') from pattern_generation where id=$G4")" 4
chk "activating it without a reason: 409 COVERAGE_REGRESSION" "$(adm -X POST $B/admin/pattern-generations/$G4/activate)" 409
chk "...with the error code" "$(echo "$(body)" | grep -c COVERAGE_REGRESSION)" 1
chk "...and nothing changed" "$(scalar "select id from pattern_generation where status='ACTIVE'")" "$G2"
chk "with an explicit reason it is allowed: 200" "$(adm -X POST $B/admin/pattern-generations/$G4/activate -d '{"reason":"e2e: accepting reduced coverage"}')" 200
chk "...the reason is in the audit trail" \
    "$(scalar "select after_state->>'reason' from audit_log where action='PATTERN_GENERATION_ACTIVATE' and target_id=$G4")" "e2e:acceptingreducedcoverage"
chk "ROLLBACK: re-activating the superseded G2: 200" "$(adm -X POST $B/admin/pattern-generations/$G2/activate)" 200
chk "...G2 is ACTIVE again and G4 superseded" \
    "$(scalar "select (select status from pattern_generation where id=$G2) || (select status from pattern_generation where id=$G4)")" ACTIVESUPERSEDED
chk "...rollback re-scored for today (the hand-set score is gone)" \
    "$(scalar "select count(*) from pattern_economics where score=777")" 0
adm -X PUT $B/admin/configuration/generation.max_total_quantity -d '{"value":40}' >/dev/null

echo "== Discard =="
G5=$(newgen); wait_built "$G5"
chk "discard a built generation: 200" "$(adm -X POST $B/admin/pattern-generations/$G5/discard)" 200
chk "...it is FAILED and can no longer be activated" "$(adm -X POST $B/admin/pattern-generations/$G5/activate)" 409
chk "discarding it again: 409" "$(adm -X POST $B/admin/pattern-generations/$G5/discard)" 409
chk "discarding the ACTIVE one: 409" "$(adm -X POST $B/admin/pattern-generations/$G2/discard)" 409

echo "== Failure is recorded, not swallowed =="
$Q "update provider_sku set quota_daily=0 where provider_sku_code='ML-50000-X'" >/dev/null 2>&1   # cap 0: SKU is simply unusable, not an error
G6=$(newgen); wait_built "$G6"
chk "a SKU with quota 0 is left out of every pattern" \
    "$(scalar "select count(*) from decomposition_component c join decomposition_pattern p on p.id=c.pattern_id join provider_sku s on s.id=c.provider_sku_id where p.generation_id=$G6 and s.provider_sku_code='ML-50000-X'")" 0
$Q "update provider_sku set quota_daily=null where provider_sku_code='ML-50000-X'" >/dev/null 2>&1
$Q "insert into pattern_generation (status, triggered_by, scope, started_at) values ('BUILDING','E2E','FULL',now())" >/dev/null 2>&1
chk "failInterrupted would clear this orphan; until then a trigger is refused" "$(adm -X POST $B/admin/pattern-generations)" 409
$Q "delete from pattern_generation where triggered_by='E2E'" >/dev/null 2>&1

echo "== Read APIs (Section 41.4) =="
chk "patterns of the ACTIVE generation, by amount: 200" "$(adm "$B/admin/patterns?parent_amount=40000&size=100")" 200
chk "...only that amount" "$(echo "$(body)" | grep -o '"parent_amount":[0-9]*' | sort -u | tr -d '\n')" '"parent_amount":40000'
N=$(echo "$(body)" | grep -o '"pattern_hash"' | wc -l | tr -d ' ')
chk "...every row carries today's economics" "$(echo "$(body)" | grep -o '"economics_today":{' | wc -l | tr -d ' ')" "$N"
chk "...and a non-empty component list" "$(echo "$(body)" | grep -o '"components":\[{' | wc -l | tr -d ' ')" "$N"
PID=$(scalar "select min(id) from decomposition_pattern where generation_id=$G2 and parent_amount=40000")
chk "detail with economics history: 200" "$(adm $B/admin/patterns/$PID)" 200
chk "...components include the SKU code" "$(echo "$(body)" | grep -c provider_sku_code)" 1
chk "unknown pattern: 404" "$(adm $B/admin/patterns/999999999)" 404
chk "filter by eligibility" "$(adm "$B/admin/patterns?parent_amount=20000&eligible=false&size=100")" 200
chk "...returns only ineligible rows" "$(echo "$(body)" | grep -c '"eligible":true')" 0

rm -f /tmp/pa.$$
echo; echo "================================"; echo "  PASS: $pass    FAIL: $fail"; echo "================================"
[ "$fail" -eq 0 ]
