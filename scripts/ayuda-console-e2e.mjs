// Comprobación en un Chromium sin ventana del centro de ayuda de la consola: la entrada «Ayuda» al final del menú, la
// lista de manuales (PDF estáticos de /manuales con su índice), el buscador por título o tema, que cada PDF se sirve,
// el «?» contextual de las pantallas principales y los enlaces a la guía de problemas desde las tarjetas de error.
//
//   node scripts/ayuda-console-e2e.mjs --web http://localhost:8088
//   (contraseña del administrador: DALLYCONTROL_ADMIN_PASSWORD, por defecto "admin", la del stack de desarrollo)
//
// Usa el Playwright de scripts/shots ((cd scripts/shots && npm ci) una vez). Termina con código 0 solo si todo pasa.
import { chromium } from './shots/node_modules/playwright/index.mjs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const web = (arg('--web') ?? '').replace(/\/$/, '');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD ?? 'admin';
if (!web) { console.error('falta --web'); process.exit(2); }

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${String(detail).slice(0, 200)}]`}`); ok ? pass++ : fail++; };

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
page.setDefaultTimeout(30000);
try {
  await page.goto(`${web}/login`);
  await page.fill('input[type="text"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 60000 });

  const menu = (await page.locator('nav.nav .nav-item').allTextContents()).map((s) => s.trim());
  check('«Ayuda» es la última entrada del menú', menu[menu.length - 1] === 'Ayuda', menu.join(','));

  await page.click('nav.nav .nav-item:has-text("Ayuda")');
  await page.waitForSelector('[data-testid="help-manual"]');
  const titulos = await page.locator('[data-testid="help-manual"] .panel-title').allTextContents();
  check('lista los 7 manuales', titulos.length === 7, titulos.join(' | '));
  for (const t of ['Manual del administrador', 'Guía de enrolamiento', 'Arquitectura y requisitos técnicos', 'Solución de problemas',
    'Operación, soporte y nivel de servicio', 'Guía rápida del técnico de campo', 'Notas de versión']) {
    check(`manual «${t}»`, titulos.includes(t));
  }

  const index = await page.evaluate(async () => (await fetch('/manuales/indice.json')).json());
  for (const m of index.manuales) {
    const r = await page.evaluate(async (u) => { const x = await fetch(u); return { s: x.status, t: x.headers.get('content-type'), n: (await x.arrayBuffer()).byteLength }; }, `/manuales/${m.archivo}`);
    check(`${m.archivo} se sirve como PDF (${m.paginas} pág.)`, r.s === 200 && /pdf/.test(r.t ?? '') && r.n > 20000, JSON.stringify(r));
  }
  const abrir = await page.locator('[data-testid="help-manual"] a:has-text("Abrir")').first().getAttribute('href');
  const bajar = await page.locator('[data-testid="help-manual"] a:has-text("Descargar")').first().getAttribute('download');
  check('«Abrir» apunta al PDF y «Descargar» tiene nombre de archivo', /^\/manuales\/.+\.pdf$/.test(abrir ?? '') && /\.pdf$/.test(bajar ?? ''), `${abrir} ${bajar}`);

  await page.fill('[data-testid="help-search"]', 'quiosco');
  await page.waitForSelector('[data-testid="help-hits"] li');
  const hits = await page.locator('[data-testid="help-hits"] li a').evaluateAll((as) => as.map((a) => `${a.textContent} -> ${a.getAttribute('href')}`));
  check('buscar «quiosco» encuentra secciones con su página', hits.length >= 2 && hits.every((h) => /#page=\d+$/.test(h)), hits.join(' ; '));
  await page.fill('[data-testid="help-search"]', 'ultima falla de conexion');
  await page.waitForTimeout(400);
  const sinTilde = await page.locator('[data-testid="help-hits"] li').count();
  check('la búsqueda ignora tildes y mayúsculas', sinTilde >= 1);
  await page.fill('[data-testid="help-search"]', 'zzzz-no-existe');
  await page.waitForTimeout(400);
  check('búsqueda sin resultados: mensaje y ningún manual', (await page.locator('[data-testid="help-manual"]').count()) === 0
    && (await page.getByText('Ningún tema coincide').count()) === 1);

  // «?» contextual: cada pantalla abre la sección que la explica (una página concreta de un PDF).
  for (const [ruta, pdf] of [['/devices', '01-manual-administrador'], ['/groups', '01-manual-administrador'], ['/configs', '01-manual-administrador'],
    ['/apps', '01-manual-administrador'], ['/enroll', '02-guia-enrolamiento'], ['/audit', '01-manual-administrador']]) {
    await page.goto(`${web}${ruta}`);
    await page.waitForFunction(() => /#page=\d+$/.test(document.querySelector('a.help-ctx')?.getAttribute('href') ?? ''), null, { timeout: 20000 }).catch(() => {});
    const href = await page.locator('a.help-ctx').getAttribute('href').catch(() => null);
    check(`«?» de ${ruta} → ${pdf}`, new RegExp(`^/manuales/${pdf}\\.pdf#page=\\d+$`).test(href ?? ''), href);
  }
  await page.goto(`${web}/ayuda`);
  await page.waitForSelector('[data-testid="help-manual"]');
  check('la página Ayuda no muestra el «?»', (await page.locator('a.help-ctx').count()) === 0);

  // Enlaces a la guía de problemas desde donde se ven los errores.
  await page.goto(`${web}/enroll`);
  await page.waitForSelector('[data-testid="enroll-attempts"]');
  await page.waitForFunction(() => /#page=\d+$/.test(document.querySelector('[data-testid="enroll-attempts"] [data-testid="help-link"]')?.getAttribute('href') ?? ''), null, { timeout: 20000 }).catch(() => {});
  const motivo = await page.locator('[data-testid="enroll-attempts"] [data-testid="help-link"]').getAttribute('href');
  check('«Intentos de inscripción» enlaza a la guía de enrolamiento', /02-guia-enrolamiento\.pdf#page=\d+$/.test(motivo ?? ''), motivo);
  const id = await page.evaluate(async () => (await (await fetch('/rest/private/devices/search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pageNum: 1, pageSize: 1 }) })).json())?.data?.devices?.items?.[0]?.id);
  if (id) {
    await page.goto(`${web}/devices/${id}`);
    const link = page.locator('[data-testid="help-link"]:has-text("¿Cómo leerla?")');
    await link.waitFor({ timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(1500);
    const h = await link.getAttribute('href').catch(() => null);
    check('«Última falla de conexión» enlaza a la guía de problemas', /04-solucion-problemas\.pdf#page=\d+$/.test(h ?? ''), h);
  } else check('hay un dispositivo para probar la tarjeta de conexión', false, 'sin dispositivos');
} catch (e) {
  check('la prueba terminó sin excepción', false, e.message);
} finally {
  await browser.close();
}
console.log(`\nRESULT: PASS=${pass} FAIL=${fail}`);
process.exit(fail ? 1 : 0);
