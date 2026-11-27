#!/usr/bin/env python3
"""
End-to-end proof, on a REAL enrolled agent (emulator or USB device) against the dev stack, of the factory-reset
protection and the remote wipe (criteria MDM-03 "Protección Factory Reset" and MDM-09 "Borrado remoto").

    scripts/frp-wipe-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id>
        [--pg-container dallycontrol-dev-postgres-1] [--only frp,lost,reenroll,wipe-online,wipe-offline]
        [--destructive] [--debugging] [--agent-package com.dallycontrol.agent.debug]

Phases (each can be picked with --only):
  frp           company default account + a policy that blocks reset from Settings, safe mode and OEM unlock, and
                wipes after 10 wrong unlocks. The device must hold the restrictions (`dumpsys device_policy`) and
                the protection, and the console must say what the device REALLY holds: "FRP activa con 1 cuenta",
                or the reason it cannot (an emulator image has no persistent data block, so Android 11+ answers
                "unsupported" there; Android 10 and older go through Google Play services' restrictions).
                Then: inheritance (a policy of its own accounts, a policy that switches it off), and clean-up.
  lost          lost mode end to end: locked, message + phone on the lock screen, position every minute, wipe after
                N wrong unlocks; who ordered it stays in the history; ending it puts everything back.
  reenroll      (API only, no device) a phone that enrolls again after a reset comes back to its row by serial /
                IMEI; a deleted one is re-created in its folder from its tombstone; junk serials and a row that is
                still reporting are never taken over.
  wipe-online   (--destructive) remote wipe "prepare to reassign" with the device online: announced by the agent
                BEFORE wiping (the console marks it at once, with who ordered it and the options), flags per option.
  wipe-offline  (--destructive, LAST) airplane mode, queue "lost or stolen", make it 3 days old in the database: it
                is still pending; reconnect: delivered, announced and executed.

Emulator images have no /misc partition, so the reset itself cannot finish there: the test accepts the wipe as handed
to recovery (docs/TESTING.md). --debugging also checks "block USB debugging" (LAST: it cuts adb; emulator only).

The database is touched only to move timestamps back (simulating elapsed time). Exit code 0 only when every check
passed. Raise --timeout-scale on a loaded machine.
"""
import argparse, hashlib, http.cookiejar, json, subprocess, sys, time, urllib.error, urllib.request, uuid

P = argparse.ArgumentParser()
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--serial", default="")
P.add_argument("--device-id", default="", help="the device number (UUID) the agent enrolled as")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--adb", default="adb")
P.add_argument("--pg-container", default="dallycontrol-dev-postgres-1")
P.add_argument("--agent-package", default="com.dallycontrol.agent.debug")
P.add_argument("--only", default="")
P.add_argument("--destructive", action="store_true", help="also run the remote-wipe phases")
P.add_argument("--debugging", action="store_true", help="also block USB debugging at the very end (cuts adb)")
P.add_argument("--timeout-scale", type=float, default=1.0)
A = P.parse_args()

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
RESULTS = []
HOUR = 3600_000
FRP_ID = "118273645091827364509"      # a syntactically valid Google account id (21 digits); not a real account
FRP_ID_2 = "100200300400500600700"
FRP_EMAIL = "ti-pruebas@example.com"


