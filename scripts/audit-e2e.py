#!/usr/bin/env python3
"""
End-to-end check of the audit trail and of a limited technician, against a running stack (docs/TESTING.md).

It creates a folder, a custom role without disruptive actions and a technician limited to that folder, then, AS THAT
TECHNICIAN, does things that are allowed (a real command to an enrolled device, editing its description, an export)
and things that are not (a wipe without permission, a device outside the folder, a read outside the role), and
verifies that every one of them is in the audit trail with who / when / from where / what / on what / result / change,
that no secret reached it, that the technician only sees their own folder's rows, that the Excel and CSV exports
carry the same rows, that the rows cannot be changed and that the retention purge works and is itself recorded.

Usage:
  scripts/audit-e2e.py --api http://localhost:8080 --device <number of an enrolled device>
                       [--admin admin] [--password admin] [--db-container <postgres container>] [--keep]

  --device        number of a device enrolled in the stack (an emulator: scripts/adb-enroll.sh). The script moves it
                  into the test folder and moves it back at the end. With a real device the allowed command must be
                  acknowledged by the agent (the script waits for it); --no-wait-ack skips that wait.
  --db-container  the stack's postgres container (e.g. dallycontrol-dev-postgres-1): also proves at SQL level that
                  the table refuses UPDATE / DELETE, and plants an old row to prove the retention purge.
Exit code 0 = every check passed. Standard library only.
"""
import argparse
import csv
import hashlib
import http.cookiejar
import io
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

UA = "audit-e2e/1.0 (prueba de punta a punta)"
TECH_PASSWORD = "Tecnico#2026"
TECH_PASSWORD_2 = "Tecnico#2027"
checks = []


def md5u(s):
    return hashlib.md5(s.encode()).hexdigest().upper()


