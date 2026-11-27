import { apiClient, API_BASE } from './client';

// Geofences (console "Geocercas" module): zones with enter/exit alerts, the inside/outside indicator, thresholds,
// alert e-mail recipients and the paginated / exportable location history.
//   /rest/private/geofences/v1/...
// The server evaluates the zones when it stores each position, so this works with every agent version.

const BASE = '/private/geofences/v1';

export type ZoneShape = 'circle' | 'polygon';
export type ZoneRule = 'stayInside' | 'keepOut';

/** When a zone applies: ISO days (1 = Monday … 7 = Sunday; empty = every day) and a local time window. */
export interface ZoneSchedule {
  days: number[];
  from: string;
  to: string;
  tz?: string;
}

export interface Zone {
  id?: number;
  name: string;
  color: string;
  shape: ZoneShape;
  centerLat?: number | null;
  centerLon?: number | null;
  radiusM?: number | null;
  /** Polygon vertices as [lat, lon]. */
  points?: [number, number][] | null;
  rule: ZoneRule;
  active: boolean;
  /** Also alert when the device goes back to where it should be. */
  alertOnReturn: boolean;
  /** Applies to the whole fleet (full administrators only). */
  allDevices: boolean;
  /** JSON text of a ZoneSchedule; null = always. */
  schedule?: string | null;
  /** Extra recipients of this zone's alert e-mails (comma separated). */
  emails?: string | null;
  /** While active, its devices report a position at least this often (minutes). */
  trackingMinutes?: number | null;
  groupIds: number[];
  deviceIds: number[];
  editable?: boolean;
  updatedAt?: number;
}

export interface ZoneState {
  zoneId: number;
  number: string;
  description?: string | null;
  state: 'inside' | 'outside';
  since: number;
  lastFixAt: number;
  /** The device is where the rule says it must not be. */
  violating: boolean;
}

export interface DeviceZone extends Zone {
  state: ZoneState | null;
}

export interface GeofenceSettings {
  /** Positions with a worse accuracy (±m) are ignored by the zones and tagged in the history. */
  maxAccuracyM: number;
  /** Extra metres beyond the border before a crossing counts. */
  hysteresisM: number;
  /** Consecutive positions needed to confirm a crossing. */
  confirmFixes: number;
  /** Days of location history kept; 0 = forever. */
  retentionDays: number;
  /** At most one alert e-mail per recipient in this many minutes. */
  emailIntervalMin: number;
  /** A device silent for longer than this is "disconnected" (Alerts page and alert e-mails). */
  offlineMinutes: number;
}

export interface AlertRecipient {
  id?: number;
  /** null = the whole fleet. */
  groupId: number | null;
  emails: string;
  /** Alert types covered; empty = all. */
  types: string[];
  active: boolean;
}

export interface TrackFix {
  lat: number;
  lon: number;
  accuracy?: number;
  provider?: string;
  capturedAt: number;
  lowAccuracy?: boolean;
}

export interface TrackPage {
  from: number;
  to: number;
  page: number;
  size: number;
  total: number;
  pages: number;
  maxAccuracyM: number;
  fixes: TrackFix[];
}

export function parseSchedule(json?: string | null): ZoneSchedule | null {
  if (!json) return null;
  try {
    const s = JSON.parse(json) as ZoneSchedule;
    return { days: Array.isArray(s.days) ? s.days : [], from: s.from || '00:00', to: s.to || '23:59', tz: s.tz };
  } catch {
    return null;
  }
}

const enc = encodeURIComponent;

export const listZones = (signal?: AbortSignal) => apiClient.get<Zone[]>(`${BASE}/zones`, signal);
export const saveZone = (z: Zone) =>
  z.id ? apiClient.put<Zone>(`${BASE}/zones/${z.id}`, z) : apiClient.post<Zone>(`${BASE}/zones`, z);
export const deleteZone = (id: number) => apiClient.del<void>(`${BASE}/zones/${id}`);
export const listZoneStates = (signal?: AbortSignal) => apiClient.get<ZoneState[]>(`${BASE}/states`, signal);
export const listDeviceZones = (number: string) => apiClient.get<DeviceZone[]>(`${BASE}/devices/${enc(number)}/zones`);

export const getGeofenceSettings = () => apiClient.get<GeofenceSettings>(`${BASE}/settings`);
export const saveGeofenceSettings = (s: GeofenceSettings) => apiClient.put<GeofenceSettings>(`${BASE}/settings`, s);

export const getMailStatus = () => apiClient.get<{ configured: boolean }>(`${BASE}/mail`);
export const sendTestMail = (email: string) => apiClient.post<void>(`${BASE}/mail/test`, { email });

export const listRecipients = () => apiClient.get<AlertRecipient[]>(`${BASE}/recipients`);
export const saveRecipient = (r: AlertRecipient) =>
  r.id ? apiClient.put<void>(`${BASE}/recipients/${r.id}`, r) : apiClient.post<void>(`${BASE}/recipients`, r);
export const deleteRecipient = (id: number) => apiClient.del<void>(`${BASE}/recipients/${id}`);

/** One page of a device's location history in [from, to]; newest first. */
export const getTrack = (number: string, from: number, to: number, page = 0, size = 1000, signal?: AbortSignal) =>
  apiClient.get<TrackPage>(
    `${BASE}/devices/${enc(number)}/track?from=${from}&to=${to}&page=${page}&size=${size}&order=desc`, signal);

/** Download the device's route in [from, to] as Excel or CSV. */
export async function downloadTrack(number: string, from: number, to: number, format: 'csv' | 'xlsx'): Promise<void> {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Bogota';
  const url = `${API_BASE}${BASE}/devices/${enc(number)}/track/export?format=${format}&from=${from}&to=${to}&tz=${enc(tz)}`;
  const res = await fetch(url, { credentials: 'include' });
  if (!res.ok) throw new Error(`No se pudo generar el archivo (${res.status})`);
  const blob = await res.blob();
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `recorrido.${format}`;
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 10_000);
}
