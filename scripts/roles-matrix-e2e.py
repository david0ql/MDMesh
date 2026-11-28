#!/usr/bin/env python3
"""
Proves, against a running stack, that every permission of the role matrix (web/src/data/roleCapabilities.ts) really
restricts the API — not just the console's buttons.

For EACH permission it builds a role that holds every permission of the matrix EXCEPT that one, signs in as a user
with that role and calls the endpoints the permission is supposed to gate: every call must be refused
(HTTP 401/403 or the envelope error.permission.denied). Then it adds the permission to the same role and repeats:
every call must now get past the permission check. The probes are harmless: they use empty bodies or ids that do not
exist, so an allowed call ends in a validation error instead of changing anything (the few that do create something
clean it up).

The matrix is run twice: with a user over the whole fleet and with a user limited to one folder (for whom the
routes outside the folder allow-list stay closed whatever the role says — those probes are skipped for them).

It also checks the role life cycle: create a role with any combination, duplicate it, the built-in roles cannot be
deleted nor the administrator's edited, a role in use cannot be deleted, nobody can hand out more than they hold,
and the two ready-made roles behave as described (read-only: no command at all; support technician: safe commands
yes, lock / reboot / change passcode / wipe no).

Usage:  scripts/roles-matrix-e2e.py --api http://localhost:8080 [--admin admin] [--password admin]
                                    [--device <number of an enrolled device>]
  --device  an enrolled device to try the device-level probes on. Prefer running WITHOUT it (a device record is created
            and removed): on a real device the probes send a "sync now" wake-up, rewrite its description with the same
            text, and send empty mobile-data / hardware / app-permission settings and a remote-support start + stop.
Exit code 0 = every permission restricts what it says. Standard library only.
"""
import argparse
import hashlib
import http.cookiejar
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PASSWORD = "Matriz#2026"
checks = []


def md5u(s):
    return hashlib.md5(s.encode()).hexdigest().upper()


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, text=None):
        data = None
        headers = {"Accept": "application/json, */*", "User-Agent": "roles-matrix-e2e/1.0"}
        if text is not None:
            data, headers["Content-Type"] = text.encode(), "text/plain"
        elif body is not None:
            data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
        req = urllib.request.Request(self.base + "/rest" + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=60) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        try:
            return status, json.loads(raw.decode() or "null")
        except ValueError:
            return status, None

    def ok(self, method, path, body=None, text=None):
        status, env = self.call(method, path, body, text)
        if status != 200 or not isinstance(env, dict) or env.get("status") != "OK":
            raise SystemExit("FATAL %s %s -> HTTP %s %s" % (method, path, status, json.dumps(env)[:300]))
        return env.get("data")

    def login(self, user, password):
        status, env = self.call("POST", "/public/auth/login", {"login": user, "password": md5u(password)})
        return env if isinstance(env, dict) else {"status": "ERROR"}


def is_denied(status, env):
    if status in (401, 403):
        return True
    return isinstance(env, dict) and env.get("status") == "ERROR" and "permission.denied" in str(env.get("message"))


def check(name, cond, detail=""):
    checks.append((name, bool(cond)))
    if not cond:
        print("  FAIL  %s   <- %s" % (name, str(detail)[:300]))
    return bool(cond)


