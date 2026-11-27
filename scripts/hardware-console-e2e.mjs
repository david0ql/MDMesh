#!/usr/bin/env node
// Console end-to-end test of "Hardware y puertos" in a headless browser: the policy editor's panel (Permitir / Bloquear
// per function, "Desde Android N", the camera-in-kiosk warning, the validated free list of Android restrictions) saved
// through the UI, and, with --device, the device's "Restricciones" tab (what the policy asks, the per-device exception
// and what the phone reported).
//
//   (cd scripts/shots && npm ci)       # once: Playwright
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/hardware-console-e2e.mjs --api http://localhost:8088 \
//        [--device <device number>] [--shots /tmp/dallycontrol-hardware-shots]
//
// It works on a copy of an existing policy (e2e-hw-*) and deletes it on exit; with --device it sets and removes a
// camera exception on that device. Exit code 0 = every check passed.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const device = arg('--device');
const shots = arg('--shots', '/tmp/dallycontrol-hardware-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !password) { console.error('--api and DALLYCONTROL_ADMIN_PASSWORD are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1400, height: 950 }, locale: 'es-CO' });
const page = await ctx.newPage();
page.on('dialog', (d) => d.accept());
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
const rest = async (method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined });
  return r.json();
}, [method, path, body]);
const configs = async () => (await rest('GET', '/private/configurations/search')).data ?? [];
const name = `e2e-hw-${Math.floor(Math.random() * 1e5)}`;
let copyId = null;

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 20000 });
  check('console login', true);

  // --- a throwaway kiosk policy with the "Cámara" function ---------------------------------------------------------
  const base = (await configs())[0];
  await rest('PUT', '/private/configurations/copy', { id: base.id, name, description: 'hardware-console-e2e' });
  const copy = (await configs()).find((c) => c.name === name);
  copyId = copy?.id ?? null;
  check('test policy created', !!copyId);
  const apps = ((await rest('GET', `/private/configurations/applications/${copyId}`)).data ?? []).filter((a) => a.selected);
  await rest('PUT', '/private/configurations', { ...copy, kioskMode: true, dcPolicy: JSON.stringify({ kioskRoles: ['phone', 'camera'] }),
    restrictions: null, usbStorage: null, bluetooth: null, disableScreenshots: null, applications: apps });

  // --- policy editor -----------------------------------------------------------------------------------------------
  await page.goto(`${api}/configs`);
  await page.locator('.cfg-card', { hasText: name }).getByRole('button', { name: 'Editar' }).click();
  const panel = page.getByTestId('hardware-policy');
  await panel.waitFor({ timeout: 20000 });
  check('the policy editor shows «Hardware y puertos»', await panel.getByText('Hardware y puertos', { exact: true }).isVisible());
  const rows = await panel.locator('[data-testid^="hw-rule-"]').count();
  check('every function has its own option', rows === 23, `${rows} rows`);
  for (const label of ['Cámara', 'Depuración USB (ADB)', 'Datos por USB', 'Compartir archivos por Bluetooth', 'NFC', 'Micrófono',
    'Tarjeta SD y memorias USB', 'Modo avión', 'Wi‑Fi Direct', 'Imprimir', 'Restablecer ajustes de red', 'Cambiar fecha y hora',
    'Instalar de orígenes desconocidos', 'Compartir ubicación', 'Captura de contenido y sugerencias', 'Capturas de pantalla']) {
    if (!(await panel.getByLabel(label, { exact: true }).count())) check(`option «${label}»`, false, 'not found');
  }
  check('rules newer than Android 6 say since which Android', await panel.getByTestId('hw-rule-usbData').getByText('Desde Android 12').isVisible()
    && await panel.getByTestId('hw-rule-nfc').getByText('Desde Android 15').isVisible()
    && await panel.getByTestId('hw-rule-wifiDirect').getByText('Desde Android 13').isVisible());
  check('the «Cámara» option has Sin gestionar / Permitir / Bloquear',
    (await panel.getByLabel('Cámara', { exact: true }).locator('option').allTextContents()).join('|') === 'Sin gestionar|Permitir|Bloquear');

  check('no kiosk warning while the camera is not blocked', !(await panel.getByTestId('hw-camera-kiosk-warning').count()));
  await panel.getByLabel('Cámara', { exact: true }).selectOption('block');
  check('blocking the camera in a kiosk with the «Cámara» function warns', await panel.getByTestId('hw-camera-kiosk-warning').isVisible());
  await panel.getByLabel('Depuración USB (ADB)', { exact: true }).selectOption('block');
  check('blocking ADB explains how support recovers the device',
    await panel.getByTestId('hw-rule-usbDebug').getByText(/Se revierte desde esta consola/).isVisible());
  await panel.getByLabel('Datos por USB', { exact: true }).selectOption('block');
  check('blocking USB data explains "no soportado por el equipo"', await panel.getByTestId('hw-rule-usbData').getByText(/no soportado por el equipo/).isVisible());
  await panel.getByLabel('NFC', { exact: true }).selectOption('allow');
  await panel.getByLabel('Capturas de pantalla', { exact: true }).selectOption('block');
  await panel.getByLabel('Transferencia de archivos por USB', { exact: true }).selectOption('block');
  await panel.getByLabel('Compartir datos (zona Wi‑Fi, USB y Bluetooth)', { exact: true }).selectOption('block');

  await panel.getByLabel('Restricciones de Android', { exact: true }).fill('no_sms, DISALLOW_SAFE_BOOT, no_factory_reset, cosa_rara');
  check('free list: accepted entries are shown', (await panel.getByTestId('hw-free-accepted').textContent())?.includes('no_safe_boot')
    && (await panel.getByTestId('hw-free-accepted').textContent())?.includes('no_sms'));
  check('free list: an unknown entry is flagged as not sent', (await panel.getByTestId('hw-free-unknown').textContent())?.includes('cosa_rara'));
  check('free list: an entry with its own option is flagged', (await panel.getByTestId('hw-free-own').textContent())?.includes('no_factory_reset'));
  await panel.screenshot({ path: `${shots}/policy-hardware.png` });

  await page.getByRole('button', { name: 'Guardar', exact: true }).click();
  const confirm = page.getByRole('button', { name: /Aplicar|Guardar de todos modos|Confirmar|Sí/i });
  if (await confirm.first().isVisible({ timeout: 3000 }).catch(() => false)) await confirm.first().click();
  await page.waitForTimeout(2500);
  const saved = (await configs()).find((c) => c.id === copyId);
  const dc = JSON.parse(saved.dcPolicy || '{}');
  check('saved from the UI: camera/usbDebug/usbData blocked, NFC allowed',
    JSON.stringify(dc.hardware) === JSON.stringify({ camera: 'block', nfc: 'allow', usbData: 'block', usbDebug: 'block' }), JSON.stringify(dc.hardware));
  check('saved from the UI: screenshots, USB storage and data sharing blocked through their own fields',
    saved.disableScreenshots === true && saved.usbStorage === false && dc.device?.tethering === 'block',
    JSON.stringify([saved.disableScreenshots, saved.usbStorage, dc.device]));
  check('saved from the UI: the free field', saved.restrictions === 'no_sms, DISALLOW_SAFE_BOOT, no_factory_reset, cosa_rara', saved.restrictions);

  // Reopen: the editor shows what was saved.
  await page.goto(`${api}/configs`);
  await page.locator('.cfg-card', { hasText: name }).getByRole('button', { name: 'Editar' }).click();
  await page.getByTestId('hardware-policy').waitFor({ timeout: 20000 });
  const again = page.getByTestId('hardware-policy');
  check('reopened: the saved choices are shown', await again.getByLabel('Cámara', { exact: true }).inputValue() === 'block'
    && await again.getByLabel('NFC', { exact: true }).inputValue() === 'allow'
    && await again.getByLabel('Micrófono', { exact: true }).inputValue() === ''
    && await again.getByLabel('Capturas de pantalla', { exact: true }).inputValue() === 'block');
  check('reopened: the count of blocked functions', (await again.getByTestId('hardware-blocked-count').textContent())?.startsWith('6 '),
    await again.getByTestId('hardware-blocked-count').textContent().catch(() => 'none'));
  check('the old free textarea is gone from the legacy fields',
    !(await page.locator('.cfg-field', { hasText: 'Restricciones de usuario de Android, separadas por comas, que se aplican en modo MDM' }).count()));

  // --- device tab --------------------------------------------------------------------------------------------------
  if (device) {
    await rest('PUT', `/private/agent/v1/devices/${encodeURIComponent(device)}/hardware`, { rules: { camera: 'policy' } });
    await page.goto(`${api}/devices/${encodeURIComponent(device)}`);
    await page.getByTestId('tab-hardware').click();
    const hw = page.getByTestId('hardware-panel');
    await hw.waitFor({ timeout: 20000 });
    check('device: the «Restricciones» tab shows «Restricciones aplicadas»', await hw.getByRole('heading', { name: 'Restricciones aplicadas' }).isVisible());
    check('device: one row per function', await hw.locator('[data-testid^="hw-row-"]').count() === 23);
    await hw.getByLabel('Excepción de Cámara').selectOption('block');
    await page.getByText('Excepción del equipo guardada').first().waitFor({ timeout: 10000 });
    check('device: the camera exception is saved from the UI',
      (await rest('GET', `/private/agent/v1/devices/${encodeURIComponent(device)}/hardware`)).data.items.find((i) => i.key === 'camera').override === 'block');
    const row = hw.getByTestId('hw-row-camera');
    let outcome = '';
    for (let i = 0; i < 45 && outcome !== 'applied'; i++) {
      await page.waitForTimeout(2000);
      if (i % 5 === 4) await hw.getByRole('button', { name: 'Actualizar' }).click();
      outcome = (await row.getAttribute('data-outcome')) ?? '';
    }
    check('device: the phone reports the camera as blocked', outcome === 'applied' && await row.getByText('Bloqueado en el equipo').isVisible(), outcome);
    await hw.screenshot({ path: `${shots}/device-restricciones.png` });
    await hw.getByLabel('Solo gestionadas').check();
    check('device: «Solo gestionadas» hides what the policy does not manage',
      await hw.locator('[data-testid^="hw-row-"]').count() < 23 && await row.isVisible());
    await hw.getByLabel('Solo gestionadas').uncheck();
    await hw.getByLabel('Excepción de Cámara').selectOption('policy');
    await page.waitForTimeout(1500);
    check('device: «Según política» removes the exception',
      !(await rest('GET', `/private/agent/v1/devices/${encodeURIComponent(device)}/hardware`)).data.items.find((i) => i.key === 'camera').override);
  }
  check('no JavaScript errors in the console pages', errors.length === 0, errors.join(' | ').slice(0, 300));
} catch (e) {
  check('run completed', false, String(e).slice(0, 400));
  await page.screenshot({ path: `${shots}/failure.png`, fullPage: true }).catch(() => {});
} finally {
  if (copyId) await rest('DELETE', `/private/configurations/${copyId}`).catch(() => {});
  if (device) await rest('PUT', `/private/agent/v1/devices/${encodeURIComponent(device)}/hardware`, { rules: { camera: 'policy' } }).catch(() => {});
  await browser.close();
}
console.log(`\nRESULT: PASS=${pass} FAIL=${fail}   screenshots: ${shots}`);
process.exit(fail ? 1 : 0);
