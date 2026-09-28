import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { AppShell } from '../ui/AppShell';
import { escapeHtml } from '../ui/html';
import { listFleetLocations, type FleetDevice, type FleetFix, type FleetLocations } from '../api/fleetLocations';

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

/** Distinct, legible on the light OSM tiles. */
const PALETTE = ['#2563eb', '#dc2626', '#16a34a', '#9333ea', '#ea580c', '#0891b2', '#c026d3', '#65a30d',
  '#b45309', '#4f46e5', '#be123c', '#0d9488'];

const startOfDay = (ms: number) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };

const PRESETS: Array<{ key: string; label: string; range: () => [number, number] }> = [
  { key: '1h', label: 'Last hour', range: () => [Date.now() - HOUR, Date.now()] },
  { key: 'today', label: 'Today', range: () => [startOfDay(Date.now()), Date.now()] },
  { key: 'yesterday', label: 'Yesterday', range: () => [startOfDay(Date.now()) - DAY, startOfDay(Date.now()) - 1] },
  { key: '24h', label: 'Last 24 h', range: () => [Date.now() - DAY, Date.now()] },
  { key: '7d', label: 'Last 7 days', range: () => [Date.now() - 7 * DAY, Date.now()] },
];

/** <input type="datetime-local"> speaks local wall-clock time without a zone. */
const toInput = (ms: number) => {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
};
const fromInput = (v: string) => new Date(v).getTime();
const fmt = (ms: number) => new Date(ms).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' });

