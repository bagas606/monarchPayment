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
# This WAS the gap, and these two assertions used to state it: parent_order had no metadata column,
# so Section 23.4's documented metadata.reseller_txn_id was bound by CreateOrderController and then
# dropped -- the same shape as the callback_url gap V16 fixed ("already accepted and silently
# dropped"). Closed by PPOB2's V23; the assertion is flipped rather than deleted, because what is
# worth checking from here is the end-to-end claim: PPOB1 sent its own id and PPOB2 can now be
# asked about it. Not one line of PPOB1 changed -- Ppob2Client has always sent the field.
chk "PPOB2 now stores PPOB1's own transaction id" \
    "$(scalar "select metadata->>'reseller_txn_id' from parent_order where order_no='$OID'")" E2E-OK-1
# The reseller's id and PPOB2's order number are now tied together on PPOB2's side, which is the
# whole point: a cross-company investigation can start from either end.
chk "...tied to the order PPOB1 was given" \
    "$(scalar "select count(*) from parent_order where order_no='$OID' and metadata->>'reseller_txn_id'='E2E-OK-1'")" 1

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
chk "exactly one order exists upstream" \
    "$(scalar "select count(*) from parent_order where customer_reference='GAMEID-OK'")" 1
# Countable by the reseller's OWN id now, which it was not before V23 -- this assertion previously
# had to go through customer_reference and carried a comment saying so, which was itself a small
# illustration of what the gap cost. Same count, asked PPOB1's way.
chk "...and one by PPOB1's own id" \
    "$(scalar "select count(*) from parent_order where metadata->>'reseller_txn_id'='E2E-OK-1'")" 1

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
chk "reconciled transactions are settled" \
    "$([ -n "$(field "$(txn "$ID")" recon_settled_at)" ] && echo t || echo f)" t
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
# A second, still-open transaction so the ordering assertion below is not vacuous: with only the
# orphan in the working set it would be "first" by default and prove nothing about severity order.
submit '{"reseller_txn_id":"E2E-OPEN-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-OPEN"}' >/dev/null
# Staged in PPOB1's OWN books: this is what a lost create response leaves behind.
P1Q "insert into ppob1_transaction (reseller_txn_id, product_code, amount, customer_reference, local_state, updated_at) values ('E2E-ORPHAN-1','MOBILE_LEGENDS',20000,'GAMEID-ORPHAN','SUBMITTING', now())"
RUN=$(curl -s -X POST "$P1/sim/recon/run")
chk "recon reports ORPHANED_SUBMIT"     "$(count "$RUN" '"verdict":"ORPHANED_SUBMIT"')" 1
chk "the open order is also in the run"  "$(count "$RUN" '"verdict":"IN_PROGRESS"')" 1
# Meaningful now that the run holds two entries with different severities: a report is read
# top-down by someone deciding what to do today, so the ordering is the recommendation.
chk "...and the orphan is listed first, ahead of the open one" "$(field "$RUN" verdict)" ORPHANED_SUBMIT
chk "...telling the operator how to recover" "$(count "$RUN" 'Idempotency-Key')" 1
# Unresolved business, so unlike a terminal finding neither is settled -- the next run looks again.
chk "...and both are left unsettled for the next run" \
    "$(field "$(curl -s -X POST "$P1/sim/recon/run")" examined)" 2

echo "== ...and the recovery that ORPHANED_SUBMIT actually recommends =="
# The detail text and the README both tell operators to re-submit under the same reseller_txn_id,
# on the grounds that PPOB2's Idempotency-Key returns the original order rather than creating a
# second. That claim had never been driven -- the staged orphan above has no upstream order, so it
# exercises the verdict and not the remedy. This drives the remedy.
R=$(submit '{"reseller_txn_id":"E2E-RECOVER-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-RECOVER"}')
RCID=$(field "$R" id); RCOID=$(field "$R" ppob2_order_id)
chk "a sale to lose the response for" "$([ -n "$RCOID" ] && echo t || echo f)" t
# Exactly what a create call that never answered leaves behind: the order exists at PPOB2, PPOB1
# cannot name it.
P1Q "update ppob1_transaction set local_state='SUBMITTING', ppob2_order_id=null, ppob2_state=null, last_source=null, recon_settled_at=null where reseller_txn_id='E2E-RECOVER-1'"
chk "staged the lost create response" "$(field "$(txn "$RCID")" local_state)" SUBMITTING
chk "...with recon seeing it as an orphan" \
    "$(count "$(curl -s -X POST "$P1/sim/recon/run")" '"reseller_txn_id":"E2E-RECOVER-1"')" 1

