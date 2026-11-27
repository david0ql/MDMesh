#!/usr/bin/env bash
# Measures the real cut of a deployment (or of any maintenance): probes a URL several times per second and reports
# every window in which it did not answer 200, the longest one and the total.
#
#   scripts/measure-downtime.sh <url> [seconds|until-file]
#     scripts/measure-downtime.sh https://mdm.example.com/healthz/deep 300 &     # 5 minutes
#     scripts/measure-downtime.sh http://localhost:8107/healthz/deep /tmp/stop   # until /tmp/stop exists
#
# Environment: INTERVAL=0.25 (seconds between probes), TIMEOUT=3 (per probe), EXPECT=200,
#   MATCH=<text the body must contain> (a 200 from the recovery/maintenance page is then a failure, as it should).
# A probe that is still waiting counts as down from the moment it was sent.
set -uo pipefail
URL="${1:?usage: measure-downtime.sh <url> [seconds|until-file]}"
LIMIT="${2:-60}"
INTERVAL="${INTERVAL:-0.25}"; TIMEOUT="${TIMEOUT:-3}"; EXPECT="${EXPECT:-200}"; MATCH="${MATCH:-}"
BODY=$(mktemp); trap 'rm -f "$BODY"' EXIT
now_ms() { python3 -c 'import time; print(int(time.time()*1000))' 2>/dev/null || echo $(( $(date +%s) * 1000 )); }

START=$(now_ms); END=0
case "$LIMIT" in ''|*[!0-9]*) STOP_FILE="$LIMIT" ;; *) STOP_FILE=""; END=$(( START + LIMIT * 1000 )) ;; esac
total=0; fails=0; down_since=0; down_at=""; down_ms=0; longest=0; windows=0; slow=0; codes=""
while :; do
  t=$(now_ms)
  if [ -n "$STOP_FILE" ]; then [ -e "$STOP_FILE" ] && break; else [ "$t" -ge "$END" ] && break; fi
  code=$(curl -s -o "$BODY" -m "$TIMEOUT" -w '%{http_code}' "$URL" 2>/dev/null); code=${code:-000}
  if [ "$code" = "$EXPECT" ] && [ -n "$MATCH" ] && ! grep -qF -- "$MATCH" "$BODY"; then code="otra-pagina"; fi
  t2=$(now_ms)
  total=$((total + 1))
  [ $((t2 - t)) -lt 1000 ] || slow=$((slow + 1))
  if [ "$code" = "$EXPECT" ]; then
    if [ "$down_since" != 0 ]; then
      w=$((t - down_since)); down_ms=$((down_ms + w)); windows=$((windows + 1)); [ "$w" -gt "$longest" ] && longest=$w
      printf '  %s corte de %d.%03d s (respuestas: %s)\n' "$down_at" $((w / 1000)) $((w % 1000)) "$codes"
      down_since=0; codes=""
    fi
  else
    fails=$((fails + 1))
    [ "$down_since" != 0 ] || { down_since=$t; down_at=$(date +%H:%M:%S); }
    case " $codes " in *" $code "*) ;; *) codes="${codes:+$codes }$code" ;; esac
  fi
  sleep "$INTERVAL"
done
if [ "$down_since" != 0 ]; then
  w=$(( $(now_ms) - down_since )); down_ms=$((down_ms + w)); windows=$((windows + 1)); [ "$w" -gt "$longest" ] && longest=$w
  printf '  %s corte de %d.%03d s, aún en curso al terminar (respuestas: %s)\n' "$down_at" $((w / 1000)) $((w % 1000)) "$codes"
fi
dur=$(( $(now_ms) - START ))
printf 'RESULTADO %s: %d sondeos en %d s, %d fallidos, %d cortes, corte más largo %d.%03d s, total sin servicio %d.%03d s, respuestas de más de 1 s: %d\n' \
  "$URL" "$total" $((dur / 1000)) "$fails" "$windows" $((longest / 1000)) $((longest % 1000)) $((down_ms / 1000)) $((down_ms % 1000)) "$slow"
