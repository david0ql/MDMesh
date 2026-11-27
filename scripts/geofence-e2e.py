#!/usr/bin/env python3
"""
End-to-end test of geofences (zones with enter/exit alerts) against a running control plane.

Two modes:

1. Simulated device (default) — this script plays the device over the Agent v1 API, like scripts/agent-v1-e2e.sh:
   it enrolls a throw-away device, creates zones and injects positions inside -> outside -> inside, a jumpy point,
   inaccurate points and a batch of buffered positions (the "reconnected after hours offline" case), then checks
   the inside/outside state, the alerts (type, zone, time of the position), the device history, the paginated
   track + its export, the tracking interval a zone pushes into the configuration and, with --mailpit, the e-mail.

       scripts/geofence-e2e.py --base http://localhost:8080 [--admin-password admin] [--mailpit http://localhost:8025]

2. Real agent on an emulator (--serial + --device) — the device is already enrolled (scripts/adb-enroll.sh) and
   this script moves it with `adb emu geo fix`: inside -> outside -> inside, then the same trip with the network
   off so the positions arrive as one batch on reconnection.

       scripts/geofence-e2e.py --base http://localhost:8088 --serial emulator-5554 --device <device number> \
                               [--mailpit http://localhost:8025]

   Emulator note: on API 30+ images the emulated GNSS only accepts the first injected fix after boot (see
   docs/TESTING.md). There, pass --mock-provider: the positions go through Android's test location provider
   (`cmd location providers add-test-provider`, Android 12+), which accepts every one of them.

Needs a database seeded like a real install (scripts/dev-seed.sh on the dev stack). It creates e2e-* zones and
restores the geofence settings on exit. Exit code 0 = every check passed.
"""
import argparse
import hashlib
import http.cookiejar
import json
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

PASS = 0
FAIL = 0
M = 1 / 111_195.0          # degrees of latitude per metre
LAT, LON = 4.7000, -74.0500  # centre of the test zone (Bogotá)
MIN = 60_000


def chk(name, got, want):
    global PASS, FAIL
    if got == want:
        PASS += 1
        print(f"  PASS: {name}")
    else:
        FAIL += 1
        print(f"  FAIL: {name} (got {got!r} want {want!r})")


def ok(name, cond, detail=""):
    chk(name + (f" [{detail}]" if detail and not cond else ""), bool(cond), True)


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
        try:
            with opener.open(req, data, timeout=60) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

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


class Cleanup:
    def __init__(self, api):
        self.api = api
        self.zones = []
        self.recipients_before = None
        self.settings = None
        self.device_ids = []
        self.movers = []

    def run(self):
        for m in self.movers:
            m.close()
        for z in self.zones:
            self.api.delete(f"/geofences/v1/zones/{z}")
        if self.settings:
            self.api.put("/geofences/v1/settings", self.settings)
        if self.device_ids:
            self.api.post("/devices/deleteBulk", {"ids": self.device_ids})


def alerts(api, number, kind=None):
    q = "?from=0" + (f"&type={kind}" if kind else "")
    rows = (api.data("/alerts/v1/list" + q) or {}).get("rows", [])
    return [r for r in rows if r.get("number") == number and str(r.get("type", "")).startswith("geofence")]


def zone_state(api, zone_id, number):
    for s in api.data("/geofences/v1/states") or []:
        if s["zoneId"] == zone_id and s["number"] == number:
            return s
    return None


def wait_for(seconds, fn, every=3):
    end = time.time() + seconds
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(every)
    return fn()


def mail_to(mailpit, address):
    """Messages Mailpit holds for an address (newest first), each with Subject and HTML."""
    url = mailpit.rstrip("/") + "/api/v1/search?query=" + urllib.parse.quote(f"to:{address}")
    with urllib.request.urlopen(url, timeout=20) as r:
        found = json.loads(r.read().decode()).get("messages", [])
    out = []
    for m in found:
        with urllib.request.urlopen(mailpit.rstrip("/") + "/api/v1/message/" + m["ID"], timeout=20) as r:
            out.append(json.loads(r.read().decode()))
    return out


