// Console (web) checks of the remote-command screens against a running DallyControl, in a real browser:
// the device's command history (created -> delivered -> completed, latency, who sent it, cancel), the notice when a
// command is queued for a device without link, the "Borrado" mark, and the "Latencia de comandos" report.
//
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/command-console-e2e.mjs --api http://localhost:8088 \
//       --device-id <numeric device id> [--shots dir]
//
// Run it after scripts/command-reliability-e2e.py (it needs a device with command history; that device is normally
// offline by then, which is what the "queued without link" check wants). Uses the Playwright copy under
// scripts/shots (npm ci there once). Exit 0 only if all checks pass.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const deviceId = arg('--device-id');
const shots = arg('--shots', '/tmp/dallycontrol-command-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !deviceId || !password) { console.error('--api, --device-id and DALLYCONTROL_ADMIN_PASSWORD are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
const page = await ctx.newPage();
page.on('dialog', (d) => { void d.accept(); }); // the "¿Cancelar la orden…?" confirmation

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices|tablero/, { timeout: 20000 });
  check('console login', true);

  // --- device page: command history ---------------------------------------------------------------------------
  await page.goto(`${api}/devices/${deviceId}`);
  const timeline = page.locator('.timeline');
  await timeline.locator('.timeline-item').first().waitFor({ timeout: 20000 });
  const text = await timeline.innerText();
  check('history shows the three steps of a command', /Creada .*\n?.*→ Entregada .*\n?.*→ Completada/s.test(text), text.slice(0, 300));
  check('history shows latency in seconds', /\(\d+([.,]\d)? s\)|\(\d+ min \d+ s\)/.test(text), text.slice(0, 300));
  check('history shows who sent each command', /Enviada por admin/.test(text));
  check('automatic commands are shown as sent by "sistema"', /Enviada por sistema/.test(text));
  const statuses = await timeline.locator('.t-status').allInnerTexts();
  const seen = new Set(statuses.map((s) => s.trim().toUpperCase()));
  check('states are named Ejecutado / Cancelado / Vencido', ['EJECUTADO', 'CANCELADO', 'VENCIDO'].every((s) => seen.has(s)), [...seen].join(','));
  check('a redelivered command says how many times it was delivered', /entregada \d+ veces/.test(text));
  await page.screenshot({ path: `${shots}/device-history.png`, fullPage: true });

  // --- "Borrado" mark ---------------------------------------------------------------------------------------
  const badge = page.locator('.wiped-badge');
  const hasBadge = await badge.isVisible().catch(() => false);
  check('device that received the wipe and went silent shows "Borrado" with date and issuer',
    hasBadge && /Borrado · .* · por admin/.test(await badge.innerText()), hasBadge ? await badge.innerText() : 'no badge');

  // --- queue for a device without link, then cancel it from the history -------------------------------------
  await page.getByRole('button', { name: 'Hacer sonar', exact: true }).click();
  const toast = page.getByText('se ejecutará cuando el equipo vuelva a conectarse').first();
  await toast.waitFor({ timeout: 15000 }).catch(() => undefined);
  check('sending to a device without link says "se ejecutará cuando el equipo vuelva a conectarse"', await toast.isVisible().catch(() => false));
  const pending = timeline.locator('.timeline-item.status-pending').first();
  await pending.waitFor({ timeout: 15000 });
  check('the command stays queued as "Pendiente"', /PENDIENTE/i.test(await pending.locator('.t-status').innerText()));
  await page.screenshot({ path: `${shots}/device-pending.png`, fullPage: true });
  await pending.getByRole('button', { name: 'Cancelar' }).click();
  await timeline.locator('.timeline-item.status-cancelled', { hasText: 'Hacer sonar' }).first().waitFor({ timeout: 15000 });
  const cancelled = await timeline.locator('.timeline-item.status-cancelled', { hasText: 'Hacer sonar' }).first().innerText();
  check('cancel from the console: the command becomes "Cancelado" with who cancelled it', /CANCELADO/i.test(cancelled) && /Cancelado por admin/.test(cancelled), cancelled);

  // --- report ------------------------------------------------------------------------------------------------
  await page.goto(`${api}/reports`);
  await page.getByRole('button', { name: 'Latencia de comandos' }).click();
  await page.getByText('Bloquear').first().waitFor({ timeout: 20000 });
  const report = await page.locator('table').first().innerText();
  check('report has p50 / p90 / max and % executed columns',
    ['% ejecutados', 'Entrega p50 (s)', 'Ejecución p90 (s)', 'Ejecución máx. (s)'].every((h) => report.toLowerCase().includes(h.toLowerCase())), report.slice(0, 200));
  check('report has a row per command type and a total', report.includes('Todos los comandos') && report.includes('Bloquear') && report.includes('Mensaje'));
  check('report offers date range, folder and Excel export',
    await page.locator('input[type="date"]').count() === 2 && await page.getByText('Carpeta').first().isVisible()
    && await page.getByRole('button', { name: /Excel/ }).first().isVisible());
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 20000 }),
    page.getByRole('button', { name: /Excel/ }).first().click(),
  ]);
  check('Excel export downloads a .xlsx', /reporte-command-latency-.*\.xlsx$/.test(download.suggestedFilename()), download.suggestedFilename());
  await page.waitForTimeout(1500);
  const after = await page.locator('table').first().innerText();
  check('the latency table is still the one shown (not replaced by another report)', after.includes('Todos los comandos'), after.slice(0, 120));
  await page.screenshot({ path: `${shots}/report-latency.png`, fullPage: true });
} catch (e) {
  check('run completed', false, String(e).slice(0, 300));
  await page.screenshot({ path: `${shots}/failure.png`, fullPage: true }).catch(() => undefined);
} finally {
  await browser.close();
}
console.log(`RESULT: PASS=${pass} FAIL=${fail}  (screenshots in ${shots})`);
process.exit(fail ? 1 : 0);
