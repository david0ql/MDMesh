#!/usr/bin/env python3
"""
End-to-end proof, on a REAL enrolled agent (emulator or USB device) against the dev stack, that remote commands are
not lost and that their latency can be shown (criteria MDM-07 "latencia de comandos remotos" and MDM-09 "borrado
remoto").

    scripts/command-reliability-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id>
        [--reps 10] [--pg-container dallycontrol-dev-postgres-1] [--only latency,offline,...] [--wipe]

Phases (each can be picked with --only):
  latency   device online: lock, alarm (ring), message (alert) and lock-screen message, --reps times each;
            prints p50 / p90 / max from the server's own timestamps (created -> delivered -> completed).
  offline   airplane mode on; queue lock + lock-screen message + alert (+ a command that is NOT kept, as control);
            make them older than the old 60-minute limit in the database; they must still be pending, the control
            expired; cancel one of them; reconnect; the kept ones must run, the cancelled one must not.
  lostack   a command the device "received" whose answer never arrived is handed out again and runs; a command
            that DID run and whose answer was lost is handed out again WITHOUT running twice; a lost state change is
            not replayed over a newer one.
  reboot    (agent with command ledger) a reboot's answer is really lost; after the reboot the server repeats the
            command and the agent answers from its ledger instead of rebooting again.
  report    the "Latencia de comandos" report (JSON and Excel) contains what this run measured; issuer recorded.
  wipe      (--wipe, LAST, destructive) airplane mode, queue the wipe, age it, reconnect: the wipe is delivered and
            executed; then the device is silent and the console marks it "Borrado" with date and issuer.

The database is touched only to move timestamps back (simulating elapsed time) and to simulate a lost answer; that
needs the dev stack's postgres container. Exit code 0 only when every check passed.
"""
import argparse, hashlib, http.cookiejar, json, subprocess, sys, time, urllib.request

P = argparse.ArgumentParser()
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--serial", required=True)
P.add_argument("--device-id", required=True, help="the device number (UUID) the agent enrolled as")
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--adb", default="adb")
P.add_argument("--pg-container", default="dallycontrol-dev-postgres-1")
P.add_argument("--reps", type=int, default=10)
P.add_argument("--only", default="")
P.add_argument("--wipe", action="store_true", help="also run the destructive remote-wipe phase (last)")
P.add_argument("--json-out", default="", help="write the measured figures to this file")
A = P.parse_args()

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
RESULTS = []
FIGURES = {}
HOUR = 3600_000


