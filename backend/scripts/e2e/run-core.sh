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

echo "== TC-BE-018 no valid pattern for parent_amount -> REFUND_PENDING =="
# 10000 is a supported amount with no seeded decomposition_pattern, so this is BR-DEC exhaustion
# reached with the money already collected. The state change alone was never the finding: until
# 2026-10-03 this order sat in REFUND_PENDING with a payment-ledger CREDIT, zero children and no
# reconciliation row anywhere -- the same funds-collected-nothing-delivered hole TC-BE-031's
# CANCELLED variant had, by a different route. The discrepancy assertion is the one that matters.
O=$(mkorder 10000 F18 "f18-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O" $CB "$(pgref "$O")" 10000 00 >/dev/null; sleep 5
chk "TC-BE-018 order REFUND_PENDING"        "$(state "$O")" REFUND_PENDING
chk "TC-BE-018 no pattern selected"         "$(scalar "select coalesce(pattern_id::text,'none') from parent_order where id=$OID")" none
chk "TC-BE-018 no children"                 "$(scalar "select count(*) from child_order where parent_order_id=$OID")" 0
chk "TC-BE-018 funds WERE collected"        "$(scalar "select coalesce(sum(amount),0) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID")" 10000
chk "TC-BE-018 ORDER_VS_FULFILLMENT opened" "$(scalar "select count(*) from reconciliation where recon_type='ORDER_VS_FULFILLMENT' and reference_id=$OID")" 1
chk "TC-BE-018 discrepancy = -10000"        "$(scalar "select discrepancy from reconciliation where recon_type='ORDER_VS_FULFILLMENT' and reference_id=$OID")" -10000

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
$Q "insert into admin_user (username,password_hash) values ('viewer1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" -q >/dev/null 2>&1
$Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u, role r where u.username='viewer1' and r.code='VIEWER' on conflict do nothing" >/dev/null 2>&1
chk "TC-ADM-005 VIEWER retry -> 403" "$(curl -s -o /dev/null -w '%{http_code}' -u viewer1:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"x"}' http://localhost:8080/admin/child-orders/1/retry)" 403
sleep 2
chk "TC-ADM-005 denial audited" "$(scalar "select count(*) from audit_log where action='ADMIN_PERMISSION_DENIED'")" 1
CID=$(scalar "select id from child_order where state='FAILED' and provider_sku_id=2 limit 1")
chk "TC-ADM-012 retry without reason -> 400" "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST http://localhost:8080/admin/child-orders/$CID/retry)" 400
chk "TC-ADM-012 retry with reason -> 200" "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"ticket OPS-1"}' http://localhost:8080/admin/child-orders/$CID/retry)" 200
chk "TC-ADM-012 reason in audit_log" "$(scalar "select count(*) from audit_log where action='CHILD_ORDER_RETRY' and after_state::text like '%ticket OPS-1%'")" 1

# Deliberately LAST in this script. The VIEWER-denial case below adds a second
# ADMIN_PERMISSION_DENIED row, which would break TC-ADM-005's absolute count assertion above if
# this block ran any earlier. Nothing after here asserts an absolute audit_log count.
echo "== Refund execution (Section 33.2 REFUND_PENDING -> REFUNDED), out-of-band mode =="
# 10000 has no pattern, so this reaches REFUND_PENDING with the money collected -- the state the
# 2026-10-03 pass found nothing could ever move an order out of (go-live blocker 8).
O=$(mkorder 10000 FRF "frf-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O" $CB "$(pgref "$O")" 10000 00 >/dev/null; sleep 5
chk "refund precondition: order REFUND_PENDING" "$(state "$O")" REFUND_PENDING
chk "refund precondition: payment SUCCESS"      "$(scalar "select status from payment where id=$PID")" SUCCESS
RF="http://localhost:8080/admin/parent-orders/$OID/refund"
chk "refund without reason -> 400" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"external_reference":"BANK-1"}' $RF)" 400
# The gateway cannot refund (Section 73.3 open question 5), so evidence the money moved is required.
chk "refund without external_reference -> 400" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"no pattern"}' $RF)" 400
chk "refund: VIEWER lacks refund:initiate -> 403" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u viewer1:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"x","external_reference":"BANK-1"}' $RF)" 403
chk "refund: nothing recorded by any rejected attempt" "$(scalar "select status from payment where id=$PID")" SUCCESS
chk "refund with reason + reference -> 200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"BR-DEC exhaustion, ticket OPS-7","external_reference":"BANK-TRANSFER-4421"}' $RF)" 200
sleep 2
chk "refund: order REFUNDED"      "$(state "$O")" REFUNDED
chk "refund: payment REFUNDED"    "$(scalar "select status from payment where id=$PID")" REFUNDED
chk "refund: refunded_at set"     "$(scalar "select refunded_at is not null from payment where id=$PID")" t
# The reversal is the whole point: a DEBIT of the same amount, so the payment ledger nets to zero.
chk "refund: reversing DEBIT posted" "$(scalar "select coalesce(sum(amount),0) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID and entry_type='DEBIT'")" 10000
chk "refund: payment ledger nets to 0" \
    "$(scalar "select coalesce(sum(case when entry_type='CREDIT' then amount else -amount end),0) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID")" 0
