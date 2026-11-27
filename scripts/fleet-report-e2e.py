#!/usr/bin/env python3
"""
End-to-end check of the fleet inventory exports against a REAL enrolled device (emulator or USB).

It changes the device over adb (battery level, used storage, network), asks it to report with the console's
"Actualizar ahora" (POST /private/fleet/v1/devices/refresh), then downloads the real Excel and CSV exports and checks
that every requested column carries what the device itself says: battery, storage, Android version, connection
state and network. With --mailpit it also checks the scheduled report e-mail ("Enviar ahora" and one run fired by
the server's scheduler) and opens the received attachment.

  # dev stack + an emulator enrolled with scripts/adb-enroll.sh (see docs/TESTING.md)
  scripts/fleet-report-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id>

  # with a local test SMTP (Mailpit: SMTP on 1025, HTTP API on 8025) wired as the server's SMTP_HOST
  scripts/fleet-report-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id> \
      --mailpit http://localhost:8025

  # later, with the device switched off for more than 20 minutes: it must show as "Sin conexión"
  scripts/fleet-report-e2e.py --api http://localhost:8088 --device-id <id> --expect-offline

Only the Python standard library is used (the .xlsx is read with zipfile + xml). Exit code 0 = every check passed.
"""
import argparse, csv, hashlib, http.cookiejar, io, json, re, subprocess, sys, time, urllib.parse, urllib.request
import xml.etree.ElementTree as ET
import zipfile

P = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
P.add_argument("--api", default="http://localhost:8088", help="console/API URL as this machine reaches it")
P.add_argument("--serial", default="", help="adb serial of the enrolled device")
P.add_argument("--device-id", required=True, help="the device's number in the console")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--adb", default="adb")
P.add_argument("--mailpit", default="", help="Mailpit HTTP API (e.g. http://localhost:8025): test the scheduled e-mail")
P.add_argument("--skip-scheduler-wait", action="store_true", help="do not wait for a run fired by the server's scheduler")
P.add_argument("--skip-network", action="store_true", help="do not switch Wi-Fi off/on")
P.add_argument("--fill-mb", type=int, default=300, help="size of the file written to use storage")
P.add_argument("--expect-offline", action="store_true", help="only check that the device shows as offline (no adb)")
P.add_argument("--expect-no-smtp", action="store_true", help="check that 'Enviar ahora' reports the missing SMTP without failing")
P.add_argument("--max-refresh-seconds", type=float, default=15, help="'Actualizar ahora' must refresh within this (raise it on a loaded machine)")
P.add_argument("--slow", type=float, default=1, help="multiply every wait by this on a loaded machine")
P.add_argument("--out", default="", help="directory to keep the downloaded files in")
A = P.parse_args()

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
RESULTS = []
FLEET = "/rest/private/fleet/v1"
REPORTS = "/rest/private/reports/v1"
NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}


def check(name, ok, detail=""):
    RESULTS.append(bool(ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else ""), flush=True)
    return bool(ok)


