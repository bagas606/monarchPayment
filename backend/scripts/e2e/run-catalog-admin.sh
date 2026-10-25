#!/usr/bin/env bash
# Catalog, pricing and supported-amount administration (PRD Sections 30.3, 40.3, 41.3, 41.11).
#
# Needs a FRESHLY SEEDED database and an app booted like run-core.sh (Basic auth on). Like run-pattern-admin.sh it builds
# and activates generations, so run it on its own database, not before run-core.sh.
SP="$(cd "$(dirname "$0")" && pwd)"
. "$SP/preflight-basic.sh"
"$SP/provision-secrets.sh" || exit 2
Q="$SP/q.sh"
B="${PPOB2_BASE_URL:-http://localhost:8080}"
pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
H=(-H 'Content-Type: application/json')
as() { local u="$1"; shift; curl -s -o /tmp/ca.$$ -w '%{http_code}' -u "$u" "${H[@]}" "$@"; }
adm() { as superadmin:admin123 "$@"; }
body() { cat /tmp/ca.$$; }
jf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
wait_built() { for _ in $(seq 1 120); do [ "$(scalar "select completed_at is not null from pattern_generation where id=$1")" = t ] && return 0; sleep 1; done; return 1; }
newgen() { adm -X POST "$B/admin/pattern-generations" >/dev/null; jf "$(body)" generation_id; }
audits() { scalar "select count(*) from audit_log where action='$1'"; }

$Q "insert into admin_user (username,password_hash) values ('viewer1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS'),('tech1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" >/dev/null 2>&1
$Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u join role r on (u.username='viewer1' and r.code='VIEWER') or (u.username='tech1' and r.code='TECH_SUPPORT') on conflict do nothing" >/dev/null 2>&1

echo "== Permissions =="
chk "VIEWER reads products" "$(as viewer1:admin123 $B/admin/products)" 200
chk "VIEWER reads SKUs with their current cost" "$(as viewer1:admin123 $B/admin/provider-skus)" 200
chk "...cost is present" "$(echo "$(body)" | grep -c '"current_cost":18000')" 1
chk "VIEWER cannot create a product" "$(as viewer1:admin123 -X POST $B/admin/products -d '{"code":"X1","name":"X","category":"X"}')" 403
chk "TECH_SUPPORT can create a product (catalog:edit)" "$(as tech1:admin123 -X POST $B/admin/products -d '{"code":"TS-PROD","name":"Tech product","category":"TS"}')" 201
chk "TECH_SUPPORT cannot set a price (pricing:edit)" "$(as tech1:admin123 -X POST $B/admin/provider-skus/1/prices -d '{"provider_cost":100,"reason":"x"}')" 403
chk "only SUPER_ADMIN holds pricing:edit and config:edit" \
    "$(scalar "select string_agg(distinct r.code, ',') from role_permission rp join role r on r.id=rp.role_id join permission p on p.id=rp.permission_id where p.code in ('pricing:edit','config:edit')")" SUPER_ADMIN

echo "== Products and providers =="
chk "create a product: 201" "$(adm -X POST $B/admin/products -d '{"code":"ONE","name":"Single-SKU product","category":"ONE"}')" 201
PONE=$(jf "$(body)" id)
chk "duplicate code: 409" "$(adm -X POST $B/admin/products -d '{"code":"ONE","name":"again","category":"ONE"}')" 409
chk "bad code: 400" "$(adm -X POST $B/admin/products -d '{"code":"a b","name":"x","category":"X"}')" 400
chk "blank name: 400" "$(adm -X POST $B/admin/products -d '{"code":"OK1","name":" ","category":"X"}')" 400
chk "rename and switch off: 200" "$(adm -X PATCH $B/admin/products/$(scalar "select id from product where code='TS-PROD'") -d '{"name":"Renamed","status":"INACTIVE"}')" 200
chk "...stored" "$(scalar "select name||'/'||status from product where code='TS-PROD'")" "Renamed/INACTIVE"
chk "bad product status: 400" "$(adm -X PATCH $B/admin/products/$PONE -d '{"status":"DELETED"}')" 400
chk "unknown product: 404" "$(adm -X PATCH $B/admin/products/999999 -d '{"name":"x"}')" 404
chk "create a provider: 201" "$(adm -X POST $B/admin/providers -d '{"code":"PONE","name":"Provider One-off","timeout_ms":4000}')" 201
POV=$(jf "$(body)" id)
chk "bad timeout: 400" "$(adm -X POST $B/admin/providers -d '{"code":"PBAD","name":"x","timeout_ms":5}')" 400
chk "degrade a provider: 200" "$(adm -X PATCH $B/admin/providers/$POV -d '{"status":"DEGRADED"}')" 200
chk "bad provider status: 400" "$(adm -X PATCH $B/admin/providers/$POV -d '{"status":"GONE"}')" 400
chk "nothing is deletable (no DELETE route): 405" "$(adm -X DELETE $B/admin/products/$PONE)" 405

