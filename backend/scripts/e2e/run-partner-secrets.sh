#!/usr/bin/env bash
# Partner secrets at rest (V28 / SecretVault): what is stored, what is refused, how an operator provisions one.
#
# Needs the app booted with PPOB2_SECRET_KEYS / PPOB2_SECRET_ACTIVE_KEY_ID and PPOB2_ADMIN_BASIC_AUTH_ENABLED=true,
# freshly seeded (dev-seed.sql). Safe to re-run: it provisions the PPOB1 secrets itself and leaves its own
# throwaway client in place only as an unprovisioned row.
SP="$(cd "$(dirname "$0")" && pwd)"
. "$SP/preflight-basic.sh"
"$SP/provision-secrets.sh" || exit 2
CALL="$SP/call.sh"; Q="$SP/q.sh"
B="${PPOB2_BASE_URL:-http://localhost:8080}"
A=(-u superadmin:admin123 -H 'Content-Type: application/json')
pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
signed() { CLIENT="$1" SECRET="$2" $CALL GET "/api/v1/config/supported-amounts?product_code=MOBILE_LEGENDS" | head -1; }
put() { curl -s -o /tmp/ps.$$ -w '%{http_code}' "${A[@]}" -X PUT "$B$1" ${2:+-d "$2"}; }

echo "== What is stored =="
chk "the client secret is ciphertext in the v1.<keyId>. format" \
    "$(scalar "select secret_ciphertext like 'v1.k1.%' from api_client where client_id='ppob1-client'")" t
chk "...and does not contain the plaintext secret" \
    "$(scalar "select position('dev-secret' in secret_ciphertext)=0 from api_client where client_id='ppob1-client'")" t
chk "the webhook secret is ciphertext too" \
    "$(scalar "select webhook_secret_ciphertext like 'v1.k1.%' and position('ppob1-webhook-secret' in webhook_secret_ciphertext)=0 from partner where code='PPOB1'")" t
chk "the provisioned client still authenticates" "$(signed ppob1-client dev-secret)" "HTTP 200"
chk "...and the wrong secret does not" "$(signed ppob1-client not-the-secret)" "HTTP 401"

echo "== A value that is not our ciphertext is never used =="
$Q "insert into api_client (client_id, partner_id, secret_ciphertext) select 'e2e-secrets', id, 'plain-secret-1' from partner where code='PPOB1' on conflict (client_id) do update set secret_ciphertext='plain-secret-1'" >/dev/null 2>&1
chk "plaintext written by SQL: signing with that plaintext is refused" "$(signed e2e-secrets plain-secret-1)" "HTTP 401"
$Q "update api_client set secret_ciphertext=null where client_id='e2e-secrets'" >/dev/null 2>&1
chk "unprovisioned (NULL): refused" "$(signed e2e-secrets dev-secret)" "HTTP 401"
$Q "update api_client set secret_ciphertext=(select secret_ciphertext from api_client where client_id='ppob1-client') where client_id='e2e-secrets'" >/dev/null 2>&1
chk "PPOB1's ciphertext copied onto another client: refused (bound to its row)" "$(signed e2e-secrets dev-secret)" "HTTP 401"
chk "...while PPOB1 itself is unaffected" "$(signed ppob1-client dev-secret)" "HTTP 200"
$Q "update api_client set secret_ciphertext='v1.k1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' where client_id='e2e-secrets'" >/dev/null 2>&1
chk "well-formed but forged ciphertext: refused" "$(signed e2e-secrets dev-secret)" "HTTP 401"
$Q "update api_client set secret_ciphertext='v1.gone.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' where client_id='e2e-secrets'" >/dev/null 2>&1
chk "ciphertext under a key id this deployment lacks: refused" "$(signed e2e-secrets dev-secret)" "HTTP 401"

