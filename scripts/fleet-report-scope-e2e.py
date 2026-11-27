#!/usr/bin/env python3
"""
Folder administrator vs. the fleet inventory, end to end over the real API: a user limited to ONE folder must get
only that folder's devices in the Excel/CSV exports, in the inventory report and in scheduled e-mails, and must not
be able to schedule, see, edit, send or delete anything of other folders.

Needs a dev stack with devices in at least two folders (scripts/dev-synthetic-fleet.py makes them):

  scripts/fleet-report-scope-e2e.py --api http://localhost:8088 --folder "Sint 01" [--mailpit http://localhost:8025]

It creates the user (login given by --login, folder editor role, random password unless --password), runs the checks
and deletes the user and its schedules. --keep-user leaves the user and prints nothing secret: the password is
written to --password-file so a browser test (scripts/fleet-console-e2e.mjs --folder-user) can sign in with it.
"""
import argparse, csv, hashlib, http.cookiejar, io, json, re, secrets, sys, time, urllib.error, urllib.request
import xml.etree.ElementTree as ET
import zipfile

P = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--folder", required=True, help="name of the top-level folder the test user is limited to")
P.add_argument("--login", default="e2e.carpeta")
P.add_argument("--password", default="")
P.add_argument("--password-file", default="", help="write the test user's password here (with --keep-user)")
P.add_argument("--keep-user", action="store_true")
P.add_argument("--remove-user", action="store_true", help="only delete the test user and exit")
P.add_argument("--mailpit", default="")
A = P.parse_args()

FLEET, REPORTS = "/rest/private/fleet/v1", "/rest/private/reports/v1"
NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append(bool(ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else ""), flush=True)
    return bool(ok)


class Session:
    def __init__(self):
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def raw(self, method, path, body=None):
        """(http status, bytes)."""
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(A.api + path, data=data, method=method, headers={"Content-Type": "application/json"})
        try:
            with self.op.open(req, timeout=300) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def json(self, method, path, body=None):
        st, b = self.raw(method, path, body)
        try:
            return json.loads(b or b"{}")
        except ValueError:
            return {"status": f"HTTP {st}"}

    def data(self, method, path, body=None):
        j = self.json(method, path, body)
        if j.get("status") != "OK":
            raise RuntimeError(f"{method} {path}: {str(j)[:300]}")
        return j.get("data")

    def login(self, user, password):
        """Signs in; a first login that must change its password does so (to the same password)."""
        md5 = hashlib.md5(password.encode()).hexdigest().upper()
        j = self.json("POST", "/rest/public/auth/login", {"login": user, "password": md5})
        d = j.get("data") or {}
        if j.get("status") == "OK" and d.get("passwordReset") and d.get("passwordResetToken"):
            self.json("POST", "/rest/public/passwordReset/reset", {"passwordResetToken": d["passwordResetToken"], "newPassword": md5})
            j = self.json("POST", "/rest/public/auth/login", {"login": user, "password": md5})
        return j.get("status") == "OK"


