#!/usr/bin/env bash
# Prepares the PRIMARY's PostgreSQL for a streaming standby (docs/ops/ALTA-DISPONIBILIDAD.md). Idempotent.
# Run on the main server, from the project directory, after `docker compose up -d postgres` with
# docker-compose.ha-primary.yml in COMPOSE_FILE.
#
#   REPLICATION_PASSWORD=<generated> scripts/ha-primary-setup.sh [standby-cidr]
#     standby-cidr: who may connect for replication (default: any address, password required; the port is only
#                   published on the private link anyway). Example: 10.8.0.2/32
#
# Creates the replication role (REPLICATION_USER, default replicator), a physical replication slot
# (REPLICATION_SLOT, default dallycontrol_standby) and the pg_hba.conf rule, and reloads the configuration.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && { set -a; . ./.env; set +a; }
: "${REPLICATION_PASSWORD:?defina REPLICATION_PASSWORD (por ejemplo: openssl rand -hex 24) en .env o en el entorno}"
[[ "$REPLICATION_PASSWORD" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "REPLICATION_PASSWORD: solo letras, números, _ y -" >&2; exit 1; }
RUSER="${REPLICATION_USER:-replicator}"
SLOT="${REPLICATION_SLOT:-dallycontrol_standby}"
CIDR="${1:-all}"
DBU="${DB_USER:-dallycontrol}"; DBN="${DB_NAME:-dallycontrol}"
psqlc() { docker compose exec -T postgres psql -X -At -v ON_ERROR_STOP=1 -U "$DBU" -d "$DBN" "$@"; }

psqlc -c "DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$RUSER') THEN
    CREATE ROLE \"$RUSER\" WITH REPLICATION LOGIN PASSWORD '$REPLICATION_PASSWORD';
  ELSE
    ALTER ROLE \"$RUSER\" WITH REPLICATION LOGIN PASSWORD '$REPLICATION_PASSWORD';
  END IF; END \$\$;" >/dev/null
psqlc -c "SELECT pg_create_physical_replication_slot('$SLOT') WHERE NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '$SLOT')" >/dev/null
RULE="host replication $RUSER $CIDR scram-sha-256"
docker compose exec -T postgres sh -c "grep -qxF '$RULE' \"\$PGDATA/pg_hba.conf\" || printf '%s\n' '$RULE' >> \"\$PGDATA/pg_hba.conf\""
psqlc -c "SELECT pg_reload_conf()" >/dev/null
echo "Primario preparado: rol '$RUSER', ranura '$SLOT', regla pg_hba \"$RULE\"."
echo "wal_level=$(psqlc -c 'SHOW wal_level')  max_wal_senders=$(psqlc -c 'SHOW max_wal_senders')  réplicas conectadas=$(psqlc -c 'SELECT count(*) FROM pg_stat_replication')"
