#!/usr/bin/env bash
#
# Start a remote view/control session on an enrolled device (ADR 0010) and print the viewer link.
#
#   scripts/remote-session.sh --api https://mdm.example.com --device <device id> [--view-only]
#                             [--admin-user admin] [--stop]
#
# It queues remote.vnc.start with a one-time numeric session id (the Mode-II repeater only accepts
# decimal ids) and an 8-character VNC password, waits until the device reports it is connected to the
# repeater, and prints the /remote/vnc/vnc.html link. Open it in a browser that is signed in to the
# console (the viewer is behind the console session). --stop ends the device's session.
#
# The device must be awake or in always-on power mode: a locked phone on battery (adaptive mode) only
# checks in every few minutes, so the command waits until then.
set -euo pipefail

API=""; DEVICE=""; VIEW_ONLY=false; ADMIN_USER="admin"; STOP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --api) API="${2%/}"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --view-only) VIEW_ONLY=true; shift ;;
    --admin-user) ADMIN_USER="$2"; shift 2 ;;
    --stop) STOP=1; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$API" ] && [ -n "$DEVICE" ] || { echo "--api and --device are required" >&2; exit 2; }

PW="${MDMESH_ADMIN_PASSWORD:-}"
[ -n "$PW" ] || { read -r -s -p "password for $ADMIN_USER: " PW; echo; }
CJ=$(mktemp); trap 'rm -f "$CJ"' EXIT
if command -v md5sum >/dev/null 2>&1; then MD5=$(printf '%s' "$PW" | md5sum | awk '{print toupper($1)}')
else MD5=$(printf '%s' "$PW" | md5 | tr 'a-f' 'A-F'); fi
curl -fsS -c "$CJ" -H 'Content-Type: application/json' -d "{\"login\":\"$ADMIN_USER\",\"password\":\"$MD5\"}" \
  "$API/rest/public/auth/login" | grep -q '"status":"OK"' || { echo "login rejected" >&2; exit 1; }

queue() { # queue <json body>; prints the command id
  curl -fsS -b "$CJ" -X POST -H 'Content-Type: application/json' -d "$1" \
    "$API/rest/private/agent/v1/devices/$DEVICE/commands" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["data"]["id"]) if d.get("status")=="OK" else sys.exit(d.get("message") or "queue failed")'
}

if [ "$STOP" = 1 ]; then
  queue '{"type":"remote.vnc.stop"}' >/dev/null && echo "stop queued"
  exit 0
fi

SID=$(python3 -c 'import secrets; print(secrets.randbelow(9 * 10**17) + 10**17)')
VNCPW=$(python3 -c 'import secrets; print(secrets.token_hex(4))')
CAP=remote.control; [ "$VIEW_ONLY" = true ] && CAP=remote.view
PAYLOAD=$(python3 -c 'import json,sys; print(json.dumps(json.dumps({"sessionId":sys.argv[1],"password":sys.argv[2],"viewOnly":sys.argv[3]=="true"})))' "$SID" "$VNCPW" "$VIEW_ONLY")
ID=$(queue "{\"type\":\"remote.vnc.start\",\"requiresCapability\":\"$CAP\",\"payload\":$PAYLOAD}")
echo "session queued (command $ID); waiting for the device..." >&2

for _ in $(seq 1 180); do
  ROW=$(curl -fsS -b "$CJ" "$API/rest/private/agent/v1/devices/$DEVICE/commands?limit=20" \
    | python3 -c 'import sys,json
cid=sys.argv[1]
for c in json.load(sys.stdin).get("data") or []:
    if str(c.get("id"))==cid: print(c.get("status"), c.get("detail") or ""); break' "$ID")
  case "$ROW" in
    done*) break ;;
    failed*|unsupported*|expired*) echo "device refused: $ROW" >&2; exit 1 ;;
  esac
  sleep 2
done
case "${ROW:-}" in done*) ;; *) echo "device did not answer within 6 minutes (asleep? offline?)" >&2; exit 1 ;; esac

echo "$API/remote/vnc/vnc.html?path=remote/vnc/websockify&repeaterID=$SID&password=$VNCPW&autoconnect=true&resize=scale&reconnect=false"
