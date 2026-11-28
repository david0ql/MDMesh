#!/usr/bin/env node
// End-to-end check of the console's remote-support screen (ADR 0012) in headless Chromium against a real device or
// emulator: device page -> Remoto -> "Ver y controlar" -> image of the phone, soft keys, a click on the image, chat,
// a voice call with a real WebRTC-style audio path in the browser (getUserMedia on Chromium's synthetic microphone ->
// AudioWorklet -> mu-law -> the session socket -> the phone, and the phone's audio back to the page), end, history.
//
//   node scripts/remote-support-console-e2e.mjs --api http://localhost:8088 --device <id> --serial emulator-5554
//        [--shots <dir>]   (screenshots of the session, for the test report)
//
// The device must report "Remoto listo" (see scripts/remote-support-e2e.mjs --authorize for the one tap of a QR
// enrollment). Chromium runs with --use-fake-device-for-media-stream: its microphone is a synthetic beep, so the
// phone must report sound (not silence) arriving; the phone (debug build) sends a 440 Hz tone back
// (?supportTestTone=1), so the page must decode sound too. Uses the Playwright copy under scripts/shots
// (cd scripts/shots && npm install && npx playwright install chromium). Exit 0 only if all checks pass.
import { createRequire } from 'module';
import { execFileSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const { chromium } = createRequire(path.join(here, 'shots', 'package.json'))('playwright');
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api', 'http://localhost:8088').replace(/\/$/, '');
const device = arg('--device');
const serial = arg('--serial');
const shots = arg('--shots', '');
const user = arg('--admin-user', 'admin');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD || 'admin';
if (!device || !serial) { console.error('--device and --serial are required'); process.exit(2); }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...a) => execFileSync('adb', ['-s', serial, ...a], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const top = () => (adb('shell', 'dumpsys activity activities')
  .match(/(?:topResumedActivity|mResumedActivity|mFocusedActivity)[=:].*? (\S+\/\S+?)[ }]/) || [])[1] || '';
function ui() {
  for (let i = 0; i < 6; i++) {
    try {
      adb('shell', 'uiautomator', 'dump', '/sdcard/dc-ui.xml');
      const xml = adb('shell', 'cat', '/sdcard/dc-ui.xml');
      if (xml.includes('<hierarchy') && (xml.match(/<node /g) || []).length > 2) {
        return [...xml.matchAll(/<node [^>]*>/g)].map((m) => {
          const a = (k) => (m[0].match(new RegExp(` ${k}="([^"]*)"`)) || [])[1] || '';
          return { text: a('text'), cls: a('class'), pkg: a('package'), b: (a('bounds').match(/\d+/g) || []).map(Number) };
        }).filter((n) => n.b.length === 4);
      }
    } catch { /* busy: again */ }
    execFileSync('sleep', ['0.7']);
  }
  return [];
}
async function waitUi(pred, ms = 12000) {
  const until = Date.now() + ms;
  while (Date.now() < until) { const hit = pred(ui()); if (hit) return hit; await sleep(500); }
  return null;
}
// `uiautomator dump` unbinds the agent's screen service for a moment (see remote-support-e2e.mjs): wait for it.
async function serviceBack() {
  for (let i = 0; i < 40; i++) {
    const bound = adb('shell', 'dumpsys', 'accessibility').split('Bound services')[1] || '';
    if (bound.split('}')[0].includes('SupportAccessibilityService')) {
      await hook('(s.info && (s.info.connected = false), s.send({ t: "info.get" }))');
      await sleep(500);
      if (await hook('s.info && s.info.connected')) return;
    }
    await sleep(300);
  }
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
const adbTap = (n) => adb('shell', 'input', 'tap', String(Math.round((n.b[0] + n.b[2]) / 2)), String(Math.round((n.b[1] + n.b[3]) / 2)));
function screenSize() {
  const m = adb('shell', 'wm', 'size').match(/(\d+)x(\d+)/g).pop().match(/(\d+)x(\d+)/);
  return [Number(m[1]), Number(m[2])];
}

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  [${detail}]` : ''}`);
  ok ? pass++ : fail++;
  return ok;
};

adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
adb('shell', 'input', 'keyevent', 'KEYCODE_MENU');
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');

