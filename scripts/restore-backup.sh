#!/usr/bin/env bash
# Restore a DallyControl backup (made by the `backup` service, ops/backup.sh). Run on the server, from the project
# directory. Documentation: docs/ops/RESPALDOS.md and docs/ops/RUNBOOK.md.
#
#   scripts/restore-backup.sh list
#   scripts/restore-backup.sh verify [<dump>|latest]
#       Restore test: restores into a TEMPORARY database, validates and drops it. Safe at any time; the service is
#       not touched.
#   scripts/restore-backup.sh apply [<dump>|latest] [--with-files [<snapshot>|latest]] --yes
#       Real restore: stops the server, restores the database (the current one is kept as <db>_before_<stamp>),
#       optionally restores the files, starts the server and waits until it is healthy.
#   scripts/restore-backup.sh files [<snapshot>|latest] --yes
#       Only the uploaded files (APKs, icons, configuration files).
#
# A dump outside the backups volume: BACKUP_HOST_DIR=/path/with/the/dump scripts/restore-backup.sh verify <file>
# Encrypted dumps need BACKUP_ENCRYPTION_PASSPHRASE (in .env or the environment).
set -euo pipefail
cd "$(dirname "$0")/.."

say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; }

[ -f .env ] || { err "No hay .env en $(pwd): ejecute desde el directorio de la instalación."; exit 1; }
dc() { docker compose --profile backup --profile relay "$@"; }
# The ops container, with the data volume writable when files are restored, and an extra host directory when the dump
# is not in the backups volume.
ops() {
  local extra=()
  if [ -n "${BACKUP_HOST_DIR:-}" ]; then extra+=(-v "$(cd "$BACKUP_HOST_DIR" && pwd):/restore-src:ro" -e BACKUP_DIR=/restore-src); fi
  if [ "${OPS_DATA_RW:-0}" = 1 ]; then extra+=(-v "${COMPOSE_PROJECT_NAME_RESOLVED}_dallycontroldata:/restore-data"); fi
  dc run --rm --no-deps -T "${extra[@]}" backup "$@"
}
COMPOSE_PROJECT_NAME_RESOLVED=$(dc config --format json 2>/dev/null | sed -n 's/^{*"name":"\([^"]*\)".*/\1/p' | head -1)
[ -n "$COMPOSE_PROJECT_NAME_RESOLVED" ] || COMPOSE_PROJECT_NAME_RESOLVED=$(dc config 2>/dev/null | awk '/^name:/{print $2; exit}')

cmd="${1:-}"; [ $# -gt 0 ] && shift || true
case "$cmd" in
  list)
    ops /ops/restore.sh list ;;

  verify)
    ops /ops/restore.sh verify "${1:-latest}" ;;

  files)
    SNAP=latest; YES=0
    for a in "$@"; do case "$a" in --yes) YES=1 ;; *) SNAP="$a" ;; esac; done
    [ "$YES" = 1 ] || { err "Esto copia los archivos del respaldo sobre los actuales. Repita con --yes."; exit 2; }
    OPS_DATA_RW=1 ops /ops/restore.sh files "$SNAP" /restore-data/files ;;

  apply)
    DUMP=latest; YES=0; WITH_FILES=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --yes) YES=1 ;;
        --with-files) WITH_FILES=latest; if [ $# -gt 1 ] && [ "${2#--}" = "$2" ]; then WITH_FILES="$2"; shift; fi ;;
        *) DUMP="$1" ;;
      esac
      shift
    done
    [ "$YES" = 1 ] || { err "Esto REEMPLAZA la base de datos en uso y detiene el servicio mientras dura. Repita con --yes."; exit 2; }
    T0=$(date +%s)
    say "1/4 Deteniendo el servidor (el borde sigue arriba y muestra la página de recuperación)…"
    dc stop server server-b >/dev/null 2>&1 || dc stop server
    say "2/4 Restaurando la base de datos…"
    if ! ops /ops/restore.sh apply "$DUMP" --yes; then
      err "La restauración falló; la base de datos anterior no se modificó. Arrancando el servidor de nuevo."
      dc start server; exit 1
    fi
    if [ -n "$WITH_FILES" ]; then
      say "3/4 Restaurando los archivos…"
      OPS_DATA_RW=1 ops /ops/restore.sh files "$WITH_FILES" /restore-data/files
    else
      say "3/4 Archivos: sin cambios (use --with-files para restaurarlos)."
    fi
    say "4/4 Arrancando el servidor…"
    dc start server
    for _ in $(seq 1 90); do
      st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$(dc ps -q server)" 2>/dev/null || echo unknown)
      [ "$st" = healthy ] && break
      sleep 2
    done
    if [ "$st" = healthy ]; then say "Servicio restaurado y saludable en $(( $(date +%s) - T0 )) s."
    else err "El servidor no quedó saludable (estado: $st). Revise: docker compose logs --tail 80 server"; exit 1; fi ;;

  -h|--help|"") usage ;;
  *) err "Orden desconocida: $cmd"; usage; exit 2 ;;
esac
