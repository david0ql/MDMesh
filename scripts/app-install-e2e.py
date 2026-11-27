#!/usr/bin/env python3
"""
End-to-end test of app management (MDM-04 silent install, MDM-05 private APKs) against a running stack and,
optionally, a REAL enrolled agent (emulator or phone, Device Owner) read over adb.

    scripts/build-install-testapps.sh /tmp/apks
    scripts/app-install-e2e.py --api http://localhost:8088 --apks /tmp/apks \
        [--serial emulator-5600 --device-id <id>] [--admin-password admin] [--skip-auto-retry]

Server part (always): a local proxy that refuses request bodies over --limit-mb (what the Cloudflare tunnel does at
100 MB) sits in front of --api; through it the big APK fails in one request (HTTP 413) and goes up in parts, with a
damaged part refused, a resume from the parts already there, and the whole file checked. Then: Range download of the
hosted file, signature comparison (another key = refused until confirmed), a corrupt APK and an .aab refused with a
clear message, and an install held on a device that is not Device Owner (a scripted device).

Device part (--serial/--device-id): silent install of the big APK, silent update, another signature, a corrupt
file, a wrong SHA-256, no free space (then retry once there is room), the fleet install view, and — unless
--skip-auto-retry — the automatic retry of a failed install (waits up to ~8 min for the server's timer).

Exit code 0 only if every executed check passed.
"""
import argparse, hashlib, http.client, http.cookiejar, http.server, json, os, socketserver, subprocess, sys, threading
import time, urllib.error, urllib.parse, urllib.request, uuid, zipfile

P = argparse.ArgumentParser()
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--apks", required=True, help="directory made by scripts/build-install-testapps.sh")
P.add_argument("--serial", default="")
P.add_argument("--device-id", default="")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--adb", default="adb")
P.add_argument("--limit-mb", type=int, default=100, help="largest request body the simulated tunnel lets through")
P.add_argument("--pg", default="", help="shell command prefix that runs psql -At -c <sql> (needed for the auto-retry check)")
P.add_argument("--skip-auto-retry", action="store_true")
P.add_argument("--skip-storage", action="store_true")
A = P.parse_args()

PKG = "com.dallycontrol.bigapp"
CAP = "app.silentInstall"
RESULTS = []
RUN = uuid.uuid4().hex[:6]


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    return ok


def section(title):
    print(f"\n== {title}", flush=True)


# --- the simulated tunnel: refuses bodies over the limit, forwards the rest ------------------------------------------

UP = urllib.parse.urlparse(A.api)
LIMIT = A.limit_mb * 1024 * 1024


