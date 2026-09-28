#!/usr/bin/env node
// End-to-end check of remote view/control (ADR 0010) on a real device or emulator enrolled with
// `scripts/adb-enroll.sh --remote`: console login -> scripts/remote-session.sh -> the stack's noVNC viewer
// (/remote/vnc/vnc.html) -> repeater -> droidVNC-NG on the device.
//
//   node scripts/remote-e2e.mjs --api http://localhost:8088 --device <id> --serial emulator-5554 [--console]
//
// Default: the session is started with scripts/remote-session.sh and its link opened. --console drives the
// console instead: device page -> Remote tab -> "View & control" -> the inline viewer -> "End session", and
// also checks the session went through the encrypted tunnel.
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
const viaConsole = process.argv.includes('--console');
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
// KEYCODE_MENU dismisses a swipe keyguard. (`wm dismiss-keyguard` crashes Android 8.0's System UI -- a
// NavigationBarFragment NPE in the stock emulator image -- which then can't answer the capture request.)
adb('shell', 'input', 'keyevent', 'KEYCODE_MENU');
let url = '';
if (!viaConsole) {
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
}

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 900, height: 1100 } })).newPage();
try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', user);
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 20000 });
  let viewer = page; // the noVNC document: the page itself, or the console's inline iframe
  if (viaConsole) {
    await page.goto(`${api}/devices/${device}`);
    await page.locator('.detail-rail').getByRole('button', { name: 'Remote', exact: true }).click();
    const chip = await page.locator('.rp .chip').first().textContent({ timeout: 15000 }).catch(() => '');
    check('console offers an encrypted session', chip.includes('Encrypted'), chip);
    // A fresh enrollment is in battery-saver: the tab must say so and "Set Always-on" must switch the device.
    const saver = page.getByRole('button', { name: 'Set Always-on' });
    const warned = await saver.isVisible().catch(() => false);
    const mode0 = (await fetchJson(page, `${api}/rest/private/agent/v1/devices/${device}/remote`)).data?.powerMode;
    if (mode0 !== 'alwaysOn') check('battery-saver device: the tab offers "Set Always-on"', warned);
    else console.log('  SKIP  "Set Always-on" (device already always-on)');
    if (warned) {
      await saver.click();
      let mode = '';
      for (let i = 0; i < 90 && mode !== 'alwaysOn'; i++) { // applies at the next check-in
        await page.waitForTimeout(2000);
        mode = (await fetchJson(page, `${api}/rest/private/agent/v1/devices/${device}/remote`)).data?.powerMode || '';
      }
      check('"Set Always-on" switched the device to always-on', mode === 'alwaysOn', mode);
      await page.reload();
      await page.getByRole('tablist').getByRole('button', { name: 'Remote' }).click();
      await page.locator('.rp .chip').first().waitFor();
      check('the battery-saver warning is gone', !(await saver.isVisible().catch(() => false)));
    }
    await page.getByRole('button', { name: 'View & control' }).click();
    const live = await page.locator('.rp-viewer').waitFor({ timeout: 360000 }).then(() => true, async () =>
      (await page.locator('.rp .banner-alert').textContent().catch(() => '')) || 'no viewer');
    check('session started from the console, device reached the repeater', live === true, String(live));
    if (live !== true) throw new Error('no session');
    const detail = await fetchJson(page, `${api}/rest/private/agent/v1/devices/${device}/commands`)
      .then((d) => (d.data || []).find((c) => c.type === 'remote.vnc.start')?.detail || '');
    check('device connected through the encrypted tunnel', detail.includes('encrypted tunnel'), detail);
    viewer = page.frameLocator('.rp-viewer');
  } else {
    await page.goto(url);
  }
  const connected = await viewer.locator('#noVNC_status').filter({ hasText: 'Connected' })
    .waitFor({ state: 'attached', timeout: 45000 }).then(() => true, () => false);
  check('viewer connected to the device', connected, await viewer.locator('#noVNC_status').textContent());

  const canvas = viewer.locator('#noVNC_container canvas');
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

  if (viaConsole) {
    // The Nexus-style soft keys under the viewer: each must take the device out of Settings.
    for (const name of ['Back', 'Home', 'Recent apps']) {
      adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS');
      await page.waitForTimeout(2500);
      const was = top();
      await page.locator('.rp-nav').getByRole('button', { name }).click();
      let now = was;
      for (let i = 0; i < 10 && now === was; i++) { await page.waitForTimeout(1000); now = top(); }
      check(`soft key "${name}" reaches the device (Settings -> ${now.split('.').pop()})`,
        was.includes('settings') && !now.includes('settings'), `${was} -> ${now}`);
    }
    await page.locator('.rp-nav').getByRole('button', { name: 'Home' }).click();
    await page.getByRole('button', { name: 'End session' }).click();
    // The inline viewer is removed at once; the device must also drop its repeater connection.
    const gone = await page.locator('.rp-viewer').waitFor({ state: 'detached', timeout: 10000 }).then(() => true, () => false);
    check('"End session" closes the inline viewer', gone);
  } else {
    session('--stop');
  }
  const dropped = viaConsole
    ? await waitStopped(page)
    : await page.waitForFunction(
      () => !(document.querySelector('#noVNC_status')?.textContent || '').includes('Connected to'),
      null, { timeout: 60000 }).then(() => true, () => false);
  check('remote.vnc.stop ends the session', dropped);
} finally {
  await browser.close();
}
console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====`);
process.exit(fail ? 1 : 0);

async function fetchJson(page, u) {
  return page.evaluate(async (x) => (await fetch(x, { credentials: 'include' })).json(), u);
}

/** Console mode: the remote.vnc.stop the button queued must come back done from the device. */
async function waitStopped(page) {
  for (let i = 0; i < 30; i++) {
    const d = await fetchJson(page, `${api}/rest/private/agent/v1/devices/${device}/commands`);
    const stop = (d.data || []).find((c) => c.type === 'remote.vnc.stop');
    if (stop?.status === 'done') return true;
    await page.waitForTimeout(2000);
  }
  return false;
}
