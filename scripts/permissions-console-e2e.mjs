// Console (web) checks of app permissions in a real headless browser (Chromium), against a running DallyControl:
//   1. the policy editor: «Permisos de apps» offers only options the agent applies (no dead ones), and «Permisos por
//      app» fixes Conceder / Denegar / Preguntar per permission, the "all the app declares" preset and special accesses;
//   2. what is saved is what the phone receives (the `permissions` section of its config.apply);
//   3. the device's «Permisos» tab: effective default policy, the real state per app, the "this phone does not let the
//      administrator grant sensor permissions" warning (Android 12+), "requires a tap of the user" for special accesses
//      with «Abrir en el teléfono», per-device rules on top of the policy, and the CSV export.
//
//   (cd scripts/shots && npm install && npx playwright install chromium)        # once
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/permissions-console-e2e.mjs --api http://localhost:8088 [--shots DIR]
//
// The phone is scripted (enrolled and answered over the agent API), so no emulator is needed; scripts/permissions-e2e.py
// is the test against a real agent. Creates a policy and a device named e2e-permisos-* and removes them at the end.
// Uses the Playwright copy under scripts/shots. Exit 0 only if all pass.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api', 'http://localhost:8088');
const shots = arg('--shots', '');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD ?? 'admin';
const PKG = 'com.dallycontrol.permtest';
const OTHER = 'com.acme.ventas';
const suf = Math.floor(Math.random() * 1e6);
const policyName = `e2e-permisos-${suf}`;
if (shots) mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${String(detail).slice(0, 300)}]`}`);
  ok ? pass++ : fail++;
  return ok;
};
const md5 = (s) => createHash('md5').update(s).digest('hex').toUpperCase();

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1400, height: 1000 }, acceptDownloads: true, locale: 'es-CO' });
const page = await ctx.newPage();
page.on('dialog', (d) => d.accept());
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
const rest = async (method, p, body, headers = {}) => page.evaluate(async ([m, q, b, h]) => {
  const r = await fetch(`/rest${q}`, { method: m, credentials: 'include',
    headers: { ...(b ? { 'Content-Type': 'application/json' } : {}), ...h }, body: b ? JSON.stringify(b) : undefined });
  return r.json().catch(() => ({ status: `HTTP ${r.status}` }));
}, [method, p, body, headers]);
const shot = async (name) => { if (shots) await page.screenshot({ path: path.join(shots, `${name}.png`), fullPage: true }).catch(() => undefined); };
const waitToast = async (re, timeout = 30000) => {
  await page.waitForFunction((src) => new RegExp(src).test(document.querySelector('.toast-wrap')?.textContent ?? ''), re.source, { timeout });
  return page.locator('.toast-wrap').innerText().catch(() => '');
};

const caps = { policy: ['wifi'], device: ['lock', 'configApply', 'permissionScan', 'permissionScreen'], appManagement: ['silentInstall'], remoteControl: { tier: 'none' } };
const P = 'android.permission.';
/** The report a real agent sends (see PermissionReport in the agent's Permissions.kt). */
const report = ({ canGrantSensors, cameraGranted, allFiles }) => ({
  policy: 'prompt', deviceOwner: true, sdkInt: 34, canGrantSensors, lastOutcome: canGrantSensors
    ? 'applied' : 'applied (este equipo no deja al administrador conceder permisos de sensores (ubicación, cámara, micrófono): com.dallycontrol.permtest)',
  at: Date.now(),
  apps: [
    { packageName: PKG, installed: true, label: 'DallyControl Permisos (prueba)', targetSdk: 34,
      permissions: [
        { group: 'locationFine', name: P + 'ACCESS_FINE_LOCATION', granted: canGrantSensors, fixed: canGrantSensors ? 'granted' : 'none', wanted: 'grant', note: canGrantSensors ? null : 'sensorsBlocked' },
        { group: 'locationCoarse', name: P + 'ACCESS_COARSE_LOCATION', granted: canGrantSensors, fixed: canGrantSensors ? 'granted' : 'none', wanted: 'grant', note: canGrantSensors ? null : 'sensorsBlocked' },
        { group: 'camera', name: P + 'CAMERA', granted: cameraGranted, fixed: cameraGranted ? 'granted' : 'denied', wanted: cameraGranted ? 'grant' : 'deny' },
        { group: 'storage', name: P + 'READ_MEDIA_IMAGES', granted: true, fixed: 'granted', wanted: 'grant' },
        { group: 'storage', name: P + 'READ_MEDIA_VIDEO', granted: true, fixed: 'granted', wanted: 'grant' },
        { group: 'contacts', name: P + 'READ_CONTACTS', granted: true, fixed: 'granted', wanted: 'grant' },
      ],
      special: [
        { key: 'allFiles', state: allFiles, wanted: true },
        { key: 'overlay', state: 'needsUser', wanted: false },
        { key: 'battery', state: 'needsUser', wanted: false, managed: null },
        { key: 'unrestrictedData', state: 'unknown', wanted: false },
        { key: 'accessibility', state: 'notDeclared', wanted: false },
      ] },
    { packageName: 'com.example.gone', installed: false },
  ],
});
const checkin = (phone, permissions, results) => rest('POST', '/public/agent/v1/checkin', {
  deviceId: phone.id, capabilities: caps, ...(results ? { results } : {}),
  state: { battery: 80, charging: false, locked: false, kioskActive: false, androidRelease: '14', agentVersion: '0.9.9' },
  telemetry: { dynamic: { batteryPct: 80, ...(permissions ? { permissions } : {}) } },
}, { Authorization: `Bearer ${phone.secret}` });

