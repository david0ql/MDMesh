import { apiClient } from './client';

// Mint an enrollment token (see server agent v1 contract):
//   POST /rest/private/agent/v1/token  ->  { token, ... }
// The token is embedded into the device's QR provisioning bundle. The exact
// shape is not strongly typed server-side here, so we read defensively.

export interface EnrollTokenResponse {
  token?: string;
  expiresAt?: number;
  [key: string]: unknown;
}

/**
 * groupId puts the enrolled device in that group (company); it then runs the group's configuration, or the
 * global one. configurationId would pin a configuration on the device instead (device level wins over both).
 */
export async function mintEnrollToken(
  opts: { groupId?: number; configurationId?: number; expectedPhone?: string; accountEmail?: string } = {},
): Promise<EnrollTokenResponse> {
  return apiClient.post<EnrollTokenResponse>('/private/agent/v1/token', {
    ...(opts.groupId ? { groupId: opts.groupId } : {}),
    ...(opts.configurationId ? { configurationId: opts.configurationId } : {}),
    ...(opts.expectedPhone && opts.expectedPhone.trim() ? { expectedPhone: opts.expectedPhone.trim() } : {}),
    ...(opts.accountEmail && opts.accountEmail.trim() ? { accountEmail: opts.accountEmail.trim() } : {}),
  });
}

/** A folder's reusable enrollment code (see AgentAdminResource /codes). Shown as ABCD-EFGH. */
export interface EnrollmentCode {
  id: number;
  code: string;
  label: string | null;
  groupId: number | null;
  groupName: string | null;
  uses: number;
  revoked: boolean;
  createdAt: number;
  expiresAt: number | null;
  wifiSsid?: string | null;
  wifiPassword?: string | null;
  wifiSecurity?: 'WPA' | 'WEP' | 'NONE' | null;
  /** Email account tagged onto devices enrolled via this code (metadata/identification only). */
  accountEmail?: string | null;
  /** The Wi-Fi does not broadcast its name (PROVISIONING_WIFI_HIDDEN). */
  wifiHidden?: boolean;
}

export const displayCode = (c: string) => (c.length === 8 ? `${c.slice(0, 4)}-${c.slice(4)}` : c);

export const listEnrollmentCodes = () => apiClient.get<EnrollmentCode[]>('/private/agent/v1/codes');

export const createEnrollmentCode = (
  groupId: number, label?: string, expiresAt?: number,
  wifi?: { ssid: string; password?: string; security: 'WPA' | 'WEP' | 'NONE'; hidden?: boolean },
  accountEmail?: string,
) =>
  apiClient.post<EnrollmentCode>('/private/agent/v1/codes', {
    groupId,
    ...(label ? { label } : {}),
    ...(expiresAt ? { expiresAt } : {}),
    ...(wifi?.ssid ? { wifiSsid: wifi.ssid, wifiPassword: wifi.password ?? '', wifiSecurity: wifi.security, wifiHidden: !!wifi.hidden } : {}),
    ...(accountEmail && accountEmail.trim() ? { accountEmail: accountEmail.trim() } : {}),
  });

export const revokeEnrollmentCode = (id: number) => apiClient.del<void>(`/private/agent/v1/codes/${id}`);

/** Remove a revoked code from the list. */
export const deleteEnrollmentCode = (id: number) => apiClient.del<void>(`/private/agent/v1/codes/${id}?purge=true`);

// --- Enrollment reliability: what became of a token, and the server's log of attempts ---------------------------

/** One row of the server's enrollment-attempts log (AgentEnrollAttempt). */
export interface EnrollAttempt {
  id: number;
  tokenId?: number;
  groupId?: number;
  at: number;
  lastAt: number;
  count: number;
  /** ok | failed | download (the phone fetched the agent) | note (a failure the phone had before reaching the server). */
  outcome: 'ok' | 'failed' | 'download' | 'note' | string;
  reason: string;
  detail?: string;
  path?: string;
  manufacturer?: string;
  model?: string;
  androidRelease?: string;
  sdkInt?: number;
  agentVersion?: string;
  hardwareId?: string;
  serial?: string;
  ip?: string;
  deviceNumber?: string;
  tries?: number;
  groupName?: string;
  tokenLabel?: string;
  reusable?: boolean;
}