echo "== Provisioning through the admin API =="
BEFORE=$(scalar "select count(*) from audit_log where action='API_CLIENT_SECRET_SET'")
chk "generate: 200" "$(put /admin/api-clients/e2e-secrets/secret)" 200
GEN=$(grep -o '"secret":"[^"]*"' /tmp/ps.$$ | cut -d'"' -f4)
chk "...returns a secret once (>= 40 chars)" "$([ ${#GEN} -ge 40 ] && echo t || echo f)" t
chk "...and the client signs with it" "$(signed e2e-secrets "$GEN")" "HTTP 200"
chk "...stored encrypted, without the returned secret" \
    "$(scalar "select position('$GEN' in secret_ciphertext)=0 and secret_ciphertext like 'v1.k1.%' from api_client where client_id='e2e-secrets'")" t
FP=$(grep -o '"fingerprint":"[^"]*"' /tmp/ps.$$ | cut -d'"' -f4)
AUD=$(scalar "select after_state::text from audit_log where action='API_CLIENT_SECRET_SET' order by id desc limit 1")
chk "audit row written" "$(scalar "select count(*)-$BEFORE from audit_log where action='API_CLIENT_SECRET_SET'")" 1
chk "...carries the fingerprint and key id" "$(echo "$AUD" | grep -c "$FP.*k1\|k1.*$FP")" 1
chk "...but neither the secret nor its ciphertext" \
    "$([ -z "$(scalar "select 1 from audit_log where action='API_CLIENT_SECRET_SET' and (after_state::text like '%$GEN%' or after_state::text like '%v1.k1.%')")" ] && echo t || echo f)" t
OLD=$GEN
chk "rotate with a supplied secret: 200" "$(put /admin/api-clients/e2e-secrets/secret '{"secret":"rotated-secret-1"}')" 200
chk "...a supplied secret is not echoed back" "$(grep -c '"secret"' /tmp/ps.$$)" 0
chk "...the new one works" "$(signed e2e-secrets rotated-secret-1)" "HTTP 200"
chk "...the old one no longer does" "$(signed e2e-secrets "$OLD")" "HTTP 401"

echo "== Webhook secret =="
chk "set webhook secret: 200" "$(put /admin/partners/PPOB1/webhook-secret '{"secret":"ppob1-webhook-secret"}')" 200
chk "...stored encrypted, bound to the partner" \
    "$(scalar "select webhook_secret_ciphertext like 'v1.k1.%' and position('ppob1-webhook-secret' in webhook_secret_ciphertext)=0 from partner where code='PPOB1'")" t
chk "...audited as PARTNER_WEBHOOK_SECRET_SET" \
    "$([ "$(scalar "select count(*) from audit_log where action='PARTNER_WEBHOOK_SECRET_SET'")" -ge 1 ] && echo t || echo f)" t

echo "== Guard rails =="
chk "too-short secret: 400" "$(put /admin/api-clients/e2e-secrets/secret '{"secret":"short"}')" 400
chk "padded secret: 400" "$(put /admin/api-clients/e2e-secrets/secret '{"secret":" padded-secret "}')" 400
chk "unknown client: 404" "$(put /admin/api-clients/nope/secret)" 404
chk "unknown partner: 404" "$(put /admin/partners/NOPE/webhook-secret)" 404
chk "a refused change did not alter the stored value" "$(signed e2e-secrets rotated-secret-1)" "HTTP 200"
chk "no credentials: 401" "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$B/admin/api-clients/e2e-secrets/secret")" 401
$Q "insert into admin_user (username,password_hash) values ('viewer1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" >/dev/null 2>&1
$Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u, role r where u.username='viewer1' and r.code='VIEWER' on conflict do nothing" >/dev/null 2>&1
chk "VIEWER lacks partner_secret:manage: 403" \
    "$(curl -s -o /dev/null -w '%{http_code}' -u viewer1:admin123 -H 'Content-Type: application/json' -X PUT "$B/admin/api-clients/e2e-secrets/secret")" 403
chk "...and the value is unchanged" "$(signed e2e-secrets rotated-secret-1)" "HTTP 200"
chk "only SUPER_ADMIN holds partner_secret:manage" \
    "$(scalar "select string_agg(r.code, ',') from role_permission rp join role r on r.id=rp.role_id join permission p on p.id=rp.permission_id where p.code='partner_secret:manage'")" SUPER_ADMIN

rm -f /tmp/ps.$$
echo; echo "================================"; echo "  PASS: $pass    FAIL: $fail"; echo "================================"
[ "$fail" -eq 0 ]
