#!/usr/bin/env bash
# usage: q.sh "SQL" [extra psql args...]   e.g. q.sh "select 1" -t
#
# The single place in the harness that knows how to reach Postgres, so the scripts work against
# either the docker-compose service or a Postgres installed on the host (a CI container, or any box
# without a Docker daemon). Resolution order:
#
#   1. $PPOB2_PSQL  -- an explicit command, word-split, e.g. PPOB2_PSQL="psql -h db.internal -U ppob2 -d ppob2"
#   2. the docker-compose container ($PPOB2_PG_CONTAINER, default backend-postgres-1), if it is running
#   3. a native psql on PATH, against $PGHOST/$PGPORT (default localhost:5432) as ppob2/ppob2
#
# Step 2 is probed rather than assumed: `docker exec` against a missing daemon fails with a message
# on stderr and a non-zero status, which the callers' `2>/dev/null` would turn into an empty scalar
# and a sweep of unexplained assertion failures rather than one clear error.
SQL="$1"; shift

if [ -n "${PPOB2_PSQL-}" ]; then
  # shellcheck disable=SC2086  # deliberate word-splitting: the override is a command, not one word
  exec $PPOB2_PSQL "$@" -c "$SQL"
fi

PG_CONTAINER="${PPOB2_PG_CONTAINER:-backend-postgres-1}"
if command -v docker >/dev/null 2>&1 &&
   [ "$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null)" = "true" ]; then
  exec docker exec -i "$PG_CONTAINER" psql -U ppob2 -d ppob2 "$@" -c "$SQL"
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "q.sh: no Postgres reachable -- the '$PG_CONTAINER' container is not running and there is no" >&2
  echo "      psql on PATH. Start docker compose, or set PPOB2_PSQL to a working psql command." >&2
  exit 1
fi

exec env PGPASSWORD="${PGPASSWORD:-ppob2}" \
  psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" -U ppob2 -d ppob2 "$@" -c "$SQL"