echo "== SKUs =="
chk "create a SKU: 201" "$(adm -X POST $B/admin/provider-skus -d "{\"provider_id\":$POV,\"product_id\":$PONE,\"provider_sku_code\":\"ONE-5000\",\"face_value\":5000}")" 201
SONE=$(jf "$(body)" id)
chk "...it is unpriced, and the response says it joins routing only after a generation" "$(echo "$(body)" | grep -c "next generation")" 1
chk "duplicate SKU code: 409" "$(adm -X POST $B/admin/provider-skus -d "{\"provider_id\":$POV,\"product_id\":$PONE,\"provider_sku_code\":\"ONE-5000\",\"face_value\":5000}")" 409
chk "zero face value: 400" "$(adm -X POST $B/admin/provider-skus -d "{\"provider_id\":$POV,\"product_id\":$PONE,\"provider_sku_code\":\"ONE-0\",\"face_value\":0}")" 400
chk "unknown provider: 404" "$(adm -X POST $B/admin/provider-skus -d "{\"provider_id\":999999,\"product_id\":$PONE,\"provider_sku_code\":\"ONE-1\",\"face_value\":1000}")" 404
chk "missing field: 400" "$(adm -X POST $B/admin/provider-skus -d '{"provider_id":1}')" 400

echo "== Pricing (Section 40.3) =="
S1=$(scalar "select id from provider_sku where provider_sku_code='ML-20000-A'")
chk "price without a reason: 400" "$(adm -X POST $B/admin/provider-skus/$SONE/prices -d '{"provider_cost":4500}')" 400
chk "first price for the new SKU: 201" "$(adm -X POST $B/admin/provider-skus/$SONE/prices -d '{"provider_cost":4500,"reason":"initial contract price"}')" 201
chk "...version 1" "$(jf "$(body)" pricing_version)" 1
G=$(newgen); wait_built "$G"; chk "build a generation" "$(scalar "select status from pattern_generation where id=$G")" VALIDATING
chk "activate it (seed generation had less coverage: needs a reason)" \
    "$(adm -X POST $B/admin/pattern-generations/$G/activate -d '{"reason":"e2e baseline"}')" 200
$Q "update pattern_economics set score=555 where pattern_id in (select p.id from decomposition_pattern p where generation_id=$G and not exists (select 1 from decomposition_component c where c.pattern_id=p.id and c.provider_sku_id=$S1)) and snapshot_date=current_date" >/dev/null 2>&1
chk "change a price with a reason: 201" "$(adm -X POST $B/admin/provider-skus/$S1/prices -d '{"provider_cost":19500,"reason":"supplier increase 2026-10"}')" 201
chk "...new version, previous closed, exactly one active" \
    "$(scalar "select count(*) filter (where effective_until is null)||'/'||count(*)||'/'||max(pricing_version) from provider_price where provider_sku_id=$S1")" "1/2/2"
chk "...previous_cost reported" "$(jf "$(body)" previous_cost)" 18000
chk "...only patterns containing the SKU were re-scored" "$([ "$(jf "$(body)" patterns_rescored)" -gt 0 ] && echo t || echo f)" t
chk "...patterns WITHOUT the SKU were left alone (hand-set score survives)" \
    "$(scalar "select count(*) from pattern_economics where score=555 and snapshot_date=current_date")" "$(scalar "select count(*) from pattern_economics e join decomposition_pattern p on p.id=e.pattern_id where p.generation_id=$G and e.snapshot_date=current_date and not exists (select 1 from decomposition_component c where c.pattern_id=p.id and c.provider_sku_id=$S1)")"
chk "...the cost now reflects the new price" \
    "$(scalar "select e.provider_cost_total from pattern_economics e join decomposition_pattern p on p.id=e.pattern_id join decomposition_component c on c.pattern_id=p.id where p.generation_id=$G and p.parent_amount=20000 and c.provider_sku_id=$S1 and c.quantity=1 and p.component_count=1 and e.snapshot_date=current_date")" 19500
