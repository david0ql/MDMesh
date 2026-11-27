#!/usr/bin/env python3
"""QR-provisioning matrix on emulators: runs Android's real managed provisioning with a QR payload, per AVD.

It starts from the point right after the camera reads the QR (see ProvisionIntent.java): the platform then joins
Wi-Fi, sets the clock, downloads the agent, verifies its signature checksum, installs it, makes it Device Owner and
calls the agent's provisioning callbacks - the same code a phone runs after a real scan. Userdebug images only.

  qr_matrix.py boot  --avd mdm_api30 --port 5600
  qr_matrix.py run   --serial emulator-5600 --payload payload.json --out out/api30 [--preinstall app.apk]
                     [--clock-back-years 6] [--wait-enrolled http://localhost:8101 --admin admin:admin]
  qr_matrix.py kill  --serial emulator-5600

`run` prints one line `RESULT <status> ...` and saves logcat + the screens it saw in --out. docs/TESTING.md has the
whole procedure.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

SDK = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
ADB = os.path.join(SDK, "platform-tools", "adb")
HERE = os.path.dirname(os.path.abspath(__file__))

# Buttons of the provisioning screens (English emulator images), most specific first.
ADVANCE = re.compile(
    r"^(accept\s*&\s*continue|accept and continue|accept|agree|next|continue|done|ok|got it|skip|set up|"
    r"allow|start|finish|i agree|wait|continuar|aceptar|siguiente|listo)$", re.I)  # "wait": an ANR dialog on a slow host
FAILURE = re.compile(
    r"(can.t set up|couldn.t set up|cannot set up|something went wrong|"
    r"reset (this )?device|couldn.t (download|install|connect)|blocked by play protect|harmful app|"
    r"isn.t allowed|not allowed|already set up|oops)", re.I)


def adb(serial, *args, timeout=60, check=False):
    try:
        # Three times the nominal limit: several emulators on one machine make every adb call slow.
        p = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=timeout * 3)
    except subprocess.TimeoutExpired:
        if check:
            raise
        return ""
    if check and p.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {p.stderr.strip()}")
    return (p.stdout or "") + (p.stderr or "")


def sh(serial, cmd, timeout=60):
    return adb(serial, "shell", cmd, timeout=timeout)


def boot(avd, port, wipe=True, timeout=600):
    serial = f"emulator-{port}"
    log_path = os.path.join(os.environ.get("QR_MATRIX_TMP", "/tmp"), f"{avd}.emu.log")
    log = open(log_path, "w")
    args = [os.path.join(SDK, "emulator", "emulator"), "-avd", avd, "-port", str(port), "-no-window", "-no-audio",
            "-no-snapshot", "-no-boot-anim"]
    if wipe:
        args.append("-wipe-data")
    proc = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    end = time.time() + timeout
    while time.time() < end:
        if proc.poll() is not None:  # the AVD is in use by someone else, or there is no disk space for it
            tail = open(log_path).read().strip().splitlines()[-1:]
            raise SystemExit(f"RESULT BOOT-FAILED {avd}: {' '.join(tail)[:200]}")
        try:
            if sh(serial, "getprop sys.boot_completed", timeout=10).strip() == "1":
                time.sleep(8)
                return serial
        except Exception:
            pass
        time.sleep(3)
    raise SystemExit(f"RESULT BOOT-FAILED {avd}")


def kill(serial):
    try:
        adb(serial, "emu", "kill", timeout=15)
    except Exception:
        pass
    for _ in range(30):
        if serial not in subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout:
            return
        time.sleep(1)
    subprocess.run(["pkill", "-f", f"-port {serial.split('-')[1]}"])


def build_helper(out_dir):
    dex = os.path.join(out_dir, "provision.dex")
    source = os.path.join(HERE, "ProvisionIntent.java")
    if os.path.exists(dex) and os.path.getmtime(dex) >= os.path.getmtime(source):
        return dex
    os.makedirs(out_dir, exist_ok=True)
    platforms = sorted(os.listdir(os.path.join(SDK, "platforms")))
    tools = sorted(os.listdir(os.path.join(SDK, "build-tools")))
    jar = os.path.join(SDK, "platforms", platforms[-1], "android.jar")
    javac = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac") if os.environ.get("JAVA_HOME") else "javac"
    subprocess.run([javac, "-source", "8", "-target", "8", "-nowarn", "-cp", jar, "-d", out_dir,
                    os.path.join(HERE, "ProvisionIntent.java")], check=True, capture_output=True)
    subprocess.run([os.path.join(SDK, "build-tools", tools[-1], "d8"), "--min-api", "23", "--output", out_dir,
                    os.path.join(out_dir, "ProvisionIntent.class")], check=True, capture_output=True)
    os.replace(os.path.join(out_dir, "classes.dex"), dex)
    return dex


def screen(serial):
    """(texts, clickable nodes [(label, x, y)]) of the current screen; empty when the dump fails."""
    sh(serial, "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1", timeout=40)
    xml = sh(serial, "cat /data/local/tmp/ui.xml", timeout=20)
    texts, clicks = [], []
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):])
    except Exception:
        return texts, clicks
    for n in root.iter("node"):
        label = (n.get("text") or n.get("content-desc") or "").strip()
        if label:
            texts.append(label)
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if label and m and (n.get("clickable") == "true" or (n.get("class") or "").endswith("Button")):
            x1, y1, x2, y2 = map(int, m.groups())
            clicks.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return texts, clicks


def setup_state(serial):
    return (sh(serial, "settings get secure user_setup_complete").strip()
            + sh(serial, "settings get global device_provisioned").strip())


def wait_network(serial, timeout=60):
    """After the framework restart the emulator's network takes a while to come back; a phone has its Wi-Fi up (from
    the QR or the wizard) before Android starts the download, so wait for the same here."""
    end = time.time() + timeout
    while time.time() < end:
        if re.search(r"VALIDATED|state: CONNECTED/CONNECTED", sh(serial, "dumpsys connectivity | head -60", timeout=30)) \
                and "1 received" in sh(serial, "ping -c 1 -W 3 10.0.2.2 2>&1", timeout=15):
            return True
        time.sleep(4)
    print("  note: the emulator has no network after the restart", flush=True)
    return False


def unprovision(serial):
    """Put a booted emulator back to "setup wizard not finished", the state a phone is in when it scans the QR.

    The two settings are not enough: DevicePolicyManagerService latches "user setup complete" in its own policy file
    and then refuses Device-Owner provisioning (CODE_USER_SETUP_COMPLETED, shown as "Device already provisioned").
    So the framework is stopped, that file removed (a fresh emulator has nothing else in it) and started again - with
    the apps that mark an emulator as set up on every boot disabled, or they latch it again.
    """
    if int(sh(serial, "getprop ro.build.version.sdk").strip() or 0) >= 26:
        # Android 8+: ask the service to read the setting again (a platform test API) - no restart, network intact.
        for _ in range(3):
            sh(serial, "settings put secure user_setup_complete 0")
            sh(serial, "settings put global device_provisioned 0")
            out = sh(serial, "CLASSPATH=/data/local/tmp/provision.dex app_process / ProvisionIntent --reset-setup")
            time.sleep(2)
            if "re-read" in out and setup_state(serial) == "00":
                return
            print("  note: reset-setup said: " + out.strip()[-200:], flush=True)
        raise SystemExit("RESULT HARNESS-FAILED could not return the emulator to the pre-setup state")
    for attempt in range(3):
        # AOSP's "Provision" and the emulator images' setup wizard finish "setup" by themselves on boot.
        sh(serial, "pm disable com.android.provision")
        sh(serial, "pm disable com.google.android.sdksetup")  # the Google-APIs images' own "mark as set up" app
        sh(serial, "pm disable-user --user 0 com.google.android.setupwizard")
        if attempt:
            # Some images re-enable their wizard on boot: take it away from the user altogether.
            sh(serial, "pm uninstall -k --user 0 com.google.android.setupwizard")
            sh(serial, "pm uninstall -k --user 0 com.android.provision")
        sh(serial, "settings put secure user_setup_complete 0")
        sh(serial, "settings put global device_provisioned 0")
        sh(serial, "stop")
        time.sleep(3)
        sh(serial, "rm -f /data/system/device_policies.xml /data/system/device_policies.xml.bak "
                   "/data/system/device_policies.xml.reservecopy")
        # A full reboot, not just `start`: on Android 12+ a restarted framework comes back without any network.
        try:
            adb(serial, "reboot", timeout=30)
        except Exception:
            pass
        time.sleep(15)
        try:
            adb(serial, "wait-for-device", timeout=180)
            adb(serial, "root", timeout=30)
            time.sleep(4)
            adb(serial, "wait-for-device", timeout=60)
        except Exception:
            pass
        end = time.time() + 300
        while time.time() < end:
            time.sleep(4)
            try:
                if (sh(serial, "getprop sys.boot_completed", timeout=10).strip() == "1"
                        and "Current Device Policy" in sh(serial, "dumpsys device_policy", timeout=20)):
                    break
            except Exception:
                pass
        time.sleep(12)
        state = setup_state(serial)
        if state == "01":
            # Only the global flag came back (the system sets it when no setup wizard is left to do so). It has no
            # latch behind it: clearing it on the live system is enough.
            sh(serial, "settings put global device_provisioned 0")
            time.sleep(3)
            state = setup_state(serial)
        if state == "00":
            return
        print(f"  note: something finished setup again after the restart (state {state}); retry {attempt + 1}", flush=True)
    raise SystemExit("RESULT HARNESS-FAILED could not return the emulator to the pre-setup state")


def device_owner(serial):
    out = sh(serial, "dumpsys device_policy", timeout=40)
    m = re.search(r"Device Owner:\s*\n?\s*admin=ComponentInfo\{([^}]+)\}", out)
    return m.group(1) if m else None


_COOKIES = urllib.request.build_opener(urllib.request.HTTPCookieProcessor())


def api(base, path, token=None, body=None, method=None):
    """Console API call; the session cookie of login() rides along."""
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 method=method or ("POST" if body is not None else "GET"))
    req.add_header("Content-Type", "application/json")
    with _COOKIES.open(req, timeout=20) as r:
        return json.loads(r.read().decode() or "{}")


def login(base, creds):
    user, password = creds.split(":", 1)
    md5 = hashlib.md5(password.encode()).hexdigest().upper()
    r = api(base, "/rest/public/auth/login", body={"login": user, "password": md5})
    if r.get("status") != "OK":
        raise RuntimeError("console login failed: " + str(r.get("message")))
    return "session"


def run(a):
    serial = a.serial
    os.makedirs(a.out, exist_ok=True)
    dex = build_helper(os.path.join(os.environ.get("QR_MATRIX_TMP", "/tmp"), "qr-matrix-helper"))
    sdk = sh(serial, "getprop ro.build.version.sdk").strip()
    release = sh(serial, "getprop ro.build.version.release").strip()
    print(f"device {serial}: Android {release} (API {sdk})", flush=True)
    adb(serial, "root")
    time.sleep(4)
    adb(serial, "wait-for-device", timeout=60)
    if a.preinstall:
        print(adb(serial, "install", "-r", "-t", a.preinstall, timeout=180).strip().splitlines()[-1], flush=True)
    if a.clock_back_years:
        # A phone straight out of the box with a dead clock and no network time: the case the QR's local time covers.
        sh(serial, "settings put global auto_time 0")
        past = time.localtime(time.time() - a.clock_back_years * 365 * 86400)
        stamp = time.strftime("%m%d%H%M%Y.%S", past)
        print("clock set back:", sh(serial, f"date {stamp}").strip(), flush=True)
    adb(serial, "push", dex, "/data/local/tmp/provision.dex")
    unprovision(serial)
    wait_network(serial)
    adb(serial, "push", dex, "/data/local/tmp/provision.dex")  # again: a reboot (Android 7) may have cleared it
    adb(serial, "push", a.payload, "/data/local/tmp/payload.json")
    adb(serial, "logcat", "-c")
    started = time.time()
    print(sh(serial, "CLASSPATH=/data/local/tmp/provision.dex app_process / ProvisionIntent "
                     "/data/local/tmp/payload.json").strip(), flush=True)

    payload = json.load(open(a.payload))
    component = payload.get("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", "")
    package = component.split("/")[0]
    seen, status, reason, owner = [], "TIMEOUT", "", None
    owner_at = None
    end = time.time() + a.timeout
    while time.time() < end:
        texts, clicks = screen(serial)
        joined = " | ".join(texts)
        if joined and (not seen or seen[-1] != joined):
            seen.append(joined)
            print(f"  [{int(time.time() - started):4d}s] {joined[:230]}", flush=True)
        fail = next((t for t in texts if FAILURE.search(t)), None)
        if fail and not owner:
            status, reason = "PROVISIONING-FAILED", joined[:400]
            break
        if not owner:
            owner = device_owner(serial)
            if owner:
                owner_at = time.time()
                print(f"  [{int(owner_at - started):4d}s] Device Owner = {owner}", flush=True)
        target = next((c for c in clicks if ADVANCE.match(c[0])), None)
        if target:
            sh(serial, f"input tap {target[1]} {target[2]}")
            time.sleep(2)
            continue
        if owner and time.time() - owner_at > a.settle:
            status = "DEVICE-OWNER"
            break
        time.sleep(3)
    if owner and status == "TIMEOUT":
        status = "DEVICE-OWNER"

    enrolled = ""
    if status == "DEVICE-OWNER" and a.wait_enrolled:
        if a.pause_cmd:
            # Server outage while the phone enrolls: down now, back after --resume-after seconds.
            print("  server down: " + subprocess.run(a.pause_cmd, shell=True, capture_output=True, text=True).stdout.strip(), flush=True)
            subprocess.Popen(f"sleep {a.resume_after}; {a.resume_cmd}", shell=True, start_new_session=True)
        status, enrolled = wait_enrolled(a, serial, payload)
        if status == "ENROLLED" and a.replay_token:
            status, more = replay_token(a, serial, payload, package)
            enrolled += " " + more
        if status != "ENROLLED":
            # Device Owner without an enrollment: the agent must be saying so on screen, with a way out.
            texts, _ = screen(serial)
            end_screen = " | ".join(texts)
            seen.append("END: " + end_screen)
            prompt = bool(re.search(r"scan the console|escanear el qr de la consola", end_screen, re.I))
            notif = bool(re.search(r"not enrolled yet|no está inscrito", sh(serial, "dumpsys notification --noredact", timeout=40), re.I))
            print(f"  end screen: {end_screen[:300]}", flush=True)
            enrolled += f" agent_prompt_on_screen={'yes' if prompt else 'no'} agent_notification={'yes' if notif else 'no'}"
    log = adb(serial, "logcat", "-d", timeout=120)
    open(os.path.join(a.out, "logcat.txt"), "w").write(log)
    open(os.path.join(a.out, "screens.txt"), "w").write("\n".join(seen))
    keep = re.compile(r"ManagedProvisioning|DevicePolicyManager|SetupWizard|DownloadManager|AdminReceiver|"
                      r"CheckInWorker|CheckInService|Enroll|PackageInstaller|Provision", re.I)
    relevant = [line for line in log.splitlines() if keep.search(line)]
    open(os.path.join(a.out, "provisioning.log"), "w").write("\n".join(relevant))
    if status != "ENROLLED" and status != "DEVICE-OWNER":
        errs = [line for line in relevant if re.search(r" E |error|fail|exception|denied", line, re.I)][-12:]
        print("  --- last provisioning errors in logcat ---")
        for line in errs:
            print("  " + line[:260])
    prefs = sh(serial, f"run-as {package} ls shared_prefs 2>/dev/null").split() if package.endswith(".debug") else []
    flow = sh(serial, f"run-as {package} cat shared_prefs/mdm_enroll_status.xml 2>/dev/null") if prefs else ""
    path = re.search(r'name="path">([^<]+)<', flow)
    print(f"RESULT {status} api={sdk} android={release} owner={owner or '-'} "
          f"path={path.group(1) if path else '?'} {enrolled} {('reason=' + reason) if reason else ''}".strip(), flush=True)
    return 0 if status in ("ENROLLED", "DEVICE-OWNER") else 1


def finish_setup_wizard(serial):
    """What a phone's setup wizard does when it ends: ask Android to finalize provisioning, mark the device set up.

    Up to Android 11 the agent only gets the QR's token then (PROFILE_PROVISIONING_COMPLETE /
    PROVISIONING_SUCCESSFUL are sent on finalization), and on Android 12+ ADMIN_POLICY_COMPLIANCE is.
    """
    print("  " + sh(serial, "am start -a android.app.action.PROVISION_FINALIZATION 2>&1").strip()[-160:], flush=True)
    time.sleep(3)
    sh(serial, "settings put secure user_setup_complete 1")
    sh(serial, "settings put global device_provisioned 1")


def replay_token(a, serial, payload, package):
    """The answer to the enroll call never reached the phone: the server consumed the single-use token and created
    the device, the agent kept nothing. Reproduced by deleting the agent's stored identity (debug build) and
    restarting it: it enrolls again with the same token and must get the SAME device back."""
    token = payload["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]["com.dallycontrol.ENROLL_TOKEN"]
    before = api(a.wait_enrolled, "/rest/private/agent/v1/token/status?token=" + token).get("data") or {}
    oks = len([x for x in before.get("attempts", []) if x.get("outcome") == "ok"]) \
        + sum(x.get("count", 1) - 1 for x in before.get("attempts", []) if x.get("outcome") == "ok")
    sh(serial, f"am force-stop {package}")
    print("  identity removed: " + sh(serial, f"run-as {package} rm -f files/datastore/mdm_identity.preferences_pb; echo done").strip(), flush=True)
    sh(serial, f"am start -n {package}/com.dallycontrol.agent.MainActivity")
    end = time.time() + 120
    while time.time() < end:
        time.sleep(4)
        st = api(a.wait_enrolled, "/rest/private/agent/v1/token/status?token=" + token).get("data") or {}
        now = sum(x.get("count", 1) for x in st.get("attempts", []) if x.get("outcome") == "ok")
        refused = [x["reason"] for x in st.get("attempts", []) if x.get("outcome") == "failed"]
        if now > oks:
            same = st.get("deviceNumber") == before.get("deviceNumber")
            ident = sh(serial, f"run-as {package} ls files/datastore/").split()
            back = "mdm_identity.preferences_pb" in ident
            return ("ENROLLED" if same and back else "REPLAY-FAILED",
                    f"token_replay=accepted same_device={'yes' if same else 'no'} credentials_stored={'yes' if back else 'no'}")
        if refused:
            return "REPLAY-FAILED", f"token_replay=refused({refused[0]})"
    return "REPLAY-FAILED", "token_replay=no-second-enroll"


def wait_enrolled(a, serial, payload):
    """The device must appear in the console. Returns (status, details)."""
    token = payload["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]["com.dallycontrol.ENROLL_TOKEN"]
    jwt = login(a.wait_enrolled, a.admin)
    started = time.time()
    end = started + a.enroll_timeout
    last, finalized = "", False
    while time.time() < end:
        try:
            st = api(a.wait_enrolled, "/rest/private/agent/v1/token/status?token=" + token, token=jwt).get("data") or {}
            last = json.dumps(st)
            if st.get("deviceNumber"):
                ok = [x for x in st.get("attempts", []) if x.get("outcome") == "ok"]
                return "ENROLLED", (f"device={st['deviceNumber']} configuration={st.get('configurationName')} "
                                    f"via={ok[0].get('path') if ok else '?'} tries={ok[0].get('tries') if ok else '?'} "
                                    f"wizard_finished_first={'yes' if finalized else 'no'} "
                                    f"seconds={int(time.time() - started)}")
        except Exception as e:  # the server may be down on purpose in a retry scenario
            last = str(e)
        if not finalized and time.time() - started > a.finalize_after:
            # Not enrolled by itself: now do what the setup wizard does at its end, as on a phone.
            finish_setup_wizard(serial)
            finalized = True
        # The agent's own status screen (Android 12+ shows it inside the wizard): leave it running, never skip it.
        time.sleep(4)
    return "ENROLL-TIMEOUT", "last=" + last[:200]


SLOTS = "/private/tmp/mdm-emu-slots"
SLOT_PORT = {1: 5600, 2: 5610, 3: 5620, 4: 5630}
# QR_MATRIX_SLOTS=4 keeps a run on a reserved turn; the default is the three shared ones.
SLOTS_USED = [int(x) for x in os.environ.get("QR_MATRIX_SLOTS", "1,2,3").split(",") if x.strip()]


def take_slot(owner):
    """Shared emulator turns on this machine (see the README in SLOTS); without that directory: port 5600."""
    if not os.path.isdir(SLOTS):
        return None, 5600
    while True:
        for n in SLOTS_USED:
            port = SLOT_PORT[n]
            try:
                os.mkdir(f"{SLOTS}/slot{n}")
            except OSError:
                continue
            open(f"{SLOTS}/slot{n}/owner", "w").write(owner + "\n")
            return n, port
        time.sleep(5)


def release_slot(n):
    if n is None:
        return
    try:
        os.remove(f"{SLOTS}/slot{n}/owner")
    except OSError:
        pass
    try:
        os.rmdir(f"{SLOTS}/slot{n}")
    except OSError:
        pass


def clean_avd(avd):
    """Free the disk an AVD's data takes (hundreds of MB each); every run wipes it anyway. Keeps config.ini."""
    import shutil
    d = os.path.expanduser(f"~/.android/avd/{avd}.avd")
    for name in os.listdir(d) if os.path.isdir(d) else []:
        if name == "config.ini":
            continue
        path = os.path.join(d, name)
        shutil.rmtree(path, ignore_errors=True) if os.path.isdir(path) else os.remove(path)


