#!/usr/bin/env bash
#
# USB/ADB enrollment of a factory-reset Android device (or emulator) into DallyControl.
#
# This is the enrollment path Google's Play-Protect DPC allowlist does NOT block (QR/zero-touch/Knox
# provisioning of a self-signed DPC is blocked on devices with Google Play services). One run:
#   1. installs the agent APK,
#   2. makes it Device Owner (`dpm set-device-owner`; needs a device with no accounts),
#   3. hands it the server URL + a single-use enroll token (AdbProvisionReceiver),
#   4. optionally sets up unattended remote view/control (--remote --vnc-apk droidvnc-ng.apk): installs
#      droidVNC-NG (the open-source VNC server the agent drives, ADR 0010), grants it the PROJECT_MEDIA
#      app-op (no screen-capture consent dialog) and its accessibility input service, and grants the agent
#      WRITE_SECURE_SETTINGS so it can keep that input service on. A Device Owner cannot grant these itself.
#
# The token is either given (--token, minted in the console's Enroll page) or minted here with admin
# credentials (--admin-user/--admin-password, or DALLYCONTROL_ADMIN_PASSWORD).
#
# Usage:
#   scripts/adb-enroll.sh --server https://mdm.example.com --apk agent.apk [--token T | --admin-user admin]
#                         [--serial SERIAL] [--group-id N] [--configuration-id N] [--remote --vnc-apk FILE]
#                         [--debug-build] [--pair HOST:PORT --pair-code CODE] [--connect HOST:PORT]
#
#   Without factory reset: the phone keeps its data; it only needs no accounts while enrolling (removing an account
#   in Settings > Accounts erases nothing; add it back afterwards). Over USB, or WITHOUT a cable on Android 11+ via
#   Wireless debugging (Developer options): --pair/--pair-code from "Pair device with pairing code", --connect with
#   the address shown on the Wireless debugging screen. Without --apk the agent is downloaded from --server.
#
#   --group-id      put the device in that group (company); it runs the group's configuration (else the global one).
#   --configuration-id  pin a configuration on the device instead (wins over the group's and the global one).
#
#   --server        URL the DEVICE uses to reach the server (for an emulator and the dev stack:
#                   http://10.0.2.2:8088, debug builds only).
#   --api-url       URL THIS machine uses to mint the token (default: --server).
#   --debug-build   the APK is a debug build (package com.dallycontrol.agent.debug).
set -euo pipefail

SERVER=""; API_URL=""; APK=""; TOKEN=""; SERIAL="${ANDROID_SERIAL:-}"; ADMIN_USER=""; CONFIG_ID=""; GROUP_ID=""
REMOTE=0; VNC_APK=""; PKG="com.dallycontrol.agent"; ADB="${ADB:-adb}"
PAIR=""; PAIR_CODE=""; CONNECT=""
VNC_PKG="net.christianbeier.droidvnc_ng"
while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --api-url) API_URL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --admin-user) ADMIN_USER="$2"; shift 2 ;;
    --admin-password) DALLYCONTROL_ADMIN_PASSWORD="$2"; shift 2 ;;
    --configuration-id) CONFIG_ID="$2"; shift 2 ;;
    --group-id) GROUP_ID="$2"; shift 2 ;;
    --remote) REMOTE=1; shift ;;
    --vnc-apk) VNC_APK="$2"; shift 2 ;;
    --debug-build) PKG="com.dallycontrol.agent.debug"; shift ;;
    --pair) PAIR="$2"; shift 2 ;;
    --pair-code) PAIR_CODE="$2"; shift 2 ;;
    --connect) CONNECT="$2"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SERVER" ] || { echo "--server is required" >&2; exit 2; }
API_URL="${API_URL:-$SERVER}"

# Without a cable (Android 11+): Settings > Developer options > Wireless debugging. "Pair device with pairing code"
# shows HOST:PORT + a 6-digit code (--pair/--pair-code); the Wireless debugging screen shows the HOST:PORT to connect
# to (--connect). The phone and this computer must be on the same Wi-Fi.
if [ -n "$PAIR" ]; then
  [ -n "$PAIR_CODE" ] || { echo "--pair needs --pair-code" >&2; exit 2; }
  printf '\033[1m==> pairing with %s\033[0m\n' "$PAIR"
  "$ADB" pair "$PAIR" "$PAIR_CODE" | tr -d '\r' | grep -qi "success" || { echo "ERROR: pairing failed (check the code, it changes every time)" >&2; exit 1; }