chk "...tomorrow's rows carry the new price as well" \
    "$(scalar "select e.provider_cost_total from pattern_economics e join decomposition_pattern p on p.id=e.pattern_id join decomposition_component c on c.pattern_id=p.id where p.generation_id=$G and p.parent_amount=20000 and c.provider_sku_id=$S1 and c.quantity=1 and p.component_count=1 and e.snapshot_date=current_date+1")" 19500
chk "price history lists both versions" "$(adm $B/admin/provider-skus/$S1/prices >/dev/null; echo "$(body)" | grep -o '"pricing_version"' | wc -l | tr -d ' ')" 2
chk "...audited with before and after" \
    "$(scalar "select (before_state->>'provider_cost')||'>'||(after_state->>'provider_cost') from audit_log where action='PROVIDER_PRICE_SET' and target_id=$S1 order by id desc limit 1")" "18000>19500"
chk "...and the reason is in the audit trail" \
    "$(scalar "select after_state->>'reason' from audit_log where action='PROVIDER_PRICE_SET' and target_id=$S1 order by id desc limit 1")" "supplierincrease2026-10"
chk "zero cost: 400" "$(adm -X POST $B/admin/provider-skus/$S1/prices -d '{"provider_cost":0,"reason":"x"}')" 400
chk "unknown SKU: 404" "$(adm -X POST $B/admin/provider-skus/999999/prices -d '{"provider_cost":100,"reason":"x"}')" 404

echo "== Structural change: face value (Section 30.3) =="
# ONE has a single SKU (5000). Give it amounts, build, activate: those amounts are then served only by that SKU.
adm -X POST $B/admin/supported-amounts -d '{"product_category":"ONE","amount":5000}' >/dev/null
adm -X POST $B/admin/supported-amounts -d '{"product_category":"ONE","amount":10000}' >/dev/null
G2=$(newgen); wait_built "$G2"
chk "activate a generation that includes ONE" "$(adm -X POST $B/admin/pattern-generations/$G2/activate)" 200
chk "ONE serves 5000 and 10000 through the single SKU" \
    "$(scalar "select count(distinct parent_amount) from decomposition_pattern p join decomposition_component c on c.pattern_id=p.id where p.generation_id=$G2 and c.provider_sku_id=$SONE")" 2
TOTAL=$(scalar "select count(*) from decomposition_pattern")
chk "face-value change without a reason: 400" "$(adm -X PATCH $B/admin/provider-skus/$SONE -d '{"face_value":6000}')" 400
chk "face-value change with a reason: 200" "$(adm -X PATCH $B/admin/provider-skus/$SONE -d '{"face_value":6000,"reason":"provider re-denominated"}')" 200
chk "...the patterns containing it were invalidated" "$([ "$(jf "$(body)" patterns_invalidated)" -ge 2 ] && echo t || echo f)" t
chk "...exactly those, none deleted" \
    "$(scalar "select (select count(*) from decomposition_pattern where structural_status='INVALIDATED' and id in (select pattern_id from decomposition_component where provider_sku_id=$SONE))||'/'||(select count(*) from decomposition_pattern where structural_status='INVALIDATED')||'/'||(select count(*) from decomposition_pattern)")" \
    "$(scalar "select count(*) from decomposition_pattern where id in (select pattern_id from decomposition_component where provider_sku_id=$SONE)")/$(scalar "select count(*) from decomposition_pattern where id in (select pattern_id from decomposition_component where provider_sku_id=$SONE)")/$TOTAL"
chk "...the lost coverage names product ONE's amounts" "$(echo "$(body)" | grep -c "\[$PONE,5000\]")" 1
chk "...and the face value changed" "$(scalar "select face_value from provider_sku where id=$SONE")" 6000
chk "...audited with the reason and the count" "$(scalar "select (after_state->>'reason') from audit_log where action='SKU_UPDATE' and target_id=$SONE order by id desc limit 1")" providerre-denominated
chk "invalidated patterns are listed" "$(adm "$B/admin/patterns?structural_status=INVALIDATED&size=100")" 200
chk "...and no longer served: an order for 5000 of ONE finds no pattern" \
    "$(scalar "select count(*) from decomposition_pattern where generation_id=$G2 and parent_amount=5000 and structural_status='VALID'")" 0
