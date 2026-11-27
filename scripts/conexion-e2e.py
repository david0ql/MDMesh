#!/usr/bin/env python3
"""Connection end-to-end test on a real agent (emulator or device), against the dev stack.

Cuts and restores the device's network in several ways and measures how long the agent takes to be connected
again (its live channel open on the server) and to execute a command; then checks that what happened reaches the
console API (/rest/private/connection/v1): last failure, last silence, reconnections, resting state.

  scripts/conexion-e2e.py --serial emulator-5600 --device-id <id> --api http://localhost:8088 \
      [--compose "docker compose --env-file docker/dev.env"] [--db-container <postgres container>] [--only a,b]

Scenarios (--only): baseline, wifi, outage, airplane, doze, server, failover, tls, nows, resting, reboot
tls / nows need scripts/red-simulada.py running, a debug agent (run-as) and a backup URL configured on the server.
"""
import argparse
import hashlib
import http.cookiejar
import json
import shlex
import subprocess
import sys
import time
import urllib.request

P = argparse.ArgumentParser()
P.add_argument("--serial", required=True)
P.add_argument("--device-id", required=True)
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--adb", default="adb")
P.add_argument("--compose", default="", help="compose command of the stack (server restart / failover scenarios)")
P.add_argument("--db-container", default="", help="postgres container (resting scenario ages the last report)")
P.add_argument("--pkg", default="com.dallycontrol.agent.debug")
P.add_argument("--server-url", default="http://10.0.2.2:8088", help="the server as the device reaches it (restored after tls/nows)")
P.add_argument("--tls-url", default="", help="scripts/red-simulada.py tls, as the device reaches it (e.g. https://10.0.2.2:8443)")
P.add_argument("--backup-device-url", default="", help="the server's backup URL as the device reaches it (tls scenario)")
P.add_argument("--nows-url", default="", help="scripts/red-simulada.py nows, as the device reaches it (e.g. http://10.0.2.2:8122)")
P.add_argument("--only", default="")
P.add_argument("--doze-minutes", type=float, default=7)
A = P.parse_args()

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
RESULTS = []
TIMES = []


def api(method, path, body=None, timeout=15):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(A.api + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with OP.open(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def try_api(method, path, body=None):
    try:
        return api(method, path, body)
    except Exception:
        return {}


def adb(*args, timeout=60):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail else ""), flush=True)
    return ok


def measure(name, seconds, limit):
    TIMES.append((name, seconds))
    return check(f"{name}: {seconds:.1f} s (límite {limit} s)" if seconds is not None else f"{name}: no ocurrió en {limit} s",
                 seconds is not None and seconds <= limit)


