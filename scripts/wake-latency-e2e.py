#!/usr/bin/env python3
"""
Evidence for criterion MDM-07 ("latencia de comandos remotos: ejecutar bloqueo, alarma o mensaje y medir el tiempo
real"): on a REAL enrolled agent (emulator or USB device) against a local stack, measures how long a lock, an alarm
and a message take from "sent in the console" to "executed on the phone" (the server's own timestamps), --reps
times each, p50 / p90 / max, in these conditions:

  live         the live channel (WebSocket) is up
  down-fcm     the live channel is cut (check-ins still work); the server's push wake reaches the phone
  down-nofcm   same cut, the push never arrives (phone without Google services): only the agent's own check-ins
  doze-fcm     as down-fcm, with the phone in forced deep Doze (`dumpsys deviceidle force-idle`), screen off
  doze-nofcm   as down-nofcm, in forced deep Doze
  kill-fcm     the agent's process is killed right before each command; the push wake arrives
  kill-nofcm   the process is killed and no push arrives: Android's own restart of the service must bring it back
  (the kill conditions first try `am force-stop` and report whether Android let it stop a Device Owner at all)

The 1-minute load of the test machine is written next to every figure: under a high load they are not representative.

There are no Firebase credentials here, so Google's part is simulated by `sim`, one small process that is:
  * a fake FCM HTTP v1 service (token endpoint + messages:send) the server is pointed at;
  * the "delivery" of each message to the phone: it does what Google Play services do on a high-priority message —
    put the app on the temporary power allowlist and hand it the message: with `--via c2dm` the very intent Google
    sends (`com.google.android.c2dm.intent.RECEIVE` into Firebase's receiver; needs `adb root`, so emulators), or by
    default the agent's shell-only test entry point `com.dallycontrol.agent.PUSH_TEST`, which runs the same code;
  * a gate in front of the server that can refuse WebSocket upgrades (and cut the open ones) while letting HTTPS
    check-ins through: "the live channel is down but the phone has network".

  1. scripts/wake-latency-e2e.py env --port 8126 > /tmp/fcm-sim.env      # fake service account + endpoints
     ...start the stack with those variables (see docs/DESPERTADOR-FCM.md, "Probar sin credenciales")
  2. scripts/wake-latency-e2e.py sim --port 8126 --upstream 127.0.0.1:8116 &
  3. enroll the emulator THROUGH the gate:  scripts/adb-enroll.sh --server http://10.0.2.2:8126 --api-url http://localhost:8116 ...
  4. scripts/wake-latency-e2e.py run --api http://localhost:8116 --sim http://127.0.0.1:8126 --serial emulator-5600 \
         --device-id <number> [--reps 10] [--slow-reps 3] [--only live,down-fcm,...] [--md-out file] [--json-out file]

`scripts/command-reliability-e2e.py --only latency` measures the first condition too (and the queue's guarantees:
nothing lost offline, redelivery, cancel); this script adds the conditions where the live channel is NOT there.
"""
import argparse, base64, hashlib, http.cookiejar, json, math, os, select, socket, subprocess, sys, threading, time
import urllib.error, urllib.parse, urllib.request

RECEIVER = "com.dallycontrol.agent.wake.PushTestReceiver"
ACTION = "com.dallycontrol.agent.PUSH_TEST"
FCM_RECEIVER = "com.google.firebase.iid.FirebaseInstanceIdReceiver"


# ======================================================================================================== env

def cmd_env(a):
    """Print the variables that point a server at the simulator (a throwaway key: it signs nothing real)."""
    key = subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
                         capture_output=True, text=True, check=True).stdout
    host = f"http://{a.host}:{a.port}"
    account = {"type": "service_account", "project_id": "simulado", "client_email": "wake@simulado.invalid",
               "private_key": key, "token_uri": host + "/token"}
    print("FCM_SERVICE_ACCOUNT_JSON=" + base64.b64encode(json.dumps(account).encode()).decode())
    print("FCM_ENDPOINT=" + host)
    print("FCM_API_KEY=AIza-simulado")
    print("FCM_APP_ID=1:1:android:simulado")


# ======================================================================================================== sim

