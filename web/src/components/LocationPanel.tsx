import { useCallback, useEffect, useMemo, useState } from 'react';
import type { LocationFix } from '../api/deviceLocations';
import { getTrack, downloadTrack, listDeviceZones, type DeviceZone } from '../api/geofences';
import { LocationMap } from './LocationMap';
import { fmtRelative } from '../ui/format';
import { RULE_LABEL } from '../ui/zoneLayer';

const fmt = (ms: number) => new Date(ms).toLocaleString('es-CO', { dateStyle: 'short', timeStyle: 'short' });
const DAY = 24 * 60 * 60 * 1000;
/** Positions fetched per request; "Cargar anteriores" brings the next page. */
const PAGE = 1000;

const PRESETS: { key: string; label: string; days: number }[] = [
  { key: '1d', label: 'Últimas 24 h', days: 1 },
  { key: '7d', label: '7 días', days: 7 },
  { key: '30d', label: '30 días', days: 30 },
  { key: '90d', label: '90 días', days: 90 },
];

/** <input type="datetime-local"> speaks local wall-clock time without a zone. */
const toInput = (ms: number) => {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
};

/**
 * A device's location history: pick a date range, see the route on the map (with the zones that apply to the
 * device and whether it is inside or outside each), page back through older positions and export the route.
 */
