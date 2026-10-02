import { useEffect, useMemo, useState } from 'react';
import { getAppUsage, type AppUsageRow } from '../api/events';
import { queueCommand } from '../api/commands';

type Device = { number: string; description?: string };
type SortKey = 'time' | 'data' | 'battery';

interface AppTotal { pkg: string; label: string; time: number; wifi: number; mobile: number; battery: number }

const IDLE = 'android.idle';
const dayKey = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const fmtTime = (ms: number) => {
  const m = Math.round(ms / 60000);
  if (m < 1) return ms > 0 ? '< 1 min' : '—';
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} min`;
};
const fmtBytes = (b: number) =>
  b <= 0 ? '—' : b >= 1024 ** 3 ? `${(b / 1024 ** 3).toLocaleString('es-CO', { maximumFractionDigits: 2 })} GB`
    : b >= 1024 ** 2 ? `${(b / 1024 ** 2).toLocaleString('es-CO', { maximumFractionDigits: 1 })} MB`
      : `${Math.max(1, Math.round(b / 1024)).toLocaleString('es-CO')} KB`;
const fmtPct = (p: number) => (p <= 0 ? '—' : `${p.toLocaleString('es-CO', { maximumFractionDigits: 1 })} %`);
const fmtDay = (k: string) => new Date(`${k}T12:00:00`).toLocaleDateString('es-CO', { weekday: 'short', day: 'numeric', month: 'short' });

/** The top apps by one measure: thin bars from a common baseline, one hue, values in text. */
function TopBars({ title, note, apps, value, format }: {
  title: string; note?: string; apps: AppTotal[]; value: (a: AppTotal) => number; format: (a: AppTotal) => string;
}) {
  const top = [...apps].filter((a) => value(a) > 0).sort((a, b) => value(b) - value(a)).slice(0, 8);
  const max = top.length ? value(top[0]) : 0;
  return (
    <figure className="au-top">
      <figcaption>{title}{note && <span className="muted"> {note}</span>}</figcaption>
      {top.length === 0 ? <p className="muted small">Sin datos en este periodo.</p> : (
        <ul>
          {top.map((a) => (
            <li key={a.pkg} title={`${a.label}: ${format(a)}`}>
              <span className="au-nm">{a.label}</span>
              <span className="au-bar"><i style={{ width: `${Math.max(2, (value(a) / max) * 100)}%` }} /></span>
              <span className="au-v">{format(a)}</span>
            </li>
          ))}
        </ul>
      )}
    </figure>
  );
}

/**
 * Per-app usage reports of one device: time on screen, data (Wi-Fi and mobile) and the battery estimate, for one day
 * or a range, with a table that downloads as CSV. Battery is an estimate (the battery lost while the app was on
 * screen): Android gives no per-app battery figure to a management agent.
 */
export function AppUsagePanel({ device, usageAccess }: { device: Device; usageAccess?: boolean }) {
  const [rows, setRows] = useState<AppUsageRow[] | null>(null);
  const [range, setRange] = useState('today');
  const [sort, setSort] = useState<SortKey>('time');
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    let on = true;
    const load = () => getAppUsage(device.number, 30).then((r) => { if (on) setRows(r); }).catch(() => { if (on) setRows([]); });
    void load();
    const t = setInterval(load, 60000);
    return () => { on = false; clearInterval(t); };
  }, [device.number]);

  const today = dayKey(new Date());
  const yesterday = dayKey(new Date(Date.now() - 86400000));
  const days = useMemo(() => [...new Set((rows ?? []).map((r) => r.day))].sort().reverse(), [rows]);
  const inRange = useMemo(() => {
    if (!rows) return [];
    if (range === 'today') return rows.filter((r) => r.day === today);
    if (range === 'yesterday') return rows.filter((r) => r.day === yesterday);
    if (range === '7' || range === '30') {
      const from = dayKey(new Date(Date.now() - (Number(range) - 1) * 86400000));
      return rows.filter((r) => r.day >= from);
    }
    return rows.filter((r) => r.day === range);
  }, [rows, range, today, yesterday]);

  const apps = useMemo(() => {
    const m = new Map<string, AppTotal>();
    for (const r of inRange) {
      const a = m.get(r.pkg) ?? { pkg: r.pkg, label: r.pkg === IDLE ? 'Pantalla apagada / sistema' : r.label || r.pkg, time: 0, wifi: 0, mobile: 0, battery: 0 };
      a.time += r.foregroundms; a.wifi += r.wifibytes; a.mobile += r.mobilebytes; a.battery += r.batterypct;
      m.set(r.pkg, a);
    }
    const key = (a: AppTotal) => (sort === 'time' ? a.time : sort === 'data' ? a.wifi + a.mobile : a.battery);
    return [...m.values()].sort((a, b) => key(b) - key(a));
  }, [inRange, sort]);
  const total = apps.reduce((t, a) => ({ time: t.time + (a.pkg === IDLE ? 0 : a.time), wifi: t.wifi + a.wifi, mobile: t.mobile + a.mobile, battery: t.battery + a.battery }),
    { time: 0, wifi: 0, mobile: 0, battery: 0 });

  const csv = () => {
    const head = ['Día', 'App', 'Paquete', 'Tiempo en pantalla (min)', 'Wi-Fi (MB)', 'Datos móviles (MB)', 'Batería estimada (%)'];
    const lines = [...inRange].sort((a, b) => (a.day === b.day ? b.foregroundms - a.foregroundms : a.day < b.day ? 1 : -1)).map((r) => [
      r.day, r.pkg === IDLE ? 'Pantalla apagada / sistema' : r.label || r.pkg, r.pkg, (r.foregroundms / 60000).toFixed(1),
      (r.wifibytes / 1024 ** 2).toFixed(2), (r.mobilebytes / 1024 ** 2).toFixed(2), r.batterypct.toFixed(2),
    ]);
    const text = [head, ...lines].map((l) => l.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(';')).join('\r\n');
    const url = URL.createObjectURL(new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `uso-apps-${device.description || device.number}-${range}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const askAccess = async () => {
    try {
      await queueCommand(device.number, { type: 'device.storageAccess', requiresCapability: 'device.storageAccess', payload: JSON.stringify({ kind: 'usage' }) });
      setMsg('Se abrió el ajuste «Acceso de uso» en el teléfono: quien lo tenga debe activar DallyControl.');
    } catch (e) {
      setMsg(`No se pudo enviar: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  return (
    <div className="panel">
      <div className="hc-head">
        <h2 className="panel-title">Uso de apps</h2>
        <select className="sel" aria-label="Periodo" value={range} onChange={(e) => setRange(e.target.value)} data-testid="usage-range">
          <option value="today">Hoy</option>
          <option value="yesterday">Ayer</option>
          <option value="7">Últimos 7 días</option>
          <option value="30">Últimos 30 días</option>
          {days.filter((d) => d !== today && d !== yesterday).map((d) => <option key={d} value={d}>{fmtDay(d)}</option>)}
        </select>
        <button type="button" className="btn btn-ghost" disabled={inRange.length === 0} onClick={csv}>Descargar CSV</button>
      </div>
      {usageAccess === false && (
        <div className="banner banner-warn">
          Este teléfono no tiene activo el «acceso de uso» para DallyControl: sin él solo se ven los datos consumidos, no el tiempo en
          pantalla ni la batería.{' '}
          <button className="btn btn-sm" type="button" onClick={() => void askAccess()}>Pedir acceso de uso</button>
        </div>
      )}
      {msg && <p className="small">{msg}</p>}
      {rows == null ? (
        <p className="muted">Cargando…</p>
      ) : rows.length === 0 ? (
        <p className="muted">Aún no hay informes. El teléfono envía el uso por app cada 10 minutos (requiere agente 0.6.0 o superior) y se guardan 90 días.</p>
      ) : (
        <>
          <div className="hc-tiles">
            <div><span>Tiempo en pantalla</span><b>{fmtTime(total.time)}</b></div>
            <div><span>Datos Wi‑Fi</span><b>{fmtBytes(total.wifi)}</b></div>
            <div><span>Datos móviles</span><b>{fmtBytes(total.mobile)}</b></div>
            <div><span>Batería gastada (estimada)</span><b>{fmtPct(total.battery)}</b></div>
          </div>
          <div className="au-tops">
            <TopBars title="Apps más usadas" note="(tiempo en pantalla)" apps={apps.filter((a) => a.pkg !== IDLE)} value={(a) => a.time} format={(a) => fmtTime(a.time)} />
            <TopBars title="Apps que más datos consumen" note="(Wi‑Fi + móviles)" apps={apps} value={(a) => a.wifi + a.mobile} format={(a) => fmtBytes(a.wifi + a.mobile)} />
            <TopBars title="Apps que más batería consumen" note="(estimado)" apps={apps} value={(a) => a.battery} format={(a) => fmtPct(a.battery)} />
          </div>
          <p className="muted small">
            La batería por app es una estimación: es la batería que bajó mientras esa app estaba en pantalla (Android no entrega el
            consumo exacto por app a un agente de administración).
          </p>
          <div className="hc-table">
            <table>
              <thead>
                <tr>
                  <th>App</th>
                  <th><button type="button" className={`au-sort ${sort === 'time' ? 'on' : ''}`} onClick={() => setSort('time')}>Tiempo en pantalla</button></th>
                  <th>Wi‑Fi</th>
                  <th><button type="button" className={`au-sort ${sort === 'data' ? 'on' : ''}`} onClick={() => setSort('data')}>Datos móviles</button></th>
                  <th><button type="button" className={`au-sort ${sort === 'battery' ? 'on' : ''}`} onClick={() => setSort('battery')}>Batería (est.)</button></th>
                </tr>
              </thead>
              <tbody>
                {apps.map((a) => (
                  <tr key={a.pkg}>
                    <td><b>{a.label}</b>{a.pkg !== IDLE && <div className="muted small mono">{a.pkg}</div>}</td>
                    <td>{a.pkg === IDLE ? '—' : fmtTime(a.time)}</td><td>{fmtBytes(a.wifi)}</td><td>{fmtBytes(a.mobile)}</td><td>{fmtPct(a.battery)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