class Sim:
    def __init__(self, a):
        self.a = a
        self.up_host, self.up_port = a.upstream.split(":")[0], int(a.upstream.split(":")[1])
        self.ws_blocked = False
        self.deliver = True
        self.delay_ms = a.push_delay_ms
        self.tokens = {}        # token -> (serial, pkg)
        self.sends = []         # {at, token, delivered}
        self.ws_open = set()
        self.lock = threading.Lock()

    def serve(self):
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.a.bind, self.a.port))
        srv.listen(128)
        print(f"sim: listening on {self.a.bind}:{self.a.port}, upstream {self.a.upstream}", flush=True)
        while True:
            conn, _ = srv.accept()
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    @staticmethod
    def reply(conn, status, body=b"", ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        head = f"HTTP/1.1 {status}\r\nContent-Type: {ctype}\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n"
        try:
            conn.sendall(head.encode() + body)
        finally:
            conn.close()

    def handle(self, conn):
        try:
            conn.settimeout(30)
            buf = b""
            while b"\r\n\r\n" not in buf:
                chunk = conn.recv(65536)
                if not chunk:
                    conn.close()
                    return
                buf += chunk
            head, rest = buf.split(b"\r\n\r\n", 1)
            lines = head.decode("latin-1").split("\r\n")
            method, target, _ = lines[0].split(" ", 2)
            headers = {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}
            path = urllib.parse.urlsplit(target).path
            if path == "/token" or path.endswith("/messages:send") or path.startswith("/__sim/"):
                length = int(headers.get("content-length", "0"))
                while len(rest) < length:
                    rest += conn.recv(65536)
                return self.local(conn, path, urllib.parse.parse_qs(urllib.parse.urlsplit(target).query), rest)
            is_ws = headers.get("upgrade", "").lower() == "websocket"
            if is_ws and self.ws_blocked:
                return self.reply(conn, "503 Service Unavailable")
            up = socket.create_connection((self.up_host, self.up_port), timeout=30)
            if not is_ws:
                # One request per connection, so a WebSocket upgrade can never ride an already-open one past the gate.
                lines = [l for l in lines if not l.lower().startswith("connection:")] + ["Connection: close"]
            up.sendall("\r\n".join(lines).encode("latin-1") + b"\r\n\r\n" + rest)
            if is_ws:
                with self.lock:
                    self.ws_open.add(conn)
                    self.ws_open.add(up)
            self.pipe(conn, up)
        except Exception:
            try:
                conn.close()
            except Exception:
                pass

    def pipe(self, a, b):
        a.settimeout(None)
        b.settimeout(None)
        try:
            while True:
                ready, _, _ = select.select([a, b], [], [], 600)
                if not ready:
                    break
                for s in ready:
                    data = s.recv(65536)
                    if not data:
                        return
                    (b if s is a else a).sendall(data)
        except Exception:
            pass
        finally:
            for s in (a, b):
                with self.lock:
                    self.ws_open.discard(s)
                try:
                    s.close()
                except Exception:
                    pass

    def local(self, conn, path, q, body):
        if path == "/token":
            return self.reply(conn, "200 OK", {"access_token": "sim-access", "expires_in": 3600, "token_type": "Bearer"})
        if path.endswith("/messages:send"):
            msg = json.loads(body or b"{}").get("message", {})
            token = msg.get("token")
            entry = {"at": time.time(), "token": token, "priority": msg.get("android", {}).get("priority"),
                     "data": msg.get("data"), "delivered": False}
            with self.lock:
                self.sends.append(entry)
                target = self.tokens.get(token)
            if target is None:
                return self.reply(conn, "404 Not Found", {"error": {"code": 404, "status": "NOT_FOUND",
                                  "message": "Requested entity was not found.", "details": [{"errorCode": "UNREGISTERED"}]}})
            if self.deliver:
                threading.Thread(target=self.push, args=(entry, target), daemon=True).start()
            return self.reply(conn, "200 OK", {"name": "projects/simulado/messages/" + str(len(self.sends))})
        if path == "/__sim/register":
            with self.lock:
                self.tokens[q["token"][0]] = (q["serial"][0], q["pkg"][0])
            return self.reply(conn, "200 OK", {"ok": True})
        if path == "/__sim/set":
            if "ws" in q:
                self.ws_blocked = q["ws"][0] == "blocked"
                if self.ws_blocked:
                    with self.lock:
                        open_now = list(self.ws_open)
                    for s in open_now:
                        try:
                            s.shutdown(socket.SHUT_RDWR)
                        except Exception:
                            pass
            if "deliver" in q:
                self.deliver = q["deliver"][0] == "1"
            return self.reply(conn, "200 OK", self.state())
        if path == "/__sim/state":
            return self.reply(conn, "200 OK", self.state())
        return self.reply(conn, "404 Not Found")

    def state(self):
        with self.lock:
            return {"wsBlocked": self.ws_blocked, "deliver": self.deliver, "wsOpen": len(self.ws_open) // 2,
                    "sends": len(self.sends), "delivered": sum(1 for s in self.sends if s["delivered"]),
                    "last": self.sends[-1] if self.sends else None}

    def push(self, entry, target):
        """What Google Play services do with a high-priority message: temp power allowlist, then hand it to the app."""
        serial, pkg = target
        time.sleep(self.delay_ms / 1000.0)
        if self.a.via == "c2dm":
            # The intent Google Play services send, into Firebase's own receiver: the whole real path on the phone
            # (library -> PushWakeService). Only root may send it, so this needs `adb root` (emulators).
            deliver = (f"am broadcast -a com.google.android.c2dm.intent.RECEIVE -n {pkg}/{FCM_RECEIVER} --es wake commands "
                       f"--es google.message_id sim-{int(time.time() * 1000)} --es from simulado")
        else:
            deliver = f"am broadcast -a {ACTION} -n {pkg}/{RECEIVER}"
        script = f"cmd deviceidle tempwhitelist -d 20000 {pkg} >/dev/null 2>&1; {deliver} >/dev/null 2>&1"
        entry["delivered"] = True
        subprocess.run([self.a.adb, "-s", serial, "shell", script], capture_output=True, timeout=60)


def cmd_sim(a):
    Sim(a).serve()


# ======================================================================================================== run

class Run:
    def __init__(self, a):
        self.a = a
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.figures = []
        self.notes = []
        self.failed = False

    # ---- plumbing
    def api(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        for attempt in range(5):
            req = urllib.request.Request(self.a.api + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
            try:
                with self.op.open(req, timeout=60) as r:
                    return json.loads(r.read() or b"{}")
            except (urllib.error.HTTPError, urllib.error.URLError, ConnectionError, TimeoutError, ValueError) as e:
                # ValueError: a gateway's HTML error page instead of JSON (a loaded test machine). A GET is retried;
                # a POST is not, so the test itself never queues a command twice.
                code = getattr(e, "code", None)
                if method != "GET" or attempt == 4 or (code is not None and code not in (502, 503, 504)):
                    raise
                time.sleep(2)

    def sim(self, path):
        with urllib.request.urlopen(self.a.sim + path, timeout=30) as r:
            return json.loads(r.read() or b"{}")

    def sh(self, cmd, timeout=90):
        r = subprocess.run([self.a.adb, "-s", self.a.serial, "shell", cmd], capture_output=True, text=True, timeout=timeout)
        return (r.stdout + r.stderr).replace("\r", "")

    def until(self, fn, timeout, step=1.0):
        end = time.time() + timeout
        v = None
        while time.time() < end:
            v = fn()
            if v:
                return v
            time.sleep(step)
        return v

    def queue(self, type_, payload=None):
        body = {"type": type_, "requiresCapability": type_}
        if payload is not None:
            body["payload"] = json.dumps(payload)
        r = self.api("POST", f"/rest/private/agent/v1/devices/{self.a.device_id}/commands", body)
        if r.get("status") != "OK":
            raise RuntimeError(f"queue {type_}: {r}")
        return r["data"]

    def command(self, cid):
        for c in self.api("GET", f"/rest/private/agent/v1/devices/{self.a.device_id}/commands").get("data") or []:
            if str(c.get("id")) == str(cid):
                return c
        return {}

    def wait_done(self, cid, timeout):
        closed = ("done", "failed", "unsupported", "expired", "cancelled")
        return self.until(lambda: (lambda c: c if c.get("status") in closed else None)(self.command(cid)), timeout, 0.4) \
            or self.command(cid)

    def reach(self):
        return self.api("GET", f"/rest/private/agent/v1/devices/{self.a.device_id}/reach").get("data") or {}

    def live(self):
        return self.reach().get("channel") == "live"

    def check(self, name, ok, detail=""):
        print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{detail}]" if detail else ""), flush=True)
        if not ok:
            self.failed = True
        return ok

    # ---- device state
    def pid(self):
        return self.sh(f"pidof {self.a.pkg}").strip()

    def wake_screen(self):
        self.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")

    def undoze(self):
        self.sh("dumpsys deviceidle unforce >/dev/null; dumpsys battery reset >/dev/null")
        self.wake_screen()

    def doze(self):
        """Screen off, on battery, deep idle forced. Returns the state Android reports."""
        self.sh("dumpsys battery unplug >/dev/null; input keyevent KEYCODE_SLEEP")
        time.sleep(1)
        self.sh("dumpsys deviceidle force-idle >/dev/null")
        return self.sh("dumpsys deviceidle get deep").strip()

    def exempt(self, on):
        self.sh(f"cmd deviceidle whitelist {'+' if on else '-'}{self.a.pkg} >/dev/null")

    def setup(self):
        md5 = hashlib.md5(self.a.admin_password.encode()).hexdigest().upper()
        r = self.api("POST", "/rest/public/auth/login", {"login": self.a.admin_user, "password": md5})
        if r.get("status") != "OK":
            sys.exit(f"login failed: {r}")
        st = self.api("GET", "/rest/private/agent/v1/push/status").get("data") or {}
        self.check("the server has a push project configured (the simulated one)", st.get("configured"), json.dumps(st)[:200])
        self.sim("/__sim/set?ws=open&deliver=1")
        self.undoze()
        self.exempt(True)
        token = "sim-" + self.a.device_id
        self.sim("/__sim/register?" + urllib.parse.urlencode({"token": token, "serial": self.a.serial, "pkg": self.a.pkg}))

        def registered():
            # The agent learns the project from a check-in answer; only then does it accept the (debug) token.
            self.api("POST", f"/rest/private/agent/v1/devices/{self.a.device_id}/sync", {})
            out = self.sh(f"am broadcast -a {ACTION} -n {self.a.pkg}/{RECEIVER} --es token {token}")
            if "ok: token set" not in out:
                return False
            time.sleep(3)
            return self.reach().get("hasToken")
        self.check("the agent holds a push token and the server knows it", self.until(registered, 180, 5))
        r = self.reach()
        self.notes.append(f"Equipo: Android {self.sh('getprop ro.build.version.release').strip()} "
                          f"(API {self.sh('getprop ro.build.version.sdk').strip()}), agente "
                          f"{self.sh('dumpsys package ' + self.a.pkg + ' | grep versionName').strip()}; exento de batería: "
                          f"{r.get('batteryExempt')}; alarmas exactas: {r.get('exactAlarms')}; sondeo de respaldo: {r.get('pollMinutes')} min.")

    # ---- one condition
    PLAN = [("Bloqueo", "device.lock", lambda i: None),
            ("Alarma", "device.ring", lambda i: {"durationMs": 1500}),
            ("Mensaje", "device.alert", lambda i: {"title": f"Latencia {i}", "body": "prueba de latencia"})]

    def measure(self, key, title, reps, timeout, before_each=None, expect_channel=None, slow=False):
        print(f"== {title}  ({reps} repeticiones, espera máx. {timeout} s)", flush=True)
        wanted = self.a.slow_commands.split(",") if slow else None
        for label, type_, payload in self.PLAN:
            if wanted and type_ not in wanted:
                continue
            total, delivery, bad, channels, loads = [], [], 0, set(), []
            for i in range(reps):
                if before_each:
                    before_each()
                loads.append(os.getloadavg()[0])
                queued = self.queue(type_, payload(i))
                channels.add(queued.get("wakeChannel"))
                c = self.wait_done(queued["id"], timeout)
                if c.get("status") != "done" or c.get("totalMs") is None:
                    bad += 1
                    print(f"     {label} #{i + 1}: no ejecutada en {timeout} s (estado {c.get('status')})", flush=True)
                    continue
                total.append(c["totalMs"])
                delivery.append(c.get("deliveryMs") or 0)
                if type_ == "device.ring":
                    self.wait_done(self.queue("device.ringStop")["id"], timeout)
                time.sleep(0.5)
            fig = {"condition": key, "title": title, "command": label, "type": type_, "n": len(total), "failed": bad,
                   "p50_s": pct(total, 50), "p90_s": pct(total, 90), "max_s": None if not total else round(max(total) / 1000, 1),
                   "delivery_p50_s": pct(delivery, 50), "channel": "/".join(sorted(str(c) for c in channels)),
                   "load_1m": round(sum(loads) / max(1, len(loads)), 1)}
            self.figures.append(fig)
            print(f"     {label:8s} n={fig['n']} fallidas={bad}  p50={fig['p50_s']} s  p90={fig['p90_s']} s  máx={fig['max_s']} s  "
                  f"canal anunciado={fig['channel']}  [carga {fig['load_1m']}]", flush=True)
            self.check(f"{title} / {label}: {reps}/{reps} ejecutadas", bad == 0, f"{bad} sin ejecutar")
            if expect_channel:
                self.check(f"{title} / {label}: la consola anunció el canal «{expect_channel}»", channels == {expect_channel},
                           str(channels))

    # ---- the conditions
    def c_live(self):
        self.sim("/__sim/set?ws=open&deliver=1")
        self.undoze()
        if not self.check("canal en vivo abierto", self.until(self.live, 120, 2)):
            return
        self.measure("live", "Canal en vivo arriba", self.a.reps, 60, before_each=self.wake_screen, expect_channel="live")

    def cut(self, deliver):
        self.sim(f"/__sim/set?ws=blocked&deliver={1 if deliver else 0}")
        return self.check("canal en vivo cortado (el servidor ya no lo ve)", self.until(lambda: not self.live(), 180, 2))

    def c_down_fcm(self):
        self.undoze()
        if self.cut(True):
            self.measure("down-fcm", "Canal caído + despertador push (FCM simulado)", self.a.reps, 90, expect_channel="push")

    def c_down_nofcm(self):
        self.undoze()
        if self.cut(False):
            self.measure("down-nofcm", "Canal caído, sin despertador (solo sondeo del agente)", self.a.slow_reps,
                         self.a.slow_timeout, slow=True)

    def dozed(self, deliver, key, title, reps, timeout):
        if not self.cut(deliver):
            return
        # Worst case for Doze: a phone whose agent is NOT exempt from battery optimization.
        self.exempt(self.a.doze_exempt)
        state = self.doze()
        self.check("Doze profundo forzado", state == "IDLE", state)

        def again():
            s = self.doze()
            if s != "IDLE":
                print(f"     (el equipo salió de Doze: {s}; se fuerza de nuevo)", flush=True)
        try:
            self.measure(key, title + ("" if self.a.doze_exempt else " — agente SIN exención de batería"),
                         reps, timeout, before_each=again, slow=not deliver)
        finally:
            self.undoze()
            self.exempt(True)

    def c_doze_fcm(self):
        self.dozed(True, "doze-fcm", "Doze forzado + despertador push", self.a.reps, 120)

    def c_doze_nofcm(self):
        self.dozed(False, "doze-nofcm", "Doze forzado, sin despertador", self.a.slow_reps, self.a.doze_timeout)

    def kill(self):
        """Kill the agent's process the way a maker's battery manager or the low-memory killer does."""
        before = self.pid()
        if not before:
            return
        self.sh(f"kill -9 {before.split()[0]}")
        self.until(lambda: self.pid() != before, 10, 0.3)

    def killed(self, deliver, key, title, reps, timeout, settle=0):
        self.undoze()
        self.sim(f"/__sim/set?ws=open&deliver={1 if deliver else 0}")
        subprocess.run([self.a.adb, "-s", self.a.serial, "root"], capture_output=True, timeout=60)
        time.sleep(3)
        if not self.until(self.pid, 60, 2):
            self.check("el agente está en ejecución antes de matarlo", False)
            return
        # First, what a person (or a task killer app) can do: force-stop. Android refuses it for a Device Owner.
        before = self.pid()
        self.sh(f"am force-stop {self.a.pkg}")
        time.sleep(2)
        after = self.pid()
        survived = bool(after) and after == before
        self.notes.append(f"`am force-stop` del agente (Device Owner): "
                          + ("Android lo ignoró, el proceso siguió vivo." if survived else
                             f"el proceso {'se reinició solo' if after else 'quedó detenido'} (pid {before} -> {after or 'ninguno'})."))
        print("     " + self.notes[-1], flush=True)
        self.until(self.pid, 120, 2)
        def kill_after_settling():
            # Android restarts a killed service after 1 s, but 4x later each time it dies again within a minute.
            # Independent kills (not a kill loop) are what a battery manager does: let the process live a minute first.
            if settle:
                self.until(self.pid, 300, 2)
                time.sleep(settle)
            self.kill()
        self.measure(key, title, reps, timeout, before_each=kill_after_settling, slow=not deliver)
        self.check("el agente revive tras matarlo (proceso en ejecución)", bool(self.until(self.pid, 120, 2)))
        self.check("y reabre el canal en vivo", self.until(self.live, 180, 3))

    def c_kill_fcm(self):
        self.killed(True, "kill-fcm", "Proceso del agente muerto + despertador push", self.a.reps, 120)

    def c_kill_nofcm(self):
        self.killed(False, "kill-nofcm", "Proceso del agente muerto, sin despertador (reinicio de Android)",
                    self.a.slow_reps, self.a.slow_timeout, settle=65)

    def report(self):
        lines = ["| Condición | Orden | n | Sin ejecutar | p50 (s) | p90 (s) | máx (s) | Canal anunciado | Carga (1 min) |",
                 "|---|---|---|---|---|---|---|---|---|"]
        for f in self.figures:
            lines.append(f"| {f['title']} | {f['command']} | {f['n']} | {f['failed']} | {f['p50_s']} | {f['p90_s']} | "
                         f"{f['max_s']} | {f['channel']} | {f['load_1m']} |")
        text = "\n".join(self.notes) + "\n\n" + "\n".join(lines) + "\n"
        print("\n" + text)
        if self.a.md_out:
            open(self.a.md_out, "w").write(text)
        if self.a.json_out:
            json.dump({"notes": self.notes, "figures": self.figures}, open(self.a.json_out, "w"), indent=1, ensure_ascii=False)


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return round(s[max(1, min(len(s), math.ceil(p / 100.0 * len(s)))) - 1] / 1000, 1)


def cmd_run(a):
    r = Run(a)
    r.setup()
    order = ["live", "down-fcm", "doze-fcm", "kill-fcm", "kill-nofcm", "down-nofcm", "doze-nofcm"]
    only = [x for x in a.only.split(",") if x] or order
    try:
        for name in order:
            if name in only:
                getattr(r, "c_" + name.replace("-", "_"))()
    finally:
        try:
            r.sim("/__sim/set?ws=open&deliver=1")
            r.undoze()
            r.exempt(True)
        except Exception:
            pass
        r.report()
    sys.exit(1 if r.failed else 0)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("env")
    e.add_argument("--port", type=int, default=8126)
    e.add_argument("--host", default="host.docker.internal", help="how the SERVER reaches the simulator")
    s = sub.add_parser("sim")
    s.add_argument("--port", type=int, default=8126)
    s.add_argument("--bind", default="127.0.0.1")
    s.add_argument("--upstream", required=True, help="host:port of the real server (console or API port)")
    s.add_argument("--adb", default="adb")
    s.add_argument("--push-delay-ms", type=int, default=300, help="simulated transit time of a push message")
    s.add_argument("--via", choices=["test", "c2dm"], default="test",
                   help="how a push is handed to the agent: its shell-only test entry point (any device), or the "
                        "real Google intent into Firebase's receiver (needs `adb root`: emulators)")
    r = sub.add_parser("run")
    r.add_argument("--api", required=True)
    r.add_argument("--sim", required=True)
    r.add_argument("--serial", required=True)
    r.add_argument("--device-id", required=True)
    r.add_argument("--pkg", default="com.dallycontrol.agent.debug")
    r.add_argument("--admin-user", default="admin")
    r.add_argument("--admin-password", default="admin")
    r.add_argument("--adb", default="adb")
    r.add_argument("--reps", type=int, default=10)
    r.add_argument("--slow-reps", type=int, default=3, help="repetitions of the conditions without a wake (minutes each)")
    r.add_argument("--slow-timeout", type=int, default=480)
    r.add_argument("--slow-commands", default="device.lock,device.ring,device.alert",
                   help="command types measured in the conditions without a wake (each repetition takes minutes)")
    r.add_argument("--doze-timeout", type=int, default=1200)
    r.add_argument("--doze-exempt", action="store_true", help="keep the agent battery-exempt in the Doze conditions")
    r.add_argument("--only", default="")
    r.add_argument("--md-out", default="")
    r.add_argument("--json-out", default="")
    a = p.parse_args()
    {"env": cmd_env, "sim": cmd_sim, "run": cmd_run}[a.cmd](a)


if __name__ == "__main__":
    main()
