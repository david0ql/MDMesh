import { API_BASE, apiClient } from './client';

// Remote support sessions (ADR 0012): the agent's own screen, touch, voice, chat and files over one WebSocket
// that the server relays. This is the REST side: what a device offers, opening and closing a session, the log.
//   GET  /rest/private/support/v1/devices/{n}            status
//   POST /rest/private/support/v1/devices/{n}/start      { viewOnly } → key + token for the console socket
//   POST /rest/private/support/v1/devices/{n}/stop
//   GET  /rest/private/support/v1/devices/{n}/sessions   the device's sessions
//   GET  /rest/private/support/v1/sessions               every session the user reaches
//   GET  /rest/private/support/v1/sessions/{id}/events   chat lines, files, calls, actions

export type SupportScreen = 'ready' | 'needsAuthorization' | 'unsupported' | 'disabled';
export type SupportReadiness = 'ready' | 'needsAuthorization' | 'disabled' | 'unsupported';

/** What the agent reported about its session channel. Empty for agents older than it. */
export interface SupportCapability {
  session?: boolean;
  screen?: SupportScreen;
  control?: boolean;
  voice?: 'ready' | 'needsPermission' | string;
  capture?: 'a11y' | 'projection' | 'none';
  selfEnable?: boolean;
}

export interface SupportLive {
  sessionKey: string;
  id: number;
  viewOnly: boolean;
  deviceConnected: boolean;
}

export interface SupportStatus {
  support: SupportCapability;
  readiness: SupportReadiness;
  /** The legacy droidVNC-NG tier. */
  vncTier: 'none' | 'view' | 'control';
  online: boolean;
  powerMode?: string | null;
  locked?: boolean | null;
  lastSeen?: number | null;
  live?: SupportLive;
}

export interface SupportStart {
  id: number;
  sessionKey: string;
  /** The operator's secret for the console socket. */
  token: string;
  /** Path of the console socket, e.g. /agent/ws/support/console/<key>. */
  socket: string;
  viewOnly: boolean;
  /** The agent's live socket was up: it joins at once. Otherwise at its next check-in. */
  online: boolean;
  commandId?: number | string;
  support: SupportCapability;
}

export interface SupportSessionRow {
  id: number;
  deviceNumber: string;
  deviceName?: string | null;
  user?: string | null;
  viewOnly?: boolean | null;
  engine?: string | null;
  startedAt?: number | null;
  connectedAt?: number | null;
  endedAt?: number | null;
  endReason?: string | null;
  durationSec?: number | null;
  chatCount?: number | null;
  fileCount?: number | null;
  voiceSeconds?: number | null;
  inputCount?: number | null;
  live?: boolean;
}

export interface SupportEvent {
  id: number;
  sessionId: number;
  ts: number;
  kind: 'chat' | 'file' | 'call' | 'clipboard' | 'action' | string;
  origin: 'console' | 'device' | string;
  detail?: string | null;
}

const dev = (n: string) => `/private/support/v1/devices/${encodeURIComponent(n)}`;

export async function getSupportStatus(deviceNumber: string, signal?: AbortSignal): Promise<SupportStatus> {
  const s = await apiClient.get<SupportStatus>(dev(deviceNumber), signal);
  return { ...s, support: s?.support ?? {} };
}

export async function startSupportSession(deviceNumber: string, viewOnly: boolean): Promise<SupportStart> {
  return apiClient.post<SupportStart>(`${dev(deviceNumber)}/start`, { viewOnly });
}

export async function stopSupportSession(deviceNumber: string): Promise<void> {
  await apiClient.post(`${dev(deviceNumber)}/stop`, {});
}

/** The stop call that survives the page going away (tab closed, navigation). Best effort. */
export function stopSupportSessionOnUnload(deviceNumber: string): void {
  try {
    void fetch(`${API_BASE}${dev(deviceNumber)}/stop`, {
      method: 'POST', keepalive: true, credentials: 'include',
      headers: { 'Content-Type': 'application/json' }, body: '{}',
    }).catch(() => undefined);
  } catch { /* best effort */ }
}

export async function listDeviceSupportSessions(deviceNumber: string, limit = 50): Promise<SupportSessionRow[]> {
  return (await apiClient.get<SupportSessionRow[]>(`${dev(deviceNumber)}/sessions?limit=${limit}`)) ?? [];
}

export async function listSupportSessions(q: { from?: number; to?: number; limit?: number } = {}): Promise<SupportSessionRow[]> {
  const p = new URLSearchParams();
  if (q.from != null) p.set('from', String(q.from));
  if (q.to != null) p.set('to', String(q.to));
  if (q.limit != null) p.set('limit', String(q.limit));
  const qs = p.toString();
  return (await apiClient.get<SupportSessionRow[]>(`/private/support/v1/sessions${qs ? `?${qs}` : ''}`)) ?? [];
}

export async function listSupportEvents(sessionId: number): Promise<SupportEvent[]> {
  return (await apiClient.get<SupportEvent[]>(`/private/support/v1/sessions/${sessionId}/events`)) ?? [];
}

// --- labels shared by the device history and the fleet page ---------------------------------------------------------

/** How a session ended, in plain Spanish. */
export function endReasonLabel(reason?: string | null): string {
  switch (reason) {
    case 'console': return 'La cerró soporte';
    case 'device': return 'La cerró el teléfono';
    case 'replaced': return 'Reemplazada por otra sesión';
    case 'timeout': return 'El teléfono no se conectó';
    case 'idle': return 'Inactividad';
    case 'network': return 'Se perdió la conexión';
    case null: case undefined: case '': return '—';
    default: return reason;
  }
}

export function engineLabel(engine?: string | null): string {
  switch (engine) {
    case 'builtin': return 'Agente';
    case 'droidvnc': return 'droidVNC-NG';
    case 'none': case null: case undefined: case '': return 'Sin pantalla';
    default: return engine;
  }
}

/** 75 → "1 min 15 s". */
export function fmtDuration(sec?: number | null): string {
  if (sec == null || !Number.isFinite(sec)) return '—';
  const s = Math.max(0, Math.round(sec));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${s % 60} s`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}