R2=$(submit '{"reseller_txn_id":"E2E-RECOVER-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-RECOVER"}')
# The assertion that makes the recommendation true: the SAME upstream order comes back.
chk "re-submitting recovers the original order id" "$(field "$R2" ppob2_order_id)" "$RCOID"
chk "...rather than a 409 or a second order"       "$(field "$R2" local_state)" AWAITING_PAYMENT
chk "...and PPOB2 still holds exactly one order for it" \
    "$(scalar "select count(*) from parent_order where customer_reference='GAMEID-RECOVER'")" 1

echo "== A live order PPOB1 holds no id for, recovered from PPOB2's own callback =="
# PPOB2 now echoes metadata.reseller_txn_id back in the Section 23.8 body, so a callback PPOB1
# cannot place by order_id can still be matched on an id PPOB1 owns. Before this, this exact
# sequence ended in UNKNOWN_ORDER: correctly signed, naming an order id PPOB1 had no row for, and
# nothing to do about it.
#
# WHAT IS STAGED HERE, precisely: the QR reached the payer and PPOB1's books lost the pointer to
# the order -- a failed outcome write, a restored backup, an operational repair. The row shape
# (SUBMITTING, no ppob2_order_id) with a live, payable upstream order behind it, so the order gets
# paid and a SUCCESS callback comes.
#
# NOT the same thing as a lost create response, which leaves no qr_payload either and so is never
# paid at all. That orphan ends in EXPIRED, and PPOB2 did not notify on EXPIRED when this block was
# written -- so it was out of reach here. It is covered now, driven separately further down
# (E2E-EXPREC-1), which is why both blocks exist rather than one.
#
# Deliberately a REAL round trip, not wh.sh: PPOB2 builds and signs the body, so this is also the
# only thing that proves PPOB2 actually sends the field.
R=$(submit '{"reseller_txn_id":"E2E-WHRECOVER-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-WHRECOVER"}')
WRID=$(field "$R" id); WROID=$(field "$R" ppob2_order_id)
chk "a sale that reached PPOB2 and got a QR" "$([ -n "$WROID" ] && echo t || echo f)" t
P1Q "update ppob1_transaction set local_state='SUBMITTING', ppob2_order_id=null, ppob2_state=null, last_source=null, recon_settled_at=null where reseller_txn_id='E2E-WHRECOVER-1'"
chk "...whose order id PPOB1 then lost" \
    "$(P1Q "select ppob2_order_id is null from ppob1_transaction where reseller_txn_id='E2E-WHRECOVER-1'")" t

# The customer pays the QR they already had. PPOB2 fulfils and fires the callback at an order PPOB1
# cannot recognise.
ORIGREF="R-$WROID" $CB "$(pgref "$WROID")" 20000 00 >/dev/null; sleep 7
chk "PPOB2 fulfilled it"  "$(scalar "select state from parent_order where order_no='$WROID'")" SUCCESS
# PPOB2-side oracle, read out of PPOB2's own webhook_event row: the field was sent, independently of
# how PPOB1 read it. Without this the assertions below could pass on PPOB1 inventing the match.
chk "PPOB2 sent the reseller's own id in the callback" \
    "$(scalar "select payload->>'reseller_txn_id' from webhook_event where direction='OUTBOUND' and payload->>'order_id'='$WROID' order by id desc limit 1")" E2E-WHRECOVER-1

