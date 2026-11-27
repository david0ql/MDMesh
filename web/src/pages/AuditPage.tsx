import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { DASH } from '../ui/format';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import {
  downloadAudit,
  getAuditFacets,
  getAuditSettings,
  searchAudit,
  setAuditRetention,
  verifyAudit,
  type AuditEntry,
  type AuditFacets,
  type AuditFilters,
  type AuditPage as AuditPageData,
  type AuditResult,
  type AuditSettings,
  type AuditVerify,
  type ExportFormat,
} from '../api/audit';
import { AuditDetail, AuditResultChip, fmtAuditTime } from '../components/AuditActivity';

// Auditoría: who did what, on what, from where and how it ended. Read-only: rows cannot be edited or deleted from the
// console (nor through the API). Filtering, counting, paging and exporting happen on the server; a folder
// administrator only receives the rows of their folders and of the users they manage.

const PAGE_SIZES = [25, 50, 100];

const todayStr = () => localDay(Date.now());
const daysAgoStr = (d: number) => localDay(Date.now() - d * 86_400_000);
function localDay(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
const startOfDay = (s: string) => new Date(`${s}T00:00:00`).getTime();
const endOfDay = (s: string) => new Date(`${s}T23:59:59.999`).getTime();

interface Form {
  user: string;
  category: string;
  action: string;
  targetType: string;
  device: string;
  group: string;
  result: AuditResult | '';
  from: string;
  to: string;
  q: string;
}

const emptyForm = (): Form => ({
  user: '', category: '', action: '', targetType: '', device: '', group: '', result: '', from: daysAgoStr(7), to: todayStr(), q: '',
});

function toFilters(f: Form): AuditFilters {
  return {
    user: f.user ? Number(f.user) : '',
    category: f.category,
    action: f.action,
    targetType: f.targetType,
    device: f.device.trim(),
    group: f.group ? Number(f.group) : '',
    result: f.result,
    from: f.from ? startOfDay(f.from) : undefined,
    to: f.to ? endOfDay(f.to) : undefined,
    q: f.q.trim(),
  };
}

export function AuditPage() {
  const toast = useToast();
  const [form, setForm] = useState<Form>(emptyForm);
  /** The filters of the rows on screen (the form is applied with "Buscar"). */
  const [applied, setApplied] = useState<Form>(emptyForm);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const [data, setData] = useState<AuditPageData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [facets, setFacets] = useState<AuditFacets | null>(null);
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [settings, setSettings] = useState<AuditSettings | null>(null);
  const [days, setDays] = useState('');
  const [verify, setVerify] = useState<AuditVerify | null>(null);
  const [busy, setBusy] = useState(false);

  const tree = useMemo(() => groupTree(groups), [groups]);

  useEffect(() => {
    getAuditFacets().then(setFacets).catch(() => setFacets(null));
    listGroups().then((o) => setGroups(o.groups)).catch(() => undefined);
    getAuditSettings().then((s) => { setSettings(s); setDays(String(s.retentionDays)); }).catch(() => setSettings(null));
  }, []);

  const load = useCallback(async (f: Form, p: number, size: number) => {
    setLoading(true);
    setError(null);
    try {
      setData(await searchAudit(toFilters(f), p, size));
    } catch (e) {
      setData(null);
      const m = (e as Error).message;
      setError(m === 'error.permission.denied' ? 'No tienes permiso para ver la auditoría.' : (m || 'No se pudo cargar la auditoría'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(applied, page, pageSize); }, [load, applied, page, pageSize]);

  function search() {
    setOpen(null);
    setPage(1);
    setApplied({ ...form });
  }

  function clear() {
    const f = emptyForm();
    setForm(f);
    setOpen(null);
    setPage(1);
    setApplied(f);
  }

  const onExport = useCallback(async (format: ExportFormat) => {
    try {
      await downloadAudit(format, toFilters(applied));
      toast.push('ok', 'Exportación lista', 'La descarga quedó registrada en la auditoría.');
    } catch (e) {
      toast.push('err', 'Error', (e as Error).message || 'No se pudo exportar');
    }
  }, [applied, toast]);

  async function saveRetention() {
    const n = Number(days);
    if (!settings || !Number.isInteger(n) || n < settings.minDays || n > settings.maxDays) {
      toast.push('err', 'Retención inválida', `Escribe un número de días entre ${settings?.minDays ?? 30} y ${settings?.maxDays ?? 3650}.`);
      return;
    }
    setBusy(true);
    try {
      await setAuditRetention(n);
      setSettings({ ...settings, retentionDays: n });
      toast.push('ok', 'Retención guardada', `Los registros se conservan ${n} días.`);
      void load(applied, page, pageSize);
    } catch (e) {
      toast.push('err', 'No se pudo guardar', (e as Error).message);
    }
    setBusy(false);
  }

  async function runVerify() {
    setBusy(true);
    try {
      setVerify(await verifyAudit());
    } catch (e) {
      toast.push('err', 'No se pudo verificar', (e as Error).message);
    }
    setBusy(false);
  }

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const actions = (facets?.actions ?? []).filter((a) => !form.category || a.category === form.category);
  const set = <K extends keyof Form>(k: K, v: Form[K]) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <AppShell title="Auditoría">
      <div className="page-head">
        <h1>
          Auditoría
          {data ? <span className="count" data-testid="audit-total">{total}</span> : null}
        </h1>
      </div>

      <p className="muted report-hint">
        Registro de lo que hace cada técnico en la consola: inicios de sesión, comandos a los equipos, cambios de políticas,
        carpetas, aplicaciones, usuarios y roles, exportaciones y accesos denegados. Los registros <b>no se pueden editar ni
        borrar</b>; se conservan {settings ? `${settings.retentionDays} días` : 'el tiempo configurado'} y luego se purgan
        automáticamente (la purga también queda registrada).
        {facets?.scoped ? <> Ves solo la actividad de <b>tus carpetas</b> y de <b>tus usuarios</b>.</> : null}
      </p>

      <form className="report-toolbar audit-toolbar" onSubmit={(e) => { e.preventDefault(); search(); }} data-testid="audit-filters">
        <label className="report-field">
          <span>Técnico</span>
          <select className="input" value={form.user} onChange={(e) => set('user', e.target.value)} data-testid="audit-f-user">
            <option value="">Todos</option>
            {(facets?.users ?? []).map((u) => (
              <option key={u.id} value={u.id}>{u.name && u.name !== u.login ? `${u.name} (${u.login})` : u.login}</option>
            ))}
          </select>
        </label>
        <label className="report-field">
          <span>Módulo</span>
          <select className="input" value={form.category} onChange={(e) => setForm((f) => ({ ...f, category: e.target.value, action: '' }))} data-testid="audit-f-category">
            <option value="">Todos</option>
            {Object.entries(facets?.categories ?? {}).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
          </select>
        </label>
        <label className="report-field">
          <span>Acción</span>
          <select className="input" value={form.action} onChange={(e) => set('action', e.target.value)} data-testid="audit-f-action" style={{ maxWidth: 260 }}>
            <option value="">Todas</option>
            {actions.map((a) => <option key={`${a.category}|${a.action}`} value={a.action}>{a.label}</option>)}
          </select>
        </label>
        <label className="report-field">
          <span>Tipo de objetivo</span>
          <select className="input" value={form.targetType} onChange={(e) => set('targetType', e.target.value)} data-testid="audit-f-type">
            <option value="">Todos</option>
            {Object.entries(facets?.targetTypes ?? {}).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
          </select>
        </label>
        <label className="report-field">
          <span>Dispositivo (número)</span>
          <input className="input mono" value={form.device} onChange={(e) => set('device', e.target.value)} placeholder="p. ej. dc-000123" style={{ width: 150 }} data-testid="audit-f-device" />
        </label>
        <label className="report-field">
          <span>Carpeta</span>
          <select className="input" value={form.group} onChange={(e) => set('group', e.target.value)} data-testid="audit-f-group" style={{ maxWidth: 220 }}>
            <option value="">Todas</option>
            {tree.map((n) => <option key={n.group.id} value={n.group.id}>{`${' '.repeat(n.depth * 2)}${n.group.name}`}</option>)}
          </select>
        </label>
        <label className="report-field">
          <span>Resultado</span>
          <select className="input" value={form.result} onChange={(e) => set('result', e.target.value as AuditResult | '')} data-testid="audit-f-result">
            <option value="">Todos</option>
            <option value="ok">Éxito</option>
            <option value="denied">Denegado</option>
            <option value="error">Error</option>
          </select>
        </label>
        <label className="report-field">
          <span>Desde</span>
          <input className="input" type="date" value={form.from} max={form.to || undefined} onChange={(e) => set('from', e.target.value)} data-testid="audit-f-from" />
        </label>
        <label className="report-field">
          <span>Hasta</span>
          <input className="input" type="date" value={form.to} min={form.from || undefined} onChange={(e) => set('to', e.target.value)} data-testid="audit-f-to" />
        </label>
        <label className="report-field">
          <span>Buscar</span>
          <input className="input" type="search" value={form.q} onChange={(e) => set('q', e.target.value)} placeholder="técnico, equipo, IP, detalle…" style={{ width: 200 }} data-testid="audit-f-q" />
        </label>

        <button className="btn btn-primary btn-sm" type="submit" disabled={loading} data-testid="audit-search">{loading ? 'Cargando…' : 'Buscar'}</button>
        <button className="btn btn-sm btn-ghost" type="button" onClick={clear}>Limpiar</button>
        <div className="report-export">
          <button className="btn btn-sm" type="button" disabled={loading || total === 0} onClick={() => void onExport('xlsx')} data-testid="audit-export-xlsx">Excel</button>
          <button className="btn btn-sm" type="button" disabled={loading || total === 0} onClick={() => void onExport('csv')} data-testid="audit-export-csv">CSV</button>
        </div>
      </form>

      {error && <div className="report-error" data-testid="audit-error">{error}</div>}

      {!error && (
        <div className="report-table-wrap">
          {items.length === 0 ? (
            <div className="report-empty muted" data-testid="audit-empty">{loading ? 'Cargando…' : 'Sin registros para estos filtros.'}</div>
          ) : (
            <table className="table audit-table" data-testid="audit-table">
              <thead>
                <tr>
                  <th>Fecha y hora</th>
                  <th>Técnico</th>
                  <th>Módulo</th>
                  <th>Acción</th>
                  <th>Objetivo</th>
                  <th>Resultado</th>
                  <th>IP</th>
                  <th aria-label="Detalle" />
                </tr>
              </thead>
              <tbody>
                {items.map((e) => (
                  <Fragment key={e.id}>
                    <tr className={open === e.id ? 'audit-open' : ''} data-testid="audit-row" data-action={e.action} data-result={e.result}>
                      <td className="audit-when">{fmtAuditTime(e.createdAt)}</td>
                      <td>
                        {e.userName || e.login || <span className="muted">{DASH}</span>}
                        <div className="muted small">{[e.userName && e.login !== e.userName ? e.login : null, e.roleName].filter(Boolean).join(' · ')}</div>
                      </td>
                      <td>{facets?.categories[e.category] ?? e.category}</td>
                      <td>{e.label}{e.message ? <div className="muted small">{e.message}</div> : null}</td>
                      <td><Target entry={e} types={facets?.targetTypes} /></td>
                      <td><AuditResultChip result={e.result} /></td>
                      <td className="mono small">{e.ip || DASH}</td>
                      <td>
                        <button className="btn btn-sm btn-ghost" aria-expanded={open === e.id} onClick={() => setOpen(open === e.id ? null : e.id)} data-testid="audit-toggle">
                          {open === e.id ? 'Ocultar' : 'Detalle'}
                        </button>
                      </td>
                    </tr>
                    {open === e.id && (<tr className="audit-detail-row"><td colSpan={8}><AuditDetail entry={e} /></td></tr>)}
                  </Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {!error && total > 0 && (
        <div className="audit-pager" data-testid="audit-pager">
          <button className="btn btn-sm" disabled={page <= 1 || loading} onClick={() => { setOpen(null); setPage(page - 1); }} data-testid="audit-prev">Anterior</button>
          <span className="muted small" data-testid="audit-page">Página {page} de {pages} · {total} registros</span>
          <button className="btn btn-sm" disabled={page >= pages || loading} onClick={() => { setOpen(null); setPage(page + 1); }} data-testid="audit-next">Siguiente</button>
          <label className="muted small audit-pagesize">
            Filas
            <select className="input" value={pageSize} onChange={(e) => { setPage(1); setPageSize(Number(e.target.value)); }} data-testid="audit-pagesize">
              {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>
      )}

      {settings && facets && !facets.scoped && (
        <section className="panel audit-admin" data-testid="audit-admin">
          <div className="panel-head keep"><h2 className="panel-title">Conservación e integridad</h2></div>
          <div className="audit-admin-body">
            <div>
              <div className="sub-h">Retención</div>
              <p className="muted small">
                Los registros más antiguos que este plazo se eliminan una vez al día. Mínimo {settings.minDays} días, máximo {settings.maxDays}.
              </p>
              <div className="audit-inline">
                <input className="input" type="number" min={settings.minDays} max={settings.maxDays} value={days} disabled={!settings.canEdit}
                  onChange={(e) => setDays(e.target.value)} style={{ width: 110 }} aria-label="Días de retención" data-testid="audit-retention" />
                <span className="muted">días</span>
                {settings.canEdit && (
                  <button className="btn btn-sm btn-primary" disabled={busy || Number(days) === settings.retentionDays} onClick={() => void saveRetention()} data-testid="audit-retention-save">Guardar</button>
                )}
              </div>
            </div>
            <div>
              <div className="sub-h">Integridad</div>
              <p className="muted small">
                Cada registro guarda la huella (hash) del anterior. La verificación recorre toda la cadena y detecta un registro
                modificado o eliminado por fuera de la consola.
              </p>
              <div className="audit-inline">
                <button className="btn btn-sm" disabled={busy} onClick={() => void runVerify()} data-testid="audit-verify">Verificar integridad</button>
                {verify && (
                  <span className={`chip ${verify.ok ? 'tone-ok' : 'tone-alert'}`} data-testid="audit-verify-result">
                    {verify.ok ? `Cadena íntegra · ${verify.checked} registros` : `Cadena alterada en el registro #${verify.brokenAtId}`}
                  </span>
                )}
              </div>
            </div>
          </div>
        </section>
      )}
    </AppShell>
  );
}

/** What the action was about: the device (linked), or the folder / policy / user by name. */
function Target({ entry, types }: { entry: AuditEntry; types?: Record<string, string> }) {
  if (!entry.targetType) return <span className="muted">{DASH}</span>;
  const kind = types?.[entry.targetType] ?? entry.targetType;
  const name = entry.targetName || entry.targetId || DASH;
  return (
    <>
      {entry.targetType === 'device' && entry.targetId
        ? <Link className="row-link-a" to={`/devices/${encodeURIComponent(entry.targetId)}`}>{name}</Link>
        : name}
      <div className="muted small">{kind}{entry.targetType === 'device' && entry.targetId && entry.targetName !== entry.targetId ? ` · ${entry.targetId}` : ''}</div>
    </>
  );
}
