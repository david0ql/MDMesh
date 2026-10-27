import { useEffect, useMemo, useState } from 'react';
import { listLocations, type LocationFix } from '../api/deviceLocations';
import { LocationMap } from './LocationMap';
import { fmtRelative } from '../ui/format';

const fmt = (ms: number) => new Date(ms).toLocaleString('es-CO', { dateStyle: 'short', timeStyle: 'short' });

export function LocationPanel({ device }: { device: { number: string } }) {
  const [fixes, setFixes] = useState<LocationFix[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [at, setAt] = useState<number | null>(null);

  async function load() {
    setErr(null);
    try {
      setFixes(await listLocations(device.number));
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'No se pudieron cargar las ubicaciones');
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [device.number]);

  // Oldest → newest, for the time slider's range and scrubbing.
  const chrono = useMemo(() => (fixes ? [...fixes].reverse() : []), [fixes]);
  const tMin = chrono[0]?.capturedAt;
  const tMax = chrono[chrono.length - 1]?.capturedAt;
  const hasSlider = chrono.length > 1 && tMin != null && tMax != null && tMax > tMin;

  // Default the scrubber to the latest fix whenever the data (re)loads.
  useEffect(() => { setAt(tMax ?? null); }, [tMax]);

  return (
    <div className="panel">
      <div className="panel-head keep">
        <h2 className="panel-title">Historial de ubicación</h2>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {fixes && (
            <span className="muted">
              {fixes.length} {fixes.length === 1 ? 'punto' : 'puntos'}
              {fixes[0] ? ` · último ${fmtRelative(fixes[0].capturedAt)}` : ''}
            </span>
          )}
          <button className="btn" onClick={() => void load()}>Actualizar</button>
        </div>
      </div>
      {err && <p className="err-text">{err}</p>}
      {!fixes && !err && <p className="muted">Cargando ubicación…</p>}
      {fixes && fixes.length === 0 && (
        <p className="muted">Aún no ha reportado ubicación. Despierta el dispositivo o cámbialo a Ubicación: precisa.</p>
      )}
      {fixes && fixes.length > 0 && <LocationMap fixes={fixes} highlightAt={hasSlider ? at ?? undefined : undefined} />}
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
