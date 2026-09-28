#!/usr/bin/env bash
# Remove a native (non-Docker) DallyControl install made by install/install-native.sh.
#
#   sudo ./install/uninstall-native.sh              # interactive: shows what goes, asks you to type UNINSTALL
#   sudo ./install/uninstall-native.sh --keep-data  # remove code/services but keep the database + uploaded files
#   sudo ./install/uninstall-native.sh -y           # unattended (still takes a final pg_dump unless --no-backup)
#
# Removes: Tomcat (/opt/dallycontrol-tc), the app dir (/opt/dallycontrol), the dallycontrol-supervisor systemd unit and its settings
# (/etc/dallycontrol), the install log, and — unless --keep-data — the PostgreSQL database + role "dallycontrol". A final dump is
# written first.
# Leaves alone: apt packages (postgresql, maven, node, …), your reverse proxy/TLS, and the git checkout.
set -euo pipefail
umask 077
export PATH="/usr/sbin:/sbin:$PATH"   # useradd/userdel/pg tools live here; not every root shell has it
[ "$(id -u)" = "0" ] || { echo "Run as root (sudo)."; exit 1; }

BASE_DIR=/opt/dallycontrol
CATALINA=/opt/dallycontrol-tc
UNIT=/etc/systemd/system/dallycontrol-supervisor.service
SUP_ENV_DIR=/etc/dallycontrol   # the supervisor's settings (install-native.sh)
SERVER_UNIT=/etc/systemd/system/dallycontrol-server.service
SVC_USER=dallycontrol
INSTALL_LOG=/var/log/dallycontrol-install.log
KEEP_DATA=0; YES=0; BACKUP=1
for a in "$@"; do
  case "$a" in
    --keep-data) KEEP_DATA=1 ;;
    -y|--yes)    YES=1 ;;
    --no-backup) BACKUP=0 ;;
    -h|--help)   sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Unknown flag: $a (see --help)"; exit 1 ;;
  esac
done

# svc_user_pids: the pids of $SVC_USER's processes, one per line, leaving out container processes that merely run as the
# same numeric uid (another PID namespace, but the host's user namespace: only a privileged container runtime sets that
# up). Same function as in install-native.sh; see the reasoning there.
svc_user_pids() {
  local host_pid host_user p
  host_pid=$(readlink /proc/1/ns/pid) host_user=$(readlink /proc/1/ns/user)
  for p in $(pgrep -u "$SVC_USER" || true); do
    [ "$(readlink "/proc/$p/ns/pid")" != "$host_pid" ] && [ "$(readlink "/proc/$p/ns/user")" = "$host_user" ] && continue
    echo "$p"
  done
}
db_exists() { su -s /bin/sh postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='dallycontrol'\"" 2>/dev/null | grep -q 1; }
counts=""
if db_exists; then
  counts=$(su -s /bin/sh postgres -c "psql -d dallycontrol -tAc \"SELECT (SELECT count(*) FROM devices)||' device(s), '||(SELECT count(*) FROM configurations)||' configuration(s), '||(SELECT count(*) FROM users)||' user(s)'\"" 2>/dev/null || echo "unreadable")
fi

echo
echo "  DallyControl native uninstall — this host will lose:"
[ -d "$CATALINA" ] && echo "    • Tomcat + deployed server:   $CATALINA"
[ -f "$SERVER_UNIT" ] && echo "    • server service:             dallycontrol-server (systemd unit removed)"
id -u "$SVC_USER" >/dev/null 2>&1 && [ "$KEEP_DATA" != 1 ] && echo "    • service user:               $SVC_USER"
[ -f "$UNIT" ]     && echo "    • updater service:            dallycontrol-supervisor (systemd unit removed)"
[ -d "$SUP_ENV_DIR" ] && echo "    • updater settings:           $SUP_ENV_DIR"
if [ "$KEEP_DATA" = 1 ]; then
  [ -d "$BASE_DIR" ] && echo "    • app dir (KEEPING files/ and backups/): $BASE_DIR"
  [ -n "$counts" ]   && echo "    • database:                   KEPT ($counts)"
else
  [ -d "$BASE_DIR" ] && echo "    • app dir, uploads, backups:  $BASE_DIR"
  [ -n "$counts" ]   && echo "    • database + role 'dallycontrol':   DROPPED — $counts"
fi
[ -f "$INSTALL_LOG" ] && echo "    • install log:                $INSTALL_LOG"
echo "  Not touched: apt packages, your TLS proxy, this git checkout."
[ "$BACKUP" = 1 ] && [ -n "$counts" ] && echo "  A final database dump is written to /root before anything is removed."
echo
if [ "$YES" != 1 ]; then
  printf '  Type UNINSTALL to proceed: '
  read -r _c
  [ "$_c" = "UNINSTALL" ] || { echo "  Aborted — nothing changed."; exit 1; }
fi

# 1. Final backup — cheap insurance even when --keep-data (the dump is the portable copy).
if [ "$BACKUP" = 1 ] && db_exists; then
  DUMP="/root/dallycontrol-final-$(date +%Y%m%d-%H%M%S).dump"
  su -s /bin/sh postgres -c "pg_dump -Fc dallycontrol" > "$DUMP" && chmod 600 "$DUMP" && echo "  ✓ final dump: $DUMP  (restore: pg_restore -c -d dallycontrol $DUMP)"
