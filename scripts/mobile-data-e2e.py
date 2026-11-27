#!/usr/bin/env python3
"""
End-to-end test of "mobile data per app" (lists, limits, alerts, per-device override) against a running control plane.

Two modes:

1. Simulated device (default) — this script plays the device over the Agent v1 API: it enrolls a throw-away device on
   a throw-away policy and checks what the SERVER does: the policy's section reaches the device document (megabytes ->
   bytes), a device override changes the document and its revision, the device's reported state and consumption show
   in the console API, limit events become alerts (and, with --mailpit, e-mails), and the "uso vs. límite" report and
   its export carry the device.

       scripts/mobile-data-e2e.py --base http://localhost:8080 [--admin-password admin] [--mailpit http://localhost:8025]

2. Real agent on an emulator (--serial + --device) — the device is already enrolled as Device Owner
   (scripts/adb-enroll.sh) and the test apps are built (scripts/build-netprobe-testapps.sh). The script moves the
   device to a throw-away policy and drives the real thing: an app blocked by the policy fails on mobile data and
   works on Wi-Fi, an allowed app and the agent keep working on mobile data, Wi-Fi <-> mobile switches, the same with
   the local VPN forced, the allowlist mode, a limit spent -> alert and automatic block, the device override, and
   everything undone when the policy is removed. It checks `dumpsys device_policy`, `dumpsys netpolicy` and
   `dumpsys connectivity` on the way.

       scripts/mobile-data-e2e.py --base http://localhost:8088 --serial emulator-5554 --device <device number>

   `adb shell svc wifi disable` leaves the emulator on its emulated cellular network (metered), which is what
   "mobile data" means here. The script serves the test payloads itself on --payload-port (the emulator reaches the
   host at 10.0.2.2).

Needs a database seeded like a real install (scripts/dev-seed.sh on the dev stack). It creates an e2e-datos-* policy
and removes it (and restores the device's policy) on exit. Exit code 0 = every check passed.
"""
import argparse
import hashlib
import http.cookiejar
import http.server
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

PASS = 0
FAIL = 0
MB = 1024 * 1024
HERE = os.path.dirname(os.path.abspath(__file__))
BLOCKED = "com.dallycontrol.netprobe.blocked"
ALLOWED = "com.dallycontrol.netprobe.allowed"
ESSENTIAL = "com.dallycontrol.netprobe.essential"
ACTIVITY = "com.dallycontrol.netprobe.ProbeActivity"


def chk(name, got, want):
    global PASS, FAIL
    if got == want:
        PASS += 1
        print(f"  PASS: {name}", flush=True)
    else:
        FAIL += 1
        print(f"  FAIL: {name} (got {got!r} want {want!r})", flush=True)


def ok(name, cond, detail=""):
    chk(name + (f" [{detail}]" if detail and not cond else ""), bool(cond), True)


def section(title):
    print(f"== {title} ==", flush=True)


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def raw(self, method, path, body=None, headers=None, cookies=True):
        req = urllib.request.Request(self.base + path, method=method)
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        opener = self.opener if cookies else urllib.request.build_opener()
        for attempt in range(4):  # a loaded dev stack's proxy answers 502/503 now and then: try again
            try:
                with opener.open(req, data, timeout=60) as r:
                    return r.status, r.read(), dict(r.headers)
            except urllib.error.HTTPError as e:
                if e.code not in (502, 503, 504) or attempt == 3:
                    return e.code, e.read(), dict(e.headers)
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if attempt == 3:
                    raise
            time.sleep(2)

    def call(self, method, path, body=None, headers=None, cookies=True):
        status, raw, _ = self.raw(method, path, body, headers, cookies)
        try:
            return json.loads(raw.decode() or "{}")
        except ValueError:
            return {"status": f"HTTP {status}", "data": None}

    def get(self, path):
        return self.call("GET", "/rest/private" + path)

    def data(self, path):
        return self.get(path).get("data")

    def post(self, path, body=None):
        return self.call("POST", "/rest/private" + path, body)

    def put(self, path, body=None):
        return self.call("PUT", "/rest/private" + path, body)

    def delete(self, path):
        return self.call("DELETE", "/rest/private" + path)

    def login(self, user, password):
        md5 = hashlib.md5(password.encode()).hexdigest().upper()
        return self.call("POST", "/rest/public/auth/login", {"login": user, "password": md5}).get("status")


def wait_for(seconds, fn, every=3):
    end = time.time() + seconds
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(every)
    return fn()


# ------------------------------------------------------------------------------------------------ policy helpers