def new_user(admin, api, login, role_id, folders):
    """Creates a user with a custom role and completes the forced first-login password change. -> (session, id)"""
    u = admin.ok("PUT", "/private/dc/users", {"login": login, "name": login, "password": md5u("Inicial#2026"),
                                              "role": "custom", "roleId": role_id, "folders": folders})
    s = Api(api)
    first = s.login(login, "Inicial#2026")
    token = (first.get("data") or {}).get("passwordResetToken")
    if first.get("status") != "OK" or not token:
        raise SystemExit("FATAL: first login of %s: %s" % (login, first))
    s.ok("POST", "/public/passwordReset/reset", {"passwordResetToken": token, "newPassword": md5u(PASSWORD)})
    time.sleep(1.2)
    if s.login(login, PASSWORD).get("status") != "OK":
        raise SystemExit("FATAL: login of %s after the password change" % login)
    return s, u["id"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--admin", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--device")
    ap.add_argument("--only", help="comma-separated permissions to check (default: all of them)")
    a = ap.parse_args()
    stamp = time.strftime("%H%M%S")

    admin = Api(a.api)
    if admin.login(a.admin, a.password).get("status") != "OK":
        raise SystemExit("FATAL: admin login failed")
    perms = {p["name"]: p["id"] for p in admin.ok("GET", "/private/roles/permissions")}

    # --- fixtures ------------------------------------------------------------------------------------------------
    folder = admin.ok("POST", "/private/fleet/v1/groups", {"name": "matriz-" + stamp})
    empty_folder = admin.ok("POST", "/private/fleet/v1/groups", {"name": "matriz-vacia-" + stamp, "parentId": folder["id"]})
    created_device = None
    if a.device:
        device = admin.ok("GET", "/private/devices/number/" + urllib.parse.quote(a.device))
    else:
        any_config = admin.ok("GET", "/private/configurations/search")[0]
        number = "matriz-dev-" + stamp
        admin.ok("PUT", "/private/devices", {"number": number, "description": "Equipo de prueba de la matriz",
                                             "configurationId": any_config["id"]})
        device = admin.ok("GET", "/private/devices/number/" + number)
        created_device = device
    scope = admin.ok("GET", "/private/fleet/v1/devices/%d/scope" % device["id"]) or {}
    original_group = scope.get("groupId")
    admin.ok("POST", "/private/fleet/v1/devices/group", {"deviceIds": [device["id"]], "groupId": folder["id"]})
    dev, dev_id, description = urllib.parse.quote(device["number"]), device["id"], device.get("description") or device["number"]
    config = admin.ok("GET", "/private/configurations/search")[0]
    script = admin.ok("PUT", "/private/scripts", {"name": "matriz-bloqueo-" + stamp, "description": "", "body": "lock", "quickAction": False})

    def temp_config(tag):
        name = "matriz-%s-%s-%d" % (tag, stamp, int(time.time() * 1000) % 100000)
        admin.ok("PUT", "/private/configurations/copy", {"id": config["id"], "name": name, "description": "temporal"})
        return [c for c in admin.ok("GET", "/private/configurations/search") if c["name"] == name][0]

    NO = 999999  # an id that does not exist
    lock_bulk = {"deviceIds": [NO], "command": {"type": "device.lock"}}
    uninstall_bulk = {"deviceIds": [NO], "command": {"type": "app.uninstall", "payload": json.dumps({"packageName": "com.no.existe"})}}

    # permission -> probes: (method, path, body, where)   where: "both" | "fleet" (routes closed to folder users)
    # A body given as a callable is built at call time (it needs something freshly created).
    MATRIX = {
        "edit_devices": [
            ("POST", "/private/agent/v1/devices/%s/commands" % dev, {}, "both"),
            ("POST", "/private/agent/v1/bulk/commands", {}, "both"),
            ("POST", "/private/agent/v1/devices/%s/sync" % dev, None, "both"),
            ("POST", "/private/agent/v1/devices/%s/remote/setup" % "no-existe", None, "fleet"),
            ("POST", "/private/agent/v1/devices/%s/remote/start" % "no-existe", {"viewOnly": True}, "fleet"),
            ("POST", "/private/agent/v1/devices/%s/config/reapply" % "no-existe", None, "fleet"),
            ("POST", "/private/agent/v1/devices/%s/syncApps" % "no-existe", None, "fleet"),
            ("POST", "/private/agent/v1/devices/recover", {}, "fleet"),
            ("POST", "/private/scripts/%d/run" % NO, {"deviceIds": [NO]}, "both"),
            ("POST", "/private/announcements", {}, "both"),
            ("POST", "/private/fleet/v1/devices/refresh", {}, "fleet"),
            ("POST", "/private/devices/deleteBulk", {"ids": [NO]}, "fleet"),  # a folder user is refused an id outside their folders
            ("DELETE", "/private/devices/%d" % NO, None, "fleet"),
            ("POST", "/private/agent/v1/rollout", {}, "fleet"),
            # modules merged later: mobile data, hardware and ports, app permissions, remote support, cancel a command
            ("POST", "/private/agent/v1/devices/%s/mobileData" % dev, {}, "fleet"),
            ("PUT", "/private/agent/v1/devices/%s/hardware" % dev, {}, "fleet"),
            ("PUT", "/private/agent/v1/devices/%s/permissions" % dev, {}, "fleet"),
            ("POST", "/private/agent/v1/devices/no-existe/commands/%d/cancel" % NO, None, "fleet"),
            ("POST", "/private/support/v1/devices/%s/start" % dev, {}, "fleet"),
            ("POST", "/private/support/v1/devices/%s/stop" % dev, {}, "fleet"),
            ("POST", "/private/fleet/v1/groups/%d/commands" % empty_folder["id"], {"command": {"type": "device.ring"}}, "both"),
        ],
        "disruptive_actions": [
            ("POST", "/private/agent/v1/devices/no-existe/commands", {"type": "device.wipe"}, "fleet"),
            ("POST", "/private/agent/v1/devices/no-existe/commands", {"type": "device.lock"}, "fleet"),
            ("POST", "/private/agent/v1/devices/no-existe/commands", {"type": "device.reboot"}, "fleet"),
            ("POST", "/private/agent/v1/devices/no-existe/commands", {"type": "device.passcodeReset"}, "fleet"),
            ("POST", "/private/agent/v1/bulk/commands", lock_bulk, "both"),
            ("POST", "/private/fleet/v1/groups/%d/commands" % empty_folder["id"], {"command": {"type": "device.reboot"}}, "both"),
            ("POST", "/private/scripts/%d/run" % script["id"], {"deviceIds": [NO]}, "both"),
        ],
        "edit_device_desc": [
            ("POST", "/private/devices/%d/description" % dev_id, ("text", description), "both"),
        ],
        "edit_device_app_settings": [
            ("POST", "/private/devices/%d/applicationSettings/notify" % NO, None, "fleet"),
        ],
        "manage_fleet": [
            ("POST", "/private/fleet/v1/groups", {}, "both"),
            ("PUT", "/private/fleet/v1/groups/%d" % empty_folder["id"], {}, "both"),
            ("PUT", "/private/fleet/v1/groups/%d/accountEmail" % empty_folder["id"], {"accountEmail": "no-es-correo"}, "both"),
            ("POST", "/private/fleet/v1/devices/group", {}, "both"),
            ("PUT", "/private/fleet/v1/devices/configuration", {}, "both"),
            ("PUT", "/private/fleet/v1/devices/accountEmail", {}, "both"),
            ("POST", "/private/fleet/v1/groups/%d/commands" % empty_folder["id"], {"command": {"type": "device.ring"}}, "both"),
            ("POST", "/private/fleet/v1/global/commands", {}, "fleet"),
            ("POST", "/private/fleet/v1/syncApps", {}, "fleet"),
            ("POST", "/private/agent/v1/devices/no-existe/commands", {"type": "app.uninstall"}, "fleet"),
            ("POST", "/private/agent/v1/bulk/commands", uninstall_bulk, "both"),
            ("POST", "/private/agent/v1/devices/%s/kioskApps" % dev, {}, "both"),
            ("POST", "/private/agent/v1/apps/install/retry", {}, "fleet"),
            ("POST", "/private/geofences/v1/zones", {}, "both"),
            ("DELETE", "/private/geofences/v1/zones/%d" % NO, None, "both"),
            ("POST", "/private/geofences/v1/recipients", {"groupId": empty_folder["id"]}, "both"),
            ("GET", "/private/reports/v1/schedules", None, "both"),
            ("POST", "/private/reports/v1/schedules", {}, "fleet"),
            ("GET", "/private/reports/v1/inventory", None, "both"),
            ("GET", "/private/reports/v1/offline", None, "both"),
            ("GET", "/private/reports/v1/export?report=offline&format=csv", None, "both"),
            ("GET", "/private/reports/v1/inventory/export?format=csv", None, "both"),
            ("GET", "/private/alerts/v1/list", None, "both"),
            ("GET", "/private/alerts/v1/export?format=csv", None, "both"),
            ("POST", "/private/alerts/v1/review", {"id": NO, "reviewed": True}, "both"),
            # factory-reset protection (FRP) defaults of a folder
            ("PUT", "/private/protection/v1/defaults/groups/%d" % empty_folder["id"], {}, "fleet"),
        ],
        "enroll_devices": [
            ("POST", "/private/agent/v1/codes", {}, "both"),
            ("GET", "/private/agent/v1/codes", None, "both"),
            ("DELETE", "/private/agent/v1/codes/%d" % NO, None, "fleet"),
            ("POST", "/private/agent/v1/token", {"groupId": NO}, "fleet"),
        ],
        "configurations": [
            ("GET", "/private/configurations/search", None, "both"),
            ("GET", "/private/configurations/%d" % config["id"], None, "both"),
            ("GET", "/private/configurations/%d/history" % config["id"], None, "both"),
            ("POST", "/private/app-groups", {}, "fleet"),
            ("DELETE", "/private/app-groups/%d" % NO, None, "fleet"),
            ("DELETE", "/private/wifi-certificates/%d" % NO, None, "fleet"),  # corporate Wi-Fi certificates
        ],
        "add_config": [
            ("PUT", "/private/configurations", lambda: {"name": "matriz-nueva-%s-%d" % (stamp, int(time.time() * 1000) % 100000)}, "both"),
        ],
        "copy_config": [
            ("PUT", "/private/configurations/copy", lambda: {"id": config["id"], "name": "matriz-copia-%s-%d" % (stamp, int(time.time() * 1000) % 100000)}, "fleet"),
            ("DELETE", lambda: "/private/configurations/%d" % temp_config("borrar")["id"], None, "both"),
        ],
        "applications": [
            ("GET", "/private/applications/search", None, "both"),
            ("GET", "/private/applications/%d/versions" % NO, None, "both"),
        ],
        "edit_applications": [
            ("DELETE", "/private/applications/%d" % NO, None, "fleet"),
            ("POST", "/private/applications/%d/remove-from-my-configs" % NO, None, "both"),
            ("PUT", "/private/dc/versions/%d/label" % NO, {"label": ""}, "fleet"),
        ],
        "edit_application_versions": [
            ("DELETE", "/private/applications/versions/%d" % NO, None, "fleet"),
        ],
        "files": [
            ("GET", "/private/web-ui-files/search", None, "fleet"),
        ],
        "edit_files": [
            ("POST", "/private/web-ui-files/remove", {}, "fleet"),
            ("POST", "/private/web-ui-files/chunked", {}, "fleet"),
            ("POST", "/private/web-ui-files/fetch", {}, "fleet"),
        ],
        "manage_scripts": [
            ("PUT", "/private/scripts", {}, "both"),
            ("DELETE", "/private/scripts/%d" % NO, None, "both"),
        ],
        "manage_users": [
            ("GET", "/private/dc/users", None, "both"),
            ("GET", "/private/dc/users/roles", None, "both"),
            ("PUT", "/private/dc/users", {}, "both"),
            ("DELETE", "/private/dc/users/%d" % NO, None, "both"),
        ],
        "view_audit": [
            ("GET", "/private/audit/v1/log", None, "both"),
            ("GET", "/private/audit/v1/facets", None, "both"),
            ("GET", "/private/audit/v1/export?format=csv", None, "both"),
            ("GET", "/private/audit/v1/devices/%s/activity" % dev, None, "both"),
            ("GET", "/private/audit/v1/settings", None, "both"),
        ],
        "settings": [
            ("DELETE", "/private/icons/%d" % NO, None, "fleet"),
            ("GET", "/private/users/all", None, "fleet"),
            ("DELETE", "/private/groups/%d" % NO, None, "fleet"),
        ],
        "push_api": [
            ("POST", "/private/push", {}, "fleet"),
        ],
    }
    missing = sorted(set(MATRIX) - set(perms))
    if missing:
        raise SystemExit("FATAL: the server lacks these permissions: %s" % missing)
    all_ids = lambda names: [{"id": perms[n]} for n in names]  # noqa: E731

    def probe(session, p):
        method, path, body, _ = p
        path = path() if callable(path) else path
        body = body() if callable(body) else body
        if isinstance(body, tuple):
            return session.call(method, path, text=body[1]) + (path,)
        return session.call(method, path, body) + (path,)

    role = admin.ok("PUT", "/private/roles", {"name": "matriz-" + stamp, "description": "rol de la prueba de matriz", "permissions": []})
    fleet_user, fleet_id = new_user(admin, a.api, "matriz.flota." + stamp, role["id"], [])
    folder_user, folder_id = new_user(admin, a.api, "matriz.carpeta." + stamp, role["id"], [folder["id"]])

    def set_role(names):
        admin.ok("PUT", "/private/roles", {"id": role["id"], "name": "matriz-" + stamp, "description": "rol de la prueba de matriz",
                                           "permissions": all_ids(names)})

    total = 0
    only = [p.strip() for p in a.only.split(",")] if a.only else list(MATRIX)
    for perm in MATRIX:
        if perm not in only:
            continue
        others = [n for n in MATRIX if n != perm]
        results = {}
        for holds in (False, True):
            set_role(others + ([perm] if holds else []))
            for who, session in (("flota", fleet_user), ("carpeta", folder_user)):
                for p in MATRIX[perm]:
                    if who == "carpeta" and p[3] == "fleet":
                        continue
                    status, env, path = probe(session, p)
                    results[(who, p[0], p[1] if not callable(p[1]) else "(temporal)", json.dumps(p[2]) if not callable(p[2]) else "(nuevo)", holds)] = \
                        (is_denied(status, env), status, env, path)
        bad = 0
        for (who, method, path, body, holds), (was_denied, status, env, real) in sorted(results.items(), key=lambda kv: str(kv[0])):
            total += 1
            msg = (env or {}).get("message") if isinstance(env, dict) else None
            label = "%s [%s] %s %s %s" % (perm, who, method, path, body if body not in ("null", "{}") else "")
            if holds:
                if not check(label.strip() + "  CON el permiso: pasa", not was_denied, "HTTP %s %s" % (status, msg)):
                    bad += 1
            else:
                if not check(label.strip() + "  SIN el permiso: denegado", was_denied, "HTTP %s %s" % (status, msg)):
                    bad += 1
        print("%-26s %2d endpoints x (flota, carpeta) x (sin, con)  %s" % (perm, len(MATRIX[perm]), "ok" if bad == 0 else "%d FALLAS" % bad))

    # --- role life cycle ------------------------------------------------------------------------------------------
    print("== ciclo de vida de roles ==")
    combo = ["edit_devices", "view_audit", "files"]
    custom = admin.ok("PUT", "/private/roles", {"name": "matriz-combo-" + stamp, "description": "combinación libre", "permissions": all_ids(combo)})
    roles = {r["id"]: r for r in admin.ok("GET", "/private/roles/all")}
    check("rol personalizado con una combinación cualquiera", sorted(p["name"] for p in roles[custom["id"]]["permissions"]) == sorted(combo),
          roles.get(custom["id"]))
    copy = admin.ok("PUT", "/private/roles", {"name": "Copia de matriz-combo-" + stamp, "description": "duplicado",
                                              "permissions": [{"id": p["id"]} for p in roles[custom["id"]]["permissions"]]})
    roles = {r["id"]: r for r in admin.ok("GET", "/private/roles/all")}
    check("duplicar un rol: mismo conjunto de permisos, otro id",
          copy["id"] != custom["id"] and sorted(p["name"] for p in roles[copy["id"]]["permissions"]) == sorted(combo))
    s, e = admin.call("PUT", "/private/roles", {"name": "matriz-combo-" + stamp, "permissions": []})
    check("nombre de rol repetido: rechazado", e.get("status") == "ERROR", e)
    for rid in (1, 2, 3, 200):
        s, e = admin.call("DELETE", "/private/roles/%d" % rid)
        check("el rol del sistema %d no se puede eliminar" % rid, e.get("status") == "ERROR", e)
    s, e = admin.call("PUT", "/private/roles", {"id": 2, "name": "Admin", "permissions": []})
    check("el rol de administrador no se puede modificar", e.get("status") == "ERROR", e)
    s, e = admin.call("DELETE", "/private/roles/%d" % role["id"])
    check("un rol asignado a usuarios no se puede eliminar", e.get("status") == "ERROR" and "asignado" in str(e.get("message")), e)
    admin.ok("DELETE", "/private/roles/%d" % copy["id"])
    check("un rol sin usuarios sí se elimina", copy["id"] not in {r["id"] for r in admin.ok("GET", "/private/roles/all")})

    print("== nadie reparte más permisos de los que tiene ==")
    set_role(["manage_users", "edit_devices"])
    big = admin.ok("PUT", "/private/roles", {"name": "matriz-grande-" + stamp, "permissions": all_ids(["manage_users", "edit_devices", "disruptive_actions", "manage_fleet"])})
    body = {"login": "matriz.x." + stamp, "password": md5u("Abcdef#12"), "folders": [folder["id"]]}
    s, e = folder_user.call("PUT", "/private/dc/users", dict(body, role="custom", roleId=big["id"]))
    check("un gestor de carpeta no puede asignar un rol mayor que el suyo", e.get("status") == "ERROR" and "más permisos" in str(e.get("message")), e)
    s, e = folder_user.call("PUT", "/private/dc/users", dict(body, role="admin", folders=[]))
    check("un gestor de carpeta no puede crear administradores", e.get("status") == "ERROR", e)
    s, e = fleet_user.call("PUT", "/private/dc/users", dict(body, role="admin", folders=[]))
    check("un rol personalizado sobre toda la flota tampoco puede crear administradores", e.get("status") == "ERROR", e)
    s, e = fleet_user.call("PUT", "/private/dc/users", dict(body, role="custom", roleId=role["id"], folders=[]))
    check("ni dar acceso a toda la flota", e.get("status") == "ERROR", e)
    s, e = folder_user.call("PUT", "/private/dc/users", dict(body, role="custom", roleId=role["id"]))
    check("sí puede crear un usuario con su mismo rol dentro de su carpeta", e.get("status") == "OK", e)
    sub_id = (e.get("data") or {}).get("id")
    assignable = [r["id"] for r in folder_user.ok("GET", "/private/dc/users/roles")]
    check("la lista de roles asignables no ofrece los mayores", big["id"] not in assignable and role["id"] in assignable, assignable)
    s, e = folder_user.call("PUT", "/private/roles", {"name": "matriz-escalada-" + stamp, "permissions": all_ids(list(MATRIX))})
    check("no puede crear ni editar roles", is_denied(s, e), e)
    s, e = folder_user.call("PUT", "/private/roles", {"id": role["id"], "name": "matriz-" + stamp, "permissions": all_ids(list(MATRIX))})
    check("no puede ampliar su propio rol", is_denied(s, e), e)

    print("== plantillas: solo lectura y técnico de soporte ==")
    roles = {r["id"]: r for r in admin.ok("GET", "/private/roles/all")}
    check("existen los roles «Solo lectura» (210) y «Técnico de soporte» (211)", 210 in roles and 211 in roles, sorted(roles))
    ro, ro_id = new_user(admin, a.api, "matriz.lectura." + stamp, 210, [folder["id"]])
    st, st_id = new_user(admin, a.api, "matriz.soporte." + stamp, 211, [folder["id"]])
    cmd = "/private/agent/v1/devices/%s/commands" % dev
    s, e = ro.call("GET", "/private/agent/v1/devices/%s/state" % dev)
    check("solo lectura: ve el dispositivo de su carpeta", s == 200 and e.get("status") == "OK", (s, e))
    for t in ("device.ring", "device.lock", "device.wipe"):
        s, e = ro.call("POST", cmd, {"type": t})
        check("solo lectura: no puede enviar %s" % t, is_denied(s, e), (s, e))
    s, e = ro.call("POST", "/private/devices/%d/description" % dev_id, text="x")
    check("solo lectura: no puede editar la descripción", is_denied(s, e), (s, e))
    s, e = ro.call("POST", "/private/agent/v1/devices/%s/remote/start" % dev, {"viewOnly": True})
    check("solo lectura: no puede iniciar control remoto", is_denied(s, e), (s, e))
    s, e = st.call("POST", cmd, {})
    check("soporte: puede enviar comandos (pasa la comprobación de permiso)", not is_denied(s, e), (s, e))
    s, e = st.call("POST", "/private/agent/v1/devices/%s/sync" % dev)
    check("soporte: puede forzar la sincronización", s == 200 and e.get("status") == "OK", (s, e))
    s, e = st.call("POST", "/private/devices/%d/description" % dev_id, text=description)
    check("soporte: puede editar la descripción", s == 200 and e.get("status") == "OK", (s, e))
    for t in ("device.lock", "device.reboot", "device.passcodeReset", "device.wipe"):
        s, e = st.call("POST", cmd, {"type": t})
        check("soporte: no puede enviar %s" % t, is_denied(s, e), (s, e))
    s, e = st.call("PUT", "/private/fleet/v1/groups/%d" % folder["id"], {"name": "x"})
    check("soporte: no puede modificar carpetas", is_denied(s, e), (s, e))
    s, e = st.call("POST", "/private/agent/v1/codes", {"groupId": folder["id"]})
    check("soporte: no puede inscribir dispositivos", is_denied(s, e), (s, e))

    # --- cleanup -------------------------------------------------------------------------------------------------
    for uid in (sub_id, fleet_id, folder_id, ro_id, st_id):
        if uid:
            admin.call("DELETE", "/private/dc/users/%d" % uid)
    for rid in (role["id"], custom["id"], big["id"]):
        admin.call("DELETE", "/private/roles/%d" % rid)
    admin.call("DELETE", "/private/scripts/%d" % script["id"])
    for c in admin.ok("GET", "/private/configurations/search"):
        if c["name"].startswith("matriz-") and stamp in c["name"]:
            admin.call("DELETE", "/private/configurations/%d" % c["id"])
    if created_device:
        admin.call("DELETE", "/private/devices/%d" % created_device["id"])
        admin.call("DELETE", "/private/agent/v1/devices/deleted/" + created_device["number"])
    elif original_group:
        admin.call("POST", "/private/fleet/v1/devices/group", {"deviceIds": [dev_id], "groupId": original_group})
    admin.call("DELETE", "/private/fleet/v1/groups/%d" % empty_folder["id"])
    if created_device or original_group:
        admin.call("DELETE", "/private/fleet/v1/groups/%d" % folder["id"])

    failed = [n for n, ok in checks if not ok]
    print("\n%d permisos, %d llamadas de la matriz, %d comprobaciones, %d fallidas" % (len(only), total, len(checks), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
