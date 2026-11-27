#!/usr/bin/env bash
# FAILOVER: turns this standby into the main server (docs/ops/ALTA-DISPONIBILIDAD.md, docs/ops/RUNBOOK.md).
# Run on the STANDBY, from the project directory, only when the primary is lost or is being retired.
#
#   scripts/ha-promote.sh --yes [--db-only]
#
# 1. Refuses when the primary still answers (two primaries = two diverging databases), unless --force.
# 2. Promotes the replica to a read-write primary and waits until it accepts writes.
# 3. Starts the whole stack here (server, edge, supervisor and the profiles of .env; with the Cloudflare tunnel the
#    connector registers with the same token and traffic arrives here with no DNS change). --db-only skips this.
#
# AFTER a failover the old primary must never be started as it was: it has to be rebuilt as the new standby.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && { set -a; . ./.env; set +a; }
YES=0; DB_ONLY=0; FORCE=0
for a in "$@"; do case "$a" in --yes) YES=1 ;; --db-only) DB_ONLY=1 ;; --force) FORCE=1 ;; *) echo "opción desconocida: $a" >&2; exit 2 ;; esac; done
[ "$YES" = 1 ] || { echo "Esto convierte este servidor en el principal. Repita con --yes." >&2; exit 2; }
say() { printf '\033[1;36m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*"; }
psqlc() { docker compose exec -T postgres psql -X -At -v ON_ERROR_STOP=1 -U "${DB_USER:-dallycontrol}" -d "${DB_NAME:-dallycontrol}" "$@"; }
T0=$(date +%s)

if [ "$(psqlc -c 'SELECT pg_is_in_recovery()')" != t ]; then
  say "Este PostgreSQL ya es primario (no está en modo réplica)."
else
  if [ "$FORCE" != 1 ] && [ -n "${PRIMARY_HOST:-}" ]; then
    if docker compose exec -T postgres pg_isready -q -h "$PRIMARY_HOST" -p "${PRIMARY_PORT:-5432}" -t 5; then
      echo "El primario ${PRIMARY_HOST}:${PRIMARY_PORT:-5432} TODAVÍA responde. Promover ahora crearía dos bases de datos que divergen." >&2
      echo "Apague primero el primario (docker compose stop en ese servidor) o, si es inalcanzable para los usuarios y está seguro, repita con --force." >&2
      exit 1
    fi
  fi
  LAG=$(psqlc -c "SELECT COALESCE(round(extract(epoch FROM now() - pg_last_xact_replay_timestamp()))::text, 'desconocido')")
  say "Promoviendo la réplica (última transacción aplicada hace ${LAG} s)…"
  psqlc -c "SELECT pg_promote(true, 60)" >/dev/null
  for _ in $(seq 1 30); do [ "$(psqlc -c 'SELECT pg_is_in_recovery()')" = f ] && break; sleep 1; done
  [ "$(psqlc -c 'SELECT pg_is_in_recovery()')" = f ] || { echo "La promoción no terminó." >&2; exit 1; }
  psqlc -c "CREATE TABLE IF NOT EXISTS ha_failover_log (at timestamptz DEFAULT now(), note text); INSERT INTO ha_failover_log (note) VALUES ('promoted')" >/dev/null
  say "Base de datos promovida y aceptando escrituras en $(( $(date +%s) - T0 )) s."
fi

if [ "$DB_ONLY" = 1 ]; then say "Solo base de datos (--db-only): el resto del stack no se arranca."; exit 0; fi
say "Arrancando el servicio completo en este servidor…"
docker compose up -d
for _ in $(seq 1 150); do
  st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$(docker compose ps -q server)" 2>/dev/null || echo unknown)
  [ "$st" = healthy ] && break
  sleep 2
done
[ "$st" = healthy ] || { echo "El servidor no quedó saludable (estado: $st): docker compose logs --tail 80 server" >&2; exit 1; }
say "CONMUTACIÓN COMPLETA en $(( $(date +%s) - T0 )) s: este servidor es ahora el principal."
echo "Pendiente: 1) confirme el acceso público; 2) NO arranque el primario antiguo tal como quedó;"
echo "           3) reconstrúyalo como réplica nueva (docs/ops/ALTA-DISPONIBILIDAD.md, «Volver a tener réplica»)."
