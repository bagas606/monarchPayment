#!/usr/bin/env bash
# Two-sided end-to-end: PPOB1 selling through PPOB2's Open API, over signed HTTP, with both
# systems running and both databases live. Nothing here is mocked — that is the point.
#
# Assumes:
#   - PPOB2 on :8080, freshly seeded (backend/scripts/e2e/dev-seed.sql), booted with the three
#     injection knobs (fail=2 ambiguous=3 timeout=4) so the partial-failure case has a failing SKU
#   - partner.webhook_secret set for PPOB1 — the dev seed leaves it NULL, in which case PPOB2
#     refuses to send unsigned and every webhook case here fails for that one reason:
#       UPDATE partner SET webhook_secret='ppob1-webhook-secret' WHERE code='PPOB1';
#   - PPOB1 on :8081 against a fresh ppob1 database
# See ppob1-sim/README.md.
set -u

SP="$(cd "$(dirname "$0")" && pwd)"
WH="$SP/wh.sh"
P1="${PPOB1_BASE:-http://localhost:8081}"
Q="${PPOB2_Q:-$(cd "$SP/../../../backend" && pwd)/scripts/e2e/q.sh}"
CB="${PPOB2_CB:-$(cd "$SP/../../../backend" && pwd)/scripts/e2e/cb.sh}"

pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }

scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
# Reads one scalar field out of a JSON object. Deliberately not `| head -1` on the whole document:
# these responses nest `payment`, so an unanchored match for "state" would find the wrong one.
field() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
# Counts OCCURRENCES, not matching lines. `grep -c` counts lines, and every response here is a
# single line of JSON -- so `grep -c` can report at most 1 and cannot tell one match from five.
# The first version of this script used it throughout: "all three agree" failed with 1, and worse,
# every "expect exactly 1" assertion would have passed just as happily with a duplicate row. That
# is the same shape as the q.sh -t incident the backend harness README warns about: suspect the
# harness first.
count()  { echo "$1" | grep -o "$2" | wc -l | tr -d ' '; }
submit() { curl -s -X POST "$P1/sim/transactions" -H 'Content-Type: application/json' -d "$1"; }
txn()    { curl -s "$P1/sim/transactions/$1"; }
pgref()  { scalar "select pg_reference from payment p join parent_order o on o.id=p.parent_order_id where o.order_no='$1'"; }

echo "== Preconditions =="
chk "PPOB1 is up"  "$(curl -s -o /dev/null -w '%{http_code}' "$P1/actuator/health")" 200
chk "PPOB2 is up"  "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health)" 200
# Without this the whole webhook half of this script fails identically, and it looks like a signing
# bug rather than one missing column.
chk "PPOB2 has a webhook_secret for PPOB1" \
    "$(scalar "select webhook_secret is not null and webhook_secret <> '' from partner where code='PPOB1'")" t

echo "== Section 23.3: signed GET with a query string =="
# The only endpoint with a query parameter, and so the only one that breaks if the signature covers
# it. PPOB2 signs getRequestURI(), which stops at the '?'.
SA=$(curl -s -o /tmp/sa.$$ -w '%{http_code}' "$P1/sim/supported-amounts?product_code=MOBILE_LEGENDS")
chk "supported-amounts -> 200 (query string not signed)" "$SA" 200
chk "supported-amounts includes 20000" "$(count "$(cat /tmp/sa.$$)" '20000')" 1
rm -f /tmp/sa.$$

echo "== Happy path: sell, pay, get told by webhook =="
R=$(submit '{"reseller_txn_id":"E2E-OK-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-OK"}')
ID=$(field "$R" id); OID=$(field "$R" ppob2_order_id)
chk "accepted by PPOB2"                 "$(field "$R" local_state)" AWAITING_PAYMENT
chk "PPOB1 recorded the upstream order" "$([ -n "$OID" ] && echo t || echo f)" t
chk "learned from the create response"  "$(field "$R" last_source)" CREATE_RESPONSE
chk "QR payload returned to the reseller" "$(count "$R" '"qr_payload":"00020101')" 1
chk "PPOB2 stored PPOB1's callback_url" \
    "$(scalar "select callback_url is not null from parent_order where order_no='$OID'")" t
# KNOWN PPOB2 GAP, asserted so it flips the day someone closes it. Section 23.4's documented
# request body carries metadata.reseller_txn_id, and CreateOrderController binds it into its
# request record and then never passes it anywhere -- parent_order has no metadata column at all.
# Exactly the shape of the callback_url gap V16 fixed ("already accepted and silently dropped").
# The consequence is concrete: PPOB2 support cannot tie an order back to the reseller's own
# reference, so every cross-company investigation has to start by asking PPOB1.
chk "PPOB2 still has nowhere to put metadata.reseller_txn_id (known gap)" \
    "$(scalar "select count(*) from information_schema.columns where table_name='parent_order' and column_name='metadata'")" 0
