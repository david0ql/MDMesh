#!/usr/bin/env bash
# Deploy an update of an EXISTING DallyControl installation without a perceptible cut, with automatic rollback.
# (First install, or a reset: ./setup.sh.) Run on the server, from the project directory, after `git pull`.
# Documentation and measured timings: docs/ops/DESPLIEGUE.md.
#
#   scripts/deploy.sh               build -> backup -> relay the server -> update the web -> verify
#   scripts/deploy.sh --rollback    put back the images of before the last deployment (same relay, no cut)
#
# Options:
#   --no-build     use the images that already exist (they were built or pulled before)
#   --no-backup    skip the pre-deployment database backup (NOT recommended)
#   --skip-web     do not touch the web/edge (Caddy)
#   --tunnel       also update the cloudflared connectors, one at a time (needs the replica to avoid a cut)
#   --classic      recreate the server in place instead of using the relay (there IS a cut: one server start)
#   -y, --yes      do not ask for confirmation
#
# What it does, and why there is no cut:
#   1. Builds (or pulls) the new images BEFORE touching anything: a build that fails changes nothing.
#   2. Backs the database up (verified dump) to the backups volume: /backups/predeploy.
#   3. Server: starts a relay instance (`server-b`) with the NEW image next to the running one and waits until it is
#      healthy (database included). Only then the old instance is marked "draining" (Caddy stops sending it
#      requests), recreated with the new image, and the relay is drained and removed. Caddy is never restarted: it
#      follows the health of both instances by itself. If the new image never gets healthy, the old instance was
#      never touched: users notice nothing and the script exits with an error.
#   4. Web: the new console files (and the Caddyfile, if it changed: graceful reload) are copied into the running
#      Caddy container. Caddy is only recreated when the Caddy binary or its service definition changed.
#   5. Verifies health inside and, when BASE_URL answers from here, from outside. On failure it rolls back.
#
# Environment: DEPLOY_HEALTH_TIMEOUT=300 (seconds to wait for an instance), DEPLOY_DRAIN_SECONDS=6,
#   DEPLOY_PUBLIC_CHECK=1 (0 = do not probe BASE_URL at the end), DEPLOY_BUMP_VERSION=1.
set -euo pipefail
cd "$(dirname "$0")/.."

say()  { printf '\033[1;36m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\033[1;33m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*"; }
err()  { printf '\033[1;31m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*" >&2; }

BUILD=1; BACKUP=1; WEB=1; TUNNEL=0; CLASSIC=0; YES=0; ROLLBACK=0
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    --no-backup) BACKUP=0 ;;
    --skip-web) WEB=0 ;;
    --tunnel) TUNNEL=1 ;;
    --classic) CLASSIC=1 ;;
    --rollback) ROLLBACK=1 ;;
    -y|--yes) YES=1 ;;
    -h|--help) sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) err "Opción desconocida: $a"; exit 2 ;;
  esac
done

HEALTH_TIMEOUT="${DEPLOY_HEALTH_TIMEOUT:-300}"
DRAIN_SECONDS="${DEPLOY_DRAIN_SECONDS:-6}"
STATE_DIR=".deploy-state"

command -v docker >/dev/null || { err "Se necesita Docker."; exit 1; }
[ -f .env ] || { err "No hay .env: esto actualiza una instalación existente. Para instalar: ./setup.sh"; exit 1; }
set -a; . ./.env; set +a
mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"

# compose reads COMPOSE_FILE / COMPOSE_PROFILES / COMPOSE_PROJECT_NAME from .env; the relay and the ops image need
# their profiles on top of the configured ones.
BASE_PROFILES="${COMPOSE_PROFILES:-}"
dc()  { COMPOSE_PROFILES="$BASE_PROFILES" docker compose "$@"; }
dcx() { COMPOSE_PROFILES="${BASE_PROFILES:+$BASE_PROFILES,}relay,backup" docker compose "$@"; }

