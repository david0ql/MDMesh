#!/usr/bin/env bash
#
# Security regression checks against a running DallyControl (dev stack or a staging copy — never a fleet you care
# about: it creates and deletes one temporary read-only user, and locks that user's login on purpose).
#
#   ADMIN_PW=<admin password> scripts/security-check.sh [BASE_URL] [SERVER_URL]
#     BASE_URL    the edge (Caddy), default http://localhost:8088
#     SERVER_URL  optional: Tomcat directly (dev: http://localhost:8080), to check the server-side allowlist too
#
# Every probe is harmless: GETs, bogus ids, or requests the server must refuse. Exit 0 only if all checks pass.
set -uo pipefail

BASE="${1:-http://localhost:8088}"; BASE="${BASE%/}"
SERVER="${2:-}"; SERVER="${SERVER%/}"
CJ=$(mktemp); OJ=$(mktemp); TMP=$(mktemp -d)
PASS=0; FAIL=0; OID=""
md5u(){ if command -v md5sum >/dev/null 2>&1; then printf '%s' "$1" | md5sum | awk '{print toupper($1)}'; else printf '%s' "$1" | md5 | tr 'a-f' 'A-F'; fi; }
cleanup(){ [ -z "$OID" ] || curl -s -b "$CJ" -X DELETE "$BASE/rest/private/users/other/$OID" >/dev/null || true; rm -rf "$CJ" "$OJ" "$TMP"; }
trap cleanup EXIT
ok(){ echo "  PASS  $1"; PASS=$((PASS+1)); }
ko(){ echo "  FAIL  $1${2:+  [$2]}"; FAIL=$((FAIL+1)); }
chk(){ if [ "$2" = "$3" ]; then ok "$1"; else ko "$1" "got '$2', want '$3'"; fi; }
code(){ curl -s -o /dev/null -w '%{http_code}' "$@"; }
field(){ python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

echo "== edge: security headers"
H=$(curl -sI "$BASE/")
for h in 'content-security-policy:' 'x-frame-options: sameorigin' 'x-content-type-options: nosniff' 'strict-transport-security:' 'referrer-policy:'; do
  if printf '%s' "$H" | tr 'A-Z' 'a-z' | grep -q "^$h"; then ok "header ${h%:}"; else ko "header ${h%:}"; fi
done
printf '%s' "$H" | tr 'A-Z' 'a-z' | grep -q '^server:' && ko "no Server header" || ok "no Server header"
printf '%s' "$H" | grep -i '^content-security-policy:' | grep -q "script-src 'self';" && ok "CSP forbids inline scripts" || ko "CSP forbids inline scripts"

echo "== unauthenticated surface: legacy endpoints closed (edge)"
for p in /rest/public/sync/configuration/sec-probe /rest/notifications/device/sec-probe \
         /rest/plugins/messaging/public/status/1/1 /rest/plugins/devicelog/log/rules/sec-probe \
         /rest/swagger.json /rest/swagger.yaml /rest/public/qr/sec-probe /rest/public/%73ync/configuration/sec-probe \
         /rest/videos/sec-probe /api/sec-probe; do
  chk "GET $p -> 404" "$(code "$BASE$p")" "404"
done
chk "PUT /rest/public/stats -> 404" "$(code -X PUT -H 'Content-Type: application/json' -d '{}' "$BASE/rest/public/stats")" "404"
chk "POST /rest/public/sync/info -> 404" "$(code -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/rest/public/sync/info")" "404"
if [ -n "$SERVER" ]; then
  echo "== unauthenticated surface: legacy endpoints closed (server, behind the edge)"
  for p in /rest/public/sync/configuration/sec-probe /rest/notifications/device/sec-probe /rest/swagger.json \
           /rest/public/%73ync/configuration/sec-probe "/rest/public/agent/v1/%2e%2e/%2e%2e/sync/info"; do
    chk "server GET $p -> 404" "$(code "$SERVER$p")" "404"
  done
fi

echo "== surface that must stay reachable"
chk "GET /rest/public/name -> 200" "$(code "$BASE/rest/public/name")" "200"
chk "tunnel gate without secret -> 401" "$(code "$BASE/rest/public/agent/v1/remote/tunnel")" "401"
chk "private API without session -> 403" "$(code "$BASE/rest/private/devices/search" -X POST -H 'Content-Type: application/json' -d '{}')" "403"
chk "remote viewer without session -> 403" "$(code "$BASE/remote/vnc/vnc.html")" "403"
body=$(curl -s "$BASE/rest/private/sec-probe-nonexistent")
printf '%s' "$body" | grep -qi 'apache tomcat' && ko "error pages hide the server version" || ok "error pages hide the server version"
chk "no CORS for other origins" "$(curl -sI -H 'Origin: https://evil.example' "$BASE/rest/public/name" | grep -ci '^access-control-allow-origin')" "0"

