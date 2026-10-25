#!/usr/bin/env bash
# usage: provision-secrets.sh [client|webhook|all]      (default: all)
#
# Gives the seeded PPOB1 partner its two signing secrets through the admin API. Since V28 these are stored
# as ciphertext under PPOB2_SECRET_KEYS, so SQL (dev-seed.sql included) can no longer write them; this is
# the same path a real operator uses (PUT /admin/api-clients/{id}/secret, PUT /admin/partners/{code}/webhook-secret).
#
# The values are the ones the harness already signs with -- call.sh's SECRET=dev-secret and ppob1-sim's
# ppob1.webhook-secret -- so supplying them (rather than letting PPOB2 generate one) keeps both sides agreeing.
#
# Idempotent, and needs HTTP Basic enabled on the app (PPOB2_ADMIN_BASIC_AUTH_ENABLED=true) like the suites.
set -u
B="${PPOB2_BASE_URL:-http://localhost:8080}"
ADMIN="${PPOB2_ADMIN_CREDENTIALS:-superadmin:admin123}"
WHAT="${1:-all}"

put() { # path secret
  local code
  code=$(curl -s -o /tmp/provision.$$ -w '%{http_code}' -u "$ADMIN" -X PUT "$B$1" \
    -H 'Content-Type: application/json' -d "{\"secret\":\"$2\"}")
  if [ "$code" != "200" ]; then
    echo "provision-secrets: PUT $1 -> HTTP $code: $(cat /tmp/provision.$$)" >&2
    echo "  (404 = DB not seeded; 401/403 = app booted without PPOB2_ADMIN_BASIC_AUTH_ENABLED=true or no SUPER_ADMIN)" >&2
    rm -f /tmp/provision.$$; exit 2
  fi
  rm -f /tmp/provision.$$
}

case "$WHAT" in client|all)  put /admin/api-clients/ppob1-client/secret "${CLIENT_SECRET:-dev-secret}" ;; esac
case "$WHAT" in webhook|all) put /admin/partners/PPOB1/webhook-secret "${WEBHOOK_SECRET:-ppob1-webhook-secret}" ;; esac