W=$(curl -s "$P1/sim/webhooks")
chk "PPOB1 recovered the order from it" "$(field "$W" verdict)" RECOVERED_BY_RESELLER_ID
T=$(txn "$WRID")
chk "...adopting the upstream order id"  "$(field "$T" ppob2_order_id)" "$WROID"
chk "...and applying PPOB2's state"      "$(field "$T" local_state)" COMPLETED
chk "...learned by WEBHOOK, not by asking" "$(field "$T" last_source)" WEBHOOK
# The finding is closed, not merely silenced: the row that was ORPHANED_SUBMIT now reconciles clean.
# Scoped to this transaction rather than counting ORPHANED_SUBMIT across the run -- the first
# version of this assertion did that and failed, because E2E-ORPHAN-1 above is a staged orphan with
# no upstream order at all and is correctly still reported.
curl -s -X POST "$P1/sim/recon/run" >/dev/null
chk "recon now agrees instead of reporting an orphan" \
    "$(P1Q "select verdict from ppob1_recon_entry where reseller_txn_id='E2E-WHRECOVER-1' order by id desc limit 1")" MATCH
# Which is only meaningful if the detector still works -- and this is also the assertion that keeps
# the scope honest: E2E-ORPHAN-1 has no upstream order at all, so no callback will ever come for it
# and it stays an orphan. The callback recovers a lost pointer, not every orphan.
chk "...while the orphan no callback can reach is still flagged" \
    "$(P1Q "select verdict from ppob1_recon_entry where reseller_txn_id='E2E-ORPHAN-1' order by id desc limit 1")" ORPHANED_SUBMIT

echo "== ...and the guard that keeps that match safe =="
# A reseller id is unique in PPOB1 but not necessarily upstream: PPOB2's idempotency key is per
# (client_id, idempotency_key) and Section 22.3 lets one partner hold several api_client rows, so the
# same id through two of them yields two orders. Adopting then would overwrite the order PPOB1 is
# tracking -- one write, two orders, one record. wh.sh rather than a real callback because this
# fixture has a single api_client and PPOB2 cannot be made to produce it.
WHCONF=$(RESELLER_TXN_ID=E2E-OK-1 $WH "ORD-NOT-PPOB1S" SUCCESS)
chk "a callback claiming a reseller id already pinned elsewhere -> 200" \
    "$(echo "$WHCONF" | grep -o 'HTTP [0-9]*' | awk '{print $2}')" 200
chk "...is an ORDER_ID_CONFLICT, not a recovery" "$(field "$(curl -s "$P1/sim/webhooks")" verdict)" ORDER_ID_CONFLICT
# The whole point: PPOB1's books are untouched.
chk "...and the pinned order id is unchanged" \
    "$(P1Q "select ppob2_order_id from ppob1_transaction where reseller_txn_id='E2E-OK-1'")" "$OID"
chk "...and so is its state"             "$(P1Q "select local_state from ppob1_transaction where reseller_txn_id='E2E-OK-1'")" COMPLETED

echo "== The REAL lost create response, recovering on its own =="
# The case the two blocks above deliberately could NOT reach, and the reason PPOB2 now notifies on
# EXPIRED. A create call that never answers leaves PPOB1 with no order id AND NO QR PAYLOAD -- only
# acceptedByPpob2 sets one -- so no customer can pay, so the upstream order is swept to EXPIRED. For
# as long as EXPIRED fired no callback, nothing could ever tell PPOB1 that the sale it is holding
# was real and is now dead: its only route back was a human reading the recon report.
#
# Staged as the no-answer branch of TransactionWriter.applyCreateOutcome actually leaves it, right
# down to the null qr_payload -- that field is the reason this orphan never gets paid, so a fixture
# that kept it would be testing the easier case again.
R=$(submit '{"reseller_txn_id":"E2E-EXPREC-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-EXPREC"}')
XRID=$(field "$R" id); XROID=$(field "$R" ppob2_order_id)
chk "a sale PPOB2 accepted and issued a QR for" "$([ -n "$XROID" ] && echo t || echo f)" t
P1Q "update ppob1_transaction set local_state='SUBMITTING', ppob2_order_id=null, ppob2_state=null, qr_payload=null, qr_expires_at=null, last_source=null, recon_settled_at=null where reseller_txn_id='E2E-EXPREC-1'"
chk "...whose create response was lost entirely" \
    "$(P1Q "select ppob2_order_id is null and qr_payload is null from ppob1_transaction where reseller_txn_id='E2E-EXPREC-1'")" t
