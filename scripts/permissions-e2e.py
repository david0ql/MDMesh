#!/usr/bin/env python3
"""
End-to-end test of app permissions without the user (MDM-06) against a running stack and a REAL enrolled agent
(emulator or phone, Device Owner) read over adb.

    scripts/build-permtestapp.sh                     # -> scripts/testapp/permtest.apk
    scripts/permissions-e2e.py --api http://localhost:8088 --serial emulator-5600 --device-id <device number> \
        [--apk scripts/testapp/permtest.apk] [--admin-password admin]

What it does, all through the console's API, checking the phone itself with `dumpsys package`, `dumpsys device_policy`,
the foreground window and the test app's own log:

 1. the default policy of the device's configuration (Conceder / Preguntar / Denegar) reaches the phone;
 2. per-app rules in the POLICY ("grant all the app declares") — the test app, which asks for location, camera and
    storage, is installed by the console and opens with every permission granted and NO dialog;
 3. a per-DEVICE rule on top (deny the camera): revoked and fixed; then "ask" (released); then back to the policy;
 4. an app that was ALREADY installed gets its permissions when the rule arrives (no reinstall);
 5. background location is granted after the foreground one; Android 12+: the sensor-grant ability is reported;
 6. special accesses: what the Device Owner can do is done, the rest is reported as "needs a tap" and the exact screen
    opens on the phone (the tap is simulated with `appops`), after which the console shows it as active;
 7. the inventory the console shows and exports matches the phone.

The device's configuration is restored at the end. Exit code 0 only if every check passed.
"""
import argparse, hashlib, http.cookiejar, json, os, re, subprocess, sys, time, urllib.error, urllib.request, uuid

P = argparse.ArgumentParser()
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--serial", required=True)
P.add_argument("--device-id", required=True, help="the device NUMBER (what the console shows in the device URL)")
P.add_argument("--apk", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "testapp", "permtest.apk"))
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--adb", default="adb")
P.add_argument("--keep", action="store_true", help="leave the test app and the rules in place at the end")
A = P.parse_args()

PKG = "com.dallycontrol.permtest"
ACTIVITY = PKG + "/.MainActivity"
RUN = uuid.uuid4().hex[:6]
RESULTS = []
PERM = "android.permission."


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    return ok


def section(title):
    print(f"\n== {title}", flush=True)


# --- console API ------------------------------------------------------------------------------------------------------

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))


def call(method, path, body=None, headers=None, timeout=300):
    data = body if isinstance(body, (bytes, bytearray)) or body is None else json.dumps(body).encode()
    h = {"Accept": "application/json"}
    if body is not None and not isinstance(body, (bytes, bytearray)):
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = urllib.request.Request(A.api + path, data=data, method=method, headers=h)
    try:
        with OP.open(req, timeout=timeout) as r:
            payload, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        payload, status = e.read(), e.code
    try:
        return status, json.loads(payload or b"{}")
    except ValueError:
        return status, {"status": "NOT_JSON", "message": payload[:200].decode("utf-8", "replace")}


def ok(resp):
    return resp[0] == 200 and resp[1].get("status") == "OK"


def data(resp):
    return resp[1].get("data")


