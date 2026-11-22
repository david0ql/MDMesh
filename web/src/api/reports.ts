import { apiClient, API_BASE } from './client';

// Fleet reports (console "Reportes" module).
//   /rest/private/reports/v1/...
// Every report returns the same shape: typed columns + rows. The page renders a generic table
// from the column metadata, and the export endpoint turns the same columns+rows into CSV/Excel.

const BASE = '/private/reports/v1';

export type ColumnType = 'text' | 'number' | 'bytes' | 'datetime' | 'day';

export interface ReportColumn {
  key: string;
  label: string;
  type: ColumnType;
}

export interface ReportRow {
  [key: string]: unknown;
}

export interface ReportResult {
  report: string;
  title: string;
  generatedAt: number;
  columns: ReportColumn[];
  rows: ReportRow[];
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

// --- the reports ---------------------------------------------------------------------------------

export const reportOffline = (days: number) => apiClient.get<ReportResult>(`${BASE}/offline${qs({ days })}`);

export const reportSim = (from: number, to: number) =>
  apiClient.get<ReportResult>(`${BASE}/sim${qs({ from, to })}`);

export const reportAppInstalls = () => apiClient.get<ReportResult>(`${BASE}/app-installs`);

export const reportAppInstallDevices = (pkg: string) =>
  apiClient.get<ReportResult>(`${BASE}/app-install-devices${qs({ pkg })}`);

export const reportAppsCatalog = () => apiClient.get<ReportResult>(`${BASE}/apps-catalog`);

export const reportByApp = (pkgs: string[]) =>
  apiClient.get<ReportResult>(`${BASE}/by-app${qs({ pkgs: pkgs.join(',') })}`);

export const reportNoCommands = (minutes: number) =>
  apiClient.get<ReportResult>(`${BASE}/no-commands${qs({ minutes })}`);

export const reportOutdatedAgents = () => apiClient.get<ReportResult>(`${BASE}/outdated-agents`);

export const reportOutdatedApps = () => apiClient.get<ReportResult>(`${BASE}/outdated-apps`);

export const reportDataUsage = (days: number, limit: number) =>
  apiClient.get<ReportResult>(`${BASE}/data-usage${qs({ days, limit })}`);

export const reportNonCompliance = () => apiClient.get<ReportResult>(`${BASE}/non-compliance`);

export const reportDecommissioned = (from: number, to: number) =>
  apiClient.get<ReportResult>(`${BASE}/decommissioned${qs({ from, to })}`);

// --- export (CSV / Excel) ------------------------------------------------------------------------

export type ExportFormat = 'csv' | 'xlsx';

/** Download a report as CSV or Excel. `params` passes the same filters the table used. */
export async function downloadReport(
  report: string,
  format: ExportFormat,
  params: Record<string, string | number | undefined | null> = {},
): Promise<void> {
  const url = `${API_BASE}${BASE}/export${qs({ report, format, tz: tz(), ...params })}`;
  const res = await fetch(url, { credentials: 'include' });
  if (!res.ok) throw new Error(`No se pudo generar el archivo (${res.status})`);
  const blob = await res.blob();
  const name =
    /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ??
    `reporte-${report}.${format}`;
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 10_000);
}