# Recon sees it for what it is, and this is the state it was previously stuck in forever.
curl -s -X POST "$P1/sim/recon/run" >/dev/null
chk "...so recon reports ORPHANED_SUBMIT for it" \
    "$(P1Q "select verdict from ppob1_recon_entry where reseller_txn_id='E2E-EXPREC-1' order by id desc limit 1")" ORPHANED_SUBMIT

# Nobody pays -- there is no QR to pay. Forced past its TTL the same way run-routing-and-sweeps.sh
# does, rather than waiting out the real 15-minute expiry.
$Q "update parent_order set expires_at = now() - interval '2 hours' where order_no='$XROID'" >/dev/null 2>&1
P1Q "update ppob1_transaction set recon_settled_at=null where reseller_txn_id='E2E-EXPREC-1'"
# Two sweeps have to turn over: QrExpirySweepJob expires the order (60s tick), then
# WebhookRetrySweepJob delivers the queued callback (60s tick). PPOB2 deliberately does NOT send
# this one synchronously -- see OrderExpiredWebhookListener for why -- so the wait is the design,
# not slowness. Budgeted at ~160s.
for i in $(seq 1 20); do
  [ "$(P1Q "select ppob2_order_id from ppob1_transaction where reseller_txn_id='E2E-EXPREC-1'")" = "$XROID" ] && break
  sleep 8
done
chk "PPOB2 expired the unpaid order" "$(scalar "select state from parent_order where order_no='$XROID'")" EXPIRED
# PPOB2-side oracle first, as before: the callback was built and sent by PPOB2, carrying the state
# and the reseller's own id. Scoped to this order so a stray row cannot satisfy it.
chk "...and sent an EXPIRED callback for it" \
    "$(scalar "select payload->>'state' from webhook_event where direction='OUTBOUND' and payload->>'order_id'='$XROID' order by id desc limit 1")" EXPIRED
chk "...carrying PPOB1's own transaction id" \
    "$(scalar "select payload->>'reseller_txn_id' from webhook_event where direction='OUTBOUND' and payload->>'order_id'='$XROID' order by id desc limit 1")" E2E-EXPREC-1
# ...queued for the sweep rather than attempted inline, which is what keeps the expiry sweep itself
# from blocking on an unreachable partner. attempt_count tells the two paths apart.
chk "...queued through webhook_delivery, not sent inline" \
    "$(scalar "select count(*) from webhook_delivery where parent_order_id=(select id from parent_order where order_no='$XROID')")" 1
chk "...and delivered on its first attempt" \
    "$(scalar "select status || ':' || attempt_count from webhook_delivery where parent_order_id=(select id from parent_order where order_no='$XROID')")" DELIVERED:1

# And the payoff: PPOB1 matched a callback it could not place by order_id, on an id it owns.
chk "PPOB1 adopted the order id from the callback" \
    "$(P1Q "select ppob2_order_id from ppob1_transaction where reseller_txn_id='E2E-EXPREC-1'")" "$XROID"
chk "...and now knows the sale is dead"  "$(field "$(txn "$XRID")" local_state)" EXPIRED
chk "...learned by WEBHOOK, not by asking" "$(field "$(txn "$XRID")" last_source)" WEBHOOK
chk "...recorded as a recovery, not an unknown order" \
    "$(P1Q "select verdict from ppob1_inbound_webhook where ppob2_order_id='$XROID' order by id desc limit 1")" RECOVERED_BY_RESELLER_ID
