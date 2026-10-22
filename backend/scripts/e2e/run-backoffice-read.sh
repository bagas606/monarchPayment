#!/usr/bin/env bash
# Backoffice read APIs (Section 41.2 / 41.7 / 41.12): lists, drill-down, filters, RBAC, and - the part a
# unit test cannot show - that nothing sensitive reaches the response.
#
# Reads whatever run-core.sh left behind, so run that first (it seeds users and orders in every state).
# Only mutates rows it creates itself or restores: three marker values on existing rows, put back at the end.
# Assumes the app is up on :8080 with Basic auth enabled (see scripts/e2e/README.md).
SP="$(cd "$(dirname "$0")" && pwd)"
Q="$SP/q.sh"
B="${PPOB2_BASE_URL:-http://localhost:8080}"
SU="-u superadmin:admin123"
pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
http() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
# py <json> <expression over d>  -> prints the value
py() { printf '%s' "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print($2)" 2>/dev/null; }
# count occurrences of a literal in a body (grep -c counts LINES; single-line JSON would always say 1)
count() { printf '%s' "$1" | grep -o -- "$2" | wc -l | tr -d ' '; }

ensure_user() { # username role
  $Q "insert into admin_user (username,password_hash) values ('$1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" -q >/dev/null 2>&1
  $Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u, role r where u.username='$1' and r.code='$2' on conflict do nothing" >/dev/null 2>&1
}
ensure_user viewer1 VIEWER; ensure_user finance1 FINANCE; ensure_user techsupport1 TECH_SUPPORT; ensure_user ops1 OPERATIONS

echo "== RBAC matrix (V26) =="
chk "no credentials -> 401"                         "$(http $B/admin/parent-orders)" 401
for spec in "superadmin:200:200:200" "ops1:200:200:403" "viewer1:200:403:403" "finance1:200:200:403" "techsupport1:403:403:403"; do
  IFS=: read u eo ep ea <<<"$spec"
  chk "$u parent-orders"  "$(http -u $u:admin123 $B/admin/parent-orders)" "$eo"
  chk "$u payments"       "$(http -u $u:admin123 $B/admin/payments)" "$ep"
  chk "$u audit-logs"     "$(http -u $u:admin123 $B/admin/audit-logs)" "$ea"
done

echo "== Parent-order list =="
L=$(curl -s $SU "$B/admin/parent-orders?size=1000")
chk "size is clamped and the clamped size is echoed"  "$(py "$L" "d['size']")" 100
L=$(curl -s $SU "$B/admin/parent-orders?size=2&page=0")
chk "size=2 returns 2 items"                        "$(py "$L" "len(d['items'])")" 2
chk "has_more is true while rows remain"            "$(py "$L" "d['has_more']")" True
L2=$(curl -s $SU "$B/admin/parent-orders?size=2&page=1")
chk "page 1 does not repeat page 0"                 "$(py "$L2" "len(set(i['id'] for i in d['items']) & set(i['id'] for i in $(py "$L" "d['items']")))")" 0
TOTAL=$(scalar "select count(*) from parent_order")
LAST=$(curl -s $SU "$B/admin/parent-orders?size=100")
chk "a single page of 100 holds every order (or has_more says not)" \
    "$(py "$LAST" "len(d['items']) == $TOTAL or d['has_more']")" True
