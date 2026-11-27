#!/usr/bin/env bash
# DallyControl restore. Runs inside the ops image; scripts/restore-backup.sh is the host-side entry point.
#
#   /ops/restore.sh list
#   /ops/restore.sh verify <dump|latest>
#       Restores the dump into a TEMPORARY database on the same PostgreSQL, validates it (checksum, every table of
#       the archive present, row counts against the manifest written at backup time, Liquibase history) and drops
#       it. Touches nothing else. This is the restore test.
#   /ops/restore.sh apply <dump|latest> --yes
#       Restores into the live database name: restores into a new database, validates it, then swaps names. The
#       previous database is KEPT as <db>_before_<stamp> (the way back). The server must be stopped (the wrapper
#       does that).
#   /ops/restore.sh files <snapshot|latest> <target-dir>
#       Copies a files snapshot (APKs, icons, configuration files) back into <target-dir>; existing files that are
#       not in the snapshot are left alone.
#
# Environment: PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD, BACKUP_DIR, BACKUP_ENCRYPTION_PASSPHRASE (for .enc dumps).
set -euo pipefail
umask 077
BACKUP_DIR="${BACKUP_DIR:-/backups/scheduled}"
DB="${PGDATABASE:-dallycontrol}"
log()  { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
fail() { log "ERROR: $*" >&2; exit 1; }
sql()  { psql -X -At -v ON_ERROR_STOP=1 -d "${2:-postgres}" -c "$1"; }

resolve_dump() {
  local d="$1"
  if [ "$d" = latest ]; then
    d=$(find "$BACKUP_DIR/db" -maxdepth 1 -type f \( -name '*.dump' -o -name '*.dump.enc' \) | sort | tail -1)
    [ -n "$d" ] || fail "no hay respaldos en $BACKUP_DIR/db"
  elif [ ! -f "$d" ] && [ -f "$BACKUP_DIR/db/$d" ]; then
    d="$BACKUP_DIR/db/$d"
  fi
  [ -f "$d" ] || fail "no existe el respaldo $d"
  printf '%s\n' "$d"
}

PLAIN=""
TEMP_DB=""
cleanup() {
  [ -z "$PLAIN" ] || rm -f "$PLAIN"
  [ -z "$TEMP_DB" ] || psql -X -q -d postgres -c "DROP DATABASE IF EXISTS \"$TEMP_DB\" WITH (FORCE)" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Checks the checksum and leaves a readable archive path in ARCHIVE (decrypting to a temporary file when needed).
prepare() {
  local dump="$1"
  if [ -f "$dump.sha256" ]; then
    local want got
    want=$(awk '{print $1}' "$dump.sha256"); got=$(sha256sum "$dump" | awk '{print $1}')
    [ "$want" = "$got" ] || fail "la suma sha256 de $(basename "$dump") no coincide: el archivo está dañado"
    log "sha256 correcto"
  else
    log "AVISO: no hay archivo .sha256 junto al respaldo; no se puede comprobar su integridad"
  fi
  case "$dump" in
    *.enc)
      [ -n "${BACKUP_ENCRYPTION_PASSPHRASE:-}" ] || fail "el respaldo está cifrado: defina BACKUP_ENCRYPTION_PASSPHRASE"
      PLAIN=$(mktemp "${TMPDIR:-/tmp}/dc-restore.XXXXXX")
      openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in "$dump" -out "$PLAIN" \
        || fail "no se pudo descifrar (¿frase de cifrado incorrecta?)"
      ARCHIVE="$PLAIN" ;;
    *) ARCHIVE="$dump" ;;
  esac
  pg_restore --list "$ARCHIVE" >/dev/null || fail "el archivo no es un respaldo legible"
}

# Restores ARCHIVE into database $1 (created here) and validates it against the archive and the manifest of $2.
restore_and_validate() {
  local target="$1" dump="$2"
  sql "CREATE DATABASE \"$target\"" >/dev/null
  local t0; t0=$(date +%s)
  pg_restore --no-owner --no-privileges --exit-on-error --jobs="${RESTORE_JOBS:-2}" -d "$target" "$ARCHIVE" \
    || fail "pg_restore falló"
  RESTORE_SECONDS=$(( $(date +%s) - t0 ))

  local want_tables got_tables
  want_tables=$(pg_restore --list "$ARCHIVE" | grep -c ' TABLE [^D]' || true)
  got_tables=$(sql "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema') AND table_type='BASE TABLE'" "$target")
  [ "$got_tables" -ge "$want_tables" ] && [ "$got_tables" -gt 0 ] || fail "se restauraron $got_tables tablas y el respaldo declara $want_tables"

  local changes
  changes=$(sql "SELECT count(*) FROM databasechangelog" "$target" 2>/dev/null || echo 0)
  [ "$changes" -gt 0 ] || fail "la base restaurada no tiene historial de migraciones (databasechangelog): no es una base de DallyControl"

  local manifest="${dump%.enc}"; manifest="${manifest%.dump}.manifest.json"
  local summary="tablas=$got_tables migraciones=$changes"
  if [ -f "$manifest" ]; then
    local t want got
    for t in databasechangelog devices users configurations applications groups; do
      want=$(grep -oE "\"$t\":-?[0-9]+" "$manifest" | cut -d: -f2 || true)
      [ -n "$want" ] && [ "$want" -ge 0 ] || continue
      got=$(sql "SELECT count(*) FROM $t" "$target")
      summary="$summary $t=$got"
      # The manifest is counted just before the dump on a live database, so a row or two of difference is normal;
      # an empty table where there was data, or a different migration history, is not.
      if [ "$t" = databasechangelog ] && [ "$got" != "$want" ]; then fail "databasechangelog: $got filas restauradas, $want esperadas"; fi
      if [ "$want" -gt 0 ] && [ "$got" -eq 0 ]; then fail "$t: restaurada vacía y tenía $want filas"; fi
      if [ "$want" -gt 20 ] && { [ "$got" -lt $(( want * 95 / 100 )) ] || [ "$got" -gt $(( want * 105 / 100 )) ]; }; then
        fail "$t: $got filas restauradas, $want esperadas"
      fi
    done
  else
    log "AVISO: no hay manifiesto junto al respaldo; solo se validan tablas y migraciones"
  fi
  VALIDATION_SUMMARY="$summary"
}