def matrix(a):
    """One clean emulator per AVD, each in its own turn: boot, provision, record, shut down."""
    template = open(a.payload).read()
    results = []
    for avd in a.avds:
        slot, port = take_slot(a.owner)
        serial = f"emulator-{port}"
        line = f"RESULT BOOT-FAILED {avd}"
        try:
            kill(serial)
            out = os.path.join(a.out, avd)
            os.makedirs(out, exist_ok=True)
            payload = template
            if a.mint:
                jwt = login(a.mint, a.admin)
                tok = api(a.mint, "/rest/private/agent/v1/token", token=jwt, body={})["data"]["token"]
                payload = payload.replace("${TOKEN12}", tok[:12]).replace("${TOKEN}", tok)
            if "${NOW}" in payload:
                payload = payload.replace('"${NOW}"', str(int(time.time() * 1000)))
            pfile = os.path.join(out, "payload.json")
            open(pfile, "w").write(payload)
            print(f"=== {avd} on {serial}", flush=True)
            boot(avd, port)
            cmd = [sys.executable, os.path.abspath(__file__), "run", "--serial", serial, "--payload", pfile,
                   "--out", out, "--timeout", str(a.timeout)] + a.run_args
            if a.mint:
                cmd += ["--wait-enrolled", a.mint, "--admin", a.admin]
            p = subprocess.run(cmd, capture_output=True, text=True)
            open(os.path.join(out, "run.txt"), "w").write(p.stdout + p.stderr)
            print(p.stdout[-3000:], flush=True)
            found = [x for x in p.stdout.splitlines() if x.startswith("RESULT")]
            line = f"{avd}: " + (found[-1] if found else "RESULT NO-RESULT " + p.stderr[-300:])
        except SystemExit as e:
            line = f"{avd}: {e}"
        except Exception as e:
            line = f"{avd}: RESULT ERROR {e}"
        finally:
            kill(serial)
            release_slot(slot)
            if a.clean_avd:
                clean_avd(avd)
        results.append(line)
        open(os.path.join(a.out, "summary.txt"), "w").write("\n".join(results) + "\n")
    print("\n".join(["", "==== SUMMARY ===="] + results), flush=True)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("boot"); b.add_argument("--avd", required=True); b.add_argument("--port", type=int, required=True)
    b.add_argument("--keep-data", action="store_true")
    k = sub.add_parser("kill"); k.add_argument("--serial", required=True)
    r = sub.add_parser("run")
    r.add_argument("--serial", required=True); r.add_argument("--payload", required=True)
    r.add_argument("--out", required=True); r.add_argument("--preinstall")
    r.add_argument("--clock-back-years", type=int, default=0)
    r.add_argument("--timeout", type=int, default=420); r.add_argument("--settle", type=int, default=25)
    r.add_argument("--wait-enrolled"); r.add_argument("--admin", default="admin:admin")
    r.add_argument("--enroll-timeout", type=int, default=170)
    r.add_argument("--pause-cmd", help="shell command that takes the server down once the phone is Device Owner")
    r.add_argument("--resume-cmd", help="shell command that brings it back")
    r.add_argument("--resume-after", type=int, default=75)
    r.add_argument("--replay-token", action="store_true",
                   help="after enrolling, drop the agent's identity and make it enroll again with the same token")
    r.add_argument("--finalize-after", type=int, default=45,
                   help="seconds to wait for an enrollment without the setup wizard's finalization before doing it")
    m = sub.add_parser("matrix")
    m.add_argument("--payload", required=True, help="payload JSON; ${TOKEN} and \"${NOW}\" are filled per device")
    m.add_argument("--out", required=True); m.add_argument("--owner", default="qr-matrix")
    m.add_argument("--mint", help="console base URL: mint a token per device and wait until it enrolls")
    m.add_argument("--admin", default="admin:admin"); m.add_argument("--timeout", type=int, default=420)
    m.add_argument("--clean-avd", action="store_true", help="delete each AVD's data after its run (frees disk)")
    m.add_argument("--run-args", nargs=argparse.REMAINDER, default=[])
    m.add_argument("avds", nargs="+")
    a = p.parse_args()
    if a.cmd == "matrix":
        return matrix(a)
    if a.cmd == "boot":
        print(boot(a.avd, a.port, wipe=not a.keep_data))
    elif a.cmd == "kill":
        kill(a.serial)
    else:
        sys.exit(run(a))


if __name__ == "__main__":
    main()