/** The device's last fix at or before [t] (fixes oldest first), or null if it had none yet. */
function positionAt(fixes: FleetFix[], t: number): FleetFix | null {
  let lo = 0, hi = fixes.length - 1, best = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (fixes[mid].capturedAt <= t) { best = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return best < 0 ? null : fixes[best];
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
const nameOf = (d: FleetDevice) => d.description?.trim() || d.number;
const esc = escapeHtml;

/**
 * Fleet map: pick a time range and see where every device was — each device's trail in its own colour, and a
 * time slider that puts each device where it was at that moment (its last fix at or before it).
 */
export function FleetMapPage() {
  const [range, setRange] = useState<[number, number]>(() => PRESETS[1].range());
  const [preset, setPreset] = useState<string | null>('today');
  const [data, setData] = useState<FleetLocations | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [showTrails, setShowTrails] = useState(true);
  const [at, setAt] = useState<number>(range[1]);
  const [q, setQ] = useState('');

  const mapEl = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const trailLayer = useRef<L.LayerGroup | null>(null);
  const markerLayer = useRef<L.LayerGroup | null>(null);
  const fitted = useRef(false);

  useEffect(() => {
    const ctl = new AbortController();
    setLoading(true);
    setErr(null);
    listFleetLocations(range[0], range[1], ctl.signal)
      .then((d) => { setData(d); setAt(range[1]); fitted.current = false; })
      .catch((e) => { if (!ctl.signal.aborted) setErr(e instanceof Error ? e.message : 'Could not load locations'); })
      .finally(() => { if (!ctl.signal.aborted) setLoading(false); });
    return () => ctl.abort();
  }, [range]);

  const color = useMemo(() => {
    const m = new Map<string, string>();
    data?.devices.forEach((d, i) => m.set(d.number, PALETTE[i % PALETTE.length]));
    return m;
  }, [data]);

  const visible = useMemo(() => (data?.devices ?? []).filter((d) => !hidden.has(d.number)), [data, hidden]);

  // Map instance.
  useEffect(() => {
    if (!mapEl.current || map.current) return;
    map.current = L.map(mapEl.current, { worldCopyJump: true }).setView([4.6, -74.1], 5);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, attribution: '© OpenStreetMap contributors',
    }).addTo(map.current);
    trailLayer.current = L.layerGroup().addTo(map.current);
    markerLayer.current = L.layerGroup().addTo(map.current);
    return () => { map.current?.remove(); map.current = null; };
  }, []);

  // Trails (range-wide) + fit once per load.
  useEffect(() => {
    const m = map.current, layer = trailLayer.current;
    if (!m || !layer) return;
    layer.clearLayers();
    const all: L.LatLngExpression[] = [];
    visible.forEach((d) => {
      const pts = d.fixes.map((f) => [f.lat, f.lon] as [number, number]);
      all.push(...pts);
      if (!showTrails) return;
      const c = color.get(d.number)!;
      if (pts.length > 1) L.polyline(pts, { color: c, weight: 3, opacity: 0.55 }).addTo(layer);
      d.fixes.forEach((f) => {
        L.circleMarker([f.lat, f.lon], { radius: 3, color: c, weight: 1, fillColor: c, fillOpacity: 0.5 })
          .bindTooltip(`${esc(nameOf(d))} · ${fmt(f.capturedAt)}`)
          .addTo(layer);
      });
    });
    if (!fitted.current && all.length) {
      m.fitBounds(L.latLngBounds(all), { padding: [32, 32], maxZoom: 16 });
      fitted.current = true;
    }
    setTimeout(() => m.invalidateSize(), 0);
  }, [visible, showTrails, color]);

  // Where each device was at the slider time.
  useEffect(() => {
    const layer = markerLayer.current;
    if (!layer) return;
    layer.clearLayers();
    visible.forEach((d) => {
      const f = positionAt(d.fixes, at);
      if (!f) return;
      const c = color.get(d.number)!;
      const stale = at - f.capturedAt;
      L.circleMarker([f.lat, f.lon], { radius: 9, color: '#fff', weight: 2, fillColor: c, fillOpacity: 0.95 })
        .bindPopup(
          `<strong>${esc(nameOf(d))}</strong><br>${fmt(f.capturedAt)}` +
          (stale > 10 * 60 * 1000 ? ` <span style="opacity:.7">(${Math.round(stale / 60000)} min before)</span>` : '') +
          (f.accuracy != null ? `<br>±${esc(Math.round(Number(f.accuracy)))} m` : '') +
          `<br><a href="/devices/${encodeURIComponent(d.number)}">Open device</a>`,
        )
        .addTo(layer);
    });
  }, [visible, at, color]);

  function applyPreset(key: string) {
    const p = PRESETS.find((x) => x.key === key)!;
    setPreset(key);
    setRange(p.range());
  }

  function toggle(n: string) {
    setHidden((h) => { const s = new Set(h); if (s.has(n)) s.delete(n); else s.add(n); return s; });
  }

  const list = (data?.devices ?? []).filter((d) => !q || nameOf(d).toLowerCase().includes(q.toLowerCase())
    || d.number.toLowerCase().includes(q.toLowerCase()));
  const fixCount = visible.reduce((n, d) => n + d.fixes.length, 0);
  const placed = visible.filter((d) => positionAt(d.fixes, at)).length;

  return (
    <AppShell title="Map">
      <div className="dv-head">
        <h1>Map</h1>
        <span className="dv-count">
          {loading ? 'Loading…' : `${plural(data?.devices.length ?? 0, 'device')} · ${plural(fixCount, 'fix', 'fixes')} in range`}
        </span>
      </div>

      <div className="panel fm-controls">
        <div className="fm-presets" role="group" aria-label="Time range">
          {PRESETS.map((p) => (
            <button key={p.key} type="button" className={`btn btn-sm ${preset === p.key ? 'btn-primary' : ''}`}
                    onClick={() => applyPreset(p.key)}>{p.label}</button>
          ))}
        </div>
        <label className="fm-field">
          <span>From</span>
          <input type="datetime-local" value={toInput(range[0])}
                 onChange={(e) => { const v = fromInput(e.target.value); if (!Number.isNaN(v)) { setPreset(null); setRange([v, Math.max(v, range[1])]); } }} />
        </label>
        <label className="fm-field">
          <span>To</span>
          <input type="datetime-local" value={toInput(range[1])}
                 onChange={(e) => { const v = fromInput(e.target.value); if (!Number.isNaN(v)) { setPreset(null); setRange([Math.min(range[0], v), v]); } }} />
        </label>
        <label className="fm-check">
          <input type="checkbox" checked={showTrails} onChange={(e) => setShowTrails(e.target.checked)} />
          <span>Trails</span>
        </label>
      </div>

      {err && <div className="banner banner-alert">{err}</div>}
      {data?.truncated && (
        <div className="banner banner-warn">This range holds more fixes than the map loads at once; narrow it to see everything.</div>
      )}

      <div className="fm-cols">
        <div className="panel fm-mapwrap">
          <div ref={mapEl} className="fm-map" />
          <div className="fm-time">
            <input type="range" min={range[0]} max={range[1]} step={60_000} value={Math.min(Math.max(at, range[0]), range[1])}
                   onChange={(e) => setAt(Number(e.target.value))} aria-label="Time" />
            <span className="fm-at">
              Positions at <strong>{fmt(at)}</strong> · {placed}/{visible.length} devices
            </span>
          </div>
        </div>

        <aside className="panel fm-list">
          <input type="search" className="fm-search" placeholder="Filter devices" value={q}
                 onChange={(e) => setQ(e.target.value)} />
          {!loading && data && data.devices.length === 0 && (
            <p className="muted">No device reported a location in this range.</p>
          )}
          <ul>
            {list.map((d) => {
              const last = d.fixes[d.fixes.length - 1];
              return (
                <li key={d.number} className={hidden.has(d.number) ? 'off' : ''}>
                  <label>
                    <input type="checkbox" checked={!hidden.has(d.number)} onChange={() => toggle(d.number)} />
                    <i style={{ background: color.get(d.number) }} />
                    <span className="fm-name" title={d.number}>{nameOf(d)}</span>
                  </label>
                  <span className="fm-meta">
                    {plural(d.fixes.length, 'fix', 'fixes')} · last {fmt(last.capturedAt)}
                  </span>
                  <span className="fm-actions">
                    <button type="button" className="t-more" onClick={() => {
                      map.current?.fitBounds(L.latLngBounds(d.fixes.map((f) => [f.lat, f.lon] as [number, number])),
                        { padding: [32, 32], maxZoom: 17 });
                    }}>Zoom</button>
                    <Link to={`/devices/${encodeURIComponent(d.number)}`} className="t-more">Open</Link>
                  </span>
                </li>
              );
            })}
          </ul>
        </aside>
      </div>
    </AppShell>
  );
}
