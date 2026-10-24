# Sourced by the suites that authenticate to /admin/** with `curl -u` (HTTP Basic).
#
# HTTP Basic is OFF by default since Section 42.3's sessions + MFA landed (it carries no TOTP, so while
# it is on MFA is bypassed). These suites predate that and need it, so they must say so up front: against
# a default boot every `-u` request is a 401, and the run would report dozens of unrelated-looking
# failures instead of one clear reason.
#
#   PPOB2_ADMIN_BASIC_AUTH_ENABLED=true ./gradlew :app:bootRun      # or add it to the env of `java -jar`
_pf_code=$(curl -s -o /dev/null -w '%{http_code}' -u superadmin:admin123 "${PPOB2_BASE_URL:-http://localhost:8080}/admin/reconciliations")
if [ "$_pf_code" = "401" ]; then
  echo "PREFLIGHT FAILED: PPOB2 refused HTTP Basic credentials (401)." >&2
  echo "  Either the DB was not seeded (dev-seed.sql creates superadmin/admin123), or the app was booted" >&2
  echo "  without  PPOB2_ADMIN_BASIC_AUTH_ENABLED=true  -- this suite authenticates with curl -u." >&2
  exit 2
fi
unset _pf_code