def raw(method, path, body=None, base=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request((base or A.api) + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with OP.open(req, timeout=120 * A.slow) as r:
        return r.read(), dict(r.headers)


def api(method, path, body=None):
    b, _ = raw(method, path, body)
    j = json.loads(b or b"{}")
    if j.get("status") != "OK":
        raise RuntimeError(f"{method} {path}: {j}")
    return j.get("data")


def adb(*args, timeout=120):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    b, _ = raw("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5})
    if json.loads(b).get("status") != "OK":
        sys.exit(f"login failed: {b[:200]}")


# --- reading the downloads ------------------------------------------------------------------------------------------

def xlsx_sheets(data):
    """{sheet name: [[cell text, ...], ...]} of a workbook (numbers as str of float, empty cells as '')."""
    z = zipfile.ZipFile(io.BytesIO(data))
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", NS):
            shared.append("".join(t.text or "" for t in si.iter("{%s}t" % NS["m"])))
    rels = {r.get("Id"): r.get("Target") for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
    out = {}
    for s in ET.fromstring(z.read("xl/workbook.xml")).find("m:sheets", NS):
        target = rels[s.get("{%s}id" % NS["r"])]
        path = target.lstrip("/") if target.startswith("/") else "xl/" + target
        rows = []
        for row in ET.fromstring(z.read(path)).iter("{%s}row" % NS["m"]):
            cells = {}
            for c in row.findall("m:c", NS):
                col = 0
                for ch in re.match(r"[A-Z]+", c.get("r")).group(0):
                    col = col * 26 + ord(ch) - 64
                v = c.find("m:v", NS)
                if c.get("t") == "s" and v is not None:
                    val = shared[int(v.text)]
                elif c.get("t") == "inlineStr":
                    val = "".join(t.text or "" for t in c.iter("{%s}t" % NS["m"]))
                else:
                    val = v.text if v is not None else ""
                cells[col - 1] = val
            r = [""] * (max(cells) + 1 if cells else 0)
            for i, v in cells.items():
                r[i] = v
            rows.append(r)
        out[s.get("name")] = rows
    return out


def table(rows):
    """[{header: value}] from a header row + data rows."""
    if not rows:
        return []
    head = rows[0]
    return [{h: (r[i] if i < len(r) else "") for i, h in enumerate(head)} for r in rows[1:]]


def download_xlsx(numbers=None, group=None):
    b, h = raw("POST", f"{FLEET}/export", {"format": "xlsx", "numbers": numbers, "group": group, "tz": "America/Bogota"})
    keep("dispositivos.xlsx", b)
    return xlsx_sheets(b), h


def download_csv(sep=";", numbers=None):
    b, h = raw("POST", f"{FLEET}/export", {"format": "csv", "separator": sep, "numbers": numbers, "tz": "America/Bogota"})
    keep("dispositivos.csv", b)
    return b, h


def parse_csv(b, sep=";"):
    text = b.decode("utf-8-sig")
    return table(list(csv.reader(io.StringIO(text, newline=""), delimiter="\t" if sep == "tab" else sep)))


def keep(name, data):
    if A.out:
        import os
        os.makedirs(A.out, exist_ok=True)
        open(os.path.join(A.out, name), "wb").write(data)


def mine(rows):
    for r in rows:
        if r.get("Número") == A.device_id:
            return r
    return None


def num(v):
    try:
        return float(str(v).replace(",", "."))
    except ValueError:
        return None


# --- the device's own truth -----------------------------------------------------------------------------------------

def dev_battery():
    m = re.search(r"^\s*level:\s*(\d+)", sh("dumpsys battery"), re.M)
    return int(m.group(1)) if m else None


def dev_storage():
    """(free GB, total GB) of /data as the device reports it."""
    for line in sh("df -k /data").splitlines()[1:]:
        p = line.split()
        if len(p) >= 4 and p[1].isdigit():
            return int(p[3]) / 1024 / 1024, int(p[1]) / 1024 / 1024
    return None, None


def summary():
    for s in api("GET", f"{FLEET}/devices/summary"):
        if s.get("number") == A.device_id:
            return s
    return {}


def refresh(wait_for=None, timeout=60):
    timeout *= A.slow
    """'Actualizar ahora': returns the seconds until the device's data was newer than the request (None = never)."""
    r = api("POST", f"{FLEET}/devices/refresh", {"numbers": [A.device_id]})
    t0 = time.time()
    while time.time() - t0 < timeout:
        # The device's own state is a cheap read; the fleet summary (every device) is asked only once it is fresh.
        st = api("GET", f"/rest/private/agent/v1/devices/{urllib.parse.quote(A.device_id)}/state") or {}
        if (st.get("updatedAt") or 0) >= r["at"]:
            if wait_for is None or wait_for(summary()):
                return time.time() - t0, r
            time.sleep(1)
        time.sleep(0.3)
    return None, r


def wait_summary(pred, timeout=120):
    timeout *= A.slow
    t0 = time.time()
    while time.time() - t0 < timeout:
        refresh(timeout=8)
        s = summary()
        if pred(s):
            return s
        time.sleep(2)
    return summary()


# --- checks ---------------------------------------------------------------------------------------------------------

def check_row(label, row, battery, charging, free_gb, total_gb, android, model, network=None):
    if not check(f"{label}: the device has a row", row is not None):
        return
    check(f"{label}: Batería % = device ({battery})", num(row["Batería %"]) == battery, row["Batería %"])
    check(f"{label}: Cargando = {charging}", row["Cargando"] == charging, row["Cargando"])
    f, t, u = num(row["Libre GB"]), num(row["Total GB"]), num(row["% usado"])
    check(f"{label}: Libre GB ≈ device ({free_gb:.2f})", f is not None and abs(f - free_gb) <= 0.15, row["Libre GB"])
    check(f"{label}: Total GB ≈ device ({total_gb:.2f})", t is not None and abs(t - total_gb) <= 0.15, row["Total GB"])
    want = round((total_gb - free_gb) * 100 / total_gb)
    check(f"{label}: % usado ≈ device ({want})", u is not None and abs(u - want) <= 3, row["% usado"])
    check(f"{label}: Android = device ({android})", row["Android"] == android, row["Android"])
    check(f"{label}: Modelo = device ({model})", row["Modelo"] == model, row["Modelo"])
    check(f"{label}: Estado = En línea", row["Estado"] == "En línea", row["Estado"])
    m = num(row["Min. desde el último reporte"])
    check(f"{label}: reported less than 3 min ago", m is not None and m <= 2, row["Min. desde el último reporte"])
    check(f"{label}: Última muestra is filled", row["Última muestra"] != "", row["Última muestra"])
    check(f"{label}: Salud batería is filled", row["Salud batería"] != "", row["Salud batería"])
    check(f"{label}: Agente is filled", row["Agente"] != "", row["Agente"])
    if network:
        check(f"{label}: Red = {network}", row["Red"] == network, row["Red"])


def device_checks():
    android = sh("getprop ro.build.version.release").strip()
    model = sh("getprop ro.product.model").strip()
    print(f"device: {model}, Android {android}, serial {A.serial}")

    # 1. Battery: set it on the device, ask it to report, time the refresh.
    adb("emu", "power", "ac", "off")
    adb("emu", "power", "status", "discharging")
    target = 37 if dev_battery() != 37 else 41
    adb("emu", "power", "capacity", str(target))
    time.sleep(2)
    battery = dev_battery()
    check("device took the new battery level", battery == target, str(battery))
    secs, r = refresh(lambda s: s.get("battery") == battery)
    check("'Actualizar ahora' reaches the device over its live channel", r["live"] == 1 and r["requested"] == 1, json.dumps(r))
    check("'Actualizar ahora': fresh data arrived", secs is not None, "timeout 60 s" if secs is None else f"{secs:.1f} s")
    if secs is not None:
        print(f"INFO  refresh latency (request -> new battery level visible in the console API): {secs:.1f} s")
        check(f"'Actualizar ahora' refreshes in under {A.max_refresh_seconds:g} s", secs < A.max_refresh_seconds, f"{secs:.1f} s")

    # 2. Storage: write a file, the free space must drop in the export.
    free0, _ = dev_storage()
    sh(f"dd if=/dev/zero of=/sdcard/Download/dc-e2e-fill.bin bs=1048576 count={A.fill_mb}", timeout=300)
    sh("sync")
    free1, total = dev_storage()
    check(f"device has ~{A.fill_mb} MB less free space", free0 - free1 > A.fill_mb / 1024 * 0.8, f"{free0:.2f} -> {free1:.2f} GB")
    refresh(lambda s: abs((s.get("freeStorageBytes") or 0) / 2**30 - free1) <= 0.15)

    net = None if A.skip_network else "Wi-Fi"
    if net:
        sh("svc wifi enable")
        s = wait_summary(lambda s: s.get("networkType") == "wifi", 60)
        if s.get("networkType") != "wifi":
            net = None
            print("INFO  this device has no Wi-Fi network: the network switch is skipped")

    battery = dev_battery()
    free1, total = dev_storage()
    sheets, h = download_xlsx()
    check("Excel: has Resumen, Dispositivos, Carpetas, Conexiones",
          list(sheets) == ["Resumen", "Dispositivos", "Carpetas", "Conexiones"], str(list(sheets)))
    check("Excel: file name and type", ".xlsx" in h.get("Content-Disposition", "") and "spreadsheetml" in h.get("Content-Type", ""))
    row = mine(table(sheets["Dispositivos"]))
    check_row("Excel", row, battery, "No", free1, total, android, model, net)
    labels = [r[0] for r in sheets["Resumen"] if r]
    check("Excel: Resumen says 'En línea (últimos 20 min)'", "En línea (últimos 20 min)" in labels and not any("10 min" in x for x in labels))
    check("Excel: Conexiones has the device's open session",
          any(r.get("Número") == A.device_id and r.get("Estado") == "Conectado ahora" for r in table(sheets["Conexiones"])))

    b, h = download_csv(";")
    check("CSV: UTF-8 with BOM", b[:3] == b"\xef\xbb\xbf")
    check("CSV: file name and type", ".csv" in h.get("Content-Disposition", "") and "text/csv" in h.get("Content-Type", ""))
    check_row("CSV", mine(parse_csv(b, ";")), battery, "No", free1, total, android, model, net)
    for sep in (",", "tab", "|"):
        rows = parse_csv(download_csv(sep)[0], sep)
        r = mine(rows)
        check(f"CSV with separator {sep!r}: same columns and values",
              r is not None and len(r) == len(row) and r["Android"] == android and num(r["Batería %"]) == battery)
    b, _ = raw("GET", f"{FLEET}/export.csv?sep=comma&numbers={urllib.parse.quote(A.device_id)}")
    check("CSV by GET (export.csv?sep=comma&numbers=…): one row", len(parse_csv(b, ",")) == 1)

    # filters / selection
    one = table(download_xlsx(numbers=[A.device_id])[0]["Dispositivos"])
    check("export of a selection: only the selected device", len(one) == 1 and one[0]["Número"] == A.device_id, str(len(one)))
    none = table(download_xlsx(numbers=["no-such-device"])[0]["Dispositivos"])
    check("export of a selection without matches: no rows", len(none) == 0, str(len(none)))

    # 3. Charging + low battery -> the inventory report's summary counts it.
    adb("emu", "power", "capacity", "9")
    time.sleep(2)
    refresh(lambda s: s.get("battery") == 9)
    inv = api("GET", f"{REPORTS}/inventory?lowBattery=20")
    r = next((x for x in inv["rows"] if x.get("number") == A.device_id), None)
    check("inventory report: the device is flagged 'Batería baja'", r is not None and r.get("lowbattery") is True and "Batería baja" in r.get("alerts", ""), str(r and r.get("alerts")))
    sm = inv.get("summary") or {}
    check("inventory report: summary counts it (lowBattery ≥ 1, online ≥ 1)", sm.get("lowBattery", 0) >= 1 and sm.get("online", 0) >= 1, json.dumps({k: sm.get(k) for k in ("total", "online", "lowBattery")}))
    check("inventory report: breakdown by Android has the device's version", any(x["label"] == android for x in sm.get("byAndroid", [])))
    check("inventory report: breakdown by model has the device's model", any(model in x["label"] for x in sm.get("byModel", [])))
    b, _ = raw("GET", f"{REPORTS}/inventory/export?format=csv&sep=;")
    check("inventory report: CSV export has the device", mine(parse_csv(b, ";")) is not None)
    b, _ = raw("GET", f"{REPORTS}/inventory/export?format=xlsx")
    check("inventory report: Excel export has the device", mine(table(xlsx_sheets(b)["Dispositivos"])) is not None)
    adb("emu", "power", "ac", "on")
    adb("emu", "power", "status", "charging")
    time.sleep(2)
    refresh(lambda s: s.get("charging") is True)
    r = mine(parse_csv(download_csv(";")[0]))
    check("CSV: Cargando = Sí once the charger is on", r is not None and r["Cargando"] == "Sí", r and r["Cargando"])

    # 4. Network: Wi-Fi off -> mobile data, and back.
    if net:
        sh("svc wifi disable")
        s = wait_summary(lambda s: s.get("networkType") == "cellular", 120)
        if check("device moved to mobile data and reported it", s.get("networkType") == "cellular", str(s.get("networkType"))):
            r = mine(parse_csv(download_csv(";")[0]))
            check("CSV: Red = Datos móviles", r is not None and r["Red"] == "Datos móviles", r and r["Red"])
            check("CSV: SSID/Operador = the carrier", r is not None and r["SSID/Operador"] != "", r and r["SSID/Operador"])
            r = mine(table(download_xlsx()[0]["Dispositivos"]))
            check("Excel: Red = Datos móviles", r is not None and r["Red"] == "Datos móviles", r and r["Red"])
        sh("svc wifi enable")
        s = wait_summary(lambda s: s.get("networkType") == "wifi", 120)
        r = mine(parse_csv(download_csv(";")[0]))
        check("CSV: Red = Wi-Fi again, with its signal", r is not None and r["Red"] == "Wi-Fi" and r["Señal"] != "", r and f'{r["Red"]} / {r["Señal"]}')

    sh("rm -f /sdcard/Download/dc-e2e-fill.bin")
    adb("emu", "power", "capacity", "100")


# --- scheduled e-mail -----------------------------------------------------------------------------------------------

def mp(path, method="GET"):
    req = urllib.request.Request(A.mailpit + path, method=method)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def wait_mail(subject_part, timeout):
    timeout *= A.slow
    t0 = time.time()
    while time.time() - t0 < timeout:
        for m in json.loads(mp("/api/v1/messages?limit=50")).get("messages", []):
            if subject_part in m.get("Subject", ""):
                return json.loads(mp(f"/api/v1/message/{m['ID']}"))
        time.sleep(2)
    return None


def schedule_body(name, fmt, sep=";", hour=7, minute=0):
    return {"name": name, "report": "inventory", "format": fmt, "csvSeparator": sep, "frequency": "daily",
            "runHour": hour, "runMinute": minute, "timezone": "America/Bogota",
            "recipients": "operaciones@example.com, jefe@example.com", "groupId": None, "enabled": True}


def mail_checks():
    mp("/api/v1/messages", "DELETE")
    lst = api("GET", f"{REPORTS}/schedules")
    check("schedules: the server reports SMTP as configured", lst["smtpConfigured"] is True)
    check("schedules: 'inventory' can be scheduled", any(r["id"] == "inventory" for r in lst["reports"]))
    created = []
    try:
        bad, _ = raw("POST", f"{REPORTS}/schedules", dict(schedule_body("x", "csv"), recipients="no-es-correo"))
        check("schedules: an invalid recipient is refused with a message", json.loads(bad).get("status") == "ERROR", json.loads(bad).get("message"))

        for fmt in ("csv", "xlsx"):
            tag = f"e2e-{fmt}-{int(time.time())}"
            s = api("POST", f"{REPORTS}/schedules", schedule_body(tag, fmt))
            created.append(s["id"])
            check(f"schedule ({fmt}): created with a next run", (s.get("nextRunAt") or 0) > time.time() * 1000)
            r = api("POST", f"{REPORTS}/schedules/{s['id']}/send")
            check(f"schedule ({fmt}): 'Enviar ahora' says sent", r["sent"] is True and r["status"] == "ok", r.get("message"))
            m = wait_mail(tag, 30)
            if not check(f"schedule ({fmt}): the e-mail arrived at the test SMTP", m is not None):
                continue
            to = sorted(x["Address"] for x in m.get("To", []))
            check(f"schedule ({fmt}): both recipients", to == ["jefe@example.com", "operaciones@example.com"], str(to))
            check(f"schedule ({fmt}): the body has the summary", "Dispositivos" in m.get("HTML", "") and "En línea" in m.get("HTML", ""))
            atts = m.get("Attachments", [])
            if not check(f"schedule ({fmt}): one attachment named *.{fmt}", len(atts) == 1 and atts[0]["FileName"].endswith("." + fmt), str([a.get("FileName") for a in atts])):
                continue
            data = mp(f"/api/v1/message/{m['ID']}/part/{atts[0]['PartID']}")
            keep("adjunto-" + atts[0]["FileName"], data)
            rows = parse_csv(data, ";") if fmt == "csv" else table(xlsx_sheets(data)["Dispositivos"])
            r = mine(rows)
            check(f"schedule ({fmt}): the attachment opens and has the device with its battery and storage",
                  r is not None and r["Batería %"] != "" and r["Libre GB"] != "" and r["Android"] != "", str(r and {k: r[k] for k in ("Batería %", "Libre GB", "Android", "Estado")}))

        # paused -> resumed
        sid = created[0]
        cur = next(x for x in api("GET", f"{REPORTS}/schedules")["schedules"] if x["id"] == sid)
        p = api("PUT", f"{REPORTS}/schedules/{sid}", dict(cur, enabled=False))
        check("schedule: pausing clears its next run", p["enabled"] is False and p.get("nextRunAt") is None)

        if not A.skip_scheduler_wait:
            # A run fired by the server's own scheduler: a schedule for the next minute (Bogotá time).
            lt = time.gmtime(time.time() - 5 * 3600 + 90)
            tag = f"e2e-cron-{int(time.time())}"
            s = api("POST", f"{REPORTS}/schedules", schedule_body(tag, "csv", ",", lt.tm_hour, lt.tm_min))
            created.append(s["id"])
            wait = s["nextRunAt"] / 1000 - time.time()
            check("scheduler: the next run is within 2 minutes", 0 < wait <= 150, f"{wait:.0f} s")
            m = wait_mail(tag, wait + 100)
            check("scheduler: the server sent the scheduled e-mail on its own", m is not None)
            if m:
                late = time.time() - s["nextRunAt"] / 1000
                print(f"INFO  the scheduled e-mail was seen {late:.0f} s after its slot")
                att = m["Attachments"][0]
                r = mine(parse_csv(mp(f"/api/v1/message/{m['ID']}/part/{att['PartID']}"), ","))
                check("scheduler: its CSV attachment (comma separated) has the device", r is not None)
                time.sleep(3)
                cur = next(x for x in api("GET", f"{REPORTS}/schedules")["schedules"] if x["id"] == s["id"])
                check("scheduler: the schedule records the send and moves to tomorrow",
                      cur.get("lastStatus") == "ok" and cur["nextRunAt"] > s["nextRunAt"] + 23 * 3600 * 1000,
                      f'{cur.get("lastStatus")} next +{(cur["nextRunAt"] - s["nextRunAt"]) / 3600000:.1f} h')
                n = sum(1 for x in json.loads(mp("/api/v1/messages?limit=50"))["messages"] if tag in x["Subject"])
                check("scheduler: sent exactly once", n == 1, str(n))
    finally:
        for sid in created:
            try:
                api("DELETE", f"{REPORTS}/schedules/{sid}")
            except Exception as e:  # noqa
                print("could not delete schedule", sid, e)
    left = [x for x in api("GET", f"{REPORTS}/schedules")["schedules"] if x["name"].startswith("e2e-")]
    check("schedules: the test schedules were deleted", not left)


def no_smtp_checks():
    lst = api("GET", f"{REPORTS}/schedules")
    check("no SMTP: the server says so", lst["smtpConfigured"] is False)
    s = api("POST", f"{REPORTS}/schedules", schedule_body(f"e2e-nosmtp-{int(time.time())}", "csv"))
    try:
        r = api("POST", f"{REPORTS}/schedules/{s['id']}/send")
        check("no SMTP: 'Enviar ahora' answers (no error) with status smtp", r["sent"] is False and r["status"] == "smtp", r.get("message"))
        check("no SMTP: the schedule shows why", (r["schedule"].get("lastError") or "").startswith("El servidor no tiene SMTP"))
    finally:
        api("DELETE", f"{REPORTS}/schedules/{s['id']}")


def offline_checks():
    r = mine(parse_csv(download_csv(";")[0]))
    if check("offline: the device has a row", r is not None):
        check("offline: Estado = Sin conexión", r["Estado"] == "Sin conexión", r["Estado"])
        m = num(r["Min. desde el último reporte"])
        check("offline: more than 20 minutes since its last report", m is not None and m > 20, r["Min. desde el último reporte"])
    r = mine(table(download_xlsx()[0]["Dispositivos"]))
    check("offline: the Excel agrees", r is not None and r["Estado"] == "Sin conexión", r and r["Estado"])


def main():
    login()
    if A.expect_offline:
        offline_checks()
    else:
        if A.serial:
            device_checks()
        if A.expect_no_smtp:
            no_smtp_checks()
        if A.mailpit:
            mail_checks()
    ok = sum(RESULTS)
    print(f"RESULT: PASS={ok} FAIL={len(RESULTS) - ok}")
    sys.exit(0 if ok == len(RESULTS) and RESULTS else 1)


if __name__ == "__main__":
    main()