export function LocationPanel({ device }: { device: { number: string } }) {
  const [range, setRange] = useState<[number, number]>(() => [Date.now() - 7 * DAY, Date.now()]);
  const [preset, setPreset] = useState<string | null>('7d');
  const [fixes, setFixes] = useState<LocationFix[] | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [maxAccuracy, setMaxAccuracy] = useState(100);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [at, setAt] = useState<number | null>(null);
  const [zones, setZones] = useState<DeviceZone[]>([]);
  const [hideLow, setHideLow] = useState(false);

  const load = useCallback(async (from: number, to: number, pg: number, previous: LocationFix[]) => {
    setErr(null);
    setLoading(true);
    try {
      const t = await getTrack(device.number, from, to, pg, PAGE);
      setFixes(pg === 0 ? t.fixes : [...previous, ...t.fixes]); // newest first; older pages go after
      setTotal(t.total);
      setPage(pg);
      setMaxAccuracy(t.maxAccuracyM);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'No se pudieron cargar las ubicaciones');
    } finally {
      setLoading(false);
    }
  }, [device.number]);

  useEffect(() => {
    setFixes(null);
    void load(range[0], range[1], 0, []);
  }, [range, load]);

  useEffect(() => {
    listDeviceZones(device.number).then(setZones).catch(() => setZones([]));
  }, [device.number, fixes?.length]);

  function applyPreset(key: string) {
    const p = PRESETS.find((x) => x.key === key)!;
    setPreset(key);
    setRange([Date.now() - p.days * DAY, Date.now()]);
  }

  async function exportAs(format: 'xlsx' | 'csv') {
    try {
      await downloadTrack(device.number, range[0], range[1], format);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'No se pudo exportar');
    }
  }

  const shown = useMemo(() => (fixes ? (hideLow ? fixes.filter((f) => !f.lowAccuracy) : fixes) : []), [fixes, hideLow]);
  const lowCount = useMemo(() => (fixes ?? []).filter((f) => f.lowAccuracy).length, [fixes]);

  // Oldest → newest, for the time slider's range and scrubbing.
  const chrono = useMemo(() => [...shown].reverse(), [shown]);
  const tMin = chrono[0]?.capturedAt;
  const tMax = chrono[chrono.length - 1]?.capturedAt;
  const hasSlider = chrono.length > 1 && tMin != null && tMax != null && tMax > tMin;
  const latest = fixes?.[0];

  // Default the scrubber to the latest fix whenever the data (re)loads.
  useEffect(() => { setAt(tMax ?? null); }, [tMax]);

  return (
    <div className="panel">
      <div className="panel-head keep">
        <h2 className="panel-title">Historial de ubicación</h2>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          {fixes && (
            <span className="muted">
              {fixes.length < total ? `${fixes.length} de ${total} puntos` : `${total} ${total === 1 ? 'punto' : 'puntos'}`}
              {latest ? ` · último ${fmtRelative(latest.capturedAt)}` : ''}
            </span>
          )}
          <button className="btn" disabled={loading}
                  onClick={() => (preset ? applyPreset(preset) : setRange([range[0], range[1]]))}>Actualizar</button>
        </div>
      </div>

      <div className="fm-controls loc-controls">
        <div className="fm-presets" role="group" aria-label="Rango de fechas">
          {PRESETS.map((p) => (
            <button key={p.key} type="button" className={`btn btn-sm ${preset === p.key ? 'btn-primary' : ''}`}
                    onClick={() => applyPreset(p.key)}>{p.label}</button>
          ))}
        </div>
        <label className="fm-field">
          <span>Desde</span>
          <input type="datetime-local" value={toInput(range[0])}
                 onChange={(e) => { const v = new Date(e.target.value).getTime(); if (!Number.isNaN(v)) { setPreset(null); setRange([v, Math.max(v, range[1])]); } }} />
        </label>
        <label className="fm-field">
          <span>Hasta</span>
          <input type="datetime-local" value={toInput(range[1])}
                 onChange={(e) => { const v = new Date(e.target.value).getTime(); if (!Number.isNaN(v)) { setPreset(null); setRange([Math.min(range[0], v), v]); } }} />
        </label>
        {total > 0 && (
          <div className="report-export">
            <button className="btn btn-sm" onClick={() => void exportAs('xlsx')}>Excel</button>
            <button className="btn btn-sm" onClick={() => void exportAs('csv')}>CSV</button>
          </div>
        )}
      </div>

      {zones.length > 0 && (
        <div className="loc-zones" aria-label="Zonas del dispositivo">
          {zones.map((z) => (
            <span key={z.id} className={`chip ${z.state ? (z.state.violating ? 'tone-alert' : 'tone-ok') : ''}`}
                  title={`${RULE_LABEL[z.rule] ?? ''}${z.state ? ` · desde ${fmt(z.state.since)}` : ''}`}>
              <i style={{ background: z.color }} />
              {z.name}: {z.state ? (z.state.state === 'inside' ? 'dentro' : 'fuera') : 'sin evaluar'}
              {z.state?.violating ? ' (en alerta)' : ''}
            </span>
          ))}
        </div>
      )}

      {latest && (
        <p className="muted loc-latest">
          Última posición: {fmt(latest.capturedAt)}
          {latest.accuracy != null ? ` · precisión ±${Math.round(Number(latest.accuracy))} m` : ' · precisión no informada'}
          {latest.provider ? ` · ${latest.provider}` : ''}
          {latest.lowAccuracy ? ' · baja precisión' : ''}
        </p>
      )}
      {lowCount > 0 && (
        <label className="fm-check loc-low">
          <input type="checkbox" checked={hideLow} onChange={(e) => setHideLow(e.target.checked)} />
          <span>Ocultar {lowCount} {lowCount === 1 ? 'posición' : 'posiciones'} de baja precisión (peor que ±{maxAccuracy} m)</span>
        </label>
      )}

      {err && <p className="err-text">{err}</p>}
      {!fixes && !err && <p className="muted">Cargando ubicación…</p>}
      {fixes && fixes.length === 0 && (
        <p className="muted">
          Sin posiciones en este rango. Amplía las fechas, despierta el dispositivo o cámbialo a Ubicación: precisa.
        </p>
      )}
      {fixes && (shown.length > 0 || zones.length > 0) && (
        <LocationMap fixes={shown} highlightAt={hasSlider ? at ?? undefined : undefined} zones={zones} />
      )}
      {fixes && fixes.length < total && (
        <p style={{ textAlign: 'center', margin: '10px 0 0' }}>
          <button className="btn btn-sm" disabled={loading} onClick={() => void load(range[0], range[1], page + 1, fixes)}>
            {loading ? 'Cargando…' : `Cargar anteriores (${total - fixes.length} más)`}
          </button>
        </p>
      )}
      {hasSlider && (
        <div className="loc-timebar" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 4px 2px' }}>
          <span className="muted" style={{ whiteSpace: 'nowrap' }}>{fmt(tMin!)}</span>
          <input
            type="range"
            min={tMin}
            max={tMax}
            step={60_000}
            value={at ?? tMax}
            onChange={(e) => setAt(Number(e.target.value))}
            aria-label="Posición por hora"
            style={{ flex: 1 }}
          />
          <span className="muted" style={{ whiteSpace: 'nowrap' }}>{fmt(tMax!)}</span>
          <button className="btn btn-sm" onClick={() => setAt(tMax ?? null)} title="Ir al más reciente">Ahora</button>
        </div>
      )}
      {hasSlider && at != null && (
        <p className="muted" style={{ textAlign: 'center', margin: '4px 0 0' }}>
          Mostrando la posición a las <strong>{fmt(at)}</strong>
        </p>
      )}
    </div>
  );
}