# Update KEY=VALUE in .env in place (same inode and permissions).
setenv() {
  if grep -q "^$1=" .env; then
    awk -v k="$1" -v v="$2" 'index($0, k "=") == 1 { print k "=" v; next } { print }' .env > "$STATE_DIR/env.tmp"
    cat "$STATE_DIR/env.tmp" > .env; rm -f "$STATE_DIR/env.tmp"
  else
    printf '%s=%s\n' "$1" "$2" >> .env
  fi
  export "$1=$2"
}
cid()        { dcx ps -aq "$1" 2>/dev/null | head -1; }
health()     { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || echo missing; }
image_id()   { docker inspect -f '{{.Image}}' "$1" 2>/dev/null || true; }
image_ref()  { docker inspect -f '{{.Config.Image}}' "$1" 2>/dev/null || true; }
# wait_healthy <service> : 0 when the service's container reports healthy within the timeout.
wait_healthy() {
  local svc="$1" deadline=$(( $(date +%s) + HEALTH_TIMEOUT )) c st
  while [ "$(date +%s)" -lt "$deadline" ]; do
    c=$(cid "$svc"); st=$(health "$c")
    # The server reports a failed start in its log and then stays up, unhealthy: do not wait the whole timeout.
    if [ -n "$c" ] && docker logs --tail 400 "$c" 2>&1 | grep -q 'Application initialization has failed'; then return 1; fi
    case "$st" in
      healthy) return 0 ;;
      exited|dead|missing) sleep 2; c=$(cid "$svc"); st=$(health "$c"); case "$st" in exited|dead|missing) return 1 ;; esac ;;
    esac
    sleep 2
  done
  return 1
}
drain() {   # the instance answers "draining" (503) to health checks: Caddy stops routing to it within ~4 s
  docker exec "$(cid "$1")" touch /tmp/dallycontrol.drain 2>/dev/null || true
  sleep "$DRAIN_SECONDS"
}
enabled() { dc config --services 2>/dev/null | grep -qx "$1"; }
# svc_image <service>: the image reference compose resolves for that service.
svc_image() {
  dc config 2>/dev/null | awk -v s="  $1:" '$0 == s { f = 1; next } f && /^  [^ ]/ { f = 0 } f && $1 == "image:" { print $2; exit }'
}
# steady <service>: the instance answers its health probe quickly several times in a row (a container can be
# "healthy" and still be too slow to carry the traffic alone on a busy host).
steady() {
  local c ok=0 i
  c=$(cid "$1")
  for i in $(seq 1 30); do
    if [ "$(docker exec "$c" curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:8080/rest/public/health 2>/dev/null)" = 200 ]; then
      ok=$((ok + 1)); [ "$ok" -ge 5 ] && return 0
    else ok=0; fi
    sleep 1
  done
  return 1
}

SERVER_C=$(cid server)
[ -n "$SERVER_C" ] || { err "No hay contenedor del servidor: use ./setup.sh para instalar."; exit 1; }
CADDY_C=$(cid caddy)

# ---- rollback mode: the previous images become "the new ones" and go through the same relay --------------------------
if [ "$ROLLBACK" = 1 ]; then
  [ -f "$STATE_DIR/previous" ] || { err "No hay un despliegue anterior registrado en $STATE_DIR."; exit 1; }
  # shellcheck disable=SC1091
  . "$STATE_DIR/previous"
  say "Vuelta atrás a las imágenes de antes del despliegue de ${PREV_AT:-?} (la base de datos NO se toca; para eso: scripts/restore-backup.sh)."
  [ -f "$STATE_DIR/env.previous" ] && cat "$STATE_DIR/env.previous" > .env
  set -a; . ./.env; set +a
  BASE_PROFILES="${COMPOSE_PROFILES:-}"
  docker tag "$PREV_SERVER_ID" "$PREV_SERVER_REF"
  [ -z "${PREV_WEB_ID:-}" ] || docker tag "$PREV_WEB_ID" "$PREV_WEB_REF"
  BUILD=0; BACKUP=0
fi

