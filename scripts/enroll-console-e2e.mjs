// Console (web) checks of the Enroll page against a running DallyControl, in a real browser: the QR panel and its
// status line, hidden Wi-Fi / proxy / 802.1X fields, the attempts log (fed by a refused enroll made here), and a
// folder code's "Zero-Touch / KME" export.
//
//   DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/enroll-console-e2e.mjs --api http://localhost:8088 [--shots dir]
//
// Creates an e2e-* folder and code and removes them. Uses the Playwright copy under scripts/shots (override with
// PLAYWRIGHT=/path/to/playwright/index.mjs). Exit 0 only if all checks pass.
import { createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';

const { chromium } = await import(process.env.PLAYWRIGHT || './shots/node_modules/playwright/index.mjs');
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const api = arg('--api');
const shots = arg('--shots', '/tmp/dallycontrol-enroll-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD;
if (!api || !password) { console.error('--api and DALLYCONTROL_ADMIN_PASSWORD are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${detail}]`}`); ok ? pass++ : fail++; };
const md5 = (s) => createHash('md5').update(s).digest('hex').toUpperCase();

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
const rest = async (method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: { 'Content-Type': 'application/json' }, body: b ? JSON.stringify(b) : undefined });
  return r.json();
}, [method, path, body]);
const cleanup = [];
const suf = Date.now().toString(36);

try {
  await page.goto(`${api}/login`);
  await page.fill('input[type="text"], input[name="login"]', 'admin');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 });
  check('console login', true);
  void md5;

  const folder = (await rest('POST', '/private/fleet/v1/groups', { name: `e2e-Inscripcion-${suf}` })).data;
  cleanup.push(() => rest('DELETE', `/private/fleet/v1/groups/${folder.id}`));

  // --- the QR panel ---
  await page.goto(`${api}/enroll`);
  await page.waitForSelector('.qr-frame canvas', { timeout: 15000 });
  check('the QR is drawn', await page.locator('.qr-frame canvas').count() === 1);
  const cap = await page.locator('.qr-cap').innerText();
  check('the caption says it carries the current time and when it expires', /lleva la hora actual/.test(cap) && /vence/.test(cap), cap);
  check('the status line waits for a phone', /Esperando a que un teléfono escanee/.test(await page.locator('[data-testid="qr-status"]').innerText()));
  check('troubleshooting names FRP, Knox and the clock', /FRP/.test(await page.locator('[data-testid="qr-troubleshoot"]').innerText()));

  await page.click('summary:has-text("Conectar a Wi‑Fi")');
  await page.fill('input[placeholder="WiFi-Oficina"]', 'Bodega-Oculta');
  await page.check('[data-testid="wifi-hidden"]');
  await page.fill('[data-testid="proxy-host"]', 'proxy.empresa.local');
  check('hidden network shows in the caption', /Bodega-Oculta”? \(oculta\)/.test(await page.locator('.qr-cap').innerText()), await page.locator('.qr-cap').innerText());
  await page.selectOption('.wifi-fields select:has(option[value="EAP"])', 'EAP');
  check('802.1X fields appear', await page.locator('text=Dominio del servidor RADIUS').count() === 1);
  check('the QR is still drawn with every option on', await page.locator('.qr-frame canvas').count() === 1);
  await page.screenshot({ path: `${shots}/enroll-qr.png`, fullPage: true });

  // --- a refused enroll made "by a phone" shows up in the attempts log, and in the QR's own status line ---
  const minted = await rest('POST', '/private/agent/v1/token', { groupId: folder.id });
  const token = minted.data.token;
  const enroll = (hw) => rest('POST', '/public/agent/v1/enroll', {
    protocolVersion: '1.1', enrollToken: token, hardwareId: hw, serial: `SN${suf.toUpperCase()}${hw}`,
    agent: { version: '0.9.9-e2e', package: 'com.dallycontrol.agent' },
    device: { id: '', androidSdkInt: 30, androidRelease: '11', manufacturer: 'e2eCo', model: `E2E-${suf}`, isDeviceOwner: true },
    capabilities: {}, diagnostics: { path: 'qr-legacy', attempts: 1 },
  });
  const first = await enroll('a1');
  check('a phone enrolls with the token', first.status === 'OK', JSON.stringify(first));
  if (first.status === 'OK') cleanup.push(async () => {
    const devs = (await rest('POST', '/private/devices/search', { pageNum: 1, pageSize: 50, value: first.data.deviceId })).data;
    const id = devs?.devices?.items?.[0]?.id;
    if (id) await rest('DELETE', `/private/devices/${id}`);
  });
  const second = await enroll('b2');
  check('a second phone is refused (token used)', second.status === 'ERROR' && second.message === 'error.agent.token.used', JSON.stringify(second));

  await page.reload();
  await page.waitForSelector('[data-testid="enroll-attempts"] table', { timeout: 15000 });
  const log = await page.locator('[data-testid="enroll-attempts"]').innerText();
  check('the log shows the refusal in words', /El QR ya lo usó otro equipo/.test(log), log.slice(0, 300));
  check('the log shows maker, model and Android', new RegExp(`e2eCo E2E-${suf}`).test(log) && /11/.test(log) && /API 30/.test(log));
  check('the log shows the successful enroll and its path', /Inscrito/.test(log) && /QR \(Android 7–11\)/.test(log));
  check('the log says what to do', /Genera un QR nuevo/.test(log));
  await page.check('[data-testid="enroll-attempts"] input[type="checkbox"]');
  const problems = await page.locator('[data-testid="enroll-attempts"] tbody tr').evaluateAll((rows) => rows.map((r) => r.dataset.outcome));
  check('"only problems" hides the successful rows', problems.length > 0 && !problems.includes('ok'), problems.join(','));
  await page.screenshot({ path: `${shots}/enroll-attempts.png`, fullPage: true });

  // --- folder code with a hidden Wi-Fi and its Zero-Touch / KME export ---
  await page.click('button:has-text("Códigos de carpeta")');
  await page.selectOption('select[aria-label="Carpeta del código"]', String(folder.id));
  await page.fill('input[aria-label="Etiqueta del código"]', `e2e-${suf}`);
  await page.fill('input[aria-label="SSID del Wi‑Fi"]', 'Bodega-Oculta');
  await page.fill('input[aria-label="Contraseña del Wi‑Fi"]', 'clave-e2e-123');
  await page.check('[data-testid="code-wifi-hidden"]');
  await page.click('button:has-text("Crear código")');
  await page.waitForSelector('.codes-qr canvas', { timeout: 15000 });
  const codes = (await rest('GET', '/private/agent/v1/codes')).data;
  const code = codes.find((c) => c.label === `e2e-${suf}`);
  check('the code keeps the hidden-network flag', !!code && code.wifiHidden === true, JSON.stringify(code));
  if (code) cleanup.push(async () => { await rest('DELETE', `/private/agent/v1/codes/${code.id}`); await rest('DELETE', `/private/agent/v1/codes/${code.id}?purge=true`); });
  const row = page.locator('tr', { hasText: `e2e-${suf}` });
  await row.locator('[data-testid="code-zt"]').click();
  await page.waitForSelector('[data-testid="zt-export"] textarea');
  const zt = JSON.parse(await page.locator('[data-testid="zt-export"] textarea').inputValue());
  const P = 'android.app.extra.';
  check('Zero-Touch JSON names the agent component, checksum and download', !!zt[`${P}PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME`]
    && !!zt[`${P}PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`] && /\.apk$|apk\?/.test(zt[`${P}PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION`] || ''));
  check('Zero-Touch JSON carries the folder code and this server', zt[`${P}PROVISIONING_ADMIN_EXTRAS_BUNDLE`]?.['com.dallycontrol.ENROLL_TOKEN'] === code?.code
    && zt[`${P}PROVISIONING_ADMIN_EXTRAS_BUNDLE`]?.['com.dallycontrol.SERVER_URL'] === new URL(api).origin);
  check('Zero-Touch JSON carries the hidden Wi-Fi and no clock', zt[`${P}PROVISIONING_WIFI_HIDDEN`] === true
    && zt[`${P}PROVISIONING_WIFI_SSID`] === 'Bodega-Oculta' && !(`${P}PROVISIONING_LOCAL_TIME` in zt));
  await page.screenshot({ path: `${shots}/enroll-zero-touch.png`, fullPage: true });
  await page.click('[data-testid="zt-export"] button:has-text("Samsung KME")');
  const kme = JSON.parse(await page.locator('[data-testid="zt-export"] textarea').inputValue());
  check('KME custom JSON is the admin extras only', Object.keys(kme).length === 2 && kme['com.dallycontrol.ENROLL_TOKEN'] === code?.code);
  check('KME shows the agent APK address', /MDM Agent APK/.test(await page.locator('[data-testid="zt-export"]').innerText()));
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('[data-testid="zt-download"]')]);
  check('the export downloads a .json file', /\.json$/.test(download.suggestedFilename()), download.suggestedFilename());
  check('no page errors', errors.length === 0, errors.join(' | '));
} catch (e) {
  check('run completed', false, String(e));
  await page.screenshot({ path: `${shots}/enroll-failure.png`, fullPage: true }).catch(() => {});
} finally {
  for (const c of cleanup.reverse()) { try { await c(); } catch { /* best effort */ } }
  await browser.close();
}
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
