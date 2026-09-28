#!/usr/bin/env bash
#
# End-to-end checks of the operations-parity features (folders, reusable enrollment codes, kiosk functions, managed
# browser, app policy, location trail, SIM alerts, passcode, open app / ring, kiosk crash-loop guard) against a
# running DallyControl and a real Device-Owner phone or emulator reachable over adb.
#
#   ADMIN_PW=<admin password> scripts/parity-e2e.sh --base https://mdm.example.com --device <device number> \
#       --serial emulator-5554 --config <kiosk configuration id> [--only folders,codes,...]
#
# Sections: folders codes roles browser apps trail sim passcode launch crashloop
# It creates folders/devices named e2e-* and removes them; it changes the given configuration's DallyControl policy
# (dcPolicy) and restores it on exit. Needs bash 4+, curl, python3, adb. Exit 0 only if every check passes.
set -uo pipefail

BASE=""; DEV=""; SERIAL=""; CFG=""; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2%/}"; shift 2 ;;
    --device) DEV="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --config) CFG="$2"; shift 2 ;;
    --only) ONLY=",$2,"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[ -n "$BASE" ] && [ -n "$DEV" ] && [ -n "$SERIAL" ] && [ -n "$CFG" ] && [ -n "${ADMIN_PW:-}" ] || {
  sed -n '2,16p' "$0" >&2; exit 2; }

API="$BASE/rest"; F="$API/private/fleet/v1"; A="$API/private/agent/v1"
TMP=$(mktemp -d); CJ="$TMP/cj"
PASS=0; FAIL=0
ORIG_POLICY_FILE="$TMP/orig-policy"
declare -a CLEAN_GROUPS=() CLEAN_DEVICES=() CLEAN_CODES=()
ORIG_GROUP=""

adb_(){ adb -s "$SERIAL" "$@"; }
ok(){ echo "  PASS  $1"; PASS=$((PASS+1)); }
ko(){ echo "  FAIL  $1${2:+  [$2]}"; FAIL=$((FAIL+1)); }
chk(){ if [ "$2" = "$3" ]; then ok "$1"; else ko "$1" "got '$2', want '$3'"; fi; }
want(){ [[ "$ONLY" == "" || "$ONLY" == *",$1,"* ]]; }
field(){ python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null; }
get(){ curl -s -b "$CJ" "$1"; }
post(){ curl -s -b "$CJ" -H 'Content-Type: application/json' -X "${3:-POST}" -d "$2" "$1"; }
md5u(){ if command -v md5sum >/dev/null 2>&1; then printf '%s' "$1" | md5sum | awk '{print toupper($1)}'; else printf '%s' "$1" | md5 | tr 'a-f' 'A-F'; fi; }
top(){ adb_ shell dumpsys activity activities 2>/dev/null | sed -n 's/.*topResumedActivity=.* u0 \([^ /]*\).*/\1/p' | head -1; }
locktask(){ adb_ shell dumpsys activity 2>/dev/null | sed -n 's/.*mLockTaskModeState=\([A-Z]*\).*/\1/p' | head -1; }
# wait_for <seconds> <command...>: poll until the command succeeds.
wait_for(){ local t=$1; shift; for _ in $(seq 1 "$t"); do if "$@"; then return 0; fi; sleep 1; done; return 1; }