def login(patience=120):
    """Signs in; retried, since the server may be restarting (a scenario just did that)."""
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    end = time.time() + patience
    while True:
        r = try_api("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5})
        if r.get("status") == "OK":
            return
        if time.time() > end:
            sys.exit(f"login failed: {r}")
        time.sleep(3)


def is_live():
    return A.device_id in (try_api("GET", "/rest/private/agent/v1/live").get("data") or [])


def wait_until(cond, timeout, step=0.5):
    """Seconds until cond() is true, or None."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if cond():
            return time.time() - t0
        time.sleep(step)
    return None


def queue():
    body = {"type": "policy.apply", "requiresCapability": "policy.camera",
            "payload": json.dumps({"policy": "camera", "value": True})}
    r = api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/commands", body)
    if r.get("status") != "OK":
        raise RuntimeError(f"queue failed: {r}")
    return r["data"]["id"]


def command_done(cmd_id):
    r = try_api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/commands?limit=50")
    for c in r.get("data") or []:
        if str(c.get("id")) == str(cmd_id):
            return c.get("status") == "done"
    return False


def command_latency(timeout=90):
    cmd = queue()
    return wait_until(lambda: command_done(cmd), timeout, 0.3)


def connection():
    return try_api("GET", f"/rest/private/connection/v1/devices/{A.device_id}").get("data") or {}


def last_update():
    """When the server stored the device's last report (device_state.updatedAt)."""
    return (try_api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/state").get("data") or {}).get("updatedAt")


def net(on):
    state = "enable" if on else "disable"
    sh(f"svc wifi {state}")
    sh(f"svc data {state}")


def wait_live(timeout):
    return wait_until(is_live, timeout)


def wait_not_live(timeout=210):
    return wait_until(lambda: not is_live(), timeout)


def compose(*args, timeout=180):
    return subprocess.run(shlex.split(A.compose) + list(args), capture_output=True, text=True, timeout=timeout)


def recover_and_command(label, restore, live_limit, cmd_limit, queued=None):
    """restore() brings the network/server back; measures time to live channel and to the command's result."""
    cmd = queued if queued is not None else queue()
    t0 = time.time()
    restore()
    live = wait_live(live_limit + 60)
    measure(f"{label}: canal en vivo de nuevo", live, live_limit)
    done = wait_until(lambda: command_done(cmd), cmd_limit + 60, 0.3)
    measure(f"{label}: orden ejecutada", None if done is None else time.time() - t0, cmd_limit)


# --- scenarios ----------------------------------------------------------------------------------------------------

def baseline():
    print("== línea base (red estable)")
    check("canal en vivo abierto", wait_live(90) is not None)
    for i in range(3):
        measure(f"orden con el canal abierto #{i + 1}", command_latency(60), 10)


def wifi():
    print("== cambio de red: Wi-Fi -> datos móviles -> Wi-Fi")
    before = connection().get("reconnects") or 0
    t0 = time.time()
    sh("svc wifi disable")
    time.sleep(1)
    measure("Wi-Fi apagado (pasa a datos): orden ejecutada", (lambda s: None if s is None else s + 1)(command_latency(60)), 30)
    check("canal en vivo tras pasar a datos", wait_live(60) is not None, f"{time.time() - t0:.1f} s")
    sh("svc wifi enable")
    time.sleep(8)  # the emulator's Wi-Fi takes a few seconds to associate
    measure("Wi-Fi de vuelta: orden ejecutada", command_latency(60), 30)
    check("canal en vivo tras volver a Wi-Fi", wait_live(60) is not None)
    time.sleep(3)
    command_latency(30)  # a check-in delivers the reconnection count
    after = connection().get("reconnects") or 0
    check("la consola cuenta las reconexiones", after > before, f"{before} -> {after}")


def outage():
    print("== sin red 90 s (Wi-Fi y datos apagados) y vuelve")
    net(False)
    gone = wait_not_live(210)
    measure("el servidor nota la caída del canal (socket sin tráfico)", gone, 200)
    cmd = queue()  # queued while the device is unreachable
    time.sleep(max(0, 90 - (gone or 0)))
    recover_and_command("red de vuelta", lambda: net(True), 30, 40, cmd)


def airplane():
    print("== modo avión 60 s")
    on = sh("cmd connectivity airplane-mode enable")
    legacy = "Unknown command" in on or "No shell command" in on or "Exception" in on or "not found" in on
    if legacy:
        adb("root")
        time.sleep(3)
        sh("settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
    check("modo avión activo", sh("settings get global airplane_mode_on").strip() == "1")
    wait_not_live(150)
    cmd = queue()
    time.sleep(60)

    def off():
        if legacy:
            sh("settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
        else:
            sh("cmd connectivity airplane-mode disable")
    recover_and_command("sale de modo avión", off, 45, 60, cmd)


def doze():
    minutes = A.doze_minutes
    print(f"== Doze forzado {minutes:g} min (pantalla apagada, sin cargador)")
    sh("dumpsys battery unplug")
    sh("input keyevent 223")  # sleep
    time.sleep(2)
    out = sh("dumpsys deviceidle force-idle")
    check("Doze forzado", "idle" in out.lower(), out.strip()[:80])
    time.sleep(20)
    measure("orden en Doze", command_latency(120), 60)
    t0 = time.time()
    seen, gaps, last = set(), [], time.time()
    live_samples = []
    while time.time() - t0 < minutes * 60:
        lu = last_update()
        if lu and lu not in seen:
            if seen:
                gaps.append(time.time() - last)
            seen.add(lu)
            last = time.time()
        live_samples.append(is_live())
        time.sleep(5)
    pct = 100.0 * sum(live_samples) / max(1, len(live_samples))
    check(f"canal en vivo abierto durante Doze: {pct:.0f} % del tiempo", pct >= 90)
    longest = max(gaps + [time.time() - last])
    measure("mayor intervalo entre reportes en Doze", longest, 6 * 60 + 30)
    measure("orden tras varios minutos en Doze", command_latency(120), 60)
    sh("dumpsys deviceidle unforce")
    sh("dumpsys battery reset")
    sh("input keyevent 224")  # wake
    sh("wm dismiss-keyguard")


def server():
    if not A.compose:
        print("== reinicio del servidor: omitido (falta --compose)")
        return
    print("== reinicio del servidor")
    compose("restart", "server")
    # Tomcat is back when the agent's own endpoint answers.
    up = wait_until(lambda: bool(try_api("POST", "/rest/public/agent/v1/checkin", {}).get("status")), 240, 1)
    check("servidor arriba de nuevo", up is not None, "" if up is None else f"{up:.0f} s")
    t_up = time.time()
    login()
    live = wait_live(90)
    measure("tras reinicio del servidor: canal en vivo (desde que el servidor responde)",
            None if live is None else time.time() - t_up, 30)
    measure("tras reinicio del servidor: orden ejecutada", command_latency(60), 15)
    time.sleep(2)
    command_latency(30)
    c = connection()
    f = c.get("lastFailure") or {}
    check("la consola recibe la última falla de red", bool(f.get("kind")),
          f"{f.get('kind')} · {f.get('label')} · {f.get('networkLabel')} · canal {f.get('channel')} · {f.get('count')} intentos")
    check("la falla trae red y hora", bool(f.get("network")) and bool(f.get("at")))


def failover():
    if not A.compose:
        print("== dirección de respaldo: omitido (falta --compose)")
        return
    print("== dirección de respaldo: la principal (Caddy) cae, el servidor sigue en la de respaldo")
    if not (try_api("GET", "/rest/private/agent/v1/live").get("status")):
        login()
    command_latency(30)  # a fresh check-in so the agent holds the backup list
    compose("stop", "caddy")
    # The console API goes through the same Caddy: use the direct port for the checks meanwhile.
    primary = A.api
    A.api = A.backup_api
    try:
        login()
        t0 = time.time()
        switched = wait_until(lambda: connection().get("usingBackup") is True, 240, 2)
        measure("cambia a la dirección de respaldo", switched, 180)
        measure("por la dirección de respaldo: orden ejecutada", command_latency(60), 30)
        check("canal en vivo por la dirección de respaldo", wait_live(60) is not None, f"{time.time() - t0:.0f} s tras la caída")
        f = connection().get("lastFailure") or {}
        check("la falla de la dirección principal queda registrada", f.get("kind") in ("connect", "timeout", "http", "wsUpgrade"),
              f"{f.get('kind')} · {f.get('label')}")
    finally:
        compose("start", "caddy")
        A.api = primary
        wait_until(lambda: bool(try_api("GET", "/rest/public/auth/options")) or True, 5)
        time.sleep(5)
        login()


def resting():
    if not A.db_container:
        print("== En reposo: omitido (falta --db-container)")
        return
    print("== estado «En reposo» frente a «Sin conexión»")

    def age_last_report(minutes):
        sql = (f"UPDATE devices SET lastUpdate = (extract(epoch from now())*1000)::bigint - {int(minutes * 60000)} "
               f"WHERE number = '{A.device_id}'")
        subprocess.run(["docker", "exec", A.db_container, "psql", "-U", "dallycontrol", "-d", "dallycontrol", "-c", sql],
                       capture_output=True, text=True, timeout=30)

    # Screen off, one report, then silence.
    sh("input keyevent 223")
    time.sleep(3)
    command_latency(60)
    net(False)
    wait_not_live(150)
    age_last_report(45)
    c = connection()
    check("silencio con pantalla apagada = En reposo", c.get("state") == "resting" and c.get("reason") == "screenOff",
          f"{c.get('state')} / {c.get('label')}")
    age_last_report(17 * 60)
    check("tras 17 h deja de ser reposo = Sin conexión", connection().get("state") == "offline")
    net(True)
    sh("input keyevent 224")
    sh("wm dismiss-keyguard")
    check("vuelve a En línea", wait_until(lambda: connection().get("state") == "online", 90, 1) is not None)
    time.sleep(3)
    # Screen on, one report, then silence: nothing explains it.
    command_latency(60)
    time.sleep(2)
    command_latency(60)
    net(False)
    wait_not_live(150)
    age_last_report(45)
    c = connection()
    check("silencio con el equipo en uso = Sin conexión", c.get("state") == "offline", f"{c.get('state')} / {c.get('label')}")
    net(True)
    wait_live(90)


def reboot():
    print("== reinicio del equipo (apagado ordenado)")
    t0 = time.time()
    adb("reboot")
    time.sleep(6)
    c = connection()
    s = c.get("lastStop") or {}
    check("el equipo avisó el apagado antes de irse", s.get("reason") == "shutdown" and not s.get("resumedAt"),
          f"{s.get('reason')} · {s.get('label')}")
    booted = wait_until(lambda: adb("shell", "getprop", "sys.boot_completed").strip() == "1", 300, 3)
    check("el equipo arrancó", booted is not None)
    live = wait_live(180)
    measure("tras reiniciar el equipo: canal en vivo (sin abrir la app)", None if live is None else time.time() - t0, 240)
    measure("tras reiniciar el equipo: orden ejecutada", command_latency(90), 30)
    time.sleep(2)
    s = connection().get("lastStop") or {}
    check("la consola cierra el corte con su duración", s.get("reason") == "shutdown" and bool(s.get("resumedAt")),
          json.dumps(s, ensure_ascii=False))


def point_agent_at(url, forget_backup_choice=False):
    """Debug builds only: rewrite the provisioned server URL and restart the agent's process (keeps its enrollment)."""
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           f"    <string name=\"base_url\">{url}</string>\n</map>\n")
    subprocess.run([A.adb, "-s", A.serial, "shell", f"run-as {A.pkg} sh -c 'cat > shared_prefs/mdm_server.xml'"],
                   input=xml, text=True, capture_output=True, timeout=30)
    if forget_backup_choice:
        sh(f"run-as {A.pkg} rm -f shared_prefs/mdm_endpoints.xml")
    elif A.backup_device_url:
        # The agent forgets its backups when its server changes (they belonged to the other server). Here the
        # "new server" stands for the same one behind a network that now inspects TLS, so hand the list back.
        prefs = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                 f"    <string name=\"backup_urls\">{A.backup_device_url}</string>\n"
                 f"    <string name=\"primary_url\">{url}</string>\n</map>\n")
        subprocess.run([A.adb, "-s", A.serial, "shell", f"run-as {A.pkg} sh -c 'cat > shared_prefs/mdm_endpoints.xml'"],
                       input=prefs, text=True, capture_output=True, timeout=30)
    pid = sh(f"pidof {A.pkg}").strip()
    if pid:
        sh(f"run-as {A.pkg} kill -9 {pid}")
    time.sleep(1)
    sh(f"am start -n {A.pkg}/com.dallycontrol.agent.MainActivity")


def restore_agent():
    point_agent_at(A.server_url, forget_backup_choice=True)
    check("de vuelta en la dirección principal: canal en vivo", wait_live(120) is not None)


def tls():
    if not A.tls_url:
        print("== inspección TLS: omitido (falta --tls-url)")
        return
    print("== la red inspecciona TLS (certificado de un firewall, no confiable para el equipo)")
    command_latency(30)  # a check-in, so the agent holds the backup list
    time.sleep(2)
    t0 = time.time()
    point_agent_at(A.tls_url)
    try:
        got = wait_until(lambda: (connection().get("lastFailure") or {}).get("kind") == "tls", 420, 3)
        measure("la consola recibe la falla TLS (llega por la dirección de respaldo)", got, 360)
        c = connection()
        f = c.get("lastFailure") or {}
        check("motivo exacto: certificado TLS rechazado", "TLS" in (f.get("label") or ""), f.get("label") or "")
        check("emisor del certificado = el firewall simulado", "FortiGate CA simulada" in (f.get("certIssuer") or ""),
              f.get("certIssuer") or "")
        check("red y servidor de la falla", bool(f.get("networkLabel")) and bool(f.get("host")),
              f"{f.get('networkLabel')} · {f.get('host')} · canal {f.get('channel')} · {f.get('count')} intentos")
        check("el equipo sigue administrado por la dirección de respaldo", c.get("usingBackup") is True)
        measure("orden ejecutada por la dirección de respaldo", command_latency(90), 60)
        print(f"          (total desde que la red empezó a inspeccionar: {time.time() - t0:.0f} s)")
    finally:
        restore_agent()


def nows():
    if not A.nows_url:
        print("== WebSocket bloqueado: omitido (falta --nows-url)")
        return
    print("== la red bloquea el upgrade a WebSocket (HTTP sí pasa)")
    point_agent_at(A.nows_url, forget_backup_choice=True)
    try:
        got = wait_until(lambda: (connection().get("lastFailure") or {}).get("kind") == "wsUpgrade", 300, 3)
        measure("la consola recibe la falla del WebSocket", got, 240)
        f = connection().get("lastFailure") or {}
        check("motivo exacto: WebSocket rechazado con su código", f.get("httpCode") == 403 and "WebSocket" in (f.get("label") or ""),
              f"{f.get('label')} · canal {f.get('channel')} · {f.get('networkLabel')}")
        measure("orden ejecutada aunque el WebSocket esté bloqueado", command_latency(400), 330)
    finally:
        restore_agent()


SCENARIOS = [("baseline", baseline), ("wifi", wifi), ("outage", outage), ("airplane", airplane), ("doze", doze),
             ("server", server), ("failover", failover), ("tls", tls), ("nows", nows),
             ("resting", resting), ("reboot", reboot)]


def main():
    A.backup_api = getattr(A, "backup_api", None) or A.api.replace("8102", "8112").replace("8088", "8080")
    login()
    only = [x for x in A.only.split(",") if x]
    release = sh("getprop ro.build.version.release").strip()
    api_level = sh("getprop ro.build.version.sdk").strip()
    print(f"Dispositivo {A.device_id} · Android {release} (API {api_level}) · {A.serial}")
    try:
        print("Carga de la máquina de pruebas (load average): " + " ".join(f"{x:.1f}" for x in __import__("os").getloadavg()))
    except OSError:
        pass
    for name, fn in SCENARIOS:
        if only and name not in only:
            continue
        try:
            fn()
        except Exception as e:  # keep going: one broken scenario must not hide the others
            check(f"{name}: excepción", False, repr(e))
            try:
                net(True)
                login()
            except Exception:
                pass
    print("\n== diagnóstico final en la consola")
    print(json.dumps(connection(), ensure_ascii=False, indent=1))
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print(f"\nRESULT: PASS={passed} FAIL={len(RESULTS) - passed} (Android {release}, API {api_level})")
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