class Policy:
    """A throw-away policy (a copy of an existing one, with no apps) whose mobile-data section the test rewrites."""

    def __init__(self, api, source_id=None):
        self.api = api
        configs = api.data("/configurations/search") or []
        src = next((c for c in configs if c["id"] == source_id), None) or configs[0]
        self.body = {k: v for k, v in (api.data(f"/configurations/{src['id']}") or src).items() if k not in ("id", "qrCodeKey", "selected")}
        self.body["name"] = f"e2e-datos-{int(time.time())}-{os.getpid()}"
        self.body["description"] = "mobile-data-e2e (temporal)"
        self.body["applications"] = []
        self.body["kioskMode"] = False
        self.body["mainAppId"] = None
        self.body["dcPolicy"] = None
        self.id = None
        self.moved = []  # (device id, previous configuration id)

    def save(self, mobile_data):
        """Store the section (None = remove it). @return the normalised dcPolicy the server kept."""
        self.body["dcPolicy"] = None if mobile_data is None else json.dumps({"mobileData": mobile_data})
        r = self.api.put("/configurations", dict(self.body, **({"id": self.id} if self.id else {})))
        if r.get("status") != "OK":
            raise SystemExit(f"could not save the test policy: {r}")
        self.id = r["data"]["id"]
        return json.loads(r["data"].get("dcPolicy") or "{}").get("mobileData")

    def adopt(self, number):
        found = self.api.post("/devices/search", {"value": number, "pageSize": 5, "pageNum": 1})["data"]["devices"]["items"]
        dev = next(d for d in found if d["number"] == number)
        self.moved.append((dev["id"], dev.get("configurationId")))
        r = self.api.put("/devices", {"ids": [dev["id"]], "configurationId": self.id})
        return dev["id"], r.get("status")

    def cleanup(self, delete_devices=()):
        for dev_id, previous in self.moved:
            if dev_id in delete_devices or previous is None:
                continue
            self.api.put("/devices", {"ids": [dev_id], "configurationId": previous})
        if delete_devices:
            self.api.post("/devices/deleteBulk", {"ids": list(delete_devices)})
        if self.id:
            self.api.delete(f"/configurations/{self.id}")


def alerts(api, number, kind, since):
    rows = (api.data(f"/alerts/v1/list?from={since}&type={kind}") or {}).get("rows", [])
    return [r for r in rows if r.get("number") == number]


def mail_to(mailpit, address):
    url = mailpit.rstrip("/") + "/api/v1/search?query=" + urllib.parse.quote(f"to:{address}")
    with urllib.request.urlopen(url, timeout=20) as r:
        found = json.loads(r.read().decode()).get("messages", [])
    out = []
    for m in found:
        with urllib.request.urlopen(mailpit.rstrip("/") + "/api/v1/message/" + m["ID"], timeout=20) as r:
            out.append(json.loads(r.read().decode()))
    return out


def report_checks(api, number, expect_scope_rows):
    section("report «Datos móviles: uso vs. límite» and its export")
    rep = api.data("/reports/v1/data-limits") or {}
    rows = [r for r in rep.get("rows", []) if r.get("number") == number]
    chk("report title", rep.get("title"), "Datos móviles: uso vs. límite")
    chk("device rows in the report (device + apps with a limit)", sorted(r["ambito"] for r in rows), sorted(expect_scope_rows))
    labels = [c["label"] for c in rep.get("columns", [])]
    ok("columns: consumption, limit, % and state", all(x in labels for x in ("Consumo del periodo", "Límite", "% usado", "Estado")), str(labels))
    for fmt, magic in (("csv", None), ("xlsx", b"PK")):
        status, raw, headers = api.raw("GET", f"/rest/private/reports/v1/export?report=data-limits&format={fmt}")
        chk(f"export {fmt}: HTTP status", status, 200)
        if magic:
            ok(f"export {fmt}: is a spreadsheet", raw[:2] == magic)
        else:
            ok(f"export {fmt}: carries the device", number.encode() in raw and "Consumo del periodo".encode() in raw)
    return rows


# ----------------------------------------------------------------------------------------------- simulated device

