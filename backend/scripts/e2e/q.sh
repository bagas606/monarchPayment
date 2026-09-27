#!/usr/bin/env bash
# usage: q.sh "SQL" [extra psql args...]   e.g. q.sh "select 1" -t
SQL="$1"; shift
docker exec backend-postgres-1 psql -U ppob2 -d ppob2 "$@" -c "$SQL"
