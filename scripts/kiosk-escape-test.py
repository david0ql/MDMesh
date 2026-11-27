#!/usr/bin/env python3
"""Kiosk escape test (docs/KIOSK-HARDENING.md): puts an enrolled Device-Owner phone/emulator in the kiosk through the
admin API and tries every known way out over adb, in single-app and multi-app mode.

  scripts/build-escapeapp.sh                          # once: the stand-in work app
  scripts/kiosk-escape-test.py --serial emulator-5600 --device-id <id> [--api http://localhost:8088]
        [--admin-user admin] [--admin-password admin] [--pkg com.dallycontrol.agent.debug]
        [--modes single,launcher] [--reboot] [--legacy-psql "docker compose ... exec -T postgres psql -U dallycontrol -d dallycontrol"]
        [--summary out.md]

What it tries, per mode: system state (lock task / suspended Settings / kiosk restrictions), `am start` of Settings,
an allowed app opening Settings by intent, the status bar and its quick-settings gear, Home, Recents, the power menu
(open by default, closed with "lock buttons"), the "share data" screen (multi-app), the 7-tap exit with wrong
passwords (wait after the third), the exit with the right one, a kiosk without password (no exit on the device) and,
with --reboot, a restart (also asking for safe mode when adb is root) that must come back into the kiosk.
--legacy-psql also sends the kiosk an OLD server would have sent (gesture exit, no password) straight into the
command table, to prove the agent itself refuses to leave.

Exit code 0 when no check failed. Android 16+ runs the kiosk without lock task ("soft kiosk"): the checks adapt.
"""
import argparse
import hashlib
import http.cookiejar
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

P = argparse.ArgumentParser()
P.add_argument("--serial", required=True)
P.add_argument("--device-id", required=True)
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--pkg", default="com.dallycontrol.agent.debug")
P.add_argument("--adb", default="adb")
P.add_argument("--app-apk", default=str(Path(__file__).parent / "testapp" / "escapetest.apk"))
P.add_argument("--modes", default="single,launcher")
P.add_argument("--reboot", action="store_true")
P.add_argument("--legacy-psql", default="")
P.add_argument("--summary", default="")
A = P.parse_args()