S=$(curl -s $SU "$B/admin/parent-orders?state=SUCCESS&size=100")
chk "state=SUCCESS: at least one row"               "$(py "$S" "len(d['items']) > 0")" True
chk "state=SUCCESS: every row is SUCCESS"           "$(py "$S" "all(i['state']=='SUCCESS' for i in d['items'])")" True
chk "state=SUCCESS count matches the database"      "$(py "$S" "len(d['items'])")" "$(scalar "select count(*) from parent_order where state='SUCCESS'")"
chk "unknown partner_code -> empty page"            "$(py "$(curl -s $SU "$B/admin/parent-orders?partner_code=NOPE")" "len(d['items'])")" 0
chk "partner_code=PPOB1 matches the database"       "$(py "$(curl -s $SU "$B/admin/parent-orders?partner_code=PPOB1&size=100")" "len(d['items']) + (100 if d['has_more'] else 0)")" "$(scalar "select least(count(*),100) + case when count(*) > 100 then 100 else 0 end from parent_order o join partner p on p.id=o.partner_id where p.code='PPOB1'")"
ON=$(py "$S" "d['items'][0]['order_no']")
chk "order_no is an exact match"                    "$(py "$(curl -s $SU "$B/admin/parent-orders?order_no=$ON")" "[i['order_no'] for i in d['items']]")" "['$ON']"
# TC-ADM-006 is search by customer_reference. run-core.sh creates orders carrying "F1".
chk "TC-ADM-006 customer_reference finds the order"  "$(py "$(curl -s $SU "$B/admin/parent-orders?customer_reference=F1&size=100")" "len(d['items']) > 0 and all(i['customer_reference']=='F1' for i in d['items'])")" True
chk "TC-ADM-006 ...and matches the database"        "$(py "$(curl -s $SU "$B/admin/parent-orders?customer_reference=F1&size=100")" "len(d['items'])")" "$(scalar "select count(*) from parent_order where customer_reference='F1'")"
chk "TC-ADM-006 unknown reference -> empty"         "$(py "$(curl -s $SU "$B/admin/parent-orders?customer_reference=NO-SUCH-REF")" "len(d['items'])")" 0
chk "'from' in the future -> empty"                 "$(py "$(curl -s $SU "$B/admin/parent-orders?from=2099-01-01T00:00:00Z")" "len(d['items'])")" 0
chk "'to' in the past -> empty"                     "$(py "$(curl -s $SU "$B/admin/parent-orders?to=2000-01-01T00:00:00Z")" "len(d['items'])")" 0
chk "list items carry no callback_url / metadata"   "$(count "$LAST" 'callback_url\|"metadata"')" 0

echo "== Bad parameters answer 400 and name no Java internals =="
for q in "state=nope%27--" "from=yesterday" "page=-1" "size=0" "page=abc"; do
  R=$(curl -s -w ' HTTP%{http_code}' $SU "$B/admin/parent-orders?$q")
  chk "parent-orders?$q -> 400"                     "$(echo "$R" | grep -o 'HTTP[0-9]*')" HTTP400
  chk "...and leaks no Java type / exception name"  "$(count "$R" 'java\.\|NumberFormat\|MethodArgument\|Failed to convert\|String')" 0
done
R=$(curl -s -w ' HTTP%{http_code}' $SU "$B/admin/child-orders?parent_order_id=abc")
chk "child-orders?parent_order_id=abc -> 400"       "$(echo "$R" | grep -o 'HTTP[0-9]*')" HTTP400
chk "...message names the parameter, not the input" "$(py "${R% HTTP*}" "d['message']")" "Parameter 'parent_order_id' has an invalid value."
R=$(curl -s -w ' HTTP%{http_code}' $SU "$B/admin/orders")
chk "GET /admin/orders without its required parameter -> 400" "$(echo "$R" | grep -o 'HTTP[0-9]*')" HTTP400
chk "...and does not name the Java parameter type"  "$(count "$R" 'type String')" 0

