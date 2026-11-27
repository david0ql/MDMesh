#!/usr/bin/env bash
# Automatic backup -> restore test, with throwaway containers (nothing of a real installation is touched).
# It builds the ops image and, against an empty PostgreSQL 17 seeded with sample data and sample files, proves:
#   1. backup.sh makes a compressed, verified dump + a files snapshot + the marker the monitor reads
#   2. restore.sh verify restores it into a temporary database and validates it; the source is untouched
#   3. an encrypted backup round-trips, cannot be read without the passphrase, and carries the .env secrets
#   4. a damaged dump is REJECTED (checksum), and so is a truncated one
#   5. the off-site copy lands (rsync target on another volume) and matches
#   6. restore.sh apply replaces a damaged database with the backup (the previous one is kept) + files come back
#   7. retention keeps daily/weekly/monthly as documented
# Usage: scripts/backup-restore-test.sh        (PREFIX=name to choose the container/volume prefix; default dcbrtest)
# Everything it creates is removed at exit. Exit status 0 = all steps passed.
set -euo pipefail
cd "$(dirname "$0")/.."

P="${PREFIX:-dcbrtest}"
IMG="$P-ops:test"
NET="$P-net"; PG="$P-pg"
VOLS=("$P-backups" "$P-data" "$P-offsite")
PGPASS="test-only-$$"
say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[1;32mOK\033[0m %s\n' "$*"; }
die() { printf '   \033[1;31mFALLA\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  docker rm -f -v "$PG" >/dev/null 2>&1 || true
  for v in "${VOLS[@]}"; do docker volume rm -f "$v" >/dev/null 2>&1 || true; done
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker rmi -f "$IMG" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

say "Imagen ops y PostgreSQL desechable"
docker build -q -f docker/ops.Dockerfile -t "$IMG" . >/dev/null
docker network create "$NET" >/dev/null
for v in "${VOLS[@]}"; do docker volume create "$v" >/dev/null; done
docker run -d --name "$PG" --network "$NET" -e POSTGRES_DB=dallycontrol -e POSTGRES_USER=dallycontrol \
  -e POSTGRES_PASSWORD="$PGPASS" postgres:17 >/dev/null
for _ in $(seq 1 60); do docker exec "$PG" psql -h 127.0.0.1 -U dallycontrol -d dallycontrol -Atc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
q() { docker exec -i "$PG" psql -X -At -v ON_ERROR_STOP=1 -U dallycontrol -d "${2:-dallycontrol}" -c "$1"; }

q "CREATE TABLE databasechangelog (id text, author text, filename text);
   INSERT INTO databasechangelog SELECT 'cs-' || g, 'test', 'db.changelog.xml' FROM generate_series(1, 40) g;
   CREATE TABLE customers (id serial PRIMARY KEY, name text);
   INSERT INTO customers (name) VALUES ('Cliente de prueba');
   CREATE TABLE devices (id serial PRIMARY KEY, number text UNIQUE, description text, lastupdate bigint, customerid int REFERENCES customers(id));
   INSERT INTO devices (number, description, lastupdate, customerid) SELECT 'DEV-' || g, 'Equipo ' || g || ' ñandú', g, 1 FROM generate_series(1, 5000) g;
   CREATE TABLE users (id serial PRIMARY KEY, login text); INSERT INTO users (login) VALUES ('admin'), ('operador');
   CREATE TABLE configurations (id serial PRIMARY KEY, name text); INSERT INTO configurations (name) VALUES ('Predeterminada');
   CREATE TABLE applications (id serial PRIMARY KEY, pkg text); INSERT INTO applications (pkg) SELECT 'com.app' || g FROM generate_series(1, 30) g;
   CREATE TABLE groups (id serial PRIMARY KEY, name text); INSERT INTO groups (name) VALUES ('General');" >/dev/null
SUM_BEFORE=$(q "SELECT md5(string_agg(number || description, ',' ORDER BY id)) FROM devices")

# The ops container: backups volume, data volume (files), an "off-site" volume, and a fake project dir with a .env.
PROJ=$(mktemp -d); printf 'HASH_SECRET=secreto-de-prueba\nDB_PASSWORD=x\n' > "$PROJ/.env"
trap 'cleanup; rm -rf "$PROJ"' EXIT
ops() {
  docker run --rm --network "$NET" --user 0:0 -e PGHOST="$PG" -e PGUSER=dallycontrol -e PGPASSWORD="$PGPASS" \
    -e PGDATABASE=dallycontrol -e TZ=America/Bogota -v "$P-backups:/backups" -v "$P-data:/data" -v "$P-offsite:/offsite" \
    -v "$PROJ:/project:ro" "$@"
}
ops "$IMG" sh -c 'mkdir -p /data/files/apps && head -c 3000000 /dev/urandom > /data/files/apps/app-1.apk && echo icono > /data/files/icon.png && echo clave > /data/jwt.secret && sha256sum /data/files/apps/app-1.apk | cut -d" " -f1 > /backups/apk.sha'

say "1. Respaldo (volcado comprimido y verificado + archivos + marca para el monitor)"
T0=$(date +%s)
ops -e BACKUP_VERIFY_RESTORE=never "$IMG" /ops/backup.sh
ops "$IMG" sh -c 'ls /backups/scheduled/db/*.dump /backups/scheduled/db/*.sha256 /backups/scheduled/db/*.manifest.json >/dev/null && test -f /backups/scheduled/files/*/apps/app-1.apk && grep -q "\"remoteOk\":null" /backups/scheduled/last_success.json' \
  || die "faltan piezas del respaldo"
ok "respaldo completo en $(( $(date +%s) - T0 )) s"

say "2. Restauración de prueba en una base temporal + validación"
OUT=$(ops "$IMG" /ops/restore.sh verify latest); printf '%s\n' "$OUT"
grep -q 'RESTAURACIÓN DE PRUEBA CORRECTA' <<< "$OUT" || die "la restauración de prueba no pasó"
grep -q 'devices=5000' <<< "$OUT" || die "no se validaron las 5000 filas de devices"
[ "$(q "SELECT count(*) FROM pg_database WHERE datname LIKE 'dc_restore_verify%'" postgres)" = 0 ] || die "quedó una base temporal"
[ "$(q "SELECT md5(string_agg(number || description, ',' ORDER BY id)) FROM devices")" = "$SUM_BEFORE" ] || die "la base de origen cambió"
ok "restaurado y validado; base temporal eliminada; origen intacto"

say "3. Respaldo cifrado: ida y vuelta, ilegible sin la frase, incluye .env"
sleep 1
ops -e BACKUP_ENCRYPTION_PASSPHRASE='frase de prueba 123' -e BACKUP_VERIFY_RESTORE=always "$IMG" /ops/backup.sh | tee /dev/stderr | grep -q 'restauración de prueba: correcta' \
  || die "el respaldo cifrado no se pudo restaurar"
ops "$IMG" sh -c 'f=$(ls /backups/scheduled/db/*.dump.enc | tail -1); ! pg_restore --list "$f" >/dev/null 2>&1' || die "el volcado cifrado se puede leer sin la frase"
if ops "$IMG" /ops/restore.sh verify latest >/dev/null 2>&1; then die "se restauró un respaldo cifrado sin frase"; fi
if ops -e BACKUP_ENCRYPTION_PASSPHRASE='otra frase' "$IMG" /ops/restore.sh verify latest >/dev/null 2>&1; then die "se restauró con una frase incorrecta"; fi
ops -e BACKUP_ENCRYPTION_PASSPHRASE='frase de prueba 123' "$IMG" sh -c 'f=$(ls /backups/scheduled/config/*.enc | tail -1); openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in "$f" | tar -xzO ./env | grep -q HASH_SECRET=secreto-de-prueba' \
  || die "el archivo de configuración cifrado no contiene .env"
ok "cifrado correcto; sin la frase (o con otra) no se puede leer ni restaurar"

say "4. Un respaldo dañado o truncado se rechaza"
ops "$IMG" sh -c 'cd /backups/scheduled/db && f=$(ls *.dump | head -1) && mkdir -p /backups/bad/db && cp "$f" "$f.sha256" /backups/bad/db/ && printf X | dd of="/backups/bad/db/$f" bs=1 seek=2000 conv=notrunc 2>/dev/null'
if ops -e BACKUP_DIR=/backups/bad "$IMG" /ops/restore.sh verify latest 2>&1 | tee /dev/stderr | grep -q 'CORRECTA'; then die "se aceptó un respaldo dañado"; fi
ops "$IMG" sh -c 'cd /backups/bad/db && f=$(ls *.dump | head -1) && rm -f "$f.sha256" && head -c 20000 /backups/scheduled/db/"$f" > "$f"'
if ops -e BACKUP_DIR=/backups/bad "$IMG" /ops/restore.sh verify latest 2>&1 | grep -q 'CORRECTA'; then die "se aceptó un respaldo truncado"; fi
ok "dañado (sha256) y truncado: rechazados"

say "5. Copia fuera del servidor (destino rsync en otro volumen) comprobada"
sleep 1
ops -e BACKUP_RSYNC_TARGET=/offsite -e BACKUP_VERIFY_RESTORE=never -e BACKUP_REMOTE_REQUIRED=1 "$IMG" /ops/backup.sh >/dev/null
ops "$IMG" sh -c 'grep -q "\"remoteOk\":true" /backups/scheduled/last_success.json && cd /backups/scheduled/db && for f in *.dump *.dump.enc; do cmp -s "$f" "/offsite/db/$f" || exit 1; done && [ "$(sha256sum /offsite/files/*/apps/app-1.apk | tail -1 | cut -d" " -f1)" = "$(cat /backups/apk.sha)" ]' \
  || die "la copia externa no coincide"
if ops -e BACKUP_RSYNC_TARGET=/no-existe/x -e BACKUP_VERIFY_RESTORE=never -e BACKUP_REMOTE_REQUIRED=1 "$IMG" /ops/backup.sh >/dev/null 2>&1; then die "un destino externo roto no hizo fallar el respaldo"; fi
ops "$IMG" grep -q '"remoteOk":false' /backups/scheduled/last_success.json || die "la falla del destino externo no quedó registrada"
ok "copia externa idéntica; un destino roto se registra (remoteOk=false) y hace fallar el respaldo"

say "6. Desastre: se dañan la base y los archivos, se restaura de verdad"
# Restore the plain (unencrypted) dump taken in step 5, the newest one.
q "DELETE FROM devices WHERE id > 10; DROP TABLE applications;" >/dev/null
ops "$IMG" sh -c 'rm -rf /data/files/apps'
T0=$(date +%s)
OUT=$(ops "$IMG" sh -c '/ops/restore.sh apply "$(ls /backups/scheduled/db/*.dump | tail -1)" --yes && mkdir -p /data/files && /ops/restore.sh files latest /data/files'); printf '%s\n' "$OUT"
RTO=$(( $(date +%s) - T0 ))
[ "$(q "SELECT count(*) FROM devices")" = 5000 ] || die "devices no volvió a 5000 filas"
[ "$(q "SELECT count(*) FROM applications")" = 30 ] || die "applications no se restauró"
[ "$(q "SELECT md5(string_agg(number || description, ',' ORDER BY id)) FROM devices")" = "$SUM_BEFORE" ] || die "el contenido restaurado difiere del original"
[ "$(q "SELECT count(*) FROM pg_database WHERE datname LIKE 'dallycontrol_before_%'" postgres)" = 1 ] || die "no se conservó la base anterior"
ops "$IMG" sh -c '[ "$(sha256sum /data/files/apps/app-1.apk | cut -d" " -f1)" = "$(cat /backups/apk.sha)" ]' || die "el APK restaurado difiere"
ok "base y archivos restaurados e idénticos al original en ${RTO} s; la base dañada quedó guardada como dallycontrol_before_*"

say "7. Retención (30 diarios + 8 semanales + 12 mensuales)"
KEPT=$(ops "$IMG" bash -c 'for i in $(seq 0 399); do date -d "2026-11-27 -$i days" +dallycontrol-%Y%m%d-023000.dump; done > /tmp/all; /ops/backup.sh --prune-test < /tmp/all > /tmp/del; grep -vxFf /tmp/del /tmp/all | sort -r')
N=$(printf '%s\n' "$KEPT" | wc -l | tr -d ' ')
printf '%s\n' "$KEPT" | head -1 | grep -q 20261127 || die "no se conserva el más reciente"
printf '%s\n' "$KEPT" | sed -n 30p | grep -q 20261029 || die "no se conservan 30 diarios seguidos"
# 30 daily + older weekly + older monthly, nothing beyond 12 months.
{ [ "$N" -ge 40 ] && [ "$N" -le 50 ]; } || die "se conservan $N respaldos de 400 (se esperaban entre 40 y 50)"
printf '%s\n' "$KEPT" | tail -1 | grep -qE '2025(12|11)' || die "el más antiguo conservado no es de hace ~12 meses: $(printf '%s\n' "$KEPT" | tail -1)"
ok "de 400 respaldos diarios se conservan $N: 30 diarios, luego uno por semana (8) y uno por mes (12)"

printf '\n\033[1;32mPRUEBA DE RESPALDO Y RESTAURACIÓN: TODO CORRECTO\033[0m (restauración real en %s s)\n' "$RTO"
