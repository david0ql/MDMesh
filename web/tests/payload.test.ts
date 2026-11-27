// Unit tests of the provisioning payload. Run with:  npm test   (Node >= 22.18 runs TypeScript directly).
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  androidLocale, buildKmeJson, buildProvisioningExtras, buildProvisioningJson, buildZeroTouchJson, qrDensity,
  trackedApkUrl, type ProvisioningOptions,
} from '../src/enroll/payload.ts';

const P = 'android.app.extra.';
const base: ProvisioningOptions = {
  adminComponent: 'com.dallycontrol.agent/com.dallycontrol.agent.admin.AdminReceiver',
  signatureChecksum: 'byMlbbiklLB7k5mQLNPBci0AC_kI3YLBS5Lkw9cIzlo',
  apkUrl: 'https://mdm.example.com/files/dallycontrol-agent.apk',
  serverUrl: 'https://mdm.example.com',
  token: '0b6f6d0e-1111-2222-3333-444455556666',
};

test('the payload names the agent, its checksum, where to download it and what the agent needs', () => {
  const x = buildProvisioningExtras(base);
  assert.equal(x[`${P}PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME`], base.adminComponent);
  assert.equal(x[`${P}PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`], base.signatureChecksum);
  assert.equal(x[`${P}PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION`], base.apkUrl);
  assert.deepEqual(x[`${P}PROVISIONING_ADMIN_EXTRAS_BUNDLE`], {
    'com.dallycontrol.ENROLL_TOKEN': base.token,
    'com.dallycontrol.SERVER_URL': base.serverUrl,
  });
  assert.equal(x[`${P}PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED`], true);
});

test('without a clock nothing about time is sent', () => {
  const x = buildProvisioningExtras(base);
  assert.equal(`${P}PROVISIONING_LOCAL_TIME` in x, false);
  assert.equal(`${P}PROVISIONING_TIME_ZONE` in x, false);
  assert.equal(`${P}PROVISIONING_LOCALE` in x, false);
});

test('the clock sets local time (a number), time zone and locale in Android form', () => {
  const x = buildProvisioningExtras({ ...base, clock: { nowMs: 1795800005123.4, timeZone: 'America/Bogota', locale: 'es-CO' } });
  assert.equal(x[`${P}PROVISIONING_LOCAL_TIME`], 1795800005123);
  assert.equal(x[`${P}PROVISIONING_TIME_ZONE`], 'America/Bogota');
  assert.equal(x[`${P}PROVISIONING_LOCALE`], 'es_CO');
  // It must be a JSON number: Android reads it as a long and ignores a string.
  assert.match(buildProvisioningJson({ ...base, clock: { nowMs: 1795800005123 } }), /"android\.app\.extra\.PROVISIONING_LOCAL_TIME":1795800005123[,}]/);
});

test('a locale without a region is not sent', () => {
  assert.equal(androidLocale('es'), undefined);
  assert.equal(androidLocale('es-419'), undefined);
  assert.equal(androidLocale('en-us'), 'en_US');
  assert.equal(androidLocale(undefined), undefined);
});

test('WPA Wi-Fi carries its password; an open one never does', () => {
  const wpa = buildProvisioningExtras({ ...base, wifi: { ssid: ' Bodega ', password: 'secreta123' } });
  assert.equal(wpa[`${P}PROVISIONING_WIFI_SSID`], 'Bodega');
  assert.equal(wpa[`${P}PROVISIONING_WIFI_SECURITY_TYPE`], 'WPA');
  assert.equal(wpa[`${P}PROVISIONING_WIFI_PASSWORD`], 'secreta123');
  assert.equal(`${P}PROVISIONING_WIFI_HIDDEN` in wpa, false);
  const open = buildProvisioningExtras({ ...base, wifi: { ssid: 'Libre', security: 'NONE', password: 'x' } });
  assert.equal(`${P}PROVISIONING_WIFI_PASSWORD` in open, false);
  const none = buildProvisioningExtras({ ...base, wifi: { ssid: '   ' } });
  assert.equal(`${P}PROVISIONING_WIFI_SSID` in none, false);
});

test('a hidden network is marked hidden', () => {
  const x = buildProvisioningExtras({ ...base, wifi: { ssid: 'Oculta', password: 'p', hidden: true } });
  assert.equal(x[`${P}PROVISIONING_WIFI_HIDDEN`], true);
});

