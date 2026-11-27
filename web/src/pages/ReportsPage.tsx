import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { InventoryReport } from '../components/InventoryReport';
import { ReportSchedulesPanel } from '../components/ReportSchedulesPanel';
import { fmtDateTime, DASH } from '../ui/format';
import {
  downloadReport,
  reportOffline,
  reportSim,
  reportSimMismatch,
  reportAppInstalls,
  reportAppInstallDevices,
  reportAppsCatalog,
  reportByApp,
  reportNoCommands,
  reportOutdatedAgents,
  reportOutdatedApps,
  reportDataUsage,
  reportNonCompliance,
  reportDecommissioned,
  reportCommandLatency,
  type ReportResult,
  type ReportColumn,
  type ReportRow,
  type ExportFormat,
} from '../api/reports';
import { listGroups, type FleetGroup } from '../api/fleet';

// ---------------------------------------------------------------------------------------------
// Generic value formatting + table
// ---------------------------------------------------------------------------------------------

function humanBytes(v: unknown): string {
  const n = typeof v === 'number' ? v : Number(v);
  if (!Number.isFinite(n) || n <= 0) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let x = n;
  let i = 0;
  while (x >= 1024 && i < u.length - 1) {
    x /= 1024;
    i++;
  }
  return `${x.toLocaleString('es-CO', { maximumFractionDigits: i === 0 ? 0 : 1 })} ${u[i]}`;
}

function renderCell(col: ReportColumn, row: ReportRow): React.ReactNode {
  const v = row[col.key];
  // A device name links to its page when the row carries a device number.
  if (col.key === 'name' && typeof row.number === 'string' && row.number) {
    return (
      <Link className="row-link-a" to={`/devices/${encodeURIComponent(row.number)}`}>
        {String(v ?? row.number)}
      </Link>
    );
  }
  if (v === null || v === undefined || v === '') return <span className="muted">{DASH}</span>;
  switch (col.type) {
    case 'datetime': {
      const n = typeof v === 'number' ? v : Number(v);
      return Number.isFinite(n) && n > 0 ? fmtDateTime(n) : <span className="muted">{DASH}</span>;
    }
    case 'bytes':
      return humanBytes(v);
    case 'number':
      return typeof v === 'number' ? v.toLocaleString('es-CO') : String(v);
    default:
      return String(v);
  }
}

