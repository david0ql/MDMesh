// Console (web) checks of the second wake channel against a running DallyControl, in a real browser:
//   * Ajustes → "Despertador push (FCM)": configured / not configured, devices registered, counters;
//   * the device page's "Alcance de órdenes" card: live channel → push → only polling, with the expected latency;
//   * the notice when a command is sent to a device without live channel: how it will be woken;
//   * the policy's "Sondeo de respaldo" fields, saved through the UI and read back.
//
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/wake-console-e2e.mjs --api http://localhost:8116 \
//       --device-id <numeric device id> --device <device number> [--config <configuration id>] \
//       [--sim http://127.0.0.1:8126] [--expect configured|off] [--shots dir]
//
// With --sim (the simulator of scripts/wake-latency-e2e.py, device enrolled through it and holding a token) the live
// channel is cut and restored to see the card and the notice change. Playwright: the copy under scripts/shots
// (npm ci there once) or PLAYWRIGHT_DIR=<path to a playwright package>. Exit 0 only if all checks pass.
import { mkdirSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const pw = process.env.PLAYWRIGHT_DIR
  ? pathToFileURL(`${process.env.PLAYWRIGHT_DIR}/index.mjs`).href
  : new URL('./shots/node_modules/playwright/index.mjs', import.meta.url).href;
const { chromium } = await import(pw);

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const deviceId = arg('--device-id');
const deviceNumber = arg('--device');
const cfgId = Number(arg('--config', '0'));
const sim = arg('--sim', '');
const expect = arg('--expect', sim ? 'configured' : 'off');
const shots = arg('--shots', '/tmp/dallycontrol-wake-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !deviceId || !deviceNumber || !password) {
  console.error('--api, --device-id, --device and DALLYCONTROL_ADMIN_PASSWORD are required');
  process.exit(2);
}
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };
const simSet = async (q) => (await fetch(`${sim}/__sim/set?${q}`)).json();

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 1100 } });
const page = await ctx.newPage();
page.on('dialog', (d) => { void d.accept(); });
const rest = async (method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined });
  return r.json();
}, [method, path, body]);
const reach = async () => (await rest('GET', `/private/agent/v1/devices/${deviceNumber}/reach`)).data;
const until = async (fn, ms) => { const end = Date.now() + ms; let v; while (Date.now() < end) { v = await fn(); if (v) return v; await page.waitForTimeout(1500); } return v; };
let origPolicy;
let policyTouched = false;

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices|tablero/, { timeout: 30000 });
  check('console login', true);

  // --- Ajustes: estado de FCM -------------------------------------------------------------------------------
  await page.goto(`${api}/settings`);
  const panel = page.getByTestId('push-status');
  await panel.waitFor({ timeout: 30000 });
  const chip = (await page.getByTestId('push-status-chip').innerText()).trim();
  check(`Ajustes shows the push channel as ${expect === 'configured' ? '"Configurado"' : '"No configurado"'}`,
    expect === 'configured' ? chip === 'Configurado' : chip === 'No configurado', chip);
  const panelText = await panel.innerText();
  check('Ajustes shows the registered devices and the message counters',
    /Equipos registrados/.test(panelText) && /\d+ enviados · \d+ respondidos · \d+ con error/.test(panelText), panelText.slice(0, 300));
  if (expect === 'configured') {
    check('Ajustes: at least this device is registered', Number((await page.getByTestId('push-devices').innerText()).trim()) >= 1);
  } else {
    check('Ajustes explains how to turn it on when it is off', /docs\/DESPERTADOR-FCM\.md/.test(panelText));
  }
  await panel.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${shots}/ajustes-fcm.png`, fullPage: true });

  // --- device page: channel + expected latency --------------------------------------------------------------
  if (sim) await simSet('ws=open&deliver=1');
  await page.goto(`${api}/devices/${deviceId}`);
  const card = page.getByTestId('reach-card');
  await card.waitFor({ timeout: 30000 });
  const channel = page.getByTestId('reach-channel');
  if (sim) {
    await until(async () => (await reach())?.channel === 'live', 120000);
    await page.reload();
    await card.waitFor({ timeout: 30000 });
    check('device with live channel: card says "Canal en vivo"', (await channel.getAttribute('data-channel')) === 'live', await channel.innerText());
    check('…and an expected latency of seconds', /unos \d+ s/.test(await page.getByTestId('reach-latency').innerText()));
    check('…and that it is registered for push', /Registrado/.test(await page.getByTestId('reach-push').innerText()));
    await page.screenshot({ path: `${shots}/equipo-canal-en-vivo.png`, fullPage: true });

    // Cut the live channel (check-ins still work): the card must change to push, the notice must say so.
    await simSet('ws=blocked&deliver=1');
    await until(async () => (await reach())?.channel === 'push', 180000);
    await page.reload();
    await card.waitFor({ timeout: 30000 });
    check('live channel cut: card says "Mensaje push (FCM)"', (await channel.getAttribute('data-channel')) === 'push', await channel.innerText());
    await page.screenshot({ path: `${shots}/equipo-canal-push.png`, fullPage: true });
    await page.getByRole('button', { name: 'Hacer sonar', exact: true }).click();
    const confirm = page.getByRole('button', { name: /^(Enviar|Confirmar|Hacer sonar)$/ }).last();
    if (await confirm.isVisible().catch(() => false)) await confirm.click().catch(() => undefined);
    const toast = page.locator('.toast, [role="status"], [role="alert"]').filter({ hasText: /despertando al equipo|mensaje push/ }).first();
    const shown = await toast.waitFor({ timeout: 20000 }).then(() => true).catch(() => false);
    check('sending a command without live channel says it wakes the device with a push', shown,
      shown ? '' : (await page.locator('body').innerText()).slice(0, 400));
    await page.screenshot({ path: `${shots}/orden-despertando.png`, fullPage: true });
    const answered = await until(async () => { const r = await reach(); return r?.lastSentOk && r?.lastWakeMs != null ? r : null; }, 60000);
    check('the push was sent and the device answered it (latency recorded)', !!answered, JSON.stringify(await reach()).slice(0, 300));
    await rest('POST', `/private/agent/v1/devices/${deviceNumber}/commands`, { type: 'device.ringStop', requiresCapability: 'device.ringStop' });
    await simSet('ws=open&deliver=1');
  } else {
    const ch = await channel.getAttribute('data-channel');
    check('card shows one of the three channels', ['live', 'push', 'poll'].includes(ch ?? ''), ch ?? '');
    check('card shows the expected latency', /(unos \d+ s|hasta \d+ min)/.test(await page.getByTestId('reach-latency').innerText()));
    const pushText = await page.getByTestId('reach-push').innerText();
    check('the card says whether push is available for this device',
      expect === 'configured' ? !/No configurados/.test(pushText) : /No configurados en el servidor/.test(pushText), pushText);
    await page.screenshot({ path: `${shots}/equipo-alcance.png`, fullPage: true });
  }

  // --- policy: backup polling, through the API the panel uses, then read in the UI ----------------------------
  if (cfgId) {
    const cfgs = (await rest('GET', '/private/configurations/search')).data;
    const cfg = cfgs.find((c) => c.id === cfgId);
    origPolicy = cfg.dcPolicy ?? null;
    const full = (await rest('GET', `/private/configurations/${cfgId}`)).data;
    const policy = { ...(origPolicy ? JSON.parse(origPolicy) : {}), wake: { pollMinutes: 3, fastPollMinutes: 1, fastWindowMinutes: 15, bogus: 9 } };
    const saved = await rest('PUT', '/private/configurations', { ...full, dcPolicy: JSON.stringify(policy) });
    policyTouched = saved.status === 'OK';
    const back = JSON.parse((await rest('GET', `/private/configurations/${cfgId}`)).data.dcPolicy ?? '{}');
    check('policy stores the backup polling schedule (and drops unknown keys)',
      back.wake?.pollMinutes === 3 && back.wake?.fastPollMinutes === 1 && back.wake?.fastWindowMinutes === 15 && back.wake?.bogus === undefined,
      JSON.stringify(back.wake));
    await page.goto(`${api}/configs`);
    await page.locator('.panel, .cfg-card, article, section', { hasText: cfg.name })
      .filter({ has: page.getByRole('button', { name: /^(Edit|Editar|Ver)$/ }) }).last().getByRole('button', { name: /^(Edit|Editar|Ver)$/ }).click();
    const wake = page.getByTestId('wake-policy');
    await wake.waitFor({ timeout: 20000 });
    await wake.scrollIntoViewIfNeeded();
    check('policy editor shows "Sondeo de respaldo" with the saved minutes',
      (await wake.getByLabel('Sondeo normal en minutos').inputValue()) === '3'
      && (await wake.getByLabel('Sondeo rápido en minutos').inputValue()) === '1'
      && (await wake.getByLabel('Duración del sondeo rápido en minutos').inputValue()) === '15');
    await page.screenshot({ path: `${shots}/politica-sondeo.png`, fullPage: true });
    // The agent is told at its next check-in (the answer carries the schedule): it reports the pace back.
    await rest('POST', `/private/agent/v1/devices/${deviceNumber}/sync`);
    const paced = await until(async () => (await reach())?.pollMinutes === 3, 90000);
    check('the phone took the new pace (reports a 3-minute backup check-in)', !!paced, JSON.stringify(await reach()).slice(0, 200));
  }
} catch (e) {
  check('unexpected error', false, String(e).slice(0, 400));
  await page.screenshot({ path: `${shots}/error.png`, fullPage: true }).catch(() => undefined);
} finally {
  if (policyTouched) {
    const full = (await rest('GET', `/private/configurations/${cfgId}`)).data;
    await rest('PUT', '/private/configurations', { ...full, dcPolicy: origPolicy });
  }
  if (sim) await simSet('ws=open&deliver=1').catch(() => undefined);
  await browser.close();
}
console.log(`\n${pass} passed, ${fail} failed · screenshots in ${shots}`);
process.exit(fail ? 1 : 0);
