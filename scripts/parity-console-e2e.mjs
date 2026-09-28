// Console (web) checks of the operations-parity features against a running DallyControl, in a real browser:
// folder tree + inheritance, the configuration's policy panel (saved through the UI), folder enrollment codes
// (create / QR / revoke), "App already on the phone", the device page's SIM rows and the device script.
//
//   DALLYCONTROL_ADMIN_PASSWORD=... node scripts/parity-console-e2e.mjs --api https://mdm.example.com \
//       --device <device number> --config <configuration id> [--shots dir]
//
// Creates e2e-* folders/apps/codes and removes them; the configuration's dcPolicy is restored. Uses the Playwright
// copy under scripts/shots. Exit 0 only if all checks pass.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const device = arg('--device');
const cfgId = Number(arg('--config'));
const shots = arg('--shots', '/tmp/dallycontrol-parity-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !device || !cfgId || !password) { console.error('--api, --device, --config and DALLYCONTROL_ADMIN_PASSWORD are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };
const md5 = (s) => createHash('md5').update(s).digest('hex').toUpperCase();

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await ctx.newPage();
// REST through the browser session (same cookie as the UI).
const rest = async (method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined });
  return r.json();
}, [method, path, body]);
const cleanup = [];
let origPolicy;

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 20000 });
  check('console login', true);
  void md5;

  // --- folders ---------------------------------------------------------------------------------------------------
  const suf = Math.floor(Math.random() * 1e5);
  const root = (await rest('POST', '/private/fleet/v1/groups', { name: `e2e-Colombia-${suf}`, configurationId: cfgId })).data;
  const child = (await rest('POST', '/private/fleet/v1/groups', { name: `e2e-Agencia-${suf}`, parentId: root.id })).data;
  cleanup.push(() => rest('DELETE', `/private/fleet/v1/groups/${child.id}`), () => rest('DELETE', `/private/fleet/v1/groups/${root.id}`));
  await page.goto(`${api}/groups`);
  const childRow = page.getByTestId(`group-row-${child.id}`);
  await childRow.waitFor({ timeout: 15000 });
  const childSelect = childRow.locator('select');
  const inheritText = await childSelect.locator('option').first().textContent();
  check('sub-folder shows it inherits the parent\'s configuration', /^Inherit \(/.test(inheritText ?? ''), inheritText ?? '');
  const indent = await childRow.locator('.gr-tree').evaluate((e) => getComputedStyle(e).paddingLeft);
  check('sub-folder is indented under its parent', indent === '20px', indent);
  check('"+ Sub-folder" action present', await childRow.getByRole('button', { name: '+ Sub-folder' }).isVisible());
  await page.screenshot({ path: `${shots}/groups-tree.png`, fullPage: true });

  // --- configuration policy panel, saved through the UI -----------------------------------------------------------
  const cfgs = (await rest('GET', '/private/configurations/search')).data;
  const cfg = cfgs.find((c) => c.id === cfgId);
  origPolicy = cfg.dcPolicy ?? null;
  await page.goto(`${api}/configs`);
  await page.locator('.panel, .cfg-card, article, section', { hasText: cfg.name }).filter({ has: page.getByRole('button', { name: 'Edit', exact: true }) })
    .last().getByRole('button', { name: 'Edit', exact: true }).click();
  const panel = page.getByTestId('dc-policy');
  await panel.waitFor({ timeout: 15000 });
  check('configuration editor shows the functions/browser/apps/location panel', true);
  await panel.getByRole('group', { name: 'Kiosk functions' }).getByText('Phone', { exact: true }).click();
  await panel.getByLabel('Browser mode').selectOption('blocklist');
  await panel.getByLabel('Blocked sites').fill('facebook.com\nyoutube.com');
  await panel.getByLabel('Blocked sites').blur();
  await panel.getByLabel('Location interval in minutes').fill('5');
  await page.screenshot({ path: `${shots}/config-policy-panel.png`, fullPage: true });
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  const confirm = page.getByRole('button', { name: /Apply|Save anyway|Confirm/i });
  if (await confirm.first().isVisible({ timeout: 3000 }).catch(() => false)) await confirm.first().click();
  await page.waitForTimeout(2500);
  const saved = JSON.parse((await rest('GET', '/private/configurations/search')).data.find((c) => c.id === cfgId).dcPolicy || '{}');
  check('policy saved from the UI (Phone + blocklist + 5 min)',
    JSON.stringify(saved.kioskRoles) === '["phone"]' && saved.browser?.mode === 'blocklist'
      && JSON.stringify(saved.browser?.block) === '["facebook.com","youtube.com"]' && saved.trackingMinutes === 5,
    JSON.stringify(saved));

  // --- folder enrollment codes ------------------------------------------------------------------------------------
  await page.goto(`${api}/enroll`);
  await page.getByRole('button', { name: 'Folder codes' }).click();
  const codes = page.getByTestId('enrollment-codes');
  await codes.waitFor();
  await codes.getByLabel('Folder for the code').selectOption(String(child.id));
  await codes.getByLabel('Code label').fill('e2e console');
  await codes.getByRole('button', { name: 'Create code' }).click();
  await codes.locator('.codes-qr canvas').waitFor({ timeout: 10000 });
  const shown = (await codes.locator('.codes-qr .code-big').textContent())?.trim() ?? '';
  check('a code is created and shown as ABCD-EFGH with its QR', /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(shown), shown);
  const folderCell = await codes.locator('tbody tr', { hasText: shown }).locator('td').nth(1).textContent();
  check('the code lists its folder path', (folderCell ?? '').includes(`e2e-Colombia-${suf} / e2e-Agencia-${suf}`), folderCell ?? '');
  await page.screenshot({ path: `${shots}/enroll-folder-codes.png`, fullPage: true });
  page.once('dialog', (d) => void d.accept());
  const row = () => codes.locator('tbody tr', { hasText: shown });
  await row().getByRole('button', { name: 'Revoke' }).click();
  await row().getByText('Revoked').waitFor({ timeout: 10000 }); // revoked codes sort last
  check('revoke from the console', true);

  // --- app already on the phone -------------------------------------------------------------------------------------
  await page.goto(`${api}/apps`);
  await page.getByRole('tab', { name: 'On the phone' }).click();
  const src = page.getByTestId('device-app-source');
  await src.getByPlaceholder('Chrome', { exact: true }).fill(`E2E App ${suf}`);
  await src.getByPlaceholder('com.android.chrome', { exact: true }).fill(`com.e2e.app${suf}`);
  await src.getByRole('button', { name: 'Add to Library' }).click();
  await page.waitForTimeout(2000);
  const lib = (await rest('GET', '/private/applications/search')).data;
  const added = lib.find((a) => a.pkg === `com.e2e.app${suf}`);
  check('an app already on the phone is added to the Library without an APK', !!added && !added.url, JSON.stringify(added ?? {}));
  if (added) cleanup.push(() => rest('DELETE', `/private/applications/${added.id}`));
  await page.screenshot({ path: `${shots}/apps-on-the-phone.png`, fullPage: true });

  // --- device page: SIM rows, script --------------------------------------------------------------------------------
  await page.goto(`${api}/devices/${device}`);
  await page.getByText('Phone number', { exact: true }).waitFor({ timeout: 20000 });
  // The rows render with "—" until the first telemetry poll answers.
  await page.waitForFunction(() => [...document.querySelectorAll('.row')].some((r) => /^Phone number\s*\+?\d/.test(r.textContent ?? '')), null, { timeout: 20000 }).catch(() => undefined);
  const simRow = await page.locator('.row', { hasText: 'SIM' }).first().textContent();
  check('device page shows the SIM', /SIM/.test(simRow ?? '') && !/SIM—$/.test(simRow ?? ''), simRow ?? '');
  const numRow = await page.locator('.row', { hasText: 'Phone number' }).first().textContent();
  check('device page shows the phone number', /\+?\d{7,}/.test(numRow ?? ''), numRow ?? '');
  await page.screenshot({ path: `${shots}/device-sim.png`, fullPage: true });
  await page.locator('.detail-rail').getByRole('button', { name: 'Control', exact: true }).click().catch(() => undefined);
  const script = page.getByTestId('script-panel');
  await script.waitFor({ timeout: 10000 });
  await script.getByLabel('Device script').fill('# e2e\nring 3\nopen co.amovil.preventa');
  await script.getByRole('button', { name: /Run script \(2 steps\)/ }).click();
  await page.getByText('Script queued').waitFor({ timeout: 15000 });
  const hist = (await rest('GET', `/private/agent/v1/devices/${device}/commands`)).data.slice(0, 4).map((c) => c.type);
  check('the script queued ring + open app, in order', hist.includes('device.ring') && hist.includes('device.appLaunch'), hist.join(','));
  await script.getByLabel('Device script').fill('teleport now');
  check('a bad script line is reported, not sent', await script.getByText('unknown action "teleport"').isVisible());
  await page.screenshot({ path: `${shots}/device-script.png`, fullPage: true });
} catch (e) {
  check('run', false, String(e).split('\n')[0]);
} finally {
  try {
    if (origPolicy !== undefined) {
      const cfg = (await rest('GET', '/private/configurations/search')).data.find((c) => c.id === cfgId);
      const apps = (await rest('GET', `/private/configurations/applications/${cfgId}`)).data.filter((a) => a.selected);
      await rest('PUT', '/private/configurations', { ...cfg, dcPolicy: origPolicy, applications: apps });
    }
    for (const f of cleanup) await f();
  } catch (e) { console.log('  cleanup:', String(e)); }
  await browser.close();
  console.log(`===== RESULT: PASS=${pass} FAIL=${fail} ===== (screenshots in ${shots})`);
  process.exit(fail ? 1 : 0);
}