// The browser's microphone: a speech-like signal (three tones switched on and off four times a second), so the echo
// canceller and noise suppressor of getUserMedia let it through as they would a voice.
const wav = path.join(os.tmpdir(), `dc-fake-mic-${process.pid}.wav`);
{
  const rate = 48000; const n = rate * 4; const pcm = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const t = i / rate; const on = Math.floor(t * 4) % 2 === 0 ? 1 : 0.05;
    const v = on * 0.25 * (Math.sin(2 * Math.PI * 300 * t) + Math.sin(2 * Math.PI * 700 * t) + Math.sin(2 * Math.PI * 1300 * t));
    pcm.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round(v * 32767))), i * 2);
  }
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20);
  h.writeUInt16LE(1, 22); h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  fs.writeFileSync(wav, Buffer.concat([h, pcm]));
}
const browser = await chromium.launch({
  args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', `--use-file-for-fake-audio-capture=${wav}`,
    '--autoplay-policy=no-user-gesture-required'],
});
const context = await browser.newContext({ viewport: { width: 1360, height: 900 }, permissions: ['microphone'], acceptDownloads: true });
const page = await context.newPage();
const consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${String(e).slice(0, 200)}`));
const hook = (expr) => page.evaluate(`(() => { const s = window.__dcSupport; return s ? (${expr}) : null; })()`);
const shot = async (name) => { if (shots) { fs.mkdirSync(shots, { recursive: true }); await page.screenshot({ path: path.join(shots, `${name}.png`) }); } };

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"]', user);
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices|tablero/, { timeout: 30000 });
  await page.goto(`${api}/devices/${device}?supportTestTone=1`);
  await page.locator('.tabs').getByRole('button', { name: 'Remoto', exact: true }).click();

  const chip = await page.getByTestId('remote-readiness').textContent({ timeout: 20000 }).catch(() => '');
  check('the Remote tab says "Remoto listo"', chip === 'Remoto listo', chip);
  await shot('01-remoto-listo');

  // --- screen ----------------------------------------------------------------------------------------------------
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Ver y controlar' }).click();
  await page.waitForFunction(() => (window.__dcSupport?.frames || 0) > 0, null, { timeout: 60000 }).catch(() => null);
  const frames = await hook('s.frames');
  const firstMs = await hook('s.firstFrameMs');
  check('the phone\'s screen appears in the console', frames > 0, `${frames} images`);
  check('time to first image < 10 s (from the click)', firstMs !== null && firstMs < 10000, `${firstMs} ms (${Date.now() - t0} ms wall)`);
  const canvas = page.getByTestId('support-viewer');
  const box = await canvas.boundingBox();
  check('the image is drawn with the phone\'s proportions', !!box && box.width > 150 && box.height > box.width, JSON.stringify(box && { w: Math.round(box.width), h: Math.round(box.height) }));
  const info = await hook('s.info');
  console.log(`  phone: ${info?.model} · Android ${info?.release} (API ${info?.sdk}) · capture ${info?.capture}`);
  await shot('02-sesion');

  // --- control ---------------------------------------------------------------------------------------------------
  adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
  await sleep(2500);
  let before = top();
  await page.getByTestId('support-key-home').click();
  let after = before;
  for (let i = 0; i < 16 && after === before; i++) { await sleep(500); after = top(); }
  check('"Inicio" in the session bar takes the phone home', /settings/i.test(before) && !/settings/i.test(after), `${before} -> ${after}`);

  adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
  await sleep(2500);
  const size = screenSize();
  const list = ui().filter((n) => /settings/i.test(n.pkg));
  await serviceBack();
  const target = list.find((n) => n.text && n.text.length > 3 && n.b[1] > size[1] * 0.25 && n.b[3] < size[1] * 0.9);
  // The row to click: found by its text with uiautomator, or (busy phone) a row a third of the way down the list.
  const cx = target ? (target.b[0] + target.b[2]) / 2 / size[0] : 0.5;
  const cy = target ? (target.b[1] + target.b[3]) / 2 / size[1] : 0.36;
  const topBefore = top();
  const b2 = await canvas.boundingBox();
  await page.mouse.click(b2.x + cx * b2.width, b2.y + cy * b2.height);
  let topAfter = topBefore;
  for (let i = 0; i < 20 && topAfter === topBefore; i++) { await sleep(500); topAfter = top(); }
  check(`a click on ${target ? `"${target.text}"` : 'a Settings row'} in the image opens it on the phone`, /settings/i.test(topBefore) && topAfter !== topBefore,
    `${topBefore} -> ${topAfter}`);
  await shot('03-control');
  await page.getByTestId('support-key-back').click();
  await sleep(700);
  await page.getByTestId('support-key-home').click();

  // --- chat ------------------------------------------------------------------------------------------------------
  const line = `Hola desde la consola ${Date.now() % 100000}`;
  await page.getByTestId('support-chat-input').fill(line);
  await page.getByTestId('support-chat-send').click();
  let screen = null;
  for (let i = 0; i < 40 && !(screen && screen.bubbles > 0); i++) { await sleep(1000); screen = supportScreen(); }
  const read = screen ? ui().some((n) => n.text.includes(line)) : false;
  await serviceBack();
  check('a message typed in the console is shown on the phone', read || (!!screen && screen.bubbles > 0),
    screen ? (read ? 'text read on the phone' : `support screen on top with ${screen.bubbles} message(s); text not read (uiautomator busy)`) : top());
  const answer = `respuesta${Date.now() % 100000}`;
  if (screen && screen.field) {
    adbTap(screen.field); // the person on the phone answers
    await sleep(1200);
    adb('shell', 'input', 'text', answer);
    await sleep(1200);
    adbTap(supportScreen()?.send || screen.send);
  }
  const got = await page.getByTestId('support-chat-list').getByText(answer).waitFor({ timeout: 12000 }).then(() => true, () => false);
  check('the person\'s reply appears in the console\'s chat', got);
  await shot('04-chat');

  // --- the operator's own keyboard: click the reply box in the image, type, Enter (the reply comes back by chat) ---
  {
    const scr = supportScreen();
    const kb = `teclado${Date.now() % 100000}`;
    if (check('the support screen is still up on the phone (reply box to type into)', !!scr && !!scr.field, top())) {
      await serviceBack();
      const b3 = await canvas.boundingBox();
      const fx = (scr.field.b[0] + scr.field.b[2]) / 2 / size[0];
      const fy = (scr.field.b[1] + scr.field.b[3]) / 2 / size[1];
      await page.mouse.click(b3.x + fx * b3.width, b3.y + fy * b3.height);
      await sleep(2500);
      await canvas.focus();
      await page.keyboard.type(kb, { delay: 60 });
      await sleep(2500);
      await page.keyboard.press('Enter');
      const back = await page.getByTestId('support-chat-list').getByText(kb).waitFor({ timeout: 15000 }).then(() => true, () => false);
      check('physical keyboard -> text: typed in the console, sent with Enter, the reply arrives through the session', back);
    }

    // --- clipboard, both ways (the support screen is in front, so Android lets the agent read its clipboard) ------
    const clip = `portapapeles ${Date.now() % 100000}`;
    await page.getByRole('button', { name: 'Portapapeles', exact: true }).click();
    const panel = page.getByTestId('support-clip-panel');
    await panel.getByLabel('Texto para el teléfono').fill(clip);
    await panel.getByRole('button', { name: 'Enviar texto al teléfono' }).click();
    await sleep(1500);
    await panel.getByRole('button', { name: 'Traer del teléfono' }).click();
    const got = await panel.locator('.ss-clip').filter({ hasText: clip }).waitFor({ timeout: 10000 }).then(() => true, () => false);
    check('clipboard: text sent to the phone and brought back from the phone', got, got ? '' : (await panel.textContent().catch(() => '') || '').slice(-160));
    await page.getByRole('button', { name: 'Portapapeles', exact: true }).click();
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  }

  // --- session bar: quality, scale, screenshot, recording ----------------------------------------------------------
  {
    const w0 = await canvas.evaluate((c) => c.width);
    await page.getByLabel('Calidad de la imagen').selectOption({ label: 'Alta' });
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS'); // something new to draw at the new size
    await page.waitForFunction((w) => document.querySelector('[data-testid="support-viewer"]').width > w, w0, { timeout: 15000 }).catch(() => null);
    const w1 = await canvas.evaluate((c) => c.width);
    check('"Calidad: Alta" gives a larger image', w1 > w0, `${w0} -> ${w1} px wide`);
    await page.getByLabel('Calidad de la imagen').selectOption({ label: 'Media' });
    const fitW = (await canvas.boundingBox()).width;
    await page.getByLabel('Escala de la pantalla').selectOption('real');
    await sleep(800);
    const realW = (await canvas.boundingBox()).width;
    check('"Escala: Tamaño real" shows the image at its own size', realW > fitW, `${Math.round(fitW)} -> ${Math.round(realW)} px on screen`);
    await page.getByLabel('Escala de la pantalla').selectOption('fit');
    const [png] = await Promise.all([page.waitForEvent('download', { timeout: 15000 }).catch(() => null),
      page.getByRole('button', { name: 'Captura', exact: true }).click()]);
    const pngPath = png ? await png.path() : null;
    check('"Captura" downloads the screen as a PNG', !!pngPath && fs.statSync(pngPath).size > 2000 && /\.png$/.test(png.suggestedFilename()),
      png ? `${png.suggestedFilename()}, ${fs.statSync(pngPath).size} bytes` : 'no download');
    await page.getByRole('button', { name: 'Grabar', exact: true }).click();
    for (let i = 0; i < 4; i++) { await page.getByTestId(i % 2 ? 'support-key-home' : 'support-key-recents').click(); await sleep(900); }
    const [rec] = await Promise.all([page.waitForEvent('download', { timeout: 20000 }).catch(() => null),
      page.getByRole('button', { name: 'Detener grabación' }).click()]);
    const recPath = rec ? await rec.path() : null;
    check('"Grabar" / "Detener grabación" downloads the session as a video', !!recPath && fs.statSync(recPath).size > 5000,
      rec ? `${rec.suggestedFilename()}, ${fs.statSync(recPath).size} bytes` : 'no download');
    await page.getByTestId('support-key-home').click();
    await shot('04b-barra');
  }

  // --- voice -----------------------------------------------------------------------------------------------------
  await page.getByTestId('support-call').click();
  let accept = null;
  for (let i = 0; i < 25 && !accept && (await hook('s.callState')) !== 'active'; i++) { await sleep(1000); accept = supportScreen()?.first || null; }
  if (accept && (await hook('s.callState')) !== 'active') { check('the phone rings: "Soporte quiere hablar contigo"', true); adbTap(accept); }
  else console.log('  (no Accept button: the policy answers calls unattended, or the call did not ring)');
  await page.waitForFunction(() => window.__dcSupport?.callState === 'active', null, { timeout: 40000 }).catch(() => null);
  const inCall = check('the call is established', (await hook('s.callState')) === 'active', `${await hook('s.callState')}`);
  let phoneRxRms = 0; let pageRxRms = 0;
  if (!inCall) throw new Error('no call: the rest of the voice checks cannot run');
  for (let i = 0; i < 12; i++) { // the synthetic microphone beeps about once a second: keep the loudest second
    await sleep(500);
    phoneRxRms = Math.max(phoneRxRms, (await hook('s.callStats && s.callStats.rxRms')) || 0);
    pageRxRms = Math.max(pageRxRms, (await hook('s.rxAudioRms')) || 0);
  }
  const tx = await hook('s.txAudioFrames');
  const rx = await hook('s.rxAudioFrames');
  const stats = await hook('s.callStats');
  check('console microphone -> phone: frames sent and received', tx > 100 && (stats?.rx || 0) > 100, `sent ${tx}, phone received ${stats?.rx}`);
  check('console microphone -> phone: the phone hears sound, not silence', phoneRxRms > 300, `level on the phone ${phoneRxRms}`);
  check('phone -> console: audio frames arrive and are decoded', rx > 100, `${rx} frames`);
  check('phone -> console: it is the phone\'s tone, not silence', pageRxRms > 3000, `level in the page ${pageRxRms}`);
  await shot('05-llamada');
  // Muted, the console sends nothing: the phone's count of received frames must stop growing.
  await page.getByTestId('support-mute').click();
  await sleep(1500);
  const rxA = (await hook('s.callStats && s.callStats.rx')) || 0;
  await sleep(3000);
  const rxB = (await hook('s.callStats && s.callStats.rx')) || 0;
  check('"Silenciar" in the console: the phone stops receiving the operator\'s audio', rxB - rxA < 10, `${rxB - rxA} frames in 3 s (150 when not muted)`);
  await page.getByTestId('support-hangup').click();
  await page.waitForFunction(() => ['ended', 'idle'].includes(window.__dcSupport?.callState), null, { timeout: 10000 }).catch(() => null);
  check('"Colgar" ends the call', ['ended', 'idle'].includes(await hook('s.callState')), `${await hook('s.callState')}`);
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');

  // --- lock from the session bar ----------------------------------------------------------------------------------
  {
    const awake = () => /mWakefulness=Awake/.test(adb('shell', 'dumpsys', 'power'));
    const was = awake();
    await page.locator(".ss-bar").getByRole("button", { name: "Bloquear", exact: true }).click();
    let asleep = false;
    for (let i = 0; i < 40 && !asleep; i++) { await sleep(500); asleep = !awake(); }
    check('"Bloquear" in the session bar locks the phone (screen off)', was && asleep);
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
    adb('shell', 'input', 'keyevent', 'KEYCODE_MENU');
    await sleep(1500);
  }

  // --- end + history ---------------------------------------------------------------------------------------------
  await page.getByTestId('support-end').click();
  await page.waitForFunction(() => window.__dcSupport?.state === 'ended', null, { timeout: 15000 }).catch(() => null);
  check('"Terminar sesión" ends it', (await hook('s.state')) === 'ended');
  await sleep(2500);
  const rows = await page.evaluate(async (d) => (await (await fetch(`/rest/private/support/v1/devices/${d}/sessions?limit=5`, { credentials: 'include' })).json()).data, device);
  const row = rows && rows[0];
  check('the session is logged with its user, duration, chat and call', !!row && row.user === user && row.endedAt && row.chatCount >= 2 && row.voiceSeconds >= 3,
    row ? `user=${row.user} dur=${row.durationSec}s chat=${row.chatCount} voice=${row.voiceSeconds}s end=${row.endReason}` : 'no row');
  await page.goto(`${api}/soporte`);
  const listed = await page.getByTestId('support-sessions-table').getByText(user).first().waitFor({ timeout: 15000 }).then(() => true, () => false);
  check('the fleet page "Sesiones de soporte" lists it', listed);
  await shot('06-sesiones');

  // --- restart from the session bar (last: the phone goes away) --------------------------------------------------------
  if (!process.argv.includes('--no-reboot')) {
    await page.goto(`${api}/devices/${device}`);
    await page.locator('.tabs').getByRole('button', { name: 'Remoto', exact: true }).click();
    await page.getByRole('button', { name: 'Ver y controlar' }).click();
    await page.waitForFunction(() => (window.__dcSupport?.frames || 0) > 0, null, { timeout: 60000 }).catch(() => null);
    const bootBefore = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id').trim();
    page.once('dialog', (d) => d.accept());
    await page.locator(".ss-bar").getByRole("button", { name: "Reiniciar", exact: true }).click();
    let rebooted = false;
    for (let i = 0; i < 90 && !rebooted; i++) {
      await sleep(2000);
      try { rebooted = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id').trim() !== bootBefore; } catch { /* going down */ }
    }
    check('"Reiniciar" in the session bar restarts the phone', rebooted);
    for (let i = 0; i < 90; i++) { try { if (adb('shell', 'getprop', 'sys.boot_completed').trim() === '1') break; } catch { /* booting */ } await sleep(2000); }
    const ended = await page.waitForFunction(() => window.__dcSupport?.state === 'ended', null, { timeout: 90000 }).then(() => true, () => false);
    check('the session ends by itself when the phone goes away', ended, `${await hook('s.state')}`);
  }
  const errors = consoleErrors.filter((e) => !/favicon|ERR_ABORTED/.test(e));
  check('no errors in the browser console', errors.length === 0, errors.slice(0, 3).join(' | '));
} catch (e) {
  check('run', false, String(e).split('\n')[0]);
} finally {
  await browser.close();
  fs.rmSync(wav, { force: true });
}
console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
process.exit(fail ? 1 : 0);
