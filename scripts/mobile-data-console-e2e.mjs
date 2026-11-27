#!/usr/bin/env node
// Console end-to-end test of "Datos móviles" in a headless browser: the section of a policy (mode, apps picked by
// package, limits, period, threshold, essential apps, mechanism), saved and reopened; the device's card under "Uso de
// apps" (mechanism, apps without mobile data, use against limits) and its per-device override; the two alert types in
// Alertas; and the report "Datos móviles: uso vs. límite" with its export.
//
//   (cd scripts/shots && npm ci)       # once: Playwright
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/mobile-data-console-e2e.mjs --api http://localhost:8088 \
//        [--shots /tmp/dallycontrol-mobile-data-shots]
//
// It creates an e2e-ui-datos-* policy and a throw-away device that reports like a phone would (through the public agent
// API), and removes both on exit. Exit code 0 = every check passed.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const shots = arg('--shots', '/tmp/dallycontrol-mobile-data-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !password) { console.error('--api and DALLYCONTROL_ADMIN_PASSWORD are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };
const MB = 1024 * 1024;

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1400, height: 950 }, locale: 'es-CO', acceptDownloads: true });
const page = await ctx.newPage();
page.on('dialog', (d) => d.accept());
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
const rest = async (method, path, body, headers) => page.evaluate(async ([m, p, b, h]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: { ...(b ? { 'Content-Type': 'application/json' } : {}), ...(h ?? {}) }, body: b ? JSON.stringify(b) : undefined });
  return r.json();
}, [method, path, body, headers]);
const suf = Math.floor(Math.random() * 1e5);
const policyName = `e2e-ui-datos-${suf}`;
let policyId = null, deviceRowId = null, number = null;
const mobileData = async () => JSON.parse((await rest('GET', `/private/configurations/${policyId}`)).data.dcPolicy || '{}').mobileData;

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 20000 });
  check('console login', true);

  // --- a throw-away policy (copy of the first one, no apps) ---------------------------------------------------------
  const first = (await rest('GET', '/private/configurations/search')).data[0];
  const src = (await rest('GET', `/private/configurations/${first.id}`)).data;
  const body = { ...src, name: policyName, description: 'mobile-data-console-e2e (temporal)', applications: [], kioskMode: false, mainAppId: null, dcPolicy: null };
  delete body.id; delete body.qrCodeKey; delete body.selected;
  policyId = (await rest('PUT', '/private/configurations', body)).data.id;
  check('test policy created', Number.isInteger(policyId));

  // --- policy editor: section "Datos móviles" -----------------------------------------------------------------------
  await page.goto(`${api}/configs`);
  const card = page.locator('.cfg-card', { hasText: policyName });
  await card.waitFor({ timeout: 20000 });
  await card.getByRole('button', { name: 'Editar' }).click();
  const sec = page.getByTestId('policy-mobile-data');
  await sec.waitFor({ timeout: 20000 });
  await sec.scrollIntoViewIfNeeded();
  check('the policy has a «Datos móviles» section', await sec.getByText('Datos móviles', { exact: true }).isVisible());
  check('starts without restriction', (await sec.getByTestId('md-mode').inputValue()) === 'open');
  check('no app list, period or mechanism while nothing is set',
    (await sec.getByTestId('md-apps').count()) === 0 && (await sec.getByTestId('md-period').count()) === 0 && (await sec.getByTestId('md-mechanism').count()) === 0);

  await sec.getByTestId('md-mode').selectOption('blocklist');
  const apps = sec.getByTestId('md-apps');
  check('blocklist shows the «solo Wi‑Fi» list', await apps.getByText('Aún no hay apps en la lista.').isVisible());
  const addApp = apps.getByLabel('Agregar a apps sin datos móviles');
  await addApp.fill('esto no es un paquete');
  await addApp.press('Enter');
  check('a text that is not a package name is refused', await apps.getByText(/No es un nombre de paquete/).isVisible());
  await addApp.fill('com.video.app, com.audio.app');
  await addApp.press('Enter');
  check('two packages added from one paste', (await apps.locator('.dcp-sites-list li').count()) === 2);
  await apps.getByRole('button', { name: 'Quitar com.audio.app' }).click();
  check('a package can be removed', (await apps.locator('.dcp-sites-list li').count()) === 1);

  await sec.getByTestId('md-device-limit').fill('500');
  await sec.getByRole('button', { name: '+ Agregar límite' }).click();
  await sec.getByLabel('Límite 1 paquete').fill('com.chat.app');
  await sec.getByLabel('Límite 1 megabytes').fill('100');
  check('limits reveal period, cut day and warning threshold',
    (await sec.getByTestId('md-period').isVisible()) && (await sec.getByTestId('md-cut-day').isVisible()) && (await sec.getByTestId('md-warn').isVisible()));
  await sec.getByTestId('md-cut-day').fill('15');
  await sec.getByTestId('md-warn').fill('70');
  const essential = sec.getByTestId('md-essential');
  check('a device limit reveals the essential apps', await essential.isVisible());
  await essential.getByLabel('Agregar a apps esenciales').fill('com.sales.app');
  await essential.getByLabel('Agregar a apps esenciales').press('Enter');
  await sec.getByTestId('md-mechanism').selectOption('vpn');
  await page.screenshot({ path: `${shots}/01-policy-section.png`, fullPage: true });

  await page.getByRole('button', { name: 'Guardar', exact: true }).first().click();
  await page.locator('.cfg-card', { hasText: policyName }).waitFor({ timeout: 20000 });
  const saved = await mobileData();
  check('saved: mode, apps and mechanism', saved?.mode === 'blocklist' && JSON.stringify(saved.apps) === '["com.video.app"]' && saved.mechanism === 'vpn', JSON.stringify(saved));
  check('saved: limits in megabytes', saved?.deviceLimitMb === 500 && JSON.stringify(saved.appLimits) === '[{"pkg":"com.chat.app","limitMb":100}]', JSON.stringify(saved));
  check('saved: cut day, threshold and essential apps', saved?.cutDay === 15 && saved.warnPercent === 70 && JSON.stringify(saved.essential) === '["com.sales.app"]', JSON.stringify(saved));

  await page.locator('.cfg-card', { hasText: policyName }).getByRole('button', { name: 'Editar' }).click();
  const again = page.getByTestId('policy-mobile-data');
  await again.waitFor({ timeout: 20000 });
  check('reopened: the editor shows what was saved',
    (await again.getByTestId('md-mode').inputValue()) === 'blocklist' && (await again.getByTestId('md-device-limit').inputValue()) === '500'
    && (await again.getByTestId('md-cut-day').inputValue()) === '15' && (await again.getByTestId('md-warn').inputValue()) === '70'
    && (await again.getByTestId('md-mechanism').inputValue()) === 'vpn' && (await again.getByLabel('Límite 1 paquete').inputValue()) === 'com.chat.app');
  await again.getByTestId('md-mechanism').selectOption('auto');
  await again.getByTestId('md-period').selectOption('daily');
  check('daily period hides the cut day', (await again.getByTestId('md-cut-day').count()) === 0);
  await again.getByTestId('md-period').selectOption('monthly');
  await page.getByRole('button', { name: 'Guardar', exact: true }).first().click();
  await page.locator('.cfg-card', { hasText: policyName }).waitFor({ timeout: 20000 });
  check('automatic mechanism is stored as «not set»', (await mobileData())?.mechanism === undefined);

  // --- a device that reports like a phone ---------------------------------------------------------------------------
  const token = (await rest('POST', '/private/agent/v1/token', { configurationId: policyId })).data.token;
  const enr = (await rest('POST', '/public/agent/v1/enroll', {
    enrollToken: token, agent: { version: '0.9.9', package: 'com.dallycontrol.agent' }, device: { androidSdkInt: 34, isDeviceOwner: true },
    capabilities: { policy: ['wifi'], appManagement: [], remoteControl: { tier: 'none' }, oem: { vendor: 'e2e', knox: false } },
  })).data;
  number = enr.deviceId;
  const found = (await rest('POST', '/private/devices/search', { value: number, pageSize: 5, pageNum: 1 })).data.devices.items;
  deviceRowId = found.find((d) => d.number === number).id;
  const now = Date.now();
  const d = new Date();
  const start = new Date(d.getFullYear(), d.getMonth() - (d.getDate() < 15 ? 1 : 0), 15).getTime();
  const status = {
    mode: 'blocklist', mechanism: 'dpm', blockedCount: 2, period: 'monthly', periodStart: start, periodEnd: start + 30 * 86400000,
    deviceBytes: 460 * MB, deviceLimitBytes: 500 * MB, warnPercent: 70, evaluatedAt: now,
    blocked: [{ pkg: 'com.video.app', reason: 'policy' }, { pkg: 'com.chat.app', reason: 'appLimit' }],
    apps: [{ pkg: 'com.chat.app', label: 'Chat', bytes: 101 * MB, limitBytes: 100 * MB }, { pkg: 'com.video.app', label: 'Video', bytes: 20 * MB }],
  };
  const checkin = await rest('POST', '/public/agent/v1/checkin', {
    deviceId: number, capabilities: { policy: ['wifi'], device: ['configApply'] },
    state: { battery: 80, charging: false, locked: false, kioskActive: false, androidRelease: '14', lastBootAt: 1000 },
    telemetry: { dynamic: { mobileData: status } },
    events: [{ type: 'dataWarning', ts: now - 2000, detail: 'Equipo: 460 MB de 500 MB (92 %)' },
      { type: 'dataLimit', ts: now - 1000, detail: 'Chat (com.chat.app): 101 MB de 100 MB (101 %)' }],
  }, { Authorization: `Bearer ${enr.deviceSecret}` });
  check('the device reported its mobile-data state', checkin.status === 'OK', JSON.stringify(checkin).slice(0, 200));

  // --- device page: card under "Uso de apps" -------------------------------------------------------------------------
  await page.goto(`${api}/devices/${encodeURIComponent(number)}`);
  await page.getByRole('button', { name: 'Uso de apps' }).click();
  const dcard = page.getByTestId('mobile-data-card');
  await dcard.getByTestId('md-usage').waitFor({ timeout: 20000 });
  check('device card shows the mechanism the phone uses', await dcard.getByTestId('md-mechanism-state').getByText(/Restricción de datos del sistema/).isVisible());
  const usage = await dcard.getByTestId('md-usage').innerText();
  check('device row: 460 MB of 500 MB, near the limit', /Todo el equipo/.test(usage) && /460 MB/.test(usage) && /500 MB \(92 %\)/.test(usage) && /Cerca del límite/.test(usage), usage);
  check('app row: over its limit', /Chat/.test(usage) && /101 MB/.test(usage) && /Límite superado/.test(usage), usage);
  const blocked = await dcard.getByTestId('md-blocked').innerText();
  check('apps without mobile data, with the reason', /com\.video\.app/.test(blocked) && /Por la lista de la política/.test(blocked) && /Superó su límite/.test(blocked), blocked);
  check('top consumers of the period', await dcard.getByTestId('md-top').getByText('Chat').isVisible());
  await dcard.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${shots}/02-device-card.png`, fullPage: true });

  await dcard.getByTestId('md-edit').click();
  await dcard.getByTestId('md-allow').fill('com.video.app');
  await dcard.getByTestId('md-block').fill('com.game.app\nmal paquete');
  check('an invalid package disables saving', await dcard.getByTestId('md-save').isDisabled());
  await dcard.getByTestId('md-block').fill('com.game.app');
  await dcard.getByTestId('md-ignore-limits').check();
  await dcard.getByTestId('md-save').click();
  await dcard.getByTestId('md-override').waitFor({ timeout: 15000 });
  const view = (await rest('GET', `/private/agent/v1/devices/${encodeURIComponent(number)}/mobileData`)).data;
  check('override stored', JSON.stringify(view.override) === '{"allow":["com.video.app"],"block":["com.game.app"],"ignoreLimits":true}', JSON.stringify(view.override));
  check('effective: allowed app out of the list, blocked app in, no limits',
    JSON.stringify(view.effective.apps) === '["com.game.app"]' && view.effective.deviceLimitBytes === undefined, JSON.stringify(view.effective));
  check('card names the override', /siempre con datos: com\.video\.app/.test(await dcard.getByTestId('md-override').innerText()));
  await page.screenshot({ path: `${shots}/03-device-override.png`, fullPage: true });
  await dcard.getByTestId('md-edit').click();
  await dcard.getByTestId('md-unrestricted').check();
  await dcard.getByTestId('md-save').click();
  await dcard.getByText('Este equipo no tiene restricción de datos móviles').waitFor({ timeout: 15000 });
  check('«sin restricción» on this device: nothing is sent to the phone',
    (await rest('GET', `/private/agent/v1/devices/${encodeURIComponent(number)}/mobileData`)).data.effective === null);
  await dcard.getByTestId('md-reset').click();
  await dcard.getByTestId('md-override').waitFor({ state: 'detached', timeout: 15000 });
  check('«Volver a la política» removes the override',
    (await rest('GET', `/private/agent/v1/devices/${encodeURIComponent(number)}/mobileData`)).data.override === null);

  // --- phone width ----------------------------------------------------------------------------------------------------
  await page.setViewportSize({ width: 390, height: 844 });
  await dcard.scrollIntoViewIfNeeded();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('device card fits a phone screen (no sideways scroll)', overflow <= 2, `overflow ${overflow}px`);
  await page.screenshot({ path: `${shots}/04-device-card-phone.png`, fullPage: true });
  await page.setViewportSize({ width: 1400, height: 950 });

  // --- alerts -----------------------------------------------------------------------------------------------------------
  await page.goto(`${api}/alerts`);
  const typeSel = page.locator('.report-toolbar select').first();
  await typeSel.waitFor({ timeout: 15000 });
  const options = await typeSel.locator('option').allInnerTexts();
  check('Alertas offers the two mobile-data types', options.includes('Datos móviles: cerca del límite') && options.includes('Datos móviles: límite superado'), options.join(' | '));
  await typeSel.selectOption('dataLimit');
  await page.getByText('Chat (com.chat.app): 101 MB de 100 MB (101 %)').first().waitFor({ timeout: 20000 });
  check('the «límite superado» alert of the device is listed with its detail', true);
  await typeSel.selectOption('dataWarning');
  await page.getByText('Equipo: 460 MB de 500 MB (92 %)').first().waitFor({ timeout: 20000 });
  check('the «cerca del límite» alert is listed with its detail', true);
  await page.screenshot({ path: `${shots}/05-alerts.png`, fullPage: true });

  // --- report ---------------------------------------------------------------------------------------------------------
  await page.goto(`${api}/reports`);
  await page.getByRole('button', { name: 'Datos móviles: uso vs. límite' }).click();
  const row = page.locator('tr', { hasText: number }).first();
  await row.waitFor({ timeout: 20000 });
  const table = await page.locator('table').first().innerText();
  check('report lists the device with consumption, limit and state', /Consumo del periodo/.test(table) && /Límite superado/.test(table) && /Cerca del límite/.test(table), table.slice(0, 300));
  await page.screenshot({ path: `${shots}/06-report.png`, fullPage: true });
  const csv = await page.evaluate(async () => {
    const r = await fetch('/rest/private/reports/v1/export?report=data-limits&format=csv');
    return { status: r.status, text: await r.text() };
  });
  check('report exports to CSV with the device', csv.status === 200 && csv.text.includes(number) && csv.text.includes('Consumo del periodo'), `${csv.status}`);

  check('no JavaScript errors in the console', errors.length === 0, errors.join(' | '));
} catch (e) {
  check('script ran to the end', false, String(e).slice(0, 400));
  await page.screenshot({ path: `${shots}/99-failure.png`, fullPage: true }).catch(() => {});
} finally {
  if (deviceRowId) await rest('POST', '/private/devices/deleteBulk', { ids: [deviceRowId] }).catch(() => {});
  if (policyId) await rest('DELETE', `/private/configurations/${policyId}`).catch(() => {});
  await browser.close();
}
console.log(`RESULT: PASS=${pass} FAIL=${fail}  (screenshots in ${shots})`);
process.exit(fail ? 1 : 0);
