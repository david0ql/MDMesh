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
import { execFileSync } from 'child_process';
import crypto from 'crypto';

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
const top = () => (adb('shell', 'dumpsys activity activities')
  .match(/(?:topResumedActivity|mResumedActivity|mFocusedActivity)[=:].*? (\S+\/\S+?)[ }]/) || [])[1] || '';

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
  for (let i = 0; i < 4; i++) {
    try {
      adb('shell', 'uiautomator', 'dump', '/sdcard/dc-ui.xml');
      const xml = adb('shell', 'cat', '/sdcard/dc-ui.xml');
      if (xml.includes('<hierarchy')) return xml;
    } catch { /* the UI was busy: try again */ }
  }
  return '';
}
async function serviceBack() {
  for (let i = 0; i < 30; i++) {
    const bound = adb('shell', 'dumpsys', 'accessibility').split('Bound services')[1] || '';
    if (bound.split('}')[0].includes('SupportAccessibilityService')) return true;
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

/** The one tap of a QR enrollment, done on the phone's own screens. */
async function authorizeOnPhone() {
  const label = /Soporte remoto DallyControl|DallyControl remote support/i;
  // The guided screen opens by itself right after enrollment; the console can also bring it up.
  let open = await waitUi((ns) => ns.find((n) => /Abrir ajustes|Open settings/i.test(n.text)), 6000);
  if (!open) {
    await rest('POST', `/private/agent/v1/devices/${device}/commands`, { type: 'remote.inputSetup' });
    open = await waitUi((ns) => ns.find((n) => /Abrir ajustes|Open settings/i.test(n.text)), 25000);
  }
  if (!check('the guided authorisation screen is shown on the phone', !!open)) return false;
  adbTap(open);
  // Accessibility list -> (Android 12+: "Downloaded apps" / "Installed apps" may be a sub-page) -> the service.
  let entry = null;
  for (let i = 0; i < 8 && !entry; i++) {
    const ns = nodes(dumpUi());
    entry = ns.find((n) => label.test(n.text));
    if (entry) break;
    const sub = ns.find((n) => /Downloaded apps|Installed (apps|services)|Apps descargadas|Apps instaladas|Servicios instalados|Servicios descargados/i.test(n.text));
    if (sub) adbTap(sub); else adb('shell', 'input', 'swipe', '500', '1500', '500', '700', '200');
    await sleep(900);
  }
  if (!check('the agent\'s service is listed in Accessibility', !!entry)) return false;
  adbTap(entry);
  const sw = await waitUi((ns) => ns.find((n) => n.checkable && /Switch/i.test(n.cls)) || ns.find((n) => n.checkable), 8000);
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
    const ws = new WebSocket(api.replace(/^http/, 'ws') + r.data.socket);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    ws.onmessage = (e) => this.on(e.data);
    ws.onclose = () => { this.closed = true; this.wake(); };
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('socket failed')); });
    ws.send(JSON.stringify({ t: 'auth', token: r.data.token }));
    this.ping = setInterval(() => { try { ws.send('{"t":"ping"}'); } catch { /* closed */ } }, 20000);
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
    const notice = adb('shell', 'dumpsys', 'notification').includes('dc_support');
    check('the phone shows the support notice', notice);
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
    if (target) s.tapNode(target, size);
    const changed = await s.until(() => (s.frames.length > framesBefore ? s.frames[framesBefore] : null), 5000);
    measures.tapLatencyMs = changed ? changed.at - tapAt : null;
    await sleep(1500);
    const uiAfter = nodes(dumpUi()).map((n) => n.text).filter(Boolean).join('|');
    await serviceBack();
    const titleAfter = adb('shell', 'dumpsys', 'activity', 'top').split('\n').filter((l) => /ACTIVITY|mTitle|Added Fragments|#0:/.test(l)).join('|');
    check(`operator taps "${target?.text}" on the image: the phone opens it`, !!target && (uiAfter !== uiBefore || titleAfter !== titleBefore),
      `screen texts changed: ${uiAfter !== uiBefore}`);
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
    const shown = await waitUi((ns) => (ns.find((n) => n.text.includes(line)) ? ns : null), 12000);
    check('operator -> phone: the support screen opens and shows the message', !!shown, top());
    const field = shown && shown.find((n) => /EditText/.test(n.cls));
    const answer = `respuesta ${Date.now() % 100000}`;
    await serviceBack();
    if (field) {
      s.tapNode(field, size); // focus the reply box through the remote session
      await sleep(900);
      const from = Date.now();
      s.send({ t: 'text', s: answer });
      await sleep(1200);
      const ui = nodes(dumpUi());
      const typed = ui.find((n) => /EditText/.test(n.cls) && n.text.includes(answer));
      check('operator types on the phone through the session', !!typed, typed ? typed.text : 'not typed');
      const sendBtn = ui.find((n) => /^(Enviar|Send)$/.test(n.text));
      await serviceBack();
      if (sendBtn) s.tapNode(sendBtn, size);
      const reply = await s.until(() => s.msg('chat', from, (m) => m.s === answer), 8000);
      check('phone -> operator: the reply typed on the phone arrives', !!reply, reply ? reply.s : 'no reply');
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
    const ring = await s.until(() => s.msg('call.state', callFrom), 8000);
    if (ring && ring.s === 'ringing') {
      const acc = await waitUi((ns) => ns.find((n) => /^(Aceptar|Accept)$/.test(n.text)), 10000);
      check('the phone rings and offers Accept / Reject', !!acc);
      if (acc) adbTap(acc); // the person on the phone accepts
    } else {
      console.log(`  call answered unattended (policy callAccept=auto): ${ring?.s}`);
    }
    const active = await s.until(() => s.msg('call.state', callFrom, (m) => m.s === 'active'), 12000);
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
      const st2 = await s.until(() => s.msg('call.state', micFrom), 8000);
      if (st2 && st2.s === 'ringing') { const a = await waitUi((ns) => ns.find((n) => /^(Aceptar|Accept)$/.test(n.text)), 10000); if (a) adbTap(a); }
      const micActive = await s.until(() => s.msg('call.state', micFrom, (m) => m.s === 'active'), 12000);
      await sleep(2500);
      const micStats = [...s.msgs].reverse().find((m) => m.t === 'call.stats' && m.at > micFrom) || {};
      check('the phone\'s microphone opens for the call', !!micActive && micStats.recording === true && micStats.tx > 50,
        micActive ? `recording=${micStats.recording} tx=${micStats.tx} micLevel=${micStats.micRms}` : ((s.last('call.state') || {}).detail || 'not active'));
      measures.mic = { recording: micStats.recording, tx: micStats.tx, micRms: micStats.micRms };
      // Hang up from the phone this time.
      const hang = nodes(dumpUi()).find((n) => /^(Colgar|Hang up)$/.test(n.text));
      const hangFrom = Date.now();
      if (hang) adbTap(hang); else s.send({ t: 'call.end' });
      check('hang up on the phone ends the call for the operator', !!(await s.until(() => s.msg('call.state', hangFrom, (m) => m.s === 'ended'), 6000)), hang ? 'button Colgar' : 'no button found');
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
  }
} finally {
  await s.close();
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

console.log(`  measures: ${JSON.stringify(measures)}`);
console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
process.exit(fail ? 1 : 0);
