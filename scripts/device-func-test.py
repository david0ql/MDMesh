#!/usr/bin/env python3
"""
Functional test of a REAL enrolled agent: queues every console command through the admin API and
verifies both the command result the agent reports AND the effect on the device (read over adb).

    scripts/device-func-test.py --api http://localhost:8088 --serial emulator-5580 --device-id <id>
        [--admin-password admin] [--pkg com.mdmesh.agent.debug] [--only kiosk,lock] [--destructive]

--destructive also runs reboot and wipe (wipe is last; it factory-resets the device).
Exit code 0 only if every executed check passed.
"""
import argparse, hashlib, http.cookiejar, json, subprocess, sys, time, urllib.request

P = argparse.ArgumentParser()
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--serial", required=True)
P.add_argument("--device-id", required=True)
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--pkg", default="com.mdmesh.agent.debug")
P.add_argument("--only", default="")
P.add_argument("--destructive", action="store_true")
P.add_argument("--adb", default="adb")
P.add_argument("--pg-container", default="mdmesh-dev-postgres-1")
P.add_argument("--self-update-apk", default="", help="agent APK with a higher versionCode: tests the agent updating itself")
P.add_argument("--self-update-code", type=int, default=0)
A = P.parse_args()

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
RESULTS = []


def api(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(A.api + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with OP.open(req, timeout=30) as r:
        return json.loads(r.read() or b"{}")


def adb(*args, timeout=60):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def sdk():
    return int(sh("getprop ro.build.version.sdk").strip())


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    return ok


def queue(type_, payload=None, cap=None):
    body = {"type": type_}
    if cap:
        body["requiresCapability"] = cap
    if payload is not None:
        body["payload"] = json.dumps(payload)
    r = api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/commands", body)
    if r.get("status") != "OK":
        raise RuntimeError(f"queue {type_} failed: {r}")
    return r["data"]["id"]


def wait_result(cmd_id, timeout=90):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        r = api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/commands?limit=100")
        for c in r.get("data") or []:
            if str(c.get("id")) == str(cmd_id):
                last = c
                if c.get("status") in ("done", "failed", "unsupported", "expired"):
                    return c
        time.sleep(1.5)
    return last or {"status": "timeout"}


def run(type_, payload=None, cap=None, timeout=90):
    c = wait_result(queue(type_, payload, cap), timeout)
    return c.get("status"), c.get("detail")


def until(fn, timeout=30, step=1.0):
    end = time.time() + timeout
    v = None
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return v


def dpm_dump():
    return sh("dumpsys device_policy")


def camera_disabled():
    d = dpm_dump()
    # NOTE: the "disable-camera" line in the admin's uses-policies list is only a declaration.
    if "disableCamera=true" in d.replace(" ", ""):
        return True
    # API 34+: a Device Owner's setCameraDisabled becomes the no_camera user restriction. (API 35 lists a
    # "userRestriction_no_camera" policy key even when it is null, so that line alone proves nothing.)
    return any(l.strip() == "no_camera" for l in sh("dumpsys user").splitlines())


def screen_capture_disabled():
    d = dpm_dump()
    if "disableScreenCapture=true" in d.replace(" ", ""):
        return True
    for line in d.splitlines():  # API 34+: "Screen capture disallowed users: [-1]"
        if "Screen capture disallowed users" in line and "[]" not in line:
            return True
    return False


def lockscreen_info_has(msg):
    if msg in dpm_dump():  # older releases print it
        return True
    adb("root")  # emulator/userdebug: read where LockSettings persists owner info
    time.sleep(1)
    # (SQLite's write-ahead log counts: a fresh value sits in locksettings.db-wal until a checkpoint.)
    return bool(sh(f"grep -l '{msg}' /data/system/locksettings.db* /data/system/device_policies.xml 2>/dev/null").strip())


_CAPS = {}


def advertised(cap):
    """True when the device advertises capability token `cap` (policy.x / device.x / app.x). Read from the
    dev stack's database (the admin API does not expose it). Not advertised = the server withholds the command."""
    if not _CAPS:
        out = subprocess.run(["docker", "exec", A.pg_container, "psql", "-U", "mdmesh", "-tAc",
                              f"select agentCapabilities from devices where number='{A.device_id}'"],
                             capture_output=True, text=True).stdout.strip()
        c = json.loads(out or "{}")
        toks = {f"policy.{k}" for k in c.get("policy", [])} | {f"app.{k}" for k in c.get("appManagement", [])} \
            | {f"device.{k}" for k in c.get("device", [])}
        _CAPS["t"] = toks
    ok = cap in _CAPS["t"]
    if not ok:
        print(f"  SKIP  {cap} (not advertised on this release)", flush=True)
    return ok


def want(key):
    return not A.only or key in A.only.split(",")


def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    r = api("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5})
    if r.get("status") != "OK":
        sys.exit(f"login failed: {r}")


# ------------------------------------------------------------------------------------------------
def t_policies():
    print("== policies")
    if advertised("policy.camera"):
        t_camera()
    if advertised("policy.screenshots"):
        t_screenshots()
    for pol in ("wifi", "bluetooth", "usbStorage"):
        if not advertised(f"policy.{pol}"):
            continue
        s, d = run("policy.apply", {"policy": pol, "value": False}, f"policy.{pol}")
        check(f"{pol} restrict: done", s == "done", f"{s} {d}")
        s, d = run("policy.apply", {"policy": pol, "value": True}, f"policy.{pol}")
        check(f"{pol} allow: done", s == "done", f"{s} {d}")


def t_camera():
    s, d = run("policy.apply", {"policy": "camera", "value": False}, "policy.camera")
    check("camera disable: done", s == "done", f"{s} {d}")
    check("camera disable: DPM reports camera disabled", until(camera_disabled))
    s, d = run("policy.apply", {"policy": "camera", "value": True}, "policy.camera")
    check("camera enable: done", s == "done", f"{s} {d}")
    check("camera enable: DPM no longer disables camera", until(lambda: not camera_disabled()))


def t_screenshots():
    s, d = run("policy.apply", {"policy": "screenshots", "value": False}, "policy.screenshots")
    check("screenshots disable: done", s == "done", f"{s} {d}")
    check("screenshots disable: DPM", until(screen_capture_disabled))
    s, d = run("policy.apply", {"policy": "screenshots", "value": True}, "policy.screenshots")
    check("screenshots enable: done", s == "done", f"{s} {d}")


def t_messages():
    print("== lockscreen message / alert / ring")
    msg = "MDMesh test %d" % int(time.time())
    if advertised("device.lockscreenMessage"):
        s, d = run("device.lockscreenMessage", {"message": msg}, "device.lockscreenMessage")
        check("lockscreen message: done", s == "done", f"{s} {d}")
        check("lockscreen message: stored by the system", until(lambda: lockscreen_info_has(msg)))
        s, d = run("device.lockscreenMessage", {"message": ""}, "device.lockscreenMessage")
        check("lockscreen message clear: done", s == "done", f"{s} {d}")
    title = "Alerta %d" % int(time.time())
    s, d = run("device.alert", {"title": title, "body": "cuerpo de prueba"}, "device.alert")
    check("alert: done", s == "done", f"{s} {d}")
    check("alert: notification posted", until(lambda: title in sh("dumpsys notification --noredact")))
    s, d = run("device.ring", {"durationMs": 5000}, "device.ring")
    check("ring: done", s == "done", f"{s} {d}")
    s, d = run("device.ringStop", None, "device.ringStop")
    check("ringStop: done", s == "done", f"{s} {d}")


def screen_on():
    out = sh("dumpsys power")
    return "mWakefulness=Awake" in out


def t_lock():
    print("== lock")
    sh("input keyevent KEYCODE_WAKEUP")
    s, d = run("device.lock", None, "device.lock")
    check("lock: done", s == "done", f"{s} {d}")
    check("lock: screen turned off / keyguard shown",
          until(lambda: (not screen_on()) or "isShowing=true" in sh("dumpsys window policy").replace(" ", "")
                or "mShowingLockscreen=true" in sh("dumpsys window")))
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")


def t_modes():
    print("== power / location modes")
    for mode in ("alwaysOn", "adaptive"):
        s, d = run("device.powerMode", {"mode": mode}, "device.powerMode")
        check(f"powerMode {mode}: done", s == "done", f"{s} {d}")
    for mode in ("active", "passive"):
        s, d = run("device.locationMode", {"mode": mode}, "device.locationMode")
        check(f"locationMode {mode}: done", s == "done", f"{s} {d}")


def t_location():
    print("== location telemetry")
    adb("emu", "geo", "fix", "-74.0721", "4.7110")  # Bogota
    run("device.locationMode", {"mode": "active"}, "device.locationMode")
    run("config.sync")

    def has_loc():
        # Keep feeding the emulator's GPS like a real receiver does: on API 30+ a single fix sent while
        # nothing is listening is not kept, so the agent's one-shot request would never see it.
        adb("emu", "geo", "fix", "-74.0721", "4.7110")
        r = api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/locations?limit=20")
        return [l for l in (r.get("data") or []) if abs(float(l.get("lat", 0)) - 4.711) < 0.01]
    check("location: server stored a fix near Bogota", until(lambda: has_loc() or (run("config.sync", timeout=30) and has_loc()),
                                                             timeout=180, step=3))
    run("device.locationMode", {"mode": "passive"}, "device.locationMode")


def lock_task_state():
    out = sh("dumpsys activity activities")
    for line in out.splitlines():
        if "mLockTaskModeState" in line:
            return line.strip()
    return ""


def t_kiosk():
    print("== kiosk")
    payload = {"mode": "single", "pinPackage": "com.android.settings",
               "allowedPackages": ["com.android.settings"], "exitMode": "remote"}
    s, d = run("kiosk.enter", payload)
    check("kiosk single: done", s == "done", f"{s} {d}")
    check("kiosk single: lock task LOCKED", until(lambda: "LOCKED" in lock_task_state() or "PINNED" in lock_task_state()),
          lock_task_state())
    check("kiosk single: settings in front", until(lambda: "com.android.settings" in sh("dumpsys activity top | grep ACTIVITY")))
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    check("kiosk single: HOME does not leave the app", "com.android.settings" in sh("dumpsys activity top | grep ACTIVITY"))
    s, d = run("kiosk.exit")
    check("kiosk exit: done", s == "done", f"{s} {d}")
    check("kiosk exit: lock task NONE", until(lambda: "NONE" in lock_task_state()), lock_task_state())
    payload = {"mode": "launcher", "allowedPackages": ["com.android.settings", "com.android.chrome"]}
    s, d = run("kiosk.enter", payload)
    check("kiosk launcher: done", s == "done", f"{s} {d}")
    check("kiosk launcher: MDMesh launcher in front",
          until(lambda: any(a in sh("dumpsys activity activities | grep ResumedActivity")
                            for a in ("KioskLauncherActivity", "KioskHomeAlias"))))
    s, d = run("kiosk.exit")
    check("kiosk launcher exit: done", s == "done", f"{s} {d}")
    check("kiosk launcher exit: lock task NONE", until(lambda: "NONE" in lock_task_state()), lock_task_state())


def t_passcode():
    print("== passcode reset")
    s, d = run("device.passcodeReset", {"newPassword": "1357"}, "device.passcodeReset")
    ok = s == "done"
    check("passcode set: done", ok, f"{s} {d}")
    if ok:
        if sdk() >= 27:  # `locksettings verify` exists from 8.1
            check("passcode set: the device accepts exactly the new PIN",
                  until(lambda: "verified successfully" in sh("locksettings verify --old 1357").lower())
                  and "verified successfully" not in sh("locksettings verify --old 9999").lower())
        else:
            print("  SKIP  passcode verification (no `locksettings verify` below API 27)", flush=True)
        s, d = run("device.passcodeReset", {"newPassword": ""}, "device.passcodeReset")
        check("passcode clear: done", s == "done", f"{s} {d}")
        if sdk() >= 27:
            check("passcode clear: no PIN left", until(lambda: "verified successfully" in sh("locksettings verify").lower()))


def t_apps():
    print("== app inventory")
    s, d = run("apps.scan")
    check("apps.scan: done", s == "done", f"{s} {d}")
    r = api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/telemetry")
    check("telemetry: hardware present", bool((r.get("data") or {})), str(r)[:200])


TESTAPP = "com.mdmesh.testapp"
TESTAPP_DIR = __import__("os").path.join(__import__("os").path.dirname(__file__), "testapp")


def upload_apk(path):
    """Upload + commit an APK the way the console does; returns (public url, sha256)."""
    import mimetypes, uuid
    boundary = uuid.uuid4().hex
    data = open(path, "rb").read()
    # Unique name per run: the server refuses to commit over an existing file of the same name.
    name = __import__('os').path.basename(path).replace(".apk", f"-{int(time.time() * 1000)}.apk")
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
            f"Content-Type: application/vnd.android.package-archive\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(A.api + "/rest/private/web-ui-files", data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with OP.open(req, timeout=60) as r:
        up = json.loads(r.read())
    tmp = up["data"]["serverPath"]
    c = api("POST", "/rest/private/web-ui-files/update", {"tmpPath": tmp, "fileName": "", "filePath": "", "external": False})
    if c.get("status") != "OK":
        raise RuntimeError(f"commit failed: {c}")
    return c["data"]["url"], hashlib.sha256(data).hexdigest()


def installed_version():
    out = sh(f"dumpsys package {TESTAPP}")
    for line in out.splitlines():
        if "versionCode=" in line:
            return int(line.split("versionCode=")[1].split()[0])
    return None


def t_install():
    print("== silent app install / upgrade / uninstall")
    if not advertised("app.silentInstall"):
        return
    # The committed URL uses the server's BASE_URL (localhost:8088 on the dev stack): let the device reach it.
    adb("reverse", "tcp:8088", "tcp:8088")
    sh(f"pm uninstall {TESTAPP}")
    for v in (1, 2):
        url, sha = upload_apk(__import__("os").path.join(TESTAPP_DIR, f"testapp-v{v}.apk"))
        s, d = run("app.install", {"url": url, "packageName": TESTAPP, "versionCode": v, "sha256": sha,
                                    "runAfterInstall": False}, "app.silentInstall", timeout=120)
        check(f"app.install v{v}: done", s == "done", f"{s} {d}")
        check(f"app.install v{v}: installed silently with versionCode {v}", until(lambda: installed_version() == v),
              str(installed_version()))
    url, _ = upload_apk(__import__("os").path.join(TESTAPP_DIR, "testapp-v1.apk"))
    s, d = run("app.install", {"url": url, "packageName": TESTAPP, "versionCode": 1, "sha256": "0" * 64}, "app.silentInstall")
    check("app.install with a wrong sha256 is refused", s == "failed", f"{s} {d}")
    s, d = run("app.uninstall", {"packageName": TESTAPP}, timeout=120)
    check("app.uninstall: done", s == "done", f"{s} {d}")
    check("app.uninstall: package gone", until(lambda: installed_version() is None))


def pkg_version(pkg):
    for line in sh(f"dumpsys package {pkg}").splitlines():
        if "versionCode=" in line:
            return int(line.split("versionCode=")[1].split()[0])
    return None


def t_self_update():
    print("== agent self-update (app.install of its own package)")
    adb("reverse", "tcp:8088", "tcp:8088")
    before = pkg_version(A.pkg)
    url, sha = upload_apk(A.self_update_apk)
    q = queue("app.install", {"url": url, "packageName": A.pkg, "versionCode": A.self_update_code, "sha256": sha},
              "app.silentInstall")
    check("self-update: new version installed", until(lambda: pkg_version(A.pkg) == A.self_update_code, timeout=180),
          f"before={before} now={pkg_version(A.pkg)}")
    check("self-update: still Device Owner",
          until(lambda: f"{A.pkg}/" in sh("dumpsys device_policy | grep -A4 -i 'Device Owner'")))
    s, d = run("config.sync", timeout=180)
    check("self-update: updated agent is back online executing commands", s == "done", f"{s} {d}")


def t_reboot():
    print("== reboot")
    if not advertised("device.reboot"):
        return
    boot_before = sh("cat /proc/sys/kernel/random/boot_id").strip()
    s, d = run("device.reboot", None, "device.reboot")
    check("reboot: queued/done", s in ("done", "timeout", "delivered"), f"{s} {d}")
    adb("wait-for-device", timeout=300)
    until(lambda: sh("getprop sys.boot_completed").strip() == "1", timeout=300, step=3)
    check("reboot: device actually rebooted", sh("cat /proc/sys/kernel/random/boot_id").strip() != boot_before)
    time.sleep(10)
    t0 = time.time() * 1000
    s, d = run("config.sync", timeout=240)
    check("reboot: agent back online and executing commands (boot receiver)", s == "done", f"{s} {d}")


def t_wipe():
    print("== wipe (factory reset)")
    s, d = run("device.wipe", None, "device.wipe", timeout=60)
    check("wipe: command accepted", s in ("done", "delivered", "timeout"), f"{s} {d}")
    # Emulator images have no /misc partition, so RecoverySystem cannot reboot into the wipe there; the
    # platform still logs the wipe command it received from our admin (uncrypt). Real devices reset.
    def wiped_or_ordered():
        if "com.mdmesh" not in sh("pm list packages com.mdmesh"):
            return "reset"
        log = adb("logcat", "-d")
        if "--wipe_data" in log and ("from com.mdmesh.agent" in log or "REBOOTING TO WIPE" in log
                                     or "rebootWipeUserData" in log or "uncrypt" in log):
            return "ordered"
        if "REBOOTING TO WIPE USER DATA" in log:  # older releases log only RecoverySystem's banner
            return "ordered"
        # API 23-25 emulators reboot into a recovery that ignores the command; at the next boot the
        # platform deletes the wipe command the agent wrote, which is the proof it was handed over.
        if "Deleted: /cache/recovery/command" in log:
            return "ordered (recovery command written; emulator recovery cannot wipe)"
        return None
    outcome = until(wiped_or_ordered, timeout=400, step=5)
    check(f"wipe: factory reset performed or handed to recovery by the platform ({outcome})", outcome is not None)


def main():
    login()
    print(f"device {A.device_id} on {A.serial} (API {sdk()})")
    # The API 30+ emulator GNSS stamps every injected fix with the same elapsed-realtime, so the framework
    # keeps only the FIRST fix it sees after boot. Make that one Bogota, before any step turns on active mode.
    for _ in range(3):
        adb("emu", "geo", "fix", "-74.0721", "4.7110")
        time.sleep(1)
    tests = [("policies", t_policies), ("messages", t_messages), ("lock", t_lock), ("modes", t_modes),
             ("kiosk", t_kiosk), ("passcode", t_passcode), ("apps", t_apps), ("install", t_install),
             ("location", t_location)]
    if A.self_update_apk:
        tests.append(("selfupdate", t_self_update))
    if A.destructive:
        tests += [("reboot", t_reboot), ("wipe", t_wipe)]
    for key, fn in tests:
        if want(key):
            try:
                fn()
            except Exception as e:  # noqa
                check(f"{key}: no exception", False, repr(e))
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print(f"===== RESULT: PASS={passed} FAIL={len(RESULTS) - passed} =====")
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