fi
if [ -n "$CONNECT" ]; then
  printf '\033[1m==> connecting to %s\033[0m\n' "$CONNECT"
  "$ADB" connect "$CONNECT" | tr -d '\r' | grep -qiE "connected to|already connected" || { echo "ERROR: adb connect $CONNECT failed" >&2; exit 1; }
  SERIAL="$CONNECT"
fi
A=("$ADB"); [ -z "$SERIAL" ] || A+=(-s "$SERIAL")
ADMIN_COMPONENT="$PKG/com.dallycontrol.agent.admin.AdminReceiver"
say(){ printf '\033[1m==> %s\033[0m\n' "$*"; }
die(){ printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

"${A[@]}" get-state >/dev/null 2>&1 || die "no device (adb get-state failed)"
SDK=$("${A[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
say "device $("${A[@]}" shell getprop ro.product.model | tr -d '\r') (API $SDK)"

# --- 1. token ---------------------------------------------------------------------------------------
if [ -z "$TOKEN" ]; then
  [ -n "$ADMIN_USER" ] || die "pass --token, or --admin-user to mint one"
  PW="${DALLYCONTROL_ADMIN_PASSWORD:-}"
  [ -n "$PW" ] || { read -r -s -p "password for $ADMIN_USER: " PW; echo; }
  CJ=$(mktemp); trap 'rm -f "$CJ"' EXIT
  if command -v md5sum >/dev/null 2>&1; then MD5=$(printf '%s' "$PW" | md5sum | awk '{print toupper($1)}')
  else MD5=$(printf '%s' "$PW" | md5 | tr 'a-f' 'A-F'); fi
  LOGIN=$(curl -fsS -c "$CJ" -H 'Content-Type: application/json' \
    -d "{\"login\":\"$ADMIN_USER\",\"password\":\"$MD5\"}" "$API_URL/rest/public/auth/login") || die "login request failed"
  echo "$LOGIN" | grep -q '"status":"OK"' || die "login rejected"
  BODY=$(python3 -c 'import json,sys; print(json.dumps({k: int(v) for k, v in (("configurationId", sys.argv[1]), ("groupId", sys.argv[2])) if v}))' "$CONFIG_ID" "$GROUP_ID")
  TOKEN=$(curl -fsS -b "$CJ" -X POST -H 'Content-Type: application/json' -d "$BODY" \
    "$API_URL/rest/private/agent/v1/token" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])') \
    || die "could not mint an enroll token"
  say "minted enroll token"
fi

# --- 2. install + Device Owner ----------------------------------------------------------------------
if [ -z "$APK" ] && ! "${A[@]}" shell pm path "$PKG" >/dev/null 2>&1; then
  # No APK given: take the agent this server hosts (the same one its QR installs).
  APK=$(mktemp -t dallycontrol-agent).apk
  say "downloading the agent from $SERVER/files/dallycontrol-agent.apk"
  curl -fsSL -o "$APK" "$SERVER/files/dallycontrol-agent.apk" || die "could not download the agent APK (pass --apk)"
fi
if [ -n "$APK" ]; then
  say "installing $APK"
  "${A[@]}" install -r -g "$APK" >/dev/null || die "adb install failed"
fi
"${A[@]}" shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed (pass --apk)"
if "${A[@]}" shell dumpsys device_policy 2>/dev/null | grep -A4 -i "Device Owner" | grep -q "$PKG/"; then
  say "already Device Owner"
else
  # Android lets an app become Device Owner only on a phone with no accounts. Removing an account (Settings > Accounts)
  # does not erase the phone's data; add it back once enrollment finishes.
  ACCOUNTS=$("${A[@]}" shell dumpsys account 2>/dev/null | tr -d '\r' | sed -n 's/^ *Account {name=\(.*\), type=\(.*\)}$/\2  \1/p' | sort -u)
  if [ -n "$ACCOUNTS" ]; then
    printf '\033[31mThe phone has accounts; Android refuses a Device Owner until they are removed:\033[0m\n%s\n' "$ACCOUNTS" >&2
    echo "Remove them in Settings > Accounts (no data is erased), run this again, then add them back." >&2
    exit 1
  fi
  say "setting Device Owner"
  # Retried: right after boot the device-policy service can refuse transiently. `|| true` keeps set -e from
  # aborting silently on a non-zero adb exit, so the real error is reported below.
  for _try in 1 2 3; do
    OUT=$("${A[@]}" shell dpm set-device-owner "$ADMIN_COMPONENT" 2>&1 | tr -d '\r') || true
    echo "$OUT" | grep -qi "success" && break
    sleep 5
  done
  echo "$OUT" | grep -qi "success" || die "dpm set-device-owner failed: $OUT (the device must have no accounts; factory reset it)"
fi

# --- 2b. storage tools: per-app sizes (usage access) and deleting large files (all-files access, Android 11+) ---------
# Only adb (or the person, in Settings) can turn these on; the console's Almacenamiento panel works without them but sees less.
"${A[@]}" shell appops set "$PKG" GET_USAGE_STATS allow >/dev/null 2>&1 || echo "  warn: usage access not granted"
# Notification access (diagnosis: which app is ringing, and its buttons). Android 11+ only takes it from here.
"${A[@]}" shell cmd notification allow_listener "$PKG/com.dallycontrol.agent.diag.DcNotificationListener" >/dev/null 2>&1 || echo "  warn: notification access not granted"
if [ "$SDK" -ge 30 ]; then
  "${A[@]}" shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || echo "  warn: all-files access not granted"
fi

# --- 3. optional: unattended remote control ----------------------------------------------------------
if [ "$REMOTE" = 1 ]; then
  say "setting up unattended remote view/control (droidVNC-NG)"
  [ "$SDK" -ge 24 ] || die "remote control needs Android 7 (API 24) or newer; this device is API $SDK"
  if [ -n "$VNC_APK" ]; then
    "${A[@]}" install -r -g "$VNC_APK" >/dev/null || die "installing droidVNC-NG failed"
  fi
  if ! "${A[@]}" shell pm path "$VNC_PKG" >/dev/null 2>&1 && [ -z "$VNC_APK" ]; then
    VNC_APK=$(mktemp -t droidvnc-ng).apk
    curl -fsSL -o "$VNC_APK" "$SERVER/files/droidvnc-ng.apk" && "${A[@]}" install -r -g "$VNC_APK" >/dev/null || true
  fi
  "${A[@]}" shell pm path "$VNC_PKG" >/dev/null 2>&1 || die "droidVNC-NG is not installed (pass --vnc-apk)"
  "${A[@]}" shell appops set "$VNC_PKG" PROJECT_MEDIA allow || echo "  warn: appops PROJECT_MEDIA failed"
  # The agent configures droidVNC-NG (access key) during provisioning below and then turns its input service on
  # itself. Do NOT enable that service here first: it would start droidVNC-NG before it has the agent's key.
  "${A[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || echo "  warn: WRITE_SECURE_SETTINGS grant failed"
fi

# --- 4. hand over server URL + token -----------------------------------------------------------------
say "provisioning server $SERVER"
RES=$("${A[@]}" shell am broadcast -a com.dallycontrol.agent.ADB_PROVISION \
  -n "$PKG/com.dallycontrol.agent.provisioning.AdbProvisionReceiver" \
  --es server_url "$SERVER" --es enroll_token "$TOKEN" 2>&1 | tr -d '\r')
if echo "$RES" | grep -q 'already enrolled'; then
  say "already enrolled with a server; left as is (re-enroll: factory reset, or send the broadcast with --ez force true)"
else
  echo "$RES" | grep -q 'data="ok' || die "provisioning broadcast failed: $RES"
fi
"${A[@]}" shell am start -n "$PKG/com.dallycontrol.agent.MainActivity" >/dev/null 2>&1 || true
if [ "$REMOTE" = 1 ]; then
  # Fallback: if the agent could not enable droidVNC-NG's input service, do it now (after its key is set).
  SVC="$VNC_PKG/$VNC_PKG.InputService"   # the full class name; the short /.InputService form is ignored
  for _ in $(seq 1 10); do
    "${A[@]}" shell settings get secure enabled_accessibility_services | grep -q "$SVC" && break
    sleep 1
  done
  CUR=$("${A[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
  case "$CUR" in
    *"$SVC"*) say "remote control ready (droidVNC-NG configured by the agent)" ;;
    *) case "$CUR" in ""|null) CUR="$SVC" ;; *) CUR="$CUR:$SVC" ;; esac
       "${A[@]}" shell settings put secure enabled_accessibility_services "$CUR"
       "${A[@]}" shell settings put secure accessibility_enabled 1
       say "remote control ready (input service enabled over adb)" ;;
  esac
fi
say "done: the device enrolls within seconds; it will appear in the console's device list"