chk "refund: REFUND payment_event recorded" "$(scalar "select count(*) from payment_event where payment_id=$PID and event_type='REFUND'")" 1
chk "refund: dedup_key is deterministic"    "$(scalar "select dedup_key from payment_event where payment_id=$PID and event_type='REFUND'")" "refund:$PID"
# Read the jsonb fields rather than LIKE-ing their serialized text: both raw_payload and
# audit_log.after_state are jsonb, so Postgres normalises whitespace and reorders keys on storage.
# A '%"mode":"OUT_OF_BAND"%' pattern would fail against a correct row.
chk "refund: recorded as OUT_OF_BAND"       "$(scalar "select raw_payload->>'mode' from payment_event where payment_id=$PID and event_type='REFUND'")" OUT_OF_BAND
chk "refund: operator reference recorded"   "$(scalar "select raw_payload->>'refundReference' from payment_event where payment_id=$PID and event_type='REFUND'")" BANK-TRANSFER-4421
# Compared in SQL: scalar() strips every space, so a multi-word reason cannot round-trip through it.
chk "refund: reason audited verbatim" \
    "$(scalar "select after_state->>'reason' = 'BR-DEC exhaustion, ticket OPS-7' from audit_log where action='PARENT_ORDER_REFUND' and target_id=$OID")" t
chk "refund: reference audited" \
    "$(scalar "select after_state->>'refundReference' from audit_log where action='PARENT_ORDER_REFUND' and target_id=$OID")" BANK-TRANSFER-4421
# Idempotency: three independent guards (order state, Payment.markRefunded, payment_event_dedup_uk).
chk "refund: second attempt -> 409" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"again","external_reference":"BANK-9"}' $RF)" 409
chk "refund: still exactly 1 DEBIT after replay" "$(scalar "select count(*) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID and entry_type='DEBIT'")" 1
chk "refund: still exactly 1 REFUND event"       "$(scalar "select count(*) from payment_event where payment_id=$PID and event_type='REFUND'")" 1
# A SUCCESS order was never queued for refund, so it must be refused outright.
SOK=$(scalar "select id from parent_order where state='SUCCESS' limit 1")
chk "refund: SUCCESS order -> 409" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"x","external_reference":"BANK-1"}' http://localhost:8080/admin/parent-orders/$SOK/refund)" 409