# ---- 0. preflight ---------------------------------------------------------------------------------------------------
say "== Despliegue de DallyControl (${COMPOSE_PROJECT_NAME:-dallycontrol}) =="
OLD_SERVER_ID=$(image_id "$SERVER_C"); OLD_SERVER_REF=$(image_ref "$SERVER_C")
OLD_WEB_ID=""; OLD_WEB_REF=""
if [ -n "$CADDY_C" ]; then
  OLD_WEB_REF=$(image_ref "$CADDY_C")
  # After an in-place web update the container still reports the image it was created from: the content it really
  # serves is recorded inside it.
  OLD_WEB_ID=$(docker exec "$CADDY_C" cat /etc/caddy/.web-image-id 2>/dev/null || true)
  [ -n "$OLD_WEB_ID" ] || OLD_WEB_ID=$(image_id "$CADDY_C")
fi
SERVER_STATE=$(health "$SERVER_C")

# The relay needs an edge that already knows both upstreams and a running server with the deep health endpoint.
RELAY=1
if [ "$CLASSIC" = 1 ]; then RELAY=0
elif [ -z "$CADDY_C" ] || ! docker exec "$CADDY_C" grep -q 'server-b:8080' /etc/caddy/Caddyfile 2>/dev/null; then
  RELAY=0; warn "El borde en ejecución todavía no conoce la instancia de relevo (primera actualización con este mecanismo)."
elif [ "$SERVER_STATE" != healthy ]; then
  RELAY=0; warn "El servidor actual no está saludable ($SERVER_STATE): no hay nada que relevar."
fi
if [ "$RELAY" = 0 ]; then
  warn "Este despliegue recrea el servidor en su sitio: HABRÁ un corte de un arranque del servidor (la consola muestra la página de recuperación; los equipos reintentan solos). Los siguientes ya no lo tendrán."
fi
USED=$(df -P . | awk 'NR==2 { gsub("%", "", $5); print $5 }')
[ "${USED:-0}" -lt 90 ] || warn "El disco está al ${USED} %: la construcción de imágenes puede fallar. Libere espacio (docker image prune) antes de seguir."
if [ "$YES" != 1 ] && [ -t 0 ]; then
  read -rp "¿Continuar? [s/N] " ans; case "$ans" in s|S|si|sí|y|Y) ;; *) echo "Cancelado."; exit 0 ;; esac
fi
T_START=$(date +%s)

# What we are replacing, for --rollback (and so `docker image prune` never deletes the way back).
if [ "$ROLLBACK" != 1 ]; then
  ( umask 077; cp .env "$STATE_DIR/env.previous" )
  docker tag "$OLD_SERVER_ID" "dallycontrol-rollback/server:previous" 2>/dev/null || true
  [ -z "$OLD_WEB_ID" ] || docker tag "$OLD_WEB_ID" "dallycontrol-rollback/web:previous" 2>/dev/null || true
  {
    printf 'PREV_AT=%q\n' "$(date '+%Y-%m-%d %H:%M:%S')"
    printf 'PREV_SERVER_ID=%q\nPREV_SERVER_REF=%q\n' "$OLD_SERVER_ID" "$OLD_SERVER_REF"
    printf 'PREV_WEB_ID=%q\nPREV_WEB_REF=%q\n' "$OLD_WEB_ID" "$OLD_WEB_REF"
  } > "$STATE_DIR/previous"
fi

# Puts the previous server image and .env back and recreates `server` with it. Used when the new one fails.
restore_previous_server() {
  [ "$ROLLBACK" = 1 ] || cat "$STATE_DIR/env.previous" > .env
  set -a; . ./.env; set +a
  docker tag "$OLD_SERVER_ID" "$OLD_SERVER_REF" || true
}

