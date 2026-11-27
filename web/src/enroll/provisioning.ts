// Builds the Android QR provisioning payload for the DallyControl agent. Crucially it carries the
// SERVER_URL extra, so one prebuilt APK works for any deployment (the agent reads it at enrollment).
// The payload itself is built (and unit-tested) in payload.ts; this file binds it to this deployment.
//
// Deploy-specific values default to the shipped debug build and can be overridden at web-build time:
//   VITE_AGENT_PACKAGE     applicationId of the distributed APK (default com.dallycontrol.agent.debug)
//   VITE_AGENT_CHECKSUM    PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM of the signing cert
//   VITE_AGENT_APK_URL     where the APK is hosted (default <origin>/files/agent.apk)
//   VITE_AGENT_APK_TRACK   "0" = the QR points straight at the APK instead of the server's tracked download

import {
  buildAgentQrJson, buildKmeJson, buildProvisioningJson, buildZeroTouchJson, trackedApkUrl,
  type ProvisioningClock, type ProvisioningOptions, type WifiConfig,
} from './payload';

export type { WifiConfig, WifiSecurity, WifiEap, WifiProxy, EapMethod, EapPhase2 } from './payload';
export { qrDensity } from './payload';

const env = import.meta.env as Record<string, string | undefined>;

const AGENT_PACKAGE = env.VITE_AGENT_PACKAGE || 'com.dallycontrol.agent.debug';
const ADMIN_COMPONENT = `${AGENT_PACKAGE}/com.dallycontrol.agent.admin.AdminReceiver`;
const SIGNATURE_CHECKSUM = env.VITE_AGENT_CHECKSUM || 'YMEWhg_-ydciRFgtdOZbQ-W_aPPz7orHKuqfn13Ujaw';
const TRACK_DOWNLOAD = env.VITE_AGENT_APK_TRACK !== '0';

/** Server origin — same-origin in production (Caddy serves the SPA and proxies the API). */
export function serverBaseUrl(): string {
  return window.location.origin;
}

export function agentApkUrl(): string {
  // VITE_AGENT_APK_URL may be absolute (https://…) or origin-relative (/update/agent.apk → resolved
  // against this deployment's origin). Release builds set it to the supervisor's verified APK mirror.
  const u = env.VITE_AGENT_APK_URL;
  if (u) return u.startsWith('/') ? `${serverBaseUrl()}${u}` : u;
  return `${serverBaseUrl()}/files/agent.apk`;
}

export function agentPackage(): string {
  return AGENT_PACKAGE;
}

export function agentAdminComponent(): string {
  return ADMIN_COMPONENT;
}

export function agentChecksum(): string {
  return SIGNATURE_CHECKSUM;
}

/** Time zone and language of this browser: the operator showing the QR is where the phone is. */
export function clockNow(nowMs: number = Date.now()): ProvisioningClock {
  let timeZone: string | undefined;
  try { timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || undefined; } catch { timeZone = undefined; }
  return { nowMs, timeZone, locale: typeof navigator !== 'undefined' ? navigator.language : undefined };
}

export interface PayloadExtras {
  /**
   * The current time, for a QR shown on screen (it is re-drawn as time passes). Leave it out for a QR that will be
   * printed or sent: a stale time would set the phone's clock BACK to the day it was printed, and a certificate
   * issued after that day would then be rejected.
   */
  clock?: ProvisioningClock;
}

function options(token: string, wifi?: WifiConfig, extras: PayloadExtras = {}): ProvisioningOptions {
  const serverUrl = serverBaseUrl();
  const apk = agentApkUrl();
  return {
    adminComponent: ADMIN_COMPONENT,
    signatureChecksum: SIGNATURE_CHECKSUM,
    apkUrl: TRACK_DOWNLOAD ? trackedApkUrl(apk, serverUrl, token) : apk,
    serverUrl,
    token,
    wifi,
    clock: extras.clock,
  };
}

/** The provisioning JSON a fresh device scans (6-tap → QR scanner). Pass `wifi` to pre-connect it. */
export function buildProvisioningPayload(token: string, wifi?: WifiConfig, extras: PayloadExtras = {}): string {
  return buildProvisioningJson(options(token, wifi, extras));
}

/** The small QR the agent itself scans on a phone that is managed but not enrolled (see payload.ts). */
export function agentScanPayload(token: string): string {
  return buildAgentQrJson({ token, serverUrl: serverBaseUrl() });
}

/** "DPC extras" JSON for the Zero-Touch portal (same content as the QR, release package). */
export function zeroTouchExtras(token: string, wifi?: WifiConfig): string {
  return buildZeroTouchJson(options(token, wifi));
}

/** "Custom JSON data" for a Samsung Knox Mobile Enrollment profile. */
export function kmeCustomJson(token: string): string {
  return buildKmeJson({ token, serverUrl: serverBaseUrl() });
}
