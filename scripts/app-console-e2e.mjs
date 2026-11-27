// Console (web) checks of app management in a real headless browser (Chromium), against a running DallyControl:
//   1. a file bigger than the tunnel's 100 MB limit uploads through the console, in parts, with a progress bar;
//   2. a new version signed with another key asks for confirmation (dismiss = not added, accept = added);
//   3. "Instalación en la flota": totals, the failed phone with its reason in Spanish, every phone the policy assigns
//      the app to — also the ones that never got an order (pending; held when not Device Owner) — measured against the
//      policy's version, "Reintentar" / "Enviar ahora", and the CSV export.
//
//   scripts/build-install-testapps.sh /tmp/apks
//   (cd scripts/shots && npm install && npx playwright install chromium)        # once
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/app-console-e2e.mjs --api http://localhost:8088 --apks /tmp/apks
//
// The browser talks to the console through a local proxy that refuses request bodies over --limit-mb (default 100),
// like the Cloudflare tunnel. The phones are scripted (enrolled and answered over the agent API), so no emulator is
// needed. Removes the test app at the start. Uses the Playwright copy under scripts/shots. Exit 0 only if all pass.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api', 'http://localhost:8088');
const apks = arg('--apks');
const limitMb = Number(arg('--limit-mb', '100'));
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD ?? 'admin';
if (!apks) { console.error('--apks <dir made by scripts/build-install-testapps.sh> is required'); process.exit(2); }
const PKG = 'com.dallycontrol.bigapp';
const BIG = path.join(apks, 'bigapp-v1.apk');
const V2 = path.join(apks, 'bigapp-v2.apk');
const V3 = path.join(apks, 'bigapp-v3-otherkey.apk');

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${String(detail).slice(0, 300)}]`}`);
  ok ? pass++ : fail++;
  return ok;
};
const md5 = (s) => createHash('md5').update(s).digest('hex').toUpperCase();

