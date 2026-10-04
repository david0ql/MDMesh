#!/usr/bin/env python3
"""
Remote diagnosis of DallyControl devices from a terminal (no browser): what a phone is doing, what made it ring, and
the actions that fix it. Talks to the console's REST API as an administrator.

  DALLYCONTROL_ADMIN_PASSWORD=… scripts/dc-diag.py devices
  scripts/dc-diag.py status   <device>          last report, live channel, agent, battery, network, kiosk
  scripts/dc-diag.py events   <device> [hours]  events (sound alerts, SIM, boots, apps…), default 48 h
  scripts/dc-diag.py sounds   <device> [hours]  only the sound events (what rang, how long, its likely source)
  scripts/dc-diag.py commands <device> [n]      last n commands and their results
  scripts/dc-diag.py diagnose <device>          live snapshot from the phone (agent 0.7.4+)
  scripts/dc-diag.py silence  <device> [min]    every volume to zero for min minutes (default 10)
  scripts/dc-diag.py notif    <device> <key> <open|fullscreen|dismiss|N>   act on a notification from `diagnose`

<device> is the serial / name (e.g. ZY32FCBNM5) or the device number. Server: --server URL or DALLYCONTROL_SERVER
(default https://mdm.felapp.co); user: DALLYCONTROL_ADMIN_USER (default admin).
"""
import hashlib, http.cookiejar, json, os, sys, time, urllib.request

SERVER = os.environ.get('DALLYCONTROL_SERVER', 'https://mdm.felapp.co')
args = sys.argv[1:]
if '--server' in args:
    i = args.index('--server'); SERVER = args[i + 1]; del args[i:i + 2]
B = SERVER.rstrip('/') + '/rest'
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def call(method, path, body=None):
    req = urllib.request.Request(B + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json', 'User-Agent': 'curl/8 dc-diag'})
    out = json.loads(op.open(req, timeout=30).read() or b'null')
    if isinstance(out, dict) and out.get('status') not in (None, 'OK'):
        raise SystemExit(f'error: {out.get("status")} {out.get("message")}')
    return out.get('data') if isinstance(out, dict) else out


def login():
    pw = os.environ.get('DALLYCONTROL_ADMIN_PASSWORD')
    if not pw:
        raise SystemExit('set DALLYCONTROL_ADMIN_PASSWORD')
    call('POST', '/public/auth/login', {'login': os.environ.get('DALLYCONTROL_ADMIN_USER', 'admin'),
                                         'password': hashlib.md5(pw.encode()).hexdigest().upper()})


def live_numbers():
    try:
        return set(call('GET', '/private/agent/v1/live') or [])
    except Exception:
        return set()  # older server without /live


def t(ms):
    return time.strftime('%m-%d %H:%M:%S', time.localtime(ms / 1000)) if ms else '—'


def devices():
    return call('POST', '/private/devices/search', {'pageSize': 500, 'pageNum': 1})['devices']['items']


def find(ref):
    for d in devices():
        if ref in (d['number'], d.get('description'), str(d['id'])) or d['number'].startswith(ref):
            return d
    raise SystemExit(f'no device "{ref}"')


def run(number, typ, payload=None, wait=75):
    since = int(time.time() * 1000) - 5000
    q = call('POST', f'/private/agent/v1/devices/{number}/commands',
             {'type': typ, 'requiresCapability': typ, **({} if payload is None else {'payload': json.dumps(payload)})})
    end = time.time() + wait
    while time.time() < end:
        time.sleep(2)
        c = next((x for x in call('GET', f'/private/agent/v1/devices/{number}/commands?since={since}') if str(x['id']) == str(q['id'])), None)
        if c and c['status'] not in ('pending', 'delivered'):
            return c
    return {'status': 'timeout', 'detail': f'no answer in {wait}s (offline, or agent older than 0.7.4); command {q["id"]} stays queued'}


def main():
    if not args or args[0] in ('-h', '--help'):
        print(__doc__); return
    login()
    cmd = args[0]
    if cmd == 'devices':
        live = live_numbers()
        for d in devices():
            print(f"{d.get('description') or '-':20} {d['number']}  último reporte {t(d.get('lastUpdate'))}  canal en vivo: {'sí' if d['number'] in live else 'no'}")
        return
    d = find(args[1]); n = d['number']
    if cmd == 'status':
        live = n in live_numbers()
        s = call('GET', f'/private/agent/v1/devices/{n}/state') or {}
        tel = json.loads(s.get('telemetry') or '{}'); dyn = tel.get('dynamic', {})
        print(f"{d.get('description')} ({n})  política {d.get('configurationId')}")
        print(f"  último reporte {t(s.get('updatedAt'))} · canal en vivo {'abierto' if live else 'cerrado'} · agente {s.get('agentVersion')} · modo {s.get('powerMode')}")
        print(f"  batería {dyn.get('batteryPct')}% · red {dyn.get('networkType')} {dyn.get('wifiSsid') or ''} · pantalla {'encendida' if dyn.get('screenOn') else 'apagada'} · quiosco {dyn.get('kioskActive')}")
        print(f"  SIM {dyn.get('sim')} · último arranque {t(s.get('lastBootAt'))}")
    elif cmd in ('events', 'sounds'):
        hours = float(args[2]) if len(args) > 2 else 48
        ev = call('GET', f"/private/agent/v1/devices/{n}/events?since={int((time.time() - hours * 3600) * 1000)}")
        for e in sorted(ev, key=lambda e: e['ts']):
            if cmd == 'sounds' and e['type'] not in ('soundAlert', 'soundEnded'):
                continue
            if cmd == 'events' and e['type'] == 'commandResult':
                continue
            print(f"{t(e['ts'])}  {e['type']:22} {(e.get('detail') or '')[:160]}")
    elif cmd == 'commands':
        k = int(args[2]) if len(args) > 2 else 20
        for c in sorted(call('GET', f'/private/agent/v1/devices/{n}/commands'), key=lambda c: c['id'])[-k:]:
            print(f"{c['id']:>6} {t(c.get('createdAt'))} {c['type']:26} {c.get('subject') or '':32} {c['status']:9} {(c.get('detail') or '')[:90]}")
    elif cmd == 'diagnose':
        r = run(n, 'device.diagnose')
        if r['status'] != 'done':
            raise SystemExit(f"{r['status']}: {r.get('detail')}")
        snap = json.loads(r['detail'])
        print(json.dumps(snap, ensure_ascii=False, indent=2))
    elif cmd == 'silence':
        print(run(n, 'device.silence', {'minutes': int(args[2]) if len(args) > 2 else 10}))
    elif cmd == 'notif':
        print(run(n, 'device.notificationAction', {'key': args[2], 'action': args[3]}))
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
