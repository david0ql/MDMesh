import { useCallback, useEffect, useState } from 'react';
import { listCommandHistory, queueCommand, type CommandHistoryItem } from '../api/commands';

type Device = { number: string };

interface AppUse { packageName: string; label: string; appBytes: number; dataBytes: number; cacheBytes: number; system: boolean }
interface FileUse { id: number; name: string; path?: string | null; bytes: number; mime?: string | null; modifiedAt: number }
interface Scan { totalBytes: number; freeBytes: number; usageAccess: boolean; filesAccess: boolean; apps: AppUse[]; files: FileUse[]; note?: string | null }

const SCAN = 'device.storageScan';
const CLEAN = 'device.storageClean';
const ACCESS = 'device.storageAccess';

const size = (b: number) =>
  b >= 1024 ** 3 ? `${(b / 1024 ** 3).toLocaleString('es-CO', { maximumFractionDigits: 1 })} GB`
    : `${Math.max(1, Math.round(b / 1024 ** 2)).toLocaleString('es-CO')} MB`;

const parse = (c?: CommandHistoryItem | null): Scan | null => {
  if (!c?.detail) return null;
  try { return JSON.parse(c.detail) as Scan; } catch { return null; }
};

/** Wait for a queued command's result (the device answers on its next check-in, normally seconds). */
async function waitFor(number: string, id: number | string | undefined, ms = 180000): Promise<CommandHistoryItem | null> {
  if (id == null) return null;
  const until = Date.now() + ms;
  while (Date.now() < until) {
    await new Promise((r) => setTimeout(r, 3000));
    const h = await listCommandHistory(number).catch(() => [] as CommandHistoryItem[]);
    const c = h.find((x) => String(x.id) === String(id));
    if (c && !['pending', 'delivered', 'accepted'].includes(c.status)) return c;
  }
  return null;
}

/**
 * Almacenamiento: what fills the phone (apps with their data, the largest files) and freeing it: wiping an app's data
 * or deleting files. The phone does the work; results come back through the command history.
 */
