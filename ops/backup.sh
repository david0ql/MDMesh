#!/usr/bin/env bash
# DallyControl backup: compressed + verified pg_dump, snapshot of the uploaded files (APKs), retention, optional
# encryption and off-site copy. Runs inside the ops image (compose service `backup`); documentation:
# docs/ops/RESPALDOS.md.
#
#   /ops/backup.sh                 one backup now (what the scheduler calls every day)
#   /ops/backup.sh --prune-test    reads backup names on stdin, prints the ones retention would delete (tests)
#
# Layout under $BACKUP_DIR (default /backups/scheduled):
#   db/<db>-YYYYmmdd-HHMMSS.dump[.enc]   pg_dump custom format (compressed), + .sha256 and .manifest.json
#   config/config-YYYYmmdd-HHMMSS.tar.gz.enc   .env + jwt.secret, ONLY when encryption is on (they are secrets)
#   files/YYYYmmdd-HHMMSS/               snapshot of the files directory; unchanged files are hard links to the
#                                        previous snapshot, so 30 snapshots cost about one copy plus the changes
#   last_success.json                    what the monitor reads (age of the last good backup)
#
# Retention (one rule for dumps and snapshots): the newest BACKUP_KEEP_DAILY, plus the newest of each of the last
# BACKUP_KEEP_WEEKLY ISO weeks, plus the newest of each of the last BACKUP_KEEP_MONTHLY months.
#
# Environment: PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD (libpq), BACKUP_DIR, BACKUP_FILES_DIR, BACKUP_FILES (1/0),
# BACKUP_KEEP_DAILY/WEEKLY/MONTHLY, BACKUP_ENCRYPTION_PASSPHRASE, BACKUP_VERIFY_RESTORE (always|weekly|never),
# off-site: BACKUP_S3_* | BACKUP_RCLONE_REMOTE (+ /config/rclone.conf) | BACKUP_RSYNC_TARGET, BACKUP_REMOTE_REQUIRED.
set -euo pipefail
umask 077

BACKUP_DIR="${BACKUP_DIR:-/backups/scheduled}"
BACKUP_FILES_DIR="${BACKUP_FILES_DIR:-/data/files}"
BACKUP_FILES="${BACKUP_FILES:-1}"
KEEP_DAILY="${BACKUP_KEEP_DAILY:-30}"
KEEP_WEEKLY="${BACKUP_KEEP_WEEKLY:-8}"
KEEP_MONTHLY="${BACKUP_KEEP_MONTHLY:-12}"
VERIFY_RESTORE="${BACKUP_VERIFY_RESTORE:-weekly}"
PASSPHRASE="${BACKUP_ENCRYPTION_PASSPHRASE:-}"
ENV_FILE="${BACKUP_ENV_FILE:-/project/.env}"
DATA_ROOT="${BACKUP_DATA_ROOT:-/data}"
CONFIG_DIR="${BACKUP_CONFIG_DIR_IN:-/config}"
DB="${PGDATABASE:-dallycontrol}"