# --- the configuration's policy: read, set, restore -------------------------------------------------------------
cfg_json(){ get "$API/private/configurations/search" | python3 -c "
import sys,json; print(json.dumps(next(c for c in json.load(sys.stdin)['data'] if c['id']==$CFG)))"; }
set_policy(){ # set_policy '<dcPolicy json or empty>'
  local cfg apps; cfg=$(cfg_json); apps=$(get "$API/private/configurations/applications/$CFG")
  python3 - "$1" "$cfg" "$apps" > "$TMP/cfg-put.json" <<'PY'
import sys, json
pol, cfg, apps = sys.argv[1], json.loads(sys.argv[2]), json.loads(sys.argv[3])['data']
cfg['dcPolicy'] = pol or None
cfg['applications'] = [a for a in apps if a.get('selected')]
print(json.dumps(cfg))
PY
  post "$API/private/configurations" "@$TMP/cfg-put.json" PUT | field "d['status']"
}
revision(){ get "$A/devices/$DEV/configStatus" | field "d['data']['currentRevision']+' '+str(d['data']['inSync'])"; }
# wait until the device applied the configuration's current revision
wait_applied(){
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null
  wait_for "${1:-90}" bash -c "curl -s -b '$CJ' '$A/devices/$DEV/configStatus' | python3 -c \"import sys,json; d=json.load(sys.stdin)['data']; sys.exit(0 if d['inSync'] else 1)\""
}
outcome(){ get "$A/devices/$DEV/configStatus" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']; det=(d.get('lastCommand') or {}).get('detail') or '{}'
try: print(json.loads(det).get('outcomes',{}).get('$1',''))
except Exception: print('')"; }
events_since(){ get "$A/devices/$DEV/events?since=$1&limit=200" | python3 -c "
import sys,json; print(' '.join(e['type']+':'+str(e.get('detail') or '') for e in json.load(sys.stdin)['data']))"; }

cleanup(){
  echo "== cleanup"
  if [ -f "$ORIG_POLICY_FILE" ]; then
    echo "  restore policy of configuration $CFG: $(set_policy "$(cat "$ORIG_POLICY_FILE")")"
  fi
  if [ -n "$ORIG_GROUP" ]; then
    post "$F/devices/group" "{\"deviceIds\":[$DEV_ID],\"groupId\":$ORIG_GROUP}" >/dev/null
  fi
  for id in "${CLEAN_DEVICES[@]:-}"; do [ -n "$id" ] && post "$API/private/devices/deleteBulk" "{\"ids\":[$id]}" >/dev/null; done
  for id in "${CLEAN_CODES[@]:-}"; do [ -n "$id" ] && curl -s -b "$CJ" -X DELETE "$A/codes/$id" >/dev/null; done
  # children first (deepest last created)
  for (( i=${#CLEAN_GROUPS[@]}-1; i>=0; i-- )); do [ -n "${CLEAN_GROUPS[$i]}" ] && curl -s -b "$CJ" -X DELETE "$F/groups/${CLEAN_GROUPS[$i]}" >/dev/null; done
  rm -rf "$TMP"
}
trap cleanup EXIT

echo "== login"
chk "admin login" "$(curl -s -c "$CJ" -H 'Content-Type: application/json' -d "{\"login\":\"admin\",\"password\":\"$(md5u "$ADMIN_PW")\"}" "$API/public/auth/login" | field "d['status']")" "OK"
DEV_ID=$(post "$API/private/devices/search" '{"pageSize":200,"pageNum":1}' | field "next(x['id'] for x in d['data']['devices']['items'] if x['number']=='$DEV')")
[ -n "$DEV_ID" ] && ok "device $DEV found (id $DEV_ID)" || { ko "device $DEV found"; exit 1; }
ORIG_GROUP=$(get "$F/devices/$DEV_ID/scope" | field "d['data']['groupId'] if d['data']['groupId'] is not None else 'null'")
cfg_json | python3 -c "import sys,json; print(json.load(sys.stdin).get('dcPolicy') or '')" > "$ORIG_POLICY_FILE"

# ================================================================================================ R1/R3 folders
if want folders; then
  echo "== folders: nested groups inherit configuration (R1, R3)"
  SUF=$RANDOM
  P=$(post "$F/groups" "{\"name\":\"e2e-Colombia-$SUF\",\"configurationId\":$CFG}" | field "d['data']['id']"); CLEAN_GROUPS+=("$P")
  C=$(post "$F/groups" "{\"name\":\"e2e-Agencia-$SUF\",\"parentId\":$P}" | field "d['data']['id']"); CLEAN_GROUPS+=("$C")
  G=$(post "$F/groups" "{\"name\":\"e2e-SamsungA15-$SUF\",\"parentId\":$C}" | field "d['data']['id']"); CLEAN_GROUPS+=("$G")
  [ -n "$P" ] && [ -n "$C" ] && [ -n "$G" ] && ok "three nested folders created" || ko "three nested folders created"
  chk "grandchild inherits the root's configuration" "$(get "$F/groups" | field "next(g['effectiveConfigurationId'] for g in d['data']['groups'] if g['id']==$G)")" "$CFG"
  chk "grandchild has no configuration of its own" "$(get "$F/groups" | field "next(str(g['configurationId']) for g in d['data']['groups'] if g['id']==$G)")" "None"
  chk "same name allowed under another parent" "$(post "$F/groups" "{\"name\":\"e2e-Agencia-$SUF\",\"parentId\":$G}" | field "d['status']")" "OK"
  X=$(get "$F/groups" | field "next(g['id'] for g in d['data']['groups'] if g['name']=='e2e-Agencia-$SUF' and g['parentId']==$G)"); CLEAN_GROUPS+=("$X")
  chk "duplicate sibling name refused" "$(post "$F/groups" "{\"name\":\"E2E-agencia-$SUF\",\"parentId\":$P}" | field "d['message']")" "error.group.name.taken"
  chk "a folder cannot move under its own descendant" "$(post "$F/groups/$P" "{\"name\":\"e2e-Colombia-$SUF\",\"configurationId\":$CFG,\"parentId\":$G}" PUT | field "d['message']")" "error.group.parent.invalid"
  chk "device moved into the grandchild folder" "$(post "$F/devices/group" "{\"deviceIds\":[$DEV_ID],\"groupId\":$G}" | field "d['status']")" "OK"
  chk "device scope: configuration comes from the folder chain" "$(get "$F/devices/$DEV_ID/scope" | field "str(d['data']['source'])+':'+str(d['data']['configurationId'])")" "group:$CFG"
  chk "a command to the root folder reaches the grandchild's device" "$(post "$F/groups/$P/commands" '{"command":{"type":"device.ringStop","requiresCapability":"device.ringStop"}}' | field "d['data']['queued']")" "1"
  chk "listing marks the tree (parentId)" "$(get "$F/groups" | field "next(g['parentId'] for g in d['data']['groups'] if g['id']==$C)")" "$P"
  post "$F/devices/group" "{\"deviceIds\":[$DEV_ID],\"groupId\":$ORIG_GROUP}" >/dev/null
  chk "deleting a middle folder lifts its children to the grandparent" "$(curl -s -b "$CJ" -X DELETE "$F/groups/$C" | field "d['status']"):$(get "$F/groups" | field "next(g['parentId'] for g in d['data']['groups'] if g['id']==$G)")" "OK:$P"
fi

# ================================================================================================ R2 codes
if want codes; then
  echo "== reusable enrollment codes per folder (R2)"
  SUF=$RANDOM
  CG=$(post "$F/groups" "{\"name\":\"e2e-Ecuador-$SUF\",\"configurationId\":$CFG}" | field "d['data']['id']"); CLEAN_GROUPS+=("$CG")
  CS=$(post "$F/groups" "{\"name\":\"e2e-Provisiones-$SUF\",\"parentId\":$CG}" | field "d['data']['id']"); CLEAN_GROUPS+=("$CS")
  R=$(post "$A/codes" "{\"groupId\":$CS,\"label\":\"e2e ops\"}")
  CODE=$(printf '%s' "$R" | field "d['data']['code']"); CID=$(printf '%s' "$R" | field "d['data']['id']"); CLEAN_CODES+=("$CID")
  chk "code has 8 unambiguous characters" "$(printf '%s' "$CODE" | grep -cE '^[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{8}$')" "1"
  typed="$(printf '%s' "${CODE:0:4}" | tr 'A-Z' 'a-z') - ${CODE:4}"
  enroll(){ curl -s -H 'Content-Type: application/json' -d "{\"enrollToken\":\"$1\",\"agent\":{\"version\":\"e2e\"},\"device\":{\"model\":\"e2e\"},\"capabilities\":{}}" "$API/public/agent/v1/enroll"; }
  D1=$(enroll "$typed" | field "d['data']['deviceId']")
  D2=$(enroll "$CODE" | field "d['data']['deviceId']")
  [ -n "$D1" ] && [ -n "$D2" ] && [ "$D1" != "$D2" ] && ok "the same code enrolls two phones (typed lowercase with a dash and spaces)" || ko "the same code enrolls two phones" "$D1 / $D2"
  for n in "$D1" "$D2"; do
    id=$(post "$API/private/devices/search" '{"pageSize":500,"pageNum":1}' | field "next(x['id'] for x in d['data']['devices']['items'] if x['number']=='$n')"); CLEAN_DEVICES+=("$id")
    chk "phone $n landed in the code's folder, running the inherited configuration" "$(get "$F/devices/$id/scope" | field "str(d['data']['groupId'])+':'+str(d['data']['configurationId'])")" "$CS:$CFG"
  done
  chk "the code counts its uses" "$(get "$A/codes" | field "next(c['uses'] for c in d['data'] if c['id']==$CID)")" "2"
  chk "revoke" "$(curl -s -b "$CJ" -X DELETE "$A/codes/$CID" | field "d['status']")" "OK"
  chk "a revoked code no longer enrolls" "$(enroll "$CODE" | field "d['message']")" "error.agent.token.invalid"
  chk "a random code is refused" "$(enroll "ZZZZ-2222" | field "d['message']")" "error.agent.token.invalid"
fi

# ================================================================================================ R10 functions
ui(){ adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb_ exec-out cat /sdcard/ui.xml 2>/dev/null; }
locktask_pkgs(){ adb_ shell dumpsys activity activities 2>/dev/null | sed -n '/mLockTaskPackages/{n;p;}' | tr -d ' []' | sed 's/^u0://' | tr ',' '\n' | sort -u; }
if want roles; then
  echo "== kiosk functions resolved per device: Phone + Browser (R10)"
  T0=$(( $(date +%s) * 1000 ))
  chk "policy saved (kioskRoles phone, browser)" "$(set_policy '{"kioskRoles":["phone","browser"]}')" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision" "$(revision)"
  chk "kiosk outcome" "$(outcome kiosk)" "applied"
  sleep 3
  chk "still locked in kiosk" "$(locktask)" "LOCKED"
  pk=$(locktask_pkgs)
  for p in co.amovil.preventa com.google.android.dialer com.android.chrome com.android.server.telecom; do
    printf '%s\n' "$pk" | grep -qx "$p" && ok "lock-task allows $p" || ko "lock-task allows $p"
  done
  adb_ shell input keyevent KEYCODE_HOME; sleep 2
  u=$(ui)
  for label in "Phone" "Chrome" "Amovil Preventa"; do
    printf '%s' "$u" | grep -q "text=\"$label\"" && ok "kiosk home shows $label" || ko "kiosk home shows $label"
  done
  adb -s "$SERIAL" emu gsm call 5551234567 >/dev/null
  wait_for 20 bash -c "[ \"\$(adb -s $SERIAL shell dumpsys activity activities | sed -n 's/.*topResumedActivity=.* u0 \([^ /]*\).*/\1/p' | head -1)\" = com.google.android.dialer ]" \
    && ok "an incoming call shows in kiosk (dialer's in-call screen)" || ko "an incoming call shows in kiosk" "top=$(top)"
  chk "kiosk still locked during the call" "$(locktask)" "LOCKED"
  adb -s "$SERIAL" emu gsm cancel 5551234567 >/dev/null; sleep 3
  post "$A/devices/$DEV/commands" '{"type":"device.appLaunch","requiresCapability":"device.appLaunch","payload":"{\"packageName\":\"com.android.chrome\"}"}' >/dev/null
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null
  wait_for 40 bash -c "[ \"\$(adb -s $SERIAL shell dumpsys activity activities | sed -n 's/.*topResumedActivity=.* u0 \([^ /]*\).*/\1/p' | head -1)\" = com.android.chrome ]" \
    && ok "remote 'open app' brings Chrome up inside the kiosk" || ko "remote 'open app' brings Chrome up" "top=$(top)"
  post "$A/devices/$DEV/commands" '{"type":"device.appLaunch","requiresCapability":"device.appLaunch","payload":"{\"packageName\":\"com.android.vending\"}"}' >/dev/null
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null
  sleep 15
  chk "an app the kiosk does not allow is refused" "$(get "$A/devices/$DEV/commands" | field "next((c['status']+':'+(c.get('detail') or '')) for c in d['data'] if c['type']=='device.appLaunch')")" "failed:not allowed by the kiosk"
  adb_ shell input keyevent KEYCODE_HOME
fi

# ================================================================================================ R9 browser
# tap_text "<text>": tap the first on-screen node whose text/description contains it; false when absent.
tap_text(){
  local b; b=$(ui | python3 -c "
import sys,re
x=sys.stdin.read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    t=(re.search(r'text=\"([^\"]*)\"',n) or [None,''])[1]+' '+(re.search(r'content-desc=\"([^\"]*)\"',n) or [None,''])[1]
    if '''$1'''.lower() in t.lower():
        b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n)
        if b: print((int(b[1])+int(b[3]))//2,(int(b[2])+int(b[4]))//2); break")
  [ -n "$b" ] && adb_ shell input tap $b && sleep 2
}
chrome_open(){ adb_ shell am start -a android.intent.action.VIEW -d "$1" -p com.android.chrome >/dev/null 2>&1; sleep 4
  for _ in 1 2 3 4 5 6; do tap_text "Use without an account" || tap_text "Accept & continue" || tap_text "No thanks" || tap_text "Got it" || tap_text "Continue" || break; done; sleep 2; }
if want browser; then
  echo "== managed browser: Chrome allowlist (R9)"
  chk "policy saved (Chrome: only example.com; kiosk allows the browser)" "$(set_policy '{"kioskRoles":["browser"],"browser":{"mode":"allowlist","allow":["example.com"]}}')" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision" "$(revision)"
  chk "browser outcome" "$(outcome browser)" "applied"
  chrome_open "https://en.wikipedia.org/wiki/Colombia"
  u=$(ui); printf '%s' "$u" | grep -qiE "ERR_BLOCKED_BY_ADMINISTRATOR|blocked by (your )?administrator|administrator has blocked" \
    && ok "a site outside the list is blocked in Chrome" || { ko "a site outside the list is blocked in Chrome" "$(printf '%s' "$u" | grep -oE 'text="[^"]{3,60}"' | head -5 | tr '\n' ' ')"; adb_ exec-out screencap -p > /tmp/dc-browser-fail.png; }
  chrome_open "https://example.com/"
  u=$(ui); printf '%s' "$u" | grep -q "Example Domain" && ok "an allowed site loads" || ko "an allowed site loads" "$(printf '%s' "$u" | grep -oE 'text="[^"]{3,60}"' | head -5 | tr '\n' ' ')"
  # (the policy itself is proven by Chrome's behaviour: blocked vs. loaded pages in both modes)
  echo "   blocklist mode"
  chk "policy saved (Chrome: all but wikipedia.org)" "$(set_policy '{"kioskRoles":["browser"],"browser":{"mode":"blocklist","block":["wikipedia.org"]}}')" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision"
  sleep 3
  chrome_open "https://en.wikipedia.org/wiki/Ecuador"
  printf '%s' "$(ui)" | grep -qiE "ERR_BLOCKED_BY_ADMINISTRATOR|blocked by (your )?administrator|administrator has blocked" && ok "the blocked site is blocked" || ko "the blocked site is blocked"
  chrome_open "https://example.com/"
  printf '%s' "$(ui)" | grep -q "Example Domain" && ok "other sites load" || ko "other sites load"
  adb_ shell input keyevent KEYCODE_HOME
fi

# ================================================================================================ R4 app policy
pkg_flag(){ adb_ shell dumpsys package "$1" 2>/dev/null | grep -m1 -oE "$2=(true|false)" | cut -d= -f2; }
if want apps; then
  echo "== app policy: only allowed apps; installed outside the list = paused (R4)"
  TESTAPK="$(dirname "$0")/testapp/testapp-v1.apk"
  TPKG=$(aapt2 dump badging "$TESTAPK" 2>/dev/null | sed -n "s/package: name='\([^']*\)'.*/\1/p")
  [ -n "$TPKG" ] || TPKG=$(~/Library/Android/sdk/build-tools/35.0.0/aapt2 dump badging "$TESTAPK" | sed -n "s/package: name='\([^']*\)'.*/\1/p")
  adb_ uninstall "$TPKG" >/dev/null 2>&1
  T0=$(( $(date +%s) * 1000 ))
  chk "policy saved (allowlist, hide Play Store)" "$(set_policy '{"apps":{"mode":"allowlist","hidePlayStore":true}}')" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision"
  chk "apps outcome" "$(outcome apps)" "applied"
  chk "Play Store hidden" "$(pkg_flag com.android.vending hidden)" "true"
  chk "the kiosk app (configuration app) is not paused" "$(pkg_flag co.amovil.preventa suspended)" "false"
  adb_ install -r "$TESTAPK" >/dev/null
  wait_for 30 bash -c "adb -s $SERIAL shell dumpsys package $TPKG | grep -q 'suspended=true'" \
    && ok "an app installed outside the list is paused within seconds ($TPKG)" || ko "a newly installed app is paused" "$(pkg_flag "$TPKG" suspended)"
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null; sleep 12
  printf '%s' "$(events_since "$T0")" | grep -q "appBlocked:$TPKG" && ok "the console timeline shows appBlocked" || ko "timeline shows appBlocked" "$(events_since "$T0")"
  chk "policy saved (the test app allowed, store visible)" "$(set_policy "{\"apps\":{\"mode\":\"allowlist\",\"allowed\":[\"$TPKG\"]}}")" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision"
  chk "allowed now: unpaused" "$(pkg_flag "$TPKG" suspended)" "false"
  chk "Play Store visible again" "$(pkg_flag com.android.vending hidden)" "false"
  chk "policy saved (app policy removed)" "$(set_policy "")" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision"
  adb_ install -r "$TESTAPK" >/dev/null; sleep 5
  chk "without an app policy nothing is paused" "$(pkg_flag "$TPKG" suspended)" "false"
  adb_ uninstall "$TPKG" >/dev/null 2>&1
fi

# ================================================================================================ R6 location trail
fixes_since(){ get "$A/devices/$DEV/locations?since=$1&limit=500" | python3 -c "
import sys,json; d=json.load(sys.stdin)['data']; print(len(d), ' '.join('%.4f,%.4f' % (f['lat'], f['lon']) for f in sorted(d, key=lambda f: f['capturedAt'])))"; }
# walk <seconds> <start lat> <start lon>: move the emulator's GPS a little every 10 s (a phone on the road).
walk(){ local end=$(( $(date +%s) + $1 )) lat=$2 lon=$3
  while [ "$(date +%s)" -lt "$end" ]; do adb -s "$SERIAL" emu geo fix "$lon" "$lat" >/dev/null; lat=$(python3 -c "print(round($lat+0.0012,6))"); sleep 10; done; }
if want trail; then
  echo "== location trail every minute, kept while offline (R6)"
  chk "policy saved (trackingMinutes 1)" "$(set_policy '{"trackingMinutes":1}')" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision"
  chk "tracking outcome" "$(outcome tracking)" "applied"
  T0=$(( $(date +%s) * 1000 ))
  walk 200 4.6097 -74.0817
  read -r n coords <<<"$(fixes_since "$T0")"
  [ "${n:-0}" -ge 3 ] && ok "online: $n points in ~3 minutes" || ko "online: at least 3 points in ~3 minutes" "$n"
  [ "$(printf '%s' "$coords" | tr ' ' '\n' | sort -u | wc -l | tr -d ' ')" -ge 2 ] && ok "points follow the movement" || ko "points follow the movement" "$coords"
  echo "   offline for ~3 minutes"
  adb_ shell svc wifi disable; adb_ shell svc data disable
  T1=$(( $(date +%s) * 1000 ))
  walk 190 4.7000 -74.0500
  read -r n_off _ <<<"$(fixes_since "$T1")"
  chk "nothing arrives while offline" "${n_off:-0}" "0"
  adb_ shell svc wifi enable; adb_ shell svc data enable
  T2=$(( $(date +%s) * 1000 ))
  wait_for 120 bash -c "curl -s -b '$CJ' '$A/devices/$DEV/locations?since=$T1&limit=500' | python3 -c \"import sys,json; d=json.load(sys.stdin)['data']; sys.exit(0 if sum(1 for f in d if f['capturedAt'] < $T2) >= 2 else 1)\""
  n_back=$(get "$A/devices/$DEV/locations?since=$T1&limit=500" | python3 -c "import sys,json; print(sum(1 for f in json.load(sys.stdin)['data'] if f['capturedAt'] < $T2))")
  [ "${n_back:-0}" -ge 2 ] && ok "after reconnecting, the $n_back points captured offline are uploaded with their times" || ko "points captured offline are uploaded" "$n_back"
  chk "policy saved (no trail)" "$(set_policy "")" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision"
fi

# ================================================================================================ R5 SIM
sim_state(){ get "$A/devices/$DEV/telemetry" | python3 -c "
import sys,json; t=json.load(sys.stdin)['data'] or {}; s=(t.get('dynamic') or {}).get('sim') or {}
print(s.get('state','')+'|'+','.join((x.get('carrier') or '')+':'+(x.get('number') or '') for x in s.get('slots',[])))"; }
if want sim; then
  echo "== SIM: number visible, swap and removal raise alerts (R5)"
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null; sleep 10
  S0=$(sim_state)
  printf '%s' "$S0" | grep -q '^ready|' && ok "SIM state reported ($S0)" || ko "SIM state reported" "$S0"
  printf '%s' "$S0" | grep -qE ':\+?[0-9]{6,}' && ok "phone number reported" || ko "phone number reported" "$S0"
  T0=$(( $(date +%s) * 1000 ))
  NEWNUM="1555$(printf '%06d' $((RANDOM*RANDOM % 1000000)))"
  adb -s "$SERIAL" emu phonenumber "$NEWNUM" >/dev/null
  sleep 8; curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null
  wait_for 90 bash -c "curl -s -b '$CJ' '$A/devices/$DEV/events?since=$T0&limit=200' | grep -q simChanged" \
    && ok "a different SIM number raises simChanged ($(events_since "$T0" | grep -o 'simChanged:[^ ]*[^s]*' | head -1))" || ko "a different SIM number raises simChanged" "$(events_since "$T0")"
  printf '%s' "$(sim_state)" | grep -q "$NEWNUM" && ok "the console shows the new number" || ko "the console shows the new number" "$(sim_state)"
  T1=$(( $(date +%s) * 1000 ))
  adb_ shell cmd phone restart-modem >/dev/null 2>&1
  sleep 25; curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null; sleep 10
  printf '%s' "$(events_since "$T1")" | grep -qE 'simRemoved|simInserted' \
    && ko "a modem restart is not reported as a removed SIM" "$(events_since "$T1")" || ok "a modem restart is not reported as a removed SIM"
fi

# ================================================================================================ R7 passcode
if want passcode; then
  echo "== change the phone's lock-screen password remotely (R7)"
  q(){ post "$A/devices/$DEV/commands" "{\"type\":\"device.passcodeReset\",\"requiresCapability\":\"device.passcodeReset\",\"payload\":\"{\\\"newPassword\\\":\\\"$1\\\"}\"}" >/dev/null; curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null; }
  last_pc(){ get "$A/devices/$DEV/commands" | field "next(c['status'] for c in d['data'] if c['type']=='device.passcodeReset')"; }
  q 2468
  wait_for 60 bash -c "[ \"\$(curl -s -b '$CJ' '$A/devices/$DEV/commands' | python3 -c \"import sys,json; print(next(c['status'] for c in json.load(sys.stdin)['data'] if c['type']=='device.passcodeReset'))\")\" = done ]" \
    && ok "passcode set to 2468 (command done)" || ko "passcode set (command done)" "$(last_pc)"
  adb_ shell locksettings verify --old 2468 2>&1 | grep -qi "verified successfully\|Lock credential verified" && ok "the phone now requires 2468" || ko "the phone requires 2468" "$(adb_ shell locksettings verify --old 2468 2>&1 | head -1)"
  adb_ shell locksettings verify --old 1111 2>&1 | grep -qi "verified successfully" && ko "a wrong PIN is refused" || ok "a wrong PIN is refused"
  q ""
  sleep 20
  adb_ shell locksettings verify 2>&1 | grep -qi "verified successfully\|Lock credential verified" && ok "passcode cleared remotely" || ko "passcode cleared remotely" "$(adb_ shell locksettings verify 2>&1 | head -1)"
fi

# ================================================================================================ R8 open app / ring / script
if want launch; then
  echo "== scripts: open an app, ring (R8)"
  qc(){ post "$A/devices/$DEV/commands" "$1" | field "d['data']['id']"; }
  st(){ get "$A/devices/$DEV/commands" | field "next(c['status']+':'+(c.get('detail') or '') for c in d['data'] if c['id']==$1)"; }
  adb_ shell input keyevent KEYCODE_HOME; sleep 2
  R=$(qc '{"type":"device.ring","requiresCapability":"device.ring","payload":"{\"durationMs\":8000}"}')
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null
  wait_for 40 bash -c "adb -s $SERIAL shell dumpsys audio | grep -q 'usage=USAGE_ALARM.*state:started\|state:started.*USAGE_ALARM'" \
    && ok "the phone rings (an alarm-usage player is playing)" || ko "the phone rings" "$(st "$R")"
  chk "ring command done" "$(st "$R" | cut -d: -f1)" "done"
  L=$(qc '{"type":"device.appLaunch","requiresCapability":"device.appLaunch","payload":"{\"packageName\":\"co.amovil.preventa\"}"}')
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null
  wait_for 40 bash -c "[ \"\$(adb -s $SERIAL shell dumpsys activity activities | sed -n 's/.*topResumedActivity=.* u0 \([^ /]*\).*/\1/p' | head -1)\" = co.amovil.preventa ]" \
    && ok "open app brings Preventa to the front" || ko "open app brings Preventa up" "top=$(top) $(st "$L")"
  M=$(qc '{"type":"device.appLaunch","requiresCapability":"device.appLaunch","payload":"{\"packageName\":\"com.example.not.installed\"}"}')
  curl -s -b "$CJ" -X POST "$A/devices/$DEV/sync" >/dev/null; sleep 12
  chk "an app that is not installed is reported" "$(st "$M")" "failed:not installed or has no launcher entry"
fi

# ================================================================================================ R11 kiosk crash-loop guard
if want crashloop; then
  echo "== kiosk: pressing Back repeatedly never leaves the kiosk (R11)"
  chk "policy saved (single-app kiosk)" "$(set_policy "")" "OK"
  wait_applied 120 && ok "device applied the new revision" || ko "device applied the new revision"
  adb_ shell monkey -p co.amovil.preventa -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  for i in 1 2 3 4 5 6; do adb_ shell input keyevent KEYCODE_BACK; sleep 2.5; done
  sleep 2
  chk "still in kiosk after 6x Back" "$(locktask)" "LOCKED"
  u=$(ui)
  printf '%s' "$u" | grep -q 'content-desc="kiosk-open-app"' && ok "the kiosk shows the locked 'Open Amovil Preventa' screen" || ko "paused screen shown" "top=$(top)"
  tap_text "kiosk-open-app"; sleep 5
  chk "one tap reopens the app" "$(top)" "co.amovil.preventa"
  chk "and the kiosk is still locked" "$(locktask)" "LOCKED"
fi

echo "===== RESULT: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" -eq 0 ]