G3=$(newgen); wait_built "$G3"
chk "a new generation sees the new face value: 6000 is not a divisor of 5000, so 5000 has none" \
    "$(scalar "select count(*) from decomposition_pattern where generation_id=$G3 and parent_amount=5000 and exists (select 1 from decomposition_component c where c.pattern_id=decomposition_pattern.id and c.provider_sku_id=$SONE)")" 0
chk "...the report counts the unservable amounts (ONE's two, plus the seed's 10000 that no 20000-SKU can make)" \
    "$(scalar "select (report->'summary'->>'amounts_without_patterns') from pattern_generation where id=$G3")" 3
# ONE's patterns are already INVALIDATED, so the active generation does not serve ONE either: nothing is lost by switching.
chk "activating it is not a coverage regression: 200" "$(adm -X POST $B/admin/pattern-generations/$G3/activate)" 200

echo "== Switching a SKU off and quota =="
chk "disable a SKU: 200" "$(adm -X PATCH $B/admin/provider-skus/$SONE -d '{"status":"INACTIVE","quota_daily":50}')" 200
chk "...stored" "$(scalar "select status||'/'||quota_daily from provider_sku where id=$SONE")" "INACTIVE/50"
chk "...no patterns invalidated by a status change" "$(echo "$(body)" | grep -c patterns_invalidated)" 0
chk "bad quota: 400" "$(adm -X PATCH $B/admin/provider-skus/$SONE -d '{"quota_daily":0}')" 400
chk "unknown SKU: 404" "$(adm -X PATCH $B/admin/provider-skus/999999 -d '{"status":"ACTIVE"}')" 404

echo "== Supported amounts =="
chk "create an amount: 201" "$(adm -X POST $B/admin/supported-amounts -d '{"product_category":"GAME_TOPUP","amount":30000}')" 201
SA=$(jf "$(body)" id)
chk "...the response warns it cannot be fulfilled yet" "$(echo "$(body)" | grep -c "cannot be fulfilled")" 1
chk "duplicate: 409" "$(adm -X POST $B/admin/supported-amounts -d '{"product_category":"GAME_TOPUP","amount":30000}')" 409
chk "out of range: 400" "$(adm -X POST $B/admin/supported-amounts -d '{"product_category":"GAME_TOPUP","amount":20000000}')" 400
chk "the list shows it with no patterns behind it" "$(adm "$B/admin/supported-amounts?product_category=GAME_TOPUP" >/dev/null; echo "$(body)" | grep -o '"amount":30000,"status":"ACTIVE","effective_from":"[^"]*","active_patterns":0' | wc -l | tr -d ' ')" 1
chk "...while 20000 has some" "$(echo "$(body)" | grep -o '"amount":20000,"status":"ACTIVE","effective_from":"[^"]*","active_patterns":[1-9]' | wc -l | tr -d ' ')" 1
chk "the partner API lists it (amounts are configuration; patterns are a separate matter)" \
    "$($SP/call.sh GET '/api/v1/config/supported-amounts?product_code=MOBILE_LEGENDS' | grep -c 30000)" 1
chk "switch it off: 200" "$(adm -X PATCH $B/admin/supported-amounts/$SA -d '{"status":"INACTIVE"}')" 200
chk "...stored" "$(scalar "select status from supported_amount where id=$SA")" INACTIVE
chk "...and the partner API stops listing it" "$($SP/call.sh GET '/api/v1/config/supported-amounts?product_code=MOBILE_LEGENDS' | grep -c 30000)" 0
chk "bad status: 400" "$(adm -X PATCH $B/admin/supported-amounts/$SA -d '{"status":"X"}')" 400

echo "== Audit =="
for a in PRODUCT_CREATE PRODUCT_UPDATE PROVIDER_CREATE PROVIDER_UPDATE SKU_CREATE SKU_UPDATE PROVIDER_PRICE_SET SUPPORTED_AMOUNT_CREATE SUPPORTED_AMOUNT_UPDATE; do
  chk "$a is audited" "$([ "$(audits $a)" -ge 1 ] && echo t || echo f)" t
done
chk "a refused change wrote nothing (duplicate product)" "$(scalar "select count(*) from audit_log where action='PRODUCT_CREATE'")" 2

rm -f /tmp/ca.$$
echo; echo "================================"; echo "  PASS: $pass    FAIL: $fail"; echo "================================"
[ "$fail" -eq 0 ]