let policyId = null;
let phone = null;
try {
  // --- session (the same login call the console makes; the SPA reads its copy of the user from localStorage) ----------
  await page.goto(`${api}/login`);
  const login = await rest('POST', '/public/auth/login', { login: 'admin', password: md5(password) });
  if (!check('console session opened', login.status === 'OK', JSON.stringify(login))) throw new Error('login failed');
  await page.evaluate((u) => localStorage.setItem('hmdm.admin.user', JSON.stringify(u)), login.data);

  // A policy of our own (copied from the first one) and a scripted phone enrolled into it.
  const all = (await rest('GET', '/private/configurations/search')).data ?? [];
  const base = all[0];
  await rest('PUT', '/private/configurations/copy', { id: base.id, name: policyName, description: 'e2e permisos' });
  policyId = ((await rest('GET', '/private/configurations/search')).data ?? []).find((c) => c.name === policyName)?.id;
  if (!check('test policy created', !!policyId)) throw new Error('no policy');
  const token = (await rest('POST', '/private/agent/v1/token', { configurationId: policyId })).data.token;
  const enrolled = await rest('POST', '/public/agent/v1/enroll', { enrollToken: token,
    agent: { version: '0.9.9', package: 'com.dallycontrol.agent' }, device: { androidSdkInt: 34, isDeviceOwner: true }, capabilities: caps });
  phone = { id: enrolled.data.deviceId, secret: enrolled.data.deviceSecret };
  check('scripted phone enrolled into the policy', !!phone.id, JSON.stringify(enrolled).slice(0, 200));
  // The phone takes (and answers) what its enrollment queued, so the next config.apply is the one for our changes.
  const answer = async (extra) => {
    const got = (await checkin(phone)).data?.commands ?? [];
    if (got.length) {
      await checkin(phone, extra, got.map((c) => ({ commandId: c.commandId, status: 'done',
        detail: c.type === 'config.apply' ? JSON.stringify({ revision: c.payload?.revision, outcomes: { permissions: 'applied' } }) : 'ok' })));
    }
    return got;
  };
  await answer();

  // --- 1. the policy editor ------------------------------------------------------------------------------------------
  console.log('\n== 1. policy editor: default policy and per-app permissions');
  await page.goto(`${api}/configs`);
  const card = page.locator('.cfg-card', { hasText: policyName });
  await card.waitFor({ timeout: 20000 });
  await card.getByRole('button', { name: 'Editar' }).click();
  const panel = page.getByTestId('policy-permissions');
  await panel.waitFor({ timeout: 20000 });
  check('the editor has the «Permisos por app» section', await panel.isVisible());

  const option = page.locator('.cfg-field', { hasText: 'Permisos de apps' }).locator('select').first();
  const options = await option.locator('option').allInnerTexts();
  check('«Permisos de apps» offers Conceder / Preguntar / Denegar and the two location variants',
    ['Conceder todo automáticamente', 'Preguntar al usuario', 'Denegar todo'].every((o) => options.includes(o))
      && options.filter((o) => o !== '—').length === 5, options.join(' | '));
  check('…and is marked as applied by the agent', await page.locator('.cfg-field', { hasText: 'Permisos de apps' }).first().locator('.chip-enforced').count() > 0);
  await option.selectOption('ASKALL');
  await page.locator('.cfg-adv-toggle').click();
  const body = await page.locator('body').innerText();
  check('the dead options are gone («Modo permisivo», «Bloquear permiso de ubicación»)', !/Modo permisivo/.test(body) && !/Bloquear permiso de ubicación/.test(body));
  check('the section explains the default policy in force', /Preguntar: el usuario decide/.test(await panel.innerText()), (await panel.innerText()).slice(0, 300));

  // A package typed by hand (an app that is not in the policy's list yet).
  await panel.getByLabel('Agregar una app por nombre de paquete').fill('not a package');
  check('an invalid package name cannot be added', await panel.getByRole('button', { name: 'Agregar app' }).isDisabled());
  await panel.getByLabel('Agregar una app por nombre de paquete').fill(PKG);
  await panel.getByRole('button', { name: 'Agregar app' }).click();
  const app = panel.getByTestId(`perm-app-${PKG}`);
  await app.locator('.perm-app-body').waitFor({ timeout: 5000 });
  const rows = await app.locator('.perm-table tbody tr td:first-child').allInnerTexts();
  const wantedRows = ['Ubicación precisa', 'Ubicación aproximada', 'Ubicación en segundo plano', 'Cámara', 'Micrófono', 'Almacenamiento / fotos y videos',
    'Contactos', 'Teléfono', 'SMS', 'Notificaciones', 'Bluetooth / dispositivos cercanos', 'Actividad física y sensores corporales', 'Calendario'];
  check('every permission of the criterion has its row (13)', wantedRows.every((r) => rows.some((x) => x.startsWith(r))), rows.join(' | '));
  const specials = await app.locator('.perm-special-row label').allInnerTexts();
  check('the 8 special accesses are listed with what Android allows',
    specials.length === 8 && specials.some((s) => /Acceso a todos los archivos/.test(s) && /Requiere un toque del usuario/.test(s)), specials.join(' | ').slice(0, 300));
  await app.getByLabel(`Preajuste de ${PKG}`).selectOption('grant');
  await app.getByLabel(`Cámara de ${PKG}`).selectOption('deny');
  await app.getByLabel(`Micrófono de ${PKG}`).selectOption('prompt');
  await app.getByLabel(`Acceso a todos los archivos de ${PKG}`).check();
  check('the app row summarises its rules', /Conceder todos · 2 permisos · 1 acceso especial/.test(await app.locator('.perm-app-head').innerText()),
    await app.locator('.perm-app-head').innerText());
  check('rows without their own rule show the preset', /— \(Conceder\)/.test(await app.getByLabel(`Contactos de ${PKG}`).locator('option').first().innerText()));
  await shot('policy-permissions');
  await page.getByRole('button', { name: 'Guardar', exact: true }).click();
  await waitToast(/Política guardada|No se pudo guardar/);
  const saved = (await rest('GET', `/private/configurations/${policyId}`)).data;
  const dc = JSON.parse(saved.dcPolicy ?? '{}');
  check('saved: default policy ASKALL', saved.appPermissions === 'ASKALL', saved.appPermissions);
  check('saved: the app rules, exactly as chosen',
    JSON.stringify(dc.permissions?.[PKG]) === JSON.stringify({ all: 'grant', permissions: { camera: 'deny', microphone: 'prompt' }, special: ['allFiles'] }),
    JSON.stringify(dc.permissions));

  // --- 2. what the phone receives -------------------------------------------------------------------------------------
  console.log('\n== 2. the phone receives it in its config.apply');
  const cmd = (await answer(report({ canGrantSensors: true, cameraGranted: false, allFiles: 'needsUser' }))).find((c) => c.type === 'config.apply');
  const sent = cmd ? (typeof cmd.payload === 'string' ? JSON.parse(cmd.payload) : cmd.payload) : null;
  const sentApp = sent?.permissions?.apps?.find((a) => a.packageName === PKG);
  check('config.apply carries permissions.defaultPolicy = prompt', sent?.permissions?.defaultPolicy === 'prompt', JSON.stringify(sent?.permissions));
  check('…and the app with its preset, its two rules and its special access',
    sentApp?.all === 'grant' && sentApp?.permissions?.camera === 'deny' && sentApp?.permissions?.microphone === 'prompt' && sentApp?.special?.[0] === 'allFiles',
    JSON.stringify(sentApp));
  await checkin(phone, report({ canGrantSensors: true, cameraGranted: false, allFiles: 'needsUser' }));

  // --- 3. the device page ---------------------------------------------------------------------------------------------
  console.log('\n== 3. device page: «Permisos»');
  await page.goto(`${api}/devices/${phone.id}`);
  await page.getByRole('button', { name: 'Permisos', exact: true }).click({ timeout: 90000 });
  const dev = page.getByTestId('device-permissions');
  await dev.waitFor({ timeout: 20000 });
  check('effective default policy shown as reported by the phone', /Preguntar al usuario/.test(await dev.getByTestId('perm-effective-policy').innerText()),
    await dev.getByTestId('perm-effective-policy').innerText());
  check('sensor permissions: allowed on this phone', /Permitido/.test(await dev.getByTestId('perm-sensors').innerText()));
  check('no sensor warning when the phone allows it', await dev.getByTestId('perm-sensors-blocked').count() === 0);
  const tap = dev.getByTestId('perm-needs-tap');
  check('«Requiere un toque del usuario» names the pending special access', /Acceso a todos los archivos/.test(await tap.innerText()), await tap.innerText().catch(() => ''));
  const dapp = dev.getByTestId(`perm-app-${PKG}`);
  check('the app row shows what its policy asks', /Política: Conceder todos · 2 permisos · 1 acceso especial/.test(await dapp.locator('.perm-app-head').innerText()),
    await dapp.locator('.perm-app-head').innerText());
  check('an app the report says is gone is flagged', /No instalada/.test(await dev.getByTestId('perm-app-com.example.gone').innerText()));
  await dapp.locator('.perm-app-head').click();
  const stateOf = async (label) => dapp.locator('.perm-table tbody tr', { hasText: label }).locator('td').nth(2).innerText();
  check('real state: location granted and fixed by policy', /Concedido · fijado por política/.test(await stateOf('Ubicación precisa')), await stateOf('Ubicación precisa'));
  check('real state: camera not granted, fixed by policy', /Sin conceder · fijado por política/.test(await stateOf('Cámara')), await stateOf('Cámara'));
  check('real state: a permission the app does not declare', /La app no lo declara/.test(await stateOf('SMS')), await stateOf('SMS'));
  check('real state: storage (2 media permissions) granted', /Concedido/.test(await stateOf('Almacenamiento')), await stateOf('Almacenamiento'));
  const filesRow = dapp.locator('.perm-special-row', { hasText: 'Acceso a todos los archivos' });
  check('special access asked by the policy: «Requiere un toque del usuario»', /Requiere un toque del usuario/.test(await filesRow.locator('.perm-special-state').innerText()));
  check('data-saver exemption: the console says Android does not let it be read',
    /Android no deja consultarlo/.test(await dapp.locator('.perm-special-row', { hasText: 'Uso de datos sin restricción' }).innerText()));
  await shot('device-permissions');

  await filesRow.getByRole('button', { name: 'Abrir en el teléfono' }).click();
  await waitToast(/Pantalla abierta en el teléfono|No se pudo abrir/);
  const opened = (await answer()).find((c) => c.type === 'device.permissionScreen');
  const openedPayload = opened ? (typeof opened.payload === 'string' ? JSON.parse(opened.payload) : opened.payload) : null;
  check('«Abrir en el teléfono» sends the phone the exact screen to open',
    openedPayload?.packageName === PKG && openedPayload?.access === 'allFiles', JSON.stringify(opened));

  // Per-device rule on top of the policy.
  await dapp.getByLabel(`Cámara de ${PKG}`).selectOption('grant');
  await dapp.getByLabel(`Mostrar sobre otras apps de ${PKG}`).check();
  await dev.getByTestId('perm-save').click();
  await waitToast(/Permisos del equipo guardados|No se pudieron guardar/);
  const view = (await rest('GET', `/private/agent/v1/devices/${phone.id}/permissions`)).data;
  check('device rules saved', JSON.stringify(view.override?.[PKG]) === JSON.stringify({ permissions: { camera: 'grant' }, special: ['overlay'] }), JSON.stringify(view.override));
  const eff = view.effective?.apps?.find((a) => a.packageName === PKG);
  check('effective = policy + device (camera granted here, microphone still "ask", both special accesses)',
    eff?.all === 'grant' && eff?.permissions?.camera === 'grant' && eff?.permissions?.microphone === 'prompt' && eff?.special?.join() === 'allFiles,overlay', JSON.stringify(eff));
  check('the policy itself is untouched', view.policy?.apps?.find((a) => a.packageName === PKG)?.permissions?.camera === 'deny');
  const next = (await answer()).find((c) => c.type === 'config.apply');
  const nextDoc = next ? (typeof next.payload === 'string' ? JSON.parse(next.payload) : next.payload) : null;
  check('the phone gets a new config.apply with the device rule', nextDoc?.permissions?.apps?.find((a) => a.packageName === PKG)?.permissions?.camera === 'grant',
    JSON.stringify(nextDoc?.permissions));

  // CSV export of the inventory.
  const [download] = await Promise.all([page.waitForEvent('download'), dev.getByRole('button', { name: 'Descargar CSV' }).click()]);
  const csv = readFileSync(await download.path(), 'utf8');
  const lines = csv.trim().split('\r\n');
  check('CSV: header and one line per permission and special access', /"App";"Paquete";"Instalada";"Tipo";"Permiso"/.test(lines[0]) && lines.length === 1 + 6 + 5 + 1, `${lines.length} lines`);
  check('CSV: the camera row says not granted, fixed as denied, rule «Denegar»',
    lines.some((l) => /"Cámara";"android\.permission\.CAMERA";"sin conceder";"denegado";"Denegar"/.test(l)), lines.find((l) => /CAMERA/.test(l)));
  check('CSV: the special access row says it needs a tap', lines.some((l) => /"Acceso especial";"Acceso a todos los archivos";"";"Requiere un toque del usuario"/.test(l)));

  // A phone that does not let the administrator grant sensors (Android 12+), and the special access turned on.
  await checkin(phone, report({ canGrantSensors: false, cameraGranted: true, allFiles: 'allowed' }));
  await page.reload();
  await page.getByRole('button', { name: 'Permisos', exact: true }).click();
  await dev.waitFor({ timeout: 20000 });
  const blocked = dev.getByTestId('perm-sensors-blocked');
  check('Android 12+ phone that forbids it: a clear warning for this device',
    /no permite que el administrador conceda permisos de sensores/.test(await blocked.innerText().catch(() => '')), await blocked.innerText().catch(() => ''));
  check('…and the tile says so', /No permitido por el equipo/.test(await dev.getByTestId('perm-sensors').innerText()));
  await dev.getByTestId(`perm-app-${PKG}`).locator('.perm-app-head').click();
  const d2 = dev.getByTestId(`perm-app-${PKG}`);
  const loc = await d2.locator('.perm-table tbody tr', { hasText: 'Ubicación precisa' }).innerText();
  check('the location row explains why it is not granted', /este equipo no deja al administrador concederlo/i.test(loc), loc);
  check('the special access the user turned on shows as active', /Activo/.test(await d2.locator('.perm-special-row', { hasText: 'Acceso a todos los archivos' }).locator('.perm-special-state').innerText()));
  check('nothing is pending a tap any more', await dev.getByTestId('perm-needs-tap').count() === 0);
  await shot('device-permissions-sensors-blocked');

  // An agent that cannot do it yet.
  await rest('POST', '/public/agent/v1/checkin', { deviceId: phone.id, capabilities: { ...caps, device: ['lock', 'configApply'] } }, { Authorization: `Bearer ${phone.secret}` });
  await page.reload();
  await page.getByRole('button', { name: 'Permisos', exact: true }).click();
  check('an older agent: the console says it must be updated', /todavía no aplica ni reporta permisos/.test(await dev.getByTestId('perm-unsupported').innerText().catch(() => '')));

  check('no JavaScript errors in the console pages', errors.length === 0, errors.join(' | '));
} catch (e) {
  check(`script error: ${e?.message ?? e}`, false);
  await shot('error');
} finally {
  if (phone?.id) {
    const row = ((await rest('POST', '/private/devices/search', { value: phone.id, pageSize: 5, pageNum: 1 }).catch(() => ({}))).data?.devices?.items ?? [])
      .find((d) => d.number === phone.id);
    if (row?.id) await rest('DELETE', `/private/devices/${row.id}`).catch(() => undefined);
  }
  if (policyId) await rest('DELETE', `/private/configurations/${policyId}`).catch(() => undefined);
  await browser.close();
}
console.log(`\nRESULT: PASS=${pass} FAIL=${fail}`);
process.exit(fail ? 1 : 0);