# The orphan is closed: what was ORPHANED_SUBMIT now reconciles clean.
P1Q "update ppob1_transaction set recon_settled_at=null where reseller_txn_id='E2E-EXPREC-1'"
curl -s -X POST "$P1/sim/recon/run" >/dev/null
chk "...and recon agrees instead of reporting an orphan" \
    "$(P1Q "select verdict from ppob1_recon_entry where reseller_txn_id='E2E-EXPREC-1' order by id desc limit 1")" MATCH

echo "== PPOB1's own gross-collected book =="
# One-sided by design: computed entirely from PPOB1's books, with no call to PPOB2. The comparison
# against PPOB2's figure is the half that needs a contract decision first -- PPOB2 exposes no
# partner-facing settlement figure (both its settlement endpoints are behind admin RBAC, and the
# allocations one returns every partner's numbers).
SNAP_URL="$P1/sim/collections/snapshots"
TODAY_UTC=$(date -u +%Y-%m-%d)
bgross() { echo "$1" | grep -o "\"bucket\":\"$2\",\"txn_count\":[0-9]*,\"gross\":[0-9-]*" | grep -o '"gross":[0-9-]*' | cut -d: -f2; }
bcount() { echo "$1" | grep -o "\"bucket\":\"$2\",\"txn_count\":[0-9]*" | grep -o '"txn_count":[0-9]*' | cut -d: -f2; }
# PPOB1's own books only -- never PPOB2's. The bucket SQL mirrors CollectionBucket.of so a drift
# between the two shows up as a failure here rather than as a quietly wrong figure.
p1sum() { P1Q "select coalesce(sum(amount),0) from ppob1_transaction where local_state in ($1) and created_at >= '$TODAY_UTC 00:00:00+00' and created_at < ('$TODAY_UTC'::date + 1)::timestamptz"; }
p1cnt() { P1Q "select count(*) from ppob1_transaction where local_state in ($1) and created_at >= '$TODAY_UTC 00:00:00+00' and created_at < ('$TODAY_UTC'::date + 1)::timestamptz"; }

SNAP=$(curl -s -X POST "$SNAP_URL?business_date=$TODAY_UTC")
chk "snapshot cut in the configured zone"  "$(field "$SNAP" zone_id)" UTC
chk "...for the date asked for"            "$(field "$SNAP" business_date)" "$TODAY_UTC"
# Omitted before computed_at was set in Java rather than left to the column default -- Hibernate
# does not re-read the row after save(), so the POST response silently dropped it.
chk "...and says when it was computed"     "$([ -n "$(field "$SNAP" computed_at)" ] && echo t || echo f)" t

# Each bucket against PPOB1's own SQL for the same predicate.
chk "DELIVERED gross matches SQL"   "$(bgross "$SNAP" DELIVERED)"   "$(p1sum "'COMPLETED'")"
chk "DELIVERED count matches SQL"   "$(bcount "$SNAP" DELIVERED)"   "$(p1cnt "'COMPLETED'")"
chk "PARTIAL gross matches SQL"     "$(bgross "$SNAP" PARTIAL)"     "$(p1sum "'PARTIALLY_FAILED'")"
# FAILED and REFUND_EXPECTED share this bucket: both are money collected with nothing delivered.
chk "COLLECTED_UNDELIVERED matches SQL" "$(bgross "$SNAP" COLLECTED_UNDELIVERED)" "$(p1sum "'FAILED','REFUND_EXPECTED'")"
chk "REVERSED matches SQL"          "$(bgross "$SNAP" REVERSED)"    "$(p1sum "'REFUNDED'")"
chk "IN_FLIGHT matches SQL"         "$(bgross "$SNAP" IN_FLIGHT)"   "$(p1sum "'AWAITING_RESULT'")"
chk "NOT_COLLECTED matches SQL"     "$(bgross "$SNAP" NOT_COLLECTED)" "$(p1sum "'AWAITING_PAYMENT','CANCELLED','EXPIRED','REJECTED'")"
chk "INDETERMINATE matches SQL"     "$(bgross "$SNAP" INDETERMINATE)" "$(p1sum "'SUBMITTING','UNKNOWN'")"

