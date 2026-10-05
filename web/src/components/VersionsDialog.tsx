import { useEffect, useState } from 'react';
import { listAppVersions, setVersionLabel, type LabeledVersion } from '../api/versions';
import { useToast } from '../ui/toast';

/** An app's versions in the Library: give each a name, and see which policies use it. */
export function VersionsDialog({ app, onClose }: { app: { id: number; name?: string; pkg?: string }; onClose: () => void }) {
  const toast = useToast();
  const [rows, setRows] = useState<LabeledVersion[] | null>(null);
  const [draft, setDraft] = useState<Record<number, string>>({});
  const load = () => listAppVersions(app.id).then((r) => { setRows(r); setDraft(Object.fromEntries(r.map((v) => [v.id, v.label ?? '']))); })
    .catch((e) => { setRows([]); toast.push('err', 'No se pudieron cargar las versiones', String(e?.message ?? e)); });
  useEffect(() => { void load(); }, [app.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function save(v: LabeledVersion) {
    try {
      await setVersionLabel(v.id, draft[v.id] ?? '');
      toast.push('ok', 'Nombre guardado', `${v.version ?? ''} (versionCode ${v.versionCode ?? '?'})`);
      void load();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal" style={{ maxWidth: 820, width: '95vw' }} data-testid="versions-dialog">
        <h3>Versiones de {app.name ?? app.pkg}</h3>
        <p className="muted">Ponle un nombre a cada versión (por ejemplo «Disay sept.» o «prueba Juliana») para saber cuál tiene cada política, aunque dos compilaciones tengan el mismo número.</p>
        {rows === null ? <p className="muted">Cargando…</p> : (
          <table className="gr-table">
            <thead><tr><th>Versión</th><th>Código</th><th>Nombre</th><th>La usan</th></tr></thead>
            <tbody>
              {rows.map((v) => (
                <tr key={v.id}>
                  <td data-label="Versión" className="mono">{v.version ?? '—'}</td>
                  <td data-label="Código" className="mono">{v.versionCode ?? '—'}</td>
                  <td data-label="Nombre">
                    <div style={{ display: 'flex', gap: 6 }}>
                      <input className="input" value={draft[v.id] ?? ''} maxLength={80} placeholder="Sin nombre"
                        onChange={(e) => setDraft((d) => ({ ...d, [v.id]: e.target.value }))}
                        onKeyDown={(e) => { if (e.key === 'Enter') void save(v); }} aria-label={`Nombre de la versión ${v.version}`} />
                      <button className="btn btn-sm" disabled={(draft[v.id] ?? '') === (v.label ?? '')} onClick={() => void save(v)}>Guardar</button>
                    </div>
                  </td>
                  <td data-label="La usan">{v.policies.length ? v.policies.map((p) => p.name).join(', ') : <span className="muted">ninguna política</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn" onClick={onClose}>Cerrar</button>
        </div>
      </div>
    </div>
  );
}
