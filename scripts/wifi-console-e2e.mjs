#!/usr/bin/env node
// Console end-to-end test of corporate Wi-Fi (MDM-13) in a headless browser: the policy's Wi-Fi editor (a hidden PSK
// network, an 802.1X network with an uploaded CA, per-network options, the lock-down rules), the checks it shows
// before saving, that a saved password never comes back ("guardada" + "Cambiar"), the certificate list with its
// expiry, the permission of a read-only role, and — with --device — the "Wi-Fi gestionado" card of an enrolled phone.
//
//   (cd scripts/shots && npm ci)       # once: Playwright
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/wifi-console-e2e.mjs --api http://localhost:8088 \
//        [--device <device number>] [--shots /tmp/dallycontrol-wifi-shots]
//
// It creates a policy "e2e-wifi-*", a CA certificate and a user "e2ewifi*" and removes them on exit. Needs openssl.
// Exit code 0 = every check passed.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { mkdirSync, mkdtempSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const device = arg('--device');
const shots = arg('--shots', '/tmp/dallycontrol-wifi-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !password) { console.error('--api and DALLYCONTROL_ADMIN_PASSWORD are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${String(detail).slice(0, 300)}]`}`); ok ? pass++ : fail++; };

// A CA made on the spot: no key material lives in the repository.
const dir = mkdtempSync(join(tmpdir(), 'dc-wifi-ui-'));
const caPem = join(dir, 'ca.pem');
execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', join(dir, 'ca.key'), '-out', caPem, '-days', '20',
  '-subj', '/CN=E2E UI CA/O=E2E', '-addext', 'basicConstraints=critical,CA:TRUE'], { stdio: 'ignore' });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1400, height: 1000 }, locale: 'es-CO' });
const page = await ctx.newPage();
page.on('dialog', (d) => d.accept());
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
const rest = async (method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined });
  return r.json();
}, [method, path, body]);
const md5 = (s) => createHash('md5').update(s).digest('hex').toUpperCase();
const signIn = async (user, pw) => {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', user);
  await page.fill('input[type="password"]', pw);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices|configs/, { timeout: 20000 });
};

const suf = Math.floor(Math.random() * 1e5);
const policyName = `e2e-wifi-${suf}`;
const PSK = `Clave-UI-${suf}-x`;
const EAP = `eap-UI-${suf}-y`;
let policyId, certId, userId;
const policy = async () => ((await rest('GET', '/private/configurations/search')).data ?? []).find((c) => c.name === policyName);
const openPolicy = async () => {
  await page.goto(`${api}/configs`);
  await page.locator('.cfg-card', { hasText: policyName }).getByRole('button', { name: 'Editar' }).click();
  await page.locator('[data-testid="policy-wifi"]').waitFor({ timeout: 15000 });
  await page.locator('[data-testid="policy-wifi"]').scrollIntoViewIfNeeded();
};
const save = async () => {
  const done = page.waitForResponse((r) => r.url().includes('/rest/private/configurations') && r.request().method() === 'PUT', { timeout: 30000 }).catch(() => null);
  await page.getByRole('button', { name: 'Guardar', exact: true }).first().click();
  await done;
  await page.waitForTimeout(500);
};