# Runs after the refund block for the same reason that one runs last: the VIEWER-denial case below
# adds further ADMIN_PERMISSION_DENIED rows. Nothing from the refund block onward asserts an
# absolute audit_log count; the audit assertions here are either a before/after delta or scoped to
# one action and target_id.
echo "== Reconciliation read side (FR-REC-003 'expose', Section 38.2 'visible', Section 41.9) =="
# $OID / $PID are still the refund order from the block above: a 10000 order with no pattern, so the
# BR-DEC exhaustion path opened an ORDER_VS_FULFILLMENT record with the full -10000 shortfall. Using
# a known row rather than "whatever is first" is what makes the value assertions mean something.
RECID=$(scalar "select id from reconciliation where recon_type='ORDER_VS_FULFILLMENT' and reference_id=$OID")
RL="http://localhost:8080/admin/reconciliations"
adm()    { curl -s -u superadmin:admin123 "$1"; }
admcode(){ curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 "$1"; }
total()  { echo "$1" | grep -o '"total_elements":[0-9]*' | cut -d: -f2; }
items()  { echo "$1" | grep -o '"id":' | wc -l | tr -d ' '; }
field()  { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '" '; }

# The V22 grant set was a judgement call, so it is asserted rather than left to the migration's
# comment: three roles, and VIEWER deliberately not among them despite Section 42.1 calling it
# "read-only across permitted modules" (see V22 for the reasoning and the revisit condition).
chk "V22 reconciliation:view seeded" "$(scalar "select count(*) from permission where code='reconciliation:view'")" 1
chk "V22 granted to exactly 3 roles" "$(scalar "select count(*) from role_permission rp join permission p on p.id=rp.permission_id where p.code='reconciliation:view'")" 3
chk "V22 granted to SUPER_ADMIN+FINANCE+RECONCILIATION" \
    "$(scalar "select string_agg(r.code,',' order by r.code) from role_permission rp join permission p on p.id=rp.permission_id join role r on r.id=rp.role_id where p.code='reconciliation:view'")" \
    "FINANCE,RECONCILIATION,SUPER_ADMIN"

# Every record this suite opens is OPEN and dated today, so a status or date filter asserted only
# as "API total == SQL count for the same predicate" would pass against an endpoint that ignored
# the parameter entirely -- 12 == 12 either way. Verified by hand that this was the case on the
# first run of this block. So one record is moved out of OPEN first, which makes the status filters
# discriminating, and each filter below is additionally asserted to EXCLUDE something.
INVID=$(scalar "select min(id) from reconciliation where recon_type='PAYMENT_VS_PG'")
chk "setup: a record moves OPEN -> INVESTIGATING" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST "$RL/$INVID/investigate")" 200
sleep 2
chk "setup: it really is INVESTIGATING" "$(scalar "select status from reconciliation where id=$INVID")" INVESTIGATING
# TC-ADM-013's 1:1 rule applied to this mutation, which was never scripted before: exactly one row,
# carrying the acting admin and not a client-supplied id.
chk "TC-ADM-015 investigate audited exactly once" \
    "$(scalar "select count(*) from audit_log where action='RECONCILIATION_INVESTIGATE' and target_id=$INVID and actor_id=(select id from admin_user where username='superadmin')")" 1

TOTAL_ROWS=$(scalar "select count(*) from reconciliation")
OPEN_ROWS=$(scalar "select count(*) from reconciliation where status='OPEN'")
# Snapshot AFTER the investigate above, which legitimately writes a RECONCILIATION_INVESTIGATE row.
AUDIT_BEFORE_READS=$(scalar "select count(*) from audit_log")

# Totals are compared against the SQL count for the SAME predicate, so these stay true as earlier
# cases add rows -- a hardcoded number would have to be re-derived every time the suite above it
# changes, and would silently stop testing the filter.
chk "list: unfiltered total matches the table" "$(total "$(adm "$RL?size=200")")" "$TOTAL_ROWS"
chk "list: status=OPEN matches SQL"            "$(total "$(adm "$RL?status=OPEN&size=200")")" "$OPEN_ROWS"
# The discriminating half: OPEN must now be strictly fewer than everything, or the filter is doing
# nothing and the assertion above it is decorative.
chk "list: status=OPEN excludes the INVESTIGATING row" \
    "$([ "$OPEN_ROWS" -lt "$TOTAL_ROWS" ] && echo t || echo "f($OPEN_ROWS/$TOTAL_ROWS)")" t
