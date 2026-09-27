#!/usr/bin/env bash
# Final clean-slate verification pass. Assumes: fresh DB, seeded via dev-seed.sql, app up on 8080
# with fail=2 ambiguous=3 timeout=4.
SP="$(cd "$(dirname "$0")" && pwd)"
CALL="$SP/call.sh"; CB="$SP/cb.sh"; Q="$SP/q.sh"
pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
code() { echo "$1" | grep -o 'HTTP [0-9]*' | head -1 | awk '{print $2}'; }

mkorder() { # amount ref key -> echoes order_no
  local r
  r=$(IDEMPOTENCY_KEY="$3" $CALL POST /api/v1/orders "{\"product_code\":\"MOBILE_LEGENDS\",\"parent_amount\":$1,\"customer_reference\":\"$2\"}")
  echo "$r" | grep -o '"order_id":"[^"]*"' | cut -d'"' -f4
}
pgref() { scalar "select pg_reference from payment p join parent_order o on o.id=p.parent_order_id where o.order_no='$1'"; }
state() { scalar "select state from parent_order where order_no='$1'"; }

echo "== Error mapping (was: all 500) =="
chk "unknown path -> 404"         "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/nope)" 404
chk "GET on POST-only -> 405"     "$(code "$($CALL GET /api/v1/orders)")" 405
chk "malformed JSON -> 400"       "$(code "$($CALL POST /api/v1/orders '{"x":')")" 400
chk "missing Idempotency-Key -> 400" "$(code "$($CALL POST /api/v1/orders '{"product_code":"MOBILE_LEGENDS","parent_amount":20000}')")" 400

