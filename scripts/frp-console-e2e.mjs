// Console (web) checks of the factory-reset protection, lost mode and remote wipe screens against a running
// DallyControl, in a real browser (criteria MDM-03 and MDM-09):
//   - a policy's "Restablecimiento de fábrica" section: accounts by e-mail + numeric id with validation, the step-by-step
//     guide to get the id (People API), the company / folder defaults dialog, and the warning when the reset is
//     blocked and no account would apply;
//   - a device's "Protección contra restablecimiento y robo" panel: the FRP status line, the lost-mode dialog and the
//     remote-wipe dialog (two intents, options, type BORRAR).
//
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/frp-console-e2e.mjs --api http://localhost:8088 \
//       --device-id <numeric device id> --policy "<policy name>" [--shots dir] [--send-lost-mode]
//
// Nothing destructive is sent: the wipe dialog is filled and cancelled. --send-lost-mode really queues lost mode (and
// then turns it off). Uses the Playwright copy under scripts/shots (npm ci there once), or PLAYWRIGHT_MODULE.
// Exit 0 only if all checks pass.
import { mkdirSync } from 'node:fs';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? './shots/node_modules/playwright/index.mjs');

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const has = (k) => process.argv.includes(k);
const api = arg('--api');
const deviceId = arg('--device-id');
const policyName = arg('--policy');
const shots = arg('--shots', '/tmp/dallycontrol-frp-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !deviceId || !policyName || !password) {
  console.error('--api, --device-id, --policy and DALLYCONTROL_ADMIN_PASSWORD are required');
  process.exit(2);
}
mkdirSync(shots, { recursive: true });
const T = Number(arg('--timeout', '30000'));
const ACCOUNT_ID = '109876543210987654321';
const ACCOUNT_EMAIL = 'consola-pruebas@example.com';

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };
const visible = (loc) => loc.first().isVisible().catch(() => false);

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 1100 } });
const page = await ctx.newPage();
const dialogs = [];
page.on('dialog', (d) => { dialogs.push(d.message()); void d.accept(); });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices|tablero/, { timeout: T });
  check('console login', true);

  // --- policy editor: reset protection ---------------------------------------------------------------------------
  await page.goto(`${api}/configs`);
  const card = (name) => page.locator('.cfg-card').filter({ has: page.locator('.cfg-nm', { hasText: new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }) });
  await card(policyName).first().waitFor({ timeout: T });
  let editable = policyName;
  if (await visible(card(policyName).getByRole('button', { name: 'Ver', exact: true }))) {
    // A built-in policy is read-only: work on a copy of it (made once).
    editable = `${policyName} FRP e2e`;
    if (!(await visible(card(editable)))) {
      await card(policyName).getByRole('button', { name: 'Usar como plantilla' }).click();
      await page.getByRole('dialog').getByRole('textbox').fill(editable);
      await page.getByRole('dialog').getByRole('button', { name: 'Copiar', exact: true }).click();
      await card(editable).first().waitFor({ timeout: T });
    }
  }
  await card(editable).getByRole('button', { name: 'Editar', exact: true }).click({ timeout: T });
  const frp = page.getByTestId('reset-protection');
  await frp.waitFor({ timeout: T });
  await frp.scrollIntoViewIfNeeded();
  check('policy editor shows the reset-protection section', await visible(frp));
  const mode = frp.getByLabel('Cuentas de reactivación');
  check('three choices: company/folder accounts, own accounts, no protection', (await mode.locator('option').count()) === 3);

  // The company / folder defaults dialog.
  await mode.selectOption('inherit');
  await frp.getByRole('button', { name: 'Administrar cuentas de la empresa y carpetas' }).click();
  const dlg = page.getByRole('dialog', { name: 'Cuentas de reactivación' });
  await dlg.waitFor({ timeout: T });
  await dlg.getByLabel('Nivel').waitFor({ timeout: T });
  check('defaults dialog opens with the level picker (company, then folders)', /Toda la empresa/.test(await dlg.getByLabel('Nivel').innerText()));

  // The guide to get the id.
  await dlg.getByRole('button', { name: '¿Cómo obtengo el ID de la cuenta?' }).click();
  const guide = await dlg.locator('.frp-guide').innerText();
  check('guide explains the official method (People API people/me) step by step',
    /people\/me/.test(guide) && /Execute/.test(guide) && /resourceName/.test(guide) && (await dlg.locator('.frp-guide-steps li').count()) >= 4, guide.slice(0, 200));
  const href = await dlg.locator('.frp-guide a').first().getAttribute('href');
  check('guide links to Google\'s People API explorer', /developers\.google\.com\/people\/api\/rest\/v1\/people\/get/.test(href ?? ''), href ?? '');

  // Validation: an e-mail typed where the id goes; letters in the id.
  const already = await dlg.locator('.frp-list li', { hasText: ACCOUNT_ID }).count();
  if (already) { await dlg.locator('.frp-list li', { hasText: ACCOUNT_ID }).getByRole('button', { name: /Quitar/ }).click(); }
  await dlg.getByLabel('Correo de la cuenta de Google').fill(ACCOUNT_EMAIL);
  await dlg.getByLabel('ID de la cuenta').fill(ACCOUNT_EMAIL);
  await dlg.getByRole('button', { name: 'Agregar' }).click();
  let msg = await dlg.locator('.field-error').first().innerText().catch(() => '');
  check('an e-mail in the id field is refused with a clear message', /Eso es un correo/.test(msg), msg);
  await dlg.getByLabel('ID de la cuenta').fill('12ab');
  await dlg.getByRole('button', { name: 'Agregar' }).click();
  msg = await dlg.locator('.field-error').first().innerText().catch(() => '');
  check('an id that is not digits is refused', /solo de dígitos/.test(msg), msg);
  await dlg.getByLabel('ID de la cuenta').fill(ACCOUNT_ID);
  await dlg.getByRole('button', { name: 'Agregar' }).click();
  const added = dlg.locator('.frp-list li', { hasText: ACCOUNT_ID });
  await added.waitFor({ timeout: T });
  check('a valid account is listed by its e-mail with its id', new RegExp(ACCOUNT_EMAIL).test(await added.innerText()));
  await page.screenshot({ path: `${shots}/frp-defaults-dialog.png`, fullPage: true });
  await dlg.getByRole('button', { name: 'Guardar cuentas' }).click();
  await page.getByText('Cuentas guardadas').first().waitFor({ timeout: T });
  check('company accounts saved', true);
  await dlg.getByRole('button', { name: 'Cerrar' }).click();
  await frp.getByText(ACCOUNT_EMAIL).first().waitFor({ timeout: T });
  check('the policy shows which company accounts it inherits', await visible(frp.getByText(ACCOUNT_EMAIL)));

  // The warning: reset blocked and no account would apply.
  await page.getByLabel('Restablecimiento de fábrica', { exact: true }).selectOption('block');
  await mode.selectOption('off');
  const warn = frp.locator('.frp-warning');
  await warn.waitFor({ timeout: T });
  check('warning when the reset is blocked without reactivation accounts ("quedará libre")', /quedará libre/.test(await warn.innerText()));
  await page.screenshot({ path: `${shots}/frp-policy-warning.png`, fullPage: true });
  await mode.selectOption('own');
  check('own accounts: the same e-mail + id editor, empty', await visible(frp.getByLabel('ID de la cuenta')) && await visible(warn));
  await mode.selectOption('inherit');
  check('with the company accounts the warning goes away', !(await visible(frp.locator('.frp-warning'))));
  check('other ways around can be closed: safe mode, OEM unlock, USB debugging, wipe after wrong unlocks',
    await visible(frp.getByLabel('Bloquear el modo seguro')) && await visible(frp.getByLabel('Bloquear el desbloqueo del bootloader (OEM)'))
    && await visible(page.getByLabel('Depuración USB', { exact: true })) && await visible(frp.getByLabel('Intentos fallidos antes de borrar')));
  // Leave the policy as it was: the editor is closed without saving.
  await page.goto(`${api}/devices/${deviceId}`);

  // --- device: protection panel -----------------------------------------------------------------------------------
  const panel = page.getByTestId('protection-panel');
  await panel.waitFor({ timeout: T });
  await panel.scrollIntoViewIfNeeded();
  const status = (await page.getByTestId('frp-status').innerText()).trim();
  check('device shows its FRP status line ("FRP activa con N cuentas" / "FRP inactiva: motivo")',
    /^FRP (activa con \d+ cuentas?|inactiva: .+|pendiente: .+|sin confirmar: .+)/.test(status), status);
  const facts = await panel.locator('.prot-facts').innerText();
  check('panel shows the accounts that apply, the policy blocks and lost mode', /CUENTAS QUE LE CORRESPONDEN/i.test(facts)
    && /BLOQUEOS DE LA POLÍTICA/i.test(facts) && /MODO PERDIDO/i.test(facts), facts.slice(0, 200));
  await page.screenshot({ path: `${shots}/device-protection.png`, fullPage: true });
  check('the generic action grid no longer has a one-click factory reset',
    (await page.getByRole('button', { name: 'Restablecer de fábrica (borrar todo)' }).count()) === 0);

  // Remote wipe dialog.
  await panel.getByRole('button', { name: 'Borrado remoto (restablecer de fábrica)' }).click();
  const wipe = page.getByRole('dialog', { name: 'Borrado remoto' });
  await wipe.waitFor({ timeout: T });
  const send = wipe.getByRole('button', { name: 'Borrar el equipo' });
  check('two intents: "Equipo perdido o robado" and "Preparar para reasignar"',
    await visible(wipe.getByText('Equipo perdido o robado')) && await visible(wipe.getByText('Preparar para reasignar')));
  check('nothing can be sent before choosing an intent', await send.isDisabled());
  await wipe.getByText('Equipo perdido o robado').click();
  const keep = wipe.getByLabel('Conservar la protección de restablecimiento (FRP)');
  check('lost or stolen keeps the protection by default', await keep.isChecked());
  await wipe.getByText('Preparar para reasignar').click();
  check('prepare to reassign removes it by default', !(await keep.isChecked()));
  check('options: SD card, eSIM, silent, reason', await visible(wipe.getByLabel('Borrar también la tarjeta SD / almacenamiento externo'))
    && await visible(wipe.getByLabel('Borrar las eSIM (Android 9 o posterior)')) && await visible(wipe.getByLabel('Borrar sin aviso en pantalla (Android 10 o posterior)'))
    && await visible(wipe.getByLabel('Motivo que se muestra en el teléfono (Android 9 a 13)')));
  await wipe.getByLabel('Confirmación').fill('WIPE');
  check('typing anything but BORRAR does not enable the wipe', await send.isDisabled());
  await wipe.getByLabel('Confirmación').fill('BORRAR');
  check('typing BORRAR enables it', await send.isEnabled());
  await page.screenshot({ path: `${shots}/wipe-dialog.png`, fullPage: true });
  await wipe.getByRole('button', { name: 'Cancelar' }).click();
  check('cancel closes the dialog without sending', !(await visible(wipe)));

  // Lost mode dialog.
  const lostBtn = panel.getByRole('button', { name: /^Modo perdido/ });
  if (await lostBtn.isDisabled()) {
    check('lost mode is offered (agent with the capability)', false, 'button disabled: older agent');
  } else {
    await lostBtn.click();
    const lost = page.getByRole('dialog', { name: 'Modo perdido' });
    await lost.waitFor({ timeout: T });
    check('lost mode asks for message, contact phone, ring and the two automatic wipes',
      await visible(lost.getByLabel('Mensaje')) && await visible(lost.getByLabel('Teléfono de contacto'))
      && await visible(lost.getByLabel('Hacer sonar durante 1 minuto')) && await visible(lost.getByLabel('Intentos fallidos'))
      && await visible(lost.getByLabel('Días sin reportar')));
    await lost.getByLabel('Mensaje').fill('');
    await lost.getByRole('button', { name: /Activar modo perdido|Actualizar modo perdido/ }).click();
    msg = await lost.locator('.field-error').first().innerText().catch(() => '');
    check('lost mode needs a message or a phone', /mensaje o un teléfono/.test(msg), msg);
    const tag = Math.random().toString(36).slice(2, 8); // tells this run's order apart in the history
    await lost.getByLabel('Mensaje').fill(`Equipo de la empresa ${tag}. Si lo encontró, llame.`);
    await lost.getByLabel('Teléfono de contacto').fill('+57 300 1234567');
    await lost.getByLabel('Intentos fallidos').fill('2');
    await lost.getByRole('button', { name: /Activar modo perdido|Actualizar modo perdido/ }).click();
    msg = await lost.locator('.field-error').first().innerText().catch(() => '');
    check('fewer than 3 wrong unlocks is refused', /3 a 100/.test(msg), msg);
    await lost.getByLabel('Intentos fallidos').fill('8');
    await page.screenshot({ path: `${shots}/lost-mode-dialog.png`, fullPage: true });
    if (has('--send-lost-mode')) {
      await lost.getByRole('button', { name: /Activar modo perdido|Actualizar modo perdido/ }).click();
      const item = page.locator('.timeline-item', { hasText: tag }).first();
      await item.waitFor({ timeout: T });
      // The device has to run it before the panel can show it.
      await page.locator('.timeline-item.status-done', { hasText: tag }).first().waitFor({ timeout: 120000 }).catch(() => undefined);
      const text = await item.innerText();
      check('the device ran the lost-mode order', /EJECUTADO/i.test(text), text.slice(0, 200));
      check('history shows lost mode with what was ordered and who sent it',
        /Activar modo perdido/.test(text) && /borrar tras 8 intentos/.test(text) && /Enviada por admin/.test(text), text.slice(0, 300));
      await page.locator('.prot-lost').waitFor({ timeout: 90000 }).catch(() => undefined);
      check('panel shows "Modo perdido: activo desde…" once the device reports it', await visible(page.locator('.prot-lost')));
      await page.screenshot({ path: `${shots}/lost-mode-active.png`, fullPage: true });
      await panel.getByRole('button', { name: /^Modo perdido/ }).click();
      await page.getByRole('dialog', { name: 'Modo perdido' }).getByRole('button', { name: 'Desactivar modo perdido' }).click();
      await page.locator('.timeline-item.status-pending, .timeline-item.status-delivered', { hasText: 'Desactivar modo perdido' })
        .first().waitFor({ timeout: T }).catch(() => undefined);
      await page.locator('.prot-lost').waitFor({ state: 'detached', timeout: 120000 }).catch(() => undefined);
      check('lost mode can be turned off from the same dialog (panel back to "Inactivo")', !(await visible(page.locator('.prot-lost'))));
    } else {
      await lost.getByRole('button', { name: 'Cancelar' }).click();
    }
  }
  check('no JavaScript errors on the pages', errors.length === 0, errors.join(' | ').slice(0, 300));
} catch (e) {
  check('script ran to the end', false, String(e).slice(0, 400));
  await page.screenshot({ path: `${shots}/failure.png`, fullPage: true }).catch(() => undefined);
} finally {
  await browser.close();
}
console.log(`\n${pass}/${pass + fail} passed (screenshots in ${shots})`);
process.exit(fail ? 1 : 0);
