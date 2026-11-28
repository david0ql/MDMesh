#!/usr/bin/env node
// End-to-end check of remote support through the agent itself (ADR 0012) on an emulator or a phone:
// console API -> server relay -> the agent's session channel -> the agent's screen service, voice, chat and files.
// No browser: this script is the operator's side of the session socket, so every check is on the wire.
// (scripts/remote-e2e.mjs --support runs it; scripts/remote-support-console-e2e.mjs drives the console's screen.)
//
//   node scripts/remote-support-e2e.mjs --api http://localhost:8088 --device <id> --serial emulator-5554
//        [--pkg com.dallycontrol.agent.debug] [--authorize] [--skip voice,files,...] [--only screen,control]
//
// --authorize   the device was enrolled QR-style (scripts/adb-enroll.sh WITHOUT --remote, so the agent holds no
//               WRITE_SECURE_SETTINGS): do the ONE tap of the enrollment on the phone's own screens through adb
//               (the guided screen -> Accessibility -> the agent's service -> the switch -> Allow), then go on.
//               Without it the script expects the device to report "ready" already.
//
// Checks (PASS/FAIL each, exit 0 only if none fails):
//   ready      the console sees "remoto listo" (after the one tap, when --authorize)
//   screen     unattended start: session opened, device joined, first image; time to first image < 10 s
//   control    Home pressed by the operator leaves Settings; a button found by its on-screen bounds is tapped and
//              its effect read back over adb; touch-to-image latency < 1 s; view-only refuses input
//   chat       operator -> phone (the support screen opens and shows the line), phone -> operator (typed and sent
//              THROUGH the remote session: tap the field, type, tap Enviar)
//   voice      call accepted on the phone; operator's audio reaches the phone (frames + level reported by the
//              agent); the phone's audio reaches the operator (level and pitch of the agent's 440 Hz test tone);
//              the real microphone path opens (recording, frames flowing); hang up
//   files      a file sent to the phone lands in Descargas/Soporte (same bytes, read back over adb) and is
//              fetched back through the session
//   clipboard  text set on the phone
//   log        the session is in the log with its duration, chat lines, file and call
//   policy     the configuration's "Soporte remoto" section reaches the phone: with "se contesta sola" and the notice
//              off, a call is established with nobody at the phone and no notice is shown (the policy is restored)
import { execFileSync } from 'child_process';
import crypto from 'crypto';
import os from 'os';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const flag = (k) => process.argv.includes(k);
const api = arg('--api', 'http://localhost:8088').replace(/\/$/, '');
const device = arg('--device');
const serial = arg('--serial');
const pkg = arg('--pkg', 'com.dallycontrol.agent.debug');
const user = arg('--admin-user', 'admin');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD || 'admin';
const only = (arg('--only', '') || '').split(',').filter(Boolean);
const skip = (arg('--skip', '') || '').split(',').filter(Boolean);
const want = (name) => (only.length ? only.includes(name) : !skip.includes(name));
if (!device || !serial) { console.error('--device and --serial are required'); process.exit(2); }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...a) => execFileSync('adb', ['-s', serial, ...a], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const adbBin = (...a) => execFileSync('adb', ['-s', serial, ...a], { maxBuffer: 256 * 1024 * 1024 });
const sdk = Number(adb('shell', 'getprop', 'ro.build.version.sdk').trim());
const topOnce = () => (adb('shell', 'dumpsys activity activities')
  .match(/(?:topResumedActivity|mResumedActivity|mFocusedActivity)[=:].*? (\S+\/\S+?)[ }]/) || [])[1] || '';
const top = () => { for (let i = 0; i < 4; i++) { const t = topOnce(); if (t) return t; } return ''; };

let pass = 0, fail = 0;
const measures = {};
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  [${detail}]` : ''}`);
  ok ? pass++ : fail++;
  return ok;
};

// --- console API -----------------------------------------------------------------------------------------------
let cookie = '';
async function rest(method, path, body) {
  // A busy edge can answer 502 for a moment (or its recovery page): try again before calling it a failure.
  for (let attempt = 0; ; attempt++) {
    const r = await fetch(`${api}/rest${path}`, {
      method, headers: { 'Content-Type': 'application/json', Cookie: cookie },
      body: body === undefined ? undefined : JSON.stringify(body),
    }).catch(() => null);
    const j = r ? await r.json().catch(() => null) : null;
    if (j && j.status) {
      const set = r.headers.getSetCookie?.() || [];
      if (set.length) cookie = set.map((c) => c.split(';')[0]).join('; ');
      return j;
    }
    if (attempt >= 5) return {};
    await sleep(2000);
  }
}
async function login() {
  const md5 = crypto.createHash('md5').update(password).digest('hex').toUpperCase();
  const j = await rest('POST', '/public/auth/login', { login: user, password: md5 });
  if (j.status !== 'OK') throw new Error(`login rejected: ${JSON.stringify(j).slice(0, 200)}`);
}
const status = async () => (await rest('GET', `/private/support/v1/devices/${device}`)).data || {};

