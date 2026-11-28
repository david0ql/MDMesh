// Console (web) check of the connection diagnostics, in a real browser (headless Chromium):
// the device page shows the card "Última falla de conexión" with the exact cause the agent reported, and the device
// list / header tell "En reposo" from "Sin conexión".
//
//   (cd scripts/shots && npm ci)     # once: the Playwright copy the console scripts share
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/consola-conexion-e2e.mjs --api http://localhost:8088 \
//       --device <device number> [--expect "Certificado TLS|WebSocket rechazado"] [--issuer "FortiGate"]
//       [--resting <device number shown as En reposo>] [--shots dir]
//
// Read-only: it changes nothing on the server. Exit 0 only if all checks pass.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const device = arg('--device');
const expect = arg('--expect', '');
const issuer = arg('--issuer', '');
const resting = arg('--resting', '');
const shots = arg('--shots', new URL('./shots/node_modules/.capturas-conexion', import.meta.url).pathname);
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !device || !password) { console.error('--api, --device and DALLYCONTROL_ADMIN_PASSWORD are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  [${detail}]` : ''}`); ok ? pass++ : fail++; };

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1360, height: 1100 } })).newPage();
try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices|tablero/, { timeout: 60000 });
  check('inicio de sesión en la consola', true);

  await page.goto(`${api}/devices/${device}`);
  const card = page.locator('.detail-rail div', { has: page.locator('.grp', { hasText: 'Última falla de conexión' }) }).last();
  await card.waitFor({ timeout: 60000 });
  check('el detalle del dispositivo muestra «Última falla de conexión»', true);
  // The card fills in once the connection poll answers.
  await page.waitForFunction(() => /Falla|Último corte|Sin fallas reportadas/.test(document.querySelector('.detail-rail')?.textContent ?? ''), null, { timeout: 60000 });
  const text = (await card.innerText()).replace(/\s+/g, ' ');
  if (expect) check(`motivo exacto visible (${expect})`, new RegExp(expect).test(text), text.slice(0, 260));
  if (issuer) check(`emisor del certificado visible (${issuer})`, text.includes(issuer), text.slice(0, 260));
  check('muestra la red del equipo y cuándo ocurrió', /Red del equipo/.test(text) && /Cuándo/.test(text));
  const status = (await page.locator('.detail-rail .top .st').innerText()).trim();
  check('estado del equipo en el encabezado', /En línea|En reposo|Sin conexión/.test(status), status);
  await page.screenshot({ path: `${shots}/detalle-falla-conexion.png`, fullPage: true });

  await page.goto(`${api}/devices`);
  await page.waitForSelector('.dev-row, .dev', { timeout: 60000 });
  if (resting) {
    await page.waitForFunction(() => document.body.innerText.includes('En reposo'), null, { timeout: 90000 }).catch(() => undefined);
    const body = await page.locator('body').innerText();
    check('la lista muestra «En reposo» para el equipo en silencio con causa conocida', body.includes('En reposo'));
    await page.goto(`${api}/devices/${resting}`);
    await page.waitForFunction(() => /En reposo/.test(document.querySelector('.detail-rail .top')?.textContent ?? ''), null, { timeout: 90000 }).catch(() => undefined);
    const st = (await page.locator('.detail-rail .top .st').innerText()).trim();
    check('el detalle dice «En reposo» con su motivo', /^En reposo · /.test(st), st);
    await page.screenshot({ path: `${shots}/detalle-en-reposo.png`, fullPage: true });
    await page.goto(`${api}/devices`);
    await page.waitForSelector('.dev-row, .dev', { timeout: 60000 });
  }
  await page.screenshot({ path: `${shots}/lista-dispositivos.png`, fullPage: true });
  check('la lista de dispositivos carga', true);
} catch (e) {
  check('excepción', false, String(e).slice(0, 300));
  await page.screenshot({ path: `${shots}/error.png`, fullPage: true }).catch(() => undefined);
} finally {
  await browser.close();
}
console.log(`\nRESULT: PASS=${pass} FAIL=${fail}  (capturas en ${shots})`);
process.exit(fail ? 1 : 0);
