#!/usr/bin/env bash
# Replication state of this server's PostgreSQL (docs/ops/ALTA-DISPONIBILIDAD.md). Works on the primary (lists the
# connected standbys and their lag) and on the standby (is it receiving, how far behind is it).
# Exit status: 0 healthy, 1 replication broken (a primary with no standby connected, or a standby not receiving).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && { set -a; . ./.env; set +a; }
psqlc() { docker compose exec -T postgres psql -X -At -F ' | ' -v ON_ERROR_STOP=1 -U "${DB_USER:-dallycontrol}" -d "${DB_NAME:-dallycontrol}" "$@"; }

if [ "$(psqlc -c 'SELECT pg_is_in_recovery()')" = t ]; then
  echo "Este servidor es RÉPLICA (en espera)."
  ROW=$(psqlc -c "SELECT status, sender_host, COALESCE(round(extract(epoch FROM now() - pg_last_xact_replay_timestamp()))::text, '?'), pg_last_wal_replay_lsn() FROM pg_stat_wal_receiver")
  if [ -z "$ROW" ]; then echo "  NO está recibiendo del primario (¿primario caído o enlace cortado?)"; exit 1; fi
  echo "  estado | primario | segundos desde la última transacción aplicada | posición"
  echo "  $ROW"
  echo "  (sin actividad en el primario, los segundos crecen aunque la réplica esté al día)"
else
  echo "Este servidor es PRIMARIO."
  ROWS=$(psqlc -c "SELECT client_addr, state, sync_state, pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) FROM pg_stat_replication")
  if [ -z "$ROWS" ]; then echo "  No hay ninguna réplica conectada."; exit 1; fi
  echo "  réplica | estado | modo | bytes de retraso"
  printf '%s\n' "$ROWS" | sed 's/^/  /'
  psqlc -c "SELECT '  ranura ' || slot_name || ': activa=' || active || ', WAL retenido=' || pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) FROM pg_replication_slots"
fi