function ReportTable({
  result,
  emptyText,
  onRowClick,
}: {
  result: ReportResult | null;
  emptyText: string;
  onRowClick?: (row: ReportRow) => void;
}) {
  if (!result) return null;
  if (result.rows.length === 0) {
    return <div className="report-empty muted">{emptyText}</div>;
  }
  return (
    <div className="report-table-wrap">
      <table className="table">
        <thead>
          <tr>
            {result.columns.map((c) => (
              <th key={c.key}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((row, i) => (
            <tr
              key={i}
              className={onRowClick ? 'row-link' : undefined}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              {result.columns.map((c) => (
                <td key={c.key}>{renderCell(c, row)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Small controls
// ---------------------------------------------------------------------------------------------

function NumberField({
  label,
  value,
  onChange,
  min = 1,
  max = 99999,
  suffix,
}: {
  label: string;
  value: number;
  onChange: (n: number) => void;
  min?: number;
  max?: number;
  suffix?: string;
}) {
  return (
    <label className="report-field">
      <span>{label}</span>
      <span className="report-field-input">
        <input
          className="input"
          type="number"
          min={min}
          max={max}
          value={value}
          onChange={(e) => {
            const n = Number(e.target.value);
            if (Number.isFinite(n)) onChange(Math.max(min, Math.min(max, n)));
          }}
          style={{ width: 90 }}
        />
        {suffix ? <span className="muted">{suffix}</span> : null}
      </span>
    </label>
  );
}

const todayStr = () => new Date().toISOString().slice(0, 10);
const daysAgoStr = (d: number) => new Date(Date.now() - d * 86_400_000).toISOString().slice(0, 10);
const startOfDay = (s: string) => new Date(`${s}T00:00:00`).getTime();
const endOfDay = (s: string) => new Date(`${s}T23:59:59.999`).getTime();

function DateRangeField({
  from,
  to,
  onFrom,
  onTo,
}: {
  from: string;
  to: string;
  onFrom: (s: string) => void;
  onTo: (s: string) => void;
}) {
  return (
    <>
      <label className="report-field">
        <span>Desde</span>
        <input className="input" type="date" value={from} max={to} onChange={(e) => onFrom(e.target.value)} />
      </label>
      <label className="report-field">
        <span>Hasta</span>
        <input className="input" type="date" value={to} min={from} max={todayStr()} onChange={(e) => onTo(e.target.value)} />
      </label>
    </>
  );
}

function ExportButtons({ disabled, onExport }: { disabled: boolean; onExport: (f: ExportFormat) => void }) {
  return (
    <div className="report-export">
      <button className="btn btn-sm" disabled={disabled} onClick={() => onExport('xlsx')}>
        Excel
      </button>
      <button className="btn btn-sm" disabled={disabled} onClick={() => onExport('csv')}>
        CSV
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Tab definitions
// ---------------------------------------------------------------------------------------------

type TabId =
  | 'offline'
  | 'sim'
  | 'sim-mismatch'
  | 'app-installs'
  | 'by-app'
  | 'no-commands'
  | 'outdated-agents'
  | 'outdated-apps'
  | 'data-usage'
  | 'non-compliance'
  | 'decommissioned'
  | 'inventory'
  | 'schedules'
  | 'command-latency';

const TABS: { id: TabId; label: string; hint: string }[] = [
  { id: 'offline', label: 'Sin conexión', hint: 'Equipos cuya última conexión supera los días indicados.' },
  { id: 'sim', label: 'Retiro de SIM', hint: 'Eventos de SIM (retirada, cambiada, insertada) en el rango de fechas.' },
  { id: 'sim-mismatch', label: 'SIM no coincide', hint: 'Equipos cuyo número real de la SIM difiere del número de celular esperado declarado en la inscripción.' },
  { id: 'app-installs', label: 'Apps desplegadas', hint: 'Despliegues app.install agrupados por paquete. Clic en una fila para ver los equipos.' },
  { id: 'by-app', label: 'Por app instalada', hint: 'Equipos que tienen alguno de los paquetes seleccionados.' },
  { id: 'no-commands', label: 'Sin recibir comandos', hint: 'Equipos con comandos pendientes creados hace más del umbral.' },
  { id: 'outdated-agents', label: 'Agentes desactualizados', hint: 'Equipos cuyo agente no es la última versión servida.' },
  { id: 'outdated-apps', label: 'Apps desactualizadas', hint: 'Apps instaladas cuya versión difiere de la última de la biblioteca.' },
  { id: 'data-usage', label: 'Datos móviles', hint: 'Mayores consumidores de datos móviles en los últimos días.' },
  { id: 'non-compliance', label: 'Incumplimiento', hint: 'Equipos cuya revisión aplicada no coincide con la actual de su política.' },
  { id: 'decommissioned', label: 'Dados de baja', hint: 'Equipos eliminados en el rango de fechas.' },
  { id: 'inventory', label: 'Inventario de la flota', hint: 'Estado de toda la flota con su último reporte: conexión, batería, almacenamiento, Android y red de cada equipo, con resumen y exportación a Excel o CSV.' },
  { id: 'schedules', label: 'Envíos programados', hint: 'Reportes que se envían solos por correo (diario, semanal o mensual) a los destinatarios que elijas.' },
  { id: 'command-latency', label: 'Latencia de comandos', hint: 'Por tipo de comando: enviados, % ejecutados y latencia (p50, p90 y máxima, en segundos) desde que se envía hasta que el equipo lo recibe y hasta que lo ejecuta. Filtra por fechas y carpeta.' },
];

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

export function ReportsPage() {
  const toast = useToast();
  const [tab, setTab] = useState<TabId>('offline');

  // shared filter state (each tab reads what it needs)
  const [days, setDays] = useState(7);
  const [usageDays, setUsageDays] = useState(30);
  const [usageLimit, setUsageLimit] = useState(50);
  const [minutes, setMinutes] = useState(60);
  const [simFrom, setSimFrom] = useState(daysAgoStr(30));
  const [simTo, setSimTo] = useState(todayStr());
  const [decFrom, setDecFrom] = useState(daysAgoStr(90));
  const [decTo, setDecTo] = useState(todayStr());
  const [latFrom, setLatFrom] = useState(daysAgoStr(30));
  const [latTo, setLatTo] = useState(todayStr());
  const [latGroup, setLatGroup] = useState<number | ''>('');
  const [folders, setFolders] = useState<FleetGroup[]>([]);

  const [result, setResult] = useState<ReportResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // by-app picker
  const [catalog, setCatalog] = useState<ReportRow[] | null>(null);
  const [selectedPkgs, setSelectedPkgs] = useState<string[]>([]);
  const [pkgFilter, setPkgFilter] = useState('');

  // app-installs drill-down
  const [drillPkg, setDrillPkg] = useState<string | null>(null);
  const [drill, setDrill] = useState<ReportResult | null>(null);
  const [drillLoading, setDrillLoading] = useState(false);

  // Build the export params that match the current filters for the active tab.
  const exportParams = useCallback((): Record<string, string | number> => {
    switch (tab) {
      case 'offline':
        return { days };
      case 'sim':
        return { from: startOfDay(simFrom), to: endOfDay(simTo) };
      case 'by-app':
        return { pkgs: selectedPkgs.join(',') };
      case 'no-commands':
        return { minutes };
      case 'data-usage':
        return { days: usageDays, limit: usageLimit };
      case 'decommissioned':
        return { from: startOfDay(decFrom), to: endOfDay(decTo) };
      case 'command-latency':
        return { from: startOfDay(latFrom), to: endOfDay(latTo), ...(latGroup === '' ? {} : { groupId: latGroup }) };
      default:
        return {};
    }
  }, [tab, days, simFrom, simTo, selectedPkgs, minutes, usageDays, usageLimit, decFrom, decTo, latFrom, latTo, latGroup]);

  // Only the newest request may fill the table: a slow answer for the tab left behind must not replace this one's.
  const loadSeq = useRef(0);
  const load = useCallback(async () => {
    const seq = ++loadSeq.current;
    setLoading(true);
    setError(null);
    setDrillPkg(null);
    setDrill(null);
    try {
      let r: ReportResult;
      switch (tab) {
        case 'offline':
          r = await reportOffline(days);
          break;
        case 'sim':
          r = await reportSim(startOfDay(simFrom), endOfDay(simTo));
          break;
        case 'sim-mismatch':
          r = await reportSimMismatch();
          break;
        case 'app-installs':
          r = await reportAppInstalls();
          break;
        case 'by-app':
          r = await reportByApp(selectedPkgs);
          break;
        case 'no-commands':
          r = await reportNoCommands(minutes);
          break;
        case 'outdated-agents':
          r = await reportOutdatedAgents();
          break;
        case 'outdated-apps':
          r = await reportOutdatedApps();
          break;
        case 'data-usage':
          r = await reportDataUsage(usageDays, usageLimit);
          break;
        case 'non-compliance':
          r = await reportNonCompliance();
          break;
        case 'decommissioned':
          r = await reportDecommissioned(startOfDay(decFrom), endOfDay(decTo));
          break;
        case 'command-latency':
          r = await reportCommandLatency(startOfDay(latFrom), endOfDay(latTo), latGroup === '' ? null : latGroup);
          break;
        default:
          return;
      }
      if (seq === loadSeq.current) setResult(r);
    } catch (e) {
      if (seq !== loadSeq.current) return;
      setResult(null);
      setError((e as Error).message || 'No se pudo cargar el reporte');
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }, [tab, days, simFrom, simTo, selectedPkgs, minutes, usageDays, usageLimit, decFrom, decTo, latFrom, latTo, latGroup]);

  // Folders for the latency report's filter (loaded the first time that tab is opened).
  useEffect(() => {
    if (tab !== 'command-latency' || folders.length) return;
    listGroups().then((g) => setFolders(g.groups)).catch(() => undefined);
  }, [tab, folders.length]);

  // Reload when the tab changes (and on first mount). Filter changes are applied with "Actualizar".
  useEffect(() => {
    setResult(null);
    // by-app needs an explicit selection first; everything else loads immediately.
    if (tab === 'inventory' || tab === 'schedules') return; // these tabs load their own data
    if (tab === 'by-app') {
      setResult(null);
      if (!catalog) {
        reportAppsCatalog()
          .then((r) => setCatalog(r.rows))
          .catch(() => setCatalog([]));
      }
      return;
    }
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const openDrill = useCallback(async (row: ReportRow) => {
    const pkg = String(row.pkg ?? '');
    if (!pkg) return;
    setDrillPkg(pkg);
    setDrill(null);
    setDrillLoading(true);
    try {
      setDrill(await reportAppInstallDevices(pkg));
    } catch (e) {
      toast.push('err', 'Error', (e as Error).message || 'No se pudo cargar el detalle');
    } finally {
      setDrillLoading(false);
    }
  }, [toast]);

  const onExport = useCallback(
    async (format: ExportFormat) => {
      try {
        await downloadReport(tab, format, exportParams());
      } catch (e) {
        toast.push('err', 'Error', (e as Error).message || 'No se pudo exportar');
      }
    },
    [tab, exportParams, toast],
  );

  const tabMeta = useMemo(() => TABS.find((t) => t.id === tab)!, [tab]);

  const filteredCatalog = useMemo(() => {
    if (!catalog) return [];
    const q = pkgFilter.trim().toLowerCase();
    const list = q
      ? catalog.filter(
          (r) =>
            String(r.pkg ?? '').toLowerCase().includes(q) ||
            String(r.name ?? '').toLowerCase().includes(q),
        )
      : catalog;
    return list.slice(0, 200);
  }, [catalog, pkgFilter]);

  const togglePkg = (pkg: string) =>
    setSelectedPkgs((prev) => (prev.includes(pkg) ? prev.filter((p) => p !== pkg) : [...prev, pkg]));

  const canExport = !!result && result.rows.length > 0 && tab !== 'app-installs';
  const exportAggregate = !!result && result.rows.length > 0 && tab === 'app-installs';

  return (
    <AppShell title="Reportes">
      <div className="page-head">
        <h1>
          Reportes
          {result ? <span className="count">{result.rows.length}</span> : null}
        </h1>
      </div>

      <div className="tabs">
        {TABS.map((t) => (
          <button key={t.id} className={t.id === tab ? 'on' : ''} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </div>

      <p className="muted report-hint">{tabMeta.hint}</p>

      {tab === 'inventory' && <InventoryReport />}
      {tab === 'schedules' && <ReportSchedulesPanel />}

      <div className="report-toolbar" style={tab === 'inventory' || tab === 'schedules' ? { display: 'none' } : undefined}>
        {tab === 'offline' && (
          <NumberField label="Días sin conexión" value={days} onChange={setDays} min={1} max={3660} suffix="días" />
        )}
        {tab === 'sim' && (
          <DateRangeField from={simFrom} to={simTo} onFrom={setSimFrom} onTo={setSimTo} />
        )}
        {tab === 'no-commands' && (
          <NumberField label="Antigüedad del pendiente" value={minutes} onChange={setMinutes} min={1} max={86400} suffix="min" />
        )}
        {tab === 'data-usage' && (
          <>
            <NumberField label="Período" value={usageDays} onChange={setUsageDays} min={1} max={90} suffix="días" />
            <NumberField label="Máx. equipos" value={usageLimit} onChange={setUsageLimit} min={1} max={1000} />
          </>
        )}
        {tab === 'decommissioned' && (
          <DateRangeField from={decFrom} to={decTo} onFrom={setDecFrom} onTo={setDecTo} />
        )}
        {tab === 'command-latency' && (
          <>
            <DateRangeField from={latFrom} to={latTo} onFrom={setLatFrom} onTo={setLatTo} />
            <label className="report-field">
              <span>Carpeta</span>
              <select className="sel" value={latGroup} onChange={(e) => setLatGroup(e.target.value === '' ? '' : Number(e.target.value))}>
                <option value="">Todas</option>
                {folders.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select>
            </label>
          </>
        )}

        {tab !== 'by-app' && (
          <button className="btn btn-primary btn-sm" disabled={loading} onClick={() => void load()}>
            {loading ? 'Cargando…' : 'Actualizar'}
          </button>
        )}
        {(canExport || exportAggregate) && (
          <ExportButtons disabled={loading} onExport={onExport} />
        )}
      </div>

      {/* by-app package picker */}
      {tab === 'by-app' && (
        <div className="report-pkgpicker">
          <input
            className="input"
            placeholder="Buscar paquete o nombre…"
            value={pkgFilter}
            onChange={(e) => setPkgFilter(e.target.value)}
            style={{ maxWidth: 320, marginBottom: 10 }}
          />
          {selectedPkgs.length > 0 && (
            <div className="report-chips">
              {selectedPkgs.map((p) => (
                <button key={p} className="chip" onClick={() => togglePkg(p)} title="Quitar">
                  {p} ✕
                </button>
              ))}
            </div>
          )}
          <div className="report-pkglist">
            {catalog === null ? (
              <span className="muted">Cargando catálogo…</span>
            ) : filteredCatalog.length === 0 ? (
              <span className="muted">Sin paquetes</span>
            ) : (
              filteredCatalog.map((r) => {
                const pkg = String(r.pkg ?? '');
                const on = selectedPkgs.includes(pkg);
                return (
                  <button
                    key={pkg}
                    className={`chip ${on ? 'tone-ok' : ''}`}
                    onClick={() => togglePkg(pkg)}
                  >
                    {String(r.name || pkg)}{' '}
                    <span className="muted">· {String(r.devices ?? 0)}</span>
                  </button>
                );
              })
            )}
          </div>
          <div style={{ marginTop: 12, display: 'flex', gap: 8 }}>
            <button
              className="btn btn-primary btn-sm"
              disabled={loading || selectedPkgs.length === 0}
              onClick={() => void load()}
            >
              {loading ? 'Cargando…' : 'Ver equipos'}
            </button>
            {canExport && <ExportButtons disabled={loading} onExport={onExport} />}
          </div>
        </div>
      )}

      {error && <div className="report-error">{error}</div>}

      {result?.title && !error && (
        <div className="report-title muted">
          {result.title}
          {result.generatedAt ? ` · generado ${fmtDateTime(result.generatedAt)}` : ''}
        </div>
      )}

      <ReportTable
        result={result}
        emptyText={loading ? 'Cargando…' : 'Sin resultados para estos filtros.'}
        onRowClick={tab === 'app-installs' ? openDrill : undefined}
      />

      {/* app-installs drill-down */}
      {tab === 'app-installs' && drillPkg && (
        <div className="report-drill">
          <div className="report-drill-head">
            <strong>Equipos: {drillPkg}</strong>
            <div style={{ display: 'flex', gap: 8 }}>
              {drill && drill.rows.length > 0 && (
                <ExportButtons
                  disabled={drillLoading}
                  onExport={(f) => void downloadReport('app-install-devices', f, { pkg: drillPkg })}
                />
              )}
              <button className="btn btn-sm btn-ghost" onClick={() => setDrillPkg(null)}>
                Cerrar
              </button>
            </div>
          </div>
          <ReportTable result={drill} emptyText={drillLoading ? 'Cargando…' : 'Sin despliegues.'} />
        </div>
      )}
    </AppShell>
  );
}