// --- phone UI over adb (what a person would see and tap) ---------------------------------------------------------
// NOTE: `uiautomator dump` takes accessibility over while it runs: Android unbinds every accessibility service
// (the agent's screen service too) and rebinds it a moment later. That is this test reading the screen, not something
// a real session meets; after each dump the test waits for the service to be bound again (serviceBack).
function dumpUi() {
  for (let i = 0; i < 6; i++) {
    try {
      adb('shell', 'uiautomator', 'dump', '/sdcard/dc-ui.xml');
      const xml = adb('shell', 'cat', '/sdcard/dc-ui.xml');
      // A busy phone sometimes answers with an empty window (one node, no size): that is not the screen.
      if (xml.includes('<hierarchy') && (xml.match(/<node /g) || []).length > 2) return xml;
    } catch { /* the UI was busy: try again */ }
    execFileSync('sleep', ['0.7']);
  }
  return '';
}
let current = null; // the open session, asked whether the screen service is connected again
async function serviceBack() {
  for (let i = 0; i < 40; i++) {
    const bound = adb('shell', 'dumpsys', 'accessibility').split('Bound services')[1] || '';
    if (bound.split('}')[0].includes('SupportAccessibilityService')) {
      if (!current) return true;
      const from = Date.now();
      current.send({ t: 'info.get' });
      const info = await current.until(() => current.msg('info', from), 2000);
      if (info && info.connected) return true;
    }
    await sleep(300);
  }
  return false;
}
/** Nodes of a uiautomator dump as { text, desc, cls, id, clickable, checkable, checked, b:[x1,y1,x2,y2] }. */
function nodes(xml) {
  const out = [];
  for (const m of xml.matchAll(/<node [^>]*>/g)) {
    const a = (k) => (m[0].match(new RegExp(` ${k}="([^"]*)"`)) || [])[1] || '';
    const b = (a('bounds').match(/\d+/g) || []).map(Number);
    if (b.length === 4) {
      out.push({ text: a('text'), desc: a('content-desc'), cls: a('class'), id: a('resource-id'), pkg: a('package'),
        clickable: a('clickable') === 'true', checkable: a('checkable') === 'true', checked: a('checked') === 'true', b });
    }
  }
  return out;
}
const center = (n) => [Math.round((n.b[0] + n.b[2]) / 2), Math.round((n.b[1] + n.b[3]) / 2)];
const adbTap = (n) => { const [x, y] = center(n); adb('shell', 'input', 'tap', String(x), String(y)); };
function screenSize() {
  const m = adb('shell', 'wm', 'size').match(/(\d+)x(\d+)/g).pop().match(/(\d+)x(\d+)/);
  let w = Number(m[1]), h = Number(m[2]);
  const rot = Number((adb('shell', 'dumpsys', 'input').match(/SurfaceOrientation: (\d)/) || [])[1] || 0);
  if (rot % 2) [w, h] = [h, w];
  return [w, h];
}
async function waitUi(pred, ms = 12000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const ns = nodes(dumpUi());
    const hit = pred(ns);
    if (hit) return hit;
    await sleep(500);
  }
  return null;
}

/**
 * The visible views of the agent's own screen on top (class + absolute bounds), read from `dumpsys activity top`.
 * It needs no UiAutomation, so it works when `uiautomator dump` does not answer on a busy phone, and it does not
 * unbind the screen service. No texts: the support screen's layout order is known (see supportScreen()).
 */
function ownViews() {
  const text = adb('shell', 'dumpsys', 'activity', 'top');
  // Newer Android releases dump the top activity of every task: take the support screen's own section.
  const own = text.search(/ACTIVITY \S*support\.SupportActivity/);
  const next = own < 0 ? -1 : text.indexOf('\nTASK ', own);
  const at = own < 0 ? -1 : text.indexOf('View Hierarchy:', own);
  if (at < 0 || (next > 0 && at > next)) return [];
  const out = []; const stack = [];
  for (const line of text.slice(at).split('\n').slice(1)) {
    const m = line.match(/^(\s+)([\w.$]+)\{\w+ ([VIG])\S* \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)/);
    if (!m) { if (out.length) break; continue; }
    const depth = m[1].length;
    while (stack.length && stack[stack.length - 1].depth >= depth) stack.pop();
    const parent = stack[stack.length - 1] || { x: 0, y: 0, visible: true };
    const node = { depth, cls: m[2], visible: parent.visible && m[3] === 'V', x: parent.x + Number(m[4]), y: parent.y + Number(m[5]) };
    node.b = [node.x, node.y, parent.x + Number(m[6]), parent.y + Number(m[7])];
    node.text = '';
    stack.push(node); out.push(node);
  }
  return out.filter((n) => n.visible && n.b[2] > n.b[0] && n.b[3] > n.b[1]);
}
/** The support screen's controls by layout order: call buttons (Aceptar|Silenciar, Rechazar|Colgar), field, Enviar. */
function supportScreen() {
  if (!/support\.SupportActivity/.test(top())) return null;
  const views = ownViews();
  const buttons = views.filter((v) => /\.Button$/.test(v.cls));
  const field = views.find((v) => /EditText$/.test(v.cls));
  const scroll = views.find((v) => /ScrollView$/.test(v.cls));
  const bubbles = scroll ? views.filter((v) => /TextView$/.test(v.cls) && v.depth > scroll.depth && v.b[1] >= scroll.b[1] && v.b[3] <= scroll.b[3] + 4000).length : 0;
  const inCall = buttons.length >= 4; // two call buttons + Enviar + Cerrar
  return { field, bubbles, first: inCall ? buttons[0] : null, second: inCall ? buttons[1] : null, send: buttons[buttons.length - 2] };
}

/** The session's own ongoing notification (id 7310) is posted: not its channel, nor Android's group summary of it. */
function noticeShown() {
  const re = new RegExp(`NotificationRecord\\([^\\n]*pkg=${pkg.replace(/\./g, '\\.')} [^\\n]*id=7310 [^\\n]*channel=dc_support\\b`);
  return re.test(adb('shell', 'dumpsys', 'notification', '--noredact'));
}

