#!/usr/bin/env python3
"""Server-side enrollment checks against a running dev stack (no phone needed).

  enroll_api_check.py http://localhost:8101 [admin:admin]

Covers what an emulator cannot show: every emulator of a release has the same serial and IMEI, so the "same phone
after a factory reset" cases are driven here with made-up identities.
"""
import json
import sys
import urllib.request
import uuid

sys.path.insert(0, __import__("os").path.dirname(__file__))
import qr_matrix as q  # noqa: E402

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8101"
ADMIN = sys.argv[2] if len(sys.argv) > 2 else "admin:admin"
failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (("  -> " + str(detail)) if detail and not ok else ""))
    if not ok:
        failures.append(name)


def enroll(token, hw, serial=None, imei=None, sdk=29, diagnostics=None):
    body = {"protocolVersion": "1.1", "enrollToken": token, "hardwareId": hw,
            "agent": {"version": "0.9.9-test", "package": "com.dallycontrol.agent"},
            "device": {"id": "", "androidSdkInt": sdk, "androidRelease": "10", "manufacturer": "TestCo",
                       "model": "Model-" + hw, "isDeviceOwner": True},
            "capabilities": {}}
    if serial:
        body["serial"] = serial
    if imei:
        body["imei"] = imei
    if diagnostics:
        body["diagnostics"] = diagnostics
    req = urllib.request.Request(BASE + "/rest/public/agent/v1/enroll", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())


def mint():
    return q.api(BASE, "/rest/private/agent/v1/token", body={})["data"]["token"]


q.login(BASE, ADMIN)
run = uuid.uuid4().hex[:8].upper()
serial = "SN" + run + "A1"
imei = "35" + str(int(run, 16) % 10**13).zfill(13)

# 1. first enroll, then the same phone asks again (the answer was lost): same device, not "token used"
t1 = mint()
a = enroll(t1, "hw-" + run + "-1", serial)
check("first enroll succeeds", a["status"] == "OK", a)
dev = a["data"]["deviceId"]
b = enroll(t1, "hw-" + run + "-1", serial)
check("same phone retries with its single-use token", b["status"] == "OK" and b["data"]["deviceId"] == dev, b)
check("the retry issues a working secret", b["data"].get("deviceSecret") and b["data"]["deviceSecret"] != a["data"]["deviceSecret"])

# 2. the phone was reset below Android 12 (hardware id changed) and scans the same QR again
c = enroll(t1, "hw-" + run + "-2", serial)
check("same serial with a new hardware id reuses token and device", c["status"] == "OK" and c["data"]["deviceId"] == dev, c)

# 3. another phone cannot use that token
d = enroll(t1, "hw-" + run + "-other", "SN" + run + "ZZ")
check("another phone gets token.used", d["status"] == "ERROR" and d["message"] == "error.agent.token.used", d)

# 4. re-enrollment with a NEW token after a reset: de-dup by serial, then by IMEI
t2 = mint()
e = enroll(t2, "hw-" + run + "-3", serial)
check("new token + same serial lands on the same device (no duplicate)", e["status"] == "OK" and e["data"]["deviceId"] == dev, e)
t3 = mint()
f = enroll(t3, "hw-" + run + "-i1", None, imei)
t4 = mint()
g = enroll(t4, "hw-" + run + "-i2", None, imei)
check("new token + same IMEI lands on the same device", g["status"] == "OK" and g["data"]["deviceId"] == f["data"]["deviceId"], g)

# 5. placeholder identities never merge two phones
t5, t6 = mint(), mint()
h = enroll(t5, "hw-" + run + "-p1", "unknown", "000000000000000")
i = enroll(t6, "hw-" + run + "-p2", "unknown", "000000000000000")
check("placeholder serial/IMEI do not merge phones", h["data"]["deviceId"] != i["data"]["deviceId"])
t7, t8 = mint(), mint()
j = enroll(t7, "hw-" + run + "-e1", "EMULATOR36X1X8X0", "358240051111110")
k = enroll(t8, "hw-" + run + "-e2", "EMULATOR36X1X8X0", "358240051111110")
check("emulator serial/IMEI do not merge devices", j["data"]["deviceId"] != k["data"]["deviceId"])

# 6. an agent of 0.9.8 (no serial, no diagnostics) still enrolls and still retries by hardware id
t9 = mint()
l1 = enroll(t9, "hw-" + run + "-old")
l2 = enroll(t9, "hw-" + run + "-old")
check("old agent enrolls and retries by hardware id", l1["status"] == "OK" and l2["status"] == "OK"
      and l1["data"]["deviceId"] == l2["data"]["deviceId"])

# 7. token status + the attempts log
st = q.api(BASE, "/rest/private/agent/v1/token/status?token=" + t1)["data"]
check("token status names the enrolled device", st["used"] and st["deviceNumber"] == dev, st)
check("token status lists its attempts", any(x["reason"] == "token.used" for x in st["attempts"])
      and any(x["outcome"] == "ok" for x in st["attempts"]), st["attempts"])