echo "== TC-BE-001 / TC-BE-006: create order, supported amount =="
R=$(IDEMPOTENCY_KEY="f1-$RANDOM" $CALL POST /api/v1/orders '{"product_code":"MOBILE_LEGENDS","parent_amount":20000,"customer_reference":"F1"}')
O1=$(echo "$R" | grep -o '"order_id":"[^"]*"' | cut -d'"' -f4)
chk "TC-BE-001 HTTP 201"                "$(code "$R")" 201
chk "TC-BE-001 state PAYMENT_PENDING"   "$(echo "$R" | grep -o '"state":"[^"]*"' | head -1 | cut -d'"' -f4)" PAYMENT_PENDING
chk "TC-BE-001 persisted PAYMENT_PENDING" "$(state "$O1")" PAYMENT_PENDING
chk "TC-BE-006 QR payload returned"     "$(echo "$R" | grep -co '"qr_payload":"00020101[^"]*"')" 1
chk "TC-BE-006 payment row PENDING"     "$(scalar "select status from payment where parent_order_id = (select id from parent_order where order_no = '$O1')")" PENDING

echo "== Order creation matrix =="
chk "TC-BE-002 unsupported amount -> 422" "$(code "$(IDEMPOTENCY_KEY=f2-$RANDOM $CALL POST /api/v1/orders '{"product_code":"MOBILE_LEGENDS","parent_amount":15000,"customer_reference":"F2"}')")" 422
chk "TC-BE-003 above ceiling -> 422"      "$(code "$(IDEMPOTENCY_KEY=f3-$RANDOM $CALL POST /api/v1/orders '{"product_code":"MOBILE_LEGENDS","parent_amount":20000000,"customer_reference":"F3"}')")" 422
K="f4-$RANDOM"
A=$(mkorder 20000 F4 "$K"); B=$(mkorder 20000 F4 "$K")
chk "TC-BE-004 same key+body -> same order" "$A" "$B"
chk "TC-BE-005 same key, diff body -> 409" "$(code "$(IDEMPOTENCY_KEY=$K $CALL POST /api/v1/orders '{"product_code":"MOBILE_LEGENDS","parent_amount":40000,"customer_reference":"DIFF"}')")" 409

echo "== TC-BE-017 all children succeed =="
O=$(mkorder 20000 F17 "f17-$RANDOM"); ORIGREF="R-$O" $CB "$(pgref "$O")" 20000 00 >/dev/null; sleep 5
chk "TC-BE-017 parent SUCCESS" "$(state "$O")" SUCCESS

echo "== TC-BE-016 partial =="
O=$(mkorder 40000 F16 "f16-$RANDOM"); ORIGREF="R-$O" $CB "$(pgref "$O")" 40000 00 >/dev/null; sleep 6
chk "TC-BE-016 parent PARTIAL_FAILED" "$(state "$O")" PARTIAL_FAILED

echo "== TC-BE-013 provider timeout, retry exhaustion =="
O=$(mkorder 100000 F13 "f13-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
ORIGREF="R-$O" $CB "$(pgref "$O")" 100000 00 >/dev/null; sleep 6
chk "TC-BE-013 parent FAILED"                "$(state "$O")" FAILED
chk "TC-BE-013 exactly 1 provider_transaction" "$(scalar "select count(*) from provider_transaction where child_order_id in (select id from child_order where parent_order_id=$OID)")" 1
chk "TC-BE-013 no provider debit"            "$(scalar "select count(*) from ledger_entry where ledger_type='PROVIDER' and reference_id in (select id from child_order where parent_order_id=$OID)")" 0

echo "== TC-BE-029 / 030 cancel =="
O=$(mkorder 10000 F29 "f29-$RANDOM")
chk "TC-BE-029 cancel PAYMENT_PENDING -> 200" "$(code "$($CALL POST /api/v1/orders/$O/cancel)")" 200
chk "TC-BE-029 state CANCELLED" "$(state "$O")" CANCELLED
S=$(mkorder 20000 F30 "f30-$RANDOM"); ORIGREF="R-$S" $CB "$(pgref "$S")" 20000 00 >/dev/null; sleep 5
chk "TC-BE-030 cancel SUCCESS -> 409" "$(code "$($CALL POST /api/v1/orders/$S/cancel)")" 409
chk "TC-BE-030 state still SUCCESS" "$(state "$S")" SUCCESS

echo "== Callback anomalies (bugs 2-4) =="
# amount mismatch
O=$(mkorder 20000 FAMT "famt-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O-amt" $CB "$(pgref "$O")" 1 00 >/dev/null; sleep 4
chk "amount mismatch: payment stays PENDING" "$(scalar "select status from payment where id=$PID")" PENDING
chk "amount mismatch: no ledger row"         "$(scalar "select count(*) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID")" 0
chk "amount mismatch: PAYMENT_VS_PG opened"  "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 1
chk "amount mismatch: discrepancy -19999"    "$(scalar "select discrepancy from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" -19999

# TC-BE-032 FAILED after SUCCESS
O=$(mkorder 20000 F32 "f32-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O-ok" $CB "$(pgref "$O")" 20000 00 >/dev/null; sleep 5
PAID_BEFORE=$(scalar "select paid_at from payment where id=$PID")
ORIGREF="R-$O-fail" $CB "$(pgref "$O")" 20000 06 >/dev/null; sleep 3
chk "TC-BE-032 SUCCESS not overwritten" "$(scalar "select status from payment where id=$PID")" SUCCESS
chk "TC-BE-032 paid_at unchanged"       "$(scalar "select paid_at from payment where id=$PID")" "$PAID_BEFORE"
chk "TC-BE-032 anomaly recorded"        "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 1
# The row must be LEGIBLE, not merely present: reconciliation has no reason column, so expected/actual
# are all an operator sees. An earlier version recorded 20000/20000 -> discrepancy 0, which reads as
# noise for what is actually "we booked a payment the PG now reverses".
chk "TC-BE-032 discrepancy = -20000"    "$(scalar "select discrepancy from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" -20000

# 04 Refunded and 05 Canceled after SUCCESS. The first version of the out-of-order fix handled only
# 06, so 05 merely logged and 04 fell through to "Unrecognized status" and was acknowledged 200.
for ST in 04 05; do
  O=$(mkorder 20000 "F32-$ST" "f32$ST-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
  PID=$(scalar "select id from payment where parent_order_id=$OID")
  ORIGREF="R-$O-ok" $CB "$(pgref "$O")" 20000 00 >/dev/null; sleep 5
  ORIGREF="R-$O-$ST" $CB "$(pgref "$O")" 20000 "$ST" >/dev/null; sleep 3
  chk "terminal status $ST after SUCCESS: SUCCESS preserved" "$(scalar "select status from payment where id=$PID")" SUCCESS
  chk "terminal status $ST after SUCCESS: anomaly recorded"  "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 1
  chk "terminal status $ST after SUCCESS: discrepancy -20000" "$(scalar "select discrepancy from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" -20000
done

echo "== amount format + anomaly edge cases (all four were defects in the first version of the fix) =="
# An unparseable amount must NOT block the payment: refusing money over an unproven format assumption
# of ours would turn one wrong guess into a total payment outage.
O=$(mkorder 20000 F-UNV "funv-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID"); P=$(pgref "$O")
ORIGREF="R-$O-unv" RAWAMT="abc" $CB "$P" 20000 00 >/dev/null; sleep 5
chk "unparseable amount: payment still applied"  "$(scalar "select status from payment where id=$PID")" SUCCESS
chk "unparseable amount: AMOUNT_UNVERIFIED row"  "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 1
chk "unparseable amount: discrepancy 0 (applied in full)" "$(scalar "select discrepancy from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 0
# ...and a later reversal on that SAME payment must still be recorded. A hasOpenDiscrepancy guard here
# used to swallow it, which is worst exactly in the degraded mode AMOUNT_UNVERIFIED exists for.
ORIGREF="R-$O-rev" $CB "$P" 20000 04 >/dev/null; sleep 3
chk "reversal after an existing record is NOT suppressed" "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 2

# A European-format amount is ambiguous, so it must route to UNVERIFIED (payment applied), never be
# read as "Money 20" and used to refuse a correctly paid order.
O=$(mkorder 20000 F-EU "feu-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O-eu" RAWAMT="20.000,00" $CB "$(pgref "$O")" 20000 00 >/dev/null; sleep 5
chk "ambiguous format: payment applied, not refused" "$(scalar "select status from payment where id=$PID")" SUCCESS
chk "ambiguous format: discrepancy 0 (not -19980)"   "$(scalar "select discrepancy from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 0

# A grouped amount that AGREES must sail straight through with no record at all.
O=$(mkorder 20000 F-GRP "fgrp-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O-grp" RAWAMT="20,000.00" $CB "$(pgref "$O")" 20000 00 >/dev/null; sleep 5
chk "grouped amount that agrees: applied"        "$(scalar "select status from payment where id=$PID")" SUCCESS
chk "grouped amount that agrees: no anomaly row" "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 0

# TC-BE-009 re-verified after the branch was restructured: 06 on a PENDING payment is an ordinary
# failed attempt, not an anomaly -- both sides agree no money changed hands, so NO record.
O=$(mkorder 20000 F-009 "f009-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O-009" $CB "$(pgref "$O")" 20000 06 >/dev/null; sleep 4
chk "TC-BE-009 payment FAILED"          "$(scalar "select status from payment where id=$PID")" FAILED
chk "TC-BE-009 no fulfilment"           "$(scalar "select count(*) from child_order where parent_order_id=$OID")" 0
chk "TC-BE-009 no ledger row"           "$(scalar "select count(*) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID")" 0
chk "TC-BE-009 no bogus anomaly record" "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 0

# ...and 06 arriving on an already-EXPIRED payment likewise records nothing: an earlier version opened
# a TERMINAL_STATUS_AFTER_SUCCESS row here, one meaningless OPEN row per expired order.
O=$(mkorder 20000 F-EXP06 "fexp-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
$Q "update payment set status='EXPIRED' where id=$PID" >/dev/null
ORIGREF="R-$O-exp06" $CB "$(pgref "$O")" 20000 06 >/dev/null; sleep 3
chk "06 on an EXPIRED payment: no anomaly record" "$(scalar "select count(*) from reconciliation where recon_type='PAYMENT_VS_PG' and reference_id=$PID")" 0

# TC-BE-031 CANCELLED variant
O=$(mkorder 20000 F31C "f31c-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
$CALL POST "/api/v1/orders/$O/cancel" >/dev/null
ORIGREF="R-$O-late" $CB "$(pgref "$O")" 20000 00 >/dev/null; sleep 5
chk "TC-BE-031(CANCELLED) order stays CANCELLED" "$(state "$O")" CANCELLED
chk "TC-BE-031(CANCELLED) no fulfilment"         "$(scalar "select count(*) from child_order where parent_order_id=$OID")" 0
chk "TC-BE-031(CANCELLED) recon record opened"   "$(scalar "select count(*) from reconciliation where recon_type='ORDER_VS_FULFILLMENT' and reference_id=$OID")" 1

echo "== TC-BE-010 / 012 =="
O=$(mkorder 20000 F10 "f10-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID"); P=$(pgref "$O")
ORIGREF="DUP-$O" $CB "$P" 20000 00 >/dev/null; sleep 5
PAID_BEFORE=$(scalar "select paid_at from payment where id=$PID")
ORIGREF="DUP-$O" $CB "$P" 20000 00 >/dev/null; sleep 3
chk "TC-BE-010 paid_at unchanged on replay" "$(scalar "select paid_at from payment where id=$PID")" "$PAID_BEFORE"
chk "TC-BE-010 exactly 1 payment_event"     "$(scalar "select count(*) from payment_event where dedup_key='DUP-$O:00'")" 1
chk "TC-BE-010 exactly 1 ledger CREDIT"     "$(scalar "select count(*) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID")" 1
chk "TC-BE-012 bad signature -> 401" "$(code "$($CB STUB-nope 20000 00 deadbeef)")" 401

echo "== Admin: TC-ADM-002 / 005 / 012 =="
AB=$(scalar "select count(*) from audit_log")
curl -s -o /dev/null -u superadmin:WRONGPW -X POST http://localhost:8080/admin/reconciliations/1/investigate; sleep 2
chk "TC-ADM-002 login failure audited" "$(scalar "select count(*) from audit_log where action='ADMIN_LOGIN_FAILED'")" 1
docker exec backend-postgres-1 psql -U ppob2 -d ppob2 -q -c "insert into admin_user (username,password_hash) values ('viewer1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" >/dev/null 2>&1
$Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u, role r where u.username='viewer1' and r.code='VIEWER' on conflict do nothing" >/dev/null 2>&1
chk "TC-ADM-005 VIEWER retry -> 403" "$(curl -s -o /dev/null -w '%{http_code}' -u viewer1:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"x"}' http://localhost:8080/admin/child-orders/1/retry)" 403
sleep 2
chk "TC-ADM-005 denial audited" "$(scalar "select count(*) from audit_log where action='ADMIN_PERMISSION_DENIED'")" 1
CID=$(scalar "select id from child_order where state='FAILED' and provider_sku_id=2 limit 1")
chk "TC-ADM-012 retry without reason -> 400" "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST http://localhost:8080/admin/child-orders/$CID/retry)" 400
chk "TC-ADM-012 retry with reason -> 200" "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"ticket OPS-1"}' http://localhost:8080/admin/child-orders/$CID/retry)" 200
chk "TC-ADM-012 reason in audit_log" "$(scalar "select count(*) from audit_log where action='CHILD_ORDER_RETRY' and after_state::text like '%ticket OPS-1%'")" 1

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
