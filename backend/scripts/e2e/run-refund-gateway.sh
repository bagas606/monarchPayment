#!/usr/bin/env bash
# The gateway-executed refund branch (Section 33.2 "Refund executed via PaymentGateway").
#
# Split into its own script because it needs the app booted differently from every other matrix --
# with the dev-only knob that makes the stub gateway claim refund capability:
#
#   PPOB2_PAYMENT_STUBGATEWAY_SUPPORTSREFUND=true \
#   PPOB2_FULFILLMENT_STUBPROVIDER_FAILPROVIDERSKUIDS=2 \
#   PPOB2_FULFILLMENT_STUBPROVIDER_AMBIGUOUSPROVIDERSKUIDS=3 \
#   PPOB2_FULFILLMENT_STUBPROVIDER_TIMEOUTPROVIDERSKUIDS=4 \
#   ./gradlew :app:bootRun
#
# (Same env-var spelling rule as the fulfillment knobs: relaxed binding strips hyphens within a
# property segment but not underscores between segments.)
#
# Without that knob no gateway in this codebase answers supportsRefund()==true -- the real
# AyolinxPaymentGateway answers false because Ayolinx's public API has no refund endpoint (Section
# 73.3's open question 5). So this branch is unreachable in any bootable production configuration,
# which is exactly why it needs driving: "compiles, has a mocked test, has never run" is the profile
# of every money defect this harness has found so far.
#
# Needs a freshly seeded database, like the other matrices. Safe to run after run-core.sh only if
# that run's own refund case has already consumed its REFUND_PENDING order -- this script makes its
# own, so a fresh DB is simplest.
SP="$(cd "$(dirname "$0")" && pwd)"
CALL="$SP/call.sh"; CB="$SP/cb.sh"; Q="$SP/q.sh"
pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
code() { echo "$1" | grep -o 'HTTP [0-9]*' | head -1 | awk '{print $2}'; }
mkorder() {
  IDEMPOTENCY_KEY="$3" $CALL POST /api/v1/orders \
    "{\"product_code\":\"MOBILE_LEGENDS\",\"parent_amount\":$1,\"customer_reference\":\"$2\"}" \
    | grep -o '"order_id":"[^"]*"' | cut -d'"' -f4
}
pgref() { scalar "select pg_reference from payment p join parent_order o on o.id=p.parent_order_id where o.order_no='$1'"; }
state() { scalar "select state from parent_order where order_no='$1'"; }

echo "== precondition: the app must be running with the stub gateway claiming refund capability =="
# Asserted rather than assumed: run against a normally-booted app, every case below would "pass"
# for the wrong reason (the 400 would be the out-of-band mode's missing-reference error).
O=$(mkorder 10000 GRF "grf-$RANDOM"); OID=$(scalar "select id from parent_order where order_no='$O'")
PID=$(scalar "select id from payment where parent_order_id=$OID")
ORIGREF="R-$O" $CB "$(pgref "$O")" 10000 00 >/dev/null; sleep 5
chk "order reached REFUND_PENDING" "$(state "$O")" REFUND_PENDING
RF="http://localhost:8080/admin/parent-orders/$OID/refund"
# In gateway mode an external_reference is meaningless and must be refused -- this is the probe that
# tells us the knob is actually on. Out-of-band mode would answer 200 here.
GOT=$(curl -s -o /tmp/grf.$$ -w '%{http_code}' -u superadmin:admin123 -X POST \
  -H 'Content-Type: application/json' -d '{"reason":"probe","external_reference":"BANK-1"}' $RF)
if [ "$GOT" != "400" ]; then
  echo "  FAIL  gateway mode is NOT active (external_reference was accepted: HTTP $GOT)"
  echo "        Boot the app with PPOB2_PAYMENT_STUBGATEWAY_SUPPORTSREFUND=true -- see this script's header."
  rm -f /tmp/grf.$$
  exit 1
fi
rm -f /tmp/grf.$$
ok "gateway mode active (external_reference refused as ambiguous)"
chk "nothing recorded by that rejection" "$(scalar "select status from payment where id=$PID")" SUCCESS

echo "== refund executed by the gateway =="
chk "refund without reason -> 400" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{}' $RF)" 400
chk "refund with reason only -> 200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"gateway refund, ticket OPS-9"}' $RF)" 200
sleep 2
chk "order REFUNDED"                "$(state "$O")" REFUNDED
chk "payment REFUNDED"              "$(scalar "select status from payment where id=$PID")" REFUNDED
chk "reversing DEBIT posted"        "$(scalar "select coalesce(sum(amount),0) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID and entry_type='DEBIT'")" 10000
chk "payment ledger nets to 0"      "$(scalar "select coalesce(sum(case when entry_type='CREDIT' then amount else -amount end),0) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID")" 0
# The distinguishing assertion: the reference recorded is the PG's own, and the mode says GATEWAY.
# Read the jsonb field, NOT a LIKE against its serialized text: `raw_payload` is jsonb, so Postgres
# normalises whitespace and reorders keys on storage ({"mode": "GATEWAY", ...}). The first version of
# this assertion matched '%"mode":"GATEWAY"%' and failed against a perfectly correct row.
chk "recorded as GATEWAY mode"      "$(scalar "select raw_payload->>'mode' from payment_event where payment_id=$PID and event_type='REFUND'")" GATEWAY
chk "PG's own refund reference recorded" \
    "$(scalar "select raw_payload->>'refundReference' like 'STUB-REFUND-%' from payment_event where payment_id=$PID and event_type='REFUND'")" t
chk "audit records GATEWAY mode"    "$(scalar "select after_state->>'mode' from audit_log where action='PARENT_ORDER_REFUND' and target_id=$OID")" GATEWAY
# Compared in SQL, not in the shell: scalar() strips every space (it is built for bare scalars), so
# a multi-word value cannot round-trip through it -- it would arrive as "gatewayrefund,ticketOPS-9".
chk "audit records the reason verbatim" \
    "$(scalar "select after_state->>'reason' = 'gateway refund, ticket OPS-9' from audit_log where action='PARENT_ORDER_REFUND' and target_id=$OID")" t
chk "second attempt -> 409"         "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 -X POST -H 'Content-Type: application/json' -d '{"reason":"again"}' $RF)" 409
chk "still exactly 1 DEBIT"         "$(scalar "select count(*) from ledger_entry where ledger_type='PAYMENT' and reference_id=$PID and entry_type='DEBIT'")" 1

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