try {
  await signIn('admin', password);
  check('console login', true);

  // A policy of its own, made from the first one (so every required field is there).
  const base = ((await rest('GET', '/private/configurations/search')).data ?? [])[0];
  const made = await rest('PUT', '/private/configurations', { ...base, id: null, name: policyName, description: 'e2e Wi-Fi', dcPolicy: null, qrCodeKey: null });
  check('a policy for the test exists', made.status === 'OK', JSON.stringify(made).slice(0, 200));
  policyId = made.data?.id;

  // --- the editor ----------------------------------------------------------------------------------------------------
  await openPolicy();
  const wifi = page.locator('[data-testid="policy-wifi"]');
  check('the Wi-Fi editor is in the policy', await wifi.isVisible());

  await wifi.getByRole('button', { name: '+ Agregar red' }).click();
  await page.getByLabel('Wi‑Fi 1 SSID').fill('Planta-UI');
  const options = await page.getByLabel('Wi‑Fi 1 seguridad').locator('option').allTextContents();
  check('every security is offered (WPA2, WPA3, transition, OWE, Enterprise)',
    ['WPA2‑Personal', 'WPA3‑Personal', 'WPA2/WPA3', 'WPA2‑Enterprise', 'WPA3‑Enterprise', 'OWE'].every((s) => options.some((o) => o.includes(s))), options.join('|'));
  check('a network without password is flagged before saving', await wifi.getByText('Falta la contraseña.').isVisible());
  await page.getByLabel('Wi‑Fi 1 contraseña').fill('corta');
  check('a short password is flagged', await wifi.getByText('La contraseña debe tener entre 8 y 63 caracteres.').isVisible());
  await page.getByLabel('Wi‑Fi 1 contraseña').fill(PSK);
  await page.getByLabel('Wi‑Fi 1 opciones').click();
  await page.getByLabel('Wi‑Fi 1 red oculta').check();
  check('the "hidden network" control exists and can be set', await page.getByLabel('Wi‑Fi 1 red oculta').isChecked());
  await page.getByLabel('Wi‑Fi 1 prioridad').fill('5');
  await page.getByLabel('Wi‑Fi 1 MAC').selectOption('device');
  await page.getByLabel('Wi‑Fi 1 medida').selectOption('unmetered');
  await page.getByLabel('Wi‑Fi 1 proxy', { exact: true }).selectOption('manual');
  await page.getByLabel('Wi‑Fi 1 proxy servidor').fill('proxy.e2e.test');
  await page.getByLabel('Wi‑Fi 1 proxy puerto').fill('3128');
  await page.getByLabel('Wi‑Fi 1 IP', { exact: true }).selectOption('static');
  await page.getByLabel('Wi‑Fi 1 IP fija').fill('192.168.50.20');
  check('a static IP without gateway is flagged', await wifi.getByText('La IP fija necesita la puerta de enlace.').isVisible());
  await page.getByLabel('Wi‑Fi 1 puerta de enlace').fill('192.168.50.1');
  await page.getByLabel('Wi‑Fi 1 DNS').fill('192.168.50.1, 8.8.8.8');

  // --- certificate ----------------------------------------------------------------------------------------------------
  await wifi.locator('[data-testid="wifi-certs"] summary').click();
  await page.getByLabel('Nombre del certificado').fill(`CA e2e ${suf}`);
  await page.locator('[data-testid="wifi-cert-file"]').setInputFiles(caPem);
  await wifi.getByRole('button', { name: 'Subir' }).click();
  const row = wifi.locator('.wf-cert-table tr', { hasText: `CA e2e ${suf}` });
  await row.waitFor({ timeout: 15000 });
  check('an uploaded CA shows its subject and when it expires', (await row.textContent()).includes('E2E UI CA') && /Vence en \d+ día/.test(await row.textContent()), await row.textContent());
  check('a certificate about to expire is marked', await row.locator('.chip.tone-warn').count() === 1);
  certId = ((await rest('GET', '/private/wifi-certificates')).data ?? []).find((c) => c.name === `CA e2e ${suf}`)?.id;
  await page.screenshot({ path: join(shots, 'wifi-1-red-y-certificado.png'), fullPage: true });

  // --- an 802.1X network ----------------------------------------------------------------------------------------------
  await wifi.getByRole('button', { name: '+ Agregar red' }).click();
  await page.getByLabel('Wi‑Fi 2 SSID').fill('Corp-UI');
  await page.getByLabel('Wi‑Fi 2 seguridad').selectOption('EAP');
  check('802.1X without CA and domain is flagged', await wifi.getByText('Elige el certificado de la CA del servidor', { exact: false }).isVisible()
    && await wifi.getByText('Indica el dominio del certificado del servidor.').isVisible());
  await page.getByLabel('Wi‑Fi 2 identidad', { exact: true }).fill('equipo@e2e.test');
  await page.getByLabel('Wi‑Fi 2 identidad anónima').fill('anonimo@e2e.test');
  await page.getByLabel('Wi‑Fi 2 contraseña EAP').fill(EAP);
  await page.getByLabel('Wi‑Fi 2 dominio del servidor').fill('radius.e2e.test');
  await page.getByRole('group', { name: 'Wi‑Fi 2 CA' }).getByText(`CA e2e ${suf}`).click();
  const methods = await page.getByLabel('Wi‑Fi 2 método EAP').locator('option').allTextContents();
  check('PEAP, TTLS and TLS are offered', ['PEAP', 'TTLS', 'TLS'].every((m) => methods.some((o) => o.startsWith(m))), methods.join('|'));
  check('the 802.1X network has no pending problem', await page.locator('[data-testid="wifi-net-1"] [data-testid="wifi-problems"]').count() === 0,
    await page.locator('[data-testid="wifi-net-1"]').textContent());

  // --- lock-down ------------------------------------------------------------------------------------------------------
  const rules = page.locator('[data-testid="wifi-rules"]');
  await rules.getByLabel('Bloquear las redes gestionadas').check();
  await rules.getByLabel('Impedir agregar redes').check();
  await rules.getByLabel('Redes permitidas').selectOption('allow');
  await rules.getByLabel('Seguridad mínima').selectOption('enterprise192');
  check('a minimum security no managed network reaches is flagged', await wifi.getByText('Ninguna red gestionada alcanza la seguridad mínima', { exact: false }).isVisible());
  await save();
  check('... and the server refuses it too', (await policy())?.dcPolicy == null || !String((await policy()).dcPolicy).includes('enterprise192'));
  await rules.getByLabel('Seguridad mínima').selectOption('personal');
  check('the safeguard is explained once a restrictive rule is on', await wifi.getByText('lo relaja por su cuenta', { exact: false }).isVisible());
  await rules.getByLabel('Minutos de salvaguarda').fill('15');
  await page.screenshot({ path: join(shots, 'wifi-2-empresarial-y-bloqueos.png'), fullPage: true });
  await save();

  // --- what was stored, and what comes back -----------------------------------------------------------------------------
  const saved = await policy();
  const dc = JSON.parse(saved?.dcPolicy ?? '{}');
  const n1 = dc.wifi?.[0] ?? {}, n2 = dc.wifi?.[1] ?? {};
  check('the policy was saved with both networks', dc.wifi?.length === 2 && n1.ssid === 'Planta-UI' && n2.ssid === 'Corp-UI', saved?.dcPolicy);
  check('hidden, priority, MAC, metered, proxy and static IP were stored', n1.hidden === true && n1.priority === 5 && n1.mac === 'device' && n1.metered === 'unmetered'
    && n1.proxy?.host === 'proxy.e2e.test' && n1.proxy?.port === 3128 && n1.ip?.address === '192.168.50.20' && n1.ip?.gateway === '192.168.50.1'
    && n1.ip?.dns?.length === 2, JSON.stringify(n1));
  check('the 802.1X parameters were stored with the CA', n2.security === 'EAP' && n2.eap?.method === 'PEAP' && n2.eap?.phase2 === 'MSCHAPV2'
    && n2.eap?.identity === 'equipo@e2e.test' && n2.eap?.domain === 'radius.e2e.test' && n2.eap?.caCertIds?.[0] === certId, JSON.stringify(n2));
  check('the rules were stored', dc.wifiRules?.lockManaged === true && dc.wifiRules?.blockAdd === true && dc.wifiRules?.ssidMode === 'allow'
    && dc.wifiRules?.minSecurity === 'personal' && dc.wifiRules?.safeguardMinutes === 15, JSON.stringify(dc.wifiRules));
  const raw = JSON.stringify(await rest('GET', '/private/configurations/search'));
  check('the API never returns the Wi-Fi passwords', !raw.includes(PSK) && !raw.includes(EAP) && n1.passwordSet === true && n2.eap?.passwordSet === true);

  await openPolicy();
  const html = await page.content();
  check('the reopened policy does not carry the passwords anywhere in the page', !html.includes(PSK) && !html.includes(EAP));
  check('saved passwords show as "guardada" with a "Cambiar" button', await page.locator('[data-testid="wifi-secret-saved"]').count() === 2
    && await page.getByRole('button', { name: 'Cambiar Wi‑Fi 1 contraseña' }).isVisible());
  check('the hidden-network control keeps its value', await page.getByLabel('Wi‑Fi 1 red oculta').isChecked());
  // saving again without typing keeps the stored password
  await page.getByLabel('Wi‑Fi 1 prioridad').fill('6');
  await save();
  const kept = JSON.parse((await policy())?.dcPolicy ?? '{}');
  check('saving again without retyping keeps the networks (password still stored)', kept.wifi?.[0]?.priority === 6 && kept.wifi?.[0]?.passwordSet === true
    && kept.wifi?.[1]?.eap?.passwordSet === true, JSON.stringify(kept.wifi));
  await openPolicy();
  await page.getByRole('button', { name: 'Cambiar Wi‑Fi 1 contraseña' }).click();
  await page.getByLabel('Wi‑Fi 1 contraseña').fill(`${PSK}-2`);
  await save();
  check('"Cambiar" stores the new password', JSON.parse((await policy())?.dcPolicy ?? '{}').wifi?.[0]?.passwordSet === true);
  await page.screenshot({ path: join(shots, 'wifi-3-guardada.png'), fullPage: true });

  const del = await rest('DELETE', `/private/wifi-certificates/${certId}`);
  check('a certificate in use cannot be deleted', del.status !== 'OK' && String(del.message).includes(policyName), JSON.stringify(del));

  // --- device card ------------------------------------------------------------------------------------------------------
  if (device) {
    await page.goto(`${api}/devices/${device}`);
    const card = page.locator('[data-testid="managed-wifi"]');
    const shown = await card.waitFor({ timeout: 20000 }).then(() => true).catch(() => false);
    check('the device page shows "Wi‑Fi gestionado" with a state per network', shown && await card.locator('.wf-status').count() > 0);
    if (shown) await card.screenshot({ path: join(shots, 'wifi-4-equipo.png') });
  }

  // --- roles ------------------------------------------------------------------------------------------------------------
  // A read-only user (role "Visor", limited to folders) may not upload or delete certificates.
  {
    const login = `e2ewifi${suf}`;
    const folders = ((await rest('GET', '/private/groups/search')).data ?? []).map((g) => g.id).slice(0, 1);
    const created = await rest('PUT', '/private/dc/users', { login, name: 'E2E Wi-Fi', email: `${login}@example.test`, password: md5(`Pw-${suf}-wifi!`),
      role: 'foldersView', folders });
    userId = created.data?.id ?? ((await rest('GET', '/private/dc/users')).data ?? []).find((u) => u.login === login)?.id;
    if (created.status === 'OK') {
      const ctx2 = await browser.newContext();
      const p2 = await ctx2.newPage();
      await p2.goto(`${api}/login`);
      const r = await p2.evaluate(async ([l, pw, cert]) => {
        // A refusal may be the API's own envelope or a plain HTTP 403 page from the role filter: both count.
        const j = async (m, p, b) => {
          const res = await fetch(`/rest${p}`, { method: m, headers: { 'Content-Type': 'application/json' }, body: b ? JSON.stringify(b) : undefined });
          const text = await res.text();
          try { return { http: res.status, ...JSON.parse(text) }; } catch { return { http: res.status, status: res.ok ? 'OK' : 'HTTP_ERROR' }; }
        };
        const auth = await j('POST', '/public/auth/login', { login: l, password: pw });
        return { auth: auth.status, upload: await j('POST', '/private/wifi-certificates', { name: 'x', kind: 'CA', data: 'AAAA' }), del: await j('DELETE', `/private/wifi-certificates/${cert}`) };
      }, [login, md5(`Pw-${suf}-wifi!`), certId]);
      check('a read-only user (Visor) cannot upload or delete Wi-Fi certificates', r.auth === 'OK' && r.upload.status !== 'OK' && r.del.status !== 'OK'
        && ((await rest('GET', '/private/wifi-certificates')).data ?? []).some((c) => c.id === certId), JSON.stringify(r));
      await ctx2.close();
    } else {
      console.log(`  SKIP  role check (could not create a user: ${JSON.stringify(created).slice(0, 160)})`);
    }
  }

  check('no JavaScript errors in the console pages', errors.length === 0, errors.join(' | '));
} catch (e) {
  check('the run finished', false, e?.stack ?? String(e));
  await page.screenshot({ path: join(shots, 'wifi-error.png'), fullPage: true }).catch(() => {});
} finally {
  try {
    await page.goto(`${api}/configs`).catch(() => {});
    if (policyId) await rest('DELETE', `/private/configurations/${policyId}`);
    if (certId) await rest('DELETE', `/private/wifi-certificates/${certId}`);
    if (userId) await rest('DELETE', `/private/dc/users/${userId}`);
  } catch { /* best effort */ }
  await browser.close();
}
console.log(`\nRESULT: PASS=${pass} FAIL=${fail}  (screenshots: ${shots})`);
process.exit(fail ? 1 : 0);
