// The Android provisioning payload (what a QR carries, and what Zero-Touch / Samsung KME are given as "DPC extras").
// Pure: no browser or build-time access here, so it is unit-tested with plain Node (web/tests/payload.test.ts).
// provisioning.ts binds it to this deployment (agent package, checksum, APK address).

const P = 'android.app.extra.';

export type WifiSecurity = 'WPA' | 'WEP' | 'NONE' | 'EAP';
export type EapMethod = 'PEAP' | 'TTLS' | 'TLS' | 'PWD' | 'SIM' | 'AKA' | 'AKA_PRIME';
export type EapPhase2 = 'NONE' | 'PAP' | 'MSCHAP' | 'MSCHAPV2' | 'GTC';

/** 802.1X (Android 10+). The server certificate is checked against the phone's system CAs for [domain]. */
export interface WifiEap {
  method: EapMethod;
  phase2?: EapPhase2;
  identity?: string;
  anonymousIdentity?: string;
  domain?: string;
}

/**
 * Wi-Fi the phone joins DURING provisioning (before it downloads the agent). The password travels in the QR in
 * plaintext: that is inherent to Android QR provisioning.
 */
export interface WifiConfig {
  ssid: string;
  password?: string;
  security?: WifiSecurity;
  /** The network does not broadcast its name: without this the phone never finds it and provisioning stalls. */
  hidden?: boolean;
  eap?: WifiEap;
  proxy?: WifiProxy;
}

/** HTTP proxy of that Wi-Fi (host + port, or a PAC address). */
export interface WifiProxy {
  host?: string;
  port?: number;
  bypass?: string;
  pacUrl?: string;
}

/** The moment and place the QR is shown: sets the clock, time zone and language of a phone fresh out of the box. */
export interface ProvisioningClock {
  nowMs: number;
  /** IANA zone, e.g. America/Bogota. */
  timeZone?: string;
  /** BCP-47 tag as the browser reports it (es-CO); written the way Android wants it (es_CO). */
  locale?: string;
}

export interface ProvisioningOptions {
  /** <package>/<receiver class> of the agent. */
  adminComponent: string;
  /** URL-safe base64 SHA-256 of the agent's signing certificate. */
  signatureChecksum: string;
  /** Where Android downloads the agent APK from. */
  apkUrl: string;
  /** The console's origin: the agent enrolls against it. */
  serverUrl: string;
  /** Single-use token or a folder's reusable code. */
  token: string;
  wifi?: WifiConfig;
  clock?: ProvisioningClock;
}

/** What the agent reads from the admin extras bundle (also the whole "custom JSON" Samsung KME asks for). */
export function adminExtras(o: Pick<ProvisioningOptions, 'token' | 'serverUrl'>): Record<string, string> {
  return {
    'com.dallycontrol.ENROLL_TOKEN': o.token,
    'com.dallycontrol.SERVER_URL': o.serverUrl,
  };
}

/** es-CO -> es_CO. Only a tag with a region is sent: a bare language would reset the country the person chose. */
export function androidLocale(tag?: string): string | undefined {
  const m = /^([a-z]{2,3})[-_]([A-Za-z]{2})$/.exec((tag ?? '').trim());
  return m ? `${m[1].toLowerCase()}_${m[2].toUpperCase()}` : undefined;
}

/** The provisioning extras as an object (keys in the order Android's documentation lists them). */
export function buildProvisioningExtras(o: ProvisioningOptions): Record<string, unknown> {
  const out: Record<string, unknown> = {
    [`${P}PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME`]: o.adminComponent,
    [`${P}PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`]: o.signatureChecksum,
    [`${P}PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION`]: o.apkUrl,
    [`${P}PROVISIONING_ADMIN_EXTRAS_BUNDLE`]: adminExtras(o),
    [`${P}PROVISIONING_SKIP_ENCRYPTION`]: true,
    [`${P}PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED`]: true,
    // Android 13+ first tries to update Google's own device-policy app from Google Play and, when that cannot be
    // done (no Play, Play unreachable from the provisioning Wi-Fi, a maker's build without it), stops with "Can't
    // set up device" before it even downloads the agent. This agent does not need that app.
    [`${P}PROVISIONING_ALLOW_OFFLINE`]: true,
  };
  const c = o.clock;
  if (c) {
    // A phone that has been in a box for months boots with its factory date; with that date every HTTPS certificate
    // looks "not valid yet" and the agent download fails. Android sets its clock from this before downloading.
    out[`${P}PROVISIONING_LOCAL_TIME`] = Math.round(c.nowMs);
    if (c.timeZone) out[`${P}PROVISIONING_TIME_ZONE`] = c.timeZone;
    const locale = androidLocale(c.locale);
    if (locale) out[`${P}PROVISIONING_LOCALE`] = locale;
  }
  const ssid = o.wifi?.ssid?.trim();
  if (ssid && o.wifi) Object.assign(out, wifiExtras(ssid, o.wifi));
  return out;
}

