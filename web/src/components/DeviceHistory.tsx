import { useEffect, useMemo, useRef, useState } from 'react';
import { getHistory, type DeviceHistory as History, type MetricSample } from '../api/events';

type Device = { number: string };

const RANGES = [
  { days: 1, label: '24 horas' },
  { days: 7, label: '7 días' },
  { days: 30, label: '30 días' },
];

const SIGNAL = ['Sin señal', 'Mala', 'Regular', 'Buena', 'Excelente'];
const gb = (b: number) => b / 1024 ** 3;
const fmtTime = (t: number, days: number) =>
  new Date(t).toLocaleString('es-CO', days > 1 ? { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' } : { hour: '2-digit', minute: '2-digit' });
const fmtDur = (ms: number) => {
  const m = Math.round(ms / 60000);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  return h < 48 ? `${h} h ${m % 60} min` : `${Math.round(h / 24)} días`;
};

/** Width of a container, kept up to date (charts draw at their real pixel width). */
function useWidth<T extends HTMLElement>(): [React.RefObject<T | null>, number] {
  const ref = useRef<T | null>(null);
  const [w, setW] = useState(600);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(260, Math.floor(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

interface Series {
  title: string;
  unit: string;
  /** Value of a sample, or null when the sample has none (the line breaks there). */
  value: (m: MetricSample) => number | null;
  format: (v: number, m: MetricSample) => string;
  min?: number;
  max?: number;
  ticks?: (lo: number, hi: number) => number[];
}

const PAD = { l: 44, r: 12, t: 10, b: 22 };
const H = 150;

/**
 * One measure over time: a 2px line (broken where the device was silent longer than the connection gap, never
 * interpolated), a recessive grid, and a crosshair + tooltip on hover.
 */
function LineChart({ s, data, from, to, gapMs, days }: {
  s: Series; data: MetricSample[]; from: number; to: number; gapMs: number; days: number;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const pts = useMemo(() => data.map((m) => ({ m, v: s.value(m) })).filter((p): p is { m: MetricSample; v: number } => p.v != null), [data, s]);
  if (pts.length === 0) {
    return (
      <figure className="hc">
        <figcaption className="hc-title">{s.title}</figcaption>
        <p className="muted small hc-empty">Sin datos en este periodo.</p>
      </figure>
    );
  }
  const vals = pts.map((p) => p.v);
  let lo = s.min ?? Math.min(...vals);
  let hi = s.max ?? Math.max(...vals);
  if (hi === lo) { hi += 1; lo -= 1; }
  const iw = width - PAD.l - PAD.r;
  const ih = H - PAD.t - PAD.b;
  const x = (t: number) => PAD.l + ((t - from) / (to - from)) * iw;
  const y = (v: number) => PAD.t + (1 - (v - lo) / (hi - lo)) * ih;
  let d = '';
  pts.forEach((p, i) => {
    const gap = i === 0 || p.m.ts - pts[i - 1].m.ts > gapMs;
    d += `${gap ? 'M' : 'L'}${x(p.m.ts).toFixed(1)},${y(p.v).toFixed(1)}`;
  });
  const ticks = s.ticks ? s.ticks(lo, hi) : [lo, (lo + hi) / 2, hi];
  const xTicks = Array.from({ length: 5 }, (_, i) => from + ((to - from) * i) / 4);
  const onMove = (e: React.MouseEvent<SVGRectElement>) => {
    const box = (e.currentTarget as SVGRectElement).getBoundingClientRect();
    const t = from + ((e.clientX - box.left) / box.width) * (to - from);
    let best = 0;
    pts.forEach((p, i) => { if (Math.abs(p.m.ts - t) < Math.abs(pts[best].m.ts - t)) best = i; });
    setHover(best);
  };
  const hp = hover == null ? null : pts[hover];
  const last = pts[pts.length - 1];
  return (
    <figure className="hc">
      <figcaption className="hc-title">
        {s.title} <span className="hc-now">ahora {s.format(last.v, last.m)}</span>
      </figcaption>
      <div ref={ref} className="hc-plot">
        <svg width={width} height={H} role="img" aria-label={`${s.title} en el tiempo`}>
          {ticks.map((v) => (
            <g key={v}>
              <line className="hc-grid" x1={PAD.l} x2={width - PAD.r} y1={y(v)} y2={y(v)} />
              <text className="hc-axis" x={PAD.l - 6} y={y(v) + 4} textAnchor="end">{s.format(v, last.m).replace(/\s.*$/, '')}</text>
            </g>
          ))}
          {xTicks.map((t, i) => (
            <text key={t} className="hc-axis" x={x(t)} y={H - 6} textAnchor={i === 0 ? 'start' : i === 4 ? 'end' : 'middle'}>{fmtTime(t, days)}</text>
          ))}
          <path className="hc-line" d={d} />
          {hp && (
            <g>
              <line className="hc-cross" x1={x(hp.m.ts)} x2={x(hp.m.ts)} y1={PAD.t} y2={PAD.t + ih} />
              <circle className="hc-dot" cx={x(hp.m.ts)} cy={y(hp.v)} r={4.5} />
            </g>
          )}
          <rect x={PAD.l} y={0} width={iw} height={H} fill="transparent" onMouseMove={onMove} onMouseLeave={() => setHover(null)} />
        </svg>
        {hp && (
          <div className="hc-tip" style={{ left: Math.min(Math.max(x(hp.m.ts) - 70, 0), width - 150), top: 0 }}>
            <b>{s.format(hp.v, hp.m)}</b>
            <span>{new Date(hp.m.ts).toLocaleString('es-CO')}</span>
          </div>
        )}
      </div>
    </figure>
  );
}

/** Connected / disconnected along the same time axis (status colours, with a label legend). */
function ConnectionStrip({ h, from, to, days }: { h: History; from: number; to: number; days: number }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [tip, setTip] = useState<{ x: number; text: string } | null>(null);
  const iw = width - PAD.l - PAD.r;
  const x = (t: number) => PAD.l + ((Math.max(from, Math.min(to, t)) - from) / (to - from)) * iw;
  const sessions = h.connections.map((c) => ({ a: c.connectedat, b: c.lastseenat }));
  return (
    <figure className="hc">
      <figcaption className="hc-title">
        Conexión
        <span className="hc-legend"><i className="lg on" /> Conectado <i className="lg off" /> Sin conexión</span>
      </figcaption>
      <div ref={ref} className="hc-plot">
        <svg width={width} height={34} role="img" aria-label="Periodos conectado y sin conexión">
          <rect className="hc-off" x={PAD.l} y={6} width={iw} height={16} rx={4} />
          {sessions.map((c) => (
            <rect key={c.a} className="hc-on" x={x(c.a)} y={6} width={Math.max(2, x(c.b) - x(c.a))} height={16} rx={2}
                  onMouseMove={() => setTip({ x: x(c.a), text: `Conectado ${fmtTime(c.a, 7)} → ${fmtTime(c.b, 7)} (${fmtDur(c.b - c.a)})` })}
                  onMouseLeave={() => setTip(null)} />
          ))}
        </svg>
        {tip && <div className="hc-tip" style={{ left: Math.min(Math.max(tip.x, 0), width - 260), top: 28 }}>{tip.text}</div>}
      </div>
      <p className="muted small">{sessions.length} conexiones en {days === 1 ? '24 horas' : `${days} días`}.</p>
    </figure>
  );
}

const SERIES: Series[] = [
  { title: 'Batería', unit: '%', value: (m) => m.battery ?? null, format: (v, m) => `${Math.round(v)} %${m.charging ? ' ⚡' : ''}`, min: 0, max: 100, ticks: () => [0, 50, 100] },
  { title: 'Señal Wi‑Fi (RSSI)', unit: 'dBm', value: (m) => (m.networktype === 'wifi' ? m.wifirssi ?? null : null), format: (v) => `${v} dBm`, min: -100, max: -30, ticks: () => [-90, -67, -50] },
  { title: 'Calidad de señal (Wi‑Fi o datos)', unit: '', value: (m) => m.signallevel ?? null, format: (v) => SIGNAL[Math.round(v)] ?? String(v), min: 0, max: 4, ticks: () => [0, 2, 4] },
  { title: 'Almacenamiento libre', unit: 'GB', value: (m) => (m.freestoragebytes == null ? null : gb(m.freestoragebytes)), format: (v) => `${v.toLocaleString('es-CO', { maximumFractionDigits: 1 })} GB`, min: 0 },
  { title: 'RAM libre', unit: 'GB', value: (m) => (m.freerambytes == null ? null : gb(m.freerambytes)), format: (v) => `${v.toLocaleString('es-CO', { maximumFractionDigits: 1 })} GB`, min: 0 },
];

/** Traceability: how this device behaved over time (connection, battery, signal/RSSI, free space), with a table view. */
export function DeviceHistory({ device }: { device: Device }) {
  const [days, setDays] = useState(7);
  const [h, setH] = useState<History | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [table, setTable] = useState(false);

  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        const v = await getHistory(device.number, days);
        if (on) { setH(v); setErr(null); }
      } catch (e) {
        if (on) setErr(e instanceof Error ? e.message : String(e));
      }
      if (on) t = setTimeout(() => void load(), 60000);
    };
    void load();
    return () => { on = false; clearTimeout(t); };
  }, [device.number, days]);

  const to = Date.now();
  const from = h?.from ?? to - days * 86400000;
  const connected = useMemo(() => {
    if (!h) return null;
    const ms = h.connections.reduce((a, c) => a + Math.max(0, Math.min(to, c.lastseenat) - Math.max(from, c.connectedat)), 0);
    return Math.min(100, Math.round((ms / (to - from)) * 100));
  }, [h, from, to]);
  const rssi = h?.metrics.filter((m) => m.networktype === 'wifi' && m.wifirssi != null).map((m) => m.wifirssi as number) ?? [];

  return (
    <div className="panel">
      <div className="hc-head">
        <h2 className="panel-title">Historial</h2>
        <div className="seg" role="group" aria-label="Periodo">
          {RANGES.map((r) => (
            <button key={r.days} type="button" className={days === r.days ? 'on' : ''} onClick={() => setDays(r.days)}>{r.label}</button>
          ))}
        </div>
        <button type="button" className="btn btn-ghost" onClick={() => setTable((v) => !v)}>{table ? 'Ver gráficas' : 'Ver como tabla'}</button>
      </div>
      {err && <div className="banner banner-alert">No se pudo cargar el historial: {err}</div>}
      {!h ? (
        <p className="muted">Cargando…</p>
      ) : h.metrics.length === 0 && h.connections.length === 0 ? (
        <p className="muted">Aún no hay historial. Se guarda una muestra cada 5 minutos mientras el equipo se reporta (se conservan 30 días).</p>
      ) : (
        <>
          <div className="hc-tiles">
            <div><span>Tiempo conectado</span><b>{connected ?? '—'} %</b></div>
            <div><span>RSSI Wi‑Fi promedio</span><b>{rssi.length ? `${Math.round(rssi.reduce((a, b) => a + b, 0) / rssi.length)} dBm` : '—'}</b></div>
            <div><span>Muestras</span><b>{h.metrics.length}</b></div>
          </div>
          {table ? (
            <div className="hc-table">
              <table>
                <thead><tr><th>Fecha</th><th>Batería</th><th>Red</th><th>RSSI</th><th>Señal</th><th>Libre</th><th>RAM libre</th><th>Quiosco</th></tr></thead>
                <tbody>
                  {[...h.metrics].reverse().map((m) => (
                    <tr key={m.ts}>
                      <td>{new Date(m.ts).toLocaleString('es-CO')}</td>
                      <td>{m.battery == null ? '—' : `${m.battery} %${m.charging ? ' ⚡' : ''}`}</td>
                      <td>{m.networktype === 'wifi' ? 'Wi‑Fi' : m.networktype === 'cellular' ? 'Datos' : m.networktype ?? '—'}</td>
                      <td>{m.wifirssi == null ? '—' : `${m.wifirssi} dBm`}</td>
                      <td>{m.signallevel == null ? '—' : SIGNAL[m.signallevel]}</td>
                      <td>{m.freestoragebytes == null ? '—' : `${gb(m.freestoragebytes).toFixed(1)} GB`}</td>
                      <td>{m.freerambytes == null ? '—' : `${gb(m.freerambytes).toFixed(1)} GB`}</td>
                      <td>{m.kioskactive ? 'Sí' : 'No'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <>
              <ConnectionStrip h={h} from={from} to={to} days={days} />
              {SERIES.map((s) => (
                <LineChart key={s.title} s={s} data={h.metrics} from={from} to={to} gapMs={h.gapMs} days={days} />
              ))}
            </>
          )}
        </>
      )}
    </div>
  );
}