# ...and sending it is nonetheless accepted rather than rejected, which is why PPOB1 keeps sending
# it: the day the column exists, the orders start carrying it with no change here.
chk "...but sending it is accepted, not refused" "$(field "$R" local_state)" AWAITING_PAYMENT

ORIGREF="R-$OID" $CB "$(pgref "$OID")" 20000 00 >/dev/null; sleep 7
chk "PPOB2 fulfilled it"                "$(scalar "select state from parent_order where order_no='$OID'")" SUCCESS
T=$(txn "$ID")
chk "PPOB1 now believes COMPLETED"      "$(field "$T" local_state)" COMPLETED
chk "...and PPOB2's own word is kept verbatim" "$(field "$T" ppob2_state)" SUCCESS
# The assertion this whole simulator exists for: PPOB1 verified PPOB2's signature with its OWN
# independent implementation of Section 23.2, not PPOB2's signer.
chk "...learned by WEBHOOK, not by polling" "$(field "$T" last_source)" WEBHOOK
W=$(curl -s "$P1/sim/webhooks")
chk "webhook recorded as APPLIED"       "$(field "$W" verdict)" APPLIED
chk "webhook signature verified independently" "$(field "$W" signature_valid)" true

echo "== Idempotency: a reseller retrying its own submit =="
R2=$(submit '{"reseller_txn_id":"E2E-OK-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-OK"}')
chk "re-submit returns the same transaction"  "$(field "$R2" id)" "$ID"
chk "re-submit does not re-send a settled sale" "$(field "$R2" local_state)" COMPLETED
# Counted by customer_reference rather than the reseller's id, because PPOB2 does not keep the
# latter (see the known gap above) -- which is itself a small illustration of what that gap costs.
chk "exactly one order exists upstream" \
    "$(scalar "select count(*) from parent_order where customer_reference='GAMEID-OK'")" 1

echo "== Section 23.4 refusal: an amount PPOB2 does not support =="
R=$(submit '{"reseller_txn_id":"E2E-REJ-1","product_code":"MOBILE_LEGENDS","amount":15000,"customer_reference":"GAMEID-REJ"}')
chk "refusal is a recorded outcome, not an absent row" "$(field "$R" local_state)" REJECTED
chk "...carrying PPOB2's own error code"               "$(field "$R" rejection_code)" UNSUPPORTED_AMOUNT
chk "...and no upstream order id"                      "$(count "$R" '"ppob2_order_id"')" 0

echo "== Partial fulfilment: PPOB1 must not read it as success =="
R=$(submit '{"reseller_txn_id":"E2E-PART-1","product_code":"MOBILE_LEGENDS","amount":40000,"customer_reference":"GAMEID-PART"}')
PID=$(field "$R" id); POID=$(field "$R" ppob2_order_id)
ORIGREF="R-$POID" $CB "$(pgref "$POID")" 40000 00 >/dev/null; sleep 8
chk "PPOB2 reports PARTIAL_FAILED" "$(scalar "select state from parent_order where order_no='$POID'")" PARTIAL_FAILED
T=$(txn "$PID")
# Folding this into FAILED would lose the distinction that something WAS delivered, which is a
# different commercial decision (partial value, not a refund).
chk "PPOB1 believes PARTIALLY_FAILED, not COMPLETED or FAILED" "$(field "$T" local_state)" PARTIALLY_FAILED
chk "...by webhook"                                            "$(field "$T" last_source)" WEBHOOK

echo "== Section 23.7 cancel, and the webhook gap it exposes =="
R=$(submit '{"reseller_txn_id":"E2E-CANCEL-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-CANCEL"}')
CID=$(field "$R" id); COID=$(field "$R" ppob2_order_id)
T=$(curl -s -X POST "$P1/sim/transactions/$CID/cancel")
chk "cancel accepted -> CANCELLED"  "$(field "$T" local_state)" CANCELLED
chk "PPOB2 agrees"                  "$(scalar "select state from parent_order where order_no='$COID'")" CANCELLED
# PPOB2 only fires ORDER_STATUS_CHANGED for the post-dispatch terminal states, so a cancel is
# never pushed. PPOB1 only knows because it asked. Asserted rather than described, so the day
# PPOB2 widens its trigger coverage this flips and someone has to look.
chk "no webhook was sent for the cancel (known PPOB2 gap)" \
    "$(count "$(curl -s "$P1/sim/webhooks")" "\"ppob2_order_id\":\"$COID\"")" 0
