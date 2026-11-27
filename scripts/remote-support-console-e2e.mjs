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
  for (let i = 0; i < 4; i++) {
    try {
      adb('shell', 'uiautomator', 'dump', '/sdcard/dc-ui.xml');
      const xml = adb('shell', 'cat', '/sdcard/dc-ui.xml');
      if (xml.includes('<hierarchy')) {
        return [...xml.matchAll(/<node [^>]*>/g)].map((m) => {
          const a = (k) => (m[0].match(new RegExp(` ${k}="([^"]*)"`)) || [])[1] || '';
          return { text: a('text'), cls: a('class'), pkg: a('package'), b: (a('bounds').match(/\d+/g) || []).map(Number) };
        }).filter((n) => n.b.length === 4);
      }
    } catch { /* busy: again */ }
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
  for (let i = 0; i < 30; i++) {
    const bound = adb('shell', 'dumpsys', 'accessibility').split('Bound services')[1] || '';
    if (bound.split('}')[0].includes('SupportAccessibilityService')) return;
    await sleep(300);
  }
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

const browser = await chromium.launch({
  args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
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
  const textsBefore = list.map((n) => n.text).filter(Boolean).join('|');
  if (target && box) {
    const cx = (target.b[0] + target.b[2]) / 2 / size[0];
    const cy = (target.b[1] + target.b[3]) / 2 / size[1];
    const b2 = await canvas.boundingBox();
    await page.mouse.click(b2.x + cx * b2.width, b2.y + cy * b2.height);
  }
  await sleep(2500);
  const textsAfter = ui().map((n) => n.text).filter(Boolean).join('|');
  await serviceBack();
  check(`a click on "${target?.text}" in the image opens it on the phone`, !!target && textsAfter !== textsBefore);
  await shot('03-control');
  await page.getByTestId('support-key-back').click();
  await sleep(700);
  await page.getByTestId('support-key-home').click();

  // --- chat ------------------------------------------------------------------------------------------------------
  const line = `Hola desde la consola ${Date.now() % 100000}`;
  await page.getByTestId('support-chat-input').fill(line);
  await page.getByTestId('support-chat-send').click();
  const shown = await waitUi((ns) => (ns.find((n) => n.text.includes(line)) ? ns : null), 15000);
  check('a message typed in the console is shown on the phone', !!shown, top());
  const answer = `respuesta${Date.now() % 100000}`;
  const field = shown && shown.find((n) => /EditText/.test(n.cls));
  if (field) {
    adbTap(field); // the person on the phone answers
    await sleep(800);
    adb('shell', 'input', 'text', answer);
    await sleep(600);
    const send = ui().find((n) => /^(Enviar|Send)$/.test(n.text));
    if (send) adbTap(send);
  }
  const got = await page.getByTestId('support-chat-list').getByText(answer).waitFor({ timeout: 12000 }).then(() => true, () => false);
  check('the person\'s reply appears in the console\'s chat', got);
  await shot('04-chat');

  // --- voice -----------------------------------------------------------------------------------------------------
  await page.getByTestId('support-call').click();
  const accept = await waitUi((ns) => ns.find((n) => /^(Aceptar|Accept)$/.test(n.text)), 15000);
  if (accept) { check('the phone rings: "Soporte quiere hablar contigo"', true); adbTap(accept); }
  else console.log('  (no Accept button: the policy answers calls unattended, or the call did not ring)');
  await page.waitForFunction(() => window.__dcSupport?.callState === 'active', null, { timeout: 20000 }).catch(() => null);
  check('the call is established', (await hook('s.callState')) === 'active', `${await hook('s.callState')}`);
  let phoneRxRms = 0; let pageRxRms = 0;
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
  await page.getByTestId('support-mute').click();
  await sleep(2500);
  const muted = (await hook('s.callStats && s.callStats.rxRms')) || 0;
  check('"Silenciar" in the console: the phone receives silence', muted < 50, `level on the phone ${muted}`);
  await page.getByTestId('support-hangup').click();
  await page.waitForFunction(() => ['ended', 'idle'].includes(window.__dcSupport?.callState), null, { timeout: 10000 }).catch(() => null);
  check('"Colgar" ends the call', ['ended', 'idle'].includes(await hook('s.callState')), `${await hook('s.callState')}`);
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');

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
  const errors = consoleErrors.filter((e) => !/favicon|ERR_ABORTED/.test(e));
  check('no errors in the browser console', errors.length === 0, errors.slice(0, 3).join(' | '));
} finally {
  await browser.close();
}
console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
process.exit(fail ? 1 : 0);