/** The one tap of a QR enrollment, done on the phone's own screens. */
async function authorizeOnPhone() {
  const label = /Soporte remoto DallyControl|DallyControl remote support/i;
  // The guided screen opens by itself right after enrollment; the console can also bring it up.
  let open = await waitUi((ns) => ns.find((n) => /^(Abrir ajustes|Open settings)$/i.test(n.text)), 6000);
  if (!open) {
    await rest('POST', `/private/agent/v1/devices/${device}/commands`, { type: 'remote.inputSetup' });
    open = await waitUi((ns) => ns.find((n) => /^(Abrir ajustes|Open settings)$/i.test(n.text)), 25000);
  }
  if (!check('the guided authorisation screen is shown on the phone', !!open)) return false;
  adbTap(open);
  // Accessibility list -> (Android 12+: "Downloaded apps" / "Installed apps" may be a sub-page) -> the service.
  let entry = null;
  for (let i = 0; i < 14 && !entry; i++) {
    const all = nodes(dumpUi());
    const ns = all.filter((n) => n.pkg !== pkg); // Settings' own screens, not the guided screen's text
    if (!ns.length) {
      // Still (or again) on the guided screen: Settings has not come up yet, so tap the button again.
      const again = all.find((n) => /^(Abrir ajustes|Open settings)$/i.test(n.text));
      if (again && i > 0 && i % 5 === 0) adbTap(again);
      await sleep(1500);
      continue;
    }
    entry = ns.find((n) => label.test(n.text));
    if (entry) break;
    const sub = ns.find((n) => /Downloaded apps|Installed (apps|services)|Apps descargadas|Apps instaladas|Servicios instalados|Servicios descargados/i.test(n.text));
    if (sub) adbTap(sub); else adb('shell', 'input', 'swipe', '500', '1500', '500', '700', '200');
    await sleep(900);
  }
  if (!check('the agent\'s service is listed in Accessibility', !!entry,
    entry ? '' : `on screen: ${nodes(dumpUi()).map((n) => n.text).filter(Boolean).slice(0, 6).join(' / ')} (${top()})`)) return false;
  adbTap(entry);
  const sw = await waitUi((ns) => {
    const own = ns.filter((n) => n.pkg !== pkg);
    return own.find((n) => n.checkable && /Switch/i.test(n.cls)) || own.find((n) => n.checkable);
  }, 10000);
  if (!check('its switch is shown', !!sw)) return false;
  if (!sw.checked) adbTap(sw);
  // Android asks to confirm full control.
  const allow = await waitUi((ns) => ns.find((n) => /^(Allow|Permitir|OK|Aceptar)$/i.test(n.text)), 6000);
  if (allow) adbTap(allow);
  let on = false;
  for (let i = 0; i < 20 && !on; i++) {
    on = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').includes(`${pkg}/`);
    if (!on) await sleep(500);
  }
  check('one tap on the phone switched the screen service on', on);
  await sleep(1500);
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  return on;
}

// --- operator side of the session socket ---------------------------------------------------------------------------
class Session {
  constructor() { this.frames = []; this.msgs = []; this.audio = []; this.files = {}; this.waiters = []; this.seq = -1; }
  async open(viewOnly = false) {
    this.t0 = Date.now();
    const r = await rest('POST', `/private/support/v1/devices/${device}/start`, { viewOnly });
    if (r.status !== 'OK') throw new Error(`start refused: ${r.message}`);
    this.info = r.data;
    let ws = null;
    for (let attempt = 0; !ws; attempt++) { // a busy edge may refuse the upgrade for a moment
      const candidate = new WebSocket(api.replace(/^http/, 'ws') + r.data.socket);
      candidate.binaryType = 'arraybuffer';
      const ok = await new Promise((res) => { candidate.onopen = () => res(true); candidate.onerror = () => res(false); });
      if (ok) ws = candidate;
      else if (attempt >= 4) throw new Error('the session socket could not be opened');
      else await sleep(2000);
    }
    this.ws = ws;
    ws.onmessage = (e) => this.on(e.data);
    ws.onclose = () => { this.closed = true; this.wake(); };
    ws.send(JSON.stringify({ t: 'auth', token: r.data.token }));
    this.ping = setInterval(() => { try { ws.send('{"t":"ping"}'); } catch { /* closed */ } }, 20000);
    current = this;
    return this;
  }
  on(data) {
    if (typeof data === 'string') {
      const m = JSON.parse(data);
      m.at = Date.now();
      this.msgs.push(m);
    } else {
      const b = Buffer.from(data);
      if (b[0] === 1) {
        const seq = b.readUInt32BE(1);
        this.frames.push({ seq, w: b.readUInt16BE(5), h: b.readUInt16BE(7), jpeg: b.subarray(9), at: Date.now() });
        this.send({ t: 'ack', seq });
      } else if (b[0] === 2) {
        this.audio.push(b.subarray(1));
      } else if (b[0] === 3) {
        (this.files[b[1]] ||= []).push(b.subarray(2));
      }
    }
    this.wake();
  }
  wake() { const w = this.waiters; this.waiters = []; w.forEach((f) => f()); }
  send(o) { if (this.ws.readyState === 1) this.ws.send(JSON.stringify(o)); }
  bin(kind, payload) { if (this.ws.readyState === 1) this.ws.send(Buffer.concat([Buffer.from([kind]), payload])); }
  /** Wait until pred() is truthy; returns it (or null on timeout). */
  async until(pred, ms = 10000) {
    const end = Date.now() + ms;
    for (;;) {
      const v = pred();
      if (v) return v;
      const left = end - Date.now();
      if (left <= 0 || this.closed) return pred() || null;
      await new Promise((res) => { this.waiters.push(res); setTimeout(res, Math.min(left, 250)); });
    }
  }
  /** Wait until no new image has arrived for [ms] (the screen is still). */
  async quiet(ms = 1200, limit = 8000) {
    const end = Date.now() + limit;
    while (Date.now() < end) {
      const last = this.frames.length ? this.frames[this.frames.length - 1].at : 0;
      if (Date.now() - last >= ms) return;
      await sleep(100);
    }
  }
  msg(type, after = 0, pred = () => true) { return this.msgs.find((m) => m.t === type && m.at >= after && pred(m)); }
  last(type) { return [...this.msgs].reverse().find((m) => m.t === type); }
  async close() {
    if (current === this) current = null;
    clearInterval(this.ping);
    this.send({ t: 'bye' });
    await sleep(300);
    try { this.ws.close(); } catch { /* already closed */ }
    await rest('POST', `/private/support/v1/devices/${device}/stop`);
  }
  /** A tap at device pixel (x, y), as a fraction of the screen. */
  tapPx(x, y, size) { this.send({ t: 'tap', x: x / (size[0] - 1), y: y / (size[1] - 1) }); }
  tapNode(n, size) { const [x, y] = center(n); this.tapPx(x, y, size); }
}

