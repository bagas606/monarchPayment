#!/usr/bin/env bash
# Posts a Section 23.8 ORDER_STATUS_CHANGED callback to PPOB1, signed as PPOB2 would sign it.
#
# usage: wh.sh ORDER_ID STATE [NONCE] [SIG_OVERRIDE]
#
# Exists to drive the receiver's refusal paths, which PPOB2 itself will never produce: a wrong
# signature, an unknown order, a malformed body. Those are the branches that decide whether the
# receiver is a control or just a parser, and without this they could only be unit-tested.
#
# The signature deliberately mirrors OutboundWebhookSender exactly: HMAC-SHA256 hex over
# "POST\n{callback path}\n{epoch ms}\n{nonce}\n{sha256 hex of body}", with the partner's
# webhook secret. Note the body carries an ISO-8601 `timestamp` while the header X-Timestamp is
# epoch milliseconds -- two different formats of two slightly different instants, and the HEADER is
# the one that is signed. Signing the body's value fails in a way that looks exactly like a wrong
# secret, which is why wh.sh keeps them visibly separate.
set -u

ORDER_ID="$1"; STATE="$2"; NONCE="${3:-wh-$RANDOM-$(date +%s%N)}"; SIGOVR="${4-}"
SECRET="${PPOB1_WEBHOOK_SECRET:-ppob1-webhook-secret}"
URL="${PPOB1_CALLBACK_URL:-http://localhost:8081/webhooks/ppob2}"
# The signed path is the URL's path component only -- PPOB2 uses URI.create(callbackUrl).getPath().
CALLBACK_PATH="${PPOB1_CALLBACK_PATH:-/webhooks/ppob2}"
TS="${TS:-$(($(date +%s)*1000))}"

# RAWBODY sends a body verbatim, for the malformed case. Without it every body here is well-formed
# and the MALFORMED branch is unreachable from this script.
#
# RESELLER_TXN_ID adds the sixth field PPOB2 sends (OutboundWebhookSender.payload), which is
# omitted entirely when unset -- exactly as PPOB2 omits it for an order carrying no metadata. It is
# needed here for the one case PPOB2 cannot be made to produce: a callback naming an order id PPOB1
# does not hold against a reseller id it already holds against a different one
# (WebhookVerdict.ORDER_ID_CONFLICT). That takes two upstream orders for one reseller id, which
# requires a second api_client, which this fixture does not have.
if [ -n "${RAWBODY-}" ]; then
  BODY="$RAWBODY"
elif [ -n "${RESELLER_TXN_ID-}" ]; then
  BODY=$(printf '{"event_type":"ORDER_STATUS_CHANGED","order_id":"%s","state":"%s","timestamp":"%s","nonce":"%s","reseller_txn_id":"%s"}' \
    "$ORDER_ID" "$STATE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$NONCE" "$RESELLER_TXN_ID")
else
  BODY=$(printf '{"event_type":"ORDER_STATUS_CHANGED","order_id":"%s","state":"%s","timestamp":"%s","nonce":"%s"}' \
    "$ORDER_ID" "$STATE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$NONCE")
fi

BH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | sed 's/^.*= //')
STS=$(printf 'POST\n%s\n%s\n%s\n%s' "$CALLBACK_PATH" "$TS" "$NONCE" "$BH")
if [ -n "$SIGOVR" ]; then
  SIG="$SIGOVR"
else
  SIG=$(printf '%s' "$STS" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.*= //')
fi

CODE=$(curl -s -o /tmp/wh.$$ -w '%{http_code}' -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "X-Timestamp: $TS" -H "X-Nonce: $NONCE" -H "X-Signature: $SIG" \
  -d "$BODY")
echo "HTTP $CODE"
cat /tmp/wh.$$ 2>/dev/null; echo; rm -f /tmp/wh.$$