m = enroll("not-a-token-" + run, "hw-" + run + "-bad", diagnostics={"path": "qr-legacy", "attempts": 3,
           "lastError": "clock", "lastErrorDetail": "CertificateNotYetValidException"})
check("unknown token is refused", m["status"] == "ERROR" and m["message"] == "error.agent.token.invalid", m)
rows = q.api(BASE, "/rest/private/agent/v1/enroll/attempts?limit=200")["data"]
mine = [r for r in rows if (r.get("hardwareId") or "").startswith("hw-" + run)]
used = [r for r in mine if r["reason"] == "token.used"]
check("the refused attempt is in the log with maker, model and Android",
      bool(used) and used[0]["manufacturer"] == "TestCo" and used[0]["androidRelease"] == "10" and used[0]["sdkInt"] == 29, used)
check("the phone's own failure before reaching the server is noted",
      any(r["outcome"] == "note" and r["reason"] == "device.clock" for r in mine), [r["reason"] for r in mine])
check("an unknown-token attempt is logged", any(r["reason"] == "token.invalid" and r.get("path") == "qr-legacy" for r in mine))

# 8. the tracked agent download: a note in the log, then a redirect to the published APK
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


t10 = mint()
opener = urllib.request.build_opener(NoRedirect)
req = urllib.request.Request(BASE + "/rest/public/agent/v1/apk?t=" + t10[:12] + "&p=%2Ffiles%2Fagent.apk", headers={
    "User-Agent": "AndroidDownloadManager/11 (Linux; U; Android 11; SM-A125M Build/RP1A.200720.012)"})
try:
    opener.open(req, timeout=20)
    check("tracked download redirects", False, "no redirect")
except urllib.error.HTTPError as err:
    loc = err.headers.get("Location", "")
    check("tracked download redirects to the APK", err.code == 302 and loc.endswith("/files/agent.apk"), (err.code, loc))
st = q.api(BASE, "/rest/private/agent/v1/token/status?token=" + t10)["data"]
dl = [x for x in st["attempts"] if x["outcome"] == "download"]
check("the download is in the log with model and Android from the User-Agent",
      bool(dl) and dl[0].get("model") == "SM-A125M" and dl[0].get("androidRelease") == "11", st["attempts"])
try:
    opener.open(BASE + "/rest/public/agent/v1/apk?t=" + t10[:12] + "&p=%2F%2Fevil.example%2Fa.apk", timeout=20)
    check("tracked download refuses other targets", False)
except urllib.error.HTTPError as err:
    check("tracked download refuses other targets", err.code == 400, err.code)

# 9. a Device Owner that never got its token reports itself; the console counts it until it enrolls
before = q.api(BASE, "/rest/private/agent/v1/enroll/attempts/summary")["data"]
body = {"protocolVersion": "1.1", "enrollToken": "", "hardwareId": "hw-" + run + "-orphan",
        "agent": {"version": "0.9.9-test", "package": "com.dallycontrol.agent"},
        "device": {"id": "", "androidSdkInt": 28, "androidRelease": "9", "manufacturer": "TestCo", "model": "Orphan",
                   "isDeviceOwner": True}, "capabilities": {}, "diagnostics": {"path": "qr-legacy", "attempts": 0}}
req = urllib.request.Request(BASE + "/rest/public/agent/v1/enroll/report", data=json.dumps(body).encode(),
                             headers={"Content-Type": "application/json"})
with urllib.request.urlopen(req, timeout=20) as r:
    check("the report endpoint answers OK and enrolls nothing", json.loads(r.read().decode())["status"] == "OK")
rows = q.api(BASE, "/rest/private/agent/v1/enroll/attempts?limit=200")["data"]
orphan = [r for r in rows if r.get("hardwareId") == "hw-" + run + "-orphan"]
check("the log lists the Device Owner without a token, once", len(orphan) == 1 and orphan[0]["reason"] == "device.no-token"
      and orphan[0]["model"] == "Orphan", orphan)
after = q.api(BASE, "/rest/private/agent/v1/enroll/attempts/summary")["data"]
check("the counter of Device Owners without a code goes up", after["ownerWithoutToken"] == before["ownerWithoutToken"] + 1, (before, after))
check("it is counted among the phones still not enrolled", after["unresolvedDevices"] >= before["unresolvedDevices"] + 1, (before, after))
t11 = mint()
enroll(t11, "hw-" + run + "-orphan")
done = q.api(BASE, "/rest/private/agent/v1/enroll/attempts/summary")["data"]
check("and back down once that phone enrolls", done["ownerWithoutToken"] == before["ownerWithoutToken"], (before, done))

print("\n%d failed" % len(failures))
sys.exit(1 if failures else 0)