# The census invariant, same discipline as BR-REC-002's "allocations MUST sum exactly": every
# transaction in exactly one bucket, so the buckets sum to the totals. A transaction dropped or
# double-counted shows up here and nowhere else.
BSUM=$(echo "$SNAP" | grep -o '"gross":[0-9-]*' | cut -d: -f2 | paste -sd+ | bc)
CSUM=$(echo "$SNAP" | grep -o '"txn_count":[0-9]*' | cut -d: -f2 | paste -sd+ | bc)
chk "buckets sum to total_gross"  "$BSUM" "$(field "$SNAP" total_gross)"
chk "buckets sum to total_count"  "$CSUM" "$(field "$SNAP" total_count)"
chk "total_count matches the day's rows" "$(field "$SNAP" total_count)" "$(p1cnt "'COMPLETED','PARTIALLY_FAILED','FAILED','REFUND_EXPECTED','REFUNDED','AWAITING_RESULT','AWAITING_PAYMENT','CANCELLED','EXPIRED','REJECTED','SUBMITTING','UNKNOWN'")"
chk "every bucket has a line, zeros included" "$(echo "$SNAP" | grep -o '"bucket":' | wc -l | tr -d ' ')" 7
# The staged orphan has no upstream order, so the day is not final.
chk "a day holding indeterminate work is provisional" "$(field "$SNAP" provisional)" true

echo "== ORACLE: do PPOB1's bucket definitions actually match PPOB2's basis? =="
# Harness-only. The service never does this -- it is the cheapest available proof that
# CollectionBucket.of agrees with the number PPOB1's figure would one day be reconciled against.
# PPOB2 computes a settlement's expected_amount as SUM(payment.amount) WHERE status='SUCCESS',
# independent of order state. Restricted to the orders PPOB1 knows about, so anything else in
# PPOB2's database cannot flatter or spoil the comparison.
P1_ORDERS=$(P1Q "select string_agg(quote_literal(ppob2_order_id), ',') from ppob1_transaction where ppob2_order_id is not null")
PPOB2_SUCCESS=$(scalar "select coalesce(sum(p.amount),0) from payment p join parent_order o on o.id=p.parent_order_id where p.status='SUCCESS' and o.order_no in ($P1_ORDERS)")
# Everything collected and not reversed: DELIVERED + PARTIAL + COLLECTED_UNDELIVERED + IN_FLIGHT.
# REVERSED is excluded because markRefunded moves the payment off SUCCESS, so it leaves PPOB2's
# figure too; NOT_COLLECTED never had a SUCCESS payment; INDETERMINATE has no order id to join on.
P1_COLLECTED=$(( $(bgross "$SNAP" DELIVERED) + $(bgross "$SNAP" PARTIAL) + $(bgross "$SNAP" COLLECTED_UNDELIVERED) + $(bgross "$SNAP" IN_FLIGHT) ))
chk "PPOB1's collected buckets equal PPOB2's SUCCESS-payment sum" "$P1_COLLECTED" "$PPOB2_SUCCESS"

echo "== The business-day boundary =="
# Staged in PPOB1's own books: a sale backdated to one second before the day began must fall out,
# and the last second of the day must stay in. The query is a half-open instant range rather than
# created_at::date, because that cast runs in the Postgres session timezone -- the same ambiguity
# PPOB2 has on its side.
submit '{"reseller_txn_id":"E2E-EDGE-1","product_code":"MOBILE_LEGENDS","amount":11000,"customer_reference":"GAMEID-EDGE"}' >/dev/null
IN_BEFORE=$(bgross "$(curl -s -X POST "$SNAP_URL?business_date=$TODAY_UTC")" NOT_COLLECTED)
P1Q "update ppob1_transaction set created_at = '$TODAY_UTC 23:59:59+00' where reseller_txn_id='E2E-EDGE-1'"
chk "23:59:59 of the day is still inside it" \
    "$(bgross "$(curl -s -X POST "$SNAP_URL?business_date=$TODAY_UTC")" NOT_COLLECTED)" "$IN_BEFORE"
