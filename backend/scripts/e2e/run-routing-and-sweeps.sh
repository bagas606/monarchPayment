#!/usr/bin/env bash
# Second half of the e2e pass: routing eligibility (needs DB mutation) and the two cases that
# depend on QrExpirySweepJob's 60s tick. Split from final_run.sh only because these are slow.
SP="$(cd "$(dirname "$0")" && pwd)"
CALL="$SP/call.sh"; CB="$SP/cb.sh"; Q="$SP/q.sh"
pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
mkorder() {
  IDEMPOTENCY_KEY="$3" $CALL POST /api/v1/orders \
    "{\"product_code\":\"MOBILE_LEGENDS\",\"parent_amount\":$1,\"customer_reference\":\"$2\"}" \
    | grep -o '"order_id":"[^"]*"' | cut -d'"' -f4
}
pgref() { scalar "select pg_reference from payment p join parent_order o on o.id=p.parent_order_id where o.order_no='$1'"; }
state() { scalar "select state from parent_order where order_no='$1'"; }
patt()  { scalar "select pattern_id from parent_order where order_no='$1'"; }

SKU_A=$(scalar "select id from provider_sku where provider_sku_code='ML-20000-A'")
SKU_C=$(scalar "select id from provider_sku where provider_sku_code='ML-20000-C'")
P_ALT=$(scalar "select id from decomposition_pattern where pattern_hash=repeat('d',64)")   # [A x4] score 50
P_PREF=$(scalar "select id from decomposition_pattern where pattern_hash=repeat('e',64)")  # [C x4] score 99

echo "== baseline: 80000 must pick the higher-scored pattern ($P_PREF, score 99) =="
O=$(mkorder 80000 E-BASE "ebase-$RANDOM"); ORIGREF="R-$O" $CB "$(pgref "$O")" 80000 00 >/dev/null; sleep 6
chk "routing picks highest score" "$(patt "$O")" "$P_PREF"

echo "== TC-BE-021 negative margin / eligible=false is excluded =="
$Q "update pattern_economics set eligible=false, net_contribution=-500, net_margin_pct=-1.0 where pattern_id=$P_PREF" >/dev/null
O=$(mkorder 80000 E-021 "e021-$RANDOM"); ORIGREF="R-$O" $CB "$(pgref "$O")" 80000 00 >/dev/null; sleep 6
chk "TC-BE-021 alternate pattern selected" "$(patt "$O")" "$P_ALT"
$Q "update pattern_economics set eligible=true, net_contribution=7500, net_margin_pct=9.0 where pattern_id=$P_PREF" >/dev/null

echo "== TC-BE-020 SKU quota exhausted excludes containing patterns =="
# sku C already has usage from the baseline order above (quantity 4); cap it below that.
USED=$(scalar "select daily_usage from sku_usage where provider_sku_id=$SKU_C and usage_date=CURRENT_DATE")
$Q "update provider_sku set quota_daily=1 where id=$SKU_C" >/dev/null
echo "  (sku C daily_usage=$USED, quota_daily now 1)"
O=$(mkorder 80000 E-020 "e020-$RANDOM"); ORIGREF="R-$O" $CB "$(pgref "$O")" 80000 00 >/dev/null; sleep 6
chk "TC-BE-020 quota-exhausted pattern excluded" "$(patt "$O")" "$P_ALT"
$Q "update provider_sku set quota_daily=NULL where id=$SKU_C" >/dev/null

echo "== TC-BE-023 disabled SKU excludes containing patterns at routing =="
$Q "update provider_sku set status='INACTIVE' where id=$SKU_C" >/dev/null
O=$(mkorder 80000 E-023 "e023-$RANDOM"); ORIGREF="R-$O" $CB "$(pgref "$O")" 80000 00 >/dev/null; sleep 6
chk "TC-BE-023 inactive-SKU pattern excluded" "$(patt "$O")" "$P_ALT"
# The Section 30.3 invalidation half is a known gap: nothing ever writes structural_status.
chk "TC-BE-023 (known gap) pattern still VALID" \
    "$(scalar "select structural_status from decomposition_pattern where id=$P_PREF")" VALID
$Q "update provider_sku set status='ACTIVE' where id=$SKU_C" >/dev/null

echo "== TC-BE-028 reconciliation discrepancy values =="
OID=$(scalar "select id from parent_order where customer_reference='F16' limit 1")
chk "TC-BE-028 partial-order discrepancy = -20000" \
    "$(scalar "select discrepancy from reconciliation where recon_type='ORDER_VS_FULFILLMENT' and reference_id=$OID")" -20000
chk "TC-BE-028 status OPEN" \
    "$(scalar "select status from reconciliation where recon_type='ORDER_VS_FULFILLMENT' and reference_id=$OID")" OPEN

echo "== TC-BE-007 + TC-BE-031 (EXPIRED path) -- waits for the 60s sweep tick =="
O=$(mkorder 20000 E-031 "e031-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
$Q "update parent_order set expires_at = now() - interval '2 hours' where order_no='$O'" >/dev/null
for i in $(seq 1 14); do
  [ "$(state "$O")" = "EXPIRED" ] && break
  sleep 8
done
chk "TC-BE-007 swept to EXPIRED"          "$(state "$O")" EXPIRED
chk "TC-BE-007 payment also EXPIRED"      "$(scalar "select status from payment where id=$PID")" EXPIRED
ORIGREF="R-$O-late" $CB "$(pgref "$O")" 20000 00 >/dev/null; sleep 5
chk "TC-BE-031 order stays EXPIRED"       "$(state "$O")" EXPIRED
chk "TC-BE-031 no fulfilment"             "$(scalar "select count(*) from child_order where parent_order_id=$OID")" 0
chk "TC-BE-031 PAYMENT_VS_PG opened"      "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 1
# Direction matters and is the whole operator-facing value: our payment row says EXPIRED (collected
# nothing), the PG says it collected 20000, so this is a +20000 SURPLUS. An earlier version recorded
# 20000/20000 -> discrepancy 0, i.e. the single most expensive anomaly looked like noise.
chk "TC-BE-031 discrepancy = +20000 surplus" "$(scalar "select discrepancy from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 20000

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
