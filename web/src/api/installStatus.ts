// "Instalación en la flota": where the installation of an app stands on each phone ordered to install it.
// Server: AppInstallResource (@Path /private/agent/v1/apps/install).
import { apiClient } from './client';

export type InstallState = 'installed' | 'pending' | 'installing' | 'held' | 'failed' | 'not_queued';

export interface InstallProblem {
  state: InstallState;
  /** storage, signature, downgrade, blocked, invalid, checksum, incompatible, timeout, notOwner, noCapabilities… */
  reasonCode?: string | null;
  /** The reason, in Spanish. */
  reason?: string | null;
  /** What the phone answered, verbatim. */
  detail?: string | null;
  commandId?: number | null;
  /** Failed attempts in a row. */
  attempts: number;
  /** When the next automatic retry is due (epoch ms); null = there will be none. */
  nextRetryAt?: number | null;
  retryable: boolean;
  at?: number | null;
  targetVersionCode: number;
}

export interface InstallDeviceRow extends InstallProblem {
  deviceId: number;
  number: string;
  description?: string | null;
  group?: string | null;
  lastSeen?: number | null;
  /** The version of the app the phone last reported in its inventory. */
  installedVersion?: string | null;
  deviceOwner: boolean;
  /** The policy (its app list or an app group) assigns the app to this phone; false = only a manual order. */
  assigned: boolean;
  /** The name of the version the policy assigns (when assigned). */
  targetVersion?: string | null;
}

export interface InstallFleetStatus {
  packageName: string;
  /** The version the policy assigns (when no policy assigns the app: the newest one ordered by hand). */
  targetVersionCode: number;
  targetVersion?: string | null;
  /** How many of the phones listed have the app assigned by their policy. */
  assigned: number;
  total: number;
  counts: Record<'installed' | 'pending' | 'installing' | 'held' | 'failed', number>;
  devices: InstallDeviceRow[];
}

/** A device's install that needs attention (held or failed). */
export interface DeviceInstallProblem extends InstallProblem {
  packageName: string;
}

const BASE = '/private/agent/v1/apps/install';

export function getInstallFleetStatus(packageName: string, signal?: AbortSignal): Promise<InstallFleetStatus> {
  return apiClient.get<InstallFleetStatus>(`${BASE}/status?pkg=${encodeURIComponent(packageName)}`, signal);
}

export function getDeviceInstallProblems(deviceNumber: string, signal?: AbortSignal): Promise<DeviceInstallProblem[]> {
  return apiClient.get<DeviceInstallProblem[]>(`${BASE}/device/${encodeURIComponent(deviceNumber)}`, signal);
}

/** Queue the install again where it failed: on these devices, or on every one when none is given. */
export async function retryInstall(packageName: string, deviceNumbers?: string[]): Promise<number> {
  const r = await apiClient.post<{ queued: number }>(`${BASE}/retry`, { packageName, deviceNumbers: deviceNumbers ?? [] });
  return r?.queued ?? 0;
}

export const INSTALL_STATE_LABEL: Record<InstallState, string> = {
  installed: 'Instalada',
  pending: 'Pendiente',
  installing: 'Instalando',
  held: 'Retenida',
  failed: 'Fallida',
  not_queued: 'Sin orden',
};

export const INSTALL_STATE_TONE: Record<InstallState, string> = {
  installed: 'tone-ok',
  pending: '',
  installing: '',
  held: 'tone-warn',
  failed: 'tone-alert',
  not_queued: 'tone-warn',
};

function when(ms?: number | null): string {
  return ms ? new Date(ms).toLocaleString('es-CO') : '';
}

/** "Se reintentará sola el 27/11 15:40" / "Sin más reintentos automáticos". */
export function retryNote(r: InstallProblem): string {
  if (r.state !== 'failed') return '';
  if (r.nextRetryAt) return `Intento ${r.attempts}. Se reintentará sola: ${when(r.nextRetryAt)}.`;
  return r.retryable
    ? `${r.attempts} intentos fallidos: ya no se reintenta sola. Pulsa Reintentar cuando esté resuelto.`
    : 'No se reintenta sola: hay que corregir la causa y pulsar Reintentar.';
}

function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v);
  return /[";\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** The view as a CSV (semicolons and a BOM, so Excel in Spanish opens it straight). */
export function installStatusCsv(st: InstallFleetStatus): string {
  const head = ['Equipo', 'Descripción', 'Carpeta', 'Estado', 'Motivo', 'Respuesta del equipo', 'Intentos',
    'Próximo reintento', 'versionCode objetivo', 'Versión reportada', 'Device Owner', 'Última conexión', 'Fecha del estado',
    'Asignada por política'];
  const lines = st.devices.map((d) => [
    d.number, d.description, d.group, INSTALL_STATE_LABEL[d.state] ?? d.state, d.reason, d.detail, d.attempts,
    when(d.nextRetryAt), d.targetVersionCode || '', d.installedVersion, d.deviceOwner ? 'Sí' : 'No', when(d.lastSeen), when(d.at),
    d.assigned ? 'Sí' : 'No (orden manual)',
  ].map(csvCell).join(';'));
  return `﻿${[head.join(';'), ...lines].join('\r\n')}\r\n`;
}

export function downloadInstallStatusCsv(st: InstallFleetStatus): void {
  const blob = new Blob([installStatusCsv(st)], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `instalacion-${st.packageName}-${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
