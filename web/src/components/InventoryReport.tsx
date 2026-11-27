import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useToast } from '../ui/toast';
import { fmtDateTime, DASH } from '../ui/format';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import {
  downloadInventory, reportInventory,
  type Breakdown, type ExportFormat, type InventoryResult, type ReportColumn, type ReportRow,
} from '../api/reports';

type Alert = 'all' | 'online' | 'offline' | 'lowbattery' | 'storagefull' | 'offlinelong' | 'agentoutdated';

/** The columns the on-screen table shows (the files carry all of them). */
const SCREEN_COLS = ['name', 'group', 'status', 'minutes', 'stateat', 'model', 'android', 'agent', 'battery', 'charging',
  'freegb', 'totalgb', 'usedpct', 'network', 'signal', 'netname', 'kiosk', 'alerts'];

function cell(col: ReportColumn, row: ReportRow): React.ReactNode {
  const v = row[col.key];
  if (col.key === 'name' && typeof row.number === 'string') {
    return <Link className="row-link-a" to={`/devices/${encodeURIComponent(row.number)}`}>{String(v ?? row.number)}</Link>;
  }
  if (v === null || v === undefined || v === '') return <span className="muted">{DASH}</span>;
  if (col.type === 'datetime') return fmtDateTime(Number(v));
  if (col.key === 'status') return <span className={row.online ? 'data-age fresh' : 'data-age aging'}>{String(v)}</span>;
  if (col.key === 'minutes') {
    const m = Number(v);
    return m < 60 ? `${m} min` : m < 1440 ? `${Math.floor(m / 60)} h` : `${Math.floor(m / 1440)} d`;
  }
  if (col.type === 'number') return typeof v === 'number' ? v.toLocaleString('es-CO') : String(v);
  return String(v);
}

function Bars({ title, items }: { title: string; items: Breakdown[] }) {
  const max = Math.max(1, ...items.map((i) => i.count));
  const top = items.slice(0, 8);
  const rest = items.slice(8).reduce((n, i) => n + i.count, 0);
  return (
    <div className="inv-breakdown">
      <h4>{title}</h4>
      {top.map((i) => (
        <div className="inv-bar-row" key={i.label}>
          <span className="lbl" title={i.label}>{i.label}</span>
          <span className="inv-bar"><i style={{ width: `${Math.max(3, (i.count * 100) / max)}%` }} /></span>
          <span className="n">{i.count}</span>
        </div>
      ))}
      {rest > 0 && <div className="inv-bar-row"><span className="lbl muted">Otros</span><span /><span className="n">{rest}</span></div>}
      {items.length === 0 && <span className="muted">Sin datos</span>}
    </div>
  );
}