# ---- 1. build / pull (nothing running is touched) -------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
  if [ "${IMAGE_OWNER:-local}" = local ]; then
    # shellcheck source=install/lib/version.sh
    . ./install/lib/version.sh
    REPO_VERSION=$(mdm_repo_version .)
    # Same rule as setup.sh: the images are named after the checkout's release tag. DEPLOY_BUMP_VERSION=0 keeps the
    # tags of .env (test stacks that must not share image names with another installation on the host).
    if [ -n "$REPO_VERSION" ] && [ "${DEPLOY_BUMP_VERSION:-1}" = 1 ]; then
      for k in CURRENT_VERSION SERVER_VERSION WEB_VERSION SUPERVISOR_VERSION; do setenv "$k" "$REPO_VERSION"; done
    fi
    say "1/5 Construyendo las imágenes nuevas (el servicio sigue con las actuales)…"
    T0=$(date +%s)
    # One image at a time: building everything in parallel can starve the running service on a small or shared
    # host (measured: a 25 s stall of the whole Docker host during a parallel build). Slower, but the service keeps
    # its CPU and disk. Building elsewhere and pulling (a registry IMAGE_OWNER) avoids the load altogether.
    for img in server caddy supervisor backup; do
      if ! dcx build "$img"; then
        err "La construcción de '$img' falló. No se cambió nada: el servicio sigue con la versión anterior."
        cat "$STATE_DIR/env.previous" > .env; exit 1
      fi
    done
    say "    imágenes construidas en $(( $(date +%s) - T0 )) s"
  else
    say "1/5 Descargando las imágenes publicadas…"
    dc pull server caddy supervisor || { err "La descarga falló. No se cambió nada."; exit 1; }
  fi
else
  say "1/5 Sin construcción (--no-build): se usan las imágenes existentes."
fi

# ---- 2. backup ------------------------------------------------------------------------------------------------------
if [ "$BACKUP" = 1 ]; then
  say "2/5 Respaldo previo de la base de datos (verificado) en el volumen de respaldos: /backups/predeploy…"
  T0=$(date +%s)
  if ! dcx run --rm --no-deps -T -e BACKUP_DIR=/backups/predeploy -e BACKUP_FILES=0 -e BACKUP_VERIFY_RESTORE=never \
        -e BACKUP_KEEP_DAILY=10 -e BACKUP_KEEP_WEEKLY=0 -e BACKUP_KEEP_MONTHLY=0 \
        -e BACKUP_S3_BUCKET= -e BACKUP_RCLONE_REMOTE= -e BACKUP_RSYNC_TARGET= backup /ops/backup.sh; then
    err "El respaldo previo falló. No se cambió nada (use --no-backup solo si acepta desplegar sin respaldo)."
    [ "$ROLLBACK" = 1 ] || cat "$STATE_DIR/env.previous" > .env
    exit 1
  fi
  say "    respaldo hecho en $(( $(date +%s) - T0 )) s"
else
  warn "2/5 Sin respaldo previo (--no-backup)."
fi