APP = "com.dallycontrol.escapetest"
PASSWORD = "4821"
SETTINGS_ACTIONS = ["android.settings.SETTINGS", "android.settings.WIFI_SETTINGS",
                    "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "android.settings.SECURITY_SETTINGS"]
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
RESULTS = []  # (mode, name, ok, detail)   ok None = informational
MODE = "-"


def api(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    last = None
    for attempt in range(4):  # a loaded dev machine answers late or with a 502 now and then: that is not a result
        req = urllib.request.Request(A.api + path, data=data, method=method, headers={"Content-Type": "application/json"})
        try:
            with OP.open(req, timeout=60) as r:
                return json.loads(r.read() or b"{}")
        except Exception as e:  # noqa: BLE001
            last = e
            if method != "GET" and attempt >= 1 and not isinstance(e, urllib.error.HTTPError):
                break  # a POST that may have reached the server is not sent a third time
            time.sleep(5)
    raise last


def adb(*args, timeout=90):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)


def check(name, ok, detail=""):
    RESULTS.append((MODE, name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    return ok


def info(name, detail):
    RESULTS.append((MODE, name, None, detail))
    print(f"  INFO  {name}: {detail}", flush=True)


def until(fn, timeout=30, step=1.0):
    end = time.time() + timeout
    v = None
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return v


def queue(type_, payload=None):
    body = {"type": type_}
    if payload is not None:
        body["payload"] = json.dumps(payload)
    r = api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/commands", body)
    if r.get("status") != "OK":
        raise RuntimeError(f"queue {type_} failed: {r}")
    return r["data"]["id"]


def run(type_, payload=None, timeout=120):
    cid = queue(type_, payload)
    end = time.time() + timeout
    last = {"status": "timeout"}
    while time.time() < end:
        for c in api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/commands").get("data") or []:
            if str(c.get("id")) == str(cid):
                last = c
                if c.get("status") in ("done", "failed", "unsupported", "expired"):
                    return c.get("status"), c.get("detail")
        time.sleep(1.5)
    return last.get("status"), last.get("detail")


def events(types, since):
    r = api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/events?types={types}&since={since}")
    return r.get("data") or []


# ---------------------------------------------------------------------------------------------- device helpers
SDK = 0
POWER_DETECTABLE = False  # whether this image shows the power menu to `input keyevent --longpress POWER` at all


def top():
    """package/activity in front."""
    out = sh("dumpsys activity activities")
    for key in ("topResumedActivity", "mResumedActivity", "ResumedActivity"):
        m = re.search(key + r"[=:]\s*ActivityRecord\{\S+ \S+ (\S+)", out)
        if m:
            return m.group(1)
    return ""


def lock_task():
    m = re.search(r"mLockTaskModeState=(\w+)", sh("dumpsys activity activities"))
    return m.group(1) if m else ""


def settings_in_front():
    t = top()
    return t.startswith("com.android.settings/") or "settings.intelligence" in t


def suspended(pkg):
    """Closed by the kiosk: suspended, or (Android 14+ will not suspend Settings) hidden."""
    out = sh(f"dumpsys package {pkg}")
    return bool(re.search(r"User 0:.*(suspended=true|hidden=true)", out))


def restrictions():
    """User restrictions in force (dumpsys user; the guest defaults listed there are not this user's)."""
    out = sh("dumpsys user").split("Guest restrictions")[0]
    found = set(re.findall(r"^\s+(no_\w+)\s*$", out, re.M))
    return found or set(re.findall(r"no_\w+", sh("dumpsys device_policy")))


def ui():
    for _ in range(3):  # the dump fails now and then while a window is animating
        sh("rm -f /sdcard/dc-ui.xml; uiautomator dump /sdcard/dc-ui.xml >/dev/null 2>&1")
        raw = sh("cat /sdcard/dc-ui.xml")
        try:
            return list(ET.fromstring(raw[raw.index("<?xml"):]).iter("node"))
        except Exception:
            time.sleep(1)
    return []


def find(nodes, **want):
    for n in nodes:
        if all(v in (n.get(k) or "") for k, v in want.items()):
            a = [int(x) for x in re.findall(r"\d+", n.get("bounds") or "")]
            if len(a) == 4:
                return ((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
    return None


def tap(xy):
    sh(f"input tap {xy[0]} {xy[1]}")


def screen():
    w, h = re.findall(r"(\d+)x(\d+)", sh("wm size"))[-1]
    d = re.findall(r"(\d+)", sh("wm density"))[-1]
    return int(w), int(h), int(d) / 160.0


def home():
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1.5)


def seven_taps():
    """7 quick taps on the kiosk's invisible top-right corner (they must land within 3 s: one shell, in parallel)."""
    w, _, dp = screen()
    x, y = int(w - 20 * dp), int(52 * dp)
    sh("for i in 1 2 3 4 5 6 7; do input tap %d %d & sleep 0.12; done; wait" % (x, y), timeout=60)
    time.sleep(1.5)


def exit_dialog():
    """(field, ok button, message) of the kiosk's exit dialog when it is up."""
    nodes = ui()
    return find(nodes, **{"content-desc": "kiosk-exit-password"}), find(nodes, **{"resource-id": "android:id/button1"}), \
        " ".join((n.get("text") or "") for n in nodes if "android:id/message" in (n.get("resource-id") or ""))


def kiosk_home_in_front():
    return "KioskLauncherActivity" in top() or "KioskHomeAlias" in top()


def to_exit_screen(mode):
    """Bring up a kiosk screen that carries the exit gesture. Single-app: the pinned app covers it, so leave the app
    until the kiosk stops reopening it (its "open <app>" screen)."""
    if mode == "launcher":
        home()
        return kiosk_home_in_front()
    for _ in range(12):
        home()
        if find(ui(), **{"content-desc": "kiosk-open-app"}):
            return True
    return False


def try_exit(mode, typed):
    """7 taps, type `typed`, confirm. Returns the dialog message when no password field was offered, else ''."""
    if not to_exit_screen(mode):
        return "no exit screen"
    seven_taps()
    field, ok, message = exit_dialog()
    if not field:
        if ok:
            tap(ok)
        return message or "no dialog"
    tap(field)
    sh(f"input text {typed}")
    time.sleep(0.5)
    _, ok, _ = exit_dialog()
    if ok:
        tap(ok)
    time.sleep(2)
    return ""


def payload(mode, password=PASSWORD, exit_mode="gesture", lock_buttons=None, soft_status_bar=None):
    p = {"mode": mode, "allowedPackages": [APP], "exitMode": exit_mode, "quickSettings": True,
         "features": {"home": True, "recents": True, "notifications": True, "systemInfo": True}}
    if mode == "single":
        p["pinPackage"] = APP
    if password:
        p["password"] = password
    if lock_buttons is not None:
        p["features"]["lockButtons"] = lock_buttons
    if soft_status_bar is not None:
        p["features"]["softStatusBar"] = soft_status_bar
    return p


def enter(mode, **kw):
    s, d = run("kiosk.enter", payload(mode, **kw))
    time.sleep(4)
    return s, d


# ---------------------------------------------------------------------------------------------- the escapes
def t_state(mode):
    soft = SDK >= 36
    if soft:
        check("Android 16+: quiosco sin lock task (estado NONE)", lock_task() == "NONE", lock_task())
    else:
        check("lock task LOCKED", until(lambda: lock_task() == "LOCKED", 20), lock_task())
    dp = restrictions()
    for r in ["no_safe_boot", "no_add_user"] + (["no_user_switch", "no_system_error_dialogs"] if SDK >= 28 else []):
        check(f"restricción {r}", r in dp)
    check("modo seguro deshabilitado (safe_boot_disallowed=1)", sh("settings get global safe_boot_disallowed").strip() == "1")
    if 24 <= SDK < 34:
        check("Ajustes (com.android.settings) suspendido", until(lambda: suspended("com.android.settings"), 15))
    elif SDK < 36:
        # Android 14+ does not let anyone suspend Settings: lock task blocks in-task starts instead (flag 64).
        check_mask("Android 14/15: lock task bloquea inicios dentro de la tarea (BLOCK_ACTIVITY_START_IN_TASK)", lambda f: f & 64)
    else:
        info("Android 16+", "Ajustes no se puede suspender ni hay lock task: lo devuelve el vigilante de primer plano (ver pruebas de intents)")
    check("la app de trabajo no está suspendida", not suspended(APP))
    want = APP if mode == "single" else A.pkg
    check("en primer plano: " + ("la app fijada" if mode == "single" else "el inicio del quiosco"),
          until(lambda: top().startswith(want + "/"), 20), top())


def t_settings_intents(mode):
    for action in SETTINGS_ACTIONS:
        out = sh(f"am start -a {action}")
        time.sleep(2)
        check(f"adb am start {action.split('.')[-1]}: no abre Ajustes", not settings_in_front(), top() + " | " + out.strip()[-120:])
        sh("input keyevent KEYCODE_BACK")
        home()
    for new_task in (False, True):
        for action in SETTINGS_ACTIONS[:2]:
            sh(f"am start -n {APP}/.Main")
            time.sleep(1.5)
            sh("logcat -c")
            sh(f"am start -n {APP}/.Main --es action {action}" + (" --ez newTask true" if new_task else ""))
            time.sleep(2.5)
            log = sh("logcat -d -s EscapeTest:I").strip().splitlines()
            check(f"app permitida → intent {action.split('.')[-1]}" + (" (tarea nueva)" if new_task else "") + ": no abre Ajustes",
                  not settings_in_front(), top() + " | " + (log[-1][-90:] if log else "sin log"))
            sh("input keyevent KEYCODE_BACK")
    home()
    # Open the work app the way the user does: its icon (multi-app) or the kiosk's "open <app>" button, which a
    # pinned app's kiosk shows after it was left several times in a row (single-app; else it reopens by itself).
    nodes = ui()
    button = find(nodes, **{"content-desc": "kiosk-app-" + APP}) or find(nodes, **{"content-desc": "kiosk-open-app"})
    if button:
        tap(button)
    time.sleep(4)
    check("tras los intentos, la app de trabajo se abre normalmente (no queda Ajustes encima)",
          until(lambda: top().startswith(APP + "/"), 8), top())
    home()


def t_status_bar(mode):
    sh("cmd statusbar expand-settings")
    time.sleep(2)
    nodes = ui()
    gear = find(nodes, **{"resource-id": "settings_button"}) or find(nodes, **{"content-desc": "Open settings"}) \
        or find(nodes, **{"content-desc": "Abrir configuración"}) or find(nodes, **{"content-desc": "Settings"})
    panel = any("com.android.systemui" == (n.get("package") or "") and "quick" in (n.get("resource-id") or "").lower() for n in nodes)
    info("barra de estado", "panel de ajustes rápidos " + ("ABIERTO" if panel or gear else "cerrado") + (", con engranaje" if gear else ""))
    if SDK >= 36:
        check("Android 16+: la barra de estado no se despliega (por defecto)", not (panel or gear))
    if gear:
        tap(gear)
        time.sleep(2.5)
    check("barra de estado / engranaje: no abre Ajustes", not settings_in_front(), top())
    sh("cmd statusbar collapse")
    sh("input keyevent KEYCODE_BACK")
    home()


def in_kiosk_front():
    """The pinned/allowed app or a kiosk screen (after several exits of a pinned app the kiosk shows "open <app>")."""
    return top().startswith(APP + "/") or top().startswith(A.pkg + "/")


def t_home_recents(mode):
    home()
    check("Inicio: sigue en el quiosco", until(in_kiosk_front, 10), top())
    sh("input keyevent KEYCODE_APP_SWITCH")
    time.sleep(2)
    t = top()
    info("Recientes", "en primer plano: " + t)
    check("Recientes: no muestra Ajustes ni saca del quiosco", not settings_in_front())
    # Anything Recents can open is an allowed task or a suspended app: open what it offers and look again.
    w, h, _ = screen()
    tap((w // 2, h // 2))
    time.sleep(2)
    check("Recientes → abrir tarjeta: sigue en apps permitidas",
          top().split("/")[0] in (APP, A.pkg, "android", "") or "launcher" in top().lower() or "systemui" in top(), top())
    home()
    check("tras Recientes, Inicio vuelve al quiosco", until(in_kiosk_front, 10), top())


def power_menu_shown():
    sh("input keyevent --longpress KEYCODE_POWER")
    time.sleep(2.5)
    shown = bool(re.search(r"GlobalActions|ActionsDialog|globalactions", sh("dumpsys window windows") + sh("dumpsys window | grep -i -E 'mCurrentFocus|mFocusedWindow'")))
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1)
    if "mWakefulness=Asleep" in sh("dumpsys power"):
        sh("input keyevent KEYCODE_WAKEUP")
        time.sleep(1)
    return shown


def lock_task_features():
    """The lock-task feature mask the policy set (-1 when this release does not print it)."""
    dp = sh("dumpsys device_policy")
    for pat in (r"LockTaskPolicy \{[^}]*mFlags=\s*(\d+)", r"mLockTaskFeatures[=:]\s*(\d+)", r"lockTaskFeatures[=:]\s*(\d+)"):
        m = re.search(pat, dp)
        if m:
            return int(m.group(1))
    m = re.search(r"mLockTaskFeatures=\{[^}]*?(\d+)\s*\}", sh("dumpsys activity activities"))
    return int(m.group(1)) if m else -1


def check_mask(name, want):
    feats = lock_task_features()
    if feats < 0:
        info(name, "esta versión no muestra la máscara de lock task en dumpsys")
    else:
        check(name, want(feats), f"mask={feats}")


def t_power(mode):
    shown = power_menu_shown()
    info("menú de encendido (por defecto)", "se abre" if shown else "no se abre"
         + ("" if POWER_DETECTABLE else " (en esta imagen tampoco se detecta fuera del quiosco: no concluyente)"))
    check("tras el menú de encendido sigue en el quiosco", not settings_in_front() and (lock_task() == "LOCKED" or SDK >= 36), lock_task())
    if 28 <= SDK < 36:
        s, d = enter(mode, lock_buttons=True)
        check("quiosco con «bloquear botones»: aplicado", s == "done", f"{s} {d}")
        check_mask("con «bloquear botones» la política quita GLOBAL_ACTIONS (16) de lock task", lambda f: f & 16 == 0)
        if POWER_DETECTABLE:
            check("con «bloquear botones» el menú de encendido NO se abre", not power_menu_shown())
        else:
            info("menú de encendido con «bloquear botones»", "no se abre (no concluyente en esta imagen; ver la máscara de lock task)")
        enter(mode)
    else:
        info("menú de encendido bloqueable", "no en esta versión (Android < 9 o quiosco sin lock task)")


def t_tether(mode):
    home()
    tile = find(ui(), **{"content-desc": "kiosk-app-dallycontrol.tether"})
    if not tile:
        info("Compartir datos", "sin ícono (bloqueado por política o no visible)")
        return
    tap(tile)
    time.sleep(3)
    t = top()
    if not settings_in_front():
        info("Compartir datos", "esta imagen no abre la pantalla de zona Wi‑Fi: " + t)
    else:
        check("Compartir datos abre solo la pantalla de zona Wi‑Fi", "ether" in t or "otspot" in t, t)
        acts = sh("dumpsys activity activities | grep -E 'Hist +#|Hist #' | grep com.android.settings").strip().splitlines()
        task = re.search(r" (t\d+)\}", next((a for a in acts if "ether" in a or "otspot" in a), ""))
        same = [a for a in acts if task and (" " + task.group(1) + "}") in a]
        check("…sola en su tarea (sin el resto de Ajustes detrás)", len(same) == 1, " / ".join(a.strip()[-60:] for a in acts))
        sh("input keyevent KEYCODE_BACK")
        time.sleep(2)
        check("Atrás desde zona Wi‑Fi vuelve al quiosco", kiosk_home_in_front(), top())
    home()
    time.sleep(1)
    if 24 <= SDK < 34:
        check("al volver, Ajustes queda suspendido de nuevo", until(lambda: suspended("com.android.settings"), 10))
    sh("am start -a android.settings.SETTINGS")
    time.sleep(2)
    check("después de Compartir datos, Ajustes sigue sin abrir", not settings_in_front(), top())
    home()


def t_exit(mode):
    since = int(time.time() * 1000) - 2000
    for i, bad in enumerate(("0000", "1111", "2222"), 1):
        msg = try_exit(mode, bad)
        check(f"7 toques + contraseña incorrecta #{i}: no sale", msg == "" and (lock_task() == "LOCKED" or SDK >= 36) and not_exited(), msg or top())
    msg = try_exit(mode, PASSWORD)
    check("4.º intento durante la espera: ni siquiera pide contraseña", msg not in ("", "no dialog", "no exit screen"), msg)
    check("…y la contraseña correcta no sirve durante la espera", not_exited(), top())
    ev = until(lambda: [e for e in events("kioskExitFailed,kioskExitDenied", since)], 60, 3) or []
    kinds = sorted({e.get("type") for e in ev})
    check("intentos fallidos registrados en el servidor", "kioskExitFailed" in kinds, f"{kinds}")
    print("        (esperando los 30 s de bloqueo)", flush=True)
    time.sleep(31)
    msg = try_exit(mode, PASSWORD)
    check("pasada la espera, la contraseña correcta saca del quiosco",
          msg == "" and until(lambda: lock_task() == "NONE" and not kiosk_home_in_front() and not top().startswith(APP + "/"), 20), msg or top())
    check("al salir: Ajustes deja de estar suspendido", until(lambda: not suspended("com.android.settings"), 15))
    sh("am start -a android.settings.SETTINGS")
    check("al salir: Ajustes vuelve a abrir (el quiosco ya no lo bloquea)", until(settings_in_front, 10), top())
    time.sleep(2)
    check("…y se queda abierto (nada lo devuelve)", settings_in_front(), top())
    sh("input keyevent KEYCODE_HOME")
    check("al salir: se levantan las restricciones del quiosco", until(lambda: not ({"no_add_user", "no_safe_boot"} & restrictions()), 10), str(sorted(restrictions())))
    check("salida registrada en el servidor", until(lambda: events("kioskExit", since), 60, 3))


def not_exited():
    """Still in the kiosk: lock task on, or (Android 16+) a kiosk screen / allowed app in front and the HOME claim kept."""
    if SDK < 36:
        return lock_task() == "LOCKED"
    return in_kiosk_front() and "KioskHomeAlias" in sh("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN")


def t_no_password(mode):
    s, d = enter(mode, password=None)
    check("quiosco sin contraseña: aplicado", s == "done", f"{s} {d}")
    msg = try_exit(mode, PASSWORD)
    check("sin contraseña: los 7 toques no ofrecen salida", msg in ("no dialog", "no exit screen") or "consola" in msg or "console" in msg, msg)
    check("sin contraseña: sigue en el quiosco", not_exited(), top())
    sh("input keyevent KEYCODE_BACK")  # the taps may have opened the kiosk's own quick-settings panel


def t_legacy(mode):
    """What a server from before this change would send: gesture exit and no password."""
    p = json.dumps(payload(mode, password=None)).replace("'", "''")
    sql = f"INSERT INTO agentCommand (deviceNumber, type, payload, status, createdAt) VALUES ('{A.device_id}', 'kiosk.enter', '{p}', 'pending', {int(time.time() * 1000)});"
    r = subprocess.run(A.legacy_psql + " -c " + json.dumps(sql), shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        info("política antigua (gesto, sin contraseña)", "no se pudo insertar: " + r.stderr.strip()[-100:])
        return
    since = int(time.time() * 1000) - 2000
    run("noop.wake", timeout=40)  # an unknown command: it only makes the device check in, which delivers the raw row
    time.sleep(8)
    msg = try_exit(mode, "")
    check("política antigua (gesto, sin contraseña): el agente se niega a salir y lo dice",
          ("consola" in msg or "console" in msg) and not_exited(), msg)
    check("…y lo reporta (kioskExitDenied)", until(lambda: events("kioskExitDenied", since), 60, 3))


def t_reboot(mode):
    s, d = enter(mode)
    check("quiosco antes de reiniciar: aplicado", s == "done", f"{s} {d}")
    root = "adbd is already running as root" in adb("root") or "restarting adbd as root" in adb("root")
    time.sleep(3)
    if root:
        sh("setprop persist.sys.safemode 1")
        info("modo seguro", "pedido con persist.sys.safemode=1 antes de reiniciar")
    else:
        info("modo seguro", "adb sin root: solo se verifica la restricción (no_safe_boot)")
    adb("reboot")
    time.sleep(15)
    adb("wait-for-device", timeout=300)
    until(lambda: sh("getprop sys.boot_completed").strip() == "1", 300, 3)
    time.sleep(25)
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    time.sleep(5)
    if root:
        check("reinicio pidiendo modo seguro: arranca NORMAL", "mSafeMode=true" not in sh("dumpsys window") and sh("getprop ro.sys.safemode").strip() != "1")
        adb("root"); time.sleep(3); sh("setprop persist.sys.safemode ''")
    want = APP if mode == "single" else A.pkg
    check("tras reiniciar vuelve al quiosco", until(lambda: top().startswith(want + "/") or kiosk_home_in_front(), 60, 2), top())
    if SDK < 36:
        check("tras reiniciar: lock task LOCKED", until(lambda: lock_task() == "LOCKED", 40, 2), lock_task())
    sh("am start -a android.settings.SETTINGS")
    time.sleep(2.5)
    check("tras reiniciar: Ajustes sigue sin abrir", not settings_in_front(), top())
    home()
    if SDK >= 36:
        sh("cmd statusbar expand-settings"); time.sleep(2)
        n = ui()
        check("tras reiniciar (Android 16+): la barra de estado sigue cerrada",
              not any("quick" in (x.get("resource-id") or "").lower() for x in n))
        sh("cmd statusbar collapse")


def main():
    global SDK, MODE, POWER_DETECTABLE
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    if api("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5}).get("status") != "OK":
        sys.exit("login failed")
    SDK = int(sh("getprop ro.build.version.sdk").strip())
    release = sh("getprop ro.build.version.release").strip()
    print(f"== {A.serial}: Android {release} (API {SDK})")
    if APP not in sh(f"pm list packages {APP}"):
        print(adb("install", "-r", A.app_apk).strip())
    sh("settings put secure show_ime_with_hard_keyboard 1")
    sh("settings put global power_button_long_press 1")  # Pixel images: long press = assistant; 1 = the power menu
    sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")
    modes = [m for m in A.modes.split(",") if m]
    run("kiosk.exit", timeout=60)
    time.sleep(2)
    POWER_DETECTABLE = power_menu_shown()
    print(f"   (menú de encendido detectable fuera del quiosco: {'sí' if POWER_DETECTABLE else 'no'})")
    try:
        for mode in modes:
            MODE = mode
            print(f"== modo {mode}")
            s, d = enter(mode)
            if not check("kiosk.enter aplicado", s == "done", f"{s} {d}"):
                continue
            t_state(mode)
            t_settings_intents(mode)
            t_status_bar(mode)
            t_home_recents(mode)
            t_power(mode)
            if mode == "launcher":
                t_tether(mode)
            t_exit(mode)
            t_no_password(mode)
            if A.legacy_psql:
                t_legacy(mode)
            s, d = run("kiosk.exit")
            check("salida desde la consola (kiosk.exit)", s == "done" and until(lambda: lock_task() == "NONE" and not suspended("com.android.settings"), 20), f"{s} {d}")
        if A.reboot and modes:
            MODE = modes[0] + "+reboot"
            print("== reinicio")
            t_reboot(modes[0])
    finally:
        try:
            run("kiosk.exit", timeout=60)
        except Exception as e:  # noqa: BLE001
            print("cleanup kiosk.exit:", e)
    fails = [r for r in RESULTS if r[2] is False]
    passed = [r for r in RESULTS if r[2] is True]
    print(f"RESULT: Android {release} (API {SDK}) PASS={len(passed)} FAIL={len(fails)}")
    if A.summary:
        with open(A.summary, "w") as f:
            f.write(f"### Android {release} (API {SDK}): {len(passed)} PASS, {len(fails)} FAIL\n\n| Modo | Prueba | Resultado | Detalle |\n|---|---|---|---|\n")
            for m, n, ok, det in RESULTS:
                f.write(f"| {m} | {n} | {'PASS' if ok else 'INFO' if ok is None else 'FAIL'} | {(det or '').replace('|', '/')[:140] if ok is not True else ''} |\n")
    sys.exit(1 if fails else 0)


main()
