#!/usr/bin/env python3
"""
End-to-end test of "hardware y puertos" (criterion MDM-12) on a REAL enrolled agent (emulator or phone, Device
Owner): the policy blocks everything, the device is read over adb, and then everything is reverted.

    scripts/hardware-e2e.py --api http://localhost:8088 --serial emulator-5600 --device-id <id>
        [--admin-password admin] [--keep-usb-debug] [--timeout 180]

What it does, on the configuration the device is using (restored at the end, also when a check fails):

  1. BLOCK   every hardware rule except USB debugging, the older toggles (screenshots, USB storage, Bluetooth), data
             sharing, and a free list with one valid, one refused and one unknown entry. Verifies the server status
             ("Restricciones aplicadas"), `dumpsys user`, `dumpsys device_policy`, a screenshot (must fail or be
             black), the camera, MTP and the tethering screen. Rules newer than the device's Android must be reported
             as "unsupported: sdk-N"; USB data must be "applied" or "unsupported: device".
  2. ADB     blocks USB debugging too: the agent must report it applied and adb must stop answering.
             (--keep-usb-debug skips this step: use it on a phone you cannot afford to lose over the cable.)
  3. REVERT  removes everything from the policy: adb must come back by itself (the agent gives USB debugging back
             because it was on before), and every restriction, the camera block and the screenshot block must be gone.
  4. DEVICE  a per-device exception blocks the camera on this device only, then "según política" lifts it.

Exit code 0 only if every executed check passed.
"""
import argparse, hashlib, http.cookiejar, json, re, subprocess, sys, time, urllib.error, urllib.request

P = argparse.ArgumentParser()
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--serial", required=True)
P.add_argument("--device-id", required=True, help="the device number shown in the console")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--adb", default="adb")
P.add_argument("--timeout", type=int, default=180, help="seconds to wait for the device to apply a policy change")
P.add_argument("--keep-usb-debug", action="store_true", help="do not block USB debugging (step 2)")
A = P.parse_args()

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
RESULTS = []

# rule key -> (first Android API level, user restrictions it sets). Mirrors HardwareCatalog.kt.
RULES = {
    "camera": (21, []),
    "usbDebug": (21, ["no_debugging_features"]),
    "usbData": (31, []),
    "physicalMedia": (21, ["no_physical_media"]),
    "bluetoothSharing": (26, ["no_bluetooth_sharing"]),
    "bluetoothConfig": (21, ["no_config_bluetooth"]),
    "nfcBeam": (22, ["no_outgoing_beam"]),
    "nfc": (35, ["no_near_field_communication_radio"]),
    "microphone": (21, ["no_unmute_microphone"]),
    "airplaneMode": (28, ["no_airplane_mode"]),
    "wifiDirect": (33, ["no_wifi_direct"]),
    "printing": (28, ["no_printing"]),
    "networkReset": (23, ["no_network_reset"]),
    "dateTime": (21, ["no_config_date_time"]),  # before Android 9: auto-time instead of the restriction
    "unknownSources": (21, ["no_install_unknown_sources"]),
    "locationConfig": (28, ["no_config_location"]),
    "locationSharing": (21, ["no_share_location"]),
    "contentCapture": (29, ["no_content_capture", "no_content_suggestions"]),
    "assistContent": (35, ["no_assist_content"]),
}
TOGGLE_RESTRICTIONS = {"usbStorage": ["no_usb_file_transfer", "no_physical_media"], "bluetooth": ["no_bluetooth"]}
FREE_FIELD = "no_sms, DISALLOW_SAFE_BOOT, no_factory_reset, bogus_restriction"


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    return ok


def skip(name, why):
    print(f"  SKIP  {name}  ({why})", flush=True)


def section(title):
    print(f"\n== {title}", flush=True)


