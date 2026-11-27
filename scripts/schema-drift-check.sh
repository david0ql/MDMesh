#!/usr/bin/env bash
# Schema drift check. Two questions, both answered against a throwaway PostgreSQL built ONLY from the Liquibase
# changelog (own container, random loopback port, removed at exit):
#
#   scripts/schema-drift-check.sh
#       Does a fresh install satisfy every SQL statement of the server's mappers? Catches tables/columns the code
#       uses that no changeSet creates, mappers that fail to load, and a changelog that cannot run on an empty
#       database (server/src/test/java/com/hmdm/persistence/SchemaDriftTest.java).
#
#   scripts/schema-drift-check.sh --compare <schema.sql>
#       The above, PLUS: does a fresh install have everything a real database has? <schema.sql> is a
#       `pg_dump --schema-only` of production; every table, column (type, nullability, default), index and
#       constraint is compared. Run it before each deployment:
#         docker compose exec -T postgres pg_dump --schema-only --no-owner --no-privileges -U dallycontrol dallycontrol > prod-schema.sql
#       "only in the dump" = something was added to production by hand: write the changeSet (IF NOT EXISTS).
#       "only in a fresh install" = migrations production has not run yet (normal before a deployment); listed, not
#       an error.
#
# Environment: PG_IMAGE=postgres:17, JAVA_HOME (JDK 17 for Maven).
# Exit status: 0 no drift, 1 drift or failure.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPARE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --compare) COMPARE="${2:?--compare needs a pg_dump --schema-only file}"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -z "$COMPARE" ] || [ -r "$COMPARE" ] || { echo "cannot read $COMPARE" >&2; exit 2; }

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
command -v mvn >/dev/null || { echo "mvn is required" >&2; exit 1; }

NAME="dc-schema-drift-$$"
PASS="drift$$pw"
PG_IMAGE="${PG_IMAGE:-postgres:17}"
WORK=$(mktemp -d)
cleanup() { docker rm -f -v "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

docker run -d --name "$NAME" -e POSTGRES_DB=drift -e POSTGRES_USER=drift -e POSTGRES_PASSWORD="$PASS" \
  -p 127.0.0.1::5432 "$PG_IMAGE" >/dev/null
PORT=$(docker port "$NAME" 5432/tcp | head -1 | sed 's/.*://')
# The image starts a temporary server, stops it, then starts the real one: wait until a TCP query really works.
for _ in $(seq 1 60); do
  if docker exec "$NAME" psql -h 127.0.0.1 -U drift -d drift -Atc 'select 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$NAME" psql -h 127.0.0.1 -U drift -d drift -Atc 'select 1' >/dev/null

# Same as docker/server.Dockerfile: the build needs server/build.properties (untracked; the example has the defaults).
[ -f server/build.properties ] || cp server/build.properties.example server/build.properties

echo "== schema drift check against an empty $PG_IMAGE on 127.0.0.1:$PORT =="
SCHEMA_DRIFT_JDBC_URL="jdbc:postgresql://127.0.0.1:${PORT}/drift" \
SCHEMA_DRIFT_DB_USER=drift SCHEMA_DRIFT_DB_PASSWORD="$PASS" \
  mvn -q -pl common,server -am test -Dtest=SchemaDriftTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
echo "== mappers: no drift (the changelog creates everything the mappers use) =="

[ -n "$COMPARE" ] || exit 0

# ---- compare with a schema dump of a real database ------------------------------------------------------------------
echo "== comparing a fresh install with $COMPARE =="
psqlc() { docker exec -i "$NAME" psql -X -h 127.0.0.1 -U drift "$@"; }
psqlc -d drift -qc 'CREATE DATABASE reference' >/dev/null
# Owners, extensions and roles of the source do not exist here: load what loads, the comparison shows the rest.
psqlc -d reference -q -v ON_ERROR_STOP=0 < "$COMPARE" >/dev/null 2>"$WORK/load.err" || true
LOADED=$(psqlc -d reference -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
[ "$LOADED" -gt 0 ] || { echo "the dump created no tables (is it a pg_dump --schema-only?)" >&2; head -5 "$WORK/load.err" >&2; exit 1; }

# One line per object, normalised. Liquibase's own bookkeeping tables are not part of the application schema.
read -r -d '' LISTING <<'SQL' || true
SELECT 'column ' || c.table_name || '.' || c.column_name || ' ' ||
       CASE WHEN c.data_type IN ('character varying', 'character')
            THEN c.data_type || '(' || COALESCE(c.character_maximum_length::text, '') || ')' ELSE c.data_type END ||
       CASE WHEN c.is_nullable = 'NO' THEN ' NOT NULL' ELSE '' END ||
       COALESCE(' DEFAULT ' || c.column_default, '')
  FROM information_schema.columns c
  JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
 WHERE c.table_schema = 'public' AND c.table_name NOT LIKE 'databasechangelog%'
UNION ALL
SELECT 'index ' || tablename || ' ' || regexp_replace(indexdef, '^CREATE (UNIQUE )?INDEX \S+ ON ', '\1')
  FROM pg_indexes WHERE schemaname = 'public' AND tablename NOT LIKE 'databasechangelog%'
UNION ALL
SELECT 'constraint ' || rel.relname || ' ' || pg_get_constraintdef(con.oid)
  FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid JOIN pg_namespace n ON n.oid = rel.relnamespace
 WHERE n.nspname = 'public' AND rel.relname NOT LIKE 'databasechangelog%' AND con.contype IN ('p', 'f', 'u', 'c')
UNION ALL
SELECT 'function ' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
ORDER BY 1
SQL
psqlc -d drift -At -c "$LISTING" | sort -u > "$WORK/fresh.txt"
psqlc -d reference -At -c "$LISTING" | sort -u > "$WORK/reference.txt"

comm -13 "$WORK/fresh.txt" "$WORK/reference.txt" > "$WORK/only-dump.txt"
comm -23 "$WORK/fresh.txt" "$WORK/reference.txt" > "$WORK/only-fresh.txt"
echo "objects compared: fresh install $(wc -l < "$WORK/fresh.txt" | tr -d ' '), dump $(wc -l < "$WORK/reference.txt" | tr -d ' ')"
if [ -s "$WORK/only-fresh.txt" ]; then
  echo "-- only in a fresh install ($(wc -l < "$WORK/only-fresh.txt" | tr -d ' ')): migrations the dumped database has not run yet, or a definition that differs (see below)"
  sed 's/^/   + /' "$WORK/only-fresh.txt"
fi
if [ -s "$WORK/only-dump.txt" ]; then
  echo "-- ONLY IN THE DUMP ($(wc -l < "$WORK/only-dump.txt" | tr -d ' ')): the changelog does not create these. Write a changeSet (IF NOT EXISTS); never alter production by hand"
  sed 's/^/   - /' "$WORK/only-dump.txt"
  echo "== DRIFT: a fresh install (or a restore into a new server) would differ from the dumped database =="
  exit 1
fi
echo "== no drift: a fresh install has everything the dumped database has =="
