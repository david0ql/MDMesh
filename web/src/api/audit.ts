import { API_BASE, apiClient } from './client';

// Auditoría: read-only API over the audit trail (server: AuditTrailResource, /private/audit/v1).
// Needs the `view_audit` permission. A folder administrator only receives the rows of their own folders and of the
// users they manage — the server filters, counts and exports with that scope. There is no call to edit or delete a row.

const BASE = '/private/audit/v1';

export type AuditResult = 'ok' | 'denied' | 'error';

export interface AuditEntry {
  id: number;
  createdAt: number;
  userId?: number | null;
  login?: string | null;
  userName?: string | null;
  roleName?: string | null;
  ip?: string | null;
  userAgent?: string | null;
  /** Module key (see facets.categories). */
  category: string;
  action: string;
  /** What happened, in Spanish. */
  label: string;
  method?: string | null;
  path?: string | null;
  targetType?: string | null;
  targetId?: string | null;
  targetName?: string | null;
  /** Device numbers the action touched, comma separated. */
  deviceNumbers?: string | null;
  result: AuditResult;
  httpStatus?: number | null;
  message?: string | null;
  /** JSON text: { solicitud, cambios, eliminado, consulta, dispositivos, duracion, … } — secrets already removed. */
  detail?: string | null;
  durationMs?: number | null;
  hash?: string | null;
}

export interface AuditPage {
  items: AuditEntry[];
  total: number;
  page: number;
  pageSize: number;
}

export interface AuditFilters {
  from?: number;
  to?: number;
  user?: number | '';
  category?: string;
  action?: string;
  result?: AuditResult | '';
  targetType?: string;
  /** Device number. */
  device?: string;
  /** Folder id. */
  group?: number | '';
  q?: string;
}

export interface AuditFacets {
  categories: Record<string, string>;
  targetTypes: Record<string, string>;
  users: { id: number; login: string; name?: string | null; n: number }[];
  actions: { category: string; action: string; label: string; n: number }[];
  retentionDays: number;
  /** The viewer is a folder administrator: they see only their folders and their users. */
  scoped: boolean;
}

export interface AuditSettings {
  retentionDays: number;
  minDays: number;
  maxDays: number;
  canEdit: boolean;
}

export interface AuditVerify {
  ok: boolean;
  checked: number;
  brokenAtId?: number;
}

function qs(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

const filterParams = (f: AuditFilters) => ({
  from: f.from,
  to: f.to,
  user: f.user,
  category: f.category,
  action: f.action,
  result: f.result,
  targetType: f.targetType,
  device: f.device,
  group: f.group,
  q: f.q,
});

export const searchAudit = (f: AuditFilters, page: number, pageSize: number) =>
  apiClient.get<AuditPage>(`${BASE}/log${qs({ ...filterParams(f), page, pageSize })}`);

export const getAuditFacets = () => apiClient.get<AuditFacets>(`${BASE}/facets`);

export const getDeviceActivity = (number: string, page = 1, pageSize = 30) =>
  apiClient.get<AuditPage>(`${BASE}/devices/${encodeURIComponent(number)}/activity${qs({ page, pageSize })}`);

export const getUserActivity = (id: number, page = 1, pageSize = 30) =>
  apiClient.get<AuditPage>(`${BASE}/users/${id}/activity${qs({ page, pageSize })}`);

export const getAuditSettings = () => apiClient.get<AuditSettings>(`${BASE}/settings`);

export const setAuditRetention = (retentionDays: number) =>
  apiClient.put<{ retentionDays: number; previous: number }>(`${BASE}/settings`, { retentionDays });

export const verifyAudit = () => apiClient.get<AuditVerify>(`${BASE}/verify`);

export type ExportFormat = 'csv' | 'xlsx';

const tz = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Bogota';

/** Download the audit as Excel or CSV, honouring the same filters as the table (same pattern as Alertas / Reportes). */
export async function downloadAudit(format: ExportFormat, f: AuditFilters): Promise<void> {
  const url = `${API_BASE}${BASE}/export${qs({ format, tz: tz(), ...filterParams(f) })}`;
  const res = await fetch(url, { credentials: 'include' });
  if (!res.ok) throw new Error(`No se pudo generar el archivo (${res.status})`);
  const blob = await res.blob();
  const name =
    /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `auditoria.${format}`;
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 10_000);
}

export const RESULT_LABEL: Record<string, string> = { ok: 'Éxito', denied: 'Denegado', error: 'Error' };

/** The detail document of a row, or null when it has none / cannot be read. */
export function parseDetail(e: AuditEntry): Record<string, unknown> | null {
  if (!e.detail) return null;
  try {
    const d = JSON.parse(e.detail) as unknown;
    return d && typeof d === 'object' && !Array.isArray(d) ? (d as Record<string, unknown>) : { detalle: d };
  } catch {
    return { detalle: e.detail };
  }
}