export interface TokenStatus {
  used: boolean;
  usedAt?: number;
  expiresAt?: number;
  expired: boolean;
  deviceNumber?: string;
  deviceId?: number;
  configurationName?: string;
  attempts: EnrollAttempt[];
}

export const getTokenStatus = (token: string) =>
  apiClient.get<TokenStatus>(`/private/agent/v1/token/status?token=${encodeURIComponent(token)}`);

export const listEnrollAttempts = (limit = 100) =>
  apiClient.get<EnrollAttempt[]>(`/private/agent/v1/enroll/attempts?limit=${limit}`);

const REASONS: Record<string, string> = {
  enrolled: 'Inscrito',
  'agent.download': 'Descargó el agente',
  'token.used': 'El QR ya lo usó otro equipo',
  'token.expired': 'El código venció',
  'token.invalid': 'Código desconocido o revocado',
  'enrollment.disabled': 'El servidor no permite crear dispositivos',
  'enrollment.internal': 'Error interno del servidor',
  'device.clock': 'Fecha y hora del teléfono incorrectas',
  'device.no-network': 'El teléfono no tenía conexión con el servidor',
  'device.timeout': 'El servidor no respondió a tiempo al teléfono',
  'device.captive-portal': 'Wi‑Fi con portal cautivo o que bloquea el servidor',
  'device.tls': 'Fallo de conexión segura (certificado)',
  'device.server-error': 'El servidor respondió con error',
  'device.no-token': 'El teléfono no recibió código de inscripción',
  'device.token.used': 'El QR ya lo usó otro equipo',
  'device.token.expired': 'El código venció',
  'device.token.invalid': 'Código desconocido o revocado',
  'device.rejected': 'El servidor rechazó la inscripción',
  'device.unknown': 'Error no clasificado en el teléfono',
};

/** The attempt's reason in words. */
export function attemptReason(a: Pick<EnrollAttempt, 'reason'>): string {
  return REASONS[a.reason] ?? a.reason;
}

const PATHS: Record<string, string> = {
  qr: 'QR',
  'qr-modern': 'QR / Zero‑Touch (Android 12+)',
  'qr-legacy': 'QR (Android 7–11)',
  adb: 'Cable (USB)',
  code: 'Código escrito',
  unknown: '—',
};

export function pathLabel(path?: string): string {
  return path ? PATHS[path] ?? path : '—';
}

const FIVE_MIN = 5 * 60 * 1000;

/**
 * What to do about an attempt, when the row alone does not say it. The telling case: the phone downloaded the agent
 * and then nothing - Android refused to install it or to make it the device's owner, which the server never sees.
 */
export function attemptHint(a: EnrollAttempt, all: EnrollAttempt[], now: number): string | null {
  if (a.outcome === 'download') {
    const followed = all.some((b) => b.outcome !== 'download' && b.lastAt >= a.at && b.tokenId === a.tokenId
      && (!a.reusable || b.ip === a.ip));
    if (followed || now - a.lastAt < FIVE_MIN) return null;
    return 'Descargó el agente pero no llegó a inscribirse. Android no lo instaló o no lo dejó como propietario: revisa '
      + 'que el equipo estuviera recién formateado y sin cuenta previa (FRP), que Play Protect no lo haya bloqueado, y '
      + 'que la firma del APK publicado coincida con la del QR.';
  }
  switch (a.reason) {
    case 'token.used': return 'Genera un QR nuevo para este equipo, o usa un código de carpeta (sirve para varios).';
    case 'token.expired': return 'El QR de un solo uso vale 24 horas. Genera uno nuevo o usa un código de carpeta.';
    case 'token.invalid': return 'El equipo trae un código que no existe o fue revocado (QR viejo o configuración Zero‑Touch/KME desactualizada).';
    case 'device.clock': return 'El equipo arrancó con la fecha de fábrica. Escanea el QR desde la pantalla (lleva la hora) o corrige la fecha.';
    case 'device.captive-portal': return 'Usa una red sin página de inicio de sesión para inscribir.';
    default: return null;
  }
}