# The relay also needs the TARGET image to have the deep health probe: the edge only routes to an instance that
# answers it. An image from before this mechanism (a rollback to it) gets the old procedure, in the old order.
WEB_FIRST=0
NEW_SERVER_REF=$(svc_image server)
HAS_HEALTH=$(docker run --rm --entrypoint sh "$NEW_SERVER_REF" -c 'command -v jar >/dev/null || { echo unknown; exit 0; }
  if jar tf /usr/local/tomcat/webapps/ROOT.war | grep -q "rest/resource/HealthResource.class"; then echo yes; else echo no; fi' 2>/dev/null || echo unknown)
if [ "$HAS_HEALTH" = no ]; then
  RELAY=0; WEB_FIRST=1
  warn "La imagen del servidor que se va a instalar es anterior al despliegue con relevo (no tiene la comprobación de salud profunda)."
  warn "Se instala a la manera clásica, primero el borde y después el servidor: HABRÁ un corte de un arranque del servidor."
fi

# ---- 3. server ------------------------------------------------------------------------------------------------------
update_server() {
  dcx rm -sf server-b >/dev/null 2>&1 || true   # a leftover relay of an interrupted run
  if [ "$RELAY" = 1 ]; then
    say "3/5 Servidor: arrancando la instancia de relevo con la imagen nueva…"
    T0=$(date +%s)
    dcx up -d --no-deps server-b >/dev/null 2>&1 || true
    if ! wait_healthy server-b; then
      err "La versión nueva NO arrancó saludable (falló al iniciar, o pasaron ${HEALTH_TIMEOUT} s). Últimas líneas de su registro:"
      dcx logs --tail 40 server-b 2>&1 | sed 's/^/    /' >&2 || true
      dcx rm -sf server-b >/dev/null 2>&1 || true
      restore_previous_server
      err "VUELTA ATRÁS AUTOMÁTICA: el servidor anterior nunca se tocó; los usuarios no notaron nada. Corrija y repita."
      exit 1
    fi
    if [ "$HAS_HEALTH" = yes ] && ! steady server-b; then
      err "El relevo arrancó pero responde con lentitud (¿servidor saturado?): no es seguro dejarle todo el tráfico."
      dcx rm -sf server-b >/dev/null 2>&1 || true
      restore_previous_server
      err "VUELTA ATRÁS AUTOMÁTICA: el servidor anterior nunca se tocó. Repita cuando el servidor tenga CPU libre, o use --classic en ventana."
      exit 1
    fi
    say "    relevo saludable y estable en $(( $(date +%s) - T0 )) s; drenando la instancia anterior…"
    drain server
    T0=$(date +%s)
    dcx up -d --no-deps --force-recreate server >/dev/null 2>&1 || true
    if ! wait_healthy server; then
      err "El servidor principal no quedó saludable con la imagen nueva; el relevo sigue atendiendo. Volviendo a la imagen anterior…"
      dcx logs --tail 40 server 2>&1 | sed 's/^/    /' >&2 || true
      restore_previous_server
      dcx up -d --no-deps --force-recreate server >/dev/null 2>&1 || true
      if wait_healthy server; then
        drain server-b; dcx rm -sf server-b >/dev/null 2>&1 || true
        err "VUELTA ATRÁS AUTOMÁTICA completada: el servicio sigue con la versión anterior."
      else
        err "La versión anterior tampoco arranca. El relevo (versión nueva) queda atendiendo. Intervención manual: docs/ops/RUNBOOK.md"
      fi
      exit 1
    fi
    [ "$HAS_HEALTH" != yes ] || steady server || warn "El servidor principal responde con lentitud; se retira el relevo igualmente (está saludable)."
    say "    servidor principal saludable en $(( $(date +%s) - T0 )) s; retirando el relevo…"
    drain server-b
    dcx rm -sf server-b >/dev/null 2>&1 || true
  else
    say "3/5 Servidor: recreando en su sitio (corte de un arranque)…"
    T0=$(date +%s)
    dcx up -d --no-deps --force-recreate server >/dev/null 2>&1 || true
    if ! wait_healthy server; then
      err "La versión nueva no quedó saludable (falló al iniciar, o pasaron ${HEALTH_TIMEOUT} s). Últimas líneas de su registro:"
      dcx logs --tail 40 server 2>&1 | sed 's/^/    /' >&2 || true
      restore_previous_server
      dcx up -d --no-deps --force-recreate server >/dev/null 2>&1 || true
      if wait_healthy server; then err "VUELTA ATRÁS AUTOMÁTICA completada: el servicio volvió a la versión anterior."
      else err "La versión anterior tampoco arranca. Intervención manual: docs/ops/RUNBOOK.md"; fi
      exit 1
    fi
    say "    servidor saludable tras $(( $(date +%s) - T0 )) s"
  fi
}

# ---- 4. web / edge --------------------------------------------------------------------------------------------------
WEB_MODE="sin cambios"
update_web() {
  rollback_web_recreate() {
    [ -z "$OLD_WEB_ID" ] || docker tag "$OLD_WEB_ID" "$OLD_WEB_REF" || true
    WEB_IMAGE="$OLD_WEB_REF" dcx up -d --no-deps --force-recreate caddy >/dev/null 2>&1 || true
  }
  if [ "$WEB" = 1 ] && [ -n "$CADDY_C" ]; then
    NEW_WEB_REF=$(svc_image caddy)
    NEW_WEB_ID=$(docker image inspect -f '{{.Id}}' "$NEW_WEB_REF" 2>/dev/null || true)
    if [ -z "$NEW_WEB_ID" ]; then
      warn "4/5 Web: no encuentro la imagen $NEW_WEB_REF; el borde no se toca."
    elif [ "$NEW_WEB_ID" = "$OLD_WEB_ID" ]; then
      say "4/5 Web: la imagen no cambió."
    else
      # In place when only the content changed: same Caddy binary, same entrypoint, same service definition.
      SAME_DEF=0
      LABEL=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$CADDY_C" 2>/dev/null || true)
      HASH=$(WEB_IMAGE="$OLD_WEB_REF" dc config --hash caddy 2>/dev/null | awk '{print $2}')
      [ -n "$LABEL" ] && [ "$LABEL" = "$HASH" ] && SAME_DEF=1
      OLD_BIN=$(docker exec "$CADDY_C" sh -c 'caddy version; sha256sum /web-entrypoint.sh' 2>/dev/null || true)
      NEW_BIN=$(docker run --rm --entrypoint sh "$NEW_WEB_ID" -c 'caddy version; sha256sum /web-entrypoint.sh' 2>/dev/null || true)
      if [ "$SAME_DEF" = 1 ] && [ -n "$OLD_BIN" ] && [ "$OLD_BIN" = "$NEW_BIN" ]; then
        say "4/5 Web: copiando la consola nueva dentro del borde en ejecución (sin reinicio)…"
        TMPC=$(docker create "$NEW_WEB_ID"); TMPD=$(mktemp -d)
        docker cp "$TMPC:/srv" "$TMPD/srv" && docker cp "$TMPC:/etc/caddy/Caddyfile" "$TMPD/Caddyfile"
        docker rm "$TMPC" >/dev/null
        docker exec -u 0 "$CADDY_C" rm -rf /srv.new /srv.prev /etc/caddy/Caddyfile.new
        docker cp "$TMPD/srv" "$CADDY_C:/srv.new"
        docker cp "$TMPD/Caddyfile" "$CADDY_C:/etc/caddy/Caddyfile.new"
        rm -rf "$TMPD"
        # Hashed assets first (new names, nothing is overwritten), index.html last and atomically: a browser never gets
        # an index that points to files that are not there yet. The previous tree is kept for the rollback.
        if docker exec -u 0 -e NEW_ID="$NEW_WEB_ID" "$CADDY_C" sh -ec '
              changed=0
              if ! cmp -s /etc/caddy/Caddyfile /etc/caddy/Caddyfile.new; then
                caddy validate --config /etc/caddy/Caddyfile.new --adapter caddyfile >/dev/null 2>&1
                changed=1
              fi
              cp -a /srv /srv.prev
              mv /srv.new/index.html /srv.new/.index.html.new
              cp -a /srv.new/. /srv/
              mv -f /srv/.index.html.new /srv/index.html
              rm -rf /srv.new
              chown -R caddy:caddy /srv
              if [ "$changed" = 1 ]; then
                cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.prev
                cat /etc/caddy/Caddyfile.new > /etc/caddy/Caddyfile
                caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || {
                  cat /etc/caddy/Caddyfile.prev > /etc/caddy/Caddyfile
                  caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; exit 1; }
              fi
              rm -f /etc/caddy/Caddyfile.new
              printf "%s\n" "$NEW_ID" > /etc/caddy/.web-image-id
              rm -rf /srv.prev'; then
          WEB_MODE="en caliente (sin reinicio)"
        else
          err "No se pudo actualizar la web en caliente; se restaura la anterior."
          docker exec -u 0 "$CADDY_C" sh -c '[ -d /srv.prev ] && { cp -a /srv.prev/. /srv/; rm -rf /srv.prev /srv.new; }' || true
          WEB_MODE="FALLÓ (se conserva la anterior)"
        fi
      else
        warn "4/5 Web: cambió el binario de Caddy o la definición del servicio: hay que recrear el borde (corte de pocos segundos)."
        T0=$(date +%s)
        dcx up -d --no-deps caddy >/dev/null 2>&1 || true
        if wait_healthy caddy; then
          WEB_MODE="recreado en $(( $(date +%s) - T0 )) s"
        else
          err "El borde nuevo no quedó saludable; volviendo al anterior…"
          rollback_web_recreate
          wait_healthy caddy || err "El borde anterior tampoco responde: docs/ops/RUNBOOK.md"
          WEB_MODE="FALLÓ (vuelta atrás)"
        fi
      fi
      say "    web: $WEB_MODE"
    fi
  else
    say "4/5 Web: omitida."
  fi
}

if [ "$WEB_FIRST" = 1 ]; then update_web; update_server; else update_server; update_web; fi

# ---- 5. the rest + verification -------------------------------------------------------------------------------------
AUX=()
for s in supervisor monitor backup; do if enabled "$s"; then AUX+=("$s"); fi; done
if [ "${#AUX[@]}" -gt 0 ]; then
  say "5/5 Servicios auxiliares (${AUX[*]})…"
  dc up -d --no-deps "${AUX[@]}" >/dev/null 2>&1 || warn "No se pudieron actualizar los servicios auxiliares: ${AUX[*]}"
fi
if [ "$TUNNEL" = 1 ]; then
  if enabled cloudflared-2; then
    say "    Túnel: actualizando los conectores de uno en uno…"
    for s in cloudflared-2 cloudflared; do
      dc up -d --no-deps "$s" >/dev/null 2>&1 || true
      sleep 15   # the connector registers its four connections with Cloudflare before the other one is touched
    done
  else
    warn "    Túnel: sin réplica (perfil cloudflare-replica) el reinicio del único conector corta el acceso ~10-20 s."
    if enabled cloudflared; then dc up -d --no-deps cloudflared >/dev/null 2>&1 || true; fi
  fi
fi

FAIL=""
[ "$(health "$(cid server)")" = healthy ] || FAIL="el servidor no está saludable"
if [ -z "$FAIL" ] && [ -n "$(cid caddy)" ]; then
  # Through the edge, as a user request arrives (the probe the load balancer itself uses).
  EDGE=$(docker exec "$(cid server)" curl -s -o /dev/null -m 8 -w '%{http_code}' -H "Host: ${BASE_URL#*://}" http://caddy:80/healthz/deep 2>/dev/null || true)
  case "$EDGE" in 200|301|302|308) ;; *) FAIL="el borde responde $EDGE a /healthz/deep" ;; esac