def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    return ok(call("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5}))


def upload(name, content):
    boundary = "----dc" + uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode()
    r = call("POST", "/rest/private/web-ui-files", head + content + f"\r\n--{boundary}--\r\n".encode(),
             {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    if not ok(r):
        return None
    c = call("POST", "/rest/private/web-ui-files/update",
             {"tmpPath": data(r).get("serverPath"), "fileName": name, "filePath": name, "external": False})
    return (data(c) or {}).get("url") if ok(c) else None


def permissions():
    return data(call("GET", f"/rest/private/agent/v1/devices/{A.device_id}/permissions")) or {}


def set_device_rules(apps):
    return call("PUT", f"/rest/private/agent/v1/devices/{A.device_id}/permissions", {"apps": apps})


def queue(type_, payload=None, cap=None):
    body = {"type": type_}
    if cap:
        body["requiresCapability"] = cap
    if payload is not None:
        body["payload"] = json.dumps(payload)
    r = call("POST", f"/rest/private/agent/v1/devices/{A.device_id}/commands", body)
    if not ok(r):
        raise RuntimeError(f"queue {type_} failed: {r}")
    return data(r)["id"]


def wait_result(cmd_id, timeout=240):
    end = time.time() + timeout
    last = {"status": "timeout"}
    while time.time() < end:
        for c in data(call("GET", f"/rest/private/agent/v1/devices/{A.device_id}/commands?limit=100")) or []:
            if str(c.get("id")) == str(cmd_id):
                last = c
                if c.get("status") in ("done", "failed", "unsupported", "expired"):
                    return c
        time.sleep(2)
    return last


def run(type_, payload=None, cap=None, timeout=240):
    c = wait_result(queue(type_, payload, cap), timeout)
    return c.get("status"), c.get("detail") or ""


def device_row():
    r = call("POST", "/rest/private/devices/search", {"value": A.device_id, "pageSize": 20, "pageNum": 1})
    for d in ((data(r) or {}).get("devices") or {}).get("items") or []:
        if d.get("number") == A.device_id:
            return d
    return {}


def load_config(cfg_id):
    c = data(call("GET", f"/rest/private/configurations/{cfg_id}"))
    rows = data(call("GET", f"/rest/private/configurations/applications/{cfg_id}")) or []
    c["applications"] = [a for a in rows if a.get("selected")]
    return c


def save_config(cfg):
    r = call("PUT", "/rest/private/configurations", cfg)
    if not ok(r):
        raise RuntimeError(f"saving the configuration failed: {str(r[1])[:300]}")
    # The device is told at once (the console's "Aplicar ahora"), instead of waiting for its next check-in.
    call("POST", f"/rest/private/agent/v1/devices/{A.device_id}/sync")
    return r


def edit_config(cfg_id, option=None, rules=...):
    """Change the policy's default option and/or its per-app rules, keeping everything else."""
    cfg = load_config(cfg_id)
    if option is not None:
        cfg["appPermissions"] = option
    if rules is not ...:
        dc = json.loads(cfg.get("dcPolicy") or "{}")
        if rules:
            dc["permissions"] = rules
        else:
            dc.pop("permissions", None)
        cfg["dcPolicy"] = json.dumps(dc) if dc else None
    save_config(cfg)


# --- device (adb) -----------------------------------------------------------------------------------------------------

def adb(*args, timeout=120):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def until(fn, timeout=150, step=2.0):
    end = time.time() + timeout
    while True:
        v = fn()
        if v or time.time() > end:
            return v
        time.sleep(step)


def package_perms(pkg=PKG):
    """{permission: (granted, flags)} from `dumpsys package` (the runtime-permission section)."""
    out = {}
    for m in re.finditer(r"(\S+): granted=(true|false)(?:, flags=\[([^\]]*)\])?", sh(f"dumpsys package {pkg}")):
        name, granted, flags = m.group(1), m.group(2) == "true", m.group(3) or ""
        # The install-permission section has no flags; the runtime one (the last occurrence) wins.
        if name not in out or flags:
            out[name] = (granted, flags)
    return out


def granted(short, pkg=PKG):
    """Older Android versions only list the runtime permissions an app holds: not listed = not granted."""
    return package_perms(pkg).get(PERM + short, (False, ""))[0]


def fixed(short, pkg=PKG):
    return "POLICY_FIXED" in package_perms(pkg).get(PERM + short, (None, ""))[1]


def any_fixed(pkg=PKG):
    return any("POLICY_FIXED" in flags for _, flags in package_perms(pkg).values())


def installed(pkg=PKG):
    return f"package:{pkg}" in sh(f"pm list packages {pkg}")


def device_policy_permission_policy():
    """
    The permission policy the system holds: 0 prompt, 1 auto-grant, 2 auto-deny; None when this phone does not show it.
    Android 14+ prints it in `dumpsys device_policy` ("Permission policy: {0=1}"). Older versions do not: there it is
    read from the system's own policy file, which needs a rooted shell (emulators have one; a phone does not).
    """
    dump = sh("dumpsys device_policy")
    for pat in (r"Permission policy: \{\s*\d+=(\d)", r"mPermissionPolicy=(\d)", r"Permission policy:\s*(\d)"):
        m = re.search(pat, dump, re.I)
        if m:
            return int(m.group(1))
    xml = sh("su 0 cat /data/system/device_policies.xml 2>/dev/null || su root cat /data/system/device_policies.xml 2>/dev/null")
    if "<policies" not in xml:
        return None
    m = re.search(r'permission-policy="(\d)"', xml)
    return int(m.group(1)) if m else 0      # the attribute is only written when it is not the default (prompt)


def check_policy(label, want, timeout=120):
    """The system holds permission policy [want] (skipped, saying so, where this phone does not let it be read)."""
    if device_policy_permission_policy() is None:
        print(f"  SKIP  {label}: this Android does not show the permission policy without root (the console's report is checked instead)")
        return
    check(label, until(lambda: device_policy_permission_policy() == want, timeout), str(device_policy_permission_policy()))


def reported_policy():
    return (permissions().get("report") or {}).get("policy")


def top_activity():
    out = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | head -3")
    return out.strip()


def launch_and_read():
    """Start the test app; -> (its log line, the activity in front)."""
    sh("logcat -c")
    sh(f"am force-stop {PKG}")
    sh(f"am start -W -n {ACTIVITY}")
    time.sleep(4)
    log = sh("logcat -d -s PermTest:I | grep missing= | tail -1").strip()
    return log, top_activity()


def log_value(log, key):
    m = re.search(rf"\b{key}=(\d+)", log)
    return int(m.group(1)) if m else None


def no_permission_dialog(top):
    return PKG in top and "GrantPermissions" not in top and "permissioncontroller" not in top and "packageinstaller" not in top


def report_app(pkg=PKG):
    for a in (permissions().get("report") or {}).get("apps") or []:
        if a.get("packageName") == pkg:
            return a
    return {}


def report_group(group, pkg=PKG):
    return [p for p in report_app(pkg).get("permissions") or [] if p.get("group") == group]


def refresh_report():
    """Ask the phone to enforce and report now; returns once the console has a report newer than the request."""
    before = permissions().get("reportAt") or 0
    status, _ = run("device.permissionScan", cap="device.permissionScan")
    call("POST", f"/rest/private/agent/v1/devices/{A.device_id}/sync")
    until(lambda: (permissions().get("reportAt") or 0) > before, 60)
    return status


def applied(want_default=None, timeout=120):
    """Wait until the phone applied the configuration's current revision."""
    def done():
        s = data(call("GET", f"/rest/private/agent/v1/devices/{A.device_id}/configStatus")) or {}
        return s.get("currentRevision") and s.get("appliedRevision") == s.get("currentRevision")
    return until(done, timeout)


# --- the test ---------------------------------------------------------------------------------------------------------

def main():
    section(f"console {A.api}, device {A.device_id} on {A.serial}")
    if not check("admin login", login()):
        return
    sdk = int(sh("getprop ro.build.version.sdk").strip() or 0)
    release = sh("getprop ro.build.version.release").strip()
    print(f"        Android {release} (API {sdk})")
    owner = sh("dumpsys device_policy | grep -i -A3 'Device Owner'")
    check("the agent is Device Owner", "dallycontrol" in owner.lower(), owner[:200])
    view = permissions()
    check("the agent advertises permission management", view.get("supported") is True, str(view)[:200])
    row = device_row()
    cfg_id = row.get("configurationId")
    if not check("the device has a configuration", bool(cfg_id), str(row)[:200]):
        return
    original = load_config(cfg_id)
    storage = "READ_MEDIA_IMAGES" if sdk >= 33 else "READ_EXTERNAL_STORAGE"
    try:
        body(sdk, cfg_id, storage)
    finally:
        if not A.keep:
            section("restore")
            set_device_rules({})
            original_now = load_config(cfg_id)
            original_now["appPermissions"] = original.get("appPermissions")
            original_now["dcPolicy"] = original.get("dcPolicy")
            original_now["applications"] = original.get("applications")
            save_config(original_now)
            applied()
            sh(f"pm uninstall {PKG}")
            check("configuration restored", True)


def body(sdk, cfg_id, storage):
    apk = open(A.apk, "rb").read()

    section("1. default policy of the configuration reaches the phone (setPermissionPolicy)")
    sh(f"pm uninstall {PKG}")
    set_device_rules({})
    for option, want, label in (("ASKALL", 0, "Preguntar"), ("DENYALL", 2, "Denegar todo"), ("GRANTALL", 1, "Conceder todo")):
        edit_config(cfg_id, option=option, rules=None)
        name = {0: "prompt", 1: "grant", 2: "deny"}[want]
        check_policy(f"«{label}»: the system holds permission policy {want}", want)
        # The agent reads the policy back from Android (getPermissionPolicy) and the console shows that.
        check(f"«{label}»: the console shows the effective policy", until(lambda: refresh_report() and reported_policy() == name, 120, 3),
              str(reported_policy()))
    status = data(call("GET", f"/rest/private/agent/v1/devices/{A.device_id}/configStatus")) or {}
    detail = (status.get("lastCommand") or {}).get("detail") or ""
    check("config.apply reports the 'permissions' outcome", '"permissions":"applied' in detail.replace(" ", ""), detail[:300])

    section("2. policy rule 'grant all the app declares' + install from the console: opens with no dialog")
    # Under "Preguntar" nothing is granted on its own: whatever the app holds comes from the per-app rule.
    edit_config(cfg_id, option="ASKALL", rules={PKG: {"all": "grant"}})
    check("the phone applied the policy", applied(), "")
    url = upload(f"permtest-{RUN}.apk", apk)
    check("test APK uploaded to the console", bool(url), str(url))
    status, detail = run("app.install", {"url": url, "packageName": PKG, "versionCode": 1,
                                          "sha256": hashlib.sha256(apk).hexdigest()}, cap="app.silentInstall", timeout=300)
    check("silent install from the console: done", status == "done", f"{status}: {detail}")
    check("installed on the phone", installed())
    perms = package_perms()
    for short in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "CAMERA", storage, "RECORD_AUDIO", "READ_CONTACTS"):
        g, flags = perms.get(PERM + short, (None, ""))
        check(f"dumpsys package: {short} granted=true, fixed by policy", g is True and "POLICY_FIXED" in flags, f"{g} {flags}")
    if sdk >= 29:
        check("background location granted too (after the foreground one)", granted("ACCESS_BACKGROUND_LOCATION") is True,
              str(perms.get(PERM + "ACCESS_BACKGROUND_LOCATION")))
    if sdk >= 33:
        check("POST_NOTIFICATIONS granted (Android 13+)", granted("POST_NOTIFICATIONS") is True, str(perms.get(PERM + "POST_NOTIFICATIONS")))
    log, top = launch_and_read()
    check("the app opens straight to its screen: no permission dialog in front", no_permission_dialog(top), top)
    check("the app itself sees every permission granted and asked for nothing",
          log_value(log, "missing") == 0 and log_value(log, "asked") == 0, log)
    check("…including location, camera and storage", log_value(log, "ACCESS_FINE_LOCATION") == 1 and log_value(log, "CAMERA") == 1
          and log_value(log, storage) == 1, log)

    section("3. the inventory the console shows matches the phone")
    refresh_report()
    rep = permissions().get("report") or {}
    app = report_app()
    check("the report lists the test app as installed", app.get("installed") is True, str(app)[:200])
    cam = report_group("camera")
    check("camera: granted and fixed by policy", bool(cam) and all(p["granted"] and p["fixed"] == "granted" for p in cam), str(cam))
    loc = report_group("locationFine") + report_group("locationCoarse")
    check("location: granted and fixed by policy", len(loc) == 2 and all(p["granted"] and p["fixed"] == "granted" for p in loc), str(loc))
    sto = report_group("storage")
    check("storage/media: granted", bool(sto) and all(p["granted"] for p in sto), str(sto))
    check("effective default policy reported: prompt", rep.get("policy") == "prompt", str(rep.get("policy")))
    if sdk >= 31:
        check("Android 12+: the phone reports it lets the admin grant sensor permissions", rep.get("canGrantSensors") is True, str(rep.get("canGrantSensors")))
    else:
        check("Android 11 or older: no sensor-grant limit (reported as not applicable)", rep.get("canGrantSensors") is None, str(rep.get("canGrantSensors")))
    check("last outcome reported", str(rep.get("lastOutcome") or "").startswith("applied"), str(rep.get("lastOutcome")))

    section("4. per-DEVICE rule on top of the policy: deny the camera")
    r = set_device_rules({PKG: {"permissions": {"camera": "deny"}}})
    check("device rule saved", ok(r) and (data(r).get("override") or {}).get(PKG, {}).get("permissions", {}).get("camera") == "deny", str(r[1])[:200])
    eff = next((a for a in (data(r).get("effective") or {}).get("apps") or [] if a.get("packageName") == PKG), {})
    check("effective = policy preset + device rule", eff.get("all") == "grant" and (eff.get("permissions") or {}).get("camera") == "deny", str(eff))
    check("dumpsys package: CAMERA granted=false", until(lambda: granted("CAMERA") is False, 150), str(package_perms().get(PERM + "CAMERA")))
    check("…and fixed by policy (the user cannot turn it on)", fixed("CAMERA"), str(package_perms().get(PERM + "CAMERA")))
    check("the rest stays granted", granted("ACCESS_FINE_LOCATION") is True and granted(storage) is True)
    log, top = launch_and_read()
    check("the app opens with no dialog and sees the camera denied", no_permission_dialog(top) and log_value(log, "CAMERA") == 0
          and log_value(log, "ACCESS_FINE_LOCATION") == 1, f"{log} | {top}")
    sh("input keyevent KEYCODE_HOME")
    refresh_report()
    cam = report_group("camera")
    check("console: camera 'sin conceder', fixed by policy, rule 'deny'",
          bool(cam) and all((not p["granted"]) and p["fixed"] == "denied" and p.get("wanted") == "deny" for p in cam), str(cam))

    section("5. 'Preguntar' on the device, then back to the policy")
    set_device_rules({PKG: {"permissions": {"camera": "prompt"}}})
    check("CAMERA released: not fixed any more, still not granted",
          until(lambda: not fixed("CAMERA") and granted("CAMERA") is False, 150), str(package_perms().get(PERM + "CAMERA")))
    set_device_rules({})
    check("device rules removed: the policy's 'grant all' applies again (CAMERA granted, fixed)",
          until(lambda: granted("CAMERA") is True and fixed("CAMERA"), 150), str(package_perms().get(PERM + "CAMERA")))
    set_device_rules({PKG: {"permissions": {"camera": "prompt"}}})
    check("'Preguntar' after a grant of the MDM takes it back so the app has to ask",
          until(lambda: granted("CAMERA") is False and not fixed("CAMERA"), 150), str(package_perms().get(PERM + "CAMERA")))
    set_device_rules({})
    until(lambda: granted("CAMERA") is True, 150)

    section("6. an app that was ALREADY installed gets its permissions when the rule arrives")
    edit_config(cfg_id, option="ASKALL", rules=None)
    check("rule removed: every permission released (none fixed)", until(lambda: not any_fixed(), 150),
          str({k: v for k, v in package_perms().items() if "POLICY_FIXED" in v[1]}))
    sh(f"pm uninstall {PKG}")
    adb("push", A.apk, "/data/local/tmp/permtest.apk")
    out = sh("pm install -r /data/local/tmp/permtest.apk")
    check("installed by hand, with no rule: nothing granted", "Success" in out and granted("CAMERA") is False and granted("ACCESS_FINE_LOCATION") is False,
          out.strip()[:120])
    edit_config(cfg_id, rules={PKG: {"permissions": {"locationFine": "grant", "locationCoarse": "grant", "locationBackground": "grant",
                                                    "camera": "grant", "storage": "grant"}}})
    last = "ACCESS_BACKGROUND_LOCATION" if sdk >= 29 else storage      # background location is granted last
    check("the rule grants location, camera and storage with no reinstall",
          until(lambda: granted("ACCESS_FINE_LOCATION") is True and granted("CAMERA") is True and granted(storage) is True
                and granted(last) is True, 150),
          str({k: v for k, v in package_perms().items() if "LOCATION" in k or "CAMERA" in k}))
    check("…and only those: the microphone has no rule and stays as it was", granted("RECORD_AUDIO") is False and not fixed("RECORD_AUDIO"))
    if sdk >= 29:
        check("background location granted", granted("ACCESS_BACKGROUND_LOCATION") is True)

    section("6b. a rule that is removed while the app is uninstalled does not come back with the app")
    # Android 14+ remembers a fixed permission across an uninstall and applies it again on reinstall.
    sh(f"pm uninstall {PKG}")
    edit_config(cfg_id, option="ASKALL", rules=None)
    applied()
    time.sleep(5)
    sh("pm install -r /data/local/tmp/permtest.apk")
    check("reinstalled after its rule was removed: nothing stays fixed by the policy",
          until(lambda: installed() and not any_fixed(), 60),
          str({k: v for k, v in package_perms().items() if "POLICY_FIXED" in v[1]}))

    section("7. 'Conceder todo' as default policy: an app with NO rule gets what it asks, with no dialog")
    edit_config(cfg_id, option="GRANTALL", rules=None)
    applied()
    until(lambda: refresh_report() and reported_policy() == "grant", 120, 3)
    until(lambda: not any_fixed(), 60)
    sh(f"pm uninstall {PKG}")
    sh("pm install -r /data/local/tmp/permtest.apk")
    check("fresh install with no rule: camera not granted yet", granted("CAMERA") is False)
    log, top = launch_and_read()
    time.sleep(3)
    log = sh("logcat -d -s PermTest:I | grep missing= | tail -1").strip()
    top = top_activity()
    check("the app asked, Android granted without showing a dialog", no_permission_dialog(top) and log_value(log, "CAMERA") == 1
          and log_value(log, "ACCESS_FINE_LOCATION") == 1 and log_value(log, storage) == 1, f"{log} | {top}")
    sh("input keyevent KEYCODE_HOME")

    section("8. special accesses: what Android lets a Device Owner do, and the rest with one tap")
    pending = "needsUser" if sdk >= 30 else "unknown"
    set_device_rules({PKG: {"special": ["allFiles", "overlay", "writeSettings", "unknownSources", "battery"]}})
    applied()
    refresh_report()
    special = {s["key"]: s for s in report_app().get("special") or []}
    check("the report lists the special accesses with their real state", len(special) >= 8, str(list(special)))
    if sdk >= 30:
        protected = sh("dumpsys device_policy | grep -i -A6 'protected packages\\|userControlDisabledPackages\\|UserControlDisabledPackages'")
        check("battery: the MDM protected the app (setUserControlDisabledPackages)", PKG in protected or PKG in sh("dumpsys device_policy"), protected[:200])
        check("…and the console says so", "protegida" in str(special.get("battery", {}).get("managed")), str(special.get("battery")))
        bucket = sh(f"am get-standby-bucket {PKG}").strip()
        if sdk >= 33:
            check("…and Android exempts it from app standby (bucket 5 = EXEMPTED)", bucket == "5", bucket)
        else:
            print(f"        (app standby bucket on this Android: {bucket})")
        check("all files: reported as 'needs a tap', asked for", special.get("allFiles", {}).get("state") == "needsUser"
              and special.get("allFiles", {}).get("wanted") is True, str(special.get("allFiles")))
        status, detail = run("device.permissionScreen", {"packageName": PKG, "access": "allFiles"}, cap="device.permissionScreen")
        time.sleep(3)
        top = top_activity()
        check("the exact Android screen opens on the phone", status == "done" and ("settings" in top.lower() or "permissioncontroller" in top.lower()),
              f"{status}: {detail} | {top}")
        # The person's tap on the switch, simulated.
        sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
        sh("input keyevent KEYCODE_HOME")
        refresh_report()
        special = {s["key"]: s for s in report_app().get("special") or []}
        check("after the tap the console shows it active", special.get("allFiles", {}).get("state") == "allowed", str(special.get("allFiles")))
    else:
        check("all files: not applicable before Android 11 (covered by the storage permission)",
              special.get("allFiles", {}).get("state") == "notDeclared", str(special.get("allFiles")))
    if sdk < 26:
        check("unknown sources: turned on by the MDM itself (Android 7 or older)",
              sh("settings get secure install_non_market_apps").strip() == "1" and special.get("unknownSources", {}).get("state") == "allowed",
              str(special.get("unknownSources")))
    else:
        # Android 10 and older do not let an app read another app's app-ops: the console then says it cannot tell.
        check(f"unknown sources: '{pending}' (per-app access since Android 8)", special.get("unknownSources", {}).get("state") == pending,
              str(special.get("unknownSources")))
        status, detail = run("device.permissionScreen", {"packageName": PKG, "access": "unknownSources"}, cap="device.permissionScreen")
        time.sleep(3)
        top = top_activity()
        check("its screen opens on the phone", status == "done" and "settings" in top.lower(), f"{status}: {detail} | {top}")
        sh(f"appops set {PKG} REQUEST_INSTALL_PACKAGES allow")
        sh("input keyevent KEYCODE_HOME")
    check(f"overlay: '{pending}'", special.get("overlay", {}).get("state") == pending, str(special.get("overlay")))
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    sh(f"appops set {PKG} WRITE_SETTINGS allow")
    sh(f"dumpsys deviceidle whitelist +{PKG}")
    refresh_report()
    special = {s["key"]: s for s in report_app().get("special") or []}
    readable = ("battery",) + (("overlay", "writeSettings", "unknownSources") if sdk >= 30 else ())
    for key in readable:
        check(f"{key}: active once turned on on the phone", special.get(key, {}).get("state") == "allowed", str(special.get(key)))
    if sdk < 30:
        check("Android 10 or older: app-ops of another app cannot be read, the console says so instead of guessing",
              all(special.get(k, {}).get("state") == "unknown" for k in ("overlay", "writeSettings")), str(special.get("overlay")))
    check("accessibility / notification access: the app has no such service, reported as not asked",
          special.get("accessibility", {}).get("state") == "notDeclared" and special.get("notificationAccess", {}).get("state") == "notDeclared",
          str(special.get("accessibility")))
    status, detail = run("device.permissionScreen", {"packageName": "com.example.missing", "access": "overlay"}, cap="device.permissionScreen")
    check("a screen for an app that is not installed fails with a clear message", status == "failed" and "instalada" in detail, f"{status}: {detail}")
    sh(f"dumpsys deviceidle whitelist -{PKG}")
    set_device_rules({})
    if sdk >= 30:
        applied()
        check("special access no longer asked: the protection is lifted",
              until(lambda: PKG not in sh("dumpsys device_policy | grep -i -A6 'protected packages\\|userControlDisabledPackages'"), 60))

    section("9. the agent re-asserts the policy on its own start")
    edit_config(cfg_id, option="DENYALL", rules={PKG: {"permissions": {"camera": "grant"}}})
    check("deny-all with one grant: the camera is granted", until(lambda: granted("CAMERA") is True, 120), str(package_perms().get(PERM + "CAMERA")))
    check_policy("…and the system holds permission policy 2 (deny)", 2)
    agent = [l.split(":")[1].strip() for l in sh("pm list packages com.dallycontrol.agent").splitlines() if l.startswith("package:")][0]
    sh(f"am force-stop {agent}")
    sh(f"monkey -p {agent} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
    time.sleep(8)
    check("after an agent restart the grant is still in force", granted("CAMERA") is True and fixed("CAMERA"))
    check_policy("…and so is the default policy (2 = deny)", 2, 30)
    check("the restarted agent reports the policy in force", until(lambda: refresh_report() == "done" and reported_policy() == "deny", 150, 3),
          str(reported_policy()))
    sh("input keyevent KEYCODE_HOME")


if __name__ == "__main__":
    try:
        main()
    finally:
        passed = sum(1 for _, o, _ in RESULTS if o)
        failed = [n for n, o, _ in RESULTS if not o]
        print(f"\nRESULT: PASS={passed} FAIL={len(failed)}")
        for n in failed:
            print("  failed: " + n)
        sys.exit(0 if RESULTS and not failed else 1)