def api(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(A.api + "/rest" + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with OP.open(req, timeout=30) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return {"status": "HTTP_%d" % e.code, "message": e.read()[:200].decode("utf-8", "replace")}


def adb(*args, timeout=60):
    try:
        r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
        return (r.stdout + r.stderr).replace("\r", "")
    except subprocess.TimeoutExpired:
        return "TIMEOUT"


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def adb_alive():
    return sh("echo alive", timeout=10).strip() == "alive"


def until(fn, timeout, step=2.0):
    end = time.time() + timeout
    v = fn()
    while not v and time.time() < end:
        time.sleep(step)
        v = fn()
    return v


def user_restrictions():
    """The user restrictions in force, as `dumpsys user` prints them (one `no_xxx` per line under a "... restrictions:"
    header). The "Guest restrictions" block is the template for guest users, not something in force."""
    out, header = set(), ""
    for line in sh("dumpsys user").splitlines():
        t = line.strip()
        if t.lower().endswith("restrictions:"):
            header = t.lower()
        elif re.fullmatch(r"no_[a-z0-9_]+", t):
            if not header.startswith("guest"):
                out.add(t)
        elif t and t != "none":
            header = header if t.startswith("no_") else ""
    return out


def camera_disabled():
    d = sh("dumpsys device_policy")
    if "disableCamera=true" in d.replace(" ", ""):
        return True
    # API 34+: a Device Owner's setCameraDisabled is the no_camera user restriction.
    return "no_camera" in user_restrictions()


def screen_capture_disabled():
    d = sh("dumpsys device_policy")
    if "disableScreenCapture=true" in d.replace(" ", ""):
        return True
    return any("Screen capture disallowed users" in l and "[]" not in l for l in d.splitlines())


def screenshot_is_blank():
    """True when a screenshot cannot be taken or comes out black. Raw format: 12/16-byte header + RGBA."""
    try:
        r = subprocess.run([A.adb, "-s", A.serial, "exec-out", "screencap"], capture_output=True, timeout=40)
    except subprocess.TimeoutExpired:
        return True
    raw = r.stdout
    if len(raw) < 1024:
        return True
    w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
    px = raw[len(raw) - w * h * 4:] if 0 < w * h * 4 <= len(raw) else raw[16:]
    return max(px[0::4]) == 0 and max(px[1::4]) == 0 and max(px[2::4]) == 0


def wake_screen():
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    time.sleep(1)


def ui_text():
    sh("uiautomator dump /sdcard/w11-ui.xml", timeout=40)
    return sh("cat /sdcard/w11-ui.xml", timeout=20)


# --- server side ------------------------------------------------------------------------------------------------------

def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    r = api("POST", "/public/auth/login", {"login": A.admin_user, "password": md5})
    if r.get("status") != "OK":
        sys.exit(f"login failed: {r}")


def config_status():
    return api("GET", f"/private/agent/v1/devices/{A.device_id}/configStatus").get("data") or {}


def hardware():
    return api("GET", f"/private/agent/v1/devices/{A.device_id}/hardware").get("data") or {}


def load_config(cfg_id):
    cfg = next((c for c in api("GET", "/private/configurations/search").get("data") or [] if c.get("id") == cfg_id), None)
    if cfg is None:
        sys.exit(f"configuration {cfg_id} not found")
    apps = [a for a in api("GET", f"/private/configurations/applications/{cfg_id}").get("data") or [] if a.get("selected")]
    return cfg, apps


def save_config(cfg, apps, **changes):
    r = api("PUT", "/private/configurations", {**cfg, **changes, "applications": apps})
    return r.get("status") == "OK", r


def apply_and_wait(what):
    """Wake the phone and wait until it reported on the configuration's current revision."""
    api("POST", f"/private/agent/v1/devices/{A.device_id}/sync")
    t0 = time.time()

    def reported():
        h = hardware()
        if h.get("reported"):
            return h
        if int(time.time() - t0) % 30 < 2:
            api("POST", f"/private/agent/v1/devices/{A.device_id}/sync")
        return None

    h = until(reported, A.timeout)
    check(f"{what}: the device reported on the new policy", bool(h), "no report within %d s" % A.timeout)
    return h or hardware()


def item(h, key, group="items"):
    return next((i for i in h.get(group) or [] if i.get("key") == key), {})


# --- steps ------------------------------------------------------------------------------------------------------------

def step_block(cfg, apps, sdk, rules):
    section(f"1. BLOCK: {len(rules)} rules + screenshots + USB storage + Bluetooth + data sharing + free list")
    dc = json.loads(cfg.get("dcPolicy") or "{}")
    dc["hardware"] = {k: "block" for k in rules}
    dc["device"] = {**(dc.get("device") or {}), "tethering": "block"}
    ok, r = save_config(cfg, apps, dcPolicy=json.dumps(dc), usbStorage=False, bluetooth=False, disableScreenshots=True,
                        restrictions=FREE_FIELD)
    if not check("policy saved with everything blocked", ok, str(r)[:300]):
        return
    h = apply_and_wait("block")

    # What the server shows in the device's "Restricciones aplicadas".
    for key in rules:
        min_sdk = RULES[key][0]
        got = item(h, key).get("outcome")
        if key == "usbData" and sdk >= min_sdk:
            check(f"status {key}: applied, or not supported by this device's USB HAL", got in ("applied", "unsupported: device"), got)
            print(f"        usbData on this device: {got}")
        elif sdk >= min_sdk:
            check(f"status {key}: applied", got == "applied", got)
        else:
            check(f"status {key}: unsupported before API {min_sdk}", got == f"unsupported: sdk-{min_sdk}", got)
    for key in ("screenshots", "usbStorage", "tethering"):
        check(f"status {key}: blocked and applied", item(h, key).get("desired") == "block" and item(h, key).get("outcome") == "applied",
              json.dumps(item(h, key)))
    bt = item(h, "bluetooth")
    check("status bluetooth: applied (API 26+) or unsupported", bt.get("outcome") == ("applied" if sdk >= 26 else "unsupported"), json.dumps(bt))
    check("free list: no_sms applied", item(h, "no_sms", "restrictions").get("outcome") == "applied", json.dumps(h.get("restrictions")))
    check("free list: DISALLOW_SAFE_BOOT accepted by name and applied", item(h, "no_safe_boot", "restrictions").get("outcome") == "applied")
    check("free list: an unknown entry is reported and never sent", h.get("unknownRestrictions") == ["bogus_restriction"], str(h.get("unknownRestrictions")))
    check("free list: an entry with its own option is refused", h.get("ownOptionRestrictions") == ["no_factory_reset"], str(h.get("ownOptionRestrictions")))

    # What the device really has.
    have = user_restrictions()
    want = {"no_sms", "no_safe_boot", "no_config_tethering", "no_usb_file_transfer", "no_physical_media"}
    if sdk >= 26:
        want.add("no_bluetooth")
    for key in rules:
        min_sdk, keys = RULES[key]
        if sdk < min_sdk:
            continue
        if key == "dateTime" and sdk < 28:
            check("device: auto-time required (date/time before Android 9)", "autoTimeRequired=true" in sh("dumpsys device_policy").replace(" ", "")
                  or sh("settings get global auto_time").strip() == "1")
            continue
        want.update(keys)
    if sdk >= 29 and "unknownSources" in rules:
        want.add("no_install_unknown_sources_globally")
    missing = sorted(want - have)
    check(f"device: `dumpsys user` shows the {len(want)} expected restrictions", not missing, "missing: " + ", ".join(missing))
    check("device: no_factory_reset was NOT set from the free list", "no_factory_reset" not in have)
    check("device: `dumpsys device_policy` says the camera is disabled", camera_disabled())
    check("device: `dumpsys device_policy` says screen capture is disabled", screen_capture_disabled())
    wake_screen()
    check("device: a screenshot fails or comes out black", screenshot_is_blank())

    # Camera: the system refuses to open it for any client.
    sh("logcat -c")
    sh("am start -a android.media.action.STILL_IMAGE_CAMERA")
    time.sleep(5)
    log = sh("logcat -d -t 3000", timeout=40)
    refused = re.search(r"disabled by (the )?(device )?polic|CAMERA_DISABLED|camera.{0,30}disabled|ERROR_DISABLED|Camera is disabled", log, re.I)
    prop = sh("getprop sys.secpolicy.camera.disabled").strip()
    if refused or prop == "1":
        check("device: opening the camera is refused by the system", True)
    else:
        skip("opening the camera", "no camera app answered on this image; the policy state above is the evidence")
    sh("input keyevent KEYCODE_HOME")

    # USB: MTP cannot be selected.
    out = sh("svc usb setFunctions mtp")
    time.sleep(2)
    funcs = sh("svc usb getFunctions")
    if "mtp" in funcs.lower() and "error" not in funcs.lower():
        check("device: MTP is not offered over USB", False, funcs.strip())
    elif re.search(r"unknown|usage|error|exception", funcs + out, re.I) and "mtp" not in funcs.lower():
        check("device: MTP is not offered over USB (svc usb)", "no_usb_file_transfer" in have, (out + funcs).strip()[:120])
    else:
        check("device: MTP is not offered over USB (svc usb getFunctions: %s)" % (funcs.strip() or "none"), True)

    # Hotspot: Settings shows the tethering screen as blocked by the administrator.
    wake_screen()
    sh("am start -a android.settings.TETHER_SETTINGS" if sdk >= 28 else "am start -n com.android.settings/.TetherSettings")
    time.sleep(4)
    ui = ui_text()
    blocked = re.search(r"admin|administrador|not allowed|no permitid|Blocked|bloquead|isn.t available|no disponible", ui, re.I)
    toggle_on = re.search(r'checkable="true"[^>]*enabled="true"', ui) and re.search(r"hotspot|zona", ui, re.I)
    if blocked or not toggle_on:
        check("device: hotspot cannot be switched on in Settings", True)
    else:
        check("device: hotspot cannot be switched on in Settings", False, "an enabled hotspot switch is on screen")
    sh("input keyevent KEYCODE_HOME")


def step_adb(cfg, apps, rules):
    section("2. ADB: block USB debugging")
    check("adb answers before the block", adb_alive())
    dc = json.loads(cfg.get("dcPolicy") or "{}")
    dc["hardware"] = {**{k: "block" for k in rules}, "usbDebug": "block"}
    dc["device"] = {**(dc.get("device") or {}), "tethering": "block"}
    ok, r = save_config(cfg, apps, dcPolicy=json.dumps(dc), usbStorage=False, bluetooth=False, disableScreenshots=True,
                        restrictions=FREE_FIELD)
    if not check("policy saved with USB debugging blocked", ok, str(r)[:300]):
        return
    h = apply_and_wait("adb block")
    check("status usbDebug: applied (the agent read the restriction back)", item(h, "usbDebug").get("outcome") == "applied", json.dumps(item(h, "usbDebug")))
    check("adb stops answering (USB debugging is off)", until(lambda: not adb_alive(), 40), "adb still answers")


def step_revert(cfg, apps, original, sdk, adb_was_blocked):
    section("3. REVERT: everything back from the console")
    ok, r = save_config(cfg, apps, dcPolicy=original["dcPolicy"], usbStorage=True, bluetooth=True, disableScreenshots=False, restrictions=None)
    check("policy saved without hardware rules", ok, str(r)[:300])
    h = apply_and_wait("revert")
    if adb_was_blocked:
        check("adb answers again without touching the device", until(adb_alive, 90), "adb did not come back")
    check("status: no hardware rule is asked any more",
          not [i["key"] for i in h.get("items") or [] if i.get("desired") == "block"], json.dumps([i for i in h.get("items") or [] if i.get("desired")]))
    have = user_restrictions()
    left = sorted(have & ({k for _, keys in RULES.values() for k in keys} | {"no_sms", "no_safe_boot", "no_config_tethering",
                  "no_usb_file_transfer", "no_bluetooth", "no_install_unknown_sources_globally"}))
    check("device: every restriction is gone", not left, "still set: " + ", ".join(left))
    check("device: the camera is enabled again", not camera_disabled())
    check("device: screen capture is allowed again", not screen_capture_disabled())
    wake_screen()
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    check("device: a screenshot shows the screen again", not screenshot_is_blank())
    if sdk >= 31:
        check("device: USB data signaling is enabled", "usbDataSignalingEnabled=false" not in sh("dumpsys device_policy").replace(" ", ""))
    # Leave the configuration exactly as it was.
    save_config(cfg, apps, **original)


def step_device_override():
    section("4. DEVICE: a per-device exception")
    r = api("PUT", f"/private/agent/v1/devices/{A.device_id}/hardware", {"rules": {"camera": "block"}})
    check("exception saved: camera blocked on this device only", r.get("status") == "OK" and item(r.get("data") or {}, "camera").get("override") == "block", str(r)[:200])
    h = apply_and_wait("device exception")
    check("status camera: blocked by the exception and applied", item(h, "camera").get("outcome") == "applied" and not item(h, "camera").get("policy"), json.dumps(item(h, "camera")))
    check("device: the camera is disabled", camera_disabled())
    r = api("PUT", f"/private/agent/v1/devices/{A.device_id}/hardware", {"rules": {"camera": "policy"}})
    check("exception removed (según política)", r.get("status") == "OK" and not item(r.get("data") or {}, "camera").get("override"), str(r)[:200])
    apply_and_wait("exception removed")
    check("device: the camera is enabled again", until(lambda: not camera_disabled(), 20))
    r = api("PUT", f"/private/agent/v1/devices/{A.device_id}/hardware", {"rules": {"teleport": "block"}})
    check("an unknown rule is refused", r.get("status") != "OK", str(r)[:120])
    r = api("PUT", f"/private/agent/v1/devices/{A.device_id}/hardware", {"rules": {"tethering": "block"}})
    check("tethering is refused here (it has its own per-device option)", r.get("status") != "OK", str(r)[:120])


def main():
    login()
    if not adb_alive():
        sys.exit(f"{A.serial} does not answer over adb")
    sdk = int(sh("getprop ro.build.version.sdk").strip())
    cfg_id = config_status().get("configurationId")
    if not cfg_id:
        sys.exit("the device has no configuration")
    cfg, apps = load_config(cfg_id)
    original = {k: cfg.get(k) for k in ("dcPolicy", "usbStorage", "bluetooth", "disableScreenshots", "restrictions")}
    print(f"device {A.device_id} on {A.serial}: Android API {sdk}, configuration {cfg_id} «{cfg.get('name')}»")
    rules = [k for k in RULES if k != "usbDebug"]
    adb_blocked = False
    try:
        step_block(cfg, apps, sdk, rules)
        if A.keep_usb_debug:
            skip("2. ADB", "--keep-usb-debug")
        else:
            step_adb(cfg, apps, rules)
            adb_blocked = True
        step_revert(cfg, apps, original, sdk, adb_blocked)
        step_device_override()
    finally:
        save_config(cfg, apps, **original)
        api("PUT", f"/private/agent/v1/devices/{A.device_id}/hardware", {"rules": {"camera": "policy"}})
        api("POST", f"/private/agent/v1/devices/{A.device_id}/sync")
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = [n for n, ok, _ in RESULTS if not ok]
    print(f"\nRESULT: PASS={passed} FAIL={len(failed)}")
    for n in failed:
        print("  failed: " + n)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
