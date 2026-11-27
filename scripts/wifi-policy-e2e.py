#!/usr/bin/env python3
"""
End-to-end test of corporate Wi-Fi (MDM-13) against a running stack and a REAL enrolled agent (emulator or phone,
Device Owner) read over adb. See docs/WIFI.md.

    scripts/wifi-policy-e2e.py --api http://localhost:8088 --serial emulator-5600 --device-id <id> \
        [--admin-password admin] [--only networks,lockdown,...] [--skip-safeguard]

What it does, on the device's own policy (restored at the end):

  certificates  makes a CA and a client PKCS#12 with openssl, uploads them, checks the API never returns them, that a
                wrong PKCS#12 password is refused and that a certificate in use cannot be deleted
  secrets       saved Wi-Fi passwords do not come back; saving again without typing them keeps them (same revision)
  validation    the server refuses policies that would strand a phone or join 802.1X without checking the server
  networks      PSK hidden, WPA3-SAE, WPA2/WPA3, OWE, PEAP, TTLS, EAP-TLS and one with every option: saved on the
                phone with the right parameters (`cmd wifi list-networks`, `dumpsys wifi`), CA and key pair installed
                (`dumpsys device_policy`), and the state the phone reports per network
  lockdown      the managed networks cannot be forgotten (`cmd wifi forget-network`), adding networks is refused
                (Android 13+), the SSID allow list disconnects a network outside it (Android 13+), minimum security
  radio         the policy's "Wi-Fi off" switches the radio off and puts it back when someone turns it on
  safeguard     an allow list that leaves the phone with no network at all is relaxed by the phone after the grace
                period and reported (takes ~3 minutes; --skip-safeguard)
  removal       networks taken out of the policy leave the phone, with their certificates; the user's stay

The emulator's own network "AndroidWifi" is open, so real association is only checked for it; for the rest the test
checks the saved configuration. Authentication against a real access point: docs/WIFI.md, "Probar con un AP real".

Exit code 0 only if every executed check passed.
"""
import argparse, base64, hashlib, http.cookiejar, json, os, re, subprocess, sys, tempfile, time, urllib.error, urllib.request

P = argparse.ArgumentParser()
P.add_argument("--api", default="http://localhost:8088")
P.add_argument("--serial", required=True)
P.add_argument("--device-id", required=True)
P.add_argument("--admin-user", default="admin")
P.add_argument("--admin-password", default="admin")
P.add_argument("--pkg", default="com.dallycontrol.agent.debug")
P.add_argument("--adb", default="adb")
P.add_argument("--only", default="", help="comma-separated sections (default: all)")
P.add_argument("--skip-safeguard", action="store_true")
P.add_argument("--keep", action="store_true", help="leave the test policy and certificates in place (to inspect the device)")
P.add_argument("--open-ssid", default="AndroidWifi", help="an OPEN network in range the device can really join")
A = P.parse_args()

CJ = http.cookiejar.CookieJar()
OP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CJ))
RESULTS = []
PSK = "Clave-PSK-0123"
EAP_PW = "eap-Secreto-9"
NETS = {"psk": "DC-PSK-Oculta", "sae": "DC-SAE", "mix": "DC-Mixta", "owe": "DC-OWE", "peap": "DC-PEAP", "ttls": "DC-TTLS",
        "tls": "DC-TLS", "opt": "DC-Opciones"}