def simulated(api, args):
    since = int(time.time() * 1000) - 1000
    section("policy section is validated and normalised")
    policy = Policy(api)
    kept = policy.save({
        "mode": "blocklist", "apps": ["com.video.app", "no es un paquete", "com.audio.app"], "mechanism": "auto",
        "period": "monthly", "cutDay": 15, "warnPercent": 70, "deviceLimitMb": 500,
        "appLimits": [{"pkg": "com.chat.app", "limitMb": 100}, {"pkg": "x", "limitMb": 5}], "essential": ["com.sales.app"],
    })
    chk("invalid package dropped, list sorted", kept.get("apps"), ["com.audio.app", "com.video.app"])
    chk("automatic mechanism is not stored", kept.get("mechanism"), None)
    chk("limits kept in megabytes", (kept.get("deviceLimitMb"), kept.get("appLimits")), (500, [{"pkg": "com.chat.app", "limitMb": 100}]))
    chk("cut day and threshold kept", (kept.get("cutDay"), kept.get("warnPercent")), (15, 70))

    section("enroll a throw-away device on that policy")
    tok = api.post("/agent/v1/token", {"configurationId": policy.id})["data"]["token"]
    enr = api.call("POST", "/rest/public/agent/v1/enroll", {
        "enrollToken": tok, "agent": {"version": "0.9.8", "package": "com.dallycontrol.agent"},
        "device": {"androidSdkInt": 34, "isDeviceOwner": True},
        "capabilities": {"policy": ["wifi"], "appManagement": [], "remoteControl": {"tier": "none"}, "oem": {"vendor": "e2e", "knox": False}},
    })
    chk("enroll OK", enr.get("status"), "OK")
    number, secret = enr["data"]["deviceId"], enr["data"]["deviceSecret"]
    found = api.post("/devices/search", {"value": number, "pageSize": 5, "pageNum": 1})["data"]["devices"]["items"]
    dev_id = next(d["id"] for d in found if d["number"] == number)
    policy.moved.append((dev_id, None))
    state = {"battery": 80, "charging": False, "locked": False, "kioskActive": False, "androidRelease": "14", "lastBootAt": 1000}

    acks = []       # results of the commands received, sent with the next check-in (like the agent does)
    applied = [None]  # the revision the "phone" has applied

    def checkin(dynamic=None, events=None, caps=("configApply",)):
        body = {"deviceId": number, "capabilities": {"policy": ["wifi"], "device": list(caps)},
                "state": dict(state, **({"appliedConfigRevision": applied[0]} if applied[0] else {}))}
        if dynamic is not None:
            body["telemetry"] = {"dynamic": dynamic}
        if events:
            body["events"] = events
        if acks and "configApply" in caps:
            body["results"] = list(acks)
            del acks[:]
        return api.call("POST", "/rest/public/agent/v1/checkin", body, {"Authorization": "Bearer " + secret}, cookies=False)

    def pushed_document():
        """The next config.apply the server delivers, applied and acknowledged like the agent would; None = none comes."""
        for _ in range(3):
            r = checkin()
            for c in (r.get("data") or {}).get("commands", []):
                if c.get("type") == "config.apply":
                    p = c.get("payload")
                    doc = json.loads(p) if isinstance(p, str) else p
                    acks.append({"commandId": c["commandId"], "status": "done", "completedAt": "2026-01-01T00:00:00Z",
                                 "detail": json.dumps({"revision": doc["revision"], "outcomes": {"mobileData": "applied"}})})
                    applied[0] = doc["revision"]
                    return doc
            time.sleep(1)
        return None

    try:
        section("the device document carries the section in bytes")
        doc = pushed_document()
        ok("config.apply delivered", doc is not None)
        md = (doc or {}).get("mobileData") or {}
        chk("mode and apps", (md.get("mode"), md.get("apps")), ("blocklist", ["com.audio.app", "com.video.app"]))
        chk("device limit in bytes", md.get("deviceLimitBytes"), 500 * MB)
        chk("app limit in bytes", md.get("appLimits"), [{"pkg": "com.chat.app", "bytes": 100 * MB}])
        chk("period: monthly is implicit, cut day and threshold sent", (md.get("period"), md.get("cutDay"), md.get("warnPercent")), (None, 15, 70))
        chk("essential apps sent (there is a device limit)", md.get("essential"), ["com.sales.app"])
        rev1 = doc["revision"]
        chk("applied and acknowledged: nothing more to deliver", pushed_document(), None)

        section("device override: wins over the policy and changes the revision")
        view = api.data(f"/agent/v1/devices/{number}/mobileData")
        chk("console view: policy and no override", ((view.get("policy") or {}).get("mode"), view.get("override")), ("blocklist", None))
        chk("console view: effective limits in bytes", (view.get("effective") or {}).get("deviceLimitBytes"), 500 * MB)
        r = api.post(f"/agent/v1/devices/{number}/mobileData", {"allow": ["com.video.app"], "block": ["com.game.app", "???"], "ignoreLimits": True})
        chk("override saved", r.get("status"), "OK")
        chk("override normalised", (r.get("data") or {}).get("override"), {"allow": ["com.video.app"], "block": ["com.game.app"], "ignoreLimits": True})
        doc2 = pushed_document()
        md2 = (doc2 or {}).get("mobileData") or {}
        chk("allowed here leaves the list, blocked here joins it", md2.get("apps"), ["com.audio.app", "com.game.app"])
        chk("limits ignored on this device", (md2.get("deviceLimitBytes"), md2.get("appLimits")), (None, None))
        ok("revision changed", doc2 and doc2["revision"] != rev1)
        api.post(f"/agent/v1/devices/{number}/mobileData", {"mode": "unrestricted"})
        doc3 = pushed_document()
        chk("unrestricted: the section disappears from the document", (doc3 or {}).get("mobileData"), None)
        r = api.post(f"/agent/v1/devices/{number}/mobileData", {"mode": "policy"})
        chk("back to the policy: override removed", (r.get("data") or {}).get("override"), None)
        doc4 = pushed_document()
        chk("revision is the policy's again", (doc4 or {}).get("revision"), rev1)
        checkin()  # delivers the acknowledgement and the applied revision
        chk("device shown in sync with its policy", (api.data(f"/agent/v1/devices/{number}/configStatus") or {}).get("inSync"), True)

        section("reported state, consumption and limit events")
        day = time.strftime("%Y-%m-%d")
        usage = {"usageAccess": True, "days": [{"day": day, "apps": [
            {"packageName": "com.chat.app", "label": "Chat", "mobileBytes": 60 * MB, "wifiBytes": 5 * MB},
            {"packageName": "com.video.app", "label": "Video", "mobileBytes": 20 * MB}]}]}
        chk("check-in with daily usage", checkin(dynamic={"appUsage": usage}).get("status"), "OK")
        view = api.data(f"/agent/v1/devices/{number}/mobileData")
        chk("consumption from the daily table while the agent reports no state", (view.get("source"), view.get("deviceBytes")), ("server", 80 * MB))
        rows = {r["pkg"] or "device": r for r in view.get("rows", [])}
        chk("device row: use against limit", (rows["device"]["usedbytes"], rows["device"]["limitbytes"], rows["device"]["level"]), (80 * MB, 500 * MB, "ok"))
        chk("app row: 60 of 100 MB is under the 70 % threshold", (rows["com.chat.app"]["usedbytes"], rows["com.chat.app"]["level"]), (60 * MB, "ok"))

        now = int(time.time() * 1000)
        cal = time.localtime()
        # The phone's own reading of the current period (it starts on the 15th): 460 MB device, 101 MB the chat app.
        start_month = cal.tm_mon if cal.tm_mday >= 15 else (cal.tm_mon - 1 or 12)
        start_year = cal.tm_year if (cal.tm_mday >= 15 or cal.tm_mon > 1) else cal.tm_year - 1
        period_start = int(time.mktime((start_year, start_month, 15, 0, 0, 0, 0, 0, -1)) * 1000)
        status = {"mode": "blocklist", "mechanism": "dpm", "blockedCount": 3, "period": "monthly", "periodStart": period_start,
                  "periodEnd": period_start + 30 * 86400000, "deviceBytes": 460 * MB, "deviceLimitBytes": 500 * MB, "warnPercent": 70,
                  "blocked": [{"pkg": "com.audio.app", "reason": "policy"}, {"pkg": "com.video.app", "reason": "policy"},
                              {"pkg": "com.chat.app", "reason": "appLimit"}],
                  "apps": [{"pkg": "com.chat.app", "label": "Chat", "bytes": 101 * MB, "limitBytes": 100 * MB}], "evaluatedAt": now}
        events = [{"type": "dataWarning", "ts": now - 2000, "detail": "Equipo: 460 MB de 500 MB (92 %)"},
                  {"type": "dataLimit", "ts": now - 1000, "detail": "Chat (com.chat.app): 101 MB de 100 MB (101 %)"}]
        if args.mailpit:
            rec = api.post("/geofences/v1/recipients", {"emails": args.mail_to, "types": ["dataWarning", "dataLimit"], "active": True})
            chk("alert recipient for the mobile-data alerts", rec.get("status"), "OK")
        chk("check-in with the agent's state and two limit events", checkin(dynamic={"mobileData": status}, events=events).get("status"), "OK")
        view = api.data(f"/agent/v1/devices/{number}/mobileData")
        chk("consumption now is the phone's reading of the period", (view.get("source"), view.get("deviceBytes")), ("agent", 460 * MB))
        rows = {r["pkg"] or "device": r for r in view.get("rows", [])}
        chk("device row at 92 %: near the limit", (rows["device"]["percent"], rows["device"]["level"], rows["device"]["estado"]), (92, "warn", "Cerca del límite"))
        chk("app row over its limit", (rows["com.chat.app"]["level"], rows["com.chat.app"]["estado"]), ("over", "Límite superado"))
        chk("state: mechanism and blocked apps", ((view.get("status") or {}).get("mechanism"), (view.get("status") or {}).get("blockedCount")), ("dpm", 3))

        section("alerts")
        warn = alerts(api, number, "dataWarning", since)
        limit = alerts(api, number, "dataLimit", since)
        chk("one «cerca del límite» alert", [(a["tipo"], a["detail"]) for a in warn], [("Datos móviles: cerca del límite", "Equipo: 460 MB de 500 MB (92 %)")])
        chk("one «límite superado» alert", [(a["tipo"], a["detail"]) for a in limit], [("Datos móviles: límite superado", "Chat (com.chat.app): 101 MB de 100 MB (101 %)")])
        checkin(dynamic={"mobileData": status}, events=events)
        chk("the same events again do not duplicate the alerts", (len(alerts(api, number, "dataWarning", since)), len(alerts(api, number, "dataLimit", since))), (1, 1))
        timeline = [e.get("type") for e in (api.data(f"/agent/v1/devices/{number}/events?limit=50") or [])]
        ok("events are in the device timeline", "dataWarning" in timeline and "dataLimit" in timeline, str(timeline[:8]))

        rows = report_checks(api, number, ["Equipo", "App"])
        by = {r["ambito"]: r for r in rows}
        chk("report: device 460 of 500 MB, 3 apps without mobile data", (by["Equipo"]["usedbytes"], by["Equipo"]["limitbytes"], by["Equipo"]["blocked"]), (460 * MB, 500 * MB, 3))
        chk("report: the app over its limit", (by["App"]["pkg"], by["App"]["estado"]), ("com.chat.app", "Límite superado"))

        if args.mailpit:
            section("e-mail (Mailpit)")
            chk("server reports SMTP configured", (api.data("/geofences/v1/mail") or {}).get("configured"), True)
            msgs = wait_for(240, lambda: mail_to(args.mailpit, args.mail_to), every=10)
            ok("an alert e-mail arrived", len(msgs) >= 1)
            html = " ".join(m.get("HTML", "") for m in msgs)
            ok("e-mail lists «cerca del límite» with its figures", "Datos móviles: cerca del límite" in html and "460 MB de 500 MB" in html)
            ok("e-mail lists «límite superado» with the app", "Datos móviles: límite superado" in html and "com.chat.app" in html)
            ok("e-mail links to the device", f"/devices/{urllib.parse.quote(number)}" in html)
            for r in api.data("/geofences/v1/recipients") or []:
                if r.get("emails") == args.mail_to:
                    api.delete(f"/geofences/v1/recipients/{r['id']}")

        section("an agent that does not know the section")
        old = api.call("POST", "/rest/public/agent/v1/checkin", {
            "deviceId": number, "capabilities": {"policy": ["wifi"], "device": ["lock"]}, "state": dict(state, appliedConfigRevision="stale")},
            {"Authorization": "Bearer " + secret}, cookies=False)
        chk("an agent without config.apply keeps checking in and gets no document",
            (old.get("status"), [c["type"] for c in (old.get("data") or {}).get("commands", [])].count("config.apply")), ("OK", 0))
        applied[0] = rev1
        checkin(dynamic={"mobileData": status})

        section("removing the section from the policy")
        chk("policy saved without the section", policy.save(None), None)
        doc5 = pushed_document()
        chk("the document no longer carries it", (doc5 or {}).get("mobileData", "absent"), "absent")
        ok("revision changed again", doc5 and doc5["revision"] != rev1)
    finally:
        policy.cleanup(delete_devices=[dev_id])