chk "list: status=INVESTIGATING returns just that one" "$(total "$(adm "$RL?status=INVESTIGATING&size=200")")" 1
chk "list: and it is the right row" "$(field "$(adm "$RL?status=INVESTIGATING")" id)" "$INVID"
# The repeatable form is what makes "the unresolved work queue" one request: Section 38.2 defines
# OPEN and INVESTIGATING as the two non-terminal states. Asserted as a union strictly larger than
# either side, which is what a last-wins or first-wins binding would fail.
chk "list: repeated status= is a union, not last-wins" \
    "$(total "$(adm "$RL?status=OPEN&status=INVESTIGATING&size=200")")" "$TOTAL_ROWS"
chk "list: status=RESOLVED returns none" "$(total "$(adm "$RL?status=RESOLVED&size=200")")" 0

chk "list: recon_type=ORDER_VS_FULFILLMENT matches SQL" \
    "$(total "$(adm "$RL?recon_type=ORDER_VS_FULFILLMENT&size=200")")" \
    "$(scalar "select count(*) from reconciliation where recon_type='ORDER_VS_FULFILLMENT'")"
chk "list: that type excludes the PAYMENT_VS_PG rows" \
    "$([ "$(total "$(adm "$RL?recon_type=ORDER_VS_FULFILLMENT&size=200")")" -lt "$TOTAL_ROWS" ] && echo t || echo f)" t
chk "list: two recon_types union to the whole table" \
    "$(total "$(adm "$RL?recon_type=ORDER_VS_FULFILLMENT&recon_type=PAYMENT_VS_PG&size=200")")" "$TOTAL_ROWS"
# Nothing opens this type (no provider report ingestion source), so it is the one filter value that
# must come back empty against a non-empty table.
chk "list: recon_type=PROVIDER_VS_REPORT returns none" \
    "$(total "$(adm "$RL?recon_type=PROVIDER_VS_REPORT&size=200")")" 0

TODAY=$(scalar "select current_date")
chk "list: from_date=today returns everything" "$(total "$(adm "$RL?from_date=$TODAY&size=200")")" "$TOTAL_ROWS"
chk "list: single-day range is inclusive at both ends" \
    "$(total "$(adm "$RL?from_date=$TODAY&to_date=$TODAY&size=200")")" "$TOTAL_ROWS"
# The discriminating half for dates: a range that ends before today must exclude every row, which a
# same-day fixture cannot otherwise prove.
chk "list: to_date before today excludes everything" \
    "$(total "$(adm "$RL?to_date=$(scalar "select current_date - 1")&size=200")")" 0
chk "list: from_date after today excludes everything" \
    "$(total "$(adm "$RL?from_date=$(scalar "select current_date + 1")&size=200")")" 0

# The narrow query an operator actually runs: one type, one referent.
ROW=$(adm "$RL?recon_type=ORDER_VS_FULFILLMENT&reference_id=$OID")
chk "list: type+reference_id narrows to exactly 1" "$(total "$ROW")" 1
chk "list: that row is the TC-BE-018-shaped record" "$(field "$ROW" id)" "$RECID"
chk "list: discrepancy -10000 is in the JSON"       "$(field "$ROW" discrepancy)" -10000
# Without reference_type, reference_id is an unqualified integer -- nothing in the row says whether
# it means parent_order N or payment N, and the table has no discriminator column.
chk "list: reference_type derived as PARENT_ORDER"  "$(field "$ROW" reference_type)" PARENT_ORDER
chk "list: status OPEN"                             "$(field "$ROW" status)" OPEN

