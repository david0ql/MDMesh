import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { AppShell } from '../ui/AppShell';
import { escapeHtml } from '../ui/html';
import { listFleetLocations, type FleetDevice, type FleetFix, type FleetLocations } from '../api/fleetLocations';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import { useDevices } from '../data/useDevices';
import { listZones, listZoneStates, type Zone, type ZoneState } from '../api/geofences';
import { drawZones } from '../ui/zoneLayer';

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

/** Distinct, legible on the light OSM tiles. */
const PALETTE = ['#2563eb', '#dc2626', '#16a34a', '#9333ea', '#ea580c', '#0891b2', '#c026d3', '#65a30d',
  '#b45309', '#4f46e5', '#be123c', '#0d9488'];

const startOfDay = (ms: number) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };

const PRESETS: Array<{ key: string; label: string; range: () => [number, number] }> = [
  { key: '1h', label: 'Última hora', range: () => [Date.now() - HOUR, Date.now()] },
  { key: 'today', label: 'Hoy', range: () => [startOfDay(Date.now()), Date.now()] },
  { key: 'yesterday', label: 'Ayer', range: () => [startOfDay(Date.now()) - DAY, startOfDay(Date.now()) - 1] },
  { key: '24h', label: 'Últimas 24 h', range: () => [Date.now() - DAY, Date.now()] },
  { key: '7d', label: 'Últimos 7 días', range: () => [Date.now() - 7 * DAY, Date.now()] },
];