chk "so PPOB1 learned it by INQUIRY" "$(field "$T" last_source)" INQUIRY
chk "cancelling an already-cancelled order stays CANCELLED" \
    "$(field "$(curl -s -X POST "$P1/sim/transactions/$CID/cancel")" local_state)" CANCELLED

echo "== Receiver refusal paths (PPOB2 will never produce these) =="
BEFORE=$(field "$(txn "$ID")" local_state)
chk "wrong signature -> 401" "$(echo "$($WH "$OID" FAILED "bad-sig-1" "$(printf '00%.0s' {1..32})")" | grep -o 'HTTP [0-9]*' | awk '{print $2}')" 401
sleep 1
# The assertion that matters: a rejected callback changes NOTHING. Anyone who learned an order id
# could otherwise move PPOB1's books.
chk "...and applied nothing"  "$(field "$(txn "$ID")" local_state)" "$BEFORE"
chk "...but was still recorded" \
    "$(count "$(curl -s "$P1/sim/webhooks")" '"verdict":"REJECTED_SIGNATURE"')" 1

NONCE="dup-$RANDOM"
chk "a fresh signed callback -> 200" "$(echo "$($WH "$OID" SUCCESS "$NONCE")" | grep -o 'HTTP [0-9]*' | awk '{print $2}')" 200
# 200, not an error: this is PPOB2's own retry of a delivery already taken, and a non-2xx would
# make it keep retrying something accepted.
chk "the same nonce again -> 200 DUPLICATE" \
    "$(echo "$($WH "$OID" SUCCESS "$NONCE")" | grep -o 'DUPLICATE' | head -1)" DUPLICATE
chk "duplicate wrote no second row" \
    "$(count "$(curl -s "$P1/sim/webhooks")" "\"nonce\":\"$NONCE\"")" 1

# Signed, so it really is from PPOB2 -- PPOB1 just has no row. 200 because retrying cannot fix it
# and a retry storm would bury the row recording a real integration gap.
chk "signed callback for an unknown order -> 200" \
    "$(echo "$($WH "ORD-DOES-NOT-EXIST" SUCCESS "ghost-$RANDOM")" | grep -o 'HTTP [0-9]*' | awk '{print $2}')" 200
chk "...recorded as UNKNOWN_ORDER" "$(count "$(curl -s "$P1/sim/webhooks")" '"verdict":"UNKNOWN_ORDER"')" 1

# 400 because a malformed body will not become well-formed on retry, and a 2xx would hide it.
chk "signed but unparseable body -> 400" \
    "$(RAWBODY='not json' $WH "$OID" SUCCESS "malformed-$RANDOM" | grep -o 'HTTP [0-9]*' | awk '{print $2}')" 400
chk "...recorded as MALFORMED" "$(count "$(curl -s "$P1/sim/webhooks")" '"verdict":"MALFORMED"')" 1

# PPOB1's own database. Used only to stage conditions in PPOB1's OWN books -- never to read PPOB2's
# state, which is the whole point of reconciling over HTTP.
P1Q() { PGPASSWORD="${PGPASSWORD:-ppob2}" psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
          -U ppob2 -d "${PPOB1_DB:-ppob1}" -tAq -c "$1" 2>/dev/null | tr -d ' \n\r'; }

echo "== Reconciliation: PPOB1's books against PPOB2's answers =="
RUN=$(curl -s -X POST "$P1/sim/recon/run")
RUNID=$(field "$RUN" run_id)
# Three transactions have an upstream order: the completed one, the partially-failed one and the
# cancelled one. All three agree with PPOB2, so a healthy first run has nothing needing attention.
chk "run examined the three orders that exist upstream" "$(field "$RUN" examined)" 3
chk "all three agree with PPOB2"      "$(count "$RUN" '"verdict":"MATCH"')" 3
chk "nothing needs attention"         "$(field "$RUN" needing_attention)" 0
# A rejected sale never produced an upstream order, so re-inquiring would ask PPOB2 about an order
# that was never created.
chk "the REJECTED sale is excluded from the working set" "$(count "$RUN" 'E2E-REJ-1')" 0
chk "the run is retrievable afterwards" \
    "$([ "$(count "$(curl -s "$P1/sim/recon/$RUNID")" 'reseller_txn_id')" -ge 1 ] && echo t || echo f)" t
# The cancel is a MATCH, not a broken-integration finding: PPOB2 fires ORDER_STATUS_CHANGED only
# for post-dispatch outcomes, so no callback for a cancel is correct behaviour. Flagging it would
# report PPOB2's documented trigger coverage as a fault on every run.
chk "the un-pushed cancel is a MATCH with the reason recorded" \
    "$(count "$(curl -s "$P1/sim/recon/$RUNID")" 'does not fire ORDER_STATUS_CHANGED for CANCELLED')" 1
