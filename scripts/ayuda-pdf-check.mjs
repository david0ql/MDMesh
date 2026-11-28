// Comprueba, contra el borde real (la imagen web con Caddy, no Vite), que los manuales del centro de ayuda se pueden
// ABRIR en el visor de PDF del navegador y DESCARGAR, con las cabeceras que pone el borde (CSP, nosniff, caché).
// Usa Google Chrome instalado en modo sin ventana «nuevo», que sí trae el visor de PDF (el Chromium de Playwright
// en modo clásico descarga los PDF en vez de mostrarlos).
//
//   node scripts/ayuda-pdf-check.mjs --web http://localhost:8088
//
// Termina con código 0 solo si todo pasa. Playwright de scripts/shots.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { mkdtempSync, statSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const web = (arg('--web') ?? '').replace(/\/$/, '');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD ?? 'admin';
if (!web) { console.error('falta --web'); process.exit(2); }
let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${String(detail).slice(0, 260)}]`}`); ok ? pass++ : fail++; };

const dir = mkdtempSync(path.join(tmpdir(), 'ayuda-pdf-'));
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--headless=new'] });
const ctx = await browser.newContext({ acceptDownloads: true });
const page = await ctx.newPage();
page.setDefaultTimeout(30000);
try {
  await page.goto(`${web}/login`);
  await page.fill('input[type="text"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 60000 });
  const index = await page.evaluate(async () => (await fetch('/manuales/indice.json')).json());
  check('el borde sirve el índice de manuales', Array.isArray(index.manuales) && index.manuales.length >= 7, JSON.stringify(index).slice(0, 80));

  for (const m of index.manuales) {
    const url = `${web}/manuales/${m.archivo}`;
    const h = await page.evaluate(async (u) => { const r = await fetch(u); return { s: r.status, t: r.headers.get('content-type'), csp: r.headers.get('content-security-policy'), xcto: r.headers.get('x-content-type-options'), cd: r.headers.get('content-disposition'), n: (await r.arrayBuffer()).byteLength }; }, url);
    check(`${m.archivo}: 200, application/pdf, sin «sandbox» en la CSP ni descarga forzada`,
      h.s === 200 && /^application\/pdf/.test(h.t ?? '') && !/sandbox/.test(h.csp ?? '') && !/attachment/.test(h.cd ?? ''), JSON.stringify(h));

    // ABRIR: la navegación termina en el visor de PDF del navegador (un <embed> de tipo PDF), sin errores de CSP.
    const visor = await ctx.newPage();
    const errores = [];
    visor.on('console', (msg) => { if (msg.type() === 'error') errores.push(msg.text()); });
    await visor.goto(`${url}#page=2`, { waitUntil: 'load' }).catch((e) => errores.push(e.message));
    await visor.waitForTimeout(1500);
    // El visor integrado de Chrome es una extensión propia (mhjfbmdg…) que se carga en un marco dentro de la página.
    const marcos = visor.frames().map((f) => f.url());
    const enVisor = marcos.some((u) => u.startsWith('chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai'));
    check(`${m.archivo}: se abre en el visor de PDF`, enVisor && !errores.some((e) => /Content Security Policy|Refused/i.test(e)), `marcos=${marcos.join(',')} ${errores.join(' | ')}`);
    await visor.close();
  }

  // DESCARGAR: el botón de la página Ayuda entrega el archivo completo con su nombre.
  await page.goto(`${web}/ayuda`);
  await page.waitForSelector('[data-testid="help-manual"]');
  const [descarga] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('[data-testid="help-manual"] a:has-text("Descargar")').first().click(),
  ]);
  const destino = path.join(dir, descarga.suggestedFilename());
  await descarga.saveAs(destino);
  const esperado = index.manuales[0];
  check('«Descargar» entrega el PDF completo con nombre', /\.pdf$/.test(descarga.suggestedFilename()) && Math.abs(statSync(destino).size - esperado.kb * 1024) < 2048,
    `${descarga.suggestedFilename()} ${statSync(destino).size} bytes, índice ${esperado.kb} KB`);
} catch (e) {
  check('la prueba terminó sin excepción', false, e.message);
} finally {
  await browser.close();
  rmSync(dir, { recursive: true, force: true });
}
console.log(`\nRESULT: PASS=${pass} FAIL=${fail}`);
process.exit(fail ? 1 : 0);