def xlsx_rows(data, sheet):
    z = zipfile.ZipFile(io.BytesIO(data))
    shared = ["".join(t.text or "" for t in si.iter("{%s}t" % NS["m"]))
              for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", NS)] if "xl/sharedStrings.xml" in z.namelist() else []
    rels = {r.get("Id"): r.get("Target") for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
    for s in ET.fromstring(z.read("xl/workbook.xml")).find("m:sheets", NS):
        if s.get("name") != sheet:
            continue
        t = rels[s.get("{%s}id" % NS["r"])]
        rows = []
        for row in ET.fromstring(z.read(t.lstrip("/") if t.startswith("/") else "xl/" + t)).iter("{%s}row" % NS["m"]):
            cells = {}
            for c in row.findall("m:c", NS):
                col = 0
                for ch in re.match(r"[A-Z]+", c.get("r")).group(0):
                    col = col * 26 + ord(ch) - 64
                v = c.find("m:v", NS)
                if c.get("t") == "s" and v is not None:
                    cells[col - 1] = shared[int(v.text)]
                elif c.get("t") == "inlineStr":
                    cells[col - 1] = "".join(x.text or "" for x in c.iter("{%s}t" % NS["m"]))
                else:
                    cells[col - 1] = v.text if v is not None else ""
            r = [""] * (max(cells) + 1 if cells else 0)
            for i, v in cells.items():
                r[i] = v
            rows.append(r)
        head = rows[0] if rows else []
        return [{h: (r[i] if i < len(r) else "") for i, h in enumerate(head)} for r in rows[1:]]
    return []


def csv_rows(b, sep=";"):
    rows = list(csv.reader(io.StringIO(b.decode("utf-8-sig"), newline=""), delimiter=sep))
    return [dict(zip(rows[0], r)) for r in rows[1:]] if rows else []


def numbers(rows):
    return {r.get("Número") for r in rows}


def mp(path, method="GET"):
    with urllib.request.urlopen(urllib.request.Request(A.mailpit + path, method=method), timeout=30) as r:
        return r.read()


def schedule(name, group):
    return {"name": name, "report": "inventory", "format": "csv", "csvSeparator": ";", "frequency": "daily", "runHour": 7,
            "runMinute": 0, "timezone": "America/Bogota", "recipients": "carpeta@example.com", "groupId": group, "enabled": True}


def remove_user(admin):
    for u in admin.data("GET", "/rest/private/dc/users"):
        if u["login"] == A.login:
            admin.json("DELETE", f"/rest/private/dc/users/{u['id']}")
            return True
    return False


def main():
    admin = Session()
    if not admin.login(A.admin_user, A.admin_password):
        sys.exit("admin login failed")
    if A.remove_user:
        print("removed" if remove_user(admin) else "no such user")
        return

    # --- what the administrator sees: the truth to compare with -------------------------------------------------
    groups = admin.data("GET", f"{FLEET}/groups")["groups"]
    own = next((g for g in groups if g["name"] == A.folder and g.get("parentId") is None), None)
    if not own:
        sys.exit(f"folder {A.folder!r} not found")
    branch = {own["id"]}
    grew = True
    while grew:
        grew = False
        for g in groups:
            if g.get("parentId") in branch and g["id"] not in branch:
                branch.add(g["id"])
                grew = True
    foreign = next(g for g in groups if g["id"] not in branch and g.get("parentId") is None and g.get("deviceCount", 0) >= 0)
    inv = admin.data("GET", f"{REPORTS}/inventory")
    all_numbers = {r["number"] for r in inv["rows"]}
    mine = {r["number"] for r in inv["rows"] if r.get("groupid") in branch}
    others = all_numbers - mine
    print(f"fleet: {len(all_numbers)} devices; folder {A.folder!r} and its sub-folders: {len(mine)}; other folders: {len(others)}")
    if not mine or not others:
        sys.exit("the test needs devices inside and outside the folder")
    a_foreign = sorted(others)[0]
    a_mine = sorted(mine)[0]
    admin_sched = admin.data("POST", f"{REPORTS}/schedules", schedule("e2e-scope-admin-todos", None))

    # --- the folder user ------------------------------------------------------------------------------------------
    remove_user(admin)
    password = A.password or secrets.token_urlsafe(12)
    r = admin.json("PUT", "/rest/private/dc/users", {"login": A.login, "name": "E2E Carpeta", "role": "folders",
                                                      "folders": [own["id"]], "password": hashlib.md5(password.encode()).hexdigest().upper()})
    check("administrator creates a user limited to the folder", r.get("status") == "OK", str(r.get("message")))
    if A.password_file:
        open(A.password_file, "w").write(password)
    u = Session()
    check("the folder user signs in", u.login(A.login, password))
    created = []
    try:
        # exports
        st, b = u.raw("POST", f"{FLEET}/export", {"format": "xlsx"})
        x = xlsx_rows(b, "Dispositivos") if st == 200 else []
        check("Excel: exactly the folder's devices", numbers(x) == mine, f"{len(x)} rows, {len(numbers(x) - mine)} foreign")
        check("Excel: Carpetas lists only their folders", st == 200 and all(A.folder in r["Carpeta"] for r in xlsx_rows(b, "Carpetas")) and len(xlsx_rows(b, "Carpetas")) == len(branch))
        conn = numbers(xlsx_rows(b, "Conexiones")) if st == 200 else {None}
        check("Excel: Conexiones has no foreign device", conn <= mine, str(len(conn - mine)))
        st, b = u.raw("POST", f"{FLEET}/export", {"format": "csv"})
        c = csv_rows(b) if st == 200 else []
        check("CSV: exactly the folder's devices", numbers(c) == mine, f"{len(c)} rows")
        st, b = u.raw("GET", f"{FLEET}/export.csv?sep=comma")
        check("CSV by GET: exactly the folder's devices", st == 200 and numbers(csv_rows(b, ",")) == mine)
        st, b = u.raw("POST", f"{FLEET}/export", {"format": "csv", "numbers": [a_foreign, a_mine]})
        check("asking for a foreign device by number returns only their own", st == 200 and numbers(csv_rows(b)) == {a_mine}, str(numbers(csv_rows(b)) if st == 200 else st))
        st, _ = u.raw("POST", f"{FLEET}/export", {"format": "xlsx", "group": foreign["id"]})
        check("exporting a foreign folder is refused (404)", st == 404, str(st))
        st, _ = u.raw("GET", f"{FLEET}/export.xlsx?group={foreign['id']}")
        check("export.xlsx?group=<foreign> is refused (404)", st == 404, str(st))

        # inventory report
        ui = u.data("GET", f"{REPORTS}/inventory")
        check("inventory report: exactly the folder's devices", {r["number"] for r in ui["rows"]} == mine, str(len(ui["rows"])))
        check("inventory report: the summary counts only them", ui["summary"]["total"] == len(mine), str(ui["summary"]["total"]))
        st, b = u.raw("GET", f"{REPORTS}/inventory/export?format=csv")
        check("inventory report CSV: exactly the folder's devices", st == 200 and numbers(csv_rows(b)) == mine)
        st, b = u.raw("GET", f"{REPORTS}/inventory/export?format=xlsx")
        check("inventory report Excel: exactly the folder's devices", st == 200 and numbers(xlsx_rows(b, "Dispositivos")) == mine)
        st, _ = u.raw("GET", f"{REPORTS}/inventory/export?format=csv&group={foreign['id']}")
        check("inventory export of a foreign folder is refused (404)", st == 404, str(st))
        fi = u.json("GET", f"{REPORTS}/inventory?group={foreign['id']}")
        check("inventory report of a foreign folder is empty", fi.get("status") == "OK" and not fi["data"]["rows"], str(len((fi.get("data") or {}).get("rows", []))))
        off = u.data("GET", f"{REPORTS}/offline?days=1")
        check("another report (offline) has no foreign device", {r["number"] for r in off["rows"]} <= mine)

        # refresh
        r = u.json("POST", f"{FLEET}/devices/refresh", {"numbers": [a_foreign]})
        check("'Actualizar ahora' on a foreign device reaches nothing", r.get("status") == "OK" and r["data"]["requested"] == 0, str(r)[:120])
        r = u.json("POST", f"{FLEET}/devices/refresh", {"numbers": [a_mine]})
        check("'Actualizar ahora' on their own device is accepted", r.get("status") == "OK" and r["data"]["requested"] == 1, str(r)[:120])

        # schedules
        lst = u.data("GET", f"{REPORTS}/schedules")
        check("schedules: the administrator's fleet-wide schedule is not listed", all(s["id"] != admin_sched["id"] for s in lst["schedules"]))
        r = u.json("POST", f"{REPORTS}/schedules", schedule("e2e-scope-todos", None))
        check("schedules: cannot schedule 'all devices'", r.get("status") == "ERROR", r.get("message"))
        r = u.json("POST", f"{REPORTS}/schedules", schedule("e2e-scope-ajena", foreign["id"]))
        check("schedules: cannot schedule a foreign folder", r.get("status") == "ERROR", r.get("message"))
        r = u.json("POST", f"{REPORTS}/schedules", schedule("e2e-scope-propia", own["id"]))
        if check("schedules: can schedule their own folder", r.get("status") == "OK", r.get("message")):
            sid = r["data"]["id"]
            created.append(sid)
            r = u.json("PUT", f"{REPORTS}/schedules/{sid}", schedule("e2e-scope-propia", foreign["id"]))
            check("schedules: cannot move it to a foreign folder", r.get("status") == "ERROR", r.get("message"))
            if A.mailpit:
                mp("/api/v1/messages", "DELETE")
                r = u.json("POST", f"{REPORTS}/schedules/{sid}/send")
                check("schedules: 'Enviar ahora' of their own schedule is sent", r.get("status") == "OK" and r["data"]["sent"], str((r.get("data") or {}).get("message")))
                m = None
                for _ in range(15):
                    msgs = json.loads(mp("/api/v1/messages?limit=10")).get("messages", [])
                    m = next((x for x in msgs if "e2e-scope-propia" in x["Subject"]), None)
                    if m:
                        break
                    time.sleep(2)
                if check("schedules: the e-mail arrived", m is not None):
                    full = json.loads(mp(f"/api/v1/message/{m['ID']}"))
                    att = csv_rows(mp(f"/api/v1/message/{m['ID']}/part/{full['Attachments'][0]['PartID']}"))
                    check("schedules: the attachment has exactly the folder's devices", numbers(att) == mine, f"{len(att)} rows")
                    check("schedules: the e-mail's summary counts only them", f"<b>{len(mine)}</b>" in full.get("HTML", ""))
        for verb, path, body in (("PUT", f"/{admin_sched['id']}", schedule("hackeado", own["id"])),
                                 ("POST", f"/{admin_sched['id']}/send", None), ("DELETE", f"/{admin_sched['id']}", None)):
            r = u.json(verb, f"{REPORTS}/schedules{path}", body)
            check(f"schedules: {verb} on the administrator's schedule is refused", r.get("status") == "ERROR", r.get("message"))
        still = next((s for s in admin.data("GET", f"{REPORTS}/schedules")["schedules"] if s["id"] == admin_sched["id"]), None)
        check("schedules: the administrator's schedule is untouched", still is not None and still["name"] == "e2e-scope-admin-todos" and still.get("lastRunAt") is None)
        both = admin.data("GET", f"{REPORTS}/schedules")["schedules"]
        check("schedules: the administrator sees both", {admin_sched["id"], *created} <= {s["id"] for s in both})
    finally:
        for sid in created + [admin_sched["id"]]:
            admin.json("DELETE", f"{REPORTS}/schedules/{sid}")
        if not A.keep_user:
            remove_user(admin)
    ok = sum(RESULTS)
    print(f"RESULT: PASS={ok} FAIL={len(RESULTS) - ok}")
    sys.exit(0 if ok == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
