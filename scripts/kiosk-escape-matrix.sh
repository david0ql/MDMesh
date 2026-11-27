#!/usr/bin/env bash
# Runs the kiosk escape test (scripts/kiosk-escape-test.py, docs/KIOSK-HARDENING.md) on a clean emulator per Android
# release: boot (wiped, headless) -> ADB enrollment -> escape test in single-app and multi-app mode + reboot -> shut down.
#   scripts/kiosk-escape-matrix.sh <agent-debug.apk> <avd>...     e.g. ... app-debug.apk mdm_api29 mdm_api35 mdm_api36
# Environment:
#   EMU_PORT   emulator console port (default 5600; serial emulator-$EMU_PORT)
#   WEB_PORT   the dev stack's console port on this machine (default 8088; the emulator reaches it at 10.0.2.2)
#   EMU_ARGS   extra emulator arguments (e.g. -read-only when another emulator already runs the same AVD)
#   OUT        where logs and the per-release result tables go (default /tmp/dallycontrol-kiosk)
#   LEGACY_PSQL  psql command of the stack (e.g. "docker compose --env-file docker/dev.env exec -T postgres psql -U dallycontrol
#              -d dallycontrol"): also sends the kiosk an old server would have sent (see --legacy-psql)
#   TEST_ARGS  extra (space-separated) arguments for kiosk-escape-test.py, e.g. "--modes single"
# Needs scripts/testapp/escapetest.apk (scripts/build-escapeapp.sh) and the dev stack seeded (admin/admin).
set -uo pipefail
APK=$1; shift
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb; export PATH=$SDK/platform-tools:$PATH
PORT=${EMU_PORT:-5600}; WEB=${WEB_PORT:-8088}
OUT=${OUT:-/tmp/dallycontrol-kiosk}; mkdir -p "$OUT"
[ -f "$HERE/testapp/escapetest.apk" ] || "$HERE/build-escapeapp.sh" >/dev/null
for AVD in "$@"; do
  SER=emulator-$PORT; LOG=$OUT/$AVD.log
  echo "=== $AVD ($SER)" | tee "$LOG"
  "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-snapshot -no-boot-anim -wipe-data ${EMU_ARGS:-} >"$OUT/$AVD.emu.log" 2>&1 &
  EMU=$!
  ok=0; for _ in $(seq 1 200); do kill -0 $EMU 2>/dev/null || break; [ "$($ADB -s "$SER" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { ok=1; break; }; sleep 3; done
  if [ $ok != 1 ]; then echo "RESULT $AVD: BOOT-FAILED ($(tail -1 "$OUT/$AVD.emu.log"))" | tee -a "$LOG"; kill $EMU; sleep 5; continue; fi
  sleep 20   # the device-policy service refuses a Device Owner for a few seconds after boot
  for _ in 1 2 3; do
    DALLYCONTROL_ADMIN_PASSWORD=${DALLYCONTROL_ADMIN_PASSWORD:-admin} "$HERE/adb-enroll.sh" --serial "$SER" --server "http://10.0.2.2:$WEB" \
        --api-url "http://localhost:$WEB" --admin-user admin --apk "$APK" --debug-build >>"$LOG" 2>&1 && break
    sleep 10
  done
  DID=""; for _ in $(seq 1 40); do
    DID=$($ADB -s "$SER" shell run-as com.dallycontrol.agent.debug cat files/datastore/mdm_identity.preferences_pb 2>/dev/null | strings | grep -oE '[0-9a-f]{8}-[0-9a-f-]{27}' | head -1)
    [ -n "$DID" ] && break; sleep 3; done
  if [ -z "$DID" ]; then echo "RESULT $AVD: ENROLL-FAILED" | tee -a "$LOG"
  else
    echo "device id $DID" >>"$LOG"
    sleep 8
    LEGACY=(); [ -n "${LEGACY_PSQL:-}" ] && LEGACY=(--legacy-psql "$LEGACY_PSQL")
    # shellcheck disable=SC2086
    python3 -u "$HERE/kiosk-escape-test.py" --serial "$SER" --device-id "$DID" --api "http://localhost:$WEB" \
        --reboot --summary "$OUT/$AVD.md" ${LEGACY[@]+"${LEGACY[@]}"} ${TEST_ARGS:-} >>"$LOG" 2>&1
    echo "RESULT $AVD: $(grep 'RESULT:' "$LOG" | tail -1)" | tee -a "$LOG"
  fi
  $ADB -s "$SER" emu kill >/dev/null 2>&1
  for _ in $(seq 1 20); do kill -0 $EMU 2>/dev/null || break; sleep 1; done
  kill $EMU 2>/dev/null; sleep 2; kill -9 $EMU 2>/dev/null; wait $EMU 2>/dev/null
done
