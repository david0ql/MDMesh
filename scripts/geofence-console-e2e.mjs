#!/usr/bin/env node
// Console end-to-end test of the Geocercas module in a headless browser: draw a circle and a polygon on the map
// (click, drag the handles, remove a vertex), save, edit, delete; the e-mail recipients and thresholds tabs; the new
// alert types in Alertas; and, with --device, the zones on the device's Ubicación tab and on the fleet map.
//
//   (cd scripts/shots && npm ci)       # once: Playwright
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/geofence-console-e2e.mjs --api http://localhost:8088 \
//        [--device <device number>] [--shots /tmp/dallycontrol-geofence-shots]
//
// It creates e2e-ui-* zones and one recipient rule and removes them on exit. Exit code 0 = every check passed.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const device = arg('--device');
const shots = arg('--shots', '/tmp/dallycontrol-geofence-shots');
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
const zones = async () => (await rest('GET', '/private/geofences/v1/zones')).data ?? [];
const suf = Math.floor(Math.random() * 1e5);
const circleName = `e2e-ui-circulo-${suf}`;
const polyName = `e2e-ui-poligono-${suf}`;
const mail = `e2e-ui-${suf}@example.test`;
let settingsBefore;

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 20000 });
  check('console login', true);
  settingsBefore = (await rest('GET', '/private/geofences/v1/settings')).data;

  // --- menu + page -------------------------------------------------------------------------------------------------
  await page.getByRole('link', { name: 'Geocercas' }).click();
  await page.waitForURL(/geofences/);
  await page.locator('.gz-map.leaflet-container').waitFor({ timeout: 15000 });
  check('Geocercas page opens from the menu with its map', true);

  const map = page.locator('.gz-map');
  const box = async () => map.boundingBox();
  const at = async (fx, fy) => { const b = await box(); return [b.x + b.width * fx, b.y + b.height * fy]; };
  const clickMap = async (fx, fy) => { const [x, y] = await at(fx, fy); await page.mouse.click(x, y); };
  const drag = async (locator, dx, dy) => {
    const b = await locator.boundingBox();
    const x = b.x + b.width / 2, y = b.y + b.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + dx / 2, y + dy / 2, { steps: 4 });
    await page.mouse.move(x + dx, y + dy, { steps: 4 });
    await page.mouse.up();
  };

  // --- circle ------------------------------------------------------------------------------------------------------
  await page.getByRole('button', { name: 'Nuevo círculo' }).click();
  await page.getByLabel('Nombre de la zona').fill(circleName);
  check('hint asks to click the map', await page.getByText('Haz clic en el mapa para poner el centro del círculo.').isVisible());
  await page.getByRole('button', { name: 'Guardar zona' }).click();
  check('a zone without drawing is not saved', (await zones()).every((z) => z.name !== circleName));
  await clickMap(0.5, 0.5);
  await page.locator('.gz-handle.gz-edge').waitFor({ timeout: 5000 });
  check('click places the circle with its centre and radius handles',
    (await page.locator('.gz-handle.gz-centre').count()) === 1 && (await page.locator('.gz-handle.gz-edge').count()) === 1);
  const radius = () => page.getByLabel('Radio en metros').inputValue().then(Number);
  const r0 = await radius();
  check('a first radius is proposed', r0 >= 50, String(r0));
  await drag(page.locator('.gz-handle.gz-edge'), 80, 0);
  const r1 = await radius();
  check('dragging the edge handle grows the radius', r1 > r0 * 1.2, `${r0} -> ${r1}`);
  const centreText = () => page.locator('.gz-form').getByText(/^-?\d+\.\d{5}, -?\d+\.\d{5}$/).textContent();
  const c0 = await centreText();
  await drag(page.locator('.gz-handle.gz-centre'), -60, 40);
  const c1 = await centreText();
  check('dragging the centre moves the zone', c0 !== c1, `${c0} -> ${c1}`);
  await page.getByLabel('Radio en metros').fill('350');
  await page.locator('label.fm-check', { hasText: 'Toda la flota' }).locator('input').check();
  await page.getByLabel('Correos de la zona').fill(mail);
  await page.screenshot({ path: `${shots}/1-circulo.png` });
  await page.getByRole('button', { name: 'Guardar zona' }).click();
  await page.locator('.gz-side li', { hasText: circleName }).waitFor({ timeout: 10000 });
  let saved = (await zones()).find((z) => z.name === circleName);
  check('circle saved', !!saved && saved.shape === 'circle' && saved.radiusM === 350 && saved.allDevices === true && saved.rule === 'stayInside',
    JSON.stringify(saved));
  check('its e-mail was stored', saved?.emails === mail, saved?.emails);

  // --- polygon -----------------------------------------------------------------------------------------------------
  await page.getByRole('button', { name: 'Nuevo polígono' }).click();
  await page.getByLabel('Nombre de la zona').fill(polyName);
  for (const [fx, fy] of [[0.2, 0.2], [0.4, 0.2], [0.4, 0.45], [0.3, 0.55], [0.2, 0.45]]) await clickMap(fx, fy);
  check('five clicks make five vertices', await page.getByText('5 vértices').isVisible());
  check('vertex and mid-point handles are shown',
    (await page.locator('.gz-handle.gz-vertex').count()) === 5 && (await page.locator('.gz-handle.gz-mid').count()) === 5);
  await page.locator('.gz-handle.gz-vertex').nth(3).click({ button: 'right' });
  check('right-click removes a vertex', await page.getByText('4 vértices').isVisible());
  await page.locator('.gz-handle.gz-mid').first().click();
  check('clicking a mid-point adds a vertex there', await page.getByText('5 vértices').isVisible());
  await drag(page.locator('.gz-handle.gz-vertex').first(), -30, -20);
  check('dragging a vertex keeps the polygon', await page.getByText('5 vértices').isVisible());
  await page.getByLabel('Regla').selectOption('keepOut');
  // Schedule: weekdays 08:00–18:00.
  await page.locator('label.fm-check', { hasText: 'Siempre' }).locator('input').uncheck();
  check('schedule editor appears', (await page.locator('.gz-days button.on').count()) === 5 && await page.getByLabel('Desde').isVisible());
  await page.locator('label.fm-check', { hasText: 'Toda la flota' }).locator('input').check();
  await page.screenshot({ path: `${shots}/2-poligono.png` });
  await page.getByRole('button', { name: 'Guardar zona' }).click();
  await page.locator('.gz-side li', { hasText: polyName }).waitFor({ timeout: 10000 });
  let poly = (await zones()).find((z) => z.name === polyName);
  check('polygon saved with its vertices, rule and schedule',
    !!poly && poly.shape === 'polygon' && poly.points.length === 5 && poly.rule === 'keepOut' && /"days":\[1,2,3,4,5\]/.test(poly.schedule ?? ''),
    JSON.stringify(poly));
  check('both zones are drawn on the map', (await page.locator('.gz-map path.leaflet-interactive').count()) >= 2);

  // --- edit + delete -----------------------------------------------------------------------------------------------
  await page.locator('.gz-side li', { hasText: circleName }).getByRole('button', { name: 'Editar' }).click();
  await page.locator('.gz-handle.gz-edge').waitFor({ timeout: 5000 });
  check('editing shows the saved circle with handles', (await radius()) === 350);
  await page.getByLabel('Radio en metros').fill('500');
  await page.getByLabel('Minutos entre posiciones').fill('2');
  await page.getByRole('button', { name: 'Guardar zona' }).click();
  await page.getByRole('button', { name: 'Nuevo círculo' }).waitFor({ timeout: 10000 });
  saved = (await zones()).find((z) => z.name === circleName);
  check('edit saved (radius, reporting frequency)', saved?.radiusM === 500 && saved?.trackingMinutes === 2, JSON.stringify(saved));
  await page.locator('.gz-side li', { hasText: polyName }).getByRole('button', { name: 'Borrar' }).click();
  await page.locator('.gz-side li', { hasText: polyName }).waitFor({ state: 'detached', timeout: 10000 });
  check('polygon deleted', (await zones()).every((z) => z.name !== polyName));
  await page.screenshot({ path: `${shots}/3-lista.png` });

  // --- e-mail tab --------------------------------------------------------------------------------------------------
  const mailOn = (await rest('GET', '/private/geofences/v1/mail')).data.configured;
  await page.getByRole('button', { name: 'Notificaciones por correo' }).click();
  await page.getByLabel('Correos destinatarios').waitFor();
  check(`SMTP status is shown (${mailOn ? 'configured' : 'missing'})`,
    await page.getByText(mailOn ? 'Correo saliente configurado' : 'no tiene correo saliente (SMTP) configurado')
      .waitFor({ timeout: 10000 }).then(() => true, () => false));
  await page.getByLabel('Correos destinatarios').fill(mail);
  await page.locator('.gz-types label', { hasText: 'Salida de zona' }).locator('input').check();
  await page.getByRole('button', { name: 'Agregar destinatarios' }).click();
  await page.locator('table tr', { hasText: mail }).waitFor({ timeout: 10000 });
  const rule = ((await rest('GET', '/private/geofences/v1/recipients')).data ?? []).find((r) => r.emails === mail);
  check('recipient rule saved for the whole fleet, exits only', !!rule && rule.groupId == null && rule.types.join() === 'geofenceExit', JSON.stringify(rule));
  await page.screenshot({ path: `${shots}/4-correo.png` });
  await page.locator('table tr', { hasText: mail }).getByRole('button', { name: 'Quitar' }).click();
  await page.locator('table tr', { hasText: mail }).waitFor({ state: 'detached', timeout: 10000 });
  check('recipient rule removed', true);

  // --- thresholds tab ----------------------------------------------------------------------------------------------
  await page.getByRole('button', { name: 'Precisión e historial' }).click();
  await page.getByLabel('Retención del historial de ubicación').fill('180');
  await page.getByLabel('Precisión máxima aceptada').fill('80');
  await page.getByRole('button', { name: 'Guardar ajustes' }).click();
  await page.waitForTimeout(800);
  const s = (await rest('GET', '/private/geofences/v1/settings')).data;
  check('thresholds saved (accuracy 80 m, retention 180 days)', s.maxAccuracyM === 80 && s.retentionDays === 180, JSON.stringify(s));
  await page.screenshot({ path: `${shots}/5-ajustes.png` });

  // --- alerts page -------------------------------------------------------------------------------------------------
  await page.goto(`${api}/alerts`);
  const typeSelect = page.locator('label.report-field', { hasText: 'Tipo' }).locator('select');
  await typeSelect.waitFor();
  const options = await typeSelect.locator('option').allTextContents();
  check('Alertas offers the zone types', options.includes('Salida de zona') && options.includes('Entrada a zona'), options.join());
  await typeSelect.selectOption('geofenceExit');
  await page.getByRole('button', { name: 'Actualizar' }).click();
  await page.waitForTimeout(1200);
  const kinds = await page.locator('table.table tbody tr td:nth-child(2)').allTextContents();
  check(`filtering by "Salida de zona" lists only those (${kinds.length})`, kinds.every((k) => k === 'Salida de zona'));
  await page.screenshot({ path: `${shots}/6-alertas.png` });

  // --- fleet map + device --------------------------------------------------------------------------------------------
  await page.goto(`${api}/map`);
  await page.locator('.fm-map.leaflet-container').waitFor({ timeout: 15000 });
  await page.locator('label.fm-check', { hasText: 'Zonas' }).waitFor({ timeout: 10000 });
  await page.waitForTimeout(1000);
  check('fleet map draws the zones', (await page.locator('.fm-map path.leaflet-interactive').count()) >= 1);
  await page.locator('label.fm-check', { hasText: 'Zonas' }).locator('input').uncheck();
  await page.screenshot({ path: `${shots}/7-mapa-flota.png` });

  if (device) {
    await page.goto(`${api}/devices/${encodeURIComponent(device)}?tab=location`);
    await page.getByText('Historial de ubicación').waitFor({ timeout: 20000 });
    check('?tab=location opens the device on its map', true);
    await page.locator('.loc-zones .chip', { hasText: circleName }).waitFor({ timeout: 15000 });
    check('device tab lists the zone with its inside/outside state',
      /dentro|fuera|sin evaluar/.test(await page.locator('.loc-zones .chip', { hasText: circleName }).textContent()));
    await page.locator('.loc-map.leaflet-container').waitFor({ timeout: 15000 });
    await page.waitForTimeout(1500);
    const pts = await page.getByText(/\d+ (de \d+ )?puntos?/).first().textContent().catch(() => '');
    check(`device map shows the history (${(pts ?? '').trim()})`, /\d+/.test(pts ?? ''));
    check('last position shows its accuracy', await page.getByText(/Última posición: .*(precisión ±\d+ m|precisión no informada)/).isVisible().catch(() => false));
    check('Excel and CSV export buttons', await page.getByRole('button', { name: 'Excel' }).isVisible() && await page.getByRole('button', { name: 'CSV' }).isVisible());
    const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 20000 }), page.getByRole('button', { name: 'CSV' }).click()]);
    check('CSV download starts', /^recorrido-.*\.csv$/.test(dl.suggestedFilename()), dl.suggestedFilename());
    await page.screenshot({ path: `${shots}/8-dispositivo.png`, fullPage: true });
  }
  check('no JavaScript errors in the console pages', errors.length === 0, errors.join(' | '));
} catch (e) {
  check(`run completed (${String(e).split('\n')[0]})`, false);
  await page.screenshot({ path: `${shots}/error.png` }).catch(() => undefined);
} finally {
  try {
    for (const z of await zones()) if (z.name.startsWith('e2e-ui-') && z.name.endsWith(`-${suf}`)) await rest('DELETE', `/private/geofences/v1/zones/${z.id}`);
    for (const r of (await rest('GET', '/private/geofences/v1/recipients')).data ?? []) if (r.emails === mail) await rest('DELETE', `/private/geofences/v1/recipients/${r.id}`);
    if (settingsBefore) await rest('PUT', '/private/geofences/v1/settings', settingsBefore);
  } catch { /* best effort */ }
  await browser.close();
}
console.log(`===== RESULT: PASS=${pass} FAIL=${fail} =====  (screenshots: ${shots})`);
process.exit(fail ? 1 : 0);
