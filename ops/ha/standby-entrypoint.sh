#!/usr/bin/env bash
# Entrypoint of the standby's PostgreSQL (docker-compose.ha-standby.yml). On the first start (empty data directory)
# it clones the primary with pg_basebackup and writes the standby configuration (-R: standby.signal +
# primary_conninfo); afterwards it just starts PostgreSQL, which follows the primary by streaming replication.
# After a promotion (scripts/ha-promote.sh) the data directory is a normal primary and this is a plain start.
set -euo pipefail
PGDATA="${PGDATA:-/var/lib/postgresql/data}"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  : "${PRIMARY_HOST:?PRIMARY_HOST is required}"
  : "${REPLICATION_PASSWORD:?REPLICATION_PASSWORD is required}"
  echo "standby: empty data directory, cloning the primary ${PRIMARY_HOST}:${PRIMARY_PORT:-5432}…"
  mkdir -p "$PGDATA"
  chown -R postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
  # Keeps trying: the standby may be started before the primary is prepared (scripts/ha-primary-setup.sh).
  until PGPASSWORD="$REPLICATION_PASSWORD" gosu postgres pg_basebackup \
      --host="$PRIMARY_HOST" --port="${PRIMARY_PORT:-5432}" --username="${REPLICATION_USER:-replicator}" \
      --pgdata="$PGDATA" --wal-method=stream --slot="${REPLICATION_SLOT:-dallycontrol_standby}" \
      --write-recovery-conf --checkpoint=fast --no-password; do
    echo "standby: the primary is not reachable or not prepared yet; retrying in 10 s…"
    # pg_basebackup refuses a non-empty directory: clear what a failed attempt left.
    find "$PGDATA" -mindepth 1 -delete 2>/dev/null || true
    sleep 10
  done
  echo "standby: clone finished; starting as a hot standby."
fi
exec docker-entrypoint.sh "$@"