P1Q "update ppob1_transaction set created_at = ('$TODAY_UTC'::date - 1 + interval '23:59:59')::timestamptz where reseller_txn_id='E2E-EDGE-1'"
chk "one second before the day starts falls out" \
    "$(bgross "$(curl -s -X POST "$SNAP_URL?business_date=$TODAY_UTC")" NOT_COLLECTED)" "$((IN_BEFORE - 11000))"
# ...and lands on the previous day instead, rather than vanishing.
chk "...and is counted on the previous day" \
    "$(bgross "$(curl -s -X POST "$SNAP_URL?business_date=$(date -u -d "$TODAY_UTC - 1 day" +%Y-%m-%d)")" NOT_COLLECTED)" 11000
P1Q "update ppob1_transaction set created_at = now() where reseller_txn_id='E2E-EDGE-1'"

echo "== A snapshot freezes what PPOB1 believed =="
# The reason snapshots are append-only and not unique per date: the transaction book moves when
# reconciliation repairs a stale belief, so the same day legitimately yields a different figure
# later -- and the figure quoted to PPOB2 last week has to stay quotable.
R=$(submit '{"reseller_txn_id":"E2E-FREEZE-1","product_code":"MOBILE_LEGENDS","amount":20000,"customer_reference":"GAMEID-FREEZE"}')
FID=$(field "$R" id); FOID=$(field "$R" ppob2_order_id)
ORIGREF="R-$FOID" $CB "$(pgref "$FOID")" 20000 00 >/dev/null; sleep 7
# Rewind PPOB1's belief only -- PPOB2 has genuinely delivered.
P1Q "update ppob1_transaction set local_state='AWAITING_PAYMENT', ppob2_state='PAYMENT_PENDING', last_source='CREATE_RESPONSE', recon_settled_at=null where reseller_txn_id='E2E-FREEZE-1'"
BEFORE=$(curl -s -X POST "$SNAP_URL?business_date=$TODAY_UTC")
BEFORE_ID=$(field "$BEFORE" id); BEFORE_DELIVERED=$(bgross "$BEFORE" DELIVERED)

curl -s -X POST "$P1/sim/recon/run" >/dev/null   # repairs the belief to COMPLETED
AFTER=$(curl -s -X POST "$SNAP_URL?business_date=$TODAY_UTC")
chk "the later snapshot sees the repaired belief" \
    "$(bgross "$AFTER" DELIVERED)" "$((BEFORE_DELIVERED + 20000))"
chk "...and is a new row, not an overwrite" \
    "$([ "$(field "$AFTER" id)" != "$BEFORE_ID" ] && echo t || echo f)" t
# The point: the earlier figure is unchanged on disk, so what was quoted then is still quotable.
chk "...while the earlier snapshot is untouched" \
    "$(P1Q "select gross from ppob1_collection_bucket where snapshot_id=$BEFORE_ID and bucket='DELIVERED'")" "$BEFORE_DELIVERED"
chk "latest for the date is the newer one" \
    "$(field "$(curl -s "$SNAP_URL/latest?business_date=$TODAY_UTC")" id)" "$(field "$AFTER" id)"
chk "history keeps both"  "$([ "$(count "$(curl -s "$SNAP_URL")" '"business_date"')" -ge 2 ] && echo t || echo f)" t
# "Nobody computed this" and "this day had no sales" are different answers.
chk "a never-snapshotted date is 404, not a zeroed census" \
    "$(curl -s -o /dev/null -w '%{http_code}' "$SNAP_URL/latest?business_date=2020-01-01")" 404

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