echo "== Parent-order detail / drill-down =="
chk "unknown id -> 404"                             "$(http $SU $B/admin/parent-orders/99999999)" 404
SID=$(scalar "select o.id from parent_order o join child_order c on c.parent_order_id=o.id where o.state='SUCCESS' limit 1")
D=$(curl -s $SU "$B/admin/parent-orders/$SID")
chk "detail: order id round-trips"                  "$(py "$D" "d['order']['id']")" "$SID"
chk "detail: has at least one child"                "$(py "$D" "len(d['children']) > 0")" True
chk "detail: child names its provider and SKU"      "$(py "$D" "bool(d['children'][0]['provider_code'] and d['children'][0]['provider_sku_code'])")" True
chk "detail: child carries the provider transaction status" "$(py "$D" "d['children'][0]['provider_transaction_status']")" SUCCESS
# TC-ADM-007: "payment, pattern, child orders, provider transactions" in one trace.
chk "TC-ADM-007 detail carries the decomposition pattern" "$(py "$D" "d['pattern']['id'] > 0 and d['pattern']['component_count'] >= 1")" True
chk "TC-ADM-007 ...with its components"             "$(py "$D" "len(d['pattern']['components']) >= 1")" True
chk "TC-ADM-007 ...whose amount equals the order's"  "$(py "$D" "d['pattern']['parent_amount'] == d['order']['parent_amount']")" True
chk "detail: payment is present, QR is a flag"      "$(py "$D" "d['payment']['has_qr']")" True
chk "detail: no qr_payload anywhere"                "$(count "$D" 'qr_payload\|000201')" 0
chk "detail: no provider request/response payload"  "$(count "$D" 'request_payload\|response_payload')" 0
chk "timeline is labelled derived"                  "$(py "$D" "d['timeline']['derived']")" True
chk "timeline starts with ORDER_CREATED"            "$(py "$D" "d['timeline']['entries'][0]['event']")" ORDER_CREATED
chk "timeline after ORDER_CREATED is time-ordered"  "$(py "$D" "(lambda t: t == sorted(t))([__import__('datetime').datetime.fromisoformat(e['at'].replace('Z','+00:00')) for e in d['timeline']['entries'][1:]])")" True
chk "timeline carries the payment callback"         "$(py "$D" "any(e['event']=='PAYMENT_CALLBACK_RECEIVED' for e in d['timeline']['entries'])")" True
# an order that took an admin action shows it, with who did it
RID=$(scalar "select target_id from audit_log where action='PARENT_ORDER_REFUND' limit 1")
if [ -n "$RID" ]; then
  RD=$(curl -s $SU "$B/admin/parent-orders/$RID")
  chk "timeline shows the admin refund and who did it" "$(py "$RD" "[e['detail'] for e in d['timeline']['entries'] if e['event']=='PARENT_ORDER_REFUND']")" "['by superadmin']"
fi

echo "== Secrets never reach the screen =="
MID=$(scalar "select id from parent_order order by id desc limit 1")
OLDCB=$(scalar "select coalesce(callback_url,'') from parent_order where id=$MID")
OLDMD=$(scalar "select coalesce(metadata::text,'') from parent_order where id=$MID")
$Q "update parent_order set callback_url='https://hooks.example.com:8443/cb/PATHSECRET?token=QUERYSECRET', metadata='{\"reseller_txn_id\":\"RT-1\",\"api_key\":\"METASECRET\",\"note\":\"visible\"}'::jsonb where id=$MID" >/dev/null
D=$(curl -s $SU "$B/admin/parent-orders/$MID")
chk "callback URL is reduced to scheme://host:port" "$(py "$D" "d['callback_host']")" "https://hooks.example.com:8443"
chk "...path secret absent"                         "$(count "$D" PATHSECRET)" 0
chk "...query secret absent"                        "$(count "$D" QUERYSECRET)" 0
chk "metadata: secret-looking key is masked"        "$(count "$D" METASECRET)" 0
chk "metadata: ordinary keys survive"               "$(py "$D" "d['metadata']['note']")" visible
chk "reseller_txn_id is surfaced in the order"      "$(py "$D" "d['order']['reseller_txn_id']")" RT-1
if [ -n "$OLDCB" ]; then $Q "update parent_order set callback_url='$OLDCB' where id=$MID" >/dev/null; else $Q "update parent_order set callback_url=null where id=$MID" >/dev/null; fi
if [ -n "$OLDMD" ]; then $Q "update parent_order set metadata='$OLDMD'::jsonb where id=$MID" >/dev/null; else $Q "update parent_order set metadata=null where id=$MID" >/dev/null; fi