/** <input type="datetime-local"> speaks local wall-clock time without a zone. */
const toInput = (ms: number) => {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
};
const fromInput = (v: string) => new Date(v).getTime();
const fmt = (ms: number) => new Date(ms).toLocaleString('es-CO', { dateStyle: 'short', timeStyle: 'short' });

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
  const [folder, setFolder] = useState('all');
  // Geofences: drawn under the trails; a device that is where its zone says it must not be gets a badge.
  const [zones, setZones] = useState<Zone[]>([]);
  const [zoneStates, setZoneStates] = useState<ZoneState[]>([]);
  const [showZones, setShowZones] = useState(true);

  // Folders (scoped to what the signed-in admin can see) and the device→folder map used to
  // filter the map by carpeta. The locations endpoint has no folder, so we match by `number`
  // against the device list, which carries each device's groups.
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  useEffect(() => { listGroups().then((o) => setGroups(o.groups)).catch(() => undefined); }, []);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const { devices: allDevices } = useDevices('', 1000);
  const deviceGroups = useMemo(() => {
    const m = new Map<string, Set<number>>();
    for (const d of allDevices) if (d.number) m.set(d.number, new Set((d.groups ?? []).map((g) => g.id)));
    return m;
  }, [allDevices]);

  // The selected folder and every folder below it (null = a specific selection isn't a folder subtree).
  const subtree = useMemo(
    () => (folder !== 'all' && folder !== 'none'
      ? tree.find((n) => String(n.group.id) === folder)?.subtree ?? null : null),
    [folder, tree]);

  // Does the device (by number) belong to the chosen folder — its own folder or a sub-folder?
  const matchesFolder = useCallback((number: string) => {
    if (folder === 'all') return true;
    const gids = deviceGroups.get(number);
    if (folder === 'none') return !gids || gids.size === 0;
    if (!gids) return false;
    for (const id of gids) if (subtree ? subtree.has(id) : String(id) === folder) return true;
    return false;
  }, [folder, subtree, deviceGroups]);

  const mapEl = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const zoneLayer = useRef<L.LayerGroup | null>(null);
  const trailLayer = useRef<L.LayerGroup | null>(null);
  const markerLayer = useRef<L.LayerGroup | null>(null);
  const fitted = useRef(false);

  useEffect(() => {
    const ctl = new AbortController();
    setLoading(true);
    setErr(null);
    listFleetLocations(range[0], range[1], ctl.signal)
      .then((d) => { setData(d); setAt(range[1]); fitted.current = false; })
      .catch((e) => { if (!ctl.signal.aborted) setErr(e instanceof Error ? e.message : 'No se pudieron cargar las ubicaciones'); })
      .finally(() => { if (!ctl.signal.aborted) setLoading(false); });
    return () => ctl.abort();
  }, [range]);

  const color = useMemo(() => {
    const m = new Map<string, string>();
    data?.devices.forEach((d, i) => m.set(d.number, PALETTE[i % PALETTE.length]));
    return m;
  }, [data]);

  // The map shows the devices that are not hidden AND match the folder filter AND, when the search box has
  // text, only those matching it — so points, trails and the list all narrow to the chosen carpeta/phone.
  const visible = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (data?.devices ?? []).filter((d) =>
      !hidden.has(d.number) &&
      matchesFolder(d.number) &&
      (!needle || nameOf(d).toLowerCase().includes(needle) || d.number.toLowerCase().includes(needle)));
  }, [data, hidden, q, matchesFolder]);

  // Map instance.
  useEffect(() => {
    if (!mapEl.current || map.current) return;
    map.current = L.map(mapEl.current, { worldCopyJump: true }).setView([4.6, -74.1], 5);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, attribution: '© colaboradores de OpenStreetMap',
    }).addTo(map.current);
    zoneLayer.current = L.layerGroup().addTo(map.current);
    trailLayer.current = L.layerGroup().addTo(map.current);
    markerLayer.current = L.layerGroup().addTo(map.current);
    return () => { map.current?.remove(); map.current = null; };
  }, []);

  // Zones and who is inside/outside, refreshed with each range load.
  useEffect(() => {
    listZones().then(setZones).catch(() => setZones([]));
    listZoneStates().then(setZoneStates).catch(() => setZoneStates([]));
  }, [range]);
  useEffect(() => {
    if (zoneLayer.current) drawZones(zoneLayer.current, showZones ? zones.filter((z) => z.active) : []);
  }, [zones, showZones]);
  const inAlert = useMemo(() => {
    const names = new Map(zones.map((z) => [z.id, z.name]));
    const m = new Map<string, string[]>();
    for (const s of zoneStates) {
      if (s.violating) m.set(s.number, [...(m.get(s.number) ?? []), names.get(s.zoneId) ?? 'zona']);
    }
    return m;
  }, [zones, zoneStates]);

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
          (stale > 10 * 60 * 1000 ? ` <span style="opacity:.7">(${Math.round(stale / 60000)} min antes)</span>` : '') +
          (f.accuracy != null ? `<br>±${esc(Math.round(Number(f.accuracy)))} m` : '') +
          `<br><a href="/devices/${encodeURIComponent(d.number)}">Abrir dispositivo</a>`,
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

  const list = (data?.devices ?? []).filter((d) => matchesFolder(d.number)
    && (!q || nameOf(d).toLowerCase().includes(q.toLowerCase()) || d.number.toLowerCase().includes(q.toLowerCase())));
  const fixCount = visible.reduce((n, d) => n + d.fixes.length, 0);
  const placed = visible.filter((d) => positionAt(d.fixes, at)).length;

  return (
    <AppShell title="Mapa">
      <div className="dv-head">
        <h1>Mapa</h1>
        <span className="dv-count">
          {loading ? 'Cargando…' : `${plural(visible.length, 'dispositivo')} · ${plural(fixCount, 'posición', 'posiciones')} en el rango`}
        </span>
      </div>

      <div className="panel fm-controls">
        <div className="fm-presets" role="group" aria-label="Rango de tiempo">
          {PRESETS.map((p) => (
            <button key={p.key} type="button" className={`btn btn-sm ${preset === p.key ? 'btn-primary' : ''}`}
                    onClick={() => applyPreset(p.key)}>{p.label}</button>
          ))}
        </div>
        <label className="fm-field">
          <span>Desde</span>
          <input type="datetime-local" value={toInput(range[0])}
                 onChange={(e) => { const v = fromInput(e.target.value); if (!Number.isNaN(v)) { setPreset(null); setRange([v, Math.max(v, range[1])]); } }} />
        </label>
        <label className="fm-field">
          <span>Hasta</span>
          <input type="datetime-local" value={toInput(range[1])}
                 onChange={(e) => { const v = fromInput(e.target.value); if (!Number.isNaN(v)) { setPreset(null); setRange([Math.min(range[0], v), v]); } }} />
        </label>
        <label className="fm-check">
          <input type="checkbox" checked={showTrails} onChange={(e) => setShowTrails(e.target.checked)} />
          <span>Recorridos</span>
        </label>
        {zones.length > 0 && (
          <label className="fm-check">
            <input type="checkbox" checked={showZones} onChange={(e) => setShowZones(e.target.checked)} />
            <span>Zonas</span>
          </label>
        )}
      </div>

      {err && <div className="banner banner-alert">{err}</div>}
      {data?.truncated && (
        <div className="banner banner-warn">Este rango tiene más posiciones de las que el mapa carga a la vez; acórtalo para verlas todas.</div>
      )}

      <div className="fm-cols">
        <div className="panel fm-mapwrap">
          <div ref={mapEl} className="fm-map" />
          <div className="fm-time">
            <input type="range" min={range[0]} max={range[1]} step={60_000} value={Math.min(Math.max(at, range[0]), range[1])}
                   onChange={(e) => setAt(Number(e.target.value))} aria-label="Hora" />
            <span className="fm-at">
              Posiciones a las <strong>{fmt(at)}</strong> · {placed}/{visible.length} dispositivos
            </span>
          </div>
        </div>

        <aside className="panel fm-list">
          <input type="search" className="fm-search" placeholder="Filtrar dispositivos" value={q}
                 onChange={(e) => setQ(e.target.value)} />
          <select className="fm-search" value={folder} onChange={(e) => setFolder(e.target.value)}
                  aria-label="Filtrar por carpeta">
            <option value="all">Carpeta: todas</option>
            {tree.map((n) => (
              <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>
            ))}
            <option value="none">Sin carpeta</option>
          </select>
          {!loading && data && data.devices.length === 0 && (
            <p className="muted">Ningún dispositivo reportó ubicación en este rango.</p>
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
                    {plural(d.fixes.length, 'posición', 'posiciones')} · última {fmt(last.capturedAt)}
                  </span>
                  {inAlert.has(d.number) && (
                    <span className="fm-meta">
                      <span className="chip tone-alert" title={inAlert.get(d.number)!.join(', ')}>
                        Fuera de lugar: {inAlert.get(d.number)!.join(', ')}
                      </span>
                    </span>
                  )}
                  <span className="fm-actions">
                    <button type="button" className="t-more" onClick={() => {
                      map.current?.fitBounds(L.latLngBounds(d.fixes.map((f) => [f.lat, f.lon] as [number, number])),
                        { padding: [32, 32], maxZoom: 17 });
                    }}>Acercar</button>
                    <Link to={`/devices/${encodeURIComponent(d.number)}`} className="t-more">Abrir</Link>
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