# Paging must not repeat or drop a row -- the reason the sort is a total order on id and not on the
# non-unique recon_date.
P0=$(field "$(adm "$RL?size=1&page=0")" id); P1=$(field "$(adm "$RL?size=1&page=1")" id)
chk "list: page 0 and page 1 are different rows" "$([ -n "$P0" ] && [ "$P0" != "$P1" ] && echo t || echo "f($P0/$P1)")" t
chk "list: newest first (page 0 is max id)" "$P0" "$(scalar "select max(id) from reconciliation")"

chk "list: default size echoed as 50"       "$(field "$(adm "$RL")" size)" 50
# An un-capped size is the one way a client could make this endpoint page the whole table; the
# echoed value is what tells the caller its first page was truncated.
chk "list: size=10000 capped to 200"        "$(field "$(adm "$RL?size=10000")" size)" 200
chk "list: at most 200 items returned"      "$([ "$(items "$(adm "$RL?size=10000")")" -le 200 ] && echo t || echo f)" t

chk "list: unknown recon_type -> 400"       "$(admcode "$RL?recon_type=NOPE")" 400
chk "list: unparseable date -> 400"         "$(admcode "$RL?from_date=13-13-2026")" 400
# Rejected, not answered with an empty page: on a discrepancy queue, empty reads as "nothing wrong".
chk "list: from_date after to_date -> 400"  "$(admcode "$RL?from_date=2026-10-10&to_date=2026-10-01")" 400

chk "detail: existing record -> 200"        "$(admcode "$RL/$RECID")" 200
chk "detail: same discrepancy as the list row" "$(field "$(adm "$RL/$RECID")" discrepancy)" -10000
chk "detail: unknown id -> 404"             "$(admcode "$RL/99999999")" 404

# Load-bearing for TC-ADM-013, which asserts 1:1 absolute audit_log counts per admin action: a GET
# that audited would break an unrelated test case, and only once someone browsed the queue.
sleep 2
chk "reads write no audit_log rows" "$(scalar "select count(*) from audit_log")" "$AUDIT_BEFORE_READS"

# TC-ADM-015's remaining leg, and the strongest filter assertion available: resolving the record
# moves it between two filter results that were both already asserted above, so the queue is shown
# to track a live transition rather than a snapshot. Placed after the audit-delta check because
# this one legitimately writes a RECONCILIATION_RESOLVE row.
echo "== TC-ADM-015 full lifecycle, continued from the INVESTIGATING record above =="
chk "TC-ADM-015 resolve -> 200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST "$RL/$INVID/resolve")" 200
sleep 2
chk "TC-ADM-015 status RESOLVED"   "$(field "$(adm "$RL/$INVID")" status)" RESOLVED
# Section 38.2 requires the resolver's identity, not just the status -- and it must be the acting
# admin_user, never a client-supplied id.
chk "TC-ADM-015 resolved_by is the acting admin" \
    "$(scalar "select resolved_by = (select id from admin_user where username='superadmin') from reconciliation where id=$INVID")" t
chk "TC-ADM-015 resolved_at recorded" "$(scalar "select resolved_at is not null from reconciliation where id=$INVID")" t
chk "TC-ADM-015 resolve audited exactly once" \
    "$(scalar "select count(*) from audit_log where action='RECONCILIATION_RESOLVE' and target_id=$INVID and actor_id=(select id from admin_user where username='superadmin')")" 1
chk "TC-ADM-015 resolver surfaced by the API" \
    "$(field "$(adm "$RL/$INVID")" resolved_by)" "$(scalar "select id from admin_user where username='superadmin'")"
# The same two filters as above, now on the other side of the transition.
chk "list: status=RESOLVED now returns exactly it" "$(total "$(adm "$RL?status=RESOLVED&size=200")")" 1
chk "list: it has left the unresolved queue" \
    "$(total "$(adm "$RL?status=OPEN&status=INVESTIGATING&size=200")")" "$((TOTAL_ROWS - 1))"
# Section 38.2's "OPEN -> INVESTIGATING -> RESOLVED" is a sequence, not a set: a second resolve has
# nothing left to apply.
chk "TC-ADM-015 re-resolve applies nothing" \
    "$(curl -s -u superadmin:admin123 -X POST "$RL/$INVID/resolve" | grep -o '"applied":[a-z]*' | cut -d: -f2)" false