cmd="${1:-}"
case "$cmd" in
  list)
    echo "Respaldos de base de datos en $BACKUP_DIR/db:"
    find "$BACKUP_DIR/db" -maxdepth 1 -type f \( -name '*.dump' -o -name '*.dump.enc' \) -printf '  %f  %s bytes\n' 2>/dev/null | sort
    echo "Instantáneas de archivos en $BACKUP_DIR/files:"
    find "$BACKUP_DIR/files" -mindepth 1 -maxdepth 1 -type d -printf '  %f\n' 2>/dev/null | sort
    ;;

  verify)
    DUMP=$(resolve_dump "${2:?uso: restore.sh verify <respaldo|latest>}")
    T0=$(date +%s)
    log "restauración de prueba de $(basename "$DUMP") en una base temporal"
    prepare "$DUMP"
    TEMP_DB="dc_restore_verify_$(date +%s)_$$"
    restore_and_validate "$TEMP_DB" "$DUMP"
    sql "DROP DATABASE \"$TEMP_DB\" WITH (FORCE)" >/dev/null
    TEMP_DB=""
    log "RESTAURACIÓN DE PRUEBA CORRECTA: $VALIDATION_SUMMARY (pg_restore ${RESTORE_SECONDS} s, total $(( $(date +%s) - T0 )) s)"
    ;;

  apply)
    DUMP=$(resolve_dump "${2:?uso: restore.sh apply <respaldo|latest> --yes}")
    [ "${3:-}" = "--yes" ] || fail "esto reemplaza la base de datos '$DB'. Repita con --yes para confirmar."
    T0=$(date +%s)
    STAMP=$(date +%Y%m%d_%H%M%S)
    NEW="${DB}_restore_${STAMP}"
    OLD="${DB}_before_${STAMP}"
    log "restauración de $(basename "$DUMP") sobre la base '$DB' (la actual se conserva como '$OLD')"
    prepare "$DUMP"
    TEMP_DB="$NEW"
    restore_and_validate "$NEW" "$DUMP"
    TEMP_DB=""
    # Swap: nobody may be connected to either database while it is renamed.
    sql "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ('$DB', '$NEW') AND pid <> pg_backend_pid()" >/dev/null
    if [ -n "$(sql "SELECT 1 FROM pg_database WHERE datname = '$DB'")" ]; then
      sql "ALTER DATABASE \"$DB\" RENAME TO \"$OLD\"" >/dev/null || fail "no se pudo apartar la base actual (¿sigue conectado el servidor?). La restaurada quedó en '$NEW'."
    else
      OLD=""
    fi
    sql "ALTER DATABASE \"$NEW\" RENAME TO \"$DB\"" >/dev/null || fail "no se pudo activar la base restaurada; la anterior está en '$OLD'"
    log "RESTAURACIÓN APLICADA: $VALIDATION_SUMMARY (pg_restore ${RESTORE_SECONDS} s, total $(( $(date +%s) - T0 )) s)"
    if [ -n "$OLD" ]; then
      log "la base anterior quedó como '$OLD'. Para volver atrás: detenga el servidor e intercambie los nombres."
      log "cuando confirme que todo está bien, libere el espacio: DROP DATABASE \"$OLD\";"
    fi
    ;;

  files)
    SNAP="${2:?uso: restore.sh files <instantánea|latest> <directorio-destino>}"
    TARGET="${3:?uso: restore.sh files <instantánea|latest> <directorio-destino>}"
    if [ "$SNAP" = latest ]; then
      SNAP=$(find "$BACKUP_DIR/files" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*' | sort | tail -1)
    elif [ ! -d "$SNAP" ]; then
      SNAP="$BACKUP_DIR/files/$SNAP"
    fi
    [ -d "$SNAP" ] || fail "no existe la instantánea de archivos"
    [ -d "$TARGET" ] || fail "no existe el directorio destino $TARGET"
    T0=$(date +%s)
    rsync -a --no-owner --no-group "$SNAP/" "$TARGET/" || fail "no se pudieron copiar los archivos"
    # Same owner as the directory they go into (the server's user), whatever user the backup ran as.
    chown -R --reference="$TARGET" "$TARGET" 2>/dev/null || true
    log "ARCHIVOS RESTAURADOS: $(find "$SNAP" -type f | wc -l) archivos de $(basename "$SNAP") en $TARGET ($(( $(date +%s) - T0 )) s)"
    ;;

  *)
    sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
