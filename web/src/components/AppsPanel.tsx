import { useEffect, useMemo, useState } from 'react';
import { getLatestScan, scanApps, uninstallApp, type AppInfo } from '../api/deviceApps';
import { useToast } from '../ui/toast';
import { fmtRelative } from '../ui/format';

/** The agent's own packages: never offered for removal. */
const AGENT_PREFIX = 'com.dallycontrol.agent';

/**
 * Installed apps on the device (from its latest `apps.scan`), with a remote silent uninstall per app.
 * Installing is the app catalog's Deploy flow; this is the other half (upstream issue #7).
 */
export function AppsPanel({ device }: { device: { number: string } }) {
  const toast = useToast();
  const [apps, setApps] = useState<AppInfo[] | null>(null);
  const [scannedAt, setScannedAt] = useState<number | undefined>();
  const [showSystem, setShowSystem] = useState(false);
  const [filter, setFilter] = useState('');
  const [scanning, setScanning] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<AppInfo | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setApps(null);
    getLatestScan(device.number)
      .then((snap) => {
        if (!alive) return;
        setApps(snap?.apps ?? []);
        setScannedAt(snap?.scannedAt);
      })
      .catch((e) => alive && setErr(e instanceof Error ? e.message : 'No se pudieron cargar las apps'));
    return () => { alive = false; };
  }, [device.number]);

  async function rescan() {
    setScanning(true);
    setErr(null);
    try {
      setApps(await scanApps(device.number));
      setScannedAt(Date.now());
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Falló el escaneo');
    } finally {
      setScanning(false);
    }
  }

  async function remove(app: AppInfo) {
    setConfirm(null);
    setRemoving(app.pkg);
    try {
      await uninstallApp(device.number, app.pkg);
      setApps((list) => list?.filter((a) => a.pkg !== app.pkg) ?? null);
      toast.push('ok', 'App desinstalada', `${app.label || app.pkg}`);
    } catch (e) {
      toast.push('err', 'No se pudo desinstalar', e instanceof Error ? e.message : '');
    } finally {
      setRemoving(null);
    }
  }

  const visible = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return (apps ?? [])
      .filter((a) => showSystem || !a.system)
      .filter((a) => !q || a.pkg.toLowerCase().includes(q) || (a.label ?? '').toLowerCase().includes(q))
      .sort((a, b) => (a.label || a.pkg).localeCompare(b.label || b.pkg));
  }, [apps, filter, showSystem]);

  return (
    <div className="panel">
      <div className="panel-head keep">
        <h2 className="panel-title">Apps instaladas</h2>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {scannedAt && <span className="muted">escaneado {fmtRelative(scannedAt)}</span>}
          <button className="btn" disabled={scanning} onClick={() => void rescan()}>
            {scanning ? 'Escaneando…' : 'Escanear ahora'}
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
        <input
          className="input"
          placeholder="Filtrar por nombre o paquete"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          style={{ flex: '1 1 220px' }}
        />
        <label className="muted" style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={showSystem} onChange={(e) => setShowSystem(e.target.checked)} />
          Mostrar apps del sistema
        </label>
      </div>

      {err && <p className="err-text">{err}</p>}
      {!apps && !err && <p className="muted">Cargando apps…</p>}
      {apps && apps.length === 0 && (
        <p className="muted">Aún no hay inventario. Presiona “Escanear ahora” (el dispositivo debe estar en línea).</p>
      )}
      {apps && apps.length > 0 && (
        <table className="table">
          <thead>
            <tr><th>App</th><th>Paquete</th><th>Versión</th><th /></tr>
          </thead>
          <tbody>
            {visible.map((a) => {
              const removable = !a.system && !a.pkg.startsWith(AGENT_PREFIX);
              return (
                <tr key={a.pkg}>
                  <td>{a.label || a.pkg}{a.system && <span className="muted"> · sistema</span>}</td>
                  <td className="mono">{a.pkg}</td>
                  <td className="mono">{a.versionName ?? '—'}{a.versionCode != null ? ` (${a.versionCode})` : ''}</td>
                  <td style={{ textAlign: 'right' }}>
                    {removable && (
                      <button
                        className="btn btn-danger"
                        disabled={removing !== null}
                        onClick={() => setConfirm(a)}
                      >
                        {removing === a.pkg ? 'Desinstalando…' : 'Desinstalar'}
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {confirm && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="modal">
            <h3>¿Desinstalar {confirm.label || confirm.pkg}?</h3>
            <p className="muted">
              La app y sus datos se eliminan del dispositivo en silencio. Si una configuración todavía la incluye
              para instalar, la próxima sincronización de apps la vuelve a instalar.
            </p>
            <div className="modal-actions">
              <button className="btn" onClick={() => setConfirm(null)}>Cancelar</button>
              <button className="btn btn-danger" onClick={() => void remove(confirm)}>Desinstalar</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