fi

# 2. Stop Tomcat for good: the systemd unit first (cgroup-tracked), then legacy fallbacks for Tomcats
#    started by older versions of the installer without a unit. $CATALINA is $SVC_USER's tree, so root never runs its
#    bin/catalina.sh (nor the bin/setenv.sh it sources): with the unit, systemd stops Tomcat; without one, catalina.sh
#    runs as $SVC_USER (no controlling terminal, none of root's environment; see as_svc_user in install-native.sh), and
#    before that account existed (Tomcat ran as root) the signals below stop it.
if [ -f "$SERVER_UNIT" ] || [ -n "$(systemctl list-unit-files --no-legend dallycontrol-server.service 2>/dev/null)" ]; then
  systemctl disable --now dallycontrol-server >/dev/null 2>&1 || true
  rm -f "$SERVER_UNIT"; systemctl daemon-reload 2>/dev/null || true
  echo "  ✓ dallycontrol-server service removed"
elif [ -x "$CATALINA/bin/catalina.sh" ] && id -u "$SVC_USER" >/dev/null 2>&1; then
  ( cd / && exec setsid -w setpriv --reuid="$SVC_USER" --regid="$SVC_USER" --init-groups --no-new-privs \
      env -i PATH=/usr/local/bin:/usr/bin:/bin JAVA_HOME="${JAVA_HOME:-}" CATALINA_HOME="$CATALINA" \
      CATALINA_BASE="$CATALINA" CATALINA_PID="$CATALINA/tomcat.pid" "$CATALINA/bin/catalina.sh" stop 20 -force \
      < /dev/null ) >/dev/null 2>&1 || true
fi
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill "$p" 2>/dev/null || true; done
for _ in $(seq 1 20); do pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" >/dev/null || break; sleep 1; done
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill -9 "$p" 2>/dev/null || true; done
echo "  ✓ Tomcat stopped"

# 3. Updater service.
if [ -f "$UNIT" ] || [ -n "$(systemctl list-unit-files --no-legend dallycontrol-supervisor.service 2>/dev/null)" ]; then
  systemctl disable --now dallycontrol-supervisor >/dev/null 2>&1 || true
  rm -f "$UNIT"; systemctl daemon-reload 2>/dev/null || true
  echo "  ✓ dallycontrol-supervisor service removed"
fi
if [ -d "$SUP_ENV_DIR" ]; then
  # With the temp file a killed install run may have left (install-native.sh write_under's .NAME.dallycontrol-tmp.XXXXXX).
  rm -f "$SUP_ENV_DIR/supervisor.env" "$SUP_ENV_DIR"/.supervisor.env.dallycontrol-tmp.??????
  if rmdir "$SUP_ENV_DIR" 2>/dev/null; then echo "  ✓ removed $SUP_ENV_DIR/supervisor.env and $SUP_ENV_DIR"
  else echo "  ✓ removed $SUP_ENV_DIR/supervisor.env (kept $SUP_ENV_DIR: it holds other files)"; fi
fi

# 4. Database.
if [ "$KEEP_DATA" != 1 ] && db_exists; then
  su -s /bin/sh postgres -c "psql -qc \"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='dallycontrol' AND pid<>pg_backend_pid();\"" >/dev/null 2>&1 || true
  su -s /bin/sh postgres -c "psql -qc 'DROP DATABASE dallycontrol;'"
  su -s /bin/sh postgres -c "psql -qc 'DROP ROLE IF EXISTS dallycontrol;'"
  echo "  ✓ database + role dropped"
fi

# 5. Files.
rm -rf "$CATALINA"
if [ "$KEEP_DATA" = 1 ]; then
  for d in emails plugins supervisor; do rm -rf "${BASE_DIR:?}/$d"; done
  rm -f "$BASE_DIR"/initialized.txt "$BASE_DIR"/log4j-dallycontrol.xml "$BASE_DIR"/supervisor.env   # log4j-dallycontrol.xml: written by v0.2.1–v0.3.x
  echo "  ✓ removed $CATALINA and app code; kept $BASE_DIR/files and $BASE_DIR/backups"
else
  rm -rf "$BASE_DIR"
  echo "  ✓ removed $CATALINA and $BASE_DIR"
fi
rm -f "$INSTALL_LOG"
# 6. Service account — only when its files are gone too (a kept files/ dir stays owned by it).
if [ "$KEEP_DATA" != 1 ] && id -u "$SVC_USER" >/dev/null 2>&1; then
  # userdel refuses while the account has processes (it ignores those in another root, such as a container's).
  for _ in $(seq 1 20); do
    pids=$(svc_user_pids); [ -n "$pids" ] || break
    # shellcheck disable=SC2086  # one pid per word
    kill -KILL $pids 2>/dev/null || true; sleep 0.2
  done
  if userdel "$SVC_USER" 2>/dev/null; then echo "  ✓ service user $SVC_USER removed"
  else echo "  ! could not remove the service user $SVC_USER (run: userdel $SVC_USER)"; fi
fi
echo
echo "  DallyControl removed. Devices still enrolled will keep polling this server's URL until factory-reset or re-provisioned."