def api(method, path, body=None, raw=False):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(A.api + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with OP.open(req, timeout=30) as r:
        content = r.read()
        return content if raw else json.loads(content or b"{}")


def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    r = api("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5})
    if r.get("status") != "OK":
        sys.exit(f"login failed: {r}")


def adb(*args, timeout=60):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=60):
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
    end = time.time() + timeout
    v = None
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return v


def queue_full(type_, payload=None, cap=None):
    body = {"type": type_}
    if cap:
        body["requiresCapability"] = cap
    if payload is not None:
        body["payload"] = json.dumps(payload)
    r = api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/commands", body)
    if r.get("status") != "OK":
        raise RuntimeError(f"queue {type_} failed: {r}")
    return r["data"]


def queue(type_, payload=None, cap=None):
    return queue_full(type_, payload, cap)["id"]


def history():
    return api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/commands").get("data") or []


def command(cmd_id):
    for c in history():
        if str(c.get("id")) == str(cmd_id):
            return c
    return {}


CLOSED = ("done", "failed", "unsupported", "expired", "cancelled")


def wait_closed(cmd_id, timeout=90):
    return until(lambda: (lambda c: c if c.get("status") in CLOSED else None)(command(cmd_id)), timeout, 0.5) \
        or command(cmd_id) or {"status": "timeout"}


def sync():
    api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/sync", {})


def live():
    return A.device_id in (api("GET", "/rest/private/agent/v1/live").get("data") or [])


def advertised(cap):
    caps = json.loads(sql(f"select agentCapabilities from devices where number='{A.device_id}'") or "{}")
    return cap in {f"device.{k}" for k in caps.get("device", [])}


def percentile(values, p):
    s = sorted(values)
    if not s:
        return None
    import math
    return s[max(1, min(len(s), math.ceil(p / 100.0 * len(s)))) - 1]


def lockscreen_info_has(msg):
    if msg in sh("dumpsys device_policy"):
        return True
    adb("root")
    time.sleep(1)
    return bool(sh(f"grep -l '{msg}' /data/system/locksettings.db* /data/system/device_policies.xml 2>/dev/null").strip())


def notification_posted(text):
    return text in sh("dumpsys notification --noredact")


def keyguard_or_screen_off():
    return "mWakefulness=Awake" not in sh("dumpsys power") \
        or "isShowing=true" in sh("dumpsys window policy").replace(" ", "") \
        or "mShowingLockscreen=true" in sh("dumpsys window")


def wake_screen():
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")


def airplane(on):
    """Cut / restore every link of the device. Emulators route everything through wifi + mobile data."""
    if sdk() >= 28:
        sh("cmd connectivity airplane-mode " + ("enable" if on else "disable"))
    else:
        sh(f"settings put global airplane_mode_on {1 if on else 0}")
        sh(f"am broadcast -a android.intent.action.AIRPLANE_MODE --ez state {'true' if on else 'false'}")
    # Belt and braces: some images keep data up in airplane mode.
    sh("svc wifi " + ("disable" if on else "enable"))
    sh("svc data " + ("disable" if on else "enable"))


def device_can_reach_server():
    host = A.api.split("//", 1)[1].split("/")[0]
    port = host.split(":")[1] if ":" in host else "80"
    out = sh(f"toybox nc -w 2 10.0.2.2 {port} </dev/null >/dev/null 2>&1; echo rc=$?", timeout=15)
    return "rc=0" in out


# ---------------------------------------------------------------------------------------------------------------

def t_latency():
    print(f"== latency with the device online ({A.reps} repetitions each)")
    if not check("device's live channel is open", until(live, timeout=90, step=2)):
        print("     without the live channel every command waits for the periodic check-in: latency not measured")
        return
    plan = [("Bloqueo", "device.lock", lambda i: None),
            ("Alarma", "device.ring", lambda i: {"durationMs": 1500}),
            ("Mensaje", "device.alert", lambda i: {"title": f"Latencia {i}", "body": "prueba de latencia"})]
    if advertised("device.lockscreenMessage"):
        plan.append(("Mensaje en bloqueo", "device.lockscreenMessage", lambda i: {"message": f"Latencia {i}"}))
    for label, type_, payload in plan:
        delivery, total, wall, bad = [], [], [], 0
        for i in range(A.reps):
            if type_ == "device.lock":
                wake_screen()
            t0 = time.time()
            cid = queue(type_, payload(i), type_)
            c = wait_closed(cid, 30)
            wall.append((time.time() - t0) * 1000)
            if c.get("status") != "done" or c.get("totalMs") is None:
                bad += 1
                continue
            delivery.append(c["deliveryMs"])
            total.append(c["totalMs"])
            if type_ == "device.ring":
                wait_closed(queue("device.ringStop", None, "device.ringStop"), 30)
            time.sleep(0.3)
        fig = {"n": len(total), "failed": bad,
               "delivery_p50_s": None if not delivery else percentile(delivery, 50) / 1000,
               "delivery_p90_s": None if not delivery else percentile(delivery, 90) / 1000,
               "exec_p50_s": None if not total else percentile(total, 50) / 1000,
               "exec_p90_s": None if not total else percentile(total, 90) / 1000,
               "exec_max_s": None if not total else max(total) / 1000,
               "console_wall_p90_s": round(percentile(wall, 90) / 1000, 2)}
        FIGURES[type_] = fig
        print(f"     {label:20s} n={fig['n']}  entrega p50={fig['delivery_p50_s']}s p90={fig['delivery_p90_s']}s   "
              f"ejecución p50={fig['exec_p50_s']}s p90={fig['exec_p90_s']}s máx={fig['exec_max_s']}s", flush=True)
        check(f"{label}: {A.reps}/{A.reps} executed", bad == 0 and len(total) == A.reps, f"{bad} not done")
        check(f"{label}: p90 from send to executed under 10 s", total and percentile(total, 90) < 10_000,
              f"p90={fig['exec_p90_s']}")
    wake_screen()
    if advertised("device.lockscreenMessage"):
        wait_closed(queue("device.lockscreenMessage", {"message": ""}, "device.lockscreenMessage"), 30)


def t_offline():
    print("== device without link: commands wait past the old 60 minutes and run on reconnect")
    stamp = int(time.time())
    airplane(True)
    check("device is cut off (cannot reach the server)", until(lambda: not device_can_reach_server(), 60, 2))
    check("server sees the live channel closed", until(lambda: not live(), timeout=180, step=3))
    wake_screen()
    msg, title, cancel_title = f"Sin enlace {stamp}", f"Aviso sin enlace {stamp}", f"Cancelado {stamp}"
    lock = queue_full("device.lock", None, "device.lock")
    check("queueing for an offline device succeeds and says it is offline (console shows 'se ejecutará cuando "
          "el equipo vuelva a conectarse')", lock.get("status") == "pending" and lock.get("deviceOnline") is False,
          str(lock))
    ids = {"lock": lock["id"], "alert": queue("device.alert", {"title": title, "body": "enviado sin enlace"}, "device.alert")}
    has_msg = advertised("device.lockscreenMessage")
    if has_msg:
        ids["message"] = queue("device.lockscreenMessage", {"message": msg}, "device.lockscreenMessage")
    control = queue("device.appLaunch", {"packageName": "com.android.settings"}, "device.appLaunch")
    cancelled = queue("device.alert", {"title": cancel_title, "body": "no debe mostrarse"}, "device.alert")
    session = queue("remote.vnc.stop")
    all_ids = list(ids.values()) + [control, cancelled, session]
    # 61+ minutes go by (2 h, to be clear of the old limit).
    sql(f"update agentCommand set createdAt = createdAt - {2 * HOUR} where id in ({','.join(map(str, all_ids))})")
    hist = {str(c["id"]): c for c in history()}
    for name, cid in ids.items():
        check(f"{name}: still pending after 2 h without link (was: expired at 60 min)",
              hist.get(str(cid), {}).get("status") == "pending", str(hist.get(str(cid))))
    check("control (device.appLaunch, not a kept type): expired at its 60 minutes",
          hist.get(str(control), {}).get("status") == "expired", str(hist.get(str(control))))
    check("remote session command: expired fast", hist.get(str(session), {}).get("status") == "expired")
    r = api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/commands/{cancelled}/cancel", {})
    check("cancel a pending command: cancelled", r.get("status") == "OK" and r["data"].get("status") == "cancelled", str(r))
    check("cancelled command records who cancelled it", A.admin_user in (r.get("data", {}).get("detail") or ""))
    r2 = api("POST", f"/rest/private/agent/v1/devices/{A.device_id}/commands/{cancelled}/cancel", {})
    check("cancelling it again is refused", r2.get("status") != "OK", str(r2))
    t0 = time.time()
    airplane(False)
    done = {}
    for name, cid in ids.items():
        done[name] = wait_closed(cid, 300)
        check(f"{name}: executed after reconnecting", done[name].get("status") == "done", str(done[name]))
    FIGURES["reconnect_to_executed_s"] = round(time.time() - t0, 1)
    print(f"     reconnect -> all executed in {FIGURES['reconnect_to_executed_s']} s", flush=True)
    check("lock: effect on the device (keyguard / screen off)", until(keyguard_or_screen_off, 20))
    check("alert: effect on the device (notification posted)", until(lambda: notification_posted(title), 20))
    if has_msg:
        check("lock-screen message: effect on the device", until(lambda: lockscreen_info_has(msg), 20))
    time.sleep(3)
    check("cancelled command stayed cancelled and never reached the device",
          command(cancelled).get("status") == "cancelled" and not notification_posted(cancel_title))
    check("history shows issuer and created -> delivered -> completed", all(
        d.get("issuerName") == A.admin_user and d.get("createdAt") and d.get("deliveredAt") and d.get("completedAt")
        for d in done.values()), str(done.get("lock")))
    wake_screen()
    if has_msg:
        wait_closed(queue("device.lockscreenMessage", {"message": ""}, "device.lockscreenMessage"), 30)


def lose_answer(cmd_id, minutes=3):
    """The device got the command `minutes` ago and the server never heard back."""
    ago = int(time.time() * 1000) - minutes * 60_000
    sql(f"update agentCommand set status='delivered', deliveredAt={ago}, completedAt=null, detail=null where id={cmd_id}")


def t_lostack():
    print("== answer lost: the command is handed out again, without a double effect")
    if not check("device online", until(live, timeout=120, step=2)):
        return
    stamp = int(time.time())
    # (a) delivered, never executed (the delivery itself was lost): must run on redelivery.
    title = f"Reenvio {stamp}"
    now = int(time.time() * 1000)
    payload = json.dumps({"title": title, "body": "entregado sin acuse"}).replace("'", "''")
    cid = sql("insert into agentCommand (deviceNumber, type, payload, requiresCapability, status, createdAt, "
              f"deliveredAt, deliveries, issuerName) values ('{A.device_id}', 'device.alert', '{payload}', "
              f"'device.alert', 'delivered', {now - 4 * 60_000}, {now - 3 * 60_000}, 1, 'prueba') returning id").split()[0]
    sync()
    c = wait_closed(cid, 60)
    check("delivered without answer for 3 min: handed out again and executed",
          c.get("status") == "done" and c.get("deliveries") == 2, str(c))
    check("...and its effect happened on the device", until(lambda: notification_posted(title), 20))
    # (b) executed, the answer was lost: redelivered, the agent must not run it twice.
    title2 = f"Una sola vez {stamp}"
    cid2 = queue("device.alert", {"title": title2, "body": "no debe repetirse"}, "device.alert")
    first = wait_closed(cid2, 60)
    check("command executed once", first.get("status") == "done", str(first))
    lose_answer(cid2)
    sync()
    again = wait_closed(cid2, 60)
    check("answer lost: handed out again, closed as done", again.get("status") == "done" and again.get("deliveries") == 2,
          str(again))
    check("agent did NOT run it a second time (answered as duplicate)",
          (again.get("detail") or "") == "duplicate ignored" or again.get("detail") == first.get("detail"), str(again))
    # (c) under 2 minutes nothing is resent (a command still running is not duplicated).
    cid3 = queue("device.alert", {"title": f"Reciente {stamp}", "body": "x"}, "device.alert")
    wait_closed(cid3, 60)
    lose_answer(cid3, minutes=1)
    sync()
    time.sleep(6)
    check("delivered 1 min ago: not handed out again yet", command(cid3).get("status") == "delivered", str(command(cid3)))
    sql(f"update agentCommand set status='done', completedAt=deliveredAt where id={cid3}")
    # (d) a lost state change is not replayed over a newer one.
    if advertised("device.lockscreenMessage"):
        old, new = f"Viejo {stamp}", f"Nuevo {stamp}"
        a = queue("device.lockscreenMessage", {"message": old}, "device.lockscreenMessage")
        wait_closed(a, 60)
        b = queue("device.lockscreenMessage", {"message": new}, "device.lockscreenMessage")
        wait_closed(b, 60)
        lose_answer(a)
        sync()
        ca = wait_closed(a, 60)
        check("older lock-screen message with a lost answer is NOT replayed over the newer one",
              ca.get("status") == "expired" and "reemplaz" in (ca.get("detail") or ""), str(ca))
        check("device keeps the newer message", lockscreen_info_has(new) and not lockscreen_info_has(old))
        wait_closed(queue("device.lockscreenMessage", {"message": ""}, "device.lockscreenMessage"), 30)


def t_reboot():
    print("== reboot: its answer is really lost; the repeat must not reboot the device again")
    if not advertised("device.reboot"):
        print("     skipped: device.reboot not advertised")
        return
    if not advertised("device.commandLedger"):
        print("     agent without command ledger: the server must NOT resend a reboot")
        cid = queue("device.reboot", None, "device.reboot")
        time.sleep(5)
        adb("wait-for-device", timeout=300)
        until(lambda: sh("getprop sys.boot_completed").strip() == "1", timeout=300, step=3)
        boot = sh("cat /proc/sys/kernel/random/boot_id").strip()
        if command(cid).get("status") == "delivered":
            lose_answer(cid)
        until(live, timeout=240, step=3)
        sync()
        time.sleep(20)
        check("no second reboot for an agent without ledger", sh("cat /proc/sys/kernel/random/boot_id").strip() == boot)
        return
    before = sh("cat /proc/sys/kernel/random/boot_id").strip()
    cid = queue("device.reboot", None, "device.reboot")
    until(lambda: sh("cat /proc/sys/kernel/random/boot_id", timeout=10).strip() not in (before, ""), timeout=300, step=3)
    adb("wait-for-device", timeout=300)
    until(lambda: sh("getprop sys.boot_completed").strip() == "1", timeout=300, step=3)
    after = sh("cat /proc/sys/kernel/random/boot_id").strip()
    check("device rebooted", after and after != before)
    c = command(cid)
    print(f"     after reboot the command is: {c.get('status')} ({c.get('detail')})", flush=True)
    # Whatever the race gave us, put it in the worst case: delivered, answer never arrived, 3 minutes ago.
    lose_answer(cid)
    check("agent back online after reboot", until(live, timeout=300, step=3))
    sync()
    c = wait_closed(cid, 120)
    check("reboot handed out again and answered from the agent's ledger",
          c.get("status") == "done" and (c.get("deliveries") or 0) >= 2, str(c))
    time.sleep(15)
    check("device did NOT reboot a second time", sh("cat /proc/sys/kernel/random/boot_id").strip() == after)
    wake_screen()


def t_report():
    print("== evidence: 'Latencia de comandos' report")
    now = int(time.time() * 1000)
    r = api("GET", f"/rest/private/reports/v1/command-latency?from={now - 6 * HOUR}&to={now}")
    rows = {row["type"]: row for row in (r.get("data") or {}).get("rows", [])}
    check("report answers with one row per command type", r.get("status") == "OK" and "device.lock" in rows, str(r)[:300])
    lock = rows.get("device.lock", {})
    print("     device.lock row:", json.dumps(lock, ensure_ascii=False), flush=True)
    check("lock row has sent / % executed / p50 / p90 / max", all(lock.get(k) is not None for k in
          ("total", "donepct", "execp50", "execp90", "execmax", "deliveryp50", "deliveryp90")), str(lock))
    check("first row adds all the types up", "*" in rows and rows["*"]["total"] >= lock.get("total", 0))
    labels = [c["label"] for c in (r.get("data") or {}).get("columns", [])]
    check("columns are in Spanish", "% ejecutados" in labels and "Ejecución p90 (s)" in labels, str(labels))
    x = api("GET", f"/rest/private/reports/v1/export?report=command-latency&format=xlsx&from={now - 6 * HOUR}&to={now}",
            raw=True)
    check("Excel export downloads (xlsx)", x[:2] == b"PK" and len(x) > 2000, f"{len(x)} bytes")
    csv = api("GET", f"/rest/private/reports/v1/export?report=command-latency&format=csv&from={now - 6 * HOUR}&to={now}",
              raw=True).decode("utf-8", "replace")
    check("CSV export has the lock row", "device.lock" in csv)
    issuer = sql(f"select issuerId is not null, issuerName, issuerIp from agentCommand where deviceNumber='{A.device_id}' "
                 "and type='device.lock' and issuerName <> 'prueba' order by id desc limit 1")
    check("agentCommand stores issuer id, login and IP", issuer.startswith("t|" + A.admin_user + "|") and
          len(issuer.split("|")[2]) > 3, issuer)
    system = sql(f"select count(*) from agentCommand where deviceNumber='{A.device_id}' and issuerName='sistema'")
    check("automatic commands are stored as issued by 'sistema'", int(system or 0) > 0, system)
    FIGURES["report_lock_row"] = lock


def t_wipe():
    print("== remote wipe queued without link, delivered on reconnect; device marked 'Borrado'")
    airplane(True)
    check("server sees the live channel closed", until(lambda: not live(), timeout=180, step=3))
    w = queue_full("device.wipe", None, "device.wipe")
    sql(f"update agentCommand set createdAt = createdAt - {72 * HOUR} where id = {w['id']}")
    check("wipe: pending after 3 days without link", command(w["id"]).get("status") == "pending")
    adb("logcat", "-c")
    airplane(False)
    c = until(lambda: (lambda x: x if x.get("status") in ("delivered", "done") else None)(command(w["id"])), 300, 1)
    check("wipe: delivered to the device on reconnect", bool(c), str(command(w["id"])))

    def ordered():
        if "com.dallycontrol" not in sh("pm list packages com.dallycontrol", timeout=20):
            return "reset"
        log = adb("logcat", "-d")
        if "--wipe_data" in log or "REBOOTING TO WIPE USER DATA" in log or "rebootWipeUserData" in log \
                or "Deleted: /cache/recovery/command" in log:
            return "handed to recovery (an emulator has no /misc partition and cannot finish the reset)"
        return None
    outcome = until(ordered, timeout=300, step=5)
    check(f"wipe: executed on the device ({outcome})", outcome is not None)
    # The phone is gone: silence. (An emulator that could not reset keeps reporting, so cut it off.)
    subprocess.run([A.adb, "-s", A.serial, "emu", "kill"], capture_output=True, timeout=30)
    time.sleep(5)
    delivered_at = int(sql(f"select coalesce(deliveredAt, 0) from agentCommand where id = {w['id']}") or 0)
    # 11 minutes of silence go by.
    shift = 11 * 60_000
    sql(f"update agentCommand set createdAt = createdAt - {shift}, deliveredAt = deliveredAt - {shift}, "
        f"completedAt = case when completedAt is null then null else completedAt - {shift} end where id = {w['id']}")
    sql(f"update devices set lastUpdate = {delivered_at - shift} where number = '{A.device_id}' and lastUpdate > {delivered_at - shift}")
    marks = api("GET", "/rest/private/agent/v1/wiped").get("data") or {}
    mark = marks.get(A.device_id)
    check("device marked as wiped ('Borrado') with date and issuer", bool(mark) and mark.get("wipedBy") == A.admin_user
          and mark.get("wipedAt"), str(marks))
    check("device row is kept (not deleted)", sql(f"select count(*) from devices where number='{A.device_id}'") == "1")
    c = command(w["id"])
    check("wipe command closed as executed", c.get("status") == "done", str(c))


def main():
    login()
    print(f"device {A.device_id} on {A.serial} (API {sdk()}), agent ledger: {advertised('device.commandLedger')}")
    tests = [("latency", t_latency), ("offline", t_offline), ("lostack", t_lostack), ("reboot", t_reboot),
             ("report", t_report)]
    if A.wipe:
        tests.append(("wipe", t_wipe))
    only = {x for x in A.only.split(",") if x}
    for name, fn in tests:
        if only and name not in only:
            continue
        try:
            fn()
        except Exception as e:  # a phase that blows up is a failure, the rest still runs
            check(f"{name}: phase completed", False, repr(e))
            try:
                airplane(False)
            except Exception:
                pass
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = [n for n, ok, _ in RESULTS if not ok]
    if A.json_out:
        with open(A.json_out, "w") as f:
            json.dump({"figures": FIGURES, "passed": passed, "failed": failed}, f, indent=2, ensure_ascii=False)
    print(f"RESULT: PASS={passed} FAIL={len(failed)}")
    for n in failed:
        print("  failed:", n)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