sleep 2
chk "TC-ADM-015 re-resolve changes no state" "$(scalar "select status from reconciliation where id=$INVID")" RESOLVED
chk "TC-ADM-015 re-resolve does not re-stamp the resolver" \
    "$(scalar "select count(distinct resolved_at) from reconciliation where id=$INVID")" 1
# NOTED DEVIATION, asserted so it is a recorded fact rather than an ordering accident above: this
# endpoint audits the no-op too, so there are now TWO RECONCILIATION_RESOLVE rows for one record.
# TC-ADM-013 reads "every mutating admin action produces exactly one audit_log entry", and a resolve
# that applied nothing mutated nothing -- by AdminFulfillmentController's precedent ("a rejected
# attempt that changed nothing isn't an admin action to record") it would not be audited. The
# difference is that a non-retryable child order is REJECTED with a 409, while this answers 200 with
# applied=false, so the controller's unconditional audit call fires. Left as-is deliberately: an
# operator trying to resolve an already-resolved discrepancy is itself worth seeing in the trail,
# and changing it is an audit-policy decision, not a fix. Asserted either way so a future change to
# that policy fails here instead of silently altering what TC-ADM-013 counts.
chk "TC-ADM-015 the no-op resolve attempt is also audited" \
    "$(scalar "select count(*) from audit_log where action='RECONCILIATION_RESOLVE' and target_id=$INVID")" 2

# TC-ADM-004 (FINANCE reaches reconciliation). Previously verified by hand only, because the seed
# fixture creates no FINANCE user -- seeded inline here, exactly as viewer1 is above, which is what
# lets V22's FINANCE grant be exercised over real HTTP instead of only read back out of SQL.
echo "== TC-ADM-004 RBAC boundary: FINANCE =="
$Q "insert into admin_user (username,password_hash) values ('finance1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" -q >/dev/null 2>&1
$Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u, role r where u.username='finance1' and r.code='FINANCE' on conflict do nothing" >/dev/null 2>&1
chk "TC-ADM-004 FINANCE can list the queue -> 200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u finance1:admin123 "$RL")" 200
chk "TC-ADM-004 FINANCE can read one record -> 200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u finance1:admin123 "$RL/$RECID")" 200
# $RECID specifically, NOT "min(id) where status='OPEN'" -- which is how this was first written and
# is a cross-script bug: the lowest-id OPEN record is the one for the F16 partial order, and
# run-routing-and-sweeps.sh asserts that exact record is still OPEN for TC-BE-028. Moving it here
# made that script fail one assertion on the next run. $RECID is the refund order's own record,
# opened by this script's refund block above and referenced by nothing downstream.
chk "TC-ADM-004 FINANCE can investigate -> 200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u finance1:admin123 -X POST "$RL/$RECID/investigate")" 200
chk "TC-ADM-004 FINANCE's investigate actually applied" \
    "$(scalar "select status from reconciliation where id=$RECID")" INVESTIGATING

# Last, as above: the denials below add the third, fourth and fifth ADMIN_PERMISSION_DENIED rows
# (the two VIEWER calls are the fourth and fifth). Nothing after this point asserts an absolute
# audit_log count.
# The boundary matters as much as the grant: a role that reached everything would also answer 200 on
# the retry-gated endpoint, and then the three 200s above would prove nothing about RBAC.
chk "TC-ADM-004 FINANCE lacks retry:execute -> 403" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u finance1:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"x"}' http://localhost:8080/admin/child-orders/1/retry)" 403
chk "list: unauthenticated -> 401" "$(curl -s -o /dev/null -w '%{http_code}' "$RL")" 401
chk "list: VIEWER lacks reconciliation:view -> 403" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u viewer1:admin123 "$RL")" 403
chk "detail: VIEWER -> 403 too" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u viewer1:admin123 "$RL/$RECID")" 403

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
