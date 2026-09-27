#!/usr/bin/env bash
# usage: call.sh METHOD PATH [BODY]
METHOD="$1"; URLPATH="$2"; BODY="${3-}"
SECRET="${SECRET:-dev-secret}"
CLIENT="${CLIENT:-ppob1-client}"
TS="${TS:-$(($(date +%s)*1000))}"
NONCE="${NONCE:-n-$RANDOM-$RANDOM-$(date +%s%N)}"
SIGNPATH="${URLPATH%%\?*}"
BH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | sed 's/^.*= //')
STS=$(printf '%s\n%s\n%s\n%s\n%s' "$METHOD" "$SIGNPATH" "$TS" "$NONCE" "$BH")
SIG=$(printf '%s' "$STS" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.*= //')
ARGS=(-s -o /tmp/resp.$$ -w '%{http_code}' -X "$METHOD" "http://localhost:8080$URLPATH"
  -H "X-Client-Id: $CLIENT" -H "X-Timestamp: $TS" -H "X-Nonce: $NONCE" -H "X-Signature: $SIG"
  -H "Content-Type: application/json")
[ -n "$BODY" ] && ARGS+=(-d "$BODY")
[ -n "$IDEMPOTENCY_KEY" ] && ARGS+=(-H "Idempotency-Key: $IDEMPOTENCY_KEY")
CODE=$(curl "${ARGS[@]}")
echo "HTTP $CODE"
cat /tmp/resp.$$ 2>/dev/null; echo; rm -f /tmp/resp.$$