# --------------------------------------------------------------------------------------------- real agent on adb

class Payloads(http.server.BaseHTTPRequestHandler):
    """What the test apps download: /ping (4 bytes) and /blob?kb=N (N kilobytes that do not compress)."""

    def do_GET(self):
        q = urllib.parse.urlparse(self.path)
        kb = int((urllib.parse.parse_qs(q.query).get("kb") or ["0"])[0])
        body = b"pong" if q.path == "/ping" else os.urandom(1024) * kb
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass

    def log_message(self, *a):
        pass


class Emu:
    def __init__(self, serial, payload_port):
        self.serial = serial
        self.url = f"http://10.0.2.2:{payload_port}"
        self.seq = int(time.time()) % 100000

    def adb(self, *args, timeout=120):
        return subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout).stdout

    def sh(self, cmd, timeout=120):
        return self.adb("shell", cmd, timeout=timeout).replace("\r", "")

    def sdk(self):
        return int(self.sh("getprop ro.build.version.sdk").strip() or 0)

    def uid(self, pkg):
        m = re.search(r"userId=(\d+)", self.sh(f"dumpsys package {pkg}"))
        return m.group(1) if m else None

    def transport(self):
        """WIFI / CELLULAR / VPN… of the default network, from `dumpsys connectivity` ('' = none)."""
        dump = self.sh("dumpsys connectivity")
        m = re.search(r"Active default network: (\d+)", dump)
        if not m:
            return ""
        for line in dump.splitlines():
            if "NetworkAgentInfo" in line and re.search(r"network\{" + m.group(1) + r"\}", line):
                t = re.search(r"Transports: ([A-Z|_]+)", line)
                return t.group(1) if t else ""
        return ""

    def network(self, kind):
        """Switch the emulator to Wi-Fi or to its emulated mobile network and wait until it is the default one."""
        if kind == "wifi":
            self.sh("svc wifi enable")
        else:
            self.sh("svc data enable")
            self.sh("svc wifi disable")
        want = "WIFI" if kind == "wifi" else "CELLULAR"
        got = wait_for(60, lambda: self.transport() if want in self.transport() else None, every=2)
        ok(f"default network is {'Wi-Fi' if kind == 'wifi' else 'mobile data'}", got is not None, self.transport())
        time.sleep(3)

    def probe(self, pkg, kb=0, timeout_ms=6000):
        """One request from the test app. @return (ok, bytes, raw line)."""
        self.seq += 1
        path = f"/blob?kb={kb}" if kb else "/ping"
        self.sh(f"am start -W -n {pkg}/{ACTIVITY} --es url '{self.url}{path}' --es id {self.seq} --ei timeoutMs {timeout_ms}")
        needle = f"pkg={pkg} id={self.seq} "
        line = wait_for(timeout_ms / 1000 * 3 + 30, lambda: next((l for l in self.sh("logcat -d -s NetProbe:I").splitlines() if needle in l), None), every=1)
        if not line:
            return None, 0, "(no result logged)"
        n = re.search(r"bytes=(\d+)", line)
        return "ok=true" in line, int(n.group(1)) if n else 0, line.split("RESULT ", 1)[-1]

    def works(self, pkg):
        good, _, line = self.probe(pkg)
        return good is True, line

    def fails(self, pkg):
        good, _, line = self.probe(pkg)
        return good is False, line


