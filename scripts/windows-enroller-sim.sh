#!/usr/bin/env bash
# Runs the Windows enroller (web/src/enroll/inscribir-windows.ps1) under PowerShell with a simulated adb, to check
# its syntax and what it does when `dpm set-device-owner` is refused "because of accounts":
#   boot      Android 16 right after boot: refused although there is no account, accepted a few tries later
#   account   an account showed up after the review step: it is named, the technician removes it, then it works
#   stuck     refused for the whole wait with no account visible: ends with the "remove the accounts" message
# Needs pwsh (brew install powershell). Exit 0 when the three behave as expected.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PS1="$HERE/../web/src/enroll/inscribir-windows.ps1"
command -v pwsh >/dev/null || { echo "pwsh not found (brew install powershell)"; exit 2; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

cat >"$TMP/adb" <<'EOF'
#!/usr/bin/env bash
# Simulated adb. State lives in $SIM_DIR: `tries` counts set-device-owner calls; `accounts` lists accounts.
[ "${1:-}" = "-s" ] && shift 2
args="$*"
case "$args" in
  start-server) ;;
  devices*) printf 'List of devices attached\nSIM123\tdevice\n' ;;
  "shell getprop ro.product.model") echo "Pixel Sim" ;;
  "shell getprop ro.product.manufacturer") echo "Google" ;;
  "shell getprop ro.build.version.release") echo "16" ;;
  "shell getprop ro.build.version.sdk") echo "36" ;;
  "shell dumpsys device_policy") echo "Current Device Policy Manager state:" ;;
  "shell dumpsys account")
    # Shown once, then gone: the technician removes it before pressing Enter.
    if [ -s "$SIM_DIR/accounts" ]; then cat "$SIM_DIR/accounts"; : >"$SIM_DIR/accounts"; fi
    echo "  Accounts History" ;;
  install*) echo "Success" ;;
  "shell dpm set-device-owner"*)
    n=$(( $(cat "$SIM_DIR/tries" 2>/dev/null || echo 0) + 1 )); echo "$n" >"$SIM_DIR/tries"
    # SIM_LATE_ACCOUNT: an account that was not there at the review step exists by the time of the first try.
    if [ "$n" = 1 ] && [ -n "${SIM_LATE_ACCOUNT:-}" ]; then echo "    Account {name=$SIM_LATE_ACCOUNT, type=com.google}" >"$SIM_DIR/accounts"; fi
    if [ -s "$SIM_DIR/accounts" ] || [ "$n" -le "${SIM_REFUSALS:-0}" ]; then
      echo "java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device"
    else
      echo "Success: Device owner set to package com.dallycontrol.agent"
    fi ;;
  "shell am broadcast"*) echo 'Broadcast completed: result=-1, data="ok: provisioned"' ;;
  "shell pm path"*) ;;
  *) ;;
esac
EOF
chmod +x "$TMP/adb"
: >"$TMP/agent.apk"
# The enroller downloads the remote-support APK from the server: a throwaway one serves an empty file.
mkdir -p "$TMP/www/files"; : >"$TMP/www/files/droidvnc-ng.apk"
PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
( cd "$TMP/www" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 ) & WWW=$!
trap 'kill $WWW 2>/dev/null; rm -rf "$TMP"' EXIT
sleep 1

run() {   # run <name> <refusals> <boot wait s> <stdin answers>
  local name=$1; export SIM_DIR="$TMP/$name" SIM_REFUSALS=$2 SIM_LATE_ACCOUNT=; mkdir -p "$SIM_DIR"; : >"$SIM_DIR/accounts"
  [ "$name" = account ] && SIM_LATE_ACCOUNT=tecnico@gmail.com
  sed -e "s#__SERVER__#http://127.0.0.1:$PORT#" -e "s#__CODE__#ABCDEFGH#" -e "s#__FOLDER__#Sim#" "$PS1" >"$TMP/run.ps1"
  printf '%b' "$4" | LOCALAPPDATA="$TMP/work-$name" DC_ADB="$TMP/adb" DC_AGENT_APK="$TMP/agent.apk" DC_BOOT_WAIT=$3 DC_BOOT_PAUSE=1 \
      pwsh -NoProfile -File "$TMP/run.ps1" >"$TMP/$name.out" 2>&1
  echo $? >"$SIM_DIR/exit"
}
fail=0
check() { if eval "$2"; then echo "  PASS  $1"; else echo "  FAIL  $1"; fail=1; fi; }

echo "== boot: refused 4 times with no account, then accepted"
run boot 4 30 '1\n\n\n'
check "it says Android is still starting" "grep -q 'Android aún está iniciando' '$TMP/boot.out'"
check "it retries until accepted" "grep -q 'DallyControl administra el teléfono' '$TMP/boot.out' && [ \$(cat '$TMP/boot/tries') -eq 5 ]"
check "it never asks to remove accounts" "! grep -q 'Quita las cuentas' '$TMP/boot.out'"
check "it finishes the enrollment" "grep -q 'Inscrito' '$TMP/boot.out'"

echo "== account: an account appears after the review; named, removed, accepted"
run account 0 30 '1\n\n\n\n'
check "it names the account" "grep -q 'tecnico@gmail.com' '$TMP/account.out'"
check "it ends as Device Owner" "grep -q 'DallyControl administra el teléfono' '$TMP/account.out'"

echo "== stuck: refused for the whole wait, nothing visible"
run stuck 9999 5 '1\n\n'
check "it gives up after the wait with the accounts message" "grep -q 'siguió diciendo durante 2 minutos' '$TMP/stuck.out' && [ \$(cat '$TMP/stuck/exit') -ne 0 ]"
check "it retried more than the old three times" "[ \$(cat '$TMP/stuck/tries') -ge 4 ]"

[ $fail = 0 ] || { echo; echo "--- outputs"; tail -25 "$TMP"/*.out; }
exit $fail
