#!/usr/bin/env bash
# Runs the functional device test on a clean emulator per Android release.
#   scripts/emulator-matrix.sh <apk> <avd>...      e.g. scripts/emulator-matrix.sh app-debug.apk mdm_api23 mdm_api24
# Needs: the dev stack on :8088 (debug agent reaches it at 10.0.2.2:8088), AVDs already created.
# Each emulator boots with a visible window (wiped), enrolls over ADB, runs scripts/device-func-test.py, and shuts down.
# REMOTE_E2E=1 VNC_APK=<droidvnc-ng.apk>: enroll with --remote and run scripts/remote-e2e.mjs instead (ADR 0010).
set -uo pipefail
APK=$1; shift
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb; export PATH=$SDK/platform-tools:$PATH
OUT=${MATRIX_OUT:-/tmp/mdmesh-matrix}; mkdir -p "$OUT"
PORT=5600
for AVD in "$@"; do
  SER=emulator-$PORT; LOG=$OUT/$AVD.log
  echo "=== $AVD ($SER)" | tee "$LOG"
  "$SDK/emulator/emulator" -avd "$AVD" -port $PORT -no-audio -no-snapshot -no-boot-anim -wipe-data >"$OUT/$AVD.emu.log" 2>&1 &
  EMU=$!
  ok=0; for i in $(seq 1 200); do [ "$($ADB -s $SER shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { ok=1; break; }; sleep 3; done
  if [ $ok != 1 ]; then echo "RESULT $AVD: BOOT-FAILED" | tee -a "$LOG"; kill $EMU; sleep 5; continue; fi
  sleep 8
  API=$($ADB -s $SER shell getprop ro.build.version.sdk | tr -d '\r')
  EXTRA=(); [ "${REMOTE_E2E:-0}" = 1 ] && EXTRA=(--remote --vnc-apk "$VNC_APK")
  MDMESH_ADMIN_PASSWORD=${MDMESH_ADMIN_PASSWORD:-admin} "$HERE/adb-enroll.sh" --serial $SER --server http://10.0.2.2:8088 \
      --api-url http://localhost:8088 --admin-user admin --apk "$APK" --debug-build "${EXTRA[@]}" >>"$LOG" 2>&1 || echo "enroll script failed" >>"$LOG"
  DID=""; for i in $(seq 1 40); do
    DID=$($ADB -s $SER shell run-as com.mdmesh.agent.debug cat files/datastore/mdm_identity.preferences_pb 2>/dev/null | strings | grep -oE '[0-9a-f]{8}-[0-9a-f-]{27}' | head -1)
    [ -n "$DID" ] && break; sleep 3; done
  if [ -z "$DID" ]; then echo "RESULT $AVD (API $API): ENROLL-FAILED" | tee -a "$LOG"
  else
    echo "device id $DID" >>"$LOG"
    if [ "${REMOTE_E2E:-0}" = 1 ]; then
      sleep 10
      node "$HERE/remote-e2e.mjs" --api http://localhost:8088 --device "$DID" --serial $SER >>"$LOG" 2>&1
    else
      python3 -u "$HERE/device-func-test.py" --serial $SER --device-id "$DID" ${FUNC_ARGS:-} >>"$LOG" 2>&1
    fi
    echo "RESULT $AVD (API $API): $(grep 'RESULT:' "$LOG" | tail -1)" | tee -a "$LOG"
  fi
  # Shut down with a deadline: `emu kill` + a plain wait once hung for 80 minutes on an emulator that never exited.
  $ADB -s $SER emu kill >/dev/null 2>&1
  for _ in $(seq 1 20); do kill -0 $EMU 2>/dev/null || break; sleep 1; done
  kill $EMU 2>/dev/null; sleep 2; kill -9 $EMU 2>/dev/null; wait $EMU 2>/dev/null
  $ADB disconnect $SER >/dev/null 2>&1 || true
done
