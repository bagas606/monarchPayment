#!/usr/bin/env bash
# usage: cb.sh PG_REFERENCE AMOUNT [STATUS_CODE] [SIG_OVERRIDE]
PGREF="$1"; AMT="$2"; ST="${3:-00}"; SIGOVR="${4-}"
SECRET="${WEBHOOK_SECRET:-dev-webhook-secret}"
REF="${ORIGREF:-AYO-TEST-$RANDOM}"
# AMT is a plain rupiah integer and ".00" is appended, matching Ayolinx's documented decimal form.
# Set RAWAMT instead to put a string into `amount.value` verbatim -- needed to exercise the
# amount-format handling (grouping separators, European notation, garbage), where appending ".00"
# would silently turn every test input into something unparseable and make those tests pass for the
# wrong reason. That is exactly what happened before RAWAMT existed.
if [ -n "${RAWAMT-}" ]; then VALUE="$RAWAMT"; else VALUE="$AMT.00"; fi
BODY=$(printf '{"callbackType":"QRIS","additionalInfo":{"channel":"BNC_QRIS"},"amount":{"currency":"IDR","value":"%s"},"latestTransactionStatus":"%s","originalPartnerReferenceNo":"%s","originalReferenceNo":"%s","finishedTime":"%s"}' \
  "$VALUE" "$ST" "$PGREF" "$REF" "$(date -u +%Y-%m-%dT%H:%M:%S+00:00)")
if [ -n "$SIGOVR" ]; then SIG="$SIGOVR"; else
  SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.*= //'); fi
CODE=$(curl -s -o /tmp/cb.$$ -w '%{http_code}' -X POST http://localhost:8080/internal/webhooks/ayolinx \
  -H "Content-Type: application/json" -H "X-Ayolinx-Signature: $SIG" -d "$BODY")
echo "HTTP $CODE"; cat /tmp/cb.$$; echo; rm -f /tmp/cb.$$
