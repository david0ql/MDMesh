// Console + server checks of the kiosk exit password (docs/KIOSK-HARDENING.md), in headless Chromium against a
// running DallyControl:
//   - the server generates an 8-digit exit password for a kiosk policy saved without one, and refuses a too short one;
//   - the policy editor does not save a kiosk without password, shows why, and "Generar" fills one that then saves.
//
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/kiosk-console-e2e.mjs --api http://localhost:8088
//
// Playwright is not a dependency of the repo: `cd scripts/shots && npm ci` once (or point PLAYWRIGHT at another copy's
// playwright/index.mjs). Creates one `e2e-kiosk-*` policy (a copy of the first one) and deletes it. Exit 0 = all passed.
import { pathToFileURL } from 'node:url';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const pw = process.env.PLAYWRIGHT || resolve(here, 'shots/node_modules/playwright/index.mjs');
const { chromium } = await import(pathToFileURL(pw).href);

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api', 'http://localhost:8088');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD || 'admin';

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 1000 } })).newPage();
const rest = async (method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined });
  return r.json();
}, [method, path, body]);
let cfgId;

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices|tablero|alerts/, { timeout: 20000 });
  check('inicio de sesión en la consola', true);

  // A policy of our own to play with: a copy of the first one.
  const all = (await rest('GET', '/private/configurations/search')).data ?? [];
  const name = `e2e-kiosk-${Math.floor(Math.random() * 1e6)}`;
  await rest('PUT', '/private/configurations/copy', { id: all[0].id, name });
  const mine = ((await rest('GET', '/private/configurations/search')).data ?? []).find((c) => c.name === name);
  cfgId = mine?.id;
  check('política de prueba creada', !!cfgId, JSON.stringify(mine ?? null).slice(0, 120));
  const full = async () => (await rest('GET', `/private/configurations/${cfgId}`)).data;

  // --- server ---------------------------------------------------------------------------------------------------
  let cfg = await full();
  let r = await rest('PUT', '/private/configurations', { ...cfg, kioskMode: true, password: '' });
  check('servidor: quiosco guardado sin contraseña → genera una de 8 dígitos', r.status === 'OK' && /^\d{8}$/.test(r.data?.password ?? ''), `${r.status} ${r.data?.password}`);
  cfg = await full();
  r = await rest('PUT', '/private/configurations', { ...cfg, kioskMode: true, password: '12' });
  check('servidor: contraseña de salida demasiado corta → rechazada', r.status === 'ERROR' && /kiosk\.password/.test(r.message ?? ''), JSON.stringify(r).slice(0, 120));
  r = await rest('PUT', '/private/configurations', { ...cfg, kioskMode: false, password: '' });
  check('servidor: sin quiosco no se exige contraseña', r.status === 'OK' && !r.data?.password, `${r.status} ${r.data?.password}`);

  // --- console --------------------------------------------------------------------------------------------------
  await page.goto(`${api}/configs`);
  const card = page.locator('.cfg-card', { hasText: name });
  await card.waitFor({ timeout: 20000 });
  await card.getByRole('button', { name: 'Editar' }).click();
  const field = (label) => page.locator('.cfg-field', { has: page.locator(`label:text-is("${label}")`) }).first();
  const save = page.getByRole('button', { name: 'Guardar', exact: true });
  await save.waitFor({ timeout: 20000 });
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some((b) => b.textContent?.trim() === 'Guardar' && !b.disabled), null, { timeout: 30000 });
  await field('Modo quiosco').locator('input[type="checkbox"]').check();
  const pwInput = page.getByTestId('kiosk-exit-password');
  check('consola: el campo «Contraseña de salida del quiosco» está a la vista', await pwInput.isVisible());
  const helpText = await field('Contraseña de salida del quiosco').locator('.cfg-field-help').textContent();
  check('consola: el texto explica que es obligatoria y el límite de intentos', /Obligatoria/.test(helpText ?? '') && /intentos/.test(helpText ?? ''), helpText ?? '');
  const notif = await field('Notificaciones').locator('.cfg-field-help').textContent().catch(() => '');
  check('consola: «Notificaciones» explica el riesgo en Android 16+', /RIESGO/.test(notif ?? '') && /Android 16/.test(notif ?? ''), (notif ?? '').slice(0, 80));
  const debugSel = page.getByTestId('policy-debugging');
  check('consola: opción «Depuración USB» en el panel de la política', await debugSel.count() > 0);
  await pwInput.fill('');
  await save.click();
  const toast = page.locator('.toast.err', { hasText: 'Falta la contraseña de salida del quiosco' });
  check('consola: no guarda un quiosco sin contraseña y dice por qué', await toast.waitFor({ timeout: 8000 }).then(() => true, () => false));
  check('…y la política sigue sin quiosco en el servidor', (await full()).kioskMode === false);
  await pwInput.fill('123');
  check('consola: avisa «Mínimo 4 caracteres»', await page.locator('.field-error', { hasText: 'Mínimo 4 caracteres' }).isVisible());
  await save.click();
  check('consola: tampoco guarda una contraseña de 3 caracteres', (await full()).kioskMode === false);
  await field('Contraseña de salida del quiosco').getByRole('button', { name: 'Generar' }).click();
  const generated = await pwInput.inputValue();
  check('consola: «Generar» pone una contraseña de 8 dígitos y la muestra', /^\d{8}$/.test(generated) && (await pwInput.getAttribute('type')) === 'text', generated);
  await save.click();
  // A kiosk change asks for confirmation when devices may re-apply it.
  const confirm = page.getByRole('button', { name: 'Guardar y aplicar' });
  await confirm.waitFor({ timeout: 4000 }).then(() => confirm.click(), () => {});
  let saved = null;
  for (let i = 0; i < 20 && !saved; i++) {
    const c = await full();
    if (c.kioskMode === true) saved = c.password; else await page.waitForTimeout(500);
  }
  check('consola: con la contraseña generada el quiosco se guarda', saved === generated, `${saved} vs ${generated}`);

} catch (e) {
  check('sin errores inesperados', false, String(e).slice(0, 300));
} finally {
  if (cfgId) await rest('DELETE', `/private/configurations/${cfgId}`).catch(() => {});
  await browser.close();
}
console.log(`RESULT: PASS=${pass} FAIL=${fail}`);
process.exit(fail ? 1 : 0);
