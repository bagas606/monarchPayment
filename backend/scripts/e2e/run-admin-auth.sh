#!/usr/bin/env bash
# Admin sessions + TOTP MFA (Section 42.3), end to end: login, MFA enrolment/challenge, replay, attempt and
# lockout limits, idle / absolute / pending expiry, revocation, CSRF, and what reaches audit_log and the DB.
#
# Boots ITS OWN app instance on :8081 against the same database, because the point is to test with
#   - HTTP Basic OFF   (so a Basic request must be refused, the production posture), and
#   - timeouts of seconds (idle 6s / absolute 15s / pending 8s) so expiry is observed, not assumed.
# The :8080 instance the other suites use is untouched. Run AFTER run-core.sh: it adds audit_log rows,
# and run-core.sh asserts absolute audit counts.
#
#   PPOB2_JAR   path to the bootJar (default: backend/app/build/libs/app-0.1.0-SNAPSHOT.jar)
#   needs: python3, curl, a seeded DB (superadmin / admin123 from dev-seed.sql)
SP="$(cd "$(dirname "$0")" && pwd)"
Q="$SP/q.sh"
JAR="${PPOB2_JAR:-$SP/../../app/build/libs/app-0.1.0-SNAPSHOT.jar}"
PORT=8081
B="http://localhost:$PORT"
KEY="$(python3 -c 'import os,base64;print(base64.b64encode(os.urandom(32)).decode())')"
pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1  -- got: $2"; fail=$((fail+1)); }
chk()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$2 (want $3)"; fi; }
scalar() { $Q "$1" -t 2>/dev/null | tr -d ' \n\r'; }
py() { printf '%s' "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print($2)" 2>/dev/null; }
count() { printf '%s' "$1" | grep -o -- "$2" | wc -l | tr -d ' '; }