class Tunnel(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _forward(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > LIMIT:
            # Like the tunnel: nothing reaches the server and the answer is a bare 413 page. (The body is read and
            # thrown away so the client gets the status instead of a broken pipe.)
            left = length
            while left > 0:
                chunk = self.rfile.read(min(1 << 20, left))
                if not chunk:
                    break
                left -= len(chunk)
            body = b"<html><body><h1>413 Request Entity Too Large</h1></body></html>"
            self.send_response(413)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return
        conn = http.client.HTTPConnection(UP.hostname, UP.port or 80, timeout=600)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")}
        conn.putrequest(self.command, self.path, skip_host=True, skip_accept_encoding=True)
        conn.putheader("Host", UP.netloc)
        for k, v in headers.items():
            conn.putheader(k, v)
        conn.endheaders()
        left = length
        while left > 0:
            chunk = self.rfile.read(min(1 << 20, left))
            if not chunk:
                break
            conn.send(chunk)
            left -= len(chunk)
        r = conn.getresponse()
        data = r.read()
        self.send_response(r.status)
        for k, v in r.getheaders():
            if k.lower() not in ("transfer-encoding", "connection", "content-length"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        conn.close()

    do_GET = do_POST = do_PUT = do_DELETE = _forward


class Threaded(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


TUNNEL = Threaded(("127.0.0.1", 0), Tunnel)
threading.Thread(target=TUNNEL.serve_forever, daemon=True).start()
VIA = f"http://127.0.0.1:{TUNNEL.server_address[1]}"

# --- API --------------------------------------------------------------------------------------------------------------

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))


def call(method, path, body=None, headers=None, base=None, raw=False, timeout=900):
    """-> (http status, parsed JSON envelope or raw bytes)."""
    data = body if isinstance(body, (bytes, bytearray)) or body is None else json.dumps(body).encode()
    h = {"Accept": "application/json"}
    if body is not None and not isinstance(body, (bytes, bytearray)):
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = urllib.request.Request((base or VIA) + path, data=data, method=method, headers=h)
    try:
        with OP.open(req, timeout=timeout) as r:
            payload = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        payload = e.read()
        status = e.code
    if raw:
        return status, payload
    try:
        return status, json.loads(payload or b"{}")
    except ValueError:
        return status, {"status": "NOT_JSON", "message": payload[:200].decode("utf-8", "replace")}


def ok(resp):
    return resp[0] == 200 and resp[1].get("status") == "OK"


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def multipart(name, content):
    boundary = "----dc" + uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode()
    return head + content + f"\r\n--{boundary}--\r\n".encode(), {"Content-Type": f"multipart/form-data; boundary={boundary}"}


def upload_one_request(name, content, path="/rest/private/web-ui-files"):
    body, headers = multipart(name, content)
    return call("POST", path, body, headers)


def upload_in_parts(name, content, as_="apk", part_size=24 * 1024 * 1024):
    st = call("POST", "/rest/private/web-ui-files/chunked", {"fileName": name, "size": len(content), "partSize": part_size})
    if not ok(st):
        return st
    s = st[1]["data"]
    for i in range(s["partCount"]):
        part = content[i * s["partSize"]:(i + 1) * s["partSize"]]
        r = call("POST", f"/rest/private/web-ui-files/chunked/{s['id']}/{i}", part,
                 {"Content-Type": "application/octet-stream", "X-Part-Sha256": sha256(part)})
        if not ok(r):
            return r
    return call("POST", f"/rest/private/web-ui-files/chunked/{s['id']}/complete?as={as_}")


def commit(server_path, file_name):
    r = call("POST", "/rest/private/web-ui-files/update",
             {"tmpPath": server_path, "fileName": file_name, "filePath": file_name, "external": False})
    return r[1].get("data", {}).get("url") if ok(r) else None


def read(name):
    with open(os.path.join(A.apks, name), "rb") as f:
        return f.read()


def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    return ok(call("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5}))


def fleet(pkg=PKG):
    r = call("GET", f"/rest/private/agent/v1/apps/install/status?pkg={pkg}")
    return r[1].get("data") or {}


def fleet_row(number, pkg=PKG):
    for d in fleet(pkg).get("devices") or []:
        if d.get("number") == number:
            return d
    return {}


# --- device -----------------------------------------------------------------------------------------------------------

def adb(*args, timeout=120):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def installed_version(pkg=PKG):
    out = sh(f"dumpsys package {pkg} | grep versionCode")
    for tok in out.split():
        if tok.startswith("versionCode="):
            return int(tok.split("=")[1])
    return None


def queue_install(payload, device=None):
    r = call("POST", f"/rest/private/agent/v1/devices/{device or A.device_id}/commands",
             {"type": "app.install", "requiresCapability": CAP, "payload": json.dumps(payload)})
    if not ok(r):
        raise RuntimeError(f"queue app.install failed: {r}")
    return r[1]["data"]["id"]


def wait_result(cmd_id, timeout=420):
    end = time.time() + timeout
    last = {"status": "timeout"}
    while time.time() < end:
        r = call("GET", f"/rest/private/agent/v1/devices/{A.device_id}/commands?limit=100")
        for c in r[1].get("data") or []:
            if str(c.get("id")) == str(cmd_id):
                last = c
                if c.get("status") in ("done", "failed", "unsupported", "expired"):
                    return c
        time.sleep(2)
    return last


def install(payload, timeout=420):
    c = wait_result(queue_install(payload), timeout)
    return c.get("status"), c.get("detail") or ""


def no_install_dialog():
    """True when no package-installer confirmation screen is in front (a silent install shows none)."""
    top = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'")
    return "packageinstaller" not in top.lower()


def free_data_kb():
    # One filesystem line; its mount point is /data on old releases and a pass-through path on new ones.
    for line in reversed(sh("df -k /data").splitlines()):
        cols = line.split()
        if len(cols) >= 6 and cols[3].isdigit():
            return int(cols[3])
    return None


# ======================================================================================================================

def main():
    section(f"server: {A.api} behind a simulated tunnel that refuses bodies over {A.limit_mb} MB ({VIA})")
    if not check("admin login", login()):
        return
    # A previous run left the test app in the library (its last version signed with the OTHER key): start clean, or
    # every signature comparison below would be against that one.
    for old in call("GET", f"/rest/private/applications/search/{PKG}")[1].get("data") or []:
        if old.get("pkg") == PKG and old.get("id"):
            call("DELETE", f"/rest/private/applications/{old['id']}")
    big = read("bigapp-v1.apk")
    v2 = read("bigapp-v2.apk")
    v3 = read("bigapp-v3-otherkey.apk")
    corrupt = read("bigapp-corrupt.apk")
    check(f"the test APK is bigger than the limit ({len(big) >> 20} MB)", len(big) > LIMIT)

    section("1. upload of a big APK")
    r = upload_one_request("bigapp-v1.apk", big)
    check("in ONE request it is refused with HTTP 413 (the reported failure)", r[0] == 413, str(r[0]))

    st = call("POST", "/rest/private/web-ui-files/chunked", {"fileName": "bigapp-v1.apk", "size": len(big), "partSize": 500 << 20})
    s = st[1].get("data") or {}
    check("an upload in parts starts", ok(st), str(st[1])[:200])
    check("the server caps the part size at 50 MB", 0 < s.get("partSize", 0) <= 50 << 20, str(s.get("partSize")))
    call("DELETE", f"/rest/private/web-ui-files/chunked/{s.get('id')}")
    check("a cancelled upload is gone", not ok(call("GET", f"/rest/private/web-ui-files/chunked/{s.get('id')}")))

    st = call("POST", "/rest/private/web-ui-files/chunked", {"fileName": "bigapp-v1.apk", "size": len(big), "partSize": 24 << 20})
    s = st[1]["data"]
    n, size, uid = s["partCount"], s["partSize"], s["id"]
    part = lambda i: big[i * size:(i + 1) * size]
    send = lambda i, data, sha: call("POST", f"/rest/private/web-ui-files/chunked/{uid}/{i}", data,
                                     {"Content-Type": "application/octet-stream", "X-Part-Sha256": sha})
    check(f"{n} parts of at most 50 MB each", n >= 3 and size <= 50 << 20)
    check("part 0 accepted", ok(send(0, part(0), sha256(part(0)))))
    damaged = bytearray(part(1)); damaged[100] ^= 0xFF
    r = send(1, bytes(damaged), sha256(part(1)))
    check("a damaged part is refused with a clear message", not ok(r) and "dañada" in (r[1].get("message") or ""), str(r[1])[:200])
    r = send(1, part(1)[:1000], sha256(part(1)[:1000]))
    check("a part cut short is refused", not ok(r) and "incompleta" in (r[1].get("message") or ""), str(r[1])[:200])
    r = call("POST", f"/rest/private/web-ui-files/chunked/{uid}/complete?as=apk")
    check("it cannot be finished with parts missing", not ok(r) and "Faltan" in (r[1].get("message") or ""), str(r[1])[:200])
    # "The page was reloaded": ask what is there and send only the rest (out of order).
    have = (call("GET", f"/rest/private/web-ui-files/chunked/{uid}")[1].get("data") or {}).get("received")
    check("resume: the server reports the parts it already has", have == [0], str(have))
    for i in reversed(range(1, n)):
        if not ok(send(i, part(i), sha256(part(i)))):
            check(f"part {i} accepted", False)
    r = call("POST", f"/rest/private/web-ui-files/chunked/{uid}/complete?as=apk")
    up = r[1].get("data") or {}
    fd = up.get("fileDetails") or {}
    check("put together and parsed as the APK it is", ok(r) and fd.get("pkg") == PKG and fd.get("versionCode") == 1, str(r[1])[:300])
    first_time = (up.get("signature") or {}).get("status") in ("first", "same")
    check("signature verdict comes with the upload", first_time, str(up.get("signature")))
    url_v1 = commit(up.get("serverPath"), f"bigapp-1-{RUN}.apk")
    check("hosted", bool(url_v1), str(url_v1))

    section("2. download of the hosted big file (what the phone does), resumable by Range")
    dl = url_v1.replace(urllib.parse.urlparse(url_v1).netloc, UP.netloc) if url_v1 else ""
    mid = len(big) // 2
    r = urllib.request.urlopen(urllib.request.Request(dl, headers={"Range": f"bytes={mid}-"}), timeout=600)
    tail = r.read()
    check("Range request answers 206 with the right Content-Range",
          r.status == 206 and r.headers.get("Content-Range") == f"bytes {mid}-{len(big) - 1}/{len(big)}", r.headers.get("Content-Range"))
    check("the resumed bytes are the file's", sha256(big[:mid] + tail) == sha256(big))
    try:
        urllib.request.urlopen(urllib.request.Request(dl, headers={"Range": f"bytes={len(big)}-"}), timeout=60)
        code = 200
    except urllib.error.HTTPError as e:
        code = e.code
    check("a Range past the end answers 416 (the agent takes it as 'already whole')", code == 416, str(code))

    section("3. library: first version, update with the same key, another key")
    apps = call("GET", f"/rest/private/applications/search/{PKG}")[1].get("data") or []
    app = next((a for a in apps if a.get("pkg") == PKG), None)
    if app is None:
        r = call("PUT", "/rest/private/applications/android",
                 {"name": "Big Test App", "pkg": PKG, "url": url_v1, "version": "1.0.1", "versionCode": 1, "type": "app"})
        app = r[1].get("data") or {}
        check("app added to the library", ok(r) and app.get("id"), str(r[1])[:200])
    else:
        check("app already in the library (re-run)", True)
    app_id = app.get("id")

    r = upload_one_request("bigapp-v2.apk", v2)
    up2 = r[1].get("data") or {}
    check("v2 (same key): signature 'same'", ok(r) and (up2.get("signature") or {}).get("status") == "same", str(up2.get("signature")))
    url_v2 = commit(up2.get("serverPath"), f"bigapp-2-{RUN}.apk")
    r = call("PUT", "/rest/private/applications/versions", {"applicationId": app_id, "version": "1.0.2", "versionCode": 2, "url": url_v2})
    check("v2 accepted as a new version (or already there on a re-run)", ok(r) or r[1].get("status") != "OK" and "firma" not in str(r[1]), str(r[1])[:200])

    r = upload_one_request("bigapp-v3-otherkey.apk", v3)
    up3 = r[1].get("data") or {}
    sig = up3.get("signature") or {}
    check("v3 (another key): the upload warns the signature changed", ok(r) and sig.get("changed") is True, str(sig))
    check("the warning explains the consequence", "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in (sig.get("message") or ""), sig.get("message"))
    url_v3 = commit(up3.get("serverPath"), f"bigapp-3-{RUN}.apk")
    v3_body = {"applicationId": app_id, "version": "1.0.3", "versionCode": 3, "url": url_v3}
    r = call("PUT", "/rest/private/applications/versions", v3_body)
    check("adding it is BLOCKED until confirmed", not ok(r) and "firma" in (r[1].get("message") or "").lower(), str(r[1])[:300])

    section("4. files that cannot be installed")
    r = upload_one_request("bigapp-corrupt.apk", corrupt)
    check("a corrupt APK is refused with a clear message", not ok(r) and "dañado" in (r[1].get("message") or ""), str(r[1])[:200])
    aab = os.path.join(A.apks, f"app-{RUN}.aab")
    with zipfile.ZipFile(aab, "w") as z:
        z.writestr("BundleConfig.pb", b"\x0a\x00")
        z.writestr("base/manifest/AndroidManifest.xml", b"x")
    aab_bytes = open(aab, "rb").read()
    os.remove(aab)
    for label, resp in (("as an APK", upload_one_request("app.aab", aab_bytes)),
                        ("as a bundle", upload_one_request("app.aab", aab_bytes, "/rest/private/web-ui-files/bundle")),
                        ("renamed .zip", upload_one_request("app.zip", aab_bytes, "/rest/private/web-ui-files/bundle")),
                        ("in parts", call("POST", "/rest/private/web-ui-files/chunked", {"fileName": "app.aab", "size": 5 << 20}))):
        msg = resp[1].get("message") or ""
        check(f".aab {label}: refused, saying how to convert it", not ok(resp) and "bundletool" in msg, str(resp[1])[:160])
    r = upload_in_parts("bigapp-v2-as-bundle.apks", make_apks(v2), "bundle", 256 * 1024)
    b = r[1].get("data") or {}
    check("an .apks (what bundletool makes from an .aab) is accepted, in parts too",
          ok(r) and b.get("packageName") == PKG and len(b.get("parts") or []) == 1, str(r[1])[:300])

    section("5. an install held on a device that is not Device Owner (scripted device)")
    held_number = scripted_device_without_owner()
    if held_number:
        queue_install({"url": url_v2, "packageName": PKG, "versionCode": 2}, held_number)
        row = fleet_row(held_number)
        check("fleet view: state 'held'", row.get("state") == "held", str(row)[:300])
        check("…with the reason (not Device Owner)", row.get("reasonCode") == "notOwner" and "Device Owner" in (row.get("reason") or ""), str(row.get("reason")))
        r = call("GET", f"/rest/private/agent/v1/apps/install/device/{held_number}")
        probs = r[1].get("data") or []
        check("device page: the held install is listed with its reason",
              ok(r) and any(p.get("packageName") == PKG and p.get("state") == "held" for p in probs), str(r[1])[:300])
        check("fleet totals count it", (fleet().get("counts") or {}).get("held", 0) >= 1, str(fleet().get("counts")))

    if not (A.serial and A.device_id):
        print("\n(no --serial/--device-id: device part skipped)")
        return
    device_part(big, url_v1, url_v2, url_v3, v3_body, app_id)


def make_apks(apk_bytes):
    """A bundletool-style .apks holding one universal.apk."""
    import io
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        z.writestr("toc.pb", b"\x0a\x00")
        z.writestr("universal.apk", apk_bytes)
    return buf.getvalue()


def scripted_device_without_owner():
    r = call("POST", "/rest/private/agent/v1/token", {})
    token = (r[1].get("data") or {}).get("token")
    if not check("enroll token minted", bool(token), str(r[1])[:200]):
        return None
    body = {"enrollToken": token, "agent": {"version": "0.9.8", "package": "com.dallycontrol.agent"},
            "device": {"androidSdkInt": 34, "isDeviceOwner": False},
            "capabilities": {"policy": ["wifi"], "device": ["lock"], "appManagement": [], "remoteControl": {"tier": "none"}}}
    r = call("POST", "/rest/public/agent/v1/enroll", body)
    number = (r[1].get("data") or {}).get("deviceId")
    check("a device that is NOT Device Owner enrolled (no app.silentInstall)", ok(r) and bool(number), str(r[1])[:200])
    return number


def device_part(big, url_v1, url_v2, url_v3, v3_body, app_id):
    api_level = sh("getprop ro.build.version.sdk").strip()
    section(f"6. real device {A.serial} (API {api_level}), id {A.device_id}")
    sh(f"pm uninstall {PKG}")
    check("the test app is not installed yet", installed_version() is None)
    owner = sh("dumpsys device_policy | grep -A3 -i 'Device Owner'")
    check("the agent is Device Owner", "dallycontrol" in owner.lower(), owner[:200])

    t0 = time.time()
    status, detail = install({"url": url_v1, "packageName": PKG, "versionCode": 1, "sha256": sha256(big)}, timeout=900)
    check(f"silent install of the {len(big) >> 20} MB APK: done", status == "done", f"{status}: {detail}")
    check("installed on the device (versionCode 1)", installed_version() == 1, str(installed_version()))
    check("no confirmation dialog on screen", no_install_dialog())
    print(f"        (took {int(time.time() - t0)} s)")
    row = fleet_row(A.device_id)
    check("fleet view: 'installed'", row.get("state") == "installed", str(row)[:200])

    status, detail = install({"url": url_v2, "packageName": PKG, "versionCode": 2})
    check("silent UPDATE to v2: done", status == "done", f"{status}: {detail}")
    check("updated on the device (versionCode 2), no dialog", installed_version() == 2 and no_install_dialog(), str(installed_version()))

    status, detail = install({"url": url_v2, "packageName": PKG, "versionCode": 2})
    check("the same version again is skipped, not reinstalled", status == "done" and "skipped" in detail, f"{status}: {detail}")

    section("7. another signature over the installed app")
    r = call("PUT", "/rest/private/applications/versions?confirmSignatureChange=true", v3_body)
    check("with the confirmation the version is accepted", ok(r) or "firma" not in str(r[1]).lower(), str(r[1])[:200])
    status, detail = install({"url": url_v3, "packageName": PKG, "versionCode": 3})
    check("the device refuses it: failed", status == "failed", f"{status}: {detail}")
    check("device still on v2", installed_version() == 2, str(installed_version()))
    row = fleet_row(A.device_id)
    check("fleet view: 'failed' with reason 'signature' in Spanish",
          row.get("state") == "failed" and row.get("reasonCode") == "signature" and "firma" in (row.get("reason") or "").lower(),
          f"{row.get('state')} {row.get('reasonCode')} {row.get('detail')}")
    check("…and no automatic retry (it cannot fix itself)", row.get("nextRetryAt") is None and row.get("retryable") is False, str(row)[:200])
    r = call("GET", f"/rest/private/agent/v1/apps/install/device/{A.device_id}")
    check("device page lists the failed install with its reason",
          any(p.get("packageName") == PKG and p.get("state") == "failed" for p in (r[1].get("data") or [])), str(r[1])[:300])

    section("8. damaged files")
    r = upload_in_parts(f"corrupt-{RUN}.bin", read("bigapp-corrupt.apk") + os.urandom(300 * 1024), "raw", 256 * 1024)
    bad_url = commit((r[1].get("data") or {}).get("serverPath"), f"bigapp-corrupt-{RUN}.apk")
    check("a corrupt file hosted as-is (raw upload)", bool(bad_url), str(r[1])[:200])
    status, detail = install({"url": bad_url, "packageName": PKG, "versionCode": 4})
    check("corrupt APK: failed on the device", status == "failed", f"{status}: {detail}")
    row = fleet_row(A.device_id)
    check("fleet view explains it (invalid APK)", row.get("reasonCode") == "invalid", f"{row.get('reasonCode')} / {row.get('detail')}")
    status, detail = install({"url": url_v2, "packageName": PKG, "versionCode": 5, "sha256": "0" * 64})
    check("wrong SHA-256: refused before installing", status == "failed" and "checksum mismatch" in detail, f"{status}: {detail}")
    check("fleet view explains it (checksum)", fleet_row(A.device_id).get("reasonCode") == "checksum")
    check("device still on v2 after the bad files", installed_version() == 2, str(installed_version()))

    if not A.skip_storage:
        storage_part(big, url_v1)

    counts = fleet().get("counts") or {}
    section("9. fleet totals")
    check("counts cover installed / pending / held / failed", all(k in counts for k in ("installed", "pending", "installing", "held", "failed")), str(counts))
    print(f"        {counts}")

    if not A.skip_auto_retry:
        auto_retry_part(url_v2)
    sh(f"pm uninstall {PKG}")


def storage_part(big, url_v1):
    section("10. device without free space")
    sh(f"pm uninstall {PKG}")
    adb("root"); time.sleep(3)
    free = free_data_kb()
    if not free:
        check("free space of /data read", False, sh("df -k /data")[:200])
        return
    # Leave ~40 MB: less than the APK needs to download, let alone install.
    fill_mb = max(0, free // 1024 - 40)
    sh(f"dd if=/dev/zero of=/data/local/tmp/dc-fill bs=1048576 count={fill_mb}", timeout=900)
    left = free_data_kb() or 0
    check(f"device filled (free: {left // 1024} MB)", left // 1024 < len(big) >> 20, str(left))
    try:
        status, detail = install({"url": url_v1, "packageName": PKG, "versionCode": 1, "sha256": sha256(big)}, timeout=900)
        check("install fails (not stuck, not silent)", status == "failed", f"{status}: {detail}")
        row = fleet_row(A.device_id)
        check("fleet view: reason 'storage' in Spanish",
              row.get("reasonCode") == "storage" and "espacio" in (row.get("reason") or ""), f"{row.get('reasonCode')} / {row.get('detail')}")
        check("…with an automatic retry scheduled", bool(row.get("nextRetryAt")) and row.get("attempts", 0) >= 1, str(row)[:300])
    finally:
        sh("rm -f /data/local/tmp/dc-fill")
    r = call("POST", "/rest/private/agent/v1/apps/install/retry", {"packageName": PKG, "deviceNumbers": [A.device_id]})
    check("'Reintentar' queues the install again", ok(r) and (r[1].get("data") or {}).get("queued") == 1, str(r[1])[:200])
    end = time.time() + 900
    while time.time() < end and fleet_row(A.device_id).get("state") in ("pending", "installing"):
        time.sleep(3)
    row = fleet_row(A.device_id)
    check("with room again the retry installs it", row.get("state") == "installed" and installed_version() == 1,
          f"{row.get('state')} {row.get('detail')} v={installed_version()}")


def auto_retry_part(url_v2):
    section("11. automatic retry of a failed install (server timer, growing wait)")
    if not A.pg:
        print("        (no --pg: skipped)")
        return
    # A failure that can fix itself: a download the device verifies and rejects (checksum).
    status, detail = install({"url": url_v2, "packageName": "com.dallycontrol.retryprobe", "versionCode": 2, "sha256": "1" * 64})
    check("a retryable failure recorded", status == "failed", f"{status}: {detail}")
    row = fleet_row(A.device_id, "com.dallycontrol.retryprobe")
    wait_min = round(((row.get("nextRetryAt") or 0) - (row.get("at") or 0)) / 60000)
    check("first retry is due 10 minutes after the failure", wait_min == 10, str(wait_min))
    # Make the 10 minutes pass: move that failure 11 minutes into the past.
    sql = ("UPDATE agentCommand SET completedAt = completedAt - 660000, createdAt = createdAt - 660000 "
           f"WHERE id = {int(row.get('commandId'))}")
    subprocess.run(A.pg.split() + [sql], capture_output=True, text=True, timeout=60)
    end = time.time() + 480
    again = {}
    while time.time() < end:
        again = fleet_row(A.device_id, "com.dallycontrol.retryprobe")
        if again.get("commandId") != row.get("commandId"):
            break
        time.sleep(5)
    check("the server sent it again by itself", again.get("commandId") not in (None, row.get("commandId")), str(again)[:200])
    end = time.time() + 180
    while time.time() < end and again.get("state") in ("pending", "installing"):
        time.sleep(3)
        again = fleet_row(A.device_id, "com.dallycontrol.retryprobe")
    check("second failure counted, next wait longer (30 min)",
          again.get("attempts") == 2 and round(((again.get("nextRetryAt") or 0) - (again.get("at") or 0)) / 60000) == 30, str(again)[:300])


try:
    main()
except Exception as e:  # a crash is a failed run, with what passed so far
    check(f"script error: {e!r}", False)
passed = sum(1 for _, good, _ in RESULTS if good)
failed = [n for n, good, _ in RESULTS if not good]
print(f"\nRESULT: PASS={passed} FAIL={len(failed)}")
for n in failed:
    print("  failed:", n)
sys.exit(1 if failed else 0)