log()  { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
fail() { log "ERROR: $*" >&2; exit 1; }

# Prints, from the backup names on stdin (anything containing YYYYmmdd-HHMMSS), the ones retention deletes.
prune_list() {
  local names name stamp day
  names=$(sort -r)   # newest first
  [ -n "$names" ] || return 0
  local -A keep=() seen_week=() seen_month=()
  local n=0 weeks=0 months=0
  while IFS= read -r name; do
    stamp=$(printf '%s' "$name" | grep -oE '[0-9]{8}-[0-9]{6}' | head -1) || true
    [ -n "$stamp" ] || { keep["$name"]=1; continue; }   # not ours: never delete
    day="${stamp:0:4}-${stamp:4:2}-${stamp:6:2}"
    n=$((n + 1))
    if [ "$n" -le "$KEEP_DAILY" ]; then keep["$name"]=1; fi
    local wk mo
    wk=$(date -d "$day" +%G-%V); mo="${stamp:0:6}"
    if [ -z "${seen_week[$wk]:-}" ]; then
      seen_week[$wk]=1; weeks=$((weeks + 1))
      if [ "$weeks" -le "$KEEP_WEEKLY" ]; then keep["$name"]=1; fi
    fi
    if [ -z "${seen_month[$mo]:-}" ]; then
      seen_month[$mo]=1; months=$((months + 1))
      if [ "$months" -le "$KEEP_MONTHLY" ]; then keep["$name"]=1; fi
    fi
  done <<< "$names"
  while IFS= read -r name; do
    [ -n "${keep[$name]:-}" ] || printf '%s\n' "$name"
  done <<< "$names"
}

if [ "${1:-}" = "--prune-test" ]; then prune_list; exit 0; fi

command -v pg_dump >/dev/null || fail "pg_dump no está disponible"
mkdir -p "$BACKUP_DIR/db" "$BACKUP_DIR/files" "$BACKUP_DIR/config"
chmod 755 "$BACKUP_DIR"   # the monitor (another user) reads last_success.json; dumps and snapshots stay owner-only
chmod 700 "$BACKUP_DIR/db" "$BACKUP_DIR/files" "$BACKUP_DIR/config"

# One backup at a time (a manual run while the scheduled one is working would double the load).
LOCK="$BACKUP_DIR/.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +360 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null || true; mkdir "$LOCK" || fail "no se pudo tomar el candado"
  else fail "ya hay un respaldo en curso ($LOCK)"; fi
fi
TMP=""
cleanup() { [ -z "$TMP" ] || rm -f "$TMP"; rmdir "$LOCK" 2>/dev/null || true; }
trap cleanup EXIT

STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
T0=$(date +%s)
STAMP=$(date +%Y%m%d-%H%M%S)
BASE="$BACKUP_DIR/db/${DB}-${STAMP}"

# ---- 1. database ----------------------------------------------------------------------------------------------------
log "respaldo de la base de datos $DB en ${PGHOST:-localhost}"
TMP="$BASE.dump.partial"
pg_dump --format=custom --compress=6 --no-owner --no-privileges --file="$TMP" "$DB" || fail "pg_dump falló"

# Verified: the archive is readable end to end and holds table data (a truncated or empty dump fails here).
TOC=$(pg_restore --list "$TMP") || fail "el respaldo no se puede leer (pg_restore --list)"
TABLES=$(printf '%s\n' "$TOC" | grep -c ' TABLE DATA ' || true)
[ "$TABLES" -gt 0 ] || fail "el respaldo no contiene datos de tablas"

# Row counts of the tables that matter, to compare after a restore (ops/restore.sh).
count() { psql -X -At -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM $1" "$DB" 2>/dev/null || echo -1; }
MANIFEST="$BASE.manifest.json"
{
  printf '{"database":"%s","createdAt":"%s","pgDumpVersion":"%s","tablesWithData":%s,"rows":{' \
    "$DB" "$STARTED" "$(pg_dump --version | awk '{print $NF}')" "$TABLES"
  first=1
  for t in databasechangelog devices users configurations applications groups; do
    [ "$first" = 1 ] || printf ','
    first=0
    printf '"%s":%s' "$t" "$(count "$t")"
  done
  printf '}}\n'
} > "$MANIFEST"

if [ -n "$PASSPHRASE" ]; then
  DUMP="$BASE.dump.enc"
  openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in "$TMP" -out "$DUMP" \
    || fail "no se pudo cifrar el respaldo"
  rm -f "$TMP"
else
  DUMP="$BASE.dump"
  mv "$TMP" "$DUMP"
fi
TMP=""
SHA=$(sha256sum "$DUMP" | awk '{print $1}')
printf '%s  %s\n' "$SHA" "$(basename "$DUMP")" > "$DUMP.sha256"
BYTES=$(stat -c %s "$DUMP")
log "base de datos: $(basename "$DUMP") ($BYTES bytes, $TABLES tablas con datos, sha256 ${SHA:0:16}…)"

# ---- 2. configuration secrets (.env + JWT key): only encrypted ------------------------------------------------------
if [ -n "$PASSPHRASE" ]; then
  CFG_TMP=$(mktemp -d)
  [ -f "$ENV_FILE" ] && cp "$ENV_FILE" "$CFG_TMP/env" || true
  [ -f "$DATA_ROOT/jwt.secret" ] && cp "$DATA_ROOT/jwt.secret" "$CFG_TMP/jwt.secret" || true
  if [ -n "$(ls -A "$CFG_TMP")" ]; then
    tar -C "$CFG_TMP" -czf - . | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_ENCRYPTION_PASSPHRASE \
      -out "$BACKUP_DIR/config/config-${STAMP}.tar.gz.enc" || fail "no se pudo cifrar la configuración"
    log "configuración (.env, jwt.secret): config-${STAMP}.tar.gz.enc"
  fi
  rm -rf "$CFG_TMP"
else
  log "AVISO: sin BACKUP_ENCRYPTION_PASSPHRASE no se respalda .env (HASH_SECRET, contraseñas). Guarde una copia de .env en un gestor de secretos: sin HASH_SECRET los equipos enrolados no se pueden recuperar."
fi

# ---- 3. files (APKs, icons, configuration files) --------------------------------------------------------------------
SNAP=""
if [ "$BACKUP_FILES" = 1 ] && [ -d "$BACKUP_FILES_DIR" ]; then
  PREV=$(find "$BACKUP_DIR/files" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*' | sort | tail -1)
  SNAP="$BACKUP_DIR/files/$STAMP"
  LINK=()
  [ -z "$PREV" ] || LINK=(--link-dest="$PREV")
  rsync -a --delete "${LINK[@]}" "$BACKUP_FILES_DIR/" "$SNAP.partial/" || fail "no se pudo copiar el directorio de archivos"
  mv "$SNAP.partial" "$SNAP"
  # Verified: same number of files and same total size as the source.
  SRC_N=$(find "$BACKUP_FILES_DIR" -type f | wc -l); DST_N=$(find "$SNAP" -type f | wc -l)
  [ "$SRC_N" -eq "$DST_N" ] || fail "la copia de archivos tiene $DST_N archivos y el origen $SRC_N"
  log "archivos: instantánea $STAMP ($DST_N archivos, $(du -sh "$SNAP" | awk '{print $1}') contando lo compartido)"
elif [ "$BACKUP_FILES" = 1 ]; then
  log "AVISO: $BACKUP_FILES_DIR no existe; no se respaldan archivos"
fi

# ---- 4. retention ---------------------------------------------------------------------------------------------------
( cd "$BACKUP_DIR/db" && ls -1 | grep -E '\.dump(\.enc)?$' | prune_list | while IFS= read -r f; do
    rm -f -- "$f" "$f.sha256" "${f%.enc}" "${f%.dump*}.manifest.json"; log "retención: eliminado $f"; done ) || true
( cd "$BACKUP_DIR/config" && ls -1 | prune_list | while IFS= read -r f; do rm -f -- "$f"; done ) || true
( cd "$BACKUP_DIR/files" && ls -1 | grep -E '^[0-9]{8}-[0-9]{6}$' | prune_list | while IFS= read -r d; do
    rm -rf -- "$d"; log "retención: eliminada la instantánea $d"; done ) || true
rm -rf "$BACKUP_DIR"/files/*.partial 2>/dev/null || true

# ---- 5. restore verification (a backup nobody restored is a hope, not a backup) -------------------------------------
VERIFY="omitida"
case "$VERIFY_RESTORE" in
  always) DO_VERIFY=1 ;;
  weekly) if [ "$(date +%u)" = 7 ] || ! ls "$BACKUP_DIR"/.verified-* >/dev/null 2>&1; then DO_VERIFY=1; else DO_VERIFY=0; fi ;;
  *)      DO_VERIFY=0 ;;
esac
if [ "$DO_VERIFY" = 1 ]; then
  if /ops/restore.sh verify "$DUMP"; then
    VERIFY="correcta"; rm -f "$BACKUP_DIR"/.verified-*; : > "$BACKUP_DIR/.verified-$STAMP"
  else
    fail "la restauración de prueba del respaldo falló: el respaldo NO es fiable"
  fi
fi

# ---- 6. off-site copy -----------------------------------------------------------------------------------------------
REMOTE_OK=null
REMOTE_DESC=""
RCLONE=(rclone --stats-one-line --stats=0 --log-level NOTICE --retries 3)
RDEST=""
if [ -n "${BACKUP_S3_BUCKET:-}" ]; then
  export RCLONE_CONFIG_DCS3_TYPE=s3 RCLONE_CONFIG_DCS3_PROVIDER="${BACKUP_S3_PROVIDER:-Other}"
  export RCLONE_CONFIG_DCS3_ACCESS_KEY_ID="${BACKUP_S3_ACCESS_KEY:-}" RCLONE_CONFIG_DCS3_SECRET_ACCESS_KEY="${BACKUP_S3_SECRET_KEY:-}"
  export RCLONE_CONFIG_DCS3_ENDPOINT="${BACKUP_S3_ENDPOINT:-}" RCLONE_CONFIG_DCS3_REGION="${BACKUP_S3_REGION:-}"
  export RCLONE_CONFIG_DCS3_FORCE_PATH_STYLE="${BACKUP_S3_FORCE_PATH_STYLE:-true}"
  RDEST="dcs3:${BACKUP_S3_BUCKET}/${BACKUP_S3_PREFIX:-dallycontrol}"
  REMOTE_DESC="S3 ${BACKUP_S3_BUCKET}/${BACKUP_S3_PREFIX:-dallycontrol}"
elif [ -n "${BACKUP_RCLONE_REMOTE:-}" ]; then
  [ -f "$CONFIG_DIR/rclone.conf" ] && RCLONE+=(--config "$CONFIG_DIR/rclone.conf")
  RDEST="$BACKUP_RCLONE_REMOTE"
  REMOTE_DESC="rclone ${BACKUP_RCLONE_REMOTE%%:*}"
fi
if [ -n "$RDEST" ]; then
  REMOTE_OK=true
  # sync with a cap on deletions: retention is mirrored, but an emptied local directory can never wipe the remote.
  "${RCLONE[@]}" sync --max-delete 20 "$BACKUP_DIR/db" "$RDEST/db" || REMOTE_OK=false
  "${RCLONE[@]}" sync --max-delete 20 "$BACKUP_DIR/config" "$RDEST/config" || REMOTE_OK=false
  if [ -n "$SNAP" ]; then
    # Files: the current snapshot; what disappears from it is kept under files-deleted/<stamp> on the remote.
    "${RCLONE[@]}" sync --max-delete 500 --backup-dir "$RDEST/files-deleted/$STAMP" "$SNAP" "$RDEST/files" || REMOTE_OK=false
  fi
  # The copy is checked, not assumed: the dump's checksum on the remote must match.
  if [ "$REMOTE_OK" = true ]; then
    "${RCLONE[@]}" check --one-way --include "$(basename "$DUMP")" "$BACKUP_DIR/db" "$RDEST/db" >/dev/null 2>&1 || REMOTE_OK=false
  fi
elif [ -n "${BACKUP_RSYNC_TARGET:-}" ]; then
  REMOTE_OK=true
  REMOTE_DESC="rsync ${BACKUP_RSYNC_TARGET%%:*}"
  RSH=()
  case "$BACKUP_RSYNC_TARGET" in
    *:*) RSH=(-e "ssh -i $CONFIG_DIR/id_ed25519 -o UserKnownHostsFile=$CONFIG_DIR/known_hosts -o StrictHostKeyChecking=yes -o BatchMode=yes") ;;
  esac
  rsync -a --delete --max-delete=20 "${RSH[@]}" "$BACKUP_DIR/db" "$BACKUP_DIR/config" "$BACKUP_RSYNC_TARGET/" || REMOTE_OK=false
  if [ -n "$SNAP" ]; then
    rsync -aH --delete "${RSH[@]}" "$BACKUP_DIR/files" "$BACKUP_RSYNC_TARGET/" || REMOTE_OK=false
  fi
fi
case "$REMOTE_OK" in
  true)  log "copia fuera del servidor: correcta ($REMOTE_DESC)" ;;
  false) log "ERROR: la copia fuera del servidor falló ($REMOTE_DESC)" >&2 ;;
  *)     log "AVISO: no hay destino fuera del servidor configurado; el respaldo solo existe en este servidor" ;;
esac

# ---- 7. done --------------------------------------------------------------------------------------------------------
FINISHED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '{"startedAt":"%s","finishedAt":"%s","seconds":%s,"dump":"%s","bytes":%s,"sha256":"%s","encrypted":%s,"filesSnapshot":"%s","restoreVerification":"%s","remoteOk":%s}\n' \
  "$STARTED" "$FINISHED" "$(( $(date +%s) - T0 ))" "$(basename "$DUMP")" "$BYTES" "$SHA" \
  "$([ -n "$PASSPHRASE" ] && echo true || echo false)" "${SNAP##*/}" "$VERIFY" "$REMOTE_OK" > "$BACKUP_DIR/last_success.json.tmp"
chmod 644 "$BACKUP_DIR/last_success.json.tmp"
mv "$BACKUP_DIR/last_success.json.tmp" "$BACKUP_DIR/last_success.json"
log "respaldo terminado en $(( $(date +%s) - T0 )) s (restauración de prueba: $VERIFY)"
if [ "$REMOTE_OK" = false ] && [ "${BACKUP_REMOTE_REQUIRED:-0}" = 1 ]; then exit 1; fi