fi
if [ -z "$FAIL" ] && [ "${DEPLOY_PUBLIC_CHECK:-1}" = 1 ] && command -v curl >/dev/null; then
  PUB=000
  for _ in 1 2 3 4 5; do
    PUB=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "${BASE_URL}/healthz/deep" 2>/dev/null || true)
    [ "$PUB" = 200 ] && break
    sleep 3
  done
  [ "$PUB" = 200 ] || FAIL="la URL pública ${BASE_URL}/healthz/deep responde $PUB"
fi
if [ -n "$FAIL" ]; then
  err "Verificación final fallida: $FAIL."
  if [ "$ROLLBACK" = 1 ]; then err "Esto ya era una vuelta atrás: intervención manual, docs/ops/RUNBOOK.md"; exit 1; fi
  err "VUELTA ATRÁS AUTOMÁTICA a la versión anterior…"
  exec "$0" --rollback --yes
fi

say "== Despliegue correcto en $(( $(date +%s) - T_START )) s. Servidor: $([ "$RELAY" = 1 ] && echo 'con relevo, sin corte' || echo 'recreado en su sitio'). Web: $WEB_MODE. =="
[ "$ROLLBACK" = 1 ] || say "   Para volver a la versión anterior: scripts/deploy.sh --rollback   (respaldo previo: /backups/predeploy)"