def emulator(api, args):
    emu = Emu(args.serial, args.payload_port)
    number = args.device
    since = int(time.time() * 1000) - 1000
    sdk = emu.sdk()
    agent = "com.dallycontrol.agent.debug" if "com.dallycontrol.agent.debug" in emu.sh("pm list packages com.dallycontrol.agent") else "com.dallycontrol.agent"
    section(f"emulator API {sdk}, agent {agent}")
    ok("the agent is Device Owner", agent + "/" in emu.sh("dumpsys device_policy | grep -A4 -i 'device owner'"))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.payload_port), Payloads)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    for name in ("blocked", "allowed", "essential"):
        apk = os.path.join(args.apks, f"netprobe-{name}.apk")
        if not os.path.exists(apk):
            raise SystemExit(f"{apk} missing: run scripts/build-netprobe-testapps.sh")
        emu.adb("install", "-r", apk)
    uids = {p: emu.uid(p) for p in (BLOCKED, ALLOWED, ESSENTIAL)}
    ok("test apps installed", all(uids.values()), str(uids))
    emu.sh("logcat -c")

    policy = Policy(api)
    policy.save(None)
    dev_id, moved = policy.adopt(number)
    chk("device moved to the test policy", moved, "OK")

    def view():
        return api.data(f"/agent/v1/devices/{number}/mobileData") or {}

    def sync():
        api.post(f"/agent/v1/devices/{number}/sync")

    def wait_state(what, pred, seconds=240):
        """Wake the phone until its reported mobile-data state satisfies pred (or the section is gone, pred(None))."""
        def step():
            sync()
            v = view()
            return v if pred(v.get("status")) else None
        v = wait_for(seconds, step, every=6)
        ok(what, v is not None, json.dumps((view().get("status") or {}), ensure_ascii=False)[:400])
        return v or view()

    def blocked_of(status):
        return {b["pkg"]: b["reason"] for b in (status or {}).get("blocked", [])}

    def agent_checks_in():
        before = (api.data(f"/agent/v1/devices/{number}/state") or {}).get("updatedAt") or 0
        def newer():
            sync()
            return ((api.data(f"/agent/v1/devices/{number}/state") or {}).get("updatedAt") or 0) > before
        return bool(wait_for(90, newer, every=5))

    try:
        section("baseline: nothing restricted")
        wait_state("no mobile-data state while the policy has no section", lambda s: s is None)
        emu.network("wifi")
        chk("blocked-app works on Wi-Fi", emu.works(BLOCKED)[0], True)
        emu.network("mobile")
        for p in (BLOCKED, ALLOWED, ESSENTIAL):
            good, line = emu.works(p)
            ok(f"{p.rsplit('.', 1)[-1]}-app works on mobile data", good, line)

        mechanisms = ["auto"] if sdk < 28 else ["auto", "vpn"]
        for mech in mechanisms:
            expect = "vpn" if (mech == "vpn" or sdk < 28) else "dpm"
            section(f"blocklist, mechanism {mech} (expects {expect})")
            policy.save({"mode": "blocklist", "apps": [BLOCKED], "mechanism": mech})
            v = wait_state(f"phone reports mechanism {expect} with the app blocked",
                           lambda s: s is not None and s.get("mechanism") == expect and BLOCKED in blocked_of(s)
                           and (expect != "vpn" or s.get("vpnActive") is True))
            chk("blocked for the policy's list", blocked_of(v.get("status")).get(BLOCKED), "policy")
            chk("no problem reported", (v.get("status") or {}).get("problem"), None)
            if expect == "dpm":
                netpolicy = emu.sh("dumpsys netpolicy")
                admin = netpolicy.split("Admin restricted uids for metered data:", 1)[-1][:400] if "Admin restricted uids" in netpolicy else ""
                ok("dumpsys netpolicy: the app's uid is admin-restricted for metered data", uids[BLOCKED] in admin, admin.strip()[:120])
                ok("dumpsys netpolicy: the allowed app's uid is not", uids[ALLOWED] not in admin)
                policy_dump = emu.sh("dumpsys device_policy")
                if "meteredDisabledPackages" in policy_dump:
                    listed = re.search(r"meteredDisabledPackages=(.*)", policy_dump).group(1)
                    ok("dumpsys device_policy: the admin's metered-disabled list has the package", BLOCKED in listed, listed[:200])
                else:
                    print("    (dumpsys device_policy does not print the metered-disabled list on this Android version)")
                ok("no VPN is used", "VPN" not in emu.transport() and emu.sh("settings get secure always_on_vpn_app").strip() in ("null", ""))
            else:
                chk("always-on VPN is the agent", emu.sh("settings get secure always_on_vpn_app").strip(), agent)
                chk("always-on VPN without lockdown", emu.sh("settings get secure always_on_vpn_lockdown").strip() in ("0", "null", ""), True)
                ok("dumpsys device_policy: VPN settings are locked (no_config_vpn)", "no_config_vpn" in emu.sh("dumpsys device_policy"))
                conn = emu.sh("dumpsys connectivity")
                ok("dumpsys connectivity: a VPN network exists", re.search(r"Transports: [A-Z|_]*VPN|type: VPN\[", conn) is not None)
                ok("no VPN consent dialog on screen", "vpndialogs" not in emu.sh("dumpsys window | grep -i -E 'mCurrentFocus|mFocusedApp'"))
            good, line = emu.fails(BLOCKED)
            ok("blocked-app FAILS on mobile data", good, line)
            good, line = emu.works(ALLOWED)
            ok("allowed-app works on mobile data", good, line)
            ok("the agent still reaches the console on mobile data", agent_checks_in())
            emu.network("wifi")
            good, line = emu.works(BLOCKED)
            ok("blocked-app works on Wi-Fi", good, line)
            good, line = emu.works(ALLOWED)
            ok("allowed-app works on Wi-Fi", good, line)
            if expect == "vpn":
                wait_state("VPN idle on Wi-Fi (not capturing the blocked apps)", lambda s: s is not None and s.get("vpnActive") is False, 90)
            emu.network("mobile")
            good, line = emu.fails(BLOCKED)
            ok("back on mobile data: blocked-app fails again", good, line)
            good, line = emu.works(ALLOWED)
            ok("back on mobile data: allowed-app still works", good, line)

        section("allowlist: only the listed apps use mobile data")
        policy.save({"mode": "allowlist", "apps": [ALLOWED]})
        v = wait_state("phone blocks the apps outside the list",
                       lambda s: s is not None and BLOCKED in blocked_of(s) and ALLOWED not in blocked_of(s) and s.get("mechanism") != "none")
        ok("the agent is never in the blocked list", agent not in blocked_of(v.get("status")))
        ok("Google Play services are not in the blocked list", "com.google.android.gms" not in blocked_of(v.get("status")))
        if (v.get("status") or {}).get("mechanism") == "vpn":
            wait_state("VPN capturing on mobile data", lambda s: s is not None and s.get("vpnActive") is True, 60)
        good, line = emu.fails(BLOCKED)
        ok("app outside the list fails on mobile data", good, line)
        good, line = emu.fails(ESSENTIAL)
        ok("another app outside the list fails too", good, line)
        good, line = emu.works(ALLOWED)
        ok("listed app works on mobile data", good, line)
        ok("the agent still reaches the console", agent_checks_in())

        section("device override on top of the policy")
        api.post(f"/agent/v1/devices/{number}/mobileData", {"allow": [ESSENTIAL], "block": [ALLOWED]})
        wait_state("override applied on the phone", lambda s: s is not None and ALLOWED in blocked_of(s) and ESSENTIAL not in blocked_of(s))
        good, line = emu.works(ESSENTIAL)
        ok("app allowed on this device works on mobile data", good, line)
        good, line = emu.fails(ALLOWED)
        ok("app blocked on this device fails on mobile data", good, line)
        api.post(f"/agent/v1/devices/{number}/mobileData", {"mode": "unrestricted"})
        wait_state("unrestricted on this device: the phone lifts everything", lambda s: s is None)
        good, line = emu.works(BLOCKED)
        ok("with «sin restricción» every app works on mobile data", good, line)
        api.post(f"/agent/v1/devices/{number}/mobileData", {"mode": "policy"})

        section("app limit: warning, then automatic block")
        policy.save({"mode": "open", "appLimits": [{"pkg": BLOCKED, "limitMb": 2}], "warnPercent": 50, "period": "daily"})
        v = wait_state("limit active, nothing blocked yet", lambda s: s is not None and s.get("mode") == "open" and not blocked_of(s))
        before = next((a["bytes"] for a in (v.get("status") or {}).get("apps", []) if a["pkg"] == BLOCKED), 0)
        print(f"    blocked-app has used {before} bytes of mobile data today")
        good, n, line = emu.probe(BLOCKED, kb=1300, timeout_ms=30000)
        ok("blocked-app downloads 1.3 MB on mobile data", good and n == 1300 * 1024, line)
        warn = wait_for(240, lambda: (sync(), alerts(api, number, "dataWarning", since))[1], every=8)
        ok("alert «Datos móviles: cerca del límite» raised", len(warn) >= 1)
        if warn:
            ok("alert names the app and its figures", BLOCKED in warn[0]["detail"] and " de 2 MB" in warn[0]["detail"], warn[0]["detail"])
        if not blocked_of(view().get("status")):
            emu.probe(BLOCKED, kb=1300, timeout_ms=30000)
        v = wait_state("over the limit: the phone blocks the app by itself",
                       lambda s: s is not None and blocked_of(s).get(BLOCKED) == "appLimit", 300)
        limit = wait_for(120, lambda: (sync(), alerts(api, number, "dataLimit", since))[1], every=8)
        ok("alert «Datos móviles: límite superado» raised", len(limit) >= 1)
        if (v.get("status") or {}).get("mechanism") == "vpn":
            wait_state("VPN capturing", lambda s: s is not None and s.get("vpnActive") is True, 60)
        good, line = emu.fails(BLOCKED)
        ok("blocked-app fails on mobile data after spending its limit", good, line)
        good, line = emu.works(ALLOWED)
        ok("an app without a limit keeps working", good, line)
        row = next((r for r in view().get("rows", []) if r.get("pkg") == BLOCKED), {})
        chk("console: the app's row is over its limit", (row.get("level"), row.get("limitbytes")), ("over", 2 * MB))
        chk("console: consumption is the phone's reading", view().get("source"), "agent")
        emu.network("wifi")
        good, line = emu.works(BLOCKED)
        ok("over the limit, the app still works on Wi-Fi", good, line)
        emu.network("mobile")
        time.sleep(70)
        chk("the limit is announced once per period", (len(alerts(api, number, "dataWarning", since)), len(alerts(api, number, "dataLimit", since))), (len(warn), len(limit)))
        report_checks(api, number, ["Equipo", "App"])

        section("device limit: every app but the essential ones loses mobile data")
        used_mb = int((view().get("deviceBytes") or 0) / MB)
        policy.save({"mode": "open", "deviceLimitMb": used_mb + 3, "warnPercent": 100, "period": "daily", "essential": [ESSENTIAL]})
        wait_state("device limit active, nothing blocked yet", lambda s: s is not None and s.get("deviceLimitBytes") == (used_mb + 3) * MB and not blocked_of(s))
        good, n, line = emu.probe(ALLOWED, kb=4200, timeout_ms=40000)
        ok("allowed-app downloads 4.2 MB on mobile data", good and n == 4200 * 1024, line)
        v = wait_state("device over its limit: non-essential apps blocked",
                       lambda s: s is not None and blocked_of(s).get(ALLOWED) == "deviceLimit" and ESSENTIAL not in blocked_of(s), 300)
        ok("the agent is not blocked by the device limit", agent not in blocked_of(v.get("status")))
        if (v.get("status") or {}).get("mechanism") == "vpn":
            wait_state("VPN capturing", lambda s: s is not None and s.get("vpnActive") is True, 60)
        good, line = emu.fails(ALLOWED)
        ok("a non-essential app fails on mobile data", good, line)
        good, line = emu.works(ESSENTIAL)
        ok("the essential app keeps working on mobile data", good, line)
        ok("the agent still reaches the console", agent_checks_in())
        device_alert = wait_for(120, lambda: [a for a in alerts(api, number, "dataLimit", since) if a["detail"].startswith("Equipo:")], every=8)
        ok("alert «límite superado» for the whole device", len(device_alert) >= 1)
        api.post(f"/agent/v1/devices/{number}/mobileData", {"ignoreLimits": True})
        wait_state("override «sin límites» on this device lifts the block", lambda s: s is None or not blocked_of(s))
        good, line = emu.works(ALLOWED)
        ok("with the limits ignored the app works again", good, line)
        api.post(f"/agent/v1/devices/{number}/mobileData", {"mode": "policy"})

        section("policy removed: everything is undone")
        policy.save({"mode": "blocklist", "apps": [BLOCKED]})
        wait_state("blocklist back", lambda s: s is not None and BLOCKED in blocked_of(s))
        policy.save(None)
        wait_state("the phone reports no mobile-data state", lambda s: s is None)
        good, line = emu.works(BLOCKED)
        ok("blocked-app works on mobile data again", good, line)
        netpolicy = emu.sh("dumpsys netpolicy")
        admin = netpolicy.split("Admin restricted uids for metered data:", 1)[-1][:300] if "Admin restricted uids" in netpolicy else ""
        ok("dumpsys netpolicy: no admin-restricted uid left", uids[BLOCKED] not in admin and uids[ALLOWED] not in admin, admin.strip()[:120])
        chk("no always-on VPN left", emu.sh("settings get secure always_on_vpn_app").strip() in ("null", ""), True)
        ok("VPN settings unlocked again", "no_config_vpn" not in emu.sh("dumpsys device_policy"))
        ok("no VPN network left", "VPN" not in emu.transport())
    finally:
        api.post(f"/agent/v1/devices/{number}/mobileData", {"mode": "policy"})
        policy.cleanup()
        sync()
        emu.sh("svc wifi enable")
        server.shutdown()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-password", default=os.environ.get("ADMIN_PW", "admin"))
    ap.add_argument("--serial", help="adb serial of an emulator with the agent enrolled as Device Owner")
    ap.add_argument("--device", help="its device number in the console")
    ap.add_argument("--apks", default=os.path.join(HERE, "testapp", "netprobe", "out"), help="where build-netprobe-testapps.sh left the test apps")
    ap.add_argument("--payload-port", type=int, default=8214, help="host port for the payloads the test apps download")
    ap.add_argument("--mailpit", help="Mailpit base URL: also check the alert e-mail (simulated mode)")
    ap.add_argument("--mail-to", default="datos-e2e@example.com")
    args = ap.parse_args()
    api = Api(args.base)
    if api.login(args.admin_user, args.admin_password) != "OK":
        raise SystemExit("login failed")
    if args.serial:
        if not args.device:
            raise SystemExit("--serial needs --device")
        emulator(api, args)
    else:
        simulated(api, args)
    print(f"RESULT: PASS={PASS} FAIL={FAIL}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