// --- mu-law (same maths as the agent's MuLaw.kt and the console's mulaw.ts) -------------------------------------------
function muEncode(s) {
  const sign = s < 0 ? 0x80 : 0; let p = Math.min(Math.abs(s), 32635) + 0x84;
  let exp = 7; for (let mask = 0x4000; exp > 0 && !(p & mask); mask >>= 1) exp--;
  return ~(sign | (exp << 4) | ((p >> (exp + 3)) & 0x0f)) & 0xff;
}
function muDecode(b) {
  const u = ~b & 0xff; const mag = ((((u & 0x0f) << 3) + 0x84) << ((u >> 4) & 7)) - 0x84;
  return u & 0x80 ? -mag : mag;
}
const toneFrame = (hz, pos) => Buffer.from(Array.from({ length: 320 }, (_, i) => muEncode(Math.round(12000 * Math.sin(2 * Math.PI * hz * (pos + i) / 16000)))));
function analyse(chunks) {
  const pcm = []; for (const c of chunks) for (const b of c) pcm.push(muDecode(b));
  if (!pcm.length) return { rms: 0, hz: 0, seconds: 0 };
  const rms = Math.sqrt(pcm.reduce((a, v) => a + v * v, 0) / pcm.length);
  let crossings = 0; for (let i = 1; i < pcm.length; i++) if ((pcm[i - 1] < 0) !== (pcm[i] < 0)) crossings++;
  return { rms: Math.round(rms), hz: Math.round(crossings / 2 / (pcm.length / 16000)), seconds: pcm.length / 16000 };
}

// =====================================================================================================================
await login();
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
adb('shell', 'input', 'keyevent', 'KEYCODE_MENU');
console.log(`device ${device} on ${serial} (API ${sdk}), agent ${pkg}`);