def check_mail(api, mailpit, address, number, min_alerts, expect_types):
    print("== e-mail (Mailpit) ==")
    chk("server reports SMTP configured", (api.data("/geofences/v1/mail") or {}).get("configured"), True)
    msgs = wait_for(240, lambda: mail_to(mailpit, address), every=10)
    ok("an alert e-mail arrived", len(msgs) >= 1)
    if not msgs:
        return
    html = " ".join(m.get("HTML", "") for m in msgs)
    subjects = " | ".join(m.get("Subject", "") for m in msgs)
    print(f"    {len(msgs)} mail(s): {subjects}")
    ok("subject names the alert or the count", any(t in subjects for t in ("Salida de zona", "Entrada a zona", "alertas nuevas")), subjects)
    for t in expect_types:
        ok(f"body lists «{t}»", t in html)
    ok("body links to the device", f"/devices/{urllib.parse.quote(number)}" in html)
    ok("body links to the map", "?tab=location" in html and "Ver en el mapa" in html)
    ok("body links to the alerts page", "/alerts" in html)
    ok(f"grouped: fewer mails ({len(msgs)}) than alerts ({min_alerts})", len(msgs) < min_alerts)


# ----------------------------------------------------------------------------------------------- simulated device

def simulated(api, args, clean):
    print("== enroll a throw-away device ==")
    tok = api.post("/agent/v1/token")["data"]["token"]
    enr = api.call("POST", "/rest/public/agent/v1/enroll", {
        "enrollToken": tok, "agent": {"version": "0.9.8", "package": "com.dallycontrol.agent"},
        "device": {"androidSdkInt": 34, "isDeviceOwner": True},
        "capabilities": {"policy": ["wifi"], "appManagement": [], "remoteControl": {"tier": "none"}, "oem": {"vendor": "e2e", "knox": False}},
    })
    chk("enroll OK", enr.get("status"), "OK")
    number, secret = enr["data"]["deviceId"], enr["data"]["deviceSecret"]
    found = api.post("/devices/search", {"value": number, "pageSize": 5})["data"]["devices"]["items"]
    dev_id = next(d["id"] for d in found if d["number"] == number)
    clean.device_ids.append(dev_id)

    state = {"battery": 80, "charging": False, "locked": False, "kioskActive": False, "androidRelease": "14", "lastBootAt": 1000}

    def checkin(location=None, trail=None, caps=None, extra_state=None):
        dyn = {}
        if location:
            dyn["location"] = location
        if trail is not None:
            dyn["trail"] = trail
        body = {"deviceId": number, "capabilities": caps or {"policy": ["wifi"]},
                "state": dict(state, **(extra_state or {})), "telemetry": {"dynamic": dyn}}
        return api.call("POST", "/rest/public/agent/v1/checkin", body, {"Authorization": f"Bearer {secret}"}, cookies=False)

    t0 = int(time.time() * 1000) - 60 * MIN
    step = [0]

    def fix(north_m, acc=10.0, lat=LAT, lon=LON, provider="gps"):
        step[0] += 1
        return {"lat": lat + north_m * M, "lon": lon, "accuracyM": acc, "provider": provider, "capturedAt": t0 + step[0] * MIN}

    def send(north_m, acc=10.0, **kw):
        f = fix(north_m, acc, **kw)
        chk_status = checkin(location=f).get("status")
        if chk_status != "OK":
            chk("check-in OK", chk_status, "OK")
        return f["capturedAt"]

    print("== settings + zone ==")
    clean.settings = api.data("/geofences/v1/settings")
    chk("settings have defaults", sorted(clean.settings.keys()),
        ["confirmFixes", "emailIntervalMin", "hysteresisM", "maxAccuracyM", "offlineMinutes", "retentionDays"])
    s = api.put("/geofences/v1/settings", dict(clean.settings, maxAccuracyM=100, hysteresisM=15, confirmFixes=2, emailIntervalMin=1))
    chk("settings saved", s.get("status"), "OK")
    address = f"e2e-{random.randint(100000, 999999)}@example.test"
    zname = f"e2e-bodega-{random.randint(1000, 9999)}"
    z = api.post("/geofences/v1/zones", {
        "name": zname, "color": "#16a34a", "shape": "circle", "centerLat": LAT, "centerLon": LON, "radiusM": 200,
        "rule": "stayInside", "active": True, "alertOnReturn": True, "allDevices": False, "trackingMinutes": 3,
        "emails": address if args.mailpit else None, "groupIds": [], "deviceIds": [dev_id]})
    chk("zone created", z.get("status"), "OK")
    zone = z["data"]
    clean.zones.append(zone["id"])
    chk("zone lists its device", zone["deviceIds"], [dev_id])
    bad = api.post("/geofences/v1/zones", {"name": "x", "shape": "circle", "centerLat": 95, "centerLon": 0, "radiusM": 100,
                                           "rule": "stayInside", "groupIds": [], "deviceIds": []})
    chk("invalid circle refused", bad.get("message"), "error.geofence.circle")
    bad = api.post("/geofences/v1/zones", {"name": "x", "shape": "polygon", "points": [[1, 1], [2, 2]],
                                           "rule": "stayInside", "groupIds": [], "deviceIds": []})
    chk("polygon with 2 vertices refused", bad.get("message"), "error.geofence.polygon")
    dz = api.data(f"/geofences/v1/devices/{number}/zones") or []
    chk("device sees its zone (not evaluated yet)", [(x["name"], x["state"]) for x in dz], [(zname, None)])

    print("== a zone raises the device's reporting frequency (config.apply) ==")
    c = checkin(caps={"policy": ["wifi"], "device": ["configApply"]}, extra_state={"appliedConfigRevision": "0000"})
    cfg = [x for x in (c.get("data") or {}).get("commands", []) if x["type"] == "config.apply"]
    chk("config.apply delivered", len(cfg), 1)
    if cfg:
        chk("tracking interval comes from the zone", (cfg[0]["payload"].get("tracking") or {}).get("intervalMinutes"), 3)

    print("== first state, jumpy point, inaccurate points ==")
    send(0)
    chk("one position does not place the device yet", zone_state(api, zone["id"], number), None)
    send(3)
    st = zone_state(api, zone["id"], number)
    chk("two positions inside: inside, not in alert", ((st or {}).get("state"), (st or {}).get("violating")), ("inside", False))
    chk("no alert for a first state on the allowed side", len(alerts(api, number)), 0)
    send(1000)          # one jumpy point…
    send(5)             # …and back
    chk("a single jumpy point does not alert", len(alerts(api, number)), 0)
    send(500, acc=300)
    send(500, acc=300)
    chk("inaccurate positions (±300 m) do not alert", len(alerts(api, number)), 0)
    chk("still inside", zone_state(api, zone["id"], number)["state"], "inside")

    print("== inside -> outside ==")
    t_out = send(500, acc=12)
    chk("one position outside is not enough", len(alerts(api, number)), 0)
    send(520, acc=12)
    ex = alerts(api, number, "geofenceExit")
    chk("exit alert raised", len(ex), 1)
    if ex:
        chk("alert carries the time of the position that left", ex[0]["createdat"], t_out)
        ok("alert names the zone", zname in ex[0]["detail"], ex[0]["detail"])
        chk("alert label", ex[0]["tipo"], "Salida de zona")
        chk("alert is pending", ex[0]["estado"], "Pendiente")
    st = zone_state(api, zone["id"], number)
    chk("state: outside and in alert", (st["state"], st["violating"]), ("outside", True))
    ev = api.data(f"/agent/v1/devices/{number}/events?since=0") or []
    ok("device history has the exit event", any(e["type"] == "geofenceExit" and e["ts"] == t_out for e in ev))

    print("== outside -> inside ==")
    t_in = send(20)
    send(10)
    en = alerts(api, number, "geofenceEnter")
    chk("return alert raised", len(en), 1)
    if en:
        chk("return alert carries the position's time", en[0]["createdat"], t_in)
        ok("return alert says it came back", "Regresó" in en[0]["detail"], en[0]["detail"])
    st = zone_state(api, zone["id"], number)
    chk("state: inside, not in alert", (st["state"], st["violating"]), ("inside", False))

    print("== batch of buffered positions on reconnection (out of order in the payload) ==")
    b1, b2, b3, b4 = fix(600), fix(650), fix(15), fix(5)
    chk("batch check-in OK", checkin(location=b4, trail=[b3, b1, b2]).get("status"), "OK")
    ex = sorted(a["createdat"] for a in alerts(api, number, "geofenceExit"))
    en = sorted(a["createdat"] for a in alerts(api, number, "geofenceEnter"))
    chk("batch produced a second exit at the buffered position's time", ex, [t_out, b1["capturedAt"]])
    chk("batch produced a second return at the buffered position's time", en, [t_in, b3["capturedAt"]])
    checkin(location=b4, trail=[b1, b2, b3])
    chk("a resent batch changes nothing", len(alerts(api, number)), 4)

    print("== polygon «no debe entrar» zone ==")
    plat = LAT + 0.05
    pname = f"e2e-restringida-{random.randint(1000, 9999)}"
    p = api.post("/geofences/v1/zones", {
        "name": pname, "color": "#dc2626", "shape": "polygon", "rule": "keepOut", "active": True, "alertOnReturn": False,
        "points": [[plat - 0.001, LON - 0.001], [plat - 0.001, LON + 0.001], [plat + 0.001, LON + 0.001], [plat + 0.001, LON - 0.001]],
        "groupIds": [], "deviceIds": [dev_id]})
    chk("polygon zone created", p.get("status"), "OK")
    poly = p["data"]
    clean.zones.append(poly["id"])
    chk("polygon keeps its 4 vertices", len(poly["points"]), 4)
    send(0)                                   # first state for the new zone: outside it (two positions)
    send(2)
    chk("first state of the restricted zone: outside, no alert",
        (zone_state(api, poly["id"], number)["state"], len([a for a in alerts(api, number) if pname in a["detail"]])), ("outside", 0))
    t_p = send(0, lat=plat)
    send(10, lat=plat)
    inside = [a for a in alerts(api, number, "geofenceEnter") if pname in a["detail"]]
    chk("entering the restricted zone alerts", len(inside), 1)
    if inside:
        chk("…at the position's time", inside[0]["createdat"], t_p)
        ok("…as a restricted-zone entry", "restringida" in inside[0]["detail"], inside[0]["detail"])
    st = zone_state(api, poly["id"], number)
    chk("restricted zone state: inside and in alert", (st["state"], st["violating"]), ("inside", True))
    third = [a for a in alerts(api, number, "geofenceExit") if zname in a["detail"]]
    chk("…and the same positions left the first zone (third exit)", len(third), 3)

    print("== schedule: outside the hours nothing alerts ==")
    # A schedule that never contains the test positions' hour: one minute long, twelve hours away from them.
    now_h = time.gmtime((t0 + step[0] * MIN) / 1000).tm_hour
    off = (now_h + 12) % 24
    sched = json.dumps({"days": [], "from": f"{off:02d}:00", "to": f"{off:02d}:01", "tz": "UTC"})
    sname = f"e2e-horario-{random.randint(1000, 9999)}"
    sz = api.post("/geofences/v1/zones", {
        "name": sname, "shape": "circle", "centerLat": plat, "centerLon": LON, "radiusM": 150, "rule": "stayInside",
        "active": True, "alertOnReturn": True, "schedule": sched, "groupIds": [], "deviceIds": [dev_id]})
    chk("scheduled zone created", sz.get("status"), "OK")
    clean.zones.append(sz["data"]["id"])
    send(0, lat=plat)
    send(2, lat=plat)
    send(900, lat=plat)
    send(950, lat=plat)
    chk("left the scheduled zone: state tracked", zone_state(api, sz["data"]["id"], number)["state"], "outside")
    chk("…but no alert outside its hours", len([a for a in alerts(api, number) if sname in a["detail"]]), 0)

    print("== device already out of place when the zone is created ==")
    # The device is ~1 km north of this new «debe permanecer dentro» zone from its very first position.
    far = LAT + 0.2
    oname = f"e2e-ya-fuera-{random.randint(1000, 9999)}"
    oz = api.post("/geofences/v1/zones", {
        "name": oname, "shape": "circle", "centerLat": far, "centerLon": LON, "radiusM": 200, "rule": "stayInside",
        "active": True, "alertOnReturn": True, "groupIds": [], "deviceIds": [dev_id]})
    chk("zone created around a place the device is not in", oz.get("status"), "OK")
    clean.zones.append(oz["data"]["id"])
    kname = f"e2e-ya-dentro-{random.randint(1000, 9999)}"
    kz = api.post("/geofences/v1/zones", {
        "name": kname, "shape": "circle", "centerLat": far + 1000 * M, "centerLon": LON, "radiusM": 200, "rule": "keepOut",
        "active": True, "alertOnReturn": True, "groupIds": [], "deviceIds": [dev_id]})
    chk("«no debe entrar» zone created around where the device is", kz.get("status"), "OK")
    clean.zones.append(kz["data"]["id"])
    t_first = send(1000, lat=far)
    chk("the zone is applied to the very next position (cache dropped on save)",
        [x["name"] for x in api.data(f"/geofences/v1/devices/{number}/zones") if x["name"] in (oname, kname)], [oname, kname])
    chk("one position: no alert yet", len([a for a in alerts(api, number) if oname in a["detail"] or kname in a["detail"]]), 0)
    send(1010, lat=far)
    out_first = [a for a in alerts(api, number, "geofenceExit") if oname in a["detail"]]
    chk("already outside: exit alert once confirmed", len(out_first), 1)
    if out_first:
        ok("…flagged «desde la primera posición»", "desde la primera posición" in out_first[0]["detail"], out_first[0]["detail"])
        chk("…dated at the first position", out_first[0]["createdat"], t_first)
    st = zone_state(api, oz["data"]["id"], number)
    chk("console state: outside and in alert", ((st or {}).get("state"), (st or {}).get("violating")), ("outside", True))
    in_first = [a for a in alerts(api, number, "geofenceEnter") if kname in a["detail"]]
    chk("already inside a restricted zone: entry alert once confirmed", len(in_first), 1)
    if in_first:
        ok("…flagged «desde la primera posición»", "Dentro de la zona restringida" in in_first[0]["detail"] and "primera posición" in in_first[0]["detail"], in_first[0]["detail"])
    st = zone_state(api, kz["data"]["id"], number)
    chk("console state: inside and in alert", ((st or {}).get("state"), (st or {}).get("violating")), ("inside", True))
    send(1015, lat=far)
    chk("not repeated while it stays there", len([a for a in alerts(api, number) if oname in a["detail"] or kname in a["detail"]]), 2)

    print("== disconnection threshold is saved and shared with the e-mails ==")
    cur = api.data("/geofences/v1/settings")
    chk("default threshold", clean.settings.get("offlineMinutes"), 20)
    chk("threshold saved", api.put("/geofences/v1/settings", dict(cur, offlineMinutes=45)).get("data", {}).get("offlineMinutes"), 45)
    chk("Alerts list uses the saved threshold when none is passed", (api.get("/alerts/v1/list?from=0&type=disconnected") or {}).get("status"), "OK")

    print("== history: paginated track, accuracy tag, export ==")
    now = int(time.time() * 1000)
    tr = api.data(f"/geofences/v1/devices/{number}/track?from={t0}&to={now}&page=0&size=5&order=desc")
    chk("track total = positions sent", tr["total"], step[0])
    chk("page size honoured", len(tr["fixes"]), 5)
    chk("pages", tr["pages"], (step[0] + 4) // 5)
    chk("newest first", tr["fixes"][0]["capturedAt"], t0 + step[0] * MIN)
    every = []
    for pg in range(tr["pages"]):
        every += api.data(f"/geofences/v1/devices/{number}/track?from={t0}&to={now}&page={pg}&size=5")["fixes"]
    chk("all pages together = total, oldest first", [f["capturedAt"] for f in every], [t0 + i * MIN for i in range(1, step[0] + 1)])
    chk("low-accuracy positions are tagged", sum(1 for f in every if f["lowAccuracy"]), 2)
    status, raw, headers = api.raw("GET", f"/rest/private/geofences/v1/devices/{number}/track/export?format=csv&from={t0}&to={now}")
    lines = [ln for ln in raw.decode("utf-8-sig").splitlines() if ln.strip()]
    chk("CSV export HTTP 200", status, 200)
    ok("CSV has a header with Latitud and Precisión", lines and "Latitud" in lines[0] and "Precisi" in lines[0], lines[:1])
    chk("CSV rows = positions", len(lines) - 1, step[0])
    ok("CSV tags low accuracy", sum(1 for ln in lines if "Baja precisi" in ln) == 2)
    status, raw, headers = api.raw("GET", f"/rest/private/geofences/v1/devices/{number}/track/export?format=xlsx&from={t0}&to={now}")
    ok("Excel export is a .xlsx file", status == 200 and raw[:2] == b"PK" and ".xlsx" in headers.get("Content-Disposition", ""))
    old = api.data(f"/agent/v1/devices/{number}/locations?since=0")
    ok("the old location endpoint still answers (old consoles)", isinstance(old, list) and len(old) == step[0])

    print("== alerts module: filter, review ==")
    all_rows = (api.data("/alerts/v1/list?from=0&type=geofenceExit") or {}).get("rows", [])
    ok("type filter returns only exits", all_rows and all(r["type"] == "geofenceExit" for r in all_rows))
    first = alerts(api, number, "geofenceExit")[0]
    chk("review an alert", api.post("/alerts/v1/review", {"id": first["id"], "reviewed": True}).get("status"), "OK")
    again = [a for a in alerts(api, number) if a["id"] == first["id"]][0]
    chk("it shows as reviewed", again["estado"], "Revisada")
    chk("pending filter no longer lists it",
        first["id"] in [r["id"] for r in (api.data("/alerts/v1/list?from=0&status=pending") or {}).get("rows", [])], False)

    print("== recipients ==")
    r = api.post("/geofences/v1/recipients", {"groupId": None, "emails": "no-es-correo", "types": [], "active": True})
    chk("invalid address refused", r.get("message"), "error.geofence.email")
    chk("anonymous cannot read zones", api.call("GET", "/rest/private/geofences/v1/zones", cookies=False).get("status") == "OK", False)

    if args.mailpit:
        check_mail(api, args.mailpit, address, number, len(alerts(api, number)), ["Salida de zona", "Entrada a zona"])
    else:
        chk("mail status endpoint answers", isinstance((api.data("/geofences/v1/mail") or {}).get("configured"), bool), True)

    print("== deleting the zone stops it ==")
    chk("zone deleted", api.delete(f"/geofences/v1/zones/{zone['id']}").get("status"), "OK")
    clean.zones.remove(zone["id"])
    before = len([a for a in alerts(api, number) if zname in a["detail"]])
    send(0)
    send(900)
    send(950)
    chk("no alert from a deleted zone", len([a for a in alerts(api, number) if zname in a["detail"]]), before)
    chk("the device no longer lists it", zname in [x["name"] for x in api.data(f"/geofences/v1/devices/{number}/zones")], False)


# ------------------------------------------------------------------------------------------------- real emulator

def adb(serial, *cmd, timeout=60):
    r = subprocess.run(["adb", "-s", serial, *cmd], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + (r.stderr if "Exception" in r.stderr else "")).strip()


class Mover:
    """
    Puts the emulator at a position. Default: `adb emu geo fix` (the emulated GNSS). With --mock-provider the
    position is fed through Android's test location provider instead (`cmd location providers …`, Android 12+),
    re-sent every few seconds like a live receiver; that is how the whole trip runs on API 30+ images, whose
    emulated GNSS only accepts the first injected fix after boot.
    """

    def __init__(self, serial, mock):
        self.serial, self.mock = serial, mock
        self.target = None
        self.stop = threading.Event()
        self.thread = None
        self.providers = ["gps"]
        self.warned = False
        if mock:
            # A device with Google Play services serves "fused" from the same satellites: mock both so they agree.
            if "fused" in adb(serial, "shell", "dumpsys", "location"):
                self.providers.append("fused")
            # The shell may only mock locations once its uid holds the mock_location app-op.
            adb(serial, "shell", "appops", "set", "2000", "android:mock_location", "allow")
            adb(serial, "shell", "appops", "set", "com.android.shell", "android:mock_location", "allow")
            for p in self.providers:
                adb(serial, "shell", "cmd", "location", "providers", "add-test-provider", p,
                    "--supportsAltitude", "--supportsSpeed", "--powerRequirement", "1", "--accuracy", "1")
                adb(serial, "shell", "cmd", "location", "providers", "set-test-provider-enabled", p, "true")
            self.thread = threading.Thread(target=self._loop, daemon=True)
            self.thread.start()

    def _push(self):
        lat, lon = self.target
        for p in self.providers:
            out = adb(self.serial, "shell", "cmd", "location", "providers", "set-test-provider-location", p,
                      "--location", f"{lat:.6f},{lon:.6f}", "--accuracy", "6")
            if "Exception" in out and not self.warned:
                self.warned = True
                print(f"    WARN: the test provider «{p}» refused the position: {out.splitlines()[1] if len(out.splitlines()) > 1 else out}")

    def _loop(self):
        while not self.stop.wait(3):
            if self.target:
                try:
                    self._push()
                except Exception:
                    pass

    def go(self, lat, lon):
        self.target = (lat, lon)
        if self.mock:
            self._push()
        else:
            adb(self.serial, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}")  # longitude first

    def close(self):
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=10)
        if self.mock:
            for p in self.providers:
                adb(self.serial, "shell", "cmd", "location", "providers", "remove-test-provider", p)