# ...and a reconciled transaction drops out, so a run stays proportional to open business.
chk "reconciled transactions are settled"    "$(field "$(txn "$ID")" recon_settled_at)" "$(field "$(txn "$ID")" recon_settled_at)"
chk "...and a second run has nothing to do"  "$(field "$(curl -s -X POST "$P1/sim/recon/run")" examined)" 0

echo "== A genuinely broken push integration =="
# Reproduced for real, not staged: clearing PPOB2's partner.webhook_secret makes it refuse to send
# an unsigned callback (WebhookDeliveryTargetResolver logs and skips rather than signing with
# nothing), so the order succeeds upstream and PPOB1 is never told. This is the exact state a
# stock dev seed is in, which is why PPOB2's outbound webhook had never been exercised at all.
$Q "update partner set webhook_secret=null where code='PPOB1'" >/dev/null 2>&1
R=$(submit '{"reseller_txn_id":"E2E-NOWH-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-NOWH"}')
NID=$(field "$R" id); NOID=$(field "$R" ppob2_order_id)
ORIGREF="R-$NOID" $CB "$(pgref "$NOID")" 20000 00 >/dev/null; sleep 7
chk "PPOB2 fulfilled it"                "$(scalar "select state from parent_order where order_no='$NOID'")" SUCCESS
chk "PPOB2 refused to send unsigned"    "$(count "$(curl -s "$P1/sim/webhooks")" "\"ppob2_order_id\":\"$NOID\"")" 0
chk "so PPOB1 still believes it unpaid" "$(field "$(txn "$NID")" local_state)" AWAITING_PAYMENT

RUN=$(curl -s -X POST "$P1/sim/recon/run")
# Two findings on one transaction, and recon reports the money one: PPOB1's belief was wrong.
chk "recon catches the stale belief"      "$(count "$RUN" '"verdict":"STATE_MISMATCH"')" 1
chk "...and flags it for attention"       "$(field "$RUN" needing_attention)" 1
# PPOB2 is authoritative for order state, so staying wrong is not an option.
chk "...and repairs PPOB1 to match PPOB2" "$(field "$(txn "$NID")" local_state)" COMPLETED
chk "...recording the states as compared, not after the repair" \
    "$(count "$(curl -s "$P1/sim/recon/$(field "$RUN" run_id)")" '"local_state":"AWAITING_PAYMENT"')" 1

# Now that the state agrees, the SECOND run is the one that can see the provenance problem: a
# SUCCESS that PPOB2 does push, which PPOB1 nonetheless only ever learned by asking.
$Q "update partner set webhook_secret='ppob1-webhook-secret' where code='PPOB1'" >/dev/null 2>&1
P1Q "update ppob1_transaction set recon_settled_at=null where reseller_txn_id='E2E-NOWH-1'"
RUN=$(curl -s -X POST "$P1/sim/recon/run")
chk "the missing callback is reported as WEBHOOK_NEVER_ARRIVED" \
    "$(count "$RUN" '"verdict":"WEBHOOK_NEVER_ARRIVED"')" 1
chk "...counted as needing attention"  "$(field "$RUN" needing_attention)" 1
chk "...naming the push integration, not the money" \
    "$(count "$(curl -s "$P1/sim/recon/$(field "$RUN" run_id)")" 'push integration is not working')" 1
# Reported once, then settled: it is a fact about a finished order, not a condition to re-detect
# hourly, and re-reporting it forever would bury the verdicts that do change.
chk "...and is not re-reported on the next run" \
    "$(field "$(curl -s -X POST "$P1/sim/recon/run")" examined)" 0

echo "== An orphaned submit: PPOB1 holding an order it cannot name =="
# Staged in PPOB1's OWN books, because reproducing it for real needs PPOB2 to stop answering
# mid-call -- see README. This is exactly what a lost create response leaves behind.
P1Q "insert into ppob1_transaction (reseller_txn_id, product_code, amount, customer_reference, local_state, updated_at) values ('E2E-ORPHAN-1','MOBILE_LEGENDS',20000,'GAMEID-ORPHAN','SUBMITTING', now())"
RUN=$(curl -s -X POST "$P1/sim/recon/run")
chk "recon reports ORPHANED_SUBMIT"     "$(count "$RUN" '"verdict":"ORPHANED_SUBMIT"')" 1
chk "...as the worst verdict, listed first" "$(field "$RUN" verdict)" ORPHANED_SUBMIT
chk "...telling the operator how to recover" \
    "$(count "$RUN" 'Idempotency-Key')" 1
# Unresolved business, so unlike a terminal finding it must NOT be settled -- the next run has to
# look again.
chk "...and is left unsettled for the next run" \
    "$(field "$(curl -s -X POST "$P1/sim/recon/run")" examined)" 1

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
