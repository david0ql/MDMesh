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