function wifiExtras(ssid: string, w: WifiConfig): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const security: WifiSecurity = w.security ?? (w.eap ? 'EAP' : w.password ? 'WPA' : 'NONE');
  out[`${P}PROVISIONING_WIFI_SSID`] = ssid;
  out[`${P}PROVISIONING_WIFI_SECURITY_TYPE`] = security;
  if (w.hidden) out[`${P}PROVISIONING_WIFI_HIDDEN`] = true;
  if (security !== 'NONE' && w.password) out[`${P}PROVISIONING_WIFI_PASSWORD`] = w.password;
  if (security === 'EAP' && w.eap) {
    out[`${P}PROVISIONING_WIFI_EAP_METHOD`] = w.eap.method;
    if (w.eap.phase2 && w.eap.phase2 !== 'NONE') out[`${P}PROVISIONING_WIFI_PHASE2_AUTH`] = w.eap.phase2;
    if (w.eap.identity?.trim()) out[`${P}PROVISIONING_WIFI_IDENTITY`] = w.eap.identity.trim();
    if (w.eap.anonymousIdentity?.trim()) out[`${P}PROVISIONING_WIFI_ANONYMOUS_IDENTITY`] = w.eap.anonymousIdentity.trim();
    if (w.eap.domain?.trim()) out[`${P}PROVISIONING_WIFI_DOMAIN`] = w.eap.domain.trim();
  }
  const x = w.proxy;
  if (x?.pacUrl?.trim()) {
    out[`${P}PROVISIONING_WIFI_PAC_URL`] = x.pacUrl.trim();
  } else if (x?.host?.trim()) {
    out[`${P}PROVISIONING_WIFI_PROXY_HOST`] = x.host.trim();
    out[`${P}PROVISIONING_WIFI_PROXY_PORT`] = x.port && x.port > 0 && x.port < 65536 ? Math.round(x.port) : 8080;
    if (x.bypass?.trim()) out[`${P}PROVISIONING_WIFI_PROXY_BYPASS`] = x.bypass.trim();
  }
  return out;
}

/** The JSON text a QR carries. */
export function buildProvisioningJson(o: ProvisioningOptions): string {
  return JSON.stringify(buildProvisioningExtras(o));
}

/**
 * "DPC extras" for the Zero-Touch portal: the same content as the QR without the clock (Zero-Touch phones get the
 * time from the network before they contact Google) and pretty-printed, because a person pastes it into a form.
 */
export function buildZeroTouchJson(o: ProvisioningOptions): string {
  return JSON.stringify(buildProvisioningExtras({ ...o, clock: undefined }), null, 2);
}

/** Samsung Knox Mobile Enrollment "Custom JSON data": the admin extras only (the profile holds APK and component). */
export function buildKmeJson(o: Pick<ProvisioningOptions, 'token' | 'serverUrl'>): string {
  return JSON.stringify(adminExtras(o), null, 2);
}

/**
 * The agent download address with a short reference to the token, routed through the server so the console can tell
 * "the phone downloaded the agent but never enrolled" from "the phone never got as far as the download".
 * [apkUrl] must be on [serverUrl]'s origin; otherwise it is returned unchanged.
 */
export function trackedApkUrl(apkUrl: string, serverUrl: string, token: string): string {
  const origin = serverUrl.replace(/\/+$/, '');
  if (!apkUrl.startsWith(`${origin}/`)) return apkUrl;
  const path = apkUrl.slice(origin.length);
  const ref = token.replace(/[^A-Za-z0-9-]/g, '').slice(0, 12);
  if (!/^\/(files|update)\/[A-Za-z0-9._-]+\.apk$/.test(path) || ref.length < 8) return apkUrl;
  return `${origin}/rest/public/agent/v1/apk?t=${ref}&p=${encodeURIComponent(path)}`;
}

// --- QR density -------------------------------------------------------------------------------------------------

// Byte capacity of QR versions 1..40 in byte mode, per error-correction level (ISO/IEC 18004).
const CAPACITY: Record<'L' | 'M', number[]> = {
  L: [17, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 367, 425, 458, 520, 586, 644, 718, 792, 858, 929, 1003, 1091,
    1171, 1273, 1367, 1465, 1528, 1628, 1732, 1840, 1952, 2068, 2188, 2303, 2431, 2563, 2699, 2809, 2953],
  M: [14, 26, 42, 62, 84, 106, 122, 152, 180, 213, 251, 287, 331, 362, 412, 450, 504, 560, 624, 666, 711, 779, 857,
    911, 997, 1059, 1125, 1190, 1264, 1370, 1452, 1538, 1628, 1722, 1809, 1911, 1989, 2099, 2213, 2331],
};

export interface QrDensity {
  /** QR version 1..40 (0 = does not fit at all). */
  version: number;
  /** Modules per side, quiet zone not included. */
  modules: number;
  /** UTF-8 bytes of the payload. */
  bytes: number;
  /** A phone camera struggles from about this size on: say so on screen. */
  dense: boolean;
}

/** How dense the QR for [text] is at [level]. On a screen L is right: nothing gets torn or stained there. */
export function qrDensity(text: string, level: 'L' | 'M' = 'L'): QrDensity {
  const bytes = new TextEncoder().encode(text).length;
  const idx = CAPACITY[level].findIndex((cap) => cap >= bytes);
  const version = idx < 0 ? 0 : idx + 1;
  const modules = version ? 17 + 4 * version : 0;
  return { version, modules, bytes, dense: version === 0 || version > 25 };
}