test('802.1X carries method, phase 2, identities and domain', () => {
  const x = buildProvisioningExtras({
    ...base,
    wifi: {
      ssid: 'Corp', security: 'EAP', password: 'clave',
      eap: { method: 'PEAP', phase2: 'MSCHAPV2', identity: 'inscripcion', anonymousIdentity: 'anon', domain: 'radius.example.com' },
    },
  });
  assert.equal(x[`${P}PROVISIONING_WIFI_SECURITY_TYPE`], 'EAP');
  assert.equal(x[`${P}PROVISIONING_WIFI_EAP_METHOD`], 'PEAP');
  assert.equal(x[`${P}PROVISIONING_WIFI_PHASE2_AUTH`], 'MSCHAPV2');
  assert.equal(x[`${P}PROVISIONING_WIFI_IDENTITY`], 'inscripcion');
  assert.equal(x[`${P}PROVISIONING_WIFI_ANONYMOUS_IDENTITY`], 'anon');
  assert.equal(x[`${P}PROVISIONING_WIFI_DOMAIN`], 'radius.example.com');
  assert.equal(x[`${P}PROVISIONING_WIFI_PASSWORD`], 'clave');
  // EAP fields never leak into a WPA network.
  const wpa = buildProvisioningExtras({ ...base, wifi: { ssid: 'Casa', security: 'WPA', password: 'p', eap: { method: 'PEAP' } } });
  assert.equal(`${P}PROVISIONING_WIFI_EAP_METHOD` in wpa, false);
});

test('a proxy is host + numeric port + bypass, or a PAC address', () => {
  const x = buildProvisioningExtras({ ...base, wifi: { ssid: 'Corp', password: 'p', proxy: { host: 'proxy.local', port: 3128, bypass: 'localhost,*.local' } } });
  assert.equal(x[`${P}PROVISIONING_WIFI_PROXY_HOST`], 'proxy.local');
  assert.equal(x[`${P}PROVISIONING_WIFI_PROXY_PORT`], 3128);
  assert.equal(x[`${P}PROVISIONING_WIFI_PROXY_BYPASS`], 'localhost,*.local');
  const pac = buildProvisioningExtras({ ...base, wifi: { ssid: 'Corp', password: 'p', proxy: { host: 'ignored', pacUrl: 'http://wpad/wpad.dat' } } });
  assert.equal(pac[`${P}PROVISIONING_WIFI_PAC_URL`], 'http://wpad/wpad.dat');
  assert.equal(`${P}PROVISIONING_WIFI_PROXY_HOST` in pac, false);
  // No Wi-Fi, no proxy: the proxy belongs to the provisioning network.
  assert.equal(`${P}PROVISIONING_WIFI_PROXY_HOST` in buildProvisioningExtras(base), false);
});

test('Zero-Touch extras are the QR content without the clock; KME gets the admin extras only', () => {
  const withClock = { ...base, clock: { nowMs: 1795800005123, timeZone: 'America/Bogota', locale: 'es-CO' } };
  const zt = JSON.parse(buildZeroTouchJson(withClock));
  const qr = JSON.parse(buildProvisioningJson(base));
  assert.deepEqual(zt, qr);
  assert.deepEqual(JSON.parse(buildKmeJson(base)), {
    'com.dallycontrol.ENROLL_TOKEN': base.token,
    'com.dallycontrol.SERVER_URL': base.serverUrl,
  });
});

test('the tracked download keeps a short token reference and only same-origin APK paths', () => {
  assert.equal(
    trackedApkUrl(base.apkUrl, base.serverUrl, base.token),
    'https://mdm.example.com/rest/public/agent/v1/apk?t=0b6f6d0e-111&p=%2Ffiles%2Fdallycontrol-agent.apk',
  );
  assert.equal(trackedApkUrl('https://cdn.example.net/agent.apk', base.serverUrl, base.token), 'https://cdn.example.net/agent.apk');
  assert.equal(trackedApkUrl('https://mdm.example.com/files/a/b.apk', base.serverUrl, base.token), 'https://mdm.example.com/files/a/b.apk');
  assert.equal(trackedApkUrl(base.apkUrl, base.serverUrl, 'short'), base.apkUrl);
  assert.equal(trackedApkUrl(base.apkUrl, 'https://mdm.example.com/', 'ABCDEFGH'),
    'https://mdm.example.com/rest/public/agent/v1/apk?t=ABCDEFGH&p=%2Ffiles%2Fdallycontrol-agent.apk');
});

test('QR density: a full payload with Wi-Fi stays scannable at level L and is flagged when it is not', () => {
  const full = buildProvisioningJson({
    ...base,
    apkUrl: trackedApkUrl(base.apkUrl, base.serverUrl, base.token),
    wifi: { ssid: 'Bodega-Principal', password: 'una-clave-bastante-larga-2026', hidden: true },
    clock: { nowMs: 1795800005123, timeZone: 'America/Bogota', locale: 'es-CO' },
  });
  const d = qrDensity(full, 'L');
  assert.ok(d.version > 0 && d.version <= 25, `version ${d.version} for ${d.bytes} bytes`);
  assert.equal(d.modules, 17 + 4 * d.version);
  assert.equal(d.dense, false);
  assert.ok(qrDensity(full, 'M').version > d.version);
  assert.equal(qrDensity('x'.repeat(2000), 'L').dense, true);
  assert.equal(qrDensity('x'.repeat(5000), 'L').version, 0);
  assert.equal(qrDensity('ñ', 'L').bytes, 2);
});
