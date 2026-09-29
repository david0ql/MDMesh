import { useEffect, useState } from 'react';
import { listLocations, type LocationFix } from '../api/deviceLocations';
import { LocationMap } from './LocationMap';
import { fmtRelative } from '../ui/format';

export function LocationPanel({ device }: { device: { number: string } }) {
  const [fixes, setFixes] = useState<LocationFix[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

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
      {fixes && fixes.length > 0 && <LocationMap fixes={fixes} />}
    </div>
  );
}