echo "== Child orders =="
C=$(curl -s $SU "$B/admin/child-orders?state=FAILED&size=100")
chk "child state=FAILED: at least one"              "$(py "$C" "len(d['items']) > 0")" True
chk "child state=FAILED: every row FAILED"          "$(py "$C" "all(i['state']=='FAILED' for i in d['items'])")" True
chk "child list names the parent order"             "$(py "$C" "all(i['parent_order_no'].startswith('ORD-') for i in d['items'])")" True
chk "parent_order_id filter"                        "$(py "$(curl -s $SU "$B/admin/child-orders?parent_order_id=$SID")" "all(i['parent_order_id']==$SID for i in d['items'])")" True
chk "unknown child state -> 400"                    "$(http $SU "$B/admin/child-orders?state=nope")" 400

echo "== Payments =="
P=$(curl -s $SU "$B/admin/payments?status=SUCCESS&size=100")
chk "payments status=SUCCESS: every row SUCCESS"    "$(py "$P" "len(d['items']) > 0 and all(i['status']=='SUCCESS' for i in d['items'])")" True
chk "payments carry no QR payload"                  "$(count "$P" 'qr_payload\|000201')" 0
PID=$(py "$P" "d['items'][0]['id']")
PD=$(curl -s $SU "$B/admin/payments/$PID")
chk "payment detail lists its callback events"      "$(py "$PD" "len(d['events']) > 0")" True
chk "payment detail: no raw gateway payload"        "$(count "$PD" 'raw_payload\|callbackType\|finishedTime')" 0
chk "unknown payment -> 404"                        "$(http $SU $B/admin/payments/99999999)" 404
chk "unknown payment status -> 400"                 "$(http $SU "$B/admin/payments?status=nope")" 400
chk "payment-events: no raw payload"                "$(count "$(curl -s $SU "$B/admin/payment-events?size=100")" 'raw_payload\|callbackType')" 0

echo "== Rejected callbacks (Section 41.7 security review) =="
WF=$(curl -s $SU "$B/admin/webhook-events?status=FAILED&size=100")
chk "a rejected inbound callback is listed"         "$(py "$WF" "len(d['items']) > 0")" True
chk "...every row is FAILED"                        "$(py "$WF" "all(i['status']=='FAILED' for i in d['items'])")" True
chk "...and carries no payload"                     "$(count "$WF" '"payload"\|callbackType')" 0
chk "webhook-events direction=OUTBOUND is accepted" "$(http $SU "$B/admin/webhook-events?direction=OUTBOUND")" 200
chk "webhook-events bad direction -> 400"           "$(http $SU "$B/admin/webhook-events?direction=sideways")" 400
chk "payment:view guards webhook-events (VIEWER)"   "$(http -u viewer1:admin123 $B/admin/webhook-events)" 403

echo "== Audit log =="
A=$(curl -s $SU "$B/admin/audit-logs?action=ADMIN_PERMISSION_DENIED&size=100")
chk "action filter: at least one denial"            "$(py "$A" "len(d['items']) > 0")" True
chk "action filter: every row matches"              "$(py "$A" "all(i['action']=='ADMIN_PERMISSION_DENIED' for i in d['items'])")" True
chk "rows name the admin, not just an id"           "$(py "$A" "all(i.get('actor_username') for i in d['items'])")" True
$Q "insert into audit_log (actor_type, actor_id, action, target_type, target_id, after_state) values ('SYSTEM', null, 'E2E_REDACTION_PROBE', 'PROBE', 1, '{\"password_hash\":\"HASHSECRET\",\"nested\":{\"webhook_secret\":\"WHSECRET\"},\"ok\":\"visible\"}'::jsonb)" >/dev/null
A=$(curl -s $SU "$B/admin/audit-logs?action=E2E_REDACTION_PROBE")
chk "audit: password hash masked"                   "$(count "$A" HASHSECRET)" 0
chk "audit: nested webhook secret masked"           "$(count "$A" WHSECRET)" 0
chk "audit: ordinary keys survive"                  "$(py "$A" "d['items'][0]['after_state']['ok']")" visible
chk "audit: target_type + target_id filters"        "$(py "$(curl -s $SU "$B/admin/audit-logs?target_type=PROBE&target_id=1")" "len(d['items'])")" 1
$Q "delete from audit_log where action='E2E_REDACTION_PROBE'" >/dev/null
chk "audit: bad date -> 400"                        "$(http $SU "$B/admin/audit-logs?from=never")" 400

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