echo "== login and session"
MD5=$(md5u "${ADMIN_PW:-admin}")
curl -s -c "$TMP/pre" -o /dev/null "$BASE/rest/public/auth/options"
printf '%s\tFALSE\t/\tFALSE\t0\tJSESSIONID\tFIXEDSESSIONID0123456789ABCDEF\n' "$(printf '%s' "$BASE" | sed -E 's#^https?://##; s#:.*##; s#/.*##')" > "$CJ"
RESP=$(curl -s -D "$TMP/h" -b "$CJ" -c "$CJ" -H 'Content-Type: application/json' -d "{\"login\":\"admin\",\"password\":\"$MD5\"}" "$BASE/rest/public/auth/login")
chk "admin login" "$(printf '%s' "$RESP" | field "d['status']")" "OK"
SC=$(grep -i '^set-cookie: JSESSIONID' "$TMP/h" | head -1)
[ -n "$SC" ] && ok "login issues a new session id" || ko "login issues a new session id"
printf '%s' "$SC" | grep -q FIXEDSESSIONID && ko "a fixed session id is not kept" || ok "a fixed session id is not kept"
printf '%s' "$SC" | grep -qi 'httponly' && ok "cookie HttpOnly" || ko "cookie HttpOnly" "$SC"
printf '%s' "$SC" | grep -qi 'samesite=lax' && ok "cookie SameSite=Lax" || ko "cookie SameSite=Lax" "$SC"
case "$BASE" in https://*) printf '%s' "$SC" | grep -qi '; secure' && ok "cookie Secure (https)" || ko "cookie Secure (https)" "$SC" ;; esac

echo "== a read-only user (Observer) cannot escalate"
OLOGIN="sec-obs-$RANDOM$RANDOM"; OPW=$(md5u "$OLOGIN-pw")
curl -s -b "$CJ" -X PUT -H 'Content-Type: application/json' \
  -d "{\"login\":\"$OLOGIN\",\"name\":\"$OLOGIN\",\"email\":\"$OLOGIN@sec.invalid\",\"userRole\":{\"id\":100},\"newPassword\":\"$OPW\",\"allDevicesAvailable\":true,\"allConfigAvailable\":true}" \
  "$BASE/rest/private/users" >/dev/null
OID=$(curl -s -b "$CJ" "$BASE/rest/private/users/all?filter=$OLOGIN" | field "next((u['id'] for u in d['data'] if u['login']=='$OLOGIN'), '')")
[ -n "$OID" ] && ok "temporary Observer created" || ko "temporary Observer created"
chk "Observer login" "$(curl -s -c "$OJ" -H 'Content-Type: application/json' -d "{\"login\":\"$OLOGIN\",\"password\":\"$OPW\"}" "$BASE/rest/public/auth/login" | field "d['status']")" "OK"
ADMIN_ID=$(curl -s -b "$CJ" "$BASE/rest/private/users/current" | field "d['data']['id']")
chk "Observer cannot change another user's email" \
  "$(curl -s -b "$OJ" -X PUT -H 'Content-Type: application/json' -d "{\"id\":$ADMIN_ID,\"name\":\"x\",\"email\":\"x@sec.invalid\"}" "$BASE/rest/private/users/details" | field "d['status']")" "ERROR"
echo sec > "$TMP/f.txt"
chk "Observer cannot upload configuration files" \
  "$(curl -s -b "$OJ" -F "file=@$TMP/f.txt;filename=sec-probe.txt" "$BASE/rest/private/config-files" | field "d['status']")" "ERROR"
chk "admin: a configuration file name with a path is refused" \
  "$(curl -s -b "$CJ" -F "file=@$TMP/f.txt;filename=../sec-probe.txt" "$BASE/rest/private/config-files" | field "d['message']")" "error.file.name.invalid"
chk "legacy server self-update endpoint is gone" "$(code -b "$CJ" -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/rest/private/update")" "404"

echo "== login brute force: the account locks"
for i in 1 2 3 4 5; do curl -s -o /dev/null -H 'Content-Type: application/json' -d "{\"login\":\"$OLOGIN\",\"password\":\"WRONG\"}" "$BASE/rest/public/auth/login"; done
chk "correct password refused while locked" \
  "$(curl -s -H 'Content-Type: application/json' -d "{\"login\":\"$OLOGIN\",\"password\":\"$OPW\"}" "$BASE/rest/public/auth/login" | field "d['status']")" "ERROR"

echo "===== RESULT: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" -eq 0 ]