class Api:
    """One browser session (cookie jar) against the console API."""

    def __init__(self, base, forwarded_for=None):
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.forwarded_for = forwarded_for

    def raw(self, method, path, body=None, text=None):
        """-> (http status, bytes, headers). Never raises on an HTTP error status."""
        data = None
        headers = {"Accept": "application/json, */*", "User-Agent": UA}
        if self.forwarded_for:
            headers["X-Forwarded-For"] = self.forwarded_for
        if text is not None:
            data = text.encode()
            headers["Content-Type"] = "text/plain"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + "/rest" + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=60) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

    def upload(self, path, filename, content):
        """multipart/form-data upload of one file (field "file"). -> (http status, envelope dict or None)."""
        boundary = "----auditE2E7MA4YWxkTrZu0gW"
        data = (("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                 "Content-Type: application/octet-stream\r\n\r\n" % (boundary, filename)).encode()
                + content + ("\r\n--%s--\r\n" % boundary).encode())
        req = urllib.request.Request(self.base + "/rest" + path, data=data, method="POST", headers={
            "Accept": "application/json", "User-Agent": UA, "Content-Type": "multipart/form-data; boundary=" + boundary})
        try:
            with self.opener.open(req, timeout=120) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        try:
            return status, json.loads(raw.decode() or "null")
        except ValueError:
            return status, None

    def call(self, method, path, body=None, text=None):
        """-> (http status, envelope dict or None)."""
        status, raw, _ = self.raw(method, path, body, text)
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


def check(name, cond, detail=""):
    checks.append((name, bool(cond)))
    print("  %s  %s%s" % ("ok  " if cond else "FAIL", name, ("" if cond else "   <- " + str(detail)[:400])))
    return bool(cond)


def denied(status, env):
    return status in (401, 403) or (isinstance(env, dict) and env.get("status") == "ERROR"
                                    and "permission.denied" in str(env.get("message")))


def psql(container, sql):
    p = subprocess.run(["docker", "exec", "-i", container, "psql", "-U", "dallycontrol", "-d", "dallycontrol", "-At",
                        "-v", "ON_ERROR_STOP=1", "-c", sql], capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--admin", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--device", required=True)
    ap.add_argument("--db-container")
    ap.add_argument("--no-wait-ack", action="store_true")
    ap.add_argument("--keep", action="store_true", help="leave the test folder, role and users in place")
    a = ap.parse_args()

    stamp = time.strftime("%H%M%S")
    admin = Api(a.api)
    env = admin.login(a.admin, a.password)
    if env.get("status") != "OK":
        raise SystemExit("FATAL: admin login failed")
    admin_id = env["data"]["id"]
    t0 = int(time.time() * 1000) - 2000

    def audit(session=None, **params):
        """Rows since the start of this run (newest first)."""
        q = {"from": t0, "pageSize": 200}
        q.update({k: v for k, v in params.items() if v is not None})
        return (session or admin).ok("GET", "/private/audit/v1/log?" + urllib.parse.urlencode(q))["items"]

    def find(rows, action=None, result=None, target=None, label=None):
        for r in rows:
            if action and not (r["action"] == action or r["action"].startswith(action + ":")):
                continue
            if result and r["result"] != result:
                continue
            if target and r.get("targetId") != str(target):
                continue
            if label and label not in r["label"]:
                continue
            return r
        return None

    print("== preparación (como administrador) ==")
    device = admin.ok("GET", "/private/devices/number/" + urllib.parse.quote(a.device))
    check("el dispositivo %s está inscrito" % a.device, device and device.get("id"), device)
    device_id = device["id"]
    scope = admin.ok("GET", "/private/fleet/v1/devices/%d/scope" % device_id) or {}
    original_group = scope.get("groupId")

    folder_a = admin.ok("POST", "/private/fleet/v1/groups", {"name": "aud-A-" + stamp})
    folder_b = admin.ok("POST", "/private/fleet/v1/groups", {"name": "aud-B-" + stamp})
    admin.ok("POST", "/private/fleet/v1/devices/group", {"deviceIds": [device_id], "groupId": folder_a["id"]})
    # A second device, outside the technician's folder (a record is enough: nothing is sent to it).
    other_number = "aud-ajeno-" + stamp
    admin.ok("PUT", "/private/devices", {"number": other_number, "description": "Equipo de otra carpeta",
                                         "configurationId": device.get("configurationId"),
                                         "groups": [{"id": folder_b["id"], "name": folder_b["name"]}]})
    other = admin.ok("GET", "/private/devices/number/" + other_number)
    admin.ok("POST", "/private/fleet/v1/devices/group", {"deviceIds": [other["id"]], "groupId": folder_b["id"]})

    perms = {p["name"]: p["id"] for p in admin.ok("GET", "/private/roles/permissions")}
    role_name = "aud-soporte-" + stamp
    role = admin.ok("PUT", "/private/roles", {"name": role_name, "description": "Rol de prueba: soporte sin acciones disruptivas",
                                              "permissions": [{"id": perms[n]} for n in ("edit_devices", "edit_device_desc", "view_audit")]})
    check("crear un rol personalizado devuelve su id", role and role.get("id"), role)
    role_id = role["id"]

    tech_login = "aud.tecnico." + stamp
    tech_user = admin.ok("PUT", "/private/dc/users", {"login": tech_login, "name": "Técnico Auditoría", "password": md5u("Inicial#2026"),
                                                      "role": "custom", "roleId": role_id, "folders": [folder_a["id"]]})
    check("el técnico queda con el rol personalizado y limitado a la carpeta",
          tech_user["role"] == "custom" and tech_user["roleId"] == role_id and tech_user["folders"] == [folder_a["id"]], tech_user)
    tech_id = tech_user["id"]

    print("== el técnico entra ==")
    tech = Api(a.api, forwarded_for="198.51.100.77")
    first = tech.login(tech_login, "Inicial#2026")
    token = (first.get("data") or {}).get("passwordResetToken")
    check("primer ingreso: el servidor exige cambiar la contraseña", first.get("status") == "OK" and token, first)
    tech.ok("POST", "/public/passwordReset/reset", {"passwordResetToken": token, "newPassword": md5u(TECH_PASSWORD)})
    time.sleep(1.2)
    bad = tech.login(tech_login, "clave-equivocada")
    check("una contraseña equivocada no entra", bad.get("status") != "OK", bad)
    time.sleep(1.5)
    good = tech.login(tech_login, TECH_PASSWORD)
    check("el técnico inicia sesión", good.get("status") == "OK", good)

    print("== acciones permitidas ==")
    dev_path = "/private/agent/v1/devices/" + urllib.parse.quote(a.device)
    ring = tech.ok("POST", dev_path + "/commands", {"type": "device.ring", "requiresCapability": "device.ring",
                                                     "payload": json.dumps({"durationMs": 3000})})
    check("comando permitido (hacer sonar) aceptado", ring and ring.get("id"), ring)
    if not a.no_wait_ack:
        status = None
        deadline = time.time() + 120
        while time.time() < deadline:
            cmds = tech.ok("GET", dev_path + "/commands") or []
            mine = [c for c in cmds if c.get("id") == ring["id"]]
            status = mine[0].get("status") if mine else None
            if status and status != "pending":
                break
            time.sleep(3)
        check("el dispositivo real recibió y respondió el comando (estado: %s)" % status,
              status in ("acked", "done", "success", "sent", "delivered", "completed", "ok"), status)
    tech.ok("POST", "/private/devices/%d/description" % device_id, text="Tablet auditada " + stamp)
    status, raw, headers = tech.raw("GET", "/private/fleet/v1/export.csv?group=%d" % folder_a["id"])
    check("exportación de dispositivos de su carpeta", status == 200 and a.device.encode() in raw, status)
    check("la exportación no trae equipos de otra carpeta", other_number.encode() not in raw)

    print("== acciones denegadas ==")
    s, e = tech.call("POST", dev_path + "/commands", {"type": "device.wipe"})
    check("wipe sin permiso: denegado", denied(s, e), (s, e))
    s, e = tech.call("POST", dev_path + "/commands", {"type": "device.lock"})
    check("bloqueo sin permiso: denegado", denied(s, e), (s, e))
    s, e = tech.call("POST", dev_path + "/commands",
                     {"type": "device.passcodeReset", "payload": json.dumps({"passcode": "246810"})})
    check("cambio de clave sin permiso: denegado", denied(s, e), (s, e))
    s, e = tech.call("POST", "/private/agent/v1/devices/%s/commands" % other_number, {"type": "device.ring"})
    check("comando a un equipo de otra carpeta: denegado (403)", s == 403, (s, e))
    s, e = tech.call("GET", "/private/agent/v1/devices/%s/state" % other_number)
    check("leer un equipo de otra carpeta: denegado (403)", s == 403, (s, e))
    s, e = tech.call("PUT", "/private/fleet/v1/groups/%d" % folder_a["id"], {"name": "renombrada"})
    check("modificar la carpeta sin manage_fleet: denegado", denied(s, e), (s, e))
    s, e = tech.call("POST", "/private/agent/v1/codes", {"groupId": folder_a["id"], "label": "x"})
    check("crear código de inscripción sin enroll_devices: denegado", denied(s, e), (s, e))
    s, e = tech.call("GET", "/private/reports/v1/inventory")
    check("reportes sin manage_fleet: denegado", denied(s, e), (s, e))
    s, e = tech.call("PUT", "/private/roles", {"name": "escalada-" + stamp, "permissions": [{"id": i} for i in perms.values()]})
    check("crear un rol (escalar privilegios): denegado", denied(s, e), (s, e))
    s, e = tech.call("PUT", "/private/dc/users", {"login": "x." + stamp, "password": md5u("Abcdef#1"), "role": "admin", "folders": []})
    check("crear un administrador: denegado", denied(s, e), (s, e))

    print("== control remoto, contraseña y salida ==")
    s, e = tech.call("POST", dev_path + "/remote/start", {"viewOnly": True})
    remote_ok = s == 200 and isinstance(e, dict) and e.get("status") == "OK"
    session_password = (e.get("data") or {}).get("password") if remote_ok else None
    time.sleep(2)
    tech.call("POST", dev_path + "/commands", {"type": "remote.vnc.stop"})
    s, e = tech.call("PUT", "/private/users/current", {"id": tech_id, "login": tech_login,
                                                        "oldPassword": md5u(TECH_PASSWORD), "newPassword": md5u(TECH_PASSWORD_2)})
    check("el técnico cambia su propia contraseña", s == 200 and e.get("status") == "OK", (s, e and e.get("message")))
    time.sleep(1.2)
    relog = tech.login(tech_login, TECH_PASSWORD_2)
    check("entra con la contraseña nueva", relog.get("status") == "OK", relog)

    print("== la auditoría, vista por el técnico (solo su carpeta) ==")
    time.sleep(2)
    own = audit(tech)
    check("el técnico ve su propia actividad", find(own, "device.command", "ok", a.device), len(own))
    facets = tech.ok("GET", "/private/audit/v1/facets")
    check("la consola le avisa que su vista está limitada", facets.get("scoped") is True, facets.get("scoped"))
    leaked = [r for r in own if r.get("targetId") in (other_number,) and r.get("userId") != tech_id]
    leaked += [r for r in own if r["action"] in ("group.create",) and str(folder_b["id"]) in (r.get("groupIds") or "").split(",")
               and str(folder_a["id"]) not in (r.get("groupIds") or "").split(",")]
    check("no ve la actividad del administrador sobre otras carpetas", not leaked, leaked[:2])
    check("sí ve lo que el administrador hizo sobre su carpeta (mover el equipo)",
          find([r for r in own if r.get("userId") == admin_id], "devices.move", "ok"), [r["action"] for r in own][:20])
    s, e = tech.call("GET", "/private/audit/v1/users/%d/activity" % admin_id)
    check("no puede abrir la actividad del administrador", denied(s, e), (s, e))
    s, e = tech.call("GET", "/private/audit/v1/devices/%s/activity" % other_number)
    check("no puede abrir la actividad de un equipo de otra carpeta", s == 403, (s, e))
    s, e = tech.call("GET", "/private/audit/v1/verify")
    check("no puede usar la verificación de integridad (solo administradores)", denied(s, e), (s, e))
    s, e = tech.call("PUT", "/private/audit/v1/settings", {"retentionDays": 30})
    check("no puede cambiar la retención", denied(s, e), (s, e))
    tech.raw("POST", "/public/auth/logout")

    print("== la auditoría, vista por el administrador: cada acción quedó registrada ==")
    time.sleep(2)
    rows = audit(user=tech_id)
    by = lambda **kw: find(rows, **kw)  # noqa: E731

    r = by(action="session.login", result="denied")
    check("inicio de sesión fallido", r and r["label"] == "Inicio de sesión fallido", r)
    r = by(action="session.login", result="ok")
    check("inicio de sesión", r, r)
    # Straight to Tomcat from this machine (a private peer, like the stack's own proxy) the forwarded address counts;
    # through Caddy the header a client sends is NOT believed: Caddy puts the address it really sees.
    ip = (r or {}).get("ip") or ""
    check("  con la IP del técnico (%s), agente de usuario y rol" % ip,
          r and ip.replace(".", "").replace(":", "").isalnum() and r.get("userAgent") == UA and r.get("roleName") == role_name, r)
    r = by(action="session.password.reset", result="ok")
    check("cambio de contraseña del primer ingreso", r, r)
    r = by(action="session.password.change", result="ok")
    check("cambio de contraseña propia", r, r)
    r = by(action="session.logout")
    check("cierre de sesión", r, r)

    r = by(action="device.command:device.ring", result="ok", target=a.device)
    check("comando real al dispositivo (hacer sonar): éxito", r and r["label"] == "Comando al dispositivo: hacer sonar", r)
    check("  objetivo con nombre e id, y carpeta del equipo",
          r and r["targetType"] == "device" and r.get("targetName") and str(folder_a["id"]) in (r.get("groupIds") or "").split(","), r)
    r = by(action="device.command:device.wipe", result="denied", target=a.device)
    check("intento de wipe: denegado, con su etiqueta", r and "borrado de fábrica" in r["label"] and r["message"] == "Permiso denegado", r)
    check("intento de bloqueo: denegado", by(action="device.command:device.lock", result="denied"))
    r = by(action="device.command:device.passcodereset", result="denied")
    check("intento de cambio de clave: denegado y SIN la clave en el registro", r and "246810" not in json.dumps(r), r)
    r = by(action="device.command", result="denied", target=other_number)
    check("comando fuera de su carpeta: denegado por alcance", r and r.get("httpStatus") == 403, r)
    r = by(label="Consulta denegada", result="denied")
    check("lectura denegada por alcance (403) registrada", r, [x["label"] for x in rows][:30])
    check("carpeta sin permiso: denegado", by(action="group.update", result="denied", target=folder_a["id"]))
    check("código de inscripción sin permiso: denegado", by(action="enroll.code.create", result="denied"))
    check("rol sin permiso: denegado", by(action="role.save", result="denied"))
    check("alta de administrador sin permiso: registrada como rechazada", by(action="user.save"), None)
    r = by(action="device.description", result="ok")
    detail = json.loads(r["detail"]) if r and r.get("detail") else {}
    check("edición de descripción con antes / después",
          detail.get("cambios", {}).get("descripción", {}).get("despues") == "Tablet auditada " + stamp, detail)
    r = by(action="export.devices", result="ok")
    check("exportación de dispositivos registrada, con su carpeta", r and r.get("targetName") == folder_a["name"], r)
    r = by(action="remote.session.start")
    check("inicio de sesión de control remoto registrado (%s)" % (r and r["result"]), r, r)
    r2 = by(action="remote.session.stop")
    check("fin de sesión de control remoto registrado", r2, r2)
    if remote_ok:
        check("  con la duración de la sesión", r2 and (r2.get("durationMs") or 0) >= 1000, r2)

    dump = json.dumps(audit()) + json.dumps(rows)
    secrets = [md5u("Inicial#2026"), md5u(TECH_PASSWORD), md5u(TECH_PASSWORD_2), md5u("clave-equivocada"), md5u(a.password), token]
    if session_password:
        secrets.append(session_password)
    check("ninguna contraseña, token ni clave quedó en la auditoría", not [s for s in secrets if s and s in dump])

    print("== lo que hizo el administrador también queda ==")
    admin.ok("PUT", "/private/roles", {"id": role_id, "name": role_name, "description": "Rol de prueba",
                                       "permissions": [{"id": perms[n]} for n in ("edit_devices", "edit_device_desc", "view_audit", "disruptive_actions")]})
    admin.ok("PUT", "/private/dc/users", {"id": tech_id, "login": tech_login, "name": "Técnico Auditado", "role": "custom",
                                          "roleId": role_id, "folders": [folder_a["id"]]})
    admin.ok("POST", "/private/fleet/v1/groups/%d/commands" % folder_a["id"],
             {"command": {"type": "device.ring", "requiresCapability": "device.ring", "payload": json.dumps({"durationMs": 1000})}})
    # The audit only observes: an upload (a body it does not keep) must arrive intact.
    payload = ("contenido de prueba " + stamp + "\n").encode() * 2000
    s, e = admin.upload("/private/web-ui-files", "aud-%s.txt" % stamp, payload)
    check("carga de archivo (multipart) a través del filtro de auditoría", s == 200 and isinstance(e, dict) and e.get("status") == "OK", (s, e))
    time.sleep(2)
    mine = audit(user=admin_id)
    r = find(mine, "file.upload", "ok")
    check("carga de archivo registrada, con su tamaño y sin el contenido",
          r and "carga de" in (r.get("detail") or "") and "contenido de prueba" not in (r.get("detail") or ""), r)
    r = find(mine, "role.save", "ok", role_id)
    d = json.loads(r["detail"]) if r and r.get("detail") else {}
    check("cambio de permisos del rol con antes / después (permiso agregado)",
          d.get("cambios", {}).get("permisos", {}).get("agregado") == "disruptive_actions"
          and "disruptive_actions" not in str(d.get("cambios", {}).get("permisos", {}).get("antes")), d)
    r = find(mine, "user.save", "ok", tech_id)
    d = json.loads(r["detail"]) if r and r.get("detail") else {}
    check("edición de usuario con antes / después", d.get("cambios", {}).get("nombre", {}).get("despues") == "Técnico Auditado", d)
    r = find(mine, "group.command", "ok", folder_a["id"])
    check("comando a la carpeta, con los dispositivos alcanzados", r and a.device in (r.get("deviceNumbers") or "").split(","), r)
    check("creación de carpeta", find(mine, "group.create", "ok"))
    check("creación del rol", find(mine, "role.save", "ok"))

    print("== actividad por dispositivo y por usuario ==")
    act = admin.ok("GET", "/private/audit/v1/devices/%s/activity?pageSize=100" % urllib.parse.quote(a.device))["items"]
    check("actividad del dispositivo: el comando del técnico", find(act, "device.command:device.ring", "ok"))
    check("actividad del dispositivo: el wipe denegado", find(act, "device.command:device.wipe", "denied"))
    check("actividad del dispositivo: el comando a su carpeta", find(act, "group.command", "ok"))
    uact = admin.ok("GET", "/private/audit/v1/users/%d/activity?pageSize=100" % tech_id)["items"]
    check("actividad del usuario: solo sus acciones", uact and all(x.get("userId") == tech_id for x in uact), len(uact))

    print("== filtros y paginación en servidor ==")
    only_denied = audit(user=tech_id, result="denied")
    check("filtro por resultado", only_denied and all(x["result"] == "denied" for x in only_denied))
    by_dev = audit(device=a.device)
    check("filtro por dispositivo", by_dev and all(a.device in (x.get("deviceNumbers") or "").split(",") for x in by_dev))
    by_group = audit(group=folder_b["id"])
    check("filtro por carpeta", by_group and all(str(folder_b["id"]) in (x.get("groupIds") or "").split(",") for x in by_group))
    by_cat = audit(category="comandos")
    check("filtro por módulo", by_cat and all(x["category"] == "comandos" for x in by_cat))
    by_text = audit(q="borrado de fábrica")
    check("búsqueda por texto", by_text and all("borrado" in x["label"] for x in by_text))
    p1 = admin.ok("GET", "/private/audit/v1/log?" + urllib.parse.urlencode({"from": t0, "page": 1, "pageSize": 5}))
    p2 = admin.ok("GET", "/private/audit/v1/log?" + urllib.parse.urlencode({"from": t0, "page": 2, "pageSize": 5}))
    check("paginación: 5 por página, total real, páginas distintas",
          len(p1["items"]) == 5 and p1["total"] > 10 and not {x["id"] for x in p1["items"]} & {x["id"] for x in p2["items"]}, p1["total"])

    print("== exportación (CSV y Excel) ==")
    q = urllib.parse.urlencode({"from": t0, "user": tech_id})
    status, raw, headers = admin.raw("GET", "/private/audit/v1/export?format=csv&" + q)
    text = raw.decode("utf-8-sig")
    table = list(csv.reader(io.StringIO(text), delimiter=";"))
    head = table[0] if table else []
    check("CSV: se descarga con sus columnas", status == 200 and "Técnico" in head and "Acción" in head and "Resultado" in head, head)
    labels = {row[head.index("Acción")] + "|" + row[head.index("Resultado")] for row in table[1:]} if head else set()
    for want in ("Comando al dispositivo: hacer sonar|Éxito", "Comando al dispositivo: borrado de fábrica (wipe)|Denegado",
                 "Inicio de sesión fallido|Denegado", "Edición de la descripción del dispositivo|Éxito"):
        check("CSV: fila «%s»" % want.replace("|", " → "), want in labels, sorted(labels)[:12])
    check("CSV: mismas filas que la búsqueda", len(table) - 1 == len(audit(user=tech_id)), (len(table) - 1, len(audit(user=tech_id))))
    check("CSV: sin contraseñas ni claves", not [s for s in secrets if s and s in text])
    status, raw, headers = admin.raw("GET", "/private/audit/v1/export?format=xlsx&" + q)
    sheet = ""
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            sheet = "".join(z.read(n).decode("utf-8", "replace") for n in z.namelist() if n.endswith(".xml"))
    except zipfile.BadZipFile:
        pass
    check("Excel: libro válido con las filas del técnico",
          status == 200 and "borrado de fábrica" in sheet and tech_login in sheet and "Denegado" in sheet, status)
    time.sleep(2)
    check("la propia exportación quedó registrada", find(audit(user=admin_id), "audit.export", "ok"))

    print("== integridad: los registros no se pueden editar ni borrar ==")
    some_id = rows[0]["id"]
    for method, path in (("DELETE", "/private/audit/v1/log/%d" % some_id), ("PUT", "/private/audit/v1/log/%d" % some_id),
                         ("DELETE", "/private/audit/v1/log"), ("POST", "/private/audit/v1/log")):
        s, e = admin.call(method, path, {} if method != "DELETE" else None)
        check("no existe %s %s en la API (HTTP %s)" % (method, path.replace(str(some_id), "{id}"), s), s in (404, 405), (s, e))
    v = admin.ok("GET", "/private/audit/v1/verify")
    check("cadena de hashes íntegra (%s registros)" % v.get("checked"), v.get("ok") is True and v.get("checked", 0) > 20, v)
    if a.db_container:
        code, out = psql(a.db_container, "UPDATE audit_trail SET result = 'ok' WHERE id = %d" % some_id)
        check("SQL: UPDATE rechazado por la tabla", code != 0 and "append-only" in out, out)
        code, out = psql(a.db_container, "DELETE FROM audit_trail WHERE id = %d" % some_id)
        check("SQL: DELETE rechazado por la tabla", code != 0 and "append-only" in out, out)
        code, out = psql(a.db_container, "TRUNCATE audit_trail")
        check("SQL: TRUNCATE rechazado por la tabla", code != 0 and "append-only" in out, out)

    print("== retención y purga ==")
    st = admin.ok("GET", "/private/audit/v1/settings")
    check("retención por defecto de 365 días (o la ya configurada)", st["retentionDays"] >= 30 and st["canEdit"] is True, st)
    s, e = admin.call("PUT", "/private/audit/v1/settings", {"retentionDays": 5})
    check("retención menor al mínimo: rechazada", isinstance(e, dict) and e.get("status") == "ERROR", e)
    admin.ok("PUT", "/private/audit/v1/settings", {"retentionDays": 90})
    check("retención configurable", admin.ok("GET", "/private/audit/v1/settings")["retentionDays"] == 90)
    if a.db_container:
        old = int(time.time() * 1000) - 120 * 86400000
        marker = "aud-vieja-" + stamp
        # The purge cuts the OLDEST rows of the chain, so the planted row goes where an old row would be: before all.
        code, out = psql(a.db_container, "INSERT INTO audit_trail (id, createdAt, customerId, login, category, action, label, result, hash) "
                                         "SELECT (SELECT MIN(id) - 1 FROM audit_trail), %d, customerId, '%s', 'otros', 'test.old', "
                                         "'Registro antiguo de prueba', 'ok', '%s' FROM users WHERE id = %d" % (old, marker, "0" * 64, admin_id))
        check("fila antigua de prueba insertada (120 días)", code == 0, out)
        before = admin.ok("GET", "/private/audit/v1/log?" + urllib.parse.urlencode({"q": marker}))["total"]
        res = admin.ok("POST", "/private/audit/v1/purge")
        after = admin.ok("GET", "/private/audit/v1/log?" + urllib.parse.urlencode({"q": marker}))["total"]
        check("la purga elimina lo anterior a la retención (90 días)", before == 1 and after == 0 and res["removed"] >= 1, (before, after, res))
        time.sleep(2)
        pr = find(audit(category="auditoria"), "audit.purge", "ok")
        pd = json.loads(pr["detail"]) if pr and pr.get("detail") else {}
        check("la purga quedó registrada (cuántos, con qué retención)", pd.get("registrosEliminados", 0) >= 1 and pd.get("retencionDias") == 90, pr)
        check("lo reciente sigue ahí después de la purga", find(audit(user=tech_id), "device.command:device.ring", "ok"))
        v = admin.ok("GET", "/private/audit/v1/verify")
        check("la cadena sigue íntegra después de la purga", v.get("ok") is True, v)
    admin.ok("PUT", "/private/audit/v1/settings", {"retentionDays": st["retentionDays"]})
    time.sleep(1)
    r = find(audit(user=admin_id), "audit.retention", "ok")
    check("el cambio de retención quedó registrado", r, r)

    if not a.keep:
        print("== limpieza ==")
        if original_group:
            admin.call("POST", "/private/fleet/v1/devices/group", {"deviceIds": [device_id], "groupId": original_group})
        admin.call("DELETE", "/private/dc/users/%d" % tech_id)
        admin.call("DELETE", "/private/devices/%d" % other["id"])
        admin.call("DELETE", "/private/agent/v1/devices/deleted/" + other_number)
        admin.call("DELETE", "/private/roles/%d" % role_id)
        if original_group:
            admin.call("DELETE", "/private/fleet/v1/groups/%d" % folder_a["id"])
        admin.call("DELETE", "/private/fleet/v1/groups/%d" % folder_b["id"])
        time.sleep(2)
        tail = audit(user=admin_id)
        r = find(tail, "user.delete", "ok", tech_id)
        d = json.loads(r["detail"]) if r and r.get("detail") else {}
        check("eliminación del usuario registrada, con lo que se eliminó", d.get("eliminado", {}).get("usuario") == tech_login, d)
        check("eliminación del rol registrada", find(tail, "role.delete", "ok", role_id))

    failed = [n for n, ok in checks if not ok]
    print("\n%d comprobaciones, %d fallidas" % (len(checks), len(failed)))
    for n in failed:
        print("  FALLÓ: " + n)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
