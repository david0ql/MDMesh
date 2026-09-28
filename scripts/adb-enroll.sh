#!/usr/bin/env bash
#
# USB/ADB enrollment of a factory-reset Android device (or emulator) into MDMesh.
#
# This is the enrollment path Google's Play-Protect DPC allowlist does NOT block (QR/zero-touch/Knox
# provisioning of a self-signed DPC is blocked on devices with Google Play services). One run:
#   1. installs the agent APK,
#   2. makes it Device Owner (`dpm set-device-owner`; needs a device with no accounts),
#   3. hands it the server URL + a single-use enroll token (AdbProvisionReceiver),
#   4. optionally pre-authorizes unattended remote control (--remote): the PROJECT_MEDIA app-op (no
#      screen-capture consent dialog) and WRITE_SECURE_SETTINGS (the agent turns its own
#      accessibility input service on/off around each control session).
#
# The token is either given (--token, minted in the console's Enroll page) or minted here with admin
# credentials (--admin-user/--admin-password, or MDMESH_ADMIN_PASSWORD).
#
# Usage:
#   scripts/adb-enroll.sh --server https://mdm.example.com --apk agent.apk [--token T | --admin-user admin]
#                         [--serial SERIAL] [--configuration-id N] [--remote] [--debug-build]
#
#   --server        URL the DEVICE uses to reach the server (for an emulator and the dev stack:
#                   http://10.0.2.2:8088, debug builds only).
#   --api-url       URL THIS machine uses to mint the token (default: --server).
#   --debug-build   the APK is a debug build (package com.mdmesh.agent.debug).
set -euo pipefail

SERVER=""; API_URL=""; APK=""; TOKEN=""; SERIAL="${ANDROID_SERIAL:-}"; ADMIN_USER=""; CONFIG_ID=""
REMOTE=0; PKG="com.mdmesh.agent"; ADB="${ADB:-adb}"
while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --api-url) API_URL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --admin-user) ADMIN_USER="$2"; shift 2 ;;
    --admin-password) MDMESH_ADMIN_PASSWORD="$2"; shift 2 ;;
    --configuration-id) CONFIG_ID="$2"; shift 2 ;;
    --remote) REMOTE=1; shift ;;
    --debug-build) PKG="com.mdmesh.agent.debug"; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SERVER" ] || { echo "--server is required" >&2; exit 2; }
API_URL="${API_URL:-$SERVER}"
A=("$ADB"); [ -z "$SERIAL" ] || A+=(-s "$SERIAL")
ADMIN_COMPONENT="$PKG/com.mdmesh.agent.admin.AdminReceiver"
say(){ printf '\033[1m==> %s\033[0m\n' "$*"; }
die(){ printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

"${A[@]}" get-state >/dev/null 2>&1 || die "no device (adb get-state failed)"
SDK=$("${A[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
say "device $("${A[@]}" shell getprop ro.product.model | tr -d '\r') (API $SDK)"

# --- 1. token ---------------------------------------------------------------------------------------
if [ -z "$TOKEN" ]; then
  [ -n "$ADMIN_USER" ] || die "pass --token, or --admin-user to mint one"
  PW="${MDMESH_ADMIN_PASSWORD:-}"
  [ -n "$PW" ] || { read -r -s -p "password for $ADMIN_USER: " PW; echo; }
  CJ=$(mktemp); trap 'rm -f "$CJ"' EXIT
  if command -v md5sum >/dev/null 2>&1; then MD5=$(printf '%s' "$PW" | md5sum | awk '{print toupper($1)}')
  else MD5=$(printf '%s' "$PW" | md5 | tr 'a-f' 'A-F'); fi
  LOGIN=$(curl -fsS -c "$CJ" -H 'Content-Type: application/json' \
    -d "{\"login\":\"$ADMIN_USER\",\"password\":\"$MD5\"}" "$API_URL/rest/public/auth/login") || die "login request failed"
  echo "$LOGIN" | grep -q '"status":"OK"' || die "login rejected"
  BODY='{}'; [ -z "$CONFIG_ID" ] || BODY="{\"configurationId\":$CONFIG_ID}"
  TOKEN=$(curl -fsS -b "$CJ" -X POST -H 'Content-Type: application/json' -d "$BODY" \
    "$API_URL/rest/private/agent/v1/token" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])') \
    || die "could not mint an enroll token"
  say "minted enroll token"
fi

# --- 2. install + Device Owner ----------------------------------------------------------------------
if [ -n "$APK" ]; then
  say "installing $APK"
  "${A[@]}" install -r -g "$APK" >/dev/null || die "adb install failed"
fi
"${A[@]}" shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed (pass --apk)"
if "${A[@]}" shell dumpsys device_policy 2>/dev/null | grep -A4 -i "Device Owner" | grep -q "$PKG/"; then
  say "already Device Owner"
else
  say "setting Device Owner"
  OUT=$("${A[@]}" shell dpm set-device-owner "$ADMIN_COMPONENT" 2>&1 | tr -d '\r')
  echo "$OUT" | grep -qi "success" || die "dpm set-device-owner failed: $OUT (the device must have no accounts; factory reset it)"
fi

# --- 3. optional: unattended remote control ----------------------------------------------------------
if [ "$REMOTE" = 1 ]; then
  say "pre-authorizing unattended remote control"
  "${A[@]}" shell appops set "$PKG" PROJECT_MEDIA allow || echo "  warn: appops PROJECT_MEDIA failed"
  "${A[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || echo "  warn: WRITE_SECURE_SETTINGS grant failed"
fi

# --- 4. hand over server URL + token -----------------------------------------------------------------
say "provisioning server $SERVER"
RES=$("${A[@]}" shell am broadcast -a com.mdmesh.agent.ADB_PROVISION \
  -n "$PKG/com.mdmesh.agent.provisioning.AdbProvisionReceiver" \
  --es server_url "$SERVER" --es enroll_token "$TOKEN" 2>&1 | tr -d '\r')
echo "$RES" | grep -q 'data="ok' || die "provisioning broadcast failed: $RES"
"${A[@]}" shell am start -n "$PKG/com.mdmesh.agent.MainActivity" >/dev/null 2>&1 || true
say "done: the device enrolls within seconds; it will appear in the console's device list"