# ---- helpers ------------------------------------------------------------------------------------
totp() { python3 - "$1" "${2:-0}" <<'PY'
import sys,hmac,hashlib,struct,time,base64
s=sys.argv[1].upper(); s+='='*((8-len(s)%8)%8)
k=base64.b32decode(s); c=struct.pack('>Q',int(time.time())//30+int(sys.argv[2]))
h=hmac.new(k,c,hashlib.sha1).digest(); o=h[-1]&15
print('%06d'%((struct.unpack('>I',h[o:o+4])[0]&0x7fffffff)%1000000))
PY
}
step() { echo $(( $(date +%s) / 30 )); }
# block until the TOTP time-step differs from $1, so a code cannot collide with one already accepted
wait_past_step() { while [ "$(step)" -le "$1" ]; do sleep 1; done; }
post() { # path token body [extra curl args]  -> body, then " HTTP<code>"
  local path="$1" token="$2" body="$3"; shift 3
  [ -z "$body" ] && body='{}'   # NOT "${body:-{}}": bash would append a stray "}" to a non-empty body
  curl -s -w ' HTTP%{http_code}' -X POST "$B$path" ${token:+-H "Authorization: Bearer $token"} -H 'Content-Type: application/json' -d "$body" "$@"
}
code_of() { echo "$1" | grep -o 'HTTP[0-9]*$' | tr -d 'HTTP'; }
body_of() { echo "${1% HTTP*}"; }
get() { curl -s -o /dev/null -w '%{http_code}' ${2:+-H "Authorization: Bearer $2"} "$B$1"; }
login() { post /admin/auth/login "" "{\"username\":\"$1\",\"password\":\"${2:-admin123}\",\"return_token\":true}"; }
tok() { py "$(body_of "$1")" "d['token']"; }
db_user_reset() { $Q "update admin_user set mfa_secret=null, mfa_enabled=false, mfa_last_step=null, failed_logins=0, locked_until=null, status='ACTIVE' where username='$1'" >/dev/null; }
ensure_user() { # username role
  $Q "insert into admin_user (username,password_hash) values ('$1','\$2a\$10\$sumJVTdd5Bhn4p0xeaYwxu5thzoF7Ok5S2NUE19rm5gB2iwE6KgtS') on conflict do nothing" -q >/dev/null 2>&1
  $Q "insert into admin_user_role (admin_user_id, role_id) select u.id, r.id from admin_user u, role r where u.username='$1' and r.code='$2' on conflict do nothing" >/dev/null 2>&1
  db_user_reset "$1"
}

# ---- boot our own instance ----------------------------------------------------------------------
if [ ! -f "$JAR" ]; then echo "no jar at $JAR -- run ./gradlew :app:bootJar"; exit 2; fi
if curl -s "localhost:$PORT/actuator/health" | grep -q UP; then echo "port $PORT already in use"; exit 2; fi
ensure_user superadmin SUPER_ADMIN; ensure_user ops1 OPERATIONS; ensure_user finance1 FINANCE; ensure_user viewer1 VIEWER
$Q "delete from admin_session" >/dev/null
env PORT=$PORT PPOB2_ADMIN_BASIC_AUTH_ENABLED=false PPOB2_ADMIN_MFA_ENCRYPTION_KEY="$KEY" PPOB2_ADMIN_COOKIE_SECURE=false \
    PPOB2_ADMIN_SESSION_IDLE_TIMEOUT=PT6S PPOB2_ADMIN_SESSION_ABSOLUTE_TIMEOUT=PT15S PPOB2_ADMIN_SESSION_PENDING_TIMEOUT=PT8S \
    PPOB2_ADMIN_MFA_MAX_ATTEMPTS=5 PPOB2_ADMIN_LOGIN_MAX_FAILURES=5 PPOB2_ADMIN_LOGIN_LOCKOUT=PT15M \
    setsid nohup java -jar "$JAR" > /tmp/ppob2-admin-auth.log 2>&1 < /dev/null &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null' EXIT
for i in $(seq 1 60); do sleep 2; curl -s "localhost:$PORT/actuator/health" | grep -q UP && break; done
curl -s "localhost:$PORT/actuator/health" | grep -q UP || { echo "app did not start; see /tmp/ppob2-admin-auth.log"; exit 2; }

echo "== Basic auth is OFF (the production posture) =="
chk "HTTP Basic is refused"                          "$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 $B/admin/parent-orders)" 401
R=$(curl -s -i $B/admin/parent-orders)
chk "unauthenticated -> 401, JSON error"             "$(py "${R#*$'\r\n\r\n'}" "d['error_code']")" AUTHENTICATION_FAILED
chk "...with no WWW-Authenticate (no browser popup)" "$(echo "$R" | grep -ci '^www-authenticate')" 0

AUD_FAILED0=$(scalar "select count(*) from audit_log where action='ADMIN_LOGIN_FAILED' and after_state->>'username' in ('ops1','nobody-here')")
AUD_LOGOUT0=$(scalar "select count(*) from audit_log where action='ADMIN_LOGOUT'")
echo "== Backoffice UI is served with its security headers =="
UH=$(curl -s -i $B/backoffice/)
chk "index is served"                                 "$(echo "$UH" | head -1 | grep -o '[0-9][0-9][0-9]')" 200
chk "CSP forbids inline script and foreign origins"   "$(echo "$UH" | grep -i '^content-security-policy:' | grep -c "default-src 'none'; script-src 'self'")" 1
chk "CSP forbids framing"                             "$(echo "$UH" | grep -i '^content-security-policy:' | grep -c "frame-ancestors 'none'")" 1
chk "X-Frame-Options: DENY"                           "$(echo "$UH" | grep -ci '^x-frame-options: deny')" 1
chk "Referrer-Policy: no-referrer"                    "$(echo "$UH" | grep -ci '^referrer-policy: no-referrer')" 1
chk "page contains no inline script"                  "$(echo "$UH" | grep -ci '<script[^>]*>[^<]')" 0
chk "bare /backoffice redirects to /backoffice/"      "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' $B/backoffice)" "302 $B/backoffice/"
chk "the UI holds no data: an asset needs no login"   "$(curl -s -o /dev/null -w '%{http_code}' $B/backoffice/js/main.js)" 200

echo "== Login failures =="
R=$(login ops1 WRONGPW); chk "wrong password -> 401"  "$(code_of "$R")" 401
M1=$(py "$(body_of "$R")" "d['message']")
R=$(login nobody-here whatever); chk "unknown user -> 401" "$(code_of "$R")" 401
chk "...with the identical message (no user enumeration)" "$(py "$(body_of "$R")" "d['message']")" "$M1"
chk "login failures are audited (TC-ADM-002, via the same listener)" "$(( $(scalar "select count(*) from audit_log where action='ADMIN_LOGIN_FAILED' and after_state->>'username' in ('ops1','nobody-here')") - AUD_FAILED0 ))" 2
chk "the submitted password is never audited"        "$(scalar "select count(*) from audit_log where after_state::text like '%WRONGPW%' or after_state::text like '%admin123%'")" 0

echo "== Session without mandatory MFA (OPERATIONS) =="
R=$(login ops1); OPS=$(tok "$R")
chk "login -> AUTHENTICATED"                          "$(py "$(body_of "$R")" "d['status']")" AUTHENTICATED
chk "response names the user's permissions"           "$(py "$(body_of "$R")" "'order:view' in d['user']['permissions']")" True
chk "token is not stored: no row holds the raw token" "$(scalar "select count(*) from admin_session where token_hash='$OPS'")" 0
chk "...only its SHA-256 is"                          "$(scalar "select count(*) from admin_session where token_hash='$(printf '%s' "$OPS" | sha256sum | cut -d' ' -f1)'")" 1
chk "Bearer: data endpoint -> 200"                    "$(get /admin/parent-orders?size=1 "$OPS")" 200
chk "Bearer: endpoint without the permission -> 403"  "$(get /admin/audit-logs "$OPS")" 403
chk "/admin/auth/me -> 200"                           "$(get /admin/auth/me "$OPS")" 200
chk "garbage bearer token -> 401"                     "$(get /admin/parent-orders "nope")" 401
H=$(curl -s -i -X POST $B/admin/auth/login -H 'Content-Type: application/json' -d '{"username":"viewer1","password":"admin123"}')
chk "cookie is HttpOnly"                              "$(echo "$H" | grep -ci '^set-cookie:.*httponly')" 1
chk "cookie is SameSite=Strict"                       "$(echo "$H" | grep -ci '^set-cookie:.*samesite=strict')" 1
chk "no token in the body unless asked for"           "$(count "${H#*$'\r\n\r\n'}" '"token"')" 0
chk "...the CSRF value is"                            "$(count "${H#*$'\r\n\r\n'}" '"csrf_token"')" 1

echo "== Cookie + CSRF =="
JAR_C=$(mktemp); CSRF=$(py "$(curl -s -c $JAR_C -X POST $B/admin/auth/login -H 'Content-Type: application/json' -d '{"username":"ops1","password":"admin123"}')" "d['csrf_token']")
chk "/me hands a cookie session its CSRF value (the page cannot read the HttpOnly cookie to derive it)" "$(py "$(curl -s -b $JAR_C $B/admin/auth/me)" "d['csrf_token']")" "$CSRF"
chk "cookie alone: GET -> 200"                        "$(curl -s -o /dev/null -w '%{http_code}' -b $JAR_C $B/admin/parent-orders?size=1)" 200
chk "cookie, POST, no CSRF header -> 403"             "$(curl -s -o /dev/null -w '%{http_code}' -b $JAR_C -X POST $B/admin/auth/logout)" 403
chk "cookie, POST, wrong CSRF -> 403"                 "$(curl -s -o /dev/null -w '%{http_code}' -b $JAR_C -H 'X-CSRF-Token: wrong' -X POST $B/admin/auth/logout)" 403
chk "...and the session survived those refusals"      "$(curl -s -o /dev/null -w '%{http_code}' -b $JAR_C $B/admin/parent-orders?size=1)" 200
HL=$(curl -s -i -b $JAR_C -H "X-CSRF-Token: $CSRF" -X POST $B/admin/auth/logout)
chk "cookie, POST, correct CSRF: logout -> 204"       "$(echo "$HL" | head -1 | grep -o '[0-9][0-9][0-9]')" 204
chk "...and the cookie is cleared"                    "$(echo "$HL" | grep -ci '^set-cookie:.*max-age=0')" 1
chk "...and the old cookie no longer works"           "$(curl -s -o /dev/null -w '%{http_code}' -b $JAR_C $B/admin/parent-orders?size=1)" 401
rm -f $JAR_C
R=$(login ops1); T=$(tok "$R")
chk "Bearer POST needs no CSRF header (not ambient)"  "$(code_of "$(post /admin/auth/logout "$T" "")")" 204
chk "logout revokes: token -> 401"                    "$(get /admin/parent-orders "$T")" 401
chk "logout is audited (both logouts above)"          "$(( $(scalar "select count(*) from audit_log where action='ADMIN_LOGOUT'") - AUD_LOGOUT0 ))" 2

echo "== Timeouts (idle 6s / absolute 15s / pending 8s) =="
R=$(login ops1); T=$(tok "$R"); sleep 8
chk "idle for 8s -> 401"                              "$(get /admin/parent-orders "$T")" 401
chk "...recorded as EXPIRED_IDLE"                     "$(scalar "select revoked_reason from admin_session order by id desc limit 1")" EXPIRED_IDLE
R=$(login ops1); T=$(tok "$R"); alive=1
for i in 1 2 3; do sleep 4; [ "$(get /admin/parent-orders?size=1 "$T")" = 200 ] || alive=0; done
chk "used every 4s for 12s (> idle limit): still alive" "$alive" 1
sleep 4; sleep 1
chk "past 15s absolute, despite constant use -> 401"  "$(get /admin/parent-orders "$T")" 401
chk "...recorded as EXPIRED_ABSOLUTE (activity never extends it)" "$(scalar "select revoked_reason from admin_session order by id desc limit 1")" EXPIRED_ABSOLUTE

echo "== Mandatory MFA: SUPER_ADMIN enrols =="
R=$(login superadmin); PEND=$(tok "$R")
chk "password alone -> MFA_ENROLLMENT_REQUIRED"       "$(py "$(body_of "$R")" "d['status']")" MFA_ENROLLMENT_REQUIRED
chk "...and no user permissions are described"        "$(py "$(body_of "$R")" "d.get('user')")" None
chk "pending session on a data endpoint -> 403"       "$(get /admin/parent-orders "$PEND")" 403
chk "pending session on /me -> 403"                   "$(get /admin/auth/me "$PEND")" 403
chk "confirm before enrolling -> 409"                 "$(code_of "$(post /admin/auth/mfa/confirm "$PEND" '{"code":"123456"}')")" 409
E=$(post /admin/auth/mfa/enroll "$PEND" ""); SEC=$(py "$(body_of "$E")" "d['secret']")
chk "enrol returns a base32 secret + otpauth URI"     "$(py "$(body_of "$E")" "d['otpauth_uri'].startswith('otpauth://totp/')")" True
chk "...MFA is not on until a code is confirmed"      "$(scalar "select mfa_enabled from admin_user where username='superadmin'")" f
chk "...the secret is stored, but encrypted"          "$(scalar "select (mfa_secret is not null and position('$SEC' in mfa_secret) = 0) from admin_user where username='superadmin'")" t
R=$(post /admin/auth/mfa/confirm "$PEND" '{"code":"000000"}'); chk "wrong confirmation code -> 401" "$(code_of "$R")" 401
chk "...error code MFA_CODE_INVALID"                  "$(py "$(body_of "$R")" "d['error_code']")" MFA_CODE_INVALID
chk "...MFA still off"                                "$(scalar "select mfa_enabled from admin_user where username='superadmin'")" f
S1=$(step)
R=$(post /admin/auth/mfa/confirm "$PEND" "{\"code\":\"$(totp $SEC)\",\"return_token\":true}")
chk "right code -> AUTHENTICATED"                     "$(py "$(body_of "$R")" "d['status']")" AUTHENTICATED
ADM=$(tok "$R")
chk "...MFA is now on"                                "$(scalar "select mfa_enabled from admin_user where username='superadmin'")" t
chk "...the pre-MFA token died at promotion"          "$(get /admin/auth/me "$PEND")" 401
chk "...the new token reaches data"                   "$(get /admin/parent-orders?size=1 "$ADM")" 200
chk "...and holds SUPER_ADMIN's permissions"          "$(get /admin/audit-logs "$ADM")" 200
chk "re-enrolling an enrolled account -> 409"         "$(code_of "$(post /admin/auth/mfa/enroll "$ADM" "")")" 409

echo "== MFA challenge =="
wait_past_step "$S1"
R=$(login superadmin); P2=$(tok "$R")
chk "second login -> MFA_REQUIRED"                    "$(py "$(body_of "$R")" "d['status']")" MFA_REQUIRED
chk "...password alone reaches nothing"               "$(get /admin/parent-orders "$P2")" 403
R=$(post /admin/auth/mfa/verify "$P2" '{"code":"111111"}'); chk "wrong code -> 401" "$(code_of "$R")" 401
S2=$(step); CODE=$(totp $SEC)
R=$(post /admin/auth/mfa/verify "$P2" "{\"code\":\"$CODE\",\"return_token\":true}")
chk "right code -> AUTHENTICATED"                     "$(py "$(body_of "$R")" "d['status']")" AUTHENTICATED
ADM2=$(tok "$R"); chk "...token works"                "$(get /admin/parent-orders?size=1 "$ADM2")" 200
chk "...the password-only token is dead (rotation)"   "$(get /admin/auth/me "$P2")" 401
R=$(login superadmin); P3=$(tok "$R")
R=$(post /admin/auth/mfa/verify "$P3" "{\"code\":\"$CODE\"}")
chk "replaying the SAME code on a new login -> 401"   "$(code_of "$R")" 401
# Wait for the next time-step, THEN log in again: a password-only session lives 8s and the wait can be 30s.
wait_past_step "$S2"
R=$(login superadmin); P3=$(tok "$R")
R=$(post /admin/auth/mfa/verify "$P3" "{\"code\":\"$(totp $SEC)\",\"return_token\":true}")
chk "a fresh code (next time-step) is accepted"       "$(py "$(body_of "$R")" "d['status']")" AUTHENTICATED
LAST=$(step)   # the step whose code was just consumed: later successes must wait past it

echo "== MFA attempt limit =="
R=$(login superadmin); P4=$(tok "$R")
for i in 1 2 3 4 5; do post /admin/auth/mfa/verify "$P4" '{"code":"222222"}' >/dev/null; done
GOOD=$(totp $SEC)
chk "after 5 wrong codes even the RIGHT one is refused" "$(code_of "$(post /admin/auth/mfa/verify "$P4" "{\"code\":\"$GOOD\"}")")" 401
chk "...session recorded as MFA_ATTEMPTS"             "$(scalar "select revoked_reason from admin_session where revoked_reason='MFA_ATTEMPTS' order by id desc limit 1")" MFA_ATTEMPTS

echo "== Pending session expiry (8s) =="
wait_past_step "$S2"
R=$(login superadmin); P5=$(tok "$R"); sleep 10
chk "a password-only session cannot be completed after 10s" "$(code_of "$(post /admin/auth/mfa/verify "$P5" "{\"code\":\"$(totp $SEC)\"}")")" 401

echo "== Lockout =="
for i in 1 2 3 4 5; do login finance1 BAD$i >/dev/null; done
chk "5 wrong passwords lock the account"              "$(scalar "select locked_until > now() from admin_user where username='finance1'")" t
R=$(login finance1 admin123)
chk "...the RIGHT password is then refused"           "$(code_of "$R")" 401
chk "...with the generic message (no 'locked' hint)"  "$(py "$(body_of "$R")" "d['message']")" "Authentication failed."
$Q "update admin_user set locked_until=null, failed_logins=0 where username='finance1'" >/dev/null
chk "once unlocked, a FINANCE login is held for MFA enrolment" "$(py "$(body_of "$(login finance1)")" "d['status']")" MFA_ENROLLMENT_REQUIRED

echo "== SUPER_ADMIN actions, in one fresh session (absolute limit is 15s) =="
wait_past_step "$LAST"
R=$(login ops1); OPS2=$(tok "$R")
JAR_C=$(mktemp)
R=$(curl -s -c $JAR_C -X POST $B/admin/auth/login -H 'Content-Type: application/json' -d '{"username":"superadmin","password":"admin123","return_token":true}')
CK_CSRF=$(py "$R" "d['csrf_token']")
R=$(curl -s -b $JAR_C -c $JAR_C -H "X-CSRF-Token: $CK_CSRF" -X POST $B/admin/auth/mfa/verify -H 'Content-Type: application/json' -d "{\"code\":\"$(totp $SEC)\",\"return_token\":true}")
CK_CSRF=$(py "$R" "d['csrf_token']"); ADM3=$(tok "$R")
chk "MFA over a cookie session -> AUTHENTICATED"      "$(py "$R" "d['status']")" AUTHENTICATED
chk "cookie POST without CSRF is refused"             "$(curl -s -o /dev/null -w '%{http_code}' -b $JAR_C -X POST $B/admin/auth/sessions/revoke -H 'Content-Type: application/json' -d '{"username":"nobody"}')" 403
chk "cookie POST WITH CSRF reaches the handler (400 = unknown user)" "$(curl -s -o /dev/null -w '%{http_code}' -b $JAR_C -H "X-CSRF-Token: $CK_CSRF" -X POST $B/admin/auth/sessions/revoke -H 'Content-Type: application/json' -d '{"username":"nobody"}')" 400
rm -f $JAR_C
chk "a non-SUPER_ADMIN cannot revoke sessions"        "$(code_of "$(post /admin/auth/sessions/revoke "$OPS2" '{"username":"viewer1"}')")" 403
AUD_REV0=$(scalar "select count(*) from audit_log where action='ADMIN_SESSIONS_REVOKED' and actor_id=(select id from admin_user where username='superadmin')")
R=$(post /admin/auth/sessions/revoke "$ADM3" '{"username":"ops1"}')
chk "SUPER_ADMIN revokes ops1's sessions"             "$(code_of "$R")" 200
chk "...at least the live one"                        "$(py "$(body_of "$R")" "d['revoked'] >= 1")" True
chk "...ops1's token is now dead"                     "$(get /admin/parent-orders "$OPS2")" 401
chk "...the action is audited with the actor"         "$(( $(scalar "select count(*) from audit_log where action='ADMIN_SESSIONS_REVOKED' and actor_id=(select id from admin_user where username='superadmin')") - AUD_REV0 ))" 1
chk "revoking an unknown user -> 400"                 "$(code_of "$(post /admin/auth/sessions/revoke "$ADM3" '{"username":"ghost"}')")" 400

echo "== Disabled account =="
R=$(login viewer1); VT=$(tok "$R")
chk "viewer1 session works"                           "$(get /admin/parent-orders?size=1 "$VT")" 200
$Q "update admin_user set status='DISABLED' where username='viewer1'" >/dev/null
chk "disabling the account kills its live session"    "$(get /admin/parent-orders?size=1 "$VT")" 401
chk "...and blocks a new login"                       "$(code_of "$(login viewer1)")" 401
$Q "update admin_user set status='ACTIVE' where username='viewer1'" >/dev/null

echo "== Audit trail (Section 42.3: login success/failure, MFA challenge) =="
for a in ADMIN_LOGIN_SUCCESS ADMIN_LOGIN_FAILED ADMIN_MFA_ENROLLED ADMIN_MFA_CHALLENGE_SUCCESS ADMIN_MFA_CHALLENGE_FAILED ADMIN_MFA_ENROLLMENT_STARTED ADMIN_LOGOUT ADMIN_SESSIONS_REVOKED; do
  chk "audit has $a"                                  "$(scalar "select count(*) > 0 from audit_log where action='$a'")" t
done
chk "no TOTP secret or code ever reaches audit_log"   "$(scalar "select count(*) from audit_log where after_state::text like '%$SEC%' or after_state::text like '%$CODE%'")" 0
chk "audited successes name the actor"                "$(scalar "select count(*) from audit_log where action='ADMIN_LOGIN_SUCCESS' and actor_id is null")" 0

echo ""
echo "================================"
echo "  PASS: $pass    FAIL: $fail"
echo "================================"
[ "$fail" -eq 0 ]
