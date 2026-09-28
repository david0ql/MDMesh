#!/usr/bin/env node
// End-to-end check of remote view/control (ADR 0010) on a real device or emulator enrolled with
// `scripts/adb-enroll.sh --remote`: console login -> scripts/remote-session.sh -> the stack's noVNC viewer
// (/remote/vnc/vnc.html) -> repeater -> droidVNC-NG on the device.
//
//   node scripts/remote-e2e.mjs --api http://localhost:8088 --device <id> --serial emulator-5554
//
// Checks: the viewer connects and renders; a key pressed in the viewer reaches the device (Settings is
// brought up over adb, Home is pressed in the viewer, the launcher must come back); remote.vnc.stop ends
// the session (the viewer is disconnected). Uses the Playwright copy under scripts/shots
// (cd scripts/shots && npm install && npx playwright install chromium). Exit 0 only if all checks pass.
import { createRequire } from 'module';
import { execFileSync } from 'child_process';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const { chromium } = createRequire(path.join(here, 'shots', 'package.json'))('playwright');
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api', 'http://localhost:8088');
const device = arg('--device');
const serial = arg('--serial');
const user = arg('--admin-user', 'admin');
const password = process.env.MDMESH_ADMIN_PASSWORD || 'admin';
if (!device || !serial) { console.error('--device and --serial are required'); process.exit(2); }

const adb = (...a) => execFileSync('adb', ['-s', serial, ...a], { encoding: 'utf8' });
// API 29+: topResumedActivity; older releases: mResumedActivity / mFocusedActivity.
const top = () => (adb('shell', 'dumpsys activity activities')
  .match(/(?:topResumedActivity|mResumedActivity|mFocusedActivity)[=:].*? (\S+)\//) || [])[1] || '';
const session = (...extra) => execFileSync(path.join(here, 'remote-session.sh'),
  ['--api', api, '--device', device, '--admin-user', user, ...extra],
  { encoding: 'utf8', env: { ...process.env, MDMESH_ADMIN_PASSWORD: password } }).trim();

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`);
  ok ? pass++ : fail++;
};

adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
adb('shell', 'wm', 'dismiss-keyguard');
let url = '';
try {
  url = session();
} catch (e) {
  url = String(e.stderr || e.message).trim().split('\n').pop();
}
check('session started, device reached the repeater', url.includes('/remote/vnc/vnc.html'), url);
if (!url.includes('/remote/vnc/vnc.html')) {
  console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
  process.exit(1);
}

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 900, height: 1100 } })).newPage();
try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', user);
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 20000 });
  await page.goto(url);
  const connected = await page.waitForFunction(
    () => (document.querySelector('#noVNC_status')?.textContent || '').includes('Connected'),
    null, { timeout: 45000 }).then(() => true, () => false);
  check('viewer connected to the device', connected, await page.locator('#noVNC_status').textContent());

  const canvas = page.locator('#noVNC_container canvas');
  const box = await canvas.boundingBox().catch(() => null);
  check('viewer renders the device screen', !!box && box.width > 100 && box.height > 100, JSON.stringify(box));

  adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
  await page.waitForTimeout(2500);
  const before = top();
  await canvas.click({ position: { x: 5, y: 5 } });
  await page.keyboard.press('Home'); // droidVNC-NG maps Home to Android Home
  let after = before;
  for (let i = 0; i < 10 && after === before; i++) { await page.waitForTimeout(1000); after = top(); }
  check('a key pressed in the viewer controls the device (Settings -> launcher)',
    before.includes('settings') && !after.includes('settings'), `${before} -> ${after}`);

  session('--stop');
  const dropped = await page.waitForFunction(
    () => !(document.querySelector('#noVNC_status')?.textContent || '').includes('Connected to'),
    null, { timeout: 60000 }).then(() => true, () => false);
  check('remote.vnc.stop ends the session', dropped);
} finally {
  await browser.close();
}
console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
process.exit(fail ? 1 : 0);
