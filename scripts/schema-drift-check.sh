#!/usr/bin/env bash
# Schema drift check: does a database built ONLY from the Liquibase changelog satisfy every SQL statement of the
# server's mappers? Catches tables/columns that were added to production by hand and never written as a changeSet
# (which breaks every fresh install and every restore into a new server), and mappers that fail to load.
#
# Starts a throwaway, empty PostgreSQL in Docker (own container name, random loopback port, removed at exit),
# migrates it with the changelog and runs server/src/test/java/com/hmdm/persistence/SchemaDriftTest.java against it.
#
# Usage: scripts/schema-drift-check.sh
#   PG_IMAGE=postgres:17   image of the throwaway database
#   JAVA_HOME=...          JDK 17 for Maven
# Exit status: 0 no drift, 1 drift or failure.
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
command -v mvn >/dev/null || { echo "mvn is required" >&2; exit 1; }

NAME="dc-schema-drift-$$"
PASS="drift$$pw"
PG_IMAGE="${PG_IMAGE:-postgres:17}"
cleanup() { docker rm -f -v "$NAME" >/dev/null 2>&1 || true; }
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
echo "== no drift: the changelog creates everything the mappers use =="
