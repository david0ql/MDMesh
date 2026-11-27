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

export const reportSimMismatch = () => apiClient.get<ReportResult>(`${BASE}/sim-mismatch`);

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

/** Mobile data: consumption of the period against the policy's limits, per device and per app with a limit. */
export const reportDataLimits = () => apiClient.get<ReportResult>(`${BASE}/data-limits`);

export const reportNonCompliance = () => apiClient.get<ReportResult>(`${BASE}/non-compliance`);

export const reportDecommissioned = (from: number, to: number) =>
  apiClient.get<ReportResult>(`${BASE}/decommissioned${qs({ from, to })}`);

/** Per command type: sent, % executed and p50/p90/max latency, for a date range and (optionally) a folder. */
export const reportCommandLatency = (from: number, to: number, groupId?: number | null) =>
  apiClient.get<ReportResult>(`${BASE}/command-latency${qs({ from, to, groupId })}`);

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

// --- Fleet inventory ------------------------------------------------------------------------------

export interface Breakdown { label: string; count: number }

export interface InventorySummary {
  total: number;
  online: number;
  offline: number;
  onlineWindowMinutes: number;
  lowBattery: number;
  lowBatteryPct: number;
  storageAlmostFull: number;
  storageUsedPct: number;
  offlineLong: number;
  offlineDays: number;
  neverReported: number;
  outdatedAgent: number;
  latestAgent?: string | null;
  osUpdatePending: number;
  byAndroid: Breakdown[];
  byModel: Breakdown[];
  byAgent: Breakdown[];
}

export interface InventoryParams {
  group?: number | null;
  lowBattery?: number;
  storagePct?: number;
  offlineDays?: number;
}

export type InventoryResult = ReportResult & { summary?: InventorySummary };

export const reportInventory = (p: InventoryParams) =>
  apiClient.get<InventoryResult>(`${BASE}/inventory${qs({ ...p })}`);

/** Download the inventory: Excel (full workbook) or CSV with the chosen separator. */
export async function downloadInventory(format: ExportFormat, p: InventoryParams, sep = ';'): Promise<void> {
  const url = `${API_BASE}${BASE}/inventory/export${qs({ format, sep, tz: tz(), ...p })}`;
  const res = await fetch(url, { credentials: 'include' });
  if (!res.ok) throw new Error(`No se pudo generar el archivo (${res.status})`);
  const blob = await res.blob();
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `inventario.${format}`;
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 10_000);
}

// --- Scheduled e-mails ----------------------------------------------------------------------------

export interface ReportSchedule {
  id?: number;
  name: string;
  report: string;
  format: ExportFormat;
  csvSeparator: string;
  frequency: 'daily' | 'weekly' | 'monthly';
  runHour: number;
  runMinute: number;
  /** 1 = lunes … 7 = domingo. */
  dayOfWeek?: number | null;
  dayOfMonth?: number | null;
  timezone: string;
  /** Comma-separated addresses. */
  recipients: string;
  groupId?: number | null;
  enabled: boolean;
  lastRunAt?: number | null;
  lastStatus?: 'ok' | 'error' | 'smtp' | null;
  lastError?: string | null;
  nextRunAt?: number | null;
}

export interface ScheduleList {
  smtpConfigured: boolean;
  reports: { id: string; label: string }[];
  schedules: ReportSchedule[];
}

export interface SendNowResult {
  sent: boolean;
  status: string;
  message: string;
  smtpConfigured: boolean;
  schedule: ReportSchedule;
}

export const listSchedules = () => apiClient.get<ScheduleList>(`${BASE}/schedules`);
export const saveSchedule = (s: ReportSchedule) =>
  s.id == null ? apiClient.post<ReportSchedule>(`${BASE}/schedules`, s) : apiClient.put<ReportSchedule>(`${BASE}/schedules/${s.id}`, s);
export const deleteSchedule = (id: number) => apiClient.del<void>(`${BASE}/schedules/${id}`);
export const sendScheduleNow = (id: number) => apiClient.post<SendNowResult>(`${BASE}/schedules/${id}/send`, {});