let st = await status();
console.log(`  reported: readiness=${st.readiness} support=${JSON.stringify(st.support)} online=${st.online}`);
if (flag('--authorize') && st.readiness !== 'ready') {
  check('before the tap the console says "falta autorización"', st.readiness === 'needsAuthorization', st.readiness);
  await authorizeOnPhone();
  for (let i = 0; i < 40 && st.readiness !== 'ready'; i++) { await sleep(1500); st = await status(); }
}
if (want('ready')) check('console: remote support is ready (unattended)', st.readiness === 'ready', `${st.readiness} ${JSON.stringify(st.support || {})}`);
if (st.readiness !== 'ready') { console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`); process.exit(1); }

adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
const size = screenSize();
const s = await new Session().open(false);
let sessionId = s.info.id;
try {
  // --- screen ------------------------------------------------------------------------------------------------------
  const joined = await s.until(() => s.msg('peer', 0, (m) => m.role === 'device' && m.state === 'joined'), 30000);
  measures.joinMs = joined ? joined.at - s.t0 : null;
  check('unattended: the device joined the session by itself', !!joined, `${measures.joinMs} ms after "start"`);
  s.send({ t: 'screen.start', maxDim: 960, quality: 55 });
  const first = await s.until(() => s.frames[0], 30000);
  measures.firstFrameMs = first ? first.at - s.t0 : null;
  if (want('screen')) {
    check('first image of the screen arrives', !!first && first.jpeg[0] === 0xff && first.jpeg[1] === 0xd8,
      first ? `${first.w}x${first.h}, ${first.jpeg.length} bytes` : 'none');
    check('time to first image < 10 s', measures.firstFrameMs !== null && measures.firstFrameMs < 10000, `${measures.firstFrameMs} ms`);
    const info = s.last('info') || {};
    check('the agent captures with its own screen service', info.engine === 'builtin' && info.screen === 'ready',
      `engine=${info.engine} capture=${info.capture} sdk=${info.sdk}`);
    console.log(`  device-owner self-authorisation probe: ${info.probe}`);
    if (info.notice === false) console.log('  SKIP  notice: the device\'s policy turns it off');
    else check('the phone shows the support notice', noticeShown());
  }

  // --- control -----------------------------------------------------------------------------------------------------
  if (want('control')) {
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
    await sleep(2500);
    const before = top();
    s.send({ t: 'key', k: 'home' });
    let after = before;
    for (let i = 0; i < 16 && after === before; i++) { await sleep(500); after = top(); }
    check('operator presses Home: the phone leaves Settings', /settings/i.test(before) && !/settings/i.test(after), `${before} -> ${after}`);

    // A button, found by its bounds on the phone's screen, tapped through the session; the effect read over adb.
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
    await sleep(2500);
    const list = nodes(dumpUi()).filter((n) => /settings/i.test(n.pkg));
    await serviceBack();
    const target = list.find((n) => n.text && n.b[1] > size[1] * 0.25 && n.b[3] < size[1] * 0.9 && n.text.length > 3);
    const titleBefore = adb('shell', 'dumpsys', 'activity', 'top').split('\n').filter((l) => /ACTIVITY|mTitle|Added Fragments|#0:/.test(l)).join('|');
    const uiBefore = list.map((n) => n.text).filter(Boolean).join('|');
    await sleep(600);
    await s.quiet(); // a still screen sends nothing: the next image after the tap is the tap's effect
    const framesBefore = s.frames.length;
    const tapAt = Date.now();
    // The row to tap: found by its text with uiautomator, or (busy phone) a row a third of the way down the list.
    if (target) s.tapNode(target, size); else s.send({ t: 'tap', x: 0.5, y: 0.36 });
    const changed = await s.until(() => (s.frames.length > framesBefore ? s.frames[framesBefore] : null), 5000);
    measures.tapLatencyMs = changed ? changed.at - tapAt : null;
    await sleep(1500);
    const uiAfter = nodes(dumpUi()).map((n) => n.text).filter(Boolean).join('|');
    await serviceBack();
    const titleAfter = adb('shell', 'dumpsys', 'activity', 'top').split('\n').filter((l) => /ACTIVITY|mTitle|Added Fragments|#0:/.test(l)).join('|');
    check(`operator taps ${target ? `"${target.text}"` : 'a Settings row'} on the image: the phone opens it`, uiAfter !== uiBefore || titleAfter !== titleBefore,
      `activity changed: ${titleAfter !== titleBefore}, screen texts changed: ${uiAfter !== uiBefore}`);
    check('touch-to-image latency < 1 s', measures.tapLatencyMs !== null && measures.tapLatencyMs < 1000, `${measures.tapLatencyMs} ms`);
    s.send({ t: 'key', k: 'back' });
    await sleep(800);
    s.send({ t: 'key', k: 'home' });
    await sleep(800);
    // Frame rate while the screen moves: the operator scrolls the Settings list up and down.
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
    await sleep(2000);
    await serviceBack();
    await s.quiet();
    const f0 = s.frames.length; const tA = Date.now();
    for (let i = 0; i < 8; i++) {
      const [a, b] = i % 2 ? [0.35, 0.75] : [0.75, 0.35];
      s.send({ t: 'swipe', pts: [[0.5, a, 0], [0.5, (a + b) / 2, 150], [0.5, b, 300]] });
      await sleep(600);
    }
    measures.fpsMoving = +((s.frames.length - f0) / ((Date.now() - tA) / 1000)).toFixed(1);
    const stats = s.last('screen.stats');
    if (stats) measures.capture = { captureMs: stats.captureMs, encodeMs: stats.encodeMs, intervalMs: stats.intervalMs };
    check('the operator\'s swipes scroll the phone and images keep coming', s.frames.length - f0 >= 4, `${measures.fpsMoving} fps while scrolling`);
    s.send({ t: 'key', k: 'home' });
  }

  // --- chat --------------------------------------------------------------------------------------------------------
  if (want('chat')) {
    const line = `Hola, soy soporte ${Date.now() % 100000}`;
    s.send({ t: 'chat', s: line });
    // The message on the phone: read with uiautomator when it answers; on a busy phone, the support screen on top
    // with a message bubble in it (dumpsys) is accepted, and says so.
    let screen = null;
    for (let i = 0; i < 40 && !(screen && screen.bubbles > 0); i++) { await sleep(1000); screen = supportScreen(); }
    const read = screen ? nodes(dumpUi()).some((n) => n.text.includes(line)) : false;
    await serviceBack();
    check('operator -> phone: the support screen opens and shows the message', read || (!!screen && screen.bubbles > 0),
      screen ? (read ? 'text read on the phone' : `support screen on top with ${screen.bubbles} message(s); text not read (uiautomator busy)`) : top());
    const answer = `respuesta ${Date.now() % 100000}`;
    if (screen && screen.field && screen.send) {
      s.tapNode(screen.field, size); // focus the reply box through the remote session
      await sleep(1500);
      const from = Date.now();
      s.send({ t: 'text', s: answer }); // typed by the operator's keyboard
      await sleep(2500);
      // Enviar, tapped through the session: where it is NOW (the keyboard came up and moved it).
      s.tapNode(supportScreen()?.send || screen.send, size);
      let reply = await s.until(() => s.msg('chat', from, (m) => m.s === answer), 15000);
      if (!reply) { s.tapNode(supportScreen()?.send || screen.send, size); reply = await s.until(() => s.msg('chat', from, (m) => m.s === answer), 15000); }
      check('operator types and sends on the phone through the session; the reply arrives', !!reply, reply ? reply.s : 'no reply');
    } else {
      check('the support screen has a reply box', false);
    }
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  }

  // --- voice -------------------------------------------------------------------------------------------------------
  if (want('voice')) {
    const info = s.last('info') || {};
    check('the Device Owner granted the microphone to the agent', info.voice === 'ready', info.voice);
    const callFrom = Date.now();
    s.audio = [];
    s.send({ t: 'call.start', testTone: true });
    const ring = await s.until(() => s.msg('call.state', callFrom), 20000);
    if (ring && ring.s === 'ringing') {
      let acc = null;
      for (let i = 0; i < 30 && !acc; i++) { await sleep(1000); acc = supportScreen()?.first || null; }
      check('the phone rings and offers Accept / Reject', !!acc);
      if (acc) adbTap(acc); // the person on the phone accepts
    } else {
      console.log(`  call answered unattended (policy callAccept=auto): ${ring?.s}`);
    }
    const active = await s.until(() => s.msg('call.state', callFrom, (m) => m.s === 'active'), 30000);
    check('the call is established', !!active, (s.last('call.state') || {}).detail || (s.last('call.state') || {}).s || 'no answer');
    if (active) {
      let pos = 0;
      const tick = setInterval(() => { s.bin(2, toneFrame(1000, pos)); pos += 320; }, 20);
      await sleep(3500);
      const stats = s.last('call.stats') || {};
      clearInterval(tick);
      check('operator -> phone: audio frames arrive and are played', stats.rx > 100, `rx=${stats.rx} frames`);
      check('operator -> phone: it is the tone, not silence', stats.rxRms > 3000, `level ${stats.rxRms} (sent ~8500)`);
      const heard = analyse(s.audio);
      check('phone -> operator: audio arrives', heard.seconds > 2, `${heard.seconds.toFixed(1)} s received`);
      check('phone -> operator: it is the phone\'s 440 Hz tone', heard.rms > 3000 && Math.abs(heard.hz - 440) < 25, `level ${heard.rms}, ${heard.hz} Hz`);
      measures.voice = { phoneRx: stats.rx, phoneRxRms: stats.rxRms, operatorRms: heard.rms, operatorHz: heard.hz };
      const endFrom = Date.now();
      s.send({ t: 'call.end' });
      check('hang up from the console ends the call', !!(await s.until(() => s.msg('call.state', endFrom, (m) => m.s === 'ended'), 6000)));
      // The real microphone: Android must let a background Device Owner open it (foreground service, microphone type).
      await sleep(800);
      const micFrom = Date.now();
      s.audio = [];
      s.send({ t: 'call.start' });
      const st2 = await s.until(() => s.msg('call.state', micFrom), 20000);
      if (st2 && st2.s === 'ringing') {
        let a = null;
        for (let i = 0; i < 30 && !a; i++) { await sleep(1000); a = supportScreen()?.first || null; }
        if (a) adbTap(a);
      }
      const micActive = await s.until(() => s.msg('call.state', micFrom, (m) => m.s === 'active'), 30000);
      await sleep(2500);
      const micStats = [...s.msgs].reverse().find((m) => m.t === 'call.stats' && m.at > micFrom) || {};
      check('the phone\'s microphone opens for the call', !!micActive && micStats.recording === true && micStats.tx > 50,
        micActive ? `recording=${micStats.recording} tx=${micStats.tx} micLevel=${micStats.micRms}` : ((s.last('call.state') || {}).detail || 'not active'));
      measures.mic = { recording: micStats.recording, tx: micStats.tx, micRms: micStats.micRms };
      // Hang up from the phone this time.
      let hang = null;
      for (let i = 0; i < 20 && !hang; i++) { await sleep(1000); hang = supportScreen()?.second || null; }
      const hangFrom = Date.now();
      if (hang) adbTap(hang); else s.send({ t: 'call.end' });
      check('hang up on the phone ends the call for the operator', !!hang && !!(await s.until(() => s.msg('call.state', hangFrom, (m) => m.s === 'ended'), 15000)), hang ? 'button Colgar' : 'no Colgar button on the phone');
    }
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  }

  // --- files -------------------------------------------------------------------------------------------------------
  if (want('files')) {
    const name = `e2e-${Date.now()}.bin`;
    const data = crypto.randomBytes(300 * 1024);
    const from = Date.now();
    s.send({ t: 'file.put', id: 7, name, size: data.length });
    for (let o = 0; o < data.length; o += 48 * 1024) s.bin(3, Buffer.concat([Buffer.from([7]), data.subarray(o, o + 48 * 1024)]));
    s.send({ t: 'file.end', id: 7 });
    const saved = await s.until(() => s.msg('file.saved', from) || s.msg('file.error', from), 20000);
    check('console -> phone: the file is saved on the phone', saved?.t === 'file.saved', saved ? (saved.path || saved.detail) : 'no answer');
    if (saved?.t === 'file.saved') {
      await sleep(500);
      const onPhone = adbBin('exec-out', 'cat', `/sdcard/${saved.path}`);
      check('the file on the phone has the same bytes', Buffer.compare(onPhone, data) === 0, `/sdcard/${saved.path}, ${onPhone.length} bytes`);
      const listFrom = Date.now();
      s.send({ t: 'file.list', path: 'Download/Soporte' });
      const listing = await s.until(() => s.msg('file.entries', listFrom), 10000);
      const entry = listing && (listing.entries || []).find((e) => e.name === name);
      check('the operator can browse the phone and sees the file', !!entry, listing ? `${(listing.entries || []).length} entries, limited=${listing.limited}` : 'no listing');
      if (entry) {
        s.files = {};
        const getFrom = Date.now();
        s.send({ t: 'file.get', id: 9, path: entry.path });
        const done = await s.until(() => s.msg('file.end', getFrom) || s.msg('file.error', getFrom), 30000);
        const got = Buffer.concat(s.files[9] || []);
        check('phone -> console: the file comes back with the same bytes', done?.t === 'file.end' && Buffer.compare(got, data) === 0, `${got.length} bytes`);
      }
      adb('shell', 'rm', '-f', `/sdcard/${saved.path}`);
    }
  }

  // --- clipboard ---------------------------------------------------------------------------------------------------
  if (want('clipboard')) {
    const from = Date.now();
    s.send({ t: 'clip.set', s: 'texto del portapapeles e2e' });
    const ok = await s.until(() => s.msg('clip.ok', from), 5000);
    check('console -> phone: clipboard text is set', ok?.ok === true);
    // Back from the phone: Android 10+ only lets the app in front read the clipboard, so the support screen is
    // brought up first (a chat line does that); from the background the agent offers the focused field's text.
    s.send({ t: 'chat', s: 'portapapeles' });
    for (let i = 0; i < 20 && !/support\.SupportActivity/.test(top()); i++) await sleep(500);
    await sleep(1500);
    const getFrom = Date.now();
    s.send({ t: 'clip.get' });
    const back = await s.until(() => s.msg('clip', getFrom), 8000);
    check('phone -> console: the clipboard text comes back', back?.s === 'texto del portapapeles e2e', back ? `source=${back.source} "${back.s}"` : 'no answer');
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  }
} finally {
  await s.close();
}

// --- kiosk: a whole session with the phone locked in a kiosk ---------------------------------------------------------------
//   --kiosk   after the other checks: single-app kiosk (Settings pinned) and the multi-app launcher kiosk; in each one a
//             session shows the screen, a touch on the image acts inside the kiosk, a chat line opens the support screen
//             over the kiosk and the reply typed there arrives, a call is answered on the phone, and the kiosk is still on.
if (flag('--kiosk')) {
  const command = async (type, payload) => {
    const q = await rest('POST', `/private/agent/v1/devices/${device}/commands`, { type, payload: payload ? JSON.stringify(payload) : undefined });
    const id = q.data?.id;
    for (let i = 0; i < 60; i++) {
      const row = ((await rest('GET', `/private/agent/v1/devices/${device}/commands?limit=20`)).data || []).find((c) => c.id === id);
      if (row && ['done', 'failed', 'unsupported', 'expired'].includes(row.status)) return row;
      await sleep(1000);
    }
    return { status: 'timeout' };
  };
  const lockTask = () => (adb('shell', 'dumpsys', 'activity', 'activities').match(/mLockTaskModeState=(\w+)/) || [])[1] || '';
  const kiosks = [
    ['single-app', { mode: 'single', pinPackage: 'com.android.settings', allowedPackages: ['com.android.settings'], exitMode: 'remote' }],
    ['multi-app', { mode: 'launcher', allowedPackages: ['com.android.settings', 'com.android.chrome'] }],
  ];
  for (const [name, payload] of kiosks) {
    const entered = await command('kiosk.enter', payload);
    let state = '';
    for (let i = 0; i < 80 && !/LOCKED|PINNED/.test(state); i++) { await sleep(500); state = lockTask(); }
    if (!check(`kiosk ${name}: the phone is in the kiosk`, entered.status === 'done' && /LOCKED|PINNED/.test(state), `${entered.status} ${entered.detail || ''} ${state}`)) continue;
    await sleep(2500);
    const k = await new Session().open(false);
    try {
      const j = await k.until(() => k.msg('peer', 0, (m) => m.state === 'joined'), 30000);
      k.send({ t: 'screen.start', maxDim: 960, quality: 55 });
      const f = await k.until(() => k.frames[0], 30000);
      check(`kiosk ${name}: unattended session, the screen is shown`, !!j && !!f, f ? `first image ${f.at - k.t0} ms` : (k.last('error') || {}).detail || 'no image');
      await k.quiet(1500, 8000);
      const before = top(); const n = k.frames.length;
      // single-app: a Settings row; launcher: the first app tile of the kiosk's grid (found with uiautomator, else a guess)
      const tile = name === 'multi-app' ? nodes(dumpUi()).find((x) => /Settings|Ajustes|Chrome/i.test(x.text)) : null;
      await serviceBack();
      if (tile) k.tapNode(tile, size); else k.send({ t: 'tap', x: 0.5, y: 0.36 });
      let after = before;
      for (let i = 0; i < 20 && after === before; i++) { await sleep(500); after = top(); }
      check(`kiosk ${name}: a touch on the image acts on the phone`, after !== before && k.frames.length > n, `${before} -> ${after}`);
      const line = `kiosco ${Date.now() % 100000}`;
      k.send({ t: 'chat', s: line });
      let scr = null;
      for (let i = 0; i < 30 && !(scr && scr.bubbles > 0); i++) { await sleep(1000); scr = supportScreen(); }
      check(`kiosk ${name}: a chat line opens the support screen over the kiosk`, !!scr && scr.bubbles > 0, top());
      if (scr && scr.field) {
        const answer = `desde-kiosco-${Date.now() % 100000}`;
        const from = Date.now();
        adbTap(scr.field);
        await sleep(1200);
        adb('shell', 'input', 'text', answer);
        await sleep(1000);
        adbTap(supportScreen()?.send || scr.send);
        check(`kiosk ${name}: the reply typed on the phone arrives`, !!(await k.until(() => k.msg('chat', from, (m) => m.s === answer), 15000)));
      }
      const callFrom = Date.now();
      k.audio = [];
      k.send({ t: 'call.start', testTone: true });
      let acc = null;
      for (let i = 0; i < 25 && !acc && !k.msg('call.state', callFrom, (m) => m.s === 'active'); i++) { await sleep(1000); acc = supportScreen()?.first || null; }
      if (acc && !k.msg('call.state', callFrom, (m) => m.s === 'active')) adbTap(acc);
      const active = await k.until(() => k.msg('call.state', callFrom, (m) => m.s === 'active'), 20000);
      await sleep(2500);
      const heard = analyse(k.audio);
      check(`kiosk ${name}: voice call accepted on the phone, its audio reaches the operator`, !!active && heard.rms > 3000, `level ${heard.rms}, ${heard.hz} Hz`);
      k.send({ t: 'call.end' });
      await sleep(1000);
      check(`kiosk ${name}: the phone is still in the kiosk`, /LOCKED|PINNED/.test(lockTask()), lockTask());
    } finally {
      await k.close();
      const left = await command('kiosk.exit');
      let st = lockTask();
      for (let i = 0; i < 20 && !/NONE/.test(st); i++) { await sleep(500); st = lockTask(); }
      check(`kiosk ${name}: exit`, left.status === 'done' && /NONE/.test(st), `${left.status} ${st}`);
      adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
      await sleep(1500);
    }
  }
}

// --- latency: N repetitions on a quiet machine ---------------------------------------------------------------------------
//   --latency N   time to first image over N fresh sessions, and touch-to-image over N taps in one session
const reps = Number(arg('--latency', '0'));
if (reps > 0) {
  const pct = (a, q) => { const v = [...a].sort((x, y) => x - y); return v.length ? v[Math.min(v.length - 1, Math.ceil(q * v.length) - 1)] : null; };
  const firsts = []; const joins = [];
  for (let i = 0; i < reps; i++) {
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
    await sleep(1500);
    const l = await new Session().open(false);
    const j = await l.until(() => l.msg('peer', 0, (m) => m.role === 'device' && m.state === 'joined'), 30000);
    l.send({ t: 'screen.start', maxDim: 960, quality: 55 });
    const f = await l.until(() => l.frames[0], 30000);
    if (j) joins.push(j.at - l.t0);
    if (f) firsts.push(f.at - l.t0);
    await l.close();
    await sleep(1500);
  }
  const taps = [];
  const l = await new Session().open(false);
  try {
    await l.until(() => l.msg('peer', 0, (m) => m.state === 'joined'), 30000);
    l.send({ t: 'screen.start', maxDim: 960, quality: 55 });
    await l.until(() => l.frames[0], 30000);
    for (let i = 0; i < reps; i++) {
      adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
      await sleep(2000);
      await l.quiet(1500, 10000);
      const n = l.frames.length; const at = Date.now();
      l.send({ t: 'tap', x: 0.5, y: 0.36 });
      const f = await l.until(() => (l.frames.length > n ? l.frames[n] : null), 6000);
      if (f) taps.push(f.at - at);
      await sleep(1200);
      l.send({ t: 'key', k: 'back' });
      await sleep(1200);
    }
    const st = l.last('screen.stats');
    if (st) measures.captureLatencyRun = { captureMs: st.captureMs, encodeMs: st.encodeMs, intervalMs: st.intervalMs };
  } finally { await l.close(); }
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  measures.latency = {
    reps, hostLoad: os.loadavg().map((v) => +v.toFixed(1)),
    joinMs: { p50: pct(joins, 0.5), p90: pct(joins, 0.9) },
    firstImageMs: { n: firsts.length, p50: pct(firsts, 0.5), p90: pct(firsts, 0.9), all: firsts },
    touchToImageMs: { n: taps.length, p50: pct(taps, 0.5), p90: pct(taps, 0.9), all: taps },
  };
  check(`first image < 10 s in ${reps} sessions (p90)`, firsts.length === reps && pct(firsts, 0.9) < 10000, `p50 ${pct(firsts, 0.5)} ms, p90 ${pct(firsts, 0.9)} ms`);
  check(`touch to image < 1 s in ${reps} taps (p90)`, taps.length === reps && pct(taps, 0.9) < 1000, `p50 ${pct(taps, 0.5)} ms, p90 ${pct(taps, 0.9)} ms`);
}

// --- view-only: input must not reach the phone ---------------------------------------------------------------------------
if (want('control')) {
  await sleep(1500);
  const v = await new Session().open(true);
  try {
    await v.until(() => v.msg('peer', 0, (m) => m.state === 'joined'), 30000);
    v.send({ t: 'screen.start', maxDim: 540, quality: 40 });
    const f = await v.until(() => v.frames[0], 20000);
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
    await sleep(2500);
    const before = top();
    v.send({ t: 'key', k: 'home' });
    await sleep(2500);
    check('view-only session: the screen is shown but Home is refused', !!f && /settings/i.test(before) && /settings/i.test(top()), `${before} -> ${top()}`);
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  } finally {
    await v.close();
  }
}

// --- log -----------------------------------------------------------------------------------------------------------------
if (want('log')) {
  await sleep(1500);
  const rows = (await rest('GET', `/private/support/v1/devices/${device}/sessions?limit=20`)).data || [];
  const row = rows.find((r) => r.id === sessionId);
  check('the session is in the log: who, when, how long', !!row && row.user === user && row.endedAt > row.startedAt && row.durationSec >= 0,
    row ? `user=${row.user} duration=${row.durationSec}s end=${row.endReason} engine=${row.engine}` : 'missing');
  if (row) {
    if (want('chat')) check('the log counts the chat', row.chatCount >= 2, `chat=${row.chatCount}`);
    if (want('voice')) check('the log counts the call', row.voiceSeconds >= 2, `voice=${row.voiceSeconds}s`);
    if (want('files')) check('the log counts the file', row.fileCount >= 1, `files=${row.fileCount}`);
    const events = (await rest('GET', `/private/support/v1/sessions/${row.id}/events`)).data || [];
    check('the session\'s events can be read (chat, call, file)', events.length > 0, events.slice(0, 4).map((e) => `${e.origin}:${e.kind}`).join(' '));
  }
  const live = (await status()).live;
  check('no session is left open on the device', !live, JSON.stringify(live || {}));
}

// --- policy ----------------------------------------------------------------------------------------------------------------
if (want('policy')) {
  const found = ((await rest('POST', '/private/devices/search', { value: device, pageNum: 1, pageSize: 5 })).data?.devices?.items || [])
    .find((d) => d.number === device);
  const cfgId = found?.configurationId;
  const readCfg = async () => ((await rest('GET', '/private/configurations/search')).data || []).find((c) => c.id === cfgId);
  const apps = async () => ((await rest('GET', `/private/configurations/applications/${cfgId}`)).data || []).filter((a) => a.selected);
  const cfg = await readCfg();
  if (!check('the device\'s policy can be read', !!cfg, `configuration ${cfgId}`)) {
    console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
    process.exit(1);
  }
  const test = { callAccept: 'auto', notice: false };
  let original = cfg.dcPolicy ?? null;
  // A run that was killed half-way left this test's own section behind: that is not the policy to restore.
  const left = JSON.parse(original || '{}');
  if (JSON.stringify(left.remoteSupport) === JSON.stringify({ notice: false, callAccept: 'auto' })) {
    delete left.remoteSupport;
    original = Object.keys(left).length ? JSON.stringify(left) : null;
    console.log('  (the policy still carried this test\'s section from an interrupted run: it will be removed)');
  }
  const policy = { ...JSON.parse(original || '{}'), remoteSupport: test };
  const saved = await rest('PUT', '/private/configurations', { ...cfg, dcPolicy: JSON.stringify(policy), applications: await apps() });
  check('the policy accepts the "Soporte remoto" section', saved.status === 'OK' && !!(await readCfg())?.dcPolicy?.includes('"callAccept":"auto"'),
    (await readCfg())?.dcPolicy || saved.message);
  await rest('POST', `/private/agent/v1/devices/${device}/config/reapply`);
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  let p = null;
  try {
    p = await new Session().open(false);
    await p.until(() => p.msg('peer', 0, (m) => m.state === 'joined'), 30000);
    let info = null;
    for (let i = 0; i < 40 && !(info && info.callAccept === 'auto' && info.notice === false); i++) {
      const from = Date.now();
      p.send({ t: 'info.get' });
      info = await p.until(() => p.msg('info', from), 3000);
      if (!(info && info.callAccept === 'auto')) await sleep(2000);
    }
    check('the phone applied the policy', !!info && info.callAccept === 'auto' && info.notice === false, info ? `callAccept=${info.callAccept} notice=${info.notice}` : 'no info');
    p.send({ t: 'screen.start', maxDim: 540, quality: 40 });
    await p.until(() => p.frames[0], 20000);
    await sleep(1000);
    // Android's screen capture (Android 7-11) cannot run without its own foreground-service notification.
    if (info && info.capture === 'projection') console.log('  SKIP  notice off: this Android captures with screen capture, whose notification Android requires');
    else check('notice turned off by the policy: none is shown while the screen is viewed', !!p.frames[0] && !noticeShown());
    const from = Date.now();
    p.send({ t: 'call.start', testTone: true });
    const active = await p.until(() => p.msg('call.state', from, (m) => m.s === 'active'), 15000);
    const rang = !!p.msg('call.state', from, (m) => m.s === 'ringing');
    check('"se contesta sola": the call is established with nobody at the phone', !!active && !rang, active ? 'active without ringing' : (p.last('call.state') || {}).s);
    await sleep(1500);
    const heard = analyse(p.audio);
    check('unattended call: the phone\'s audio reaches the operator', heard.rms > 3000, `level ${heard.rms}, ${heard.hz} Hz`);
    p.send({ t: 'call.end' });
    await sleep(800);
  } catch (e) {
    check('policy run', false, String(e).split('\n')[0]);
  } finally {
    if (p) await p.close();
    const now = await readCfg();
    await rest('PUT', '/private/configurations', { ...now, dcPolicy: original, applications: await apps() });
    await rest('POST', `/private/agent/v1/devices/${device}/config/reapply`);
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  }
}

// Times measured on a busy test machine are not the product's: the host's load goes with them.
measures.hostLoad = +os.loadavg()[0].toFixed(1);
console.log(`  measures: ${JSON.stringify(measures)}`);
console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
process.exit(fail ? 1 : 0);
