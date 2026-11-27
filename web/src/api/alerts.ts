import { apiClient, API_BASE } from './client';
import type { ReportColumn, ReportRow } from './reports';

// Fleet alerts (console "Alertas" module).
//   /rest/private/alerts/v1/...
// Three alert kinds — disconnected, kioskExit, simChange — derived on the server and persisted so each can be
// marked "revisada". The list returns the same typed columns + rows shape as the reports module (so the export
// stays identical), plus per-row review fields the page uses for its checkbox.

const BASE = '/private/alerts/v1';

export type AlertType = 'disconnected' | 'kioskExit' | 'simChange' | 'geofenceExit' | 'geofenceEnter';
export type AlertStatus = 'reviewed' | 'pending';

/** One alert row. Carries the generic report cells plus the review bookkeeping. */
export interface AlertRow extends ReportRow {
  id: number;
  type: AlertType;
  tipo: string;
  name?: string;
  number?: string;
  group?: string;
  createdat?: number;
  detail?: string;
  estado: string;
  reviewed: boolean;
  reviewedby?: number | null;
  reviewedat?: number | null;
  reviewedbyname?: string;
}

export interface AlertsResult {
  report: string;
  title: string;
  generatedAt: number;
  columns: ReportColumn[];
  rows: AlertRow[];
}

export interface AlertFilters {
  type?: AlertType | '';
  from?: number;
  to?: number;
  status?: AlertStatus | '';
  /** Disconnection threshold, minutes. */
  minutes?: number;
}

const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Bogota';

function qs(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

export const listAlerts = (f: AlertFilters) =>
  apiClient.get<AlertsResult>(
    `${BASE}/list${qs({ type: f.type, from: f.from, to: f.to, status: f.status, minutes: f.minutes })}`,
  );

export const reviewAlert = (id: number, reviewed: boolean) =>
  apiClient.post<void>(`${BASE}/review`, { id, reviewed });

export type ExportFormat = 'csv' | 'xlsx';

/** Download the alerts as CSV or Excel, honouring the same filters as the table. */
export async function downloadAlerts(format: ExportFormat, f: AlertFilters): Promise<void> {
  const url = `${API_BASE}${BASE}/export${qs({
    format,
    tz: tz(),
    type: f.type,
    from: f.from,
    to: f.to,
    status: f.status,
    minutes: f.minutes,
  })}`;
  const res = await fetch(url, { credentials: 'include' });
  if (!res.ok) throw new Error(`No se pudo generar el archivo (${res.status})`);
  const blob = await res.blob();
  const name =
    /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `alertas.${format}`;
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 10_000);
}
