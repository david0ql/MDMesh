#!/usr/bin/env bash
# Runs /ops/backup.sh every day at BACKUP_TIME (HH:MM, container time zone TZ). The compose service `backup` runs this.
# A failed backup is retried after 30 minutes (up to 3 times); the monitor alerts when the last good backup is too old.
set -uo pipefail
BACKUP_TIME="${BACKUP_TIME:-02:30}"
[[ "$BACKUP_TIME" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || { echo "BACKUP_TIME debe ser HH:MM (es '$BACKUP_TIME')" >&2; exit 1; }
log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
trap 'exit 0' TERM INT

log "respaldos programados cada día a las $BACKUP_TIME ($(date +%Z)); manual: docker compose run --rm backup /ops/backup.sh"
while true; do
  now=$(date +%s)
  next=$(date -d "today $BACKUP_TIME" +%s)
  [ "$next" -gt "$now" ] || next=$(date -d "tomorrow $BACKUP_TIME" +%s)
  log "próximo respaldo: $(date -d "@$next" '+%Y-%m-%d %H:%M')"
  sleep $(( next - now )) & wait $!
  for attempt in 1 2 3; do
    if /ops/backup.sh; then break; fi
    log "el respaldo falló (intento $attempt de 3)"
    [ "$attempt" = 3 ] || { sleep 1800 & wait $!; }
  done
done