def api(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(A.api + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with OP.open(req, timeout=30) as r:
        return json.loads(r.read() or b"{}")


def adb(*args, timeout=60):
    r = subprocess.run([A.adb, "-s", A.serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).replace("\r", "")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  [{str(detail)[:400]}]" if detail and not ok else ""), flush=True)
    return ok


def skip(name, why):
    print(f"  SKIP  {name}  ({why})", flush=True)


def section(title):
    print(f"\n== {title}", flush=True)


def until(fn, timeout=30, step=1.5):
    end = time.time() + timeout
    v = None
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return v


def want(key):
    return not A.only or key in A.only.split(",")


SDK = 0

# --- server side ------------------------------------------------------------------------------------------------------


def login():
    md5 = hashlib.md5(A.admin_password.encode()).hexdigest().upper()
    r = api("POST", "/rest/public/auth/login", {"login": A.admin_user, "password": md5})
    if r.get("status") != "OK":
        sys.exit(f"login failed: {r}")


def config_status():
    return api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/configStatus").get("data") or {}


def telemetry():
    return api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/telemetry").get("data") or {}


def find_config():
    cid = config_status().get("configurationId")
    for c in api("GET", "/rest/private/configurations/search").get("data") or []:
        if c.get("id") == cid:
            return c
    sys.exit(f"the device has no policy (configStatus: {config_status()})")


def get_config(cid):
    for c in api("GET", "/rest/private/configurations/search").get("data") or []:
        if c.get("id") == cid:
            return c
    return None


def save_config(cfg, dc, radio="keep"):
    """PUT the policy with this dcPolicy object. @return (ok, message, saved configuration)."""
    body = dict(cfg)
    body["dcPolicy"] = json.dumps(dc) if dc else None
    if radio != "keep":
        body["wifi"] = radio
    r = api("PUT", "/rest/private/configurations", body)
    return r.get("status") == "OK", r.get("message"), r.get("data")


def wait_applied(timeout=480):
    """Wait until the agent reports the policy's current revision (asking it to check in meanwhile). @return the status.
    A Wi-Fi change can take the phone off its network: the agent then needs a few minutes to reach the server again."""
    end = time.time() + timeout
    while time.time() < end:
        st = config_status()
        if st.get("currentRevision") and st.get("appliedRevision") == st.get("currentRevision"):
            return st
        poke()
        time.sleep(5)
    return config_status()


def poke():
    """Ask the agent for a check-in now: run its pending WorkManager jobs (they are what a check-in is)."""
    out = sh(f"dumpsys jobscheduler | grep -E 'JOB #.*{A.pkg}/' ; true")
    for job in sorted(set(re.findall(r"JOB #\S+/(\d+):", out)))[:6]:
        sh(f"cmd jobscheduler run -f {A.pkg} {job} >/dev/null 2>&1; true")


def managed_report(pred=None, timeout=240):
    """The agent's own state report (telemetry dynamic.managedWifi), waiting until pred(report) holds."""
    def get():
        rep = (telemetry().get("dynamic") or {}).get("managedWifi")
        if rep is None:
            return None
        return rep if pred is None or pred(rep) else None
    end = time.time() + timeout
    while time.time() < end:
        rep = get()
        if rep:
            return rep
        poke()
        time.sleep(5)
    return (telemetry().get("dynamic") or {}).get("managedWifi") or {}


def net_status(rep, ssid):
    return next((n for n in rep.get("networks") or [] if n.get("ssid") == ssid), {})


# --- device side ------------------------------------------------------------------------------------------------------


def list_networks():
    """SSID -> (network id, security types joined by space) from `cmd wifi list-networks` (Android 11+); {} when not available.
    Android 12+ prints one line per security type of a network (a '^' marks the ones the system added itself)."""
    out = sh("cmd wifi list-networks")
    nets = {}
    for line in out.splitlines():
        m = re.match(r"^\s*(\d+)\s+(.+?)\s{2,}(\S.*)$", line)
        if m:
            ssid = m.group(2).strip()
            before = nets.get(ssid, (int(m.group(1)), ""))[1]
            nets[ssid] = (int(m.group(1)), (before + " " + m.group(3).strip()).strip())
    return nets


def restricted(key):
    """Is this user restriction in force for the main user? (`dumpsys user` also lists the defaults for guests.)"""
    header = ""
    for line in sh("dumpsys user").splitlines():
        t = line.strip()
        if not t.startswith("no_"):
            header = t
        elif t == key and not header.startswith(("Guest restrictions", "mDefaultRestrictions")):
            return True
    return False


def wifi_dump():
    return sh("dumpsys wifi", timeout=120)


def config_blocks(dump=None):
    """SSID -> the text of its saved-configuration block in `dumpsys wifi` (WifiConfigManager's dump)."""
    dump = dump if dump is not None else wifi_dump()
    start = dump.find("Configured networks Begin")
    end = dump.find("Configured networks End", start)
    body = dump[start:end] if start >= 0 else dump
    blocks = {}
    for part in re.split(r"(?=\bID: \d+ SSID: \")", body):  # a block does not always start on its own line
        m = re.match(r"ID: (\d+) SSID: \"(.*?)\"", part)
        if m:
            blocks[m.group(2)] = part
    return blocks


def saved_ssids():
    nets = list_networks()
    return set(nets) if nets else set(config_blocks())


def wifi_connected_ssid():
    out = sh("dumpsys wifi | grep -m1 -E 'mWifiInfo .*SSID' ; true")
    m = re.search(r'SSID: "?([^",]+)"?,', out)
    state = re.search(r"Supplicant state: (\w+)", out)
    return m.group(1) if m and state and state.group(1) == "COMPLETED" else None


# --- certificates -----------------------------------------------------------------------------------------------------


def openssl(*args):
    r = subprocess.run(["openssl", *args], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"openssl {' '.join(args[:2])} failed: {r.stderr[:300]}")


def make_certs(d):
    """A CA (PEM), and a client key + certificate signed by it (PKCS#12, password 'p12-clave')."""
    ca_key, ca_pem = os.path.join(d, "ca.key"), os.path.join(d, "ca.pem")
    cl_key, cl_csr, cl_pem, p12 = (os.path.join(d, n) for n in ("client.key", "client.csr", "client.pem", "client.p12"))
    ext = os.path.join(d, "client.ext")
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", ca_key, "-out", ca_pem, "-days", "400",
            "-subj", "/CN=DallyControl E2E CA/O=E2E", "-addext", "basicConstraints=critical,CA:TRUE")
    openssl("req", "-newkey", "rsa:2048", "-nodes", "-keyout", cl_key, "-out", cl_csr, "-subj", "/CN=equipo-e2e/O=E2E")
    with open(ext, "w") as f:
        f.write("basicConstraints=CA:FALSE\nextendedKeyUsage=clientAuth\n")
    openssl("x509", "-req", "-in", cl_csr, "-CA", ca_pem, "-CAkey", ca_key, "-CAcreateserial", "-out", cl_pem, "-days", "200", "-extfile", ext)
    openssl("pkcs12", "-export", "-inkey", cl_key, "-in", cl_pem, "-certfile", ca_pem, "-out", p12, "-passout", "pass:p12-clave", "-name", "equipo-e2e")
    return ca_pem, p12


def b64(path):
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode()


def t_certificates(state):
    section("certificates")
    d = tempfile.mkdtemp(prefix="dc-wifi-e2e-")
    ca_pem, p12 = make_certs(d)
    tag = str(int(time.time()))[-6:]
    r = api("POST", "/rest/private/wifi-certificates", {"name": f"e2e CA {tag}", "kind": "CA", "data": b64(ca_pem)})
    check("CA (PEM) is accepted", r.get("status") == "OK", r)
    ca = r.get("data") or {}
    state["ca"] = ca.get("id")
    check("the answer has subject, expiry and fingerprint", "DallyControl E2E CA" in str(ca.get("subject")) and ca.get("daysLeft", 0) > 300
          and len(str(ca.get("fingerprint"))) == 64 and ca.get("status") == "valid", ca)
    r = api("POST", "/rest/private/wifi-certificates", {"name": f"e2e cliente {tag}", "kind": "CLIENT", "data": b64(p12), "password": "otra"})
    check("PKCS#12 with a wrong password is refused, in Spanish", r.get("status") != "OK" and "contraseña" in str(r.get("message")), r)
    r = api("POST", "/rest/private/wifi-certificates", {"name": f"e2e cliente {tag}", "kind": "CLIENT", "data": b64(p12), "password": "p12-clave"})
    check("client PKCS#12 is accepted with its password", r.get("status") == "OK", r)
    state["client"] = (r.get("data") or {}).get("id")
    r = api("POST", "/rest/private/wifi-certificates", {"name": "basura", "kind": "CA", "data": base64.b64encode(b"no soy un certificado").decode()})
    check("garbage is refused", r.get("status") != "OK", r)
    listing = json.dumps(api("GET", "/rest/private/wifi-certificates"))
    with open(ca_pem) as f:
        body = "".join(line.strip() for line in f if "-----" not in line)
    check("the list never returns certificate bodies, keys or passwords",
          body[:60] not in listing and "sealed" not in listing and "p12-clave" not in listing and "PRIVATE" not in listing)
    state["dir"] = d


def cleanup_certificates(state):
    for k in ("ca", "client"):
        if state.get(k):
            api("DELETE", f"/rest/private/wifi-certificates/{state[k]}")


# --- the policy under test -----------------------------------------------------------------------------------------------


def eap(method, phase2, state, **extra):
    e = {"method": method, "identity": "equipo@e2e.test", "anonymousIdentity": "anonimo@e2e.test", "domain": "radius.e2e.test",
         "caCertIds": [state["ca"]]}
    if phase2:
        e["phase2"] = phase2
    if method != "TLS":
        e["password"] = EAP_PW
    e.update(extra)
    return e


def full_policy(state, base):
    dc = dict(base)
    dc["wifi"] = [
        {"id": "e2eopen", "ssid": A.open_ssid, "security": "NONE"},
        {"id": "e2epsk", "ssid": NETS["psk"], "security": "WPA", "password": PSK, "hidden": True},
        {"id": "e2esae", "ssid": NETS["sae"], "security": "WPA3", "password": PSK},
        {"id": "e2emix", "ssid": NETS["mix"], "security": "WPA3_TRANSITION", "password": PSK},
        {"id": "e2eowe", "ssid": NETS["owe"], "security": "OWE"},
        {"id": "e2epeap", "ssid": NETS["peap"], "security": "EAP", "eap": eap("PEAP", "MSCHAPV2", state)},
        {"id": "e2ettls", "ssid": NETS["ttls"], "security": "EAP", "eap": eap("TTLS", "PAP", state, altSubjectMatch="DNS:radius.e2e.test")},
        {"id": "e2etls", "ssid": NETS["tls"], "security": "EAP", "eap": eap("TLS", None, state, clientCertId=state["client"])},
        {"id": "e2eopt", "ssid": NETS["opt"], "security": "WPA", "password": PSK, "autoConnect": False, "priority": 7,
         "mac": "device", "metered": "metered", "proxy": {"mode": "manual", "host": "proxy.e2e.test", "port": 3128, "exclude": ["intranet.e2e.test"]},
         "ip": {"address": "192.168.77.50", "prefix": 24, "gateway": "192.168.77.1", "dns": ["192.168.77.1", "8.8.8.8"]}},
    ]
    dc["wifiRules"] = {"lockManaged": True}
    return dc


def t_secrets(state, cfg, base):
    section("secrets")
    ok, msg, saved = save_config(cfg, full_policy(state, base))
    check("the policy with every kind of network is saved", ok, msg)
    if not ok:
        return False
    text = json.dumps(saved)
    check("the save's own answer has no Wi-Fi password", PSK not in text and EAP_PW not in text)
    again = get_config(cfg["id"])
    text = json.dumps(again)
    check("reading the policy has no Wi-Fi password", PSK not in text and EAP_PW not in text)
    dc = json.loads(again["dcPolicy"])
    by = {n["ssid"]: n for n in dc["wifi"]}
    check("saved passwords are answered as passwordSet", by[NETS["psk"]].get("passwordSet") is True and by[NETS["peap"]]["eap"].get("passwordSet") is True
          and "passwordSet" not in by[A.open_ssid], by)
    hist = json.dumps(api("GET", f"/rest/private/configurations/{cfg['id']}/history"))
    check("the change history has no Wi-Fi password either", PSK not in hist and EAP_PW not in hist)
    rev = config_status().get("currentRevision")
    ok, msg, _ = save_config(again, dc)
    check("saving what the console got back (no passwords typed) is accepted", ok, msg)
    check("... and keeps the stored passwords: same revision for the phones", config_status().get("currentRevision") == rev)
    dc2 = json.loads(json.dumps(dc))
    next(n for n in dc2["wifi"] if n["ssid"] == NETS["sae"])["password"] = PSK + "-nueva"
    ok, msg, _ = save_config(again, dc2)
    check("typing a new password changes the revision", ok and config_status().get("currentRevision") != rev, msg)
    ok, msg, _ = save_config(again, dc)  # passwordSet again: keeps "-nueva" (the stored one)
    state["cfg"] = get_config(cfg["id"])
    return True


def t_validation(state, cfg, base):
    section("validation")
    def refused(name, dc, radio="keep", word=""):
        merged = dict(base)
        merged.update(dc)
        ok, msg, _ = save_config(cfg, merged, radio)
        check(name, not ok and "Wi" in str(msg) and word in str(msg), msg)
    refused("allow list without any network", {"wifiRules": {"ssidMode": "allow"}}, word="permitidas")
    refused("blocking new networks without bringing one", {"wifiRules": {"blockAdd": True}}, word="ninguna")
    refused("minimum security above every managed network",
            {"wifi": [{"ssid": "x", "password": "12345678"}], "wifiRules": {"minSecurity": "enterprise"}}, word="mínima")
    refused("a managed network in the deny list",
            {"wifi": [{"ssid": "x", "password": "12345678"}], "wifiRules": {"ssidMode": "deny", "ssids": ["x"]}}, word="bloqueadas")
    refused("a WPA password that is too short", {"wifi": [{"ssid": "x", "password": "corta"}]}, word="contraseña")
    refused("802.1X without the server's CA",
            {"wifi": [{"ssid": "x", "security": "EAP", "eap": {"method": "PEAP", "identity": "u", "password": "p", "domain": "d.test"}}]}, word="CA")
    refused("802.1X without the server's domain",
            {"wifi": [{"ssid": "x", "security": "EAP", "eap": {"method": "PEAP", "identity": "u", "password": "p", "caCertIds": [state["ca"]]}}]}, word="dominio")
    refused("EAP-TLS without a client certificate",
            {"wifi": [{"ssid": "x", "security": "EAP", "eap": {"method": "TLS", "domain": "d.test", "caCertIds": [state["ca"]]}}]}, word="cliente")
    refused("Wi-Fi forced off while the policy brings networks", {"wifi": [{"ssid": "x", "password": "12345678"}]}, radio=False, word="apagado")
    r = api("DELETE", f"/rest/private/wifi-certificates/{state['ca']}")
    check("a certificate a policy uses cannot be deleted", r.get("status") != "OK" and "política" in str(r.get("message")), r)


def t_networks(state):
    section(f"networks on the device (API {SDK})")
    st = wait_applied()
    check("the phone applied the policy", st.get("appliedRevision") == st.get("currentRevision"), st)
    detail = json.loads((st.get("lastCommand") or {}).get("detail") or "{}").get("outcomes", {})
    check("config.apply reports wifi and wifiRules applied", detail.get("wifi") == "applied" and detail.get("wifiRules") == "applied", detail)
    last = "tls" if SDK >= 29 else "opt"  # saved in order: wait for the last one
    blocks = until(lambda: (lambda b: b if NETS[last] in b and NETS["opt"] in b else None)(config_blocks()), 60) or {}
    listed = list_networks()

    def has(key, *patterns):
        text = blocks.get(NETS[key], "")
        return bool(text) and all(re.search(p, text, re.I | re.S) for p in patterns)

    modern = SDK >= 29
    check("PSK hidden: saved, WPA-PSK, hidden SSID", has("psk", r"KeyMgmt:.*WPA_PSK", r"HIDDEN: true"), blocks.get(NETS["psk"], "")[:300])
    check("WPA2/WPA3 mixed: saved as PSK (the system adds SAE on Android 12+)", has("mix", r"KeyMgmt:.*WPA_PSK"), blocks.get(NETS["mix"], "")[:300])
    if modern:
        check("WPA3-SAE: saved with SAE and management-frame protection", has("sae", r"KeyMgmt:.*SAE", r"PMF: true"), blocks.get(NETS["sae"], "")[:300])
        check("OWE: saved with OWE", has("owe", r"KeyMgmt:.*OWE"), blocks.get(NETS["owe"], "")[:300])
    else:
        check("WPA3-SAE and OWE are not saved on this Android (no API for them)", NETS["sae"] not in blocks and NETS["owe"] not in blocks)
    check("PEAP: EAP network with PEAP/MSCHAPv2, identities and domain",
          has("peap", r"KeyMgmt:.*(WPA_EAP|IEEE8021X)", r"eap[_ ]method:? *\"?PEAP|eap PEAP", r"MSCHAPV2", r"equipo@e2e\.test", r"anonimo@e2e\.test",
              r"domain_suffix_match \"radius\.e2e\.test\""),
          blocks.get(NETS["peap"], "")[-900:])
    check("PEAP: the CA is referenced by the network (not 'do not validate')", has("peap", r"ca_cert \"keystore://CACERT"), blocks.get(NETS["peap"], "")[-900:])
    check("TTLS: EAP network with TTLS/PAP and the alternative-subject match", has("ttls", r"eap[_ ]method:? *\"?TTLS|eap TTLS", r"PAP", r"altsubject_match \"DNS:radius\.e2e\.test\""), blocks.get(NETS["ttls"], "")[-900:])
    check("EAP-TLS: EAP network with TLS and a client certificate", has("tls", r"eap[_ ]method:? *\"?TLS|eap TLS", r"client_cert \"keystore://", r"(key_id|private_key|engine_id) \"\S+"), blocks.get(NETS["tls"], "")[-900:])
    check("no EAP password shows in the dump", EAP_PW not in "".join(blocks.values()))
    check("options: static IP, gateway and DNS", has("opt", r"IP assignment: STATIC", r"192\.168\.77\.50/24", r"192\.168\.77\.1"), blocks.get(NETS["opt"], "")[-900:])
    check("options: manual proxy with its exclusion", has("opt", r"Proxy settings: STATIC", r"proxy\.e2e\.test", r"3128"), blocks.get(NETS["opt"], "")[-900:])
    rep = managed_report(lambda r: len(r.get("networks") or []) >= 9 and net_status(r, A.open_ssid).get("connected"))
    opt = net_status(rep, NETS["opt"])
    dropped = opt.get("unsupportedOptions") or []
    if "mac" in dropped:
        skip("options: device MAC", "this Android refuses it; the agent reports it")
    elif SDK >= 29:
        check("options: device MAC (no randomization)", has("opt", r"macRandomizationSetting: 0"), blocks.get(NETS["opt"], "")[-900:])
    if "metered" in dropped:
        skip("options: metered", "this Android refuses it; the agent reports it")
    elif SDK >= 28:
        check("options: metered", has("opt", r"meteredOverride\W+1"), blocks.get(NETS["opt"], "")[-900:])
    check("options: auto-connect off (the network is disabled for auto-join)",
          has("opt", r"(DISABLED_BY_WIFI_MANAGER|NETWORK_SELECTION_PERMANENTLY_DISABLED|allowAutojoin=false|autoJoin.*false)"), blocks.get(NETS["opt"], "")[:900])
    if listed:
        types = {k: listed.get(NETS[k], (None, ""))[1] for k in NETS}
        check("`cmd wifi list-networks` lists them with their security",
              "psk" in types["psk"] and "sae" in types["sae"] and "owe" in types["owe"] and "enterprise" in types["peap"].replace("eap", "enterprise")
              and "enterprise" in types["tls"].replace("eap", "enterprise"), types)
        if SDK >= 31:
            check("WPA2/WPA3 mixed carries both types on Android 12+", "psk" in types["mix"] and "sae" in types["mix"], types["mix"])
    store = sh("su 0 ls /data/misc/user/0/cacerts-added/ 2>&1 || su root ls /data/misc/user/0/cacerts-added/ 2>&1")
    if re.search(r"^[0-9a-f]{8}\.\d+$", store, re.M):
        check("the CA is in the user's trusted store (installCaCert)", True)
    elif "not found" in store or "denied" in store or "inaccessible" in store:
        skip("the CA is in the user's trusted store", "no root on this device to list it; the agent's record follows")
    else:
        check("the CA is in the user's trusted store (installCaCert)", False, store)
    keys = sh(f"run-as {A.pkg} cat shared_prefs/mdm_wifi_certs.xml")
    check("the agent installed the CA and the EAP-TLS key pair (its record)", "ca:" in keys and "dc-wifi-" in keys, keys[:300])

    # what the phone reports per network
    open_net = net_status(rep, A.open_ssid)
    check("report: the open network in range is configured and connected", open_net.get("configured") and open_net.get("connected"), open_net)
    hidden = net_status(rep, NETS["psk"])
    check("report: the hidden PSK network is configured, no false 'out of range'", hidden.get("configured") and not hidden.get("reason"), hidden)
    mix = net_status(rep, NETS["mix"])
    check("report: a network that is not around says 'out of range'", mix.get("configured") and mix.get("reason") == "out_of_range", mix)
    sae = net_status(rep, NETS["sae"])
    if modern:
        check("report: WPA3-SAE configured", sae.get("configured") and sae.get("reason") in ("out_of_range", None), sae)
    else:
        check("report: WPA3-SAE 'not supported on this version'", not sae.get("configured") and sae.get("reason") == "unsupported", sae)
    check("report: auto-connect off is told apart", opt.get("configured") and opt.get("reason") in ("out_of_range", "disabled"), opt)
    check("report: the lock-down in force is listed", "lockManaged" in ((rep.get("rules") or {}).get("applied") or []), rep.get("rules"))
    state["report"] = rep


def t_mismatch(state, cfg, base):
    section("state: wrong security for a network in range")
    dc = full_policy(state, base)
    dc["wifi"][0] = {"id": "e2eopen", "ssid": A.open_ssid, "security": "WPA", "password": PSK}
    ok, msg, _ = save_config(state["cfg"], dc)
    check("policy saved with the in-range network as WPA", ok, msg)
    wait_applied()
    rep = managed_report(lambda r: net_status(r, A.open_ssid).get("reason") == "security_mismatch", 150)
    check("report: 'in range with another security'", net_status(rep, A.open_ssid).get("reason") == "security_mismatch", net_status(rep, A.open_ssid))
    ok, msg, _ = save_config(get_config(cfg["id"]), full_policy(state, base))
    wait_applied()
    until(lambda: wifi_connected_ssid() == A.open_ssid, 60)


def t_lockdown(state, cfg, base):
    section("lock-down")
    check("the lock-down of managed networks is on", sh("settings get global wifi_device_owner_configs_lockdown").strip() == "1")
    nets = list_networks()
    if nets and NETS["psk"] in nets:
        out = sh(f"cmd wifi forget-network {nets[NETS['psk']][0]}")
        time.sleep(2)
        check("`cmd wifi forget-network` of a managed network is refused", NETS["psk"] in list_networks(), out)
        # the user's own network is not locked
        sh("cmd wifi add-network DC-DelUsuario open")
        mine = until(lambda: list_networks().get("DC-DelUsuario"), 10)
        check("a network of the user can still be added", bool(mine))
        if mine:
            sh(f"cmd wifi forget-network {mine[0]}")
            check("... and forgotten", not until(lambda: "DC-DelUsuario" not in list_networks() or None, 10) is None)
    else:
        skip("forget-network refused", "`cmd wifi` has no network commands before Android 11; the setting above is what the Settings app obeys")

    dc = full_policy(state, base)
    dc["wifiRules"] = {"lockManaged": True, "blockAdd": True, "blockConfig": True, "blockState": True, "minSecurity": "personal",
                       "ssidMode": "deny", "ssids": ["DC-Prohibida"], "safeguardMinutes": 30}
    ok, msg, _ = save_config(get_config(cfg["id"]), dc)
    check("policy saved with every block (deny list, minimum security personal)", ok, msg)
    wait_applied()
    dp = sh("dumpsys device_policy")
    check("DISALLOW_CONFIG_WIFI is set", until(lambda: restricted("no_config_wifi"), 20))
    rep = managed_report(lambda r: "blockConfig" in ((r.get("rules") or {}).get("applied") or []))
    applied = (rep.get("rules") or {}).get("applied") or []
    unsupported = (rep.get("rules") or {}).get("unsupported") or []
    if SDK >= 33:
        check("DISALLOW_ADD_WIFI_CONFIG and DISALLOW_CHANGE_WIFI_STATE are set", restricted("no_add_wifi_config") and restricted("no_change_wifi_state"))
        check("the SSID deny list is in the device policy", "DC-Prohibida" in dp or "WifiSsidPolicy" in dp or "mWifiSsidPolicy" in dp, "")
        check("report: every rule applied", all(k in applied for k in ("lockManaged", "blockAdd", "blockConfig", "blockState", "ssidPolicy", "minSecurity")), rep.get("rules"))
        out = sh("cmd wifi add-network DC-NoDebe open")
        time.sleep(2)
        check("adding a network is refused (DISALLOW_ADD_WIFI_CONFIG)", "DC-NoDebe" not in list_networks(), out)
        # minimum security "personal": the open network may not be used any more
        gone = until(lambda: wifi_connected_ssid() != A.open_ssid, 60)
        check("minimum security 'personal' takes the phone off the open network", gone, wifi_connected_ssid())
    else:
        check("report: Android 13 rules are listed as not available on this version",
              all(k in unsupported for k in ("blockAdd", "blockState", "ssidPolicy", "minSecurity")) and "blockConfig" in applied, rep.get("rules"))

    if SDK >= 33:
        dc["wifiRules"] = {"lockManaged": True, "ssidMode": "allow", "ssids": [], "safeguardMinutes": 30}
        dc["wifi"] = [n for n in dc["wifi"] if n["ssid"] != A.open_ssid]  # "only corporate networks", the open one is not one
        ok, msg, _ = save_config(get_config(cfg["id"]), dc)
        check("policy saved with an allow list of the managed networks only", ok, msg)
        wait_applied()
        sh("cmd wifi connect-network %s open" % A.open_ssid)
        time.sleep(8)
        check("allow list: the network outside it cannot be joined", wifi_connected_ssid() != A.open_ssid, wifi_connected_ssid())
        dc["wifiRules"]["ssids"] = [A.open_ssid]
        ok, msg, _ = save_config(get_config(cfg["id"]), dc)
        wait_applied()
        sh("cmd wifi connect-network %s open" % A.open_ssid)
        check("allow list: once listed, the same network connects", until(lambda: wifi_connected_ssid() == A.open_ssid, 60), wifi_connected_ssid())
    ok, msg, _ = save_config(get_config(cfg["id"]), full_policy(state, base))
    wait_applied()
    check("lifting the rules clears the restrictions", not restricted("no_config_wifi") and not restricted("no_add_wifi_config"))
    until(lambda: wifi_connected_ssid() == A.open_ssid, 60)


def t_radio(state, cfg, base):
    section("radio")
    ok, msg, _ = save_config(get_config(cfg["id"]), dict(base), radio=False)
    check("policy saved: Wi-Fi forced off, no networks", ok, msg)
    wait_applied()
    off = until(lambda: "disabled" in sh("cmd wifi status 2>/dev/null || dumpsys wifi | grep -m1 'Wi-Fi is'").lower(), 40)
    check("the radio is off", off)
    sh("svc wifi enable")
    time.sleep(6)
    again = until(lambda: "disabled" in sh("cmd wifi status 2>/dev/null || dumpsys wifi | grep -m1 'Wi-Fi is'").lower(), 90)
    check("switched on by hand, the agent switches it off again", again)
    rep = managed_report(lambda r: "radioOff" in ((r.get("rules") or {}).get("applied") or []))
    check("report: 'Wi-Fi forced off'", "radioOff" in ((rep.get("rules") or {}).get("applied") or []), rep.get("rules"))
    ok, msg, _ = save_config(get_config(cfg["id"]), full_policy(state, base), radio=None)
    wait_applied()
    sh("svc wifi enable")
    check("back to unmanaged: Wi-Fi comes back and reconnects", until(lambda: wifi_connected_ssid() == A.open_ssid, 90), wifi_connected_ssid())


def t_safeguard(state, cfg, base):
    section("safeguard (about 3 minutes)")
    if SDK < 33:
        dc = dict(base)
        dc["wifi"] = [{"id": "e2epsk", "ssid": NETS["psk"], "security": "WPA", "password": PSK}]
        dc["wifiRules"] = {"lockManaged": True, "blockConfig": True, "safeguardMinutes": 1}
        cut = lambda: sh("svc wifi disable")  # noqa: E731  (no SSID policy before Android 13: the test takes Wi-Fi away itself)
    else:
        dc = dict(base)
        dc["wifi"] = [{"id": "e2epsk", "ssid": NETS["psk"], "security": "WPA", "password": PSK}]
        dc["wifiRules"] = {"lockManaged": True, "blockAdd": True, "ssidMode": "allow", "safeguardMinutes": 1}
        cut = lambda: None  # noqa: E731  (the allow list itself takes the phone off its only network)
    ok, msg, _ = save_config(get_config(cfg["id"]), dc)
    check("policy saved: a lock-down that leaves this phone without Wi-Fi", ok, msg)
    wait_applied()
    time.sleep(5)
    rep = managed_report(lambda r: (r.get("rules") or {}).get("applied"))
    check("with mobile data still up, nothing is relaxed", not (rep.get("rules") or {}).get("relaxed"), rep.get("rules"))
    since = int(time.time() * 1000)
    sh("svc data disable")
    cut()
    print("        ... no Wi-Fi and no mobile data; waiting for the agent to relax the lock-down", flush=True)
    if SDK >= 33:
        relaxed = until(lambda: not restricted("no_add_wifi_config"), 240, 10)
    else:
        relaxed = until(lambda: not restricted("no_config_wifi"), 240, 10)
    check("after the grace period the phone lifts the restrictive rules by itself", relaxed)
    check("the managed networks stay locked meanwhile", sh("settings get global wifi_device_owner_configs_lockdown").strip() == "1")
    sh("svc wifi enable")
    back = until(lambda: wifi_connected_ssid() == A.open_ssid, 120, 5)
    if not back:
        sh("cmd wifi connect-network %s open" % A.open_ssid)
        back = until(lambda: wifi_connected_ssid() == A.open_ssid, 60, 5)
    check("the phone gets back on a network", back, wifi_connected_ssid())
    sh("svc data enable")
    rep = managed_report(lambda r: (r.get("rules") or {}).get("relaxed"), 150)
    check("report: the console is told the lock-down was relaxed, and until when",
          (rep.get("rules") or {}).get("relaxed") and (rep.get("rules") or {}).get("relaxedUntil", 0) > since, rep.get("rules"))
    ev = api("GET", f"/rest/private/agent/v1/devices/{A.device_id}/events?since={since - 60000}").get("data") or []
    check("event 'wifiLockdownRelaxed' reached the server", any(e.get("type") == "wifiLockdownRelaxed" for e in ev), [e.get("type") for e in ev][-8:])
    ok, msg, _ = save_config(get_config(cfg["id"]), full_policy(state, base))
    wait_applied()
    rep = managed_report(lambda r: not (r.get("rules") or {}).get("relaxed"), 120)
    check("a corrected policy ends the relaxed state", not (rep.get("rules") or {}).get("relaxed"), rep.get("rules"))


def t_removal(state, cfg, base):
    section("removal")
    sh("cmd wifi add-network DC-DelUsuario open")
    ok, msg, _ = save_config(get_config(cfg["id"]), dict(base), radio=None)
    check("policy saved without any Wi-Fi", ok, msg)
    wait_applied()
    left = until(lambda: (lambda s: s if not any(v in s for v in NETS.values()) else None)(saved_ssids()), 60)
    now = saved_ssids()
    check("every managed network left the phone", left is not None, sorted(now))
    if SDK >= 30:
        check("the user's own network stayed", "DC-DelUsuario" in now, sorted(now))
        nets = list_networks()
        if "DC-DelUsuario" in nets:
            sh(f"cmd wifi forget-network {nets['DC-DelUsuario'][0]}")
    check("the lock-down setting is off again", sh("settings get global wifi_device_owner_configs_lockdown").strip() in ("0", "null"))
    keys = sh(f"run-as {A.pkg} cat shared_prefs/mdm_wifi_certs.xml")
    check("the certificates were removed with their networks", "ca:" not in keys and "dc-wifi-" not in keys, keys[:300])
    rep = until(lambda: (telemetry().get("dynamic") or {}).get("managedWifi") is None or None, 90, 5)
    check("report: nothing managed any more", rep is not None, (telemetry().get("dynamic") or {}).get("managedWifi"))


def main():
    global SDK
    login()
    SDK = int(sh("getprop ro.build.version.sdk").strip())
    print(f"device {A.serial} (API {SDK}), policy of device {A.device_id}")
    cfg = find_config()
    original = cfg.get("dcPolicy")
    original_radio = cfg.get("wifi")
    base = json.loads(original) if original else {}
    leftover = all(str(n.get("id", "")).startswith("e2e") for n in base.get("wifi") or [])  # an earlier --keep run
    if leftover:
        original = json.dumps({k: v for k, v in base.items() if k not in ("wifi", "wifiRules")}) or None
        original = None if original == "{}" else original
    if base.get("wifi") and not leftover:
        sys.exit("the device's policy already has Wi-Fi networks; their passwords cannot be read back to restore them. "
                 "Run this on a device whose policy has none.")
    base.pop("wifi", None)
    base.pop("wifiRules", None)
    state = {}
    try:
        t_certificates(state)
        if not (state.get("ca") and state.get("client")):
            sys.exit("certificates could not be uploaded; nothing else can run")
        if not t_secrets(state, cfg, base):
            sys.exit("the policy could not be saved")
        if want("validation"):
            t_validation(state, cfg, base)
            save_config(get_config(cfg["id"]), full_policy(state, base))
        if want("networks"):
            t_networks(state)
        if want("mismatch"):
            t_mismatch(state, cfg, base)
        if want("lockdown"):
            t_lockdown(state, cfg, base)
        if want("radio"):
            t_radio(state, cfg, base)
        if want("safeguard") and not A.skip_safeguard:
            t_safeguard(state, cfg, base)
        if want("removal"):
            t_removal(state, cfg, base)
    finally:
        sh("svc data enable; svc wifi enable")
        now = None if A.keep else get_config(cfg["id"])
        if now:
            now["dcPolicy"] = original
            now["wifi"] = original_radio
            # the original policy may have Wi-Fi passwords: they were never read, so they are kept by passwordSet
            api("PUT", "/rest/private/configurations", now)
        if not A.keep:
            cleanup_certificates(state)
            # the open network was managed during the test, so it left with the policy: give it back to the device
            if SDK >= 30:
                time.sleep(5)
                sh("cmd wifi connect-network %s open" % A.open_ssid)
    failed = [r for r in RESULTS if not r[1]]
    print(f"\nRESULT: PASS={len(RESULTS) - len(failed)} FAIL={len(failed)}")
    for name, _, detail in failed:
        print(f"  FAILED: {name}  {str(detail)[:300]}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