// --- the simulated tunnel ---------------------------------------------------------------------------------------------
const up = new URL(api);
const seen = { biggest: 0, refused: 0, parts: 0 };
const tunnel = http.createServer((req, res) => {
  const length = Number(req.headers['content-length'] ?? 0);
  seen.biggest = Math.max(seen.biggest, length);
  if (/\/chunked\/[0-9a-f-]+\/\d+$/.test(req.url ?? '')) seen.parts++;
  if (length > limitMb * 1024 * 1024) {
    seen.refused++;
    req.resume();
    req.on('end', () => { res.writeHead(413, { 'Content-Type': 'text/html' }); res.end('<h1>413 Request Entity Too Large</h1>'); });
    return;
  }
  const fwd = http.request({ host: up.hostname, port: up.port || 80, method: req.method, path: req.url,
    headers: { ...req.headers, host: up.host } }, (r) => { res.writeHead(r.statusCode ?? 502, r.headers); r.pipe(res); });
  fwd.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(fwd);
});
await new Promise((r) => tunnel.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${tunnel.address().port}`;
console.log(`== console ${api} behind a simulated tunnel that refuses bodies over ${limitMb} MB (${base})`);

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1400, height: 950 }, acceptDownloads: true });
const page = await ctx.newPage();
const rest = async (method, p, body, headers = {}) => page.evaluate(async ([m, q, b, h]) => {
  const r = await fetch(`/rest${q}`, { method: m, credentials: 'include',
    headers: { ...(b ? { 'Content-Type': 'application/json' } : {}), ...h }, body: b ? JSON.stringify(b) : undefined });
  return r.json().catch(() => ({ status: `HTTP ${r.status}` }));
}, [method, p, body, headers]);
const toastText = () => page.locator('.toast-wrap').innerText().catch(() => '');
const waitToast = async (re, timeout = 600000) => {
  await page.waitForFunction((src) => new RegExp(src).test(document.querySelector('.toast-wrap')?.textContent ?? ''), re.source, { timeout });
  return toastText();
};
const versionCodes = async (appId) => ((await rest('GET', `/private/applications/${appId}/versions`)).data ?? []).map((v) => v.versionCode).sort();
const findApp = async () => ((await rest('GET', `/private/applications/search/${PKG}`)).data ?? []).find((a) => a.pkg === PKG);
const openCustomSource = async () => {
  await page.goto(`${base}/apps`);
  await page.getByRole('tab', { name: 'APK propio' }).or(page.getByText('APK propio', { exact: true })).first().click();
  await page.locator('.dropzone').waitFor({ timeout: 20000 });
};
const dropFile = (file) => page.locator('.dropzone input[type="file"]').setInputFiles(file);

/** A scripted phone: enrolled over the agent API, with or without Device Owner. Returns { id, secret, caps }. */
async function enrollPhone(deviceOwner) {
  const token = (await rest('POST', '/private/agent/v1/token', {})).data.token;
  const caps = { policy: ['wifi'], device: ['lock'], appManagement: deviceOwner ? ['silentInstall'] : [], remoteControl: { tier: 'none' } };
  const r = await rest('POST', '/public/agent/v1/enroll', { enrollToken: token,
    agent: { version: '0.9.8', package: 'com.dallycontrol.agent' }, device: { androidSdkInt: 34, isDeviceOwner: deviceOwner }, capabilities: caps });
  return { id: r.data.deviceId, secret: r.data.deviceSecret, caps };
}
const checkin = (phone, results) => rest('POST', '/public/agent/v1/checkin',
  { deviceId: phone.id, capabilities: phone.caps, ...(results ? { results } : {}) }, { Authorization: `Bearer ${phone.secret}` });

try {
  // --- session (the same login call the console makes; the SPA reads its copy of the user from localStorage) ----------
  await page.goto(`${base}/login`);
  const login = await rest('POST', '/public/auth/login', { login: 'admin', password: md5(password) });
  if (!check('console session opened', login.status === 'OK', JSON.stringify(login))) throw new Error('login failed');
  await page.evaluate((u) => localStorage.setItem('hmdm.admin.user', JSON.stringify(u)), login.data);
  for (const old of ((await rest('GET', `/private/applications/search/${PKG}`)).data ?? [])) {
    if (old.pkg !== PKG || !old.id) continue;
    // A previous run left it in the policies: take it out first (an app in use cannot be deleted).
    const used = (await rest('GET', `/private/applications/configurations/${old.id}`)).data ?? [];
    await rest('POST', '/private/applications/configurations', { applicationId: old.id,
      configurations: used.map((l) => ({ ...l, applicationId: old.id, remove: true })) });
    await rest('POST', `/private/applications/${old.id}/remove-from-my-configs`, {});
    await rest('DELETE', `/private/applications/${old.id}`);
  }
  if (!check('the Library starts without the test app', !(await findApp()))) throw new Error('could not remove the test app');

  // --- 1. big upload with a progress bar -----------------------------------------------------------------------------
  console.log('\n== 1. upload of a file over the limit, through the console');
  const bigSize = statSync(BIG).size;
  check(`the test APK is bigger than the limit (${Math.round(bigSize / 1048576)} MB)`, bigSize > limitMb * 1024 * 1024);
  await openCustomSource();
  // Watch the bar from inside the page (on a local stack the whole upload takes a second or two).
  await page.evaluate(() => {
    window.__bar = { values: [], labels: [] };
    window.__barTimer = setInterval(() => {
      const bar = document.querySelector('.dropzone progress');
      if (!bar) return;
      window.__bar.values.push(bar.value / bar.max);
      const label = document.querySelector('.dropzone .dz-sub')?.textContent ?? '';
      if (label && window.__bar.labels[window.__bar.labels.length - 1] !== label) window.__bar.labels.push(label);
    }, 10);
  });
  await dropFile(BIG);
  await page.locator('.dropzone progress').waitFor({ state: 'attached', timeout: 60000 }).catch(() => undefined);
  const t1 = await waitToast(/APK listo|Falló la subida|ya está en la Biblioteca|No se pudo/);
  const seenBar = await page.evaluate(() => { clearInterval(window.__barTimer); return window.__bar; });
  const samples = seenBar.values;
  check('a progress bar appears', samples.length > 0);
  const increasing = samples.every((v, i) => i === 0 || v >= samples[i - 1] - 1e-9);
  const distinct = new Set(samples.map((v) => v.toFixed(2))).size;
  check(`the bar advances and never goes back (${distinct} different values seen)`, increasing && distinct >= 3 && samples[samples.length - 1] > 0.9,
    `${samples.length} samples`);
  const label = seenBar.labels.find((l) => /Parte [2-9]\d* de \d+ · [\d.,]+ MB de/.test(l)) ?? seenBar.labels.join(" | ");
  check(`it says which part is going ("${label}")`, /Parte \d+ de \d+ · [\d.,]+ MB de [\d.,]+ MB/.test(label), label);
  check('the upload finishes: "APK listo"', /APK listo/.test(t1), t1);
  check(`it travelled in parts (${seen.parts}), none over the limit (biggest ${Math.round(seen.biggest / 1048576)} MB)`,
    seen.parts >= 3 && seen.refused === 0 && seen.biggest <= limitMb * 1024 * 1024);
  const app = await findApp();
  check('the app is in the Library at versionCode 1', !!app && (await versionCodes(app.id)).join() === '1', JSON.stringify(app ?? {}).slice(0, 200));
  await page.screenshot({ path: path.join(apks, 'console-1-upload.png') });
  // What used to happen: the same file in one request is refused by the tunnel.
  const one = await page.evaluate(async (n) => {
    const form = new FormData();
    form.append('file', new Blob([new Uint8Array(n)]), 'x.apk');
    const r = await fetch('/rest/private/web-ui-files', { method: 'POST', body: form, credentials: 'include' });
    return r.status;
  }, limitMb * 1024 * 1024 + 4096);
  check('the same size in ONE request is refused by the tunnel (HTTP 413)', one === 413, one);

  // --- 2. signature ---------------------------------------------------------------------------------------------------
  console.log('\n== 2. new version signed with another key');
  await openCustomSource();
  await dropFile(V2);
  const t2 = await waitToast(/Nueva versión agregada|Falló|No se/);
  check('v2 (same key) is added with no question', /Nueva versión agregada/.test(t2), t2);
  check('Library: versionCodes 1, 2', (await versionCodes(app.id)).join() === '1,2');

  let asked = '';
  page.once('dialog', (d) => { asked = d.message(); void d.dismiss(); });
  await openCustomSource();
  await dropFile(V3);
  const t3 = await waitToast(/No se agregó la versión|Nueva versión agregada/);
  check('v3 (another key) asks for confirmation', /firma/i.test(asked) && /INSTALL_FAILED_UPDATE_INCOMPATIBLE/.test(asked), asked);
  check('…naming the version it compares with', /1\.0\.2/.test(asked), asked);
  check('cancelled: the version is NOT added', /No se agregó la versión/.test(t3) && (await versionCodes(app.id)).join() === '1,2', t3);
  await page.screenshot({ path: path.join(apks, 'console-2-signature.png') });

  let asked2 = '';
  page.once('dialog', (d) => { asked2 = d.message(); void d.accept(); });
  await openCustomSource();
  await dropFile(V3);
  const t4 = await waitToast(/Nueva versión agregada|No se agregó|Falló/);
  check('confirmed: the version is added', asked2 !== '' && /Nueva versión agregada/.test(t4) && (await versionCodes(app.id)).join() === '1,2,3', t4);

  // --- 3. fleet install view -----------------------------------------------------------------------------------------
  console.log('\n== 3. "Instalación en la flota"');
  const failing = await enrollPhone(true);      // Device Owner; its install will fail for lack of space
  const notOwner = await enrollPhone(false);    // not Device Owner: the order is held
  const fine = await enrollPhone(true);         // Device Owner; installs fine
  const unsent = await enrollPhone(true);       // Device Owner; assigned by the policy but never sent the order
  // The policy of those phones assigns the app (every policy of this test stack). The order goes out only to two of
  // them ("Instalar apps de la política" on the device page): the other two are assigned but never got one.
  const links = (await rest('GET', `/private/applications/configurations/${app.id}`)).data ?? [];
  await rest('POST', '/private/applications/configurations', { applicationId: app.id,
    configurations: links.map((l) => ({ ...l, applicationId: app.id, action: 1, showIcon: true, remove: false })) });
  for (const phone of [failing, fine]) await rest('POST', `/private/agent/v1/devices/${phone.id}/syncApps`);
  const queuedFor = async (phone) => ((await checkin(phone)).data?.commands ?? []).filter((c) => c.type === 'app.install' && JSON.stringify(c.payload).includes(PKG));
  const cmds = await queuedFor(failing);
  check('the policy queued the install (versionCode 3) for the phone', cmds.length === 1 && JSON.stringify(cmds[0].payload).includes('"versionCode":3'), JSON.stringify(cmds).slice(0, 200));
  await checkin(failing, cmds.map((c) => ({ commandId: c.commandId, status: 'failed', detail: 'INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to allocate 125837721' })));
  const ok = await queuedFor(fine);
  await checkin(fine, ok.map((c) => ({ commandId: c.commandId, status: 'done' })));
  await checkin(notOwner);

  await page.goto(`${base}/apps`);
  await page.locator(`[data-testid="install-status-${PKG}"], [data-testid="group-install-${PKG}"]`).first().click();
  const modal = page.getByTestId('install-fleet-modal');
  await modal.getByTestId('install-fleet-counts').waitFor({ timeout: 20000 });
  const head = await modal.locator('p.muted').first().innerText();
  check('the target is the version the policy assigns (versionCode 3)', /asigna la política/.test(head) && /versionCode 3/.test(head), head);
  const num = async (id) => Number((await modal.getByTestId(id).innerText()).match(/\d+/)?.[0] ?? -1);
  check('totals: at least 1 installed, 1 held, 1 failed',
    await num('count-installed') >= 1 && await num('count-held') >= 1 && await num('count-failed') >= 1,
    await modal.getByTestId('install-fleet-counts').innerText());
  const rowFail = modal.getByTestId(`install-row-${failing.id}`);
  const failText = await rowFail.innerText();
  check('the failed phone shows the reason in Spanish', /Fallida/.test(failText) && /No hay espacio suficiente/.test(failText), failText);
  check('…what the phone answered, and when it will be retried', /INSTALL_FAILED_INSUFFICIENT_STORAGE/.test(failText) && /Se reintentará sola/.test(failText), failText);
  const heldText = await modal.getByTestId(`install-row-${notOwner.id}`).innerText();
  check('a phone the policy assigns the app to but that is not Device Owner is listed as held, with why — though it never got an order',
    /Retenida/.test(heldText) && /Device Owner/.test(heldText), heldText);
  const unsentRow = modal.getByTestId(`install-row-${unsent.id}`);
  const unsentText = await unsentRow.innerText();
  check('an assigned phone that never got the order is listed as pending, saying so',
    (await unsentRow.getAttribute('data-state')) === 'pending' && /aún no se le ha enviado la orden/.test(unsentText), unsentText);
  check('the phone that installed is listed as installed', (await modal.getByTestId(`install-row-${fine.id}`).getAttribute('data-state')) === 'installed');
  await page.screenshot({ path: path.join(apks, 'console-3-fleet.png') });

  const [download] = await Promise.all([page.waitForEvent('download'), modal.getByTestId('install-export').click()]);
  const csvPath = path.join(apks, download.suggestedFilename());
  await download.saveAs(csvPath);
  const csv = readFileSync(csvPath, 'utf8');
  const lines = csv.trim().split(/\r\n/);
  check('CSV export downloads (name, BOM, header)', /^instalacion-com\.dallycontrol\.bigapp-\d{4}-\d{2}-\d{2}\.csv$/.test(download.suggestedFilename())
    && csv.charCodeAt(0) === 0xFEFF && /Equipo;Descripción;Carpeta;Estado;Motivo/.test(lines[0]), lines[0]);
  const csvFail = lines.find((l) => l.startsWith(failing.id)) ?? '';
  check('CSV has the failed phone with state, reason and raw answer', /;Fallida;/.test(csvFail) && /espacio/.test(csvFail) && /INSUFFICIENT_STORAGE/.test(csvFail), csvFail);
  check('CSV has one line per phone listed', lines.length - 1 === await modal.locator('[data-testid^="install-row-"]').count(), `${lines.length - 1}`);

  await rowFail.getByTestId(`install-retry-${failing.id}`).click();
  const t5 = await waitToast(/Instalación reenviada|Nada que reintentar|No se pudo reintentar/, 30000);
  check('"Reintentar" on the failed phone sends the install again', /Instalación reenviada/.test(t5), t5);
  await page.waitForFunction((id) => document.querySelector(`[data-testid="install-row-${id}"]`)?.getAttribute('data-state') === 'pending', failing.id, { timeout: 20000 });
  check('…and the phone goes back to pending', true);
  const again = await queuedFor(failing);
  check('the phone really receives a new install order', again.length === 1, JSON.stringify(again).slice(0, 200));

  await unsentRow.getByTestId(`install-retry-${unsent.id}`).click();
  await page.waitForFunction((id) => !/aún no se le ha enviado/.test(document.querySelector(`[data-testid="install-row-${id}"]`)?.textContent ?? 'aún no se le ha enviado'), unsent.id, { timeout: 20000 });
  check('"Enviar ahora" sends the order to the assigned phone that had none', (await queuedFor(unsent)).length === 1);

  // Taken out of the policy: a failed install is not brought back.
  await checkin(failing, again.map((c) => ({ commandId: c.commandId, status: 'failed', detail: 'INSTALL_FAILED_INSUFFICIENT_STORAGE' })));
  const out = await rest('POST', `/private/applications/${app.id}/remove-from-my-configs`, {});
  check('the app is taken out of the policies', out.status === 'OK' && (out.data?.affected ?? 0) > 0, JSON.stringify(out));
  const r = await rest('POST', '/private/agent/v1/apps/install/retry', { packageName: PKG, deviceNumbers: [failing.id] });
  check('after the app leaves the policy, a retry installs nothing', r.status === 'OK' && r.data?.queued === 0 && (await queuedFor(failing)).length === 0, JSON.stringify(r));
} catch (e) {
  check(`script error: ${e?.message ?? e}`, false);
  await page.screenshot({ path: path.join(apks, 'console-error.png') }).catch(() => undefined);
} finally {
  await browser.close();
  tunnel.close();
}
console.log(`\nRESULT: PASS=${pass} FAIL=${fail}`);
process.exit(fail ? 1 : 0);