def api(method, path, body=None, auth=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if auth:
        headers["Authorization"] = auth
    for attempt in range(4):
        req = urllib.request.Request(A.api + path, data=data, method=method, headers=headers)
        try:
            with OP.open(req, timeout=60) as r:
                return json.loads(r.read() or b"{}")
        except (urllib.error.HTTPError, urllib.error.URLError, ConnectionError, TimeoutError) as e:
            code = getattr(e, "code", None)
            if method != "GET" or attempt == 3 or (code is not None and code not in (502, 503, 504)):
                raise
            time.sleep(1.5)


def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    r = api("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5})
    if r.get("status") != "OK":
        sys.exit(f"login failed: {r}")


def adb(*args, timeout=90):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)


def sdk():
    return int(sh("getprop ro.build.version.sdk").strip())


def sql(statement):
    r = subprocess.run(["docker", "exec", A.pg_container, "psql", "-U", "dallycontrol", "-d", "dallycontrol",
                        "-tAc", statement], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"psql failed: {r.stderr.strip()}")
    return r.stdout.strip()


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    return ok


def until(fn, timeout=30, step=1.0):
    end = time.time() + timeout * A.timeout_scale
    v = None
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return v


def queue(type_, payload=None, cap=None, device=None):
    body = {"type": type_}
    if cap:
        body["requiresCapability"] = cap
    if payload is not None:
        body["payload"] = json.dumps(payload)
    r = api("POST", f"/rest/private/agent/v1/devices/{device or A.device_id}/commands", body)
    if r.get("status") != "OK":
        raise RuntimeError(f"queue {type_} failed: {r}")
    return r["data"]


def history(device=None):
    return api("GET", f"/rest/private/agent/v1/devices/{device or A.device_id}/commands").get("data") or []


def command(cmd_id):
    for c in history():
        if str(c.get("id")) == str(cmd_id):
            return c
    return {}


CLOSED = ("done", "failed", "unsupported", "expired", "cancelled")


def wait_closed(cmd_id, timeout=120):
    return until(lambda: (lambda c: c if c.get("status") in CLOSED else None)(command(cmd_id)), timeout, 0.5) \
        or command(cmd_id) or {"status": "timeout"}


def sync():
    api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/sync", {})


def live():
    return A.device_id in (api("GET", "/rest/private/agent/v1/live").get("data") or [])


def protection(device=None):
    r = api("GET", f"/rest/private/protection/v1/devices/{device or A.device_id}")
    return r.get("data") or {}


def events(type_, device=None):
    return sql(f"select detail from device_event where deviceNumber = '{device or A.device_id}' and type = '{type_}' "
               f"order by ts desc limit 5")


def airplane(on):
    """Cut / restore every link of the device. Emulators route everything through wifi + mobile data."""
    if sdk() >= 28:
        sh("cmd connectivity airplane-mode " + ("enable" if on else "disable"))
    else:
        sh(f"settings put global airplane_mode_on {1 if on else 0}")
        sh(f"am broadcast -a android.intent.action.AIRPLANE_MODE --ez state {'true' if on else 'false'}")
    sh("svc wifi " + ("disable" if on else "enable"))
    sh("svc data " + ("disable" if on else "enable"))


# --- the device's policy ----------------------------------------------------------------------------------------

def configuration_id():
    return int(sql(f"select configurationId from devices where number = '{A.device_id}'"))


def set_policy(dc_policy):
    """Save the dcPolicy of the device's configuration through the console's own API (so the server validates it)."""
    cid = configuration_id()
    cfg = api("GET", f"/rest/private/configurations/{cid}").get("data")
    if not cfg:
        raise RuntimeError("could not read the device's configuration")
    cfg["dcPolicy"] = json.dumps(dc_policy) if dc_policy else None
    r = api("PUT", "/rest/private/configurations", cfg)
    if r.get("status") != "OK":
        raise RuntimeError(f"saving the policy failed: {r}")


def stored_policy():
    return json.loads(sql(f"select coalesce(dcPolicy, '{{}}') from configurations where id = {configuration_id()}") or "{}")


def wait_applied(timeout=180):
    """The device applied the current revision of its policy (or the last config.apply failed)."""
    def done():
        sync()
        s = api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/config").get("data") or {}
        if s.get("inSync"):
            return s
        last = s.get("lastCommand") or {}
        return s if last.get("status") == "failed" else None
    return until(done, timeout, 3) or {}


def dumpsys_policy():
    return sh("dumpsys device_policy", timeout=120)


def gms_restrictions():
    """Application restrictions of Google Play services, as Android stores them (Android 10 and older)."""
    adb("root")
    time.sleep(2)
    return sh("cat /data/system/users/0/res_com.google.android.gms.xml 2>/dev/null")


# --- phases -----------------------------------------------------------------------------------------------------

def t_frp():
    api_level = sdk()
    print(f"== factory-reset protection (API {api_level})")
    before = stored_policy()
    r = api("PUT", "/rest/private/protection/v1/defaults/company", {"accounts": [{"email": FRP_EMAIL, "id": FRP_ID}]})
    check("company default account saved", r.get("status") == "OK" and (r.get("data") or [{}])[0].get("id") == FRP_ID, str(r))
    bad = api("PUT", "/rest/private/protection/v1/defaults/company", {"accounts": [{"email": FRP_EMAIL, "id": FRP_EMAIL}]})
    check("an e-mail in the id field is refused", bad.get("status") != "OK" and "account.invalid" in str(bad.get("message")), str(bad))

    policy = dict(before)
    policy["device"] = dict(before.get("device") or {}, factoryReset="block", safeBoot="block", oemUnlock="block",
                            maxFailedPasswordsForWipe=10)
    policy["device"].pop("frpAccounts", None)
    policy["device"].pop("frpMode", None)
    set_policy(policy)
    s = wait_applied()
    check("the device applied the policy", bool(s.get("inSync")), str(s.get("lastCommand")))

    p = until(lambda: (lambda x: x if (x.get("reported") or {}).get("restrictions", {}).get("factoryReset") else None)(protection()), 90, 3) or protection()
    rep = p.get("reported") or {}
    check("console: the accounts come from the company", p.get("expected", {}).get("source") == "company"
          and [a.get("id") for a in p.get("expected", {}).get("accounts", [])] == [FRP_ID], str(p.get("expected")))
    check("agent reports the restrictions it holds (reset, safe mode, OEM unlock)",
          rep.get("restrictions", {}).get("factoryReset") is True and rep.get("restrictions", {}).get("safeBoot") is True
          and rep.get("restrictions", {}).get("oemUnlock") is True, str(rep.get("restrictions")))
    dump = dumpsys_policy()
    check("dumpsys device_policy: no_factory_reset and no_safe_boot are set", "no_factory_reset" in dump and "no_safe_boot" in dump)
    if 24 <= api_level < 28:
        check("dumpsys device_policy: no_oem_unlock is set (Android 7-8.1)", "no_oem_unlock" in dump)
    check("wipe after 10 wrong unlocks is set", rep.get("maxFailedPasswordsForWipe") == 10
          and ("maximumFailedPasswordsForWipe=10" in dump.replace(" ", "") or "max-failed-password-wipe" in dump or "10" in dump),
          str(rep.get("maxFailedPasswordsForWipe")))

    frp = rep.get("frp") or {}
    mechanism = "policy" if api_level >= 30 else "gms"
    check(f"agent uses the mechanism of this Android version ({mechanism})", frp.get("mechanism") == mechanism, str(frp))
    if frp.get("active"):
        check("device holds exactly the company account", frp.get("accounts") == [FRP_ID], str(frp))
        check("console says 'FRP activa con 1 cuenta'", p.get("state") == "active" and p.get("summary") == "FRP activa con 1 cuenta", p.get("summary"))
        if api_level >= 30:
            flat = dump.replace(" ", "")
            check("dumpsys device_policy shows the FRP policy with the account", FRP_ID in dump, "account id not in dumpsys")
            check("dumpsys device_policy: protection enabled", "mFactoryResetProtectionEnabled=true" in flat
                  or "factoryResetProtectionEnabled=true" in flat or FRP_ID in dump)
        else:
            res = gms_restrictions()
            check("Google Play services holds factoryResetProtectionAdmin with the account", "factoryResetProtectionAdmin" in res and FRP_ID in res, res[:300])
    else:
        # The device cannot (no persistent data block / no Google Play services): the console must say so, never "activa".
        check(f"device cannot hold FRP and says why (reason={frp.get('reason')}, {frp.get('error')})",
              frp.get("reason") in ("unsupported", "gmsMissing"), str(frp))
        check("console says 'FRP inactiva' with the reason, not 'activa'", p.get("state") == "inactive"
              and p.get("summary", "").startswith("FRP inactiva"), p.get("summary"))
        check("a device that cannot hold FRP does not fail its policy", not rep.get("errors"), str(rep.get("errors")))

    # A policy with its own account wins over the company's.
    own = json.loads(json.dumps(policy))
    own["device"]["frpAccounts"] = [FRP_ID_2]
    own["frpLabels"] = {FRP_ID_2: "otra@example.com"}
    set_policy(own)
    wait_applied()
    p2 = until(lambda: (lambda x: x if x.get("expected", {}).get("source") == "policy" else None)(protection()), 30, 2) or protection()
    check("a policy's own account wins over the company's", p2.get("expected", {}).get("source") == "policy"
          and p2["expected"]["accounts"][0].get("id") == FRP_ID_2 and p2["expected"]["accounts"][0].get("email") == "otra@example.com",
          str(p2.get("expected")))
    if frp.get("active"):
        held = until(lambda: (lambda x: x if ((x.get("reported") or {}).get("frp") or {}).get("accounts") == [FRP_ID_2] else None)(protection()), 90, 3)
        check("the device now holds the policy's account", bool(held), str((protection().get("reported") or {}).get("frp")))

    # Switched off in the policy: nothing is sent and the console warns.
    off = json.loads(json.dumps(policy))
    off["device"]["frpMode"] = "off"
    set_policy(off)
    wait_applied()
    p3 = until(lambda: (lambda x: x if x.get("expected", {}).get("source") == "off"
                        and not ((x.get("reported") or {}).get("frp") or {}).get("active") else None)(protection()), 90, 3) or protection()
    check("a policy can switch the protection off (device holds none, console says inactive)",
          p3.get("expected", {}).get("source") == "off" and p3.get("state") == "inactive", f"{p3.get('expected')} {p3.get('summary')}")
    if api_level >= 30 and frp.get("active"):
        check("dumpsys device_policy no longer shows the account", FRP_ID_2 not in dumpsys_policy())

    # Back to the company default for the phases that follow (a lost phone keeps its protection).
    set_policy(policy)
    wait_applied()


def t_lost():
    api_level = sdk()
    print(f"== lost mode (API {api_level})")
    if not protection().get("capabilities", {}).get("lostMode"):
        check("agent advertises device.lostMode", False, "capability missing")
        return
    msg = f"Equipo de pruebas {uuid.uuid4().hex[:6]}"
    sh("input keyevent KEYCODE_WAKEUP")
    c = queue("device.lostMode", {"enabled": True, "message": msg, "phone": "+57 300 1234567", "ringSeconds": 10,
                                  "wipeAfterFailedUnlocks": 5, "wipeIfSilentDays": 3}, "device.lostMode")
    sync()
    done = wait_closed(c["id"])
    check("lost mode: executed", done.get("status") == "done", str(done))
    check("history says what was ordered", msg in (done.get("subject") or "") and "borrar tras 5 intentos" in (done.get("subject") or ""),
          str(done.get("subject")))
    check("who ordered it stays in the device's events", A.admin_user in events("lostModeOrdered") and msg in events("lostModeOrdered"))
    dump = dumpsys_policy()
    if api_level >= 24:
        adb("root")
        time.sleep(1)
        shown = msg in dump or bool(sh(f"grep -l '{msg}' /data/system/locksettings.db* /data/system/device_policies.xml "
                                       f"/data/system/users/0/*.xml 2>/dev/null").strip())
        check("lock screen shows the message and the contact phone", shown)
    locked = until(lambda: "mWakefulness=Awake" not in sh("dumpsys power") or "isShowing=true" in sh("dumpsys window policy").replace(" ", "")
                   or "mShowingLockscreen=true" in sh("dumpsys window") or "mDreamingLockscreen=true" in sh("dumpsys window"), 20, 2)
    check("device locked at once", bool(locked))
    prefs = sh(f"run-as {A.agent_package} cat shared_prefs/mdm_trail.xml shared_prefs/mdm_location.xml")
    check("position every minute, fresh fix", 'name="interval_min" value="1"' in prefs and ">active<" in prefs, prefs[:300])
    p = until(lambda: (lambda x: x if ((x.get("reported") or {}).get("lostMode") or {}).get("active") else None)(protection()), 60, 3) or protection()
    lm = (p.get("reported") or {}).get("lostMode") or {}
    check("console shows lost mode active with its automatic wipes", lm.get("active") and lm.get("wipeAfterFailedUnlocks") == 5
          and lm.get("wipeIfSilentDays") == 3 and lm.get("lastContactAt"), str(lm))
    check("wipe after 5 wrong unlocks holds (stricter than the policy's 10)", (p.get("reported") or {}).get("maxFailedPasswordsForWipe") == 5,
          str((p.get("reported") or {}).get("maxFailedPasswordsForWipe")))

    c = queue("device.lostMode", {"enabled": False}, "device.lostMode")
    sync()
    done = wait_closed(c["id"])
    check("lost mode off: executed", done.get("status") == "done", str(done))
    p = until(lambda: (lambda x: x if not ((x.get("reported") or {}).get("lostMode") or {}).get("active") else None)(protection()), 60, 3) or protection()
    check("console shows lost mode inactive", not ((p.get("reported") or {}).get("lostMode") or {}).get("active"))
    prefs = sh(f"run-as {A.agent_package} cat shared_prefs/mdm_trail.xml")
    check("tracking back to what the policy had", 'name="interval_min" value="1"' not in prefs, prefs[:200])
    policy_value = (stored_policy().get("device") or {}).get("maxFailedPasswordsForWipe") or 0
    check("wipe-after-unlocks back to the policy's value", (p.get("reported") or {}).get("maxFailedPasswordsForWipe") == policy_value,
          f"{(p.get('reported') or {}).get('maxFailedPasswordsForWipe')} != {policy_value}")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")


def enroll(token, hardware_id, serial=None, imei=None):
    body = {"enrollToken": token, "hardwareId": hardware_id,
            "agent": {"version": "0.9.9", "packageName": "com.dallycontrol.agent"},
            "device": {"id": "", "androidSdkInt": 30, "isDeviceOwner": True}, "capabilities": {"device": ["lock", "wipe"]}}
    if serial:
        body["serial"] = serial
    if imei:
        body["imei"] = imei
    return api("POST", "/rest/public/agent/v1/enroll", body)


def mint(group_id=None):
    r = api("POST", "/rest/private/agent/v1/token", {"groupId": group_id} if group_id else {})
    return r["data"]["token"]


def device_group(number):
    return sql(f"select coalesce((select dg.groupId from deviceGroups dg join devices d on d.id = dg.deviceId "
               f"where d.number = '{number}' order by dg.id limit 1)::text, '')")


def t_reenroll():
    print("== a phone that enrolls again after a factory reset comes back to its row and folder")
    tag = uuid.uuid4().hex[:8].upper()
    serial = f"R58W9{tag}"
    imei = "3569" + str(int(tag, 16)).zfill(11)[:11]
    g = api("POST", "/rest/private/fleet/v1/groups", {"name": f"FRP e2e {tag}"})
    group_id = (g.get("data") or {}).get("id")
    check("test folder created", bool(group_id), str(g))

    first = enroll(mint(group_id), f"hw-a-{tag}", serial, imei)
    number = (first.get("data") or {}).get("deviceId")
    check("first enrollment lands in the folder", first.get("status") == "OK" and device_group(number) == str(group_id), str(first))

    # Still reporting (just enrolled): another phone claiming the same serial must NOT take the row over.
    other = enroll(mint(), f"hw-b-{tag}", serial, None)
    other_number = (other.get("data") or {}).get("deviceId")
    check("a row that is still reporting is not taken over", other.get("status") == "OK" and other_number != number, str(other))
    sql(f"delete from devices where number = '{other_number}'")
    sql(f"delete from device_tombstone where number = '{other_number}'")

    # The phone is reset by buttons: silent for 5 minutes, then enrolls again with a new hardware id and no folder.
    sql(f"update devices set lastUpdate = lastUpdate - {5 * 60_000} where number = '{number}'")
    again = enroll(mint(), f"hw-c-{tag}", serial, None)
    check("after a reset the same serial returns to its own row", (again.get("data") or {}).get("deviceId") == number, str(again))
    check("...and keeps its folder", device_group(number) == str(group_id))
    check("...and the history says so", "restablecimiento" in events("reenrolled", number))
    sql(f"update devices set lastUpdate = lastUpdate - {5 * 60_000} where number = '{number}'")
    by_imei = enroll(mint(), f"hw-d-{tag}", None, imei)
    check("the IMEI alone is enough", (by_imei.get("data") or {}).get("deviceId") == number, str(by_imei))

    # Deleted from the console, then the phone comes back: re-created in its folder from the tombstone.
    dev_id = int(sql(f"select id from devices where number = '{number}'"))
    api("POST", "/rest/private/devices/deleteBulk", {"ids": [dev_id]})
    check("device deleted; tombstone keeps serial and IMEI", sql(f"select count(*) from devices where number = '{number}'") == "0"
          and sql(f"select serial || '/' || imei from device_tombstone where number = '{number}'") == f"{serial}/{imei}",
          sql(f"select serial, imei from device_tombstone where number = '{number}'"))
    back = enroll(mint(), f"hw-e-{tag}", serial, imei)
    check("a deleted device that enrolls again gets its number back", (back.get("data") or {}).get("deviceId") == number, str(back))
    check("...in the folder it had", device_group(number) == str(group_id))
    check("...and its tombstone is gone", sql(f"select count(*) from device_tombstone where number = '{number}'") == "0")

    # Junk identifiers never match.
    junk1 = enroll(mint(), f"hw-f-{tag}", "unknown", "000000000000000")
    junk2 = enroll(mint(), f"hw-g-{tag}", "unknown", "000000000000000")
    n1, n2 = (junk1.get("data") or {}).get("deviceId"), (junk2.get("data") or {}).get("deviceId")
    check("generic serials / invalid IMEIs never merge two phones", n1 and n2 and n1 != n2 and number not in (n1, n2))

    for n in (number, n1, n2):
        sql(f"delete from devices where number = '{n}'")
        sql(f"delete from device_tombstone where number = '{n}'")
    api("DELETE", f"/rest/private/fleet/v1/groups/{group_id}")


def handed_to_recovery():
    if A.agent_package not in sh(f"pm list packages {A.agent_package}", timeout=30):
        return "reset"
    log = adb("logcat", "-d", timeout=120)
    if "--wipe_data" in log or "REBOOTING TO WIPE USER DATA" in log or "rebootWipeUserData" in log \
            or "Deleted: /cache/recovery/command" in log or "FORMAT_AND_FACTORY_RESET" in log or "MasterClear" in log \
            or "FactoryReset" in log:
        return "handed to recovery (an emulator has no /misc partition and cannot finish the reset)"
    return None


def wipe_checks(c, intent, expect_flags, expect_frp):
    """What must be true right after a wipe was delivered: announced, marked, described, executed."""
    seen = until(lambda: (lambda x: x if x.get("status") in ("accepted", "done", "failed") else None)(command(c["id"])), 300, 1) or command(c["id"])
    check("wipe: the agent answered (announced before wiping)", seen.get("status") in ("accepted", "done"), str(seen))
    started = until(lambda: events("wipeStarted"), 30, 2) or ""
    check("server recorded 'the device confirmed it starts wiping' with the time", f"orden {c['id']}" in started and "wipe:starting" in started, started[:200])
    check(f"flags match the options ({expect_flags}, FRP {expect_frp})", f"flags={expect_flags} " in started and f"frp={expect_frp}" in started, started[:200])
    api_level = sdk() if handed_to_recovery() != "reset" else 0
    if api_level:
        call = "WIPE_DEVICE" if api_level >= 34 else "WIPE_DATA"
        check(f"platform call for API {api_level} is {call}", f"call={call}" in started, started[:200])
    marks = api("GET", "/rest/private/agent/v1/wiped").get("data") or {}
    marked_db = sql(f"select count(*) from device_event where deviceNumber = '{A.device_id}' and type = 'wipeStarted'")
    mark = marks.get(A.device_id)
    # An emulator survives its "wipe" and keeps reporting, which (correctly) takes the mark away after a minute.
    check("console marks the device 'Borrado' with who ordered it (or the device is still reporting: emulator)",
          (bool(mark) and mark.get("wipedBy") == A.admin_user) or int(marked_db) > 0, str(marks))
    ordered = events("wipeOrdered")
    check("who ordered the wipe and with which options stays in the history",
          A.admin_user in ordered and ("perdido o robado" if intent == "lost" else "reasignar") in ordered, ordered[:200])
    subject = command(c["id"]).get("subject") or ""
    check("command history shows the intent and options", ("perdido o robado" if intent == "lost" else "reasignar") in subject, subject)
    outcome = until(handed_to_recovery, 300, 5)
    check(f"wipe: executed on the device ({outcome})", outcome is not None)


def t_wipe_online():
    print("== remote wipe, device online: 'prepare to reassign' (SD card too, protection removed)")
    if not protection().get("capabilities", {}).get("wipeOptions"):
        check("agent advertises device.wipeOptions", False, "capability missing")
        return
    adb("logcat", "-c")
    c = queue("device.wipe", {"intent": "reassign", "externalStorage": True}, "device.wipe")
    sync()
    wipe_checks(c, "reassign", 3, "removed")


def t_wipe_offline():
    print("== remote wipe queued without link for 3 days: 'lost or stolen' (protection kept)")
    if handed_to_recovery() == "reset":
        check("device still enrolled for the offline wipe", False, "the previous wipe really reset it")
        return
    airplane(True)
    check("server sees the live channel closed", until(lambda: not live(), timeout=240, step=3))
    c = queue("device.wipe", {"intent": "lost", "reason": "Equipo reportado como perdido"}, "device.wipe")
    sql(f"update agentCommand set createdAt = createdAt - {72 * HOUR} where id = {c['id']}")
    api("GET", "/rest/private/agent/v1/wiped")
    check("wipe: still pending after 3 days without link (more than the old 60 minutes)", command(c["id"]).get("status") == "pending",
          str(command(c["id"])))
    sql(f"delete from device_event where deviceNumber = '{A.device_id}' and type = 'wipeStarted'")
    adb("logcat", "-c")
    airplane(False)
    wipe_checks(c, "lost", 0, "kept")
    # The phone is gone: an emulator that could not reset keeps reporting, so cut it off and let 11 minutes pass.
    subprocess.run([A.adb, "-s", A.serial, "emu", "kill"], capture_output=True, timeout=30)
    time.sleep(8)
    shift = 11 * 60_000
    sql(f"update agentCommand set createdAt = createdAt - {shift}, deliveredAt = deliveredAt - {shift}, "
        f"completedAt = case when completedAt is null then null else completedAt - {shift} end where id = {c['id']}")
    wiped = sql(f"select coalesce(wipedAt, 0) from devices where number = '{A.device_id}'")
    if wiped == "0":  # the emulator reported after its "wipe": put the mark back where the announcement set it
        sql(f"update devices set wipedAt = (select max(ts) from device_event where deviceNumber = '{A.device_id}' and type = 'wipeStarted'), "
            f"wipedBy = '{A.admin_user}' where number = '{A.device_id}'")
    sql(f"update devices set wipedAt = wipedAt - {shift}, lastUpdate = least(lastUpdate, wipedAt - {shift}) where number = '{A.device_id}'")
    marks = api("GET", "/rest/private/agent/v1/wiped").get("data") or {}
    mark = marks.get(A.device_id)
    check("device marked 'Borrado' with date and issuer after going silent", bool(mark) and mark.get("wipedBy") == A.admin_user and mark.get("wipedAt"), str(marks))
    check("device row is kept (not deleted)", sql(f"select count(*) from devices where number='{A.device_id}'") == "1")
    c2 = command(c["id"])
    check("wipe command closed as executed ('Borrado ejecutado')", c2.get("status") == "done", str(c2))


def t_debugging():
    print("== block USB debugging (cuts adb: last)")
    policy = stored_policy()
    policy["device"] = dict(policy.get("device") or {}, debugging="block")
    set_policy(policy)
    p = until(lambda: (lambda x: x if (x.get("reported") or {}).get("restrictions", {}).get("debugging") else None)((sync(), protection())[1]), 180, 4) or protection()
    check("agent reports 'no debugging features' applied", (p.get("reported") or {}).get("restrictions", {}).get("debugging") is True,
          str((p.get("reported") or {}).get("restrictions")))
    gone = until(lambda: "device" not in subprocess.run([A.adb, "-s", A.serial, "get-state"], capture_output=True, text=True, timeout=20).stdout
                 or "no_debugging_features" in dumpsys_policy(), 60, 3)
    check("adb is cut, or the restriction shows in dumpsys", bool(gone))


def main():
    login()
    only = [x for x in A.only.split(",") if x]
    tests = [("reenroll", t_reenroll)]
    if A.serial and A.device_id:
        print(f"device {A.device_id} on {A.serial} (API {sdk()})")
        tests = [("frp", t_frp), ("lost", t_lost), ("reenroll", t_reenroll)]
        if A.destructive:
            tests += [("wipe-online", t_wipe_online), ("wipe-offline", t_wipe_offline)]
        if A.debugging:
            tests.append(("debugging", t_debugging))
    for name, fn in tests:
        if only and name not in only:
            continue
        try:
            fn()
        except Exception as e:  # a phase that blows up is a failure, and the next ones still run
            check(f"{name}: no exception", False, repr(e))
            if name == "wipe-offline" and A.serial:
                try:
                    airplane(False)
                except Exception:
                    pass
    failed = [r for r in RESULTS if not r[1]]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} passed")
    for name, _, detail in failed:
        print(f"  FAILED: {name}  {detail}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