def fix_trace(serial):
    """The agent's own account of its last fresh-position requests (logcat: providers tried, time, who answered)."""
    out = adb(serial, "logcat", "-d", "-s", "LocationCollector:I")
    return [ln.split("fresh fix: ", 1)[1] for ln in out.splitlines() if "fresh fix: " in ln]


def emulator(api, args, clean):
    serial, number = args.serial, args.device
    found = api.post("/devices/search", {"value": number, "pageSize": 5})["data"]["devices"]["items"]
    dev = next((d for d in found if d["number"] == number), None)
    if not dev:
        sys.exit(f"device {number} is not enrolled on {api.base}")
    dev_id = dev["id"]
    in_lat, in_lon = LAT, LON
    out_lat, out_lon = LAT + 0.02, LON          # ~2.2 km north

    mover = Mover(serial, args.mock_provider)
    clean.movers.append(mover)
    geo = mover.go

    def latest():
        now = int(time.time() * 1000)
        tr = api.data(f"/geofences/v1/devices/{number}/track?from={now - 3600_000}&to={now + 60_000}&page=0&size=1&order=desc")
        return (tr["fixes"] or [None])[0], tr["total"]

    def near(fix, lat, lon, metres=150):
        return fix is not None and abs(fix["lat"] - lat) < metres * M and abs(fix["lon"] - lon) < metres * M * 1.1

    print(f"== emulator {serial}, device {number} (Android {adb(serial, 'shell', 'getprop', 'ro.build.version.release')}) ==")
    clean.settings = api.data("/geofences/v1/settings")
    api.put("/geofences/v1/settings", dict(clean.settings, maxAccuracyM=100, hysteresisM=15, confirmFixes=2, emailIntervalMin=1))
    geo(in_lat, in_lon)
    chk("accurate location mode queued",
        api.post(f"/agent/v1/devices/{number}/commands", {"type": "device.locationMode", "requiresCapability": "device.locationMode",
                                                          "payload": json.dumps({"mode": "active", "fixTimeoutSec": 25})}).get("status"), "OK")
    address = f"e2e-{random.randint(100000, 999999)}@example.test"
    zname = f"e2e-emulador-{random.randint(1000, 9999)}"
    z = api.post("/geofences/v1/zones", {
        "name": zname, "color": "#2563eb", "shape": "circle", "centerLat": in_lat, "centerLon": in_lon, "radiusM": 300,
        "rule": "stayInside", "active": True, "alertOnReturn": True, "trackingMinutes": 1,
        "emails": address if args.mailpit else None, "groupIds": [], "deviceIds": [dev_id]})
    chk("zone created (asks for a position every minute)", z.get("status"), "OK")
    zone = z["data"]
    clean.zones.append(zone["id"])

    print("== position and accuracy reach the server ==")
    geo(in_lat, in_lon)
    first = wait_for(300, lambda: (lambda f: f if near(f[0], in_lat, in_lon) else None)(latest()), every=5)
    ok("the injected position is on the server", first is not None, str(latest()))
    if first:
        f = first[0]
        print(f"    lat={f['lat']:.5f} lon={f['lon']:.5f} ±{f['accuracy']} m provider={f['provider']}")
        ok("it carries an accuracy", f["accuracy"] is not None and f["accuracy"] > 0)
        ok("it is not tagged low accuracy", f["lowAccuracy"] is False)
    st = wait_for(240, lambda: zone_state(api, zone["id"], number), every=5)
    chk("state: inside (confirmed by two positions)", (st or {}).get("state"), "inside")
    traces = wait_for(150, lambda: fix_trace(serial), every=5)   # the next once-a-minute fresh request
    ok("the agent logs which providers it tried and which answered", len(traces) >= 1)
    for t in traces[-3:]:
        print(f"    agente: {t}")
    if args.first_fix_only:
        return

    def walk(lat, lon, label, kind, since_ms):
        geo(lat, lon)
        got = wait_for(420, lambda: [a for a in alerts(api, number, kind) if zname in a["detail"] and a["createdat"] >= since_ms], every=5)
        ok(f"{label}: {kind} alert", len(got) >= 1)
        return got

    print("== inside -> outside -> inside (one fresh position per minute, 2 to confirm) ==")
    t_a = int(time.time() * 1000)
    walk(out_lat, out_lon, "moved out", "geofenceExit", t_a)
    st = zone_state(api, zone["id"], number)
    chk("state: outside and in alert", (st["state"], st["violating"]), ("outside", True))
    t_b = int(time.time() * 1000)
    walk(in_lat, in_lon, "moved back", "geofenceEnter", t_b)
    chk("state: inside", zone_state(api, zone["id"], number)["state"], "inside")

    print("== offline trip: positions buffered on the phone, uploaded as a batch ==")
    _, before_total = latest()
    t_off = int(time.time() * 1000)
    adb(serial, "shell", "svc", "wifi", "disable")
    adb(serial, "shell", "svc", "data", "disable")
    time.sleep(5)
    geo(out_lat, out_lon)
    time.sleep(190)                       # three positions outside, kept on the phone
    geo(in_lat, in_lon)
    time.sleep(190)                       # three positions inside
    _, mid_total = latest()
    chk("nothing reached the server while offline", mid_total, before_total)
    chk("no alert while offline", len([a for a in alerts(api, number) if a["createdat"] >= t_off]), 0)
    t_on = int(time.time() * 1000)
    adb(serial, "shell", "svc", "wifi", "enable")
    adb(serial, "shell", "svc", "data", "enable")
    back = wait_for(300, lambda: latest()[1] > before_total, every=5)
    ok("the buffered positions arrived", back)
    _, after_total = latest()
    ok(f"several positions in the batch ({after_total - before_total})", after_total - before_total >= 4)
    ex = wait_for(120, lambda: [a for a in alerts(api, number, "geofenceExit") if t_off <= a["createdat"] < t_on], every=5)
    en = wait_for(120, lambda: [a for a in alerts(api, number, "geofenceEnter") if t_off <= a["createdat"] < t_on], every=5)
    ok("exit alert dated while the phone was offline", len(ex) == 1, str(ex))
    ok("return alert dated while the phone was offline", len(en) == 1, str(en))
    if ex and en:
        ok("in order: left, then came back", ex[0]["createdat"] < en[0]["createdat"])

    print("== zone created while the phone is already outside it ==")
    oname = f"e2e-emulador-fuera-{random.randint(1000, 9999)}"
    t_c = int(time.time() * 1000)
    oz = api.post("/geofences/v1/zones", {
        "name": oname, "shape": "circle", "centerLat": out_lat, "centerLon": out_lon, "radiusM": 300, "rule": "stayInside",
        "active": True, "alertOnReturn": True, "trackingMinutes": 1, "groupIds": [], "deviceIds": [dev_id]})
    chk("second zone created around the other place", oz.get("status"), "OK")
    clean.zones.append(oz["data"]["id"])
    got = wait_for(300, lambda: [a for a in alerts(api, number, "geofenceExit") if oname in a["detail"]], every=5)
    ok("already outside: exit alert without ever having been inside", len(got) == 1, str(got))
    if got:
        ok("…flagged «desde la primera posición»", "desde la primera posición" in got[0]["detail"], got[0]["detail"])
        ok("…dated after the zone was created", got[0]["createdat"] >= t_c - 90_000)
    st = zone_state(api, oz["data"]["id"], number)
    chk("console state: outside and in alert", ((st or {}).get("state"), (st or {}).get("violating")), ("outside", True))

    print("== diagnosis: providers tried ==")
    for t in fix_trace(serial)[-4:]:
        print(f"    agente: {t}")
    d = api.post(f"/agent/v1/devices/{number}/commands", {"type": "device.diagnose", "requiresCapability": "device.diagnose"})
    cmd_id = (d.get("data") or {}).get("id")

    def diag():
        for c in api.data(f"/agent/v1/devices/{number}/commands?since={t_a}") or []:
            if c.get("id") == cmd_id and c.get("status") == "done":
                try:
                    return json.loads(c.get("detail") or "{}").get("location")
                except ValueError:
                    return None
        return None
    loc = wait_for(90, diag, every=5) if cmd_id else None
    ok("the diagnosis snapshot reports the last position request", bool(loc), str(d.get("message")))
    if loc:
        print(f"    diagnóstico: {loc.get('summary')} · respondió {loc.get('answeredBy')} · disponibles {loc.get('available')}")
        ok("…with the providers tried (or the recent position reused) and who answered",
           bool(loc.get("attempts") or loc.get("answeredBy")) and "available" in loc)

    print("== history ==")
    now = int(time.time() * 1000)
    tr = api.data(f"/geofences/v1/devices/{number}/track?from={t_a - 600_000}&to={now}&page=0&size=2000")
    ok(f"route has the trip ({tr['total']} positions)", tr["total"] >= 8)
    ok("route visits both places", any(near(f, out_lat, out_lon) for f in tr["fixes"]) and any(near(f, in_lat, in_lon) for f in tr["fixes"]))
    status, raw, _ = api.raw("GET", f"/rest/private/geofences/v1/devices/{number}/track/export?format=csv&from={t_a - 600_000}&to={now}")
    chk("CSV export rows", len([ln for ln in raw.decode('utf-8-sig').splitlines() if ln.strip()]) - 1, tr["total"])
    ev = api.data(f"/agent/v1/devices/{number}/events?since={t_a}") or []
    ok("device history has zone events", sum(1 for e in ev if e["type"].startswith("geofence")) >= 4)

    if args.mailpit:
        check_mail(api, args.mailpit, address, number, len(alerts(api, number)), ["Salida de zona", "Entrada a zona"])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:8080", help="server URL as this machine reaches it")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-password", default="admin")
    ap.add_argument("--mailpit", help="Mailpit/MailHog-compatible API base (e.g. http://localhost:8025) to verify the e-mail")
    ap.add_argument("--serial", help="adb serial of an enrolled emulator (enables the emulator mode)")
    ap.add_argument("--device", help="its device number in the console")
    ap.add_argument("--mock-provider", action="store_true",
                    help="emulator mode: feed positions through Android's test location provider (Android 12+) instead of "
                         "`adb emu geo fix`; needed for the whole trip on API 30+ images")
    ap.add_argument("--first-fix-only", action="store_true",
                    help="emulator mode: stop after the first position (API 30+ images accept only one injected fix)")
    args = ap.parse_args()
    if bool(args.serial) != bool(args.device):
        ap.error("--serial and --device go together")

    api = Api(args.base)
    print("== login ==")
    chk("login OK", api.login(args.admin_user, args.admin_password), "OK")
    clean = Cleanup(api)
    try:
        if args.serial:
            emulator(api, args, clean)
        else:
            simulated(api, args, clean)
    finally:
        if args.serial:
            adb(args.serial, "shell", "svc", "wifi", "enable")
            adb(args.serial, "shell", "svc", "data", "enable")
        clean.run()
    print(f"===== RESULT: PASS={PASS} FAIL={FAIL} =====")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