export function StoragePanel({ device }: { device: Device }) {
  const [scan, setScan] = useState<Scan | null>(null);
  const [scannedAt, setScannedAt] = useState<number | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [apps, setApps] = useState<Set<string>>(new Set());
  const [files, setFiles] = useState<Set<number>>(new Set());
  const [confirm, setConfirm] = useState(false);

  // The last scan the device answered, so the tab opens with data.
  useEffect(() => {
    let on = true;
    void listCommandHistory(device.number).then((h) => {
      const last = h.find((c) => c.type === SCAN && c.status === 'done');
      if (on && last) { setScan(parse(last)); setScannedAt(last.completedAt ?? null); }
    }).catch(() => undefined);
    return () => { on = false; };
  }, [device.number]);

  const runScan = useCallback(async () => {
    setBusy('Analizando el almacenamiento del equipo…');
    setMsg(null);
    try {
      const q = await queueCommand(device.number, { type: SCAN, requiresCapability: SCAN });
      const c = await waitFor(device.number, q.id);
      if (!c) setMsg('El equipo no respondió todavía. Si está apagado o su agente es antiguo (actualízalo), inténtalo más tarde.');
      else if (c.status !== 'done') setMsg(`No se pudo analizar: ${c.detail ?? c.status}`);
      else { setScan(parse(c)); setScannedAt(c.completedAt ?? Date.now()); setApps(new Set()); setFiles(new Set()); }
    } catch (e) {
      setMsg(`No se pudo enviar: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setBusy(null);
    }
  }, [device.number]);

  const clean = async () => {
    setConfirm(false);
    setBusy('Liberando espacio…');
    setMsg(null);
    try {
      const q = await queueCommand(device.number, {
        type: CLEAN, requiresCapability: CLEAN,
        payload: JSON.stringify({ clearData: [...apps], deleteFiles: [...files] }),
      });
      const c = await waitFor(device.number, q.id);
      setMsg(!c ? 'El equipo no respondió todavía.' : `${c.status === 'done' ? 'Listo' : 'No se pudo'}: ${c.detail ?? ''}`);
      if (c) await runScan();
    } catch (e) {
      setMsg(`No se pudo enviar: ${e instanceof Error ? e.message : String(e)}`);
      setBusy(null);
    }
  };

  const askAccess = async (kind: 'usage' | 'files') => {
    setMsg(null);
    try {
      await queueCommand(device.number, { type: ACCESS, requiresCapability: ACCESS, payload: JSON.stringify({ kind }) });
      setMsg('Se abrió el ajuste en el teléfono: quien lo tenga debe activar DallyControl. Luego pulsa «Analizar».');
    } catch (e) {
      setMsg(`No se pudo enviar: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  const toggle = <T,>(set: Set<T>, v: T, put: (s: Set<T>) => void) => {
    const n = new Set(set);
    if (n.has(v)) n.delete(v); else n.add(v);
    put(n);
  };

  const used = scan ? scan.totalBytes - scan.freeBytes : 0;
  const pct = scan && scan.totalBytes > 0 ? Math.round((used / scan.totalBytes) * 100) : 0;
  const selectedBytes = (scan?.apps.filter((a) => apps.has(a.packageName)).reduce((s, a) => s + a.dataBytes + a.cacheBytes, 0) ?? 0)
    + (scan?.files.filter((f) => files.has(f.id)).reduce((s, f) => s + f.bytes, 0) ?? 0);

  return (
    <div className="panel">
      <div className="hc-head">
        <h2 className="panel-title">Almacenamiento</h2>
        <button className="btn" type="button" disabled={!!busy} onClick={() => void runScan()}>Analizar</button>
      </div>
      {busy && <p className="muted">{busy}</p>}
      {msg && <p className="small">{msg}</p>}
      {!scan ? (
        !busy && <p className="muted">Pulsa «Analizar» para ver qué ocupa el espacio del equipo.</p>
      ) : (
        <>
          <div className="st-bar" role="img" aria-label={`${pct} % ocupado`}>
            <span style={{ width: `${pct}%` }} className={pct >= 90 ? 'full' : ''} />
          </div>
          <p className="small">
            <b>{size(scan.freeBytes)} libres</b> de {size(scan.totalBytes)} ({pct} % ocupado)
            {scannedAt && <span className="muted"> · analizado {new Date(scannedAt).toLocaleString('es-CO')}</span>}
          </p>
          {(!scan.usageAccess || !scan.filesAccess) && (
            <div className="banner">
              {scan.note}{' '}
              {!scan.usageAccess && <button className="btn btn-ghost" type="button" onClick={() => void askAccess('usage')}>Pedir acceso de uso</button>}
              {!scan.filesAccess && <button className="btn btn-ghost" type="button" onClick={() => void askAccess('files')}>Pedir acceso a archivos</button>}
              <span className="muted small"> (con la inscripción por cable/Wi‑Fi ADB ya quedan activos)</span>
            </div>
          )}

          <h3 className="st-h">Apps que más ocupan</h3>
          {scan.apps.length === 0 ? (
            <p className="muted small">{scan.usageAccess ? 'Sin datos.' : 'Requiere el acceso de uso.'}</p>
          ) : (
            <table className="st-table">
              <thead><tr><th /><th>App</th><th>App</th><th>Datos</th><th>Caché</th></tr></thead>
              <tbody>
                {scan.apps.map((a) => (
                  <tr key={a.packageName}>
                    <td><input type="checkbox" aria-label={`Borrar datos de ${a.label}`} checked={apps.has(a.packageName)} onChange={() => toggle(apps, a.packageName, setApps)} /></td>
                    <td><b>{a.label}</b><div className="muted small mono">{a.packageName}{a.system ? ' · sistema' : ''}</div></td>
                    <td>{size(a.appBytes)}</td><td>{size(a.dataBytes)}</td><td>{size(a.cacheBytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h3 className="st-h">Archivos más pesados</h3>
          {scan.files.length === 0 ? (
            <p className="muted small">No hay archivos de más de 1 MB visibles.</p>
          ) : (
            <table className="st-table">
              <thead><tr><th /><th>Archivo</th><th>Tamaño</th><th>Fecha</th></tr></thead>
              <tbody>
                {scan.files.map((f) => (
                  <tr key={f.id}>
                    <td><input type="checkbox" aria-label={`Borrar ${f.name}`} checked={files.has(f.id)} onChange={() => toggle(files, f.id, setFiles)} /></td>
                    <td><b>{f.name}</b><div className="muted small mono">{f.path ?? ''}</div></td>
                    <td>{size(f.bytes)}</td>
                    <td>{f.modifiedAt ? new Date(f.modifiedAt).toLocaleDateString('es-CO') : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="st-actions">
            <button className="btn btn-danger" type="button" disabled={!!busy || (apps.size === 0 && files.size === 0)} onClick={() => setConfirm(true)}>
              Liberar {selectedBytes > 0 ? `≈ ${size(selectedBytes)}` : 'espacio'}
            </button>
          </div>
        </>
      )}

      {confirm && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setConfirm(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>¿Liberar espacio en este equipo?</h3>
            <p>
              {apps.size > 0 && <>Se borrarán <b>todos los datos</b> de {apps.size} app{apps.size === 1 ? '' : 's'} (quedan como recién instaladas; se pierden sesiones y archivos guardados dentro de ellas). </>}
              {files.size > 0 && <>Se borrarán {files.size} archivo{files.size === 1 ? '' : 's'} del teléfono. </>}
              No se puede deshacer.
            </p>
            <div className="modal-actions">
              <button className="btn" type="button" onClick={() => setConfirm(false)}>Cancelar</button>
              <button className="btn btn-danger" type="button" onClick={() => void clean()}>Borrar</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