/** Reportes → "Inventario de la flota": summary tiles, breakdowns, the device table and its Excel/CSV export. */
export function InventoryReport() {
  const toast = useToast();
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const [group, setGroup] = useState('all');
  const [lowBattery, setLowBattery] = useState(20);
  const [storagePct, setStoragePct] = useState(90);
  const [offlineDays, setOfflineDays] = useState(7);
  const [sep, setSep] = useState(';');
  const [res, setRes] = useState<InventoryResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [alert, setAlert] = useState<Alert>('all');

  const params = useMemo(() => ({
    group: group === 'all' ? null : Number(group), lowBattery, storagePct, offlineDays,
  }), [group, lowBattery, storagePct, offlineDays]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRes(await reportInventory(params));
    } catch (e) {
      setError((e as Error).message || 'No se pudo cargar el inventario');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => { listGroups().then((o) => setGroups(o.groups)).catch(() => undefined); }, []);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void load(); }, [group]);

  const s = res?.summary;
  const rows = useMemo(() => {
    const all = res?.rows ?? [];
    switch (alert) {
      case 'online': return all.filter((r) => r.online === true);
      case 'offline': return all.filter((r) => r.online !== true);
      case 'all': return all;
      default: return all.filter((r) => r[alert] === true);
    }
  }, [res, alert]);
  const cols = useMemo(
    () => SCREEN_COLS.map((k) => res?.columns.find((c) => c.key === k)).filter((c): c is ReportColumn => !!c),
    [res],
  );

  const onExport = async (f: ExportFormat) => {
    try {
      await downloadInventory(f, params, sep);
    } catch (e) {
      toast.push('err', 'Error', (e as Error).message || 'No se pudo exportar');
    }
  };

  const num = (label: string, value: number, set: (n: number) => void, min: number, max: number, suffix: string) => (
    <label className="report-field">
      <span>{label}</span>
      <span className="report-field-input">
        <input className="input" type="number" min={min} max={max} value={value} style={{ width: 80 }}
          onChange={(e) => { const n = Number(e.target.value); if (Number.isFinite(n)) set(Math.max(min, Math.min(max, n))); }} />
        <span className="muted">{suffix}</span>
      </span>
    </label>
  );
  const tile = (id: Alert, value: number | undefined, label: string, tone = '') => (
    <button className={`inv-tile ${tone} ${alert === id ? 'on' : ''}`} onClick={() => setAlert(alert === id ? 'all' : id)}
      title="Clic para ver solo estos equipos en la tabla">
      <b>{value ?? 0}</b><span>{label}</span>
    </button>
  );

  return (
    <div data-testid="inventory-report">
      <div className="report-toolbar">
        <label className="report-field">
          <span>Carpeta</span>
          <select className="sel" value={group} onChange={(e) => setGroup(e.target.value)}>
            <option value="all">Todas</option>
            {tree.map((n) => <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>)}
          </select>
        </label>
        {num('Batería baja', lowBattery, setLowBattery, 1, 100, '% o menos')}
        {num('Almacenamiento casi lleno', storagePct, setStoragePct, 1, 100, '% usado o más')}
        {num('Sin conexión por más de', offlineDays, setOfflineDays, 1, 3660, 'días')}
        <button className="btn btn-primary btn-sm" disabled={loading} onClick={() => void load()}>
          {loading ? 'Cargando…' : 'Actualizar'}
        </button>
        <div className="report-export">
          <select className="sel" value={sep} onChange={(e) => setSep(e.target.value)} aria-label="Separador del CSV" title="Separador del CSV">
            <option value=";">CSV con ;</option>
            <option value=",">CSV con ,</option>
            <option value="tab">CSV con tabulador</option>
            <option value="|">CSV con |</option>
          </select>
          <button className="btn btn-sm" disabled={loading || !res} onClick={() => void onExport('xlsx')}>Excel</button>
          <button className="btn btn-sm" disabled={loading || !res} onClick={() => void onExport('csv')}>CSV</button>
        </div>
      </div>

      {error && <div className="report-error">{error}</div>}

      {s && (
        <>
          <div className="inv-tiles">
            {tile('all', s.total, 'Dispositivos')}
            {tile('online', s.online, `En línea (últimos ${s.onlineWindowMinutes} min)`, 'ok')}
            {tile('offline', s.offline, 'Sin conexión')}
            {tile('offlinelong', s.offlineLong, `Sin conexión > ${s.offlineDays} días`, s.offlineLong ? 'warn' : '')}
            {tile('lowbattery', s.lowBattery, `Batería ≤ ${s.lowBatteryPct} %`, s.lowBattery ? 'warn' : '')}
            {tile('storagefull', s.storageAlmostFull, `Almacenamiento ≥ ${s.storageUsedPct} % usado`, s.storageAlmostFull ? 'warn' : '')}
            {tile('agentoutdated', s.outdatedAgent, `Agente desactualizado${s.latestAgent ? ` (última ${s.latestAgent})` : ''}`, s.outdatedAgent ? 'warn' : '')}
          </div>
          <div className="inv-breakdowns">
            <Bars title="Por versión de Android" items={s.byAndroid} />
            <Bars title="Por modelo" items={s.byModel} />
            <Bars title="Por versión del agente" items={s.byAgent} />
          </div>
        </>
      )}

      {res && (
        <div className="report-title muted">
          {res.title} · {rows.length} de {res.rows.length} equipos · generado {fmtDateTime(res.generatedAt)}
          {alert !== 'all' && <> · <button className="btn btn-sm btn-ghost" onClick={() => setAlert('all')}>Ver todos</button></>}
        </div>
      )}
      {res && rows.length === 0 && <div className="report-empty muted">Sin equipos para este filtro.</div>}
      {res && rows.length > 0 && (
        <div className="report-table-wrap">
          <table className="table">
            <thead><tr>{cols.map((c) => <th key={c.key}>{c.label}</th>)}</tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={String(r.number ?? i)}>{cols.map((c) => <td key={c.key}>{cell(c, r)}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
