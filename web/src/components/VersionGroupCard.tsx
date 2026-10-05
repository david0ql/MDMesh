import { useRef, useState } from 'react';
import type { Application } from '../api/applications';
import { assignVersion, deleteVersion, setVersionLabel, type LabeledVersion, type VersionGroup } from '../api/versions';
import { uploadApkToLibrary } from '../api/uploadToLibrary';
import { useToast } from '../ui/toast';

export interface PolicyRef { id: number; name: string }

/** "SKULL 9.2.44 · código 24" */
const buildLine = (v: LabeledVersion) => `${v.version ?? '—'} · código ${v.versionCode ?? '?'}`;
/** What people call a build: its name, else its version. */
export const buildName = (v: LabeledVersion) => v.label?.trim() || v.version || `versión ${v.id}`;

/**
 * A group of builds of one app (same package, e.g. Distribución for El Sol and for Disay). Each policy uses exactly
 * one build of the group, assigned here; uploading a new build adds it to the group without moving any policy.
 */
export function VersionGroupCard({ app, group, policies, onChanged, onDeploy }: {
  app: Application; group: VersionGroup; policies: PolicyRef[]; onChanged: () => void; onDeploy: (a: Application) => void;
}) {
  const toast = useToast();
  const [assigning, setAssigning] = useState<LabeledVersion | null>(null);
  const [renaming, setRenaming] = useState<{ id: number; value: string } | null>(null);
  const [adding, setAdding] = useState(false);
  const members = group.members;

  async function rename(v: LabeledVersion, value: string) {
    try {
      await setVersionLabel(v.id, value);
      setRenaming(null);
      onChanged();
    } catch (e) {
      toast.push('err', 'No se pudo cambiar el nombre', e instanceof Error ? e.message : String(e));
    }
  }

  async function remove(v: LabeledVersion) {
    if (!window.confirm(`¿Quitar «${buildName(v)}» (${buildLine(v)}) del grupo? Se borra su APK del servidor.`)) return;
    try {
      await deleteVersion(v.id);
      toast.push('ok', 'Versión quitada', buildName(v));
      onChanged();
    } catch (e) {
      toast.push('err', 'No se pudo quitar', e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="app-card vg-card" data-testid={`group-${app.pkg}`}>
      {assigning && (
        <AssignDialog app={app} build={assigning} members={members} policies={policies}
          onClose={() => setAssigning(null)} onDone={() => { setAssigning(null); onChanged(); }} />
      )}
      {adding && <AddBuildDialog app={app} onClose={() => setAdding(false)} onDone={() => { setAdding(false); onChanged(); }} />}
      <div className="vg-head">
        <div className="app-top">
          <div className="app-ic" aria-hidden>{(app.name || app.pkg || '?').trim().charAt(0).toUpperCase()}</div>
          <div className="app-meta">
            <div className="app-nm">{app.name}</div>
            <div className="app-pkg mono">{app.pkg} · grupo de {members.length} {members.length === 1 ? 'versión' : 'versiones'}</div>
          </div>
        </div>
        <div className="vg-actions">
          <button className="btn btn-sm" onClick={() => setAdding(true)} data-testid={`group-add-${app.pkg}`}>Agregar versión</button>
          <button className="btn btn-sm btn-primary" onClick={() => onDeploy(app)}>Desplegar</button>
        </div>
      </div>
      <ul className="vg-list">
        {members.map((v) => (
          <li key={v.id} className="vg-row" data-testid={`build-${v.id}`}>
            <div className="vg-name">
              {renaming?.id === v.id ? (
                <form style={{ display: 'flex', gap: 6 }} onSubmit={(e) => { e.preventDefault(); void rename(v, renaming.value); }}>
                  <input className="input" autoFocus maxLength={80} value={renaming.value} aria-label="Nombre de la versión"
                    onChange={(e) => setRenaming({ id: v.id, value: e.target.value })} />
                  <button className="btn btn-sm btn-primary" type="submit">Guardar</button>
                  <button className="btn btn-sm btn-ghost" type="button" onClick={() => setRenaming(null)}>Cancelar</button>
                </form>
              ) : (
                <>
                  <span className="vg-label">{v.label?.trim() || <span className="muted">Sin nombre</span>}</span>
                  <span className="app-ver">{buildLine(v)}</span>
                </>
              )}
            </div>
            <div className="vg-used">
              {v.policies.length
                ? v.policies.map((p) => <span key={p.id} className="chip tone-ok">{p.name}</span>)
                : <span className="muted small">Ninguna política la usa</span>}
            </div>
            <div className="vg-row-actions">
              <button className="btn btn-sm" onClick={() => setAssigning(v)} data-testid={`assign-${v.id}`}>Asignar a políticas</button>
              <button className="btn btn-sm btn-ghost" onClick={() => setRenaming({ id: v.id, value: v.label ?? '' })}>Renombrar</button>
              {v.policies.length === 0 && members.length > 1 && (
                <button className="btn btn-sm btn-ghost" onClick={() => void remove(v)}>Quitar</button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Pick the policies that use this build. A policy shows which build of the group it has today. */
function AssignDialog({ app, build, members, policies, onClose, onDone }: {
  app: Application; build: LabeledVersion; members: LabeledVersion[]; policies: PolicyRef[]; onClose: () => void; onDone: () => void;
}) {
  const toast = useToast();
  const usingNow = (policyId: number) => members.find((m) => m.policies.some((p) => p.id === policyId));
  const [picked, setPicked] = useState<Set<number>>(() => new Set(build.policies.map((p) => p.id)));
  const [busy, setBusy] = useState(false);
  const top = members.reduce((m, v) => Math.max(m, v.versionCode ?? 0), 0);
  const older = (build.versionCode ?? 0) < top;
  const leaving = build.policies.filter((p) => !picked.has(p.id));

  const toggle = (id: number) => setPicked((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  async function save() {
    setBusy(true);
    try {
      const r = await assignVersion(build.id, [...picked]);
      toast.push('ok', `«${buildName(build)}» asignada`,
        `${r.assigned} ${r.assigned === 1 ? 'política la usa' : 'políticas la usan'}${r.removed ? `; ${r.removed} sin ${app.name}` : ''}. `
        + `${r.queued} ${r.queued === 1 ? 'instalación enviada' : 'instalaciones enviadas'} a los teléfonos.`);
      onDone();
    } catch (e) {
      toast.push('err', 'No se pudo asignar', e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 620, width: '95vw' }} onClick={(e) => e.stopPropagation()} data-testid="assign-dialog">
        <h3>¿Qué políticas usan «{buildName(build)}»?</h3>
        <p className="muted" style={{ marginTop: 0 }}>{app.name} · {buildLine(build)}. Cada política usa una sola versión del grupo: al marcarla aquí deja la que tenía.</p>
        <ul className="vg-policies">
          {policies.map((p) => {
            const now = usingNow(p.id);
            return (
              <li key={p.id}>
                <label>
                  <input type="checkbox" checked={picked.has(p.id)} onChange={() => toggle(p.id)} />
                  <span>{p.name}</span>
                  <span className="muted small">
                    {now ? (now.id === build.id ? 'usa esta' : `hoy usa «${buildName(now)}»`) : `hoy no tiene ${app.name}`}
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
        {older && picked.size > 0 && (
          <p className="note">Hay una versión más nueva en el grupo. Los teléfonos de estas políticas que tengan una más nueva
            desinstalan {app.name} e instalan esta: <b>se borran los datos de la app en ese teléfono</b> (por ejemplo pedidos sin enviar).</p>
        )}
        {leaving.length > 0 && (
          <p className="note">{leaving.map((p) => p.name).join(', ')} {leaving.length === 1 ? 'deja' : 'dejan'} de tener {app.name} en la
            política (no se desinstala de los teléfonos). Para cambiarlas a otra versión, asígnalas desde esa versión.</p>
        )}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
          <button className="btn" onClick={onClose} disabled={busy}>Cancelar</button>
          <button className="btn btn-primary" onClick={() => void save()} disabled={busy} data-testid="assign-save">
            {busy ? 'Guardando…' : 'Guardar'}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Upload one more build into the group, with its name. It moves no policy. */
function AddBuildDialog({ app, onClose, onDone }: { app: Application; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  async function save() {
    if (!file || !name.trim()) return;
    setBusy(true);
    try {
      const r = await uploadApkToLibrary(file, name.trim(), app.pkg);
      if (r.kind === 'error') { toast.push('err', 'No se pudo agregar', r.note); return; }
      toast.push('ok', `«${name.trim()}» agregada al grupo`, 'Ninguna política cambió: asígnala a las que deban usarla.');
      onDone();
    } catch (e) {
      toast.push('err', 'No se pudo agregar', e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 520, width: '95vw' }} onClick={(e) => e.stopPropagation()} data-testid="add-build-dialog">
        <h3>Agregar versión a {app.name}</h3>
        <label className="field">
          <span className="label">Nombre</span>
          <input className="input" maxLength={80} value={name} placeholder="Por ejemplo: El Sol, Disay, prueba nueva"
            onChange={(e) => setName(e.target.value)} data-testid="add-build-name" />
        </label>
        <label className="field">
          <span className="label">APK</span>
          <input ref={input} type="file" accept=".apk" onChange={(e) => setFile(e.target.files?.[0] ?? null)} data-testid="add-build-file" />
        </label>
        <p className="muted small">Puede ser más vieja o más nueva que las otras. Queda en el grupo sin cambiar ninguna política.</p>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
          <button className="btn" onClick={onClose} disabled={busy}>Cancelar</button>
          <button className="btn btn-primary" onClick={() => void save()} disabled={busy || !file || !name.trim()} data-testid="add-build-save">
            {busy ? 'Subiendo…' : 'Agregar'}
          </button>
        </div>
      </div>
    </div>
  );
}
