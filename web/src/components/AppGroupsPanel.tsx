import { useEffect, useState } from 'react';
import { AppPicker } from './AppPicker';
import { useToast } from '../ui/toast';
import { uploadApkToLibrary } from '../api/uploadToLibrary';
import type { Application } from '../api/applications';
import { createAppGroup, deleteAppGroup, listAppGroups, updateAppGroup, type AppGroup } from '../api/appGroups';

/**
 * Grupos de aplicaciones: named sets of library apps. A policy picks whole groups (and whether each shows in the
 * kiosk), instead of adding apps one by one. Editing a group reaches every policy that uses it.
 */
export function AppGroupsPanel({ apps, onChanged }: { apps: Application[]; onChanged?: () => void }) {
  const toast = useToast();
  const [groups, setGroups] = useState<AppGroup[] | null>(null);
  const [editing, setEditing] = useState<AppGroup | 'new' | null>(null);
  const load = () => listAppGroups().then(setGroups).catch(() => setGroups([]));
  useEffect(() => { void load(); }, []);

  const remove = async (g: AppGroup) => {
    if (!window.confirm(`¿Eliminar el grupo «${g.name}»?`)) return;
    try {
      await deleteAppGroup(g.id);
      toast.push('ok', 'Grupo eliminado', g.name);
      void load();
    } catch (e) {
      toast.push('err', 'No se pudo eliminar', e instanceof Error ? e.message : '');
    }
  };

  return (
    <>
      <div className="ag-head">
        <p className="note" style={{ margin: 0 }}>
          Agrupa apps (p. ej. «Ecuador distribución») y en cada política elige los grupos: sus apps se instalan y se permiten
          en los teléfonos. En cada política decides si el grupo se muestra en el quiosco o solo se instala.
        </p>
        <button className="btn btn-dark" onClick={() => setEditing('new')} data-testid="ag-new">Nuevo grupo</button>
      </div>
      {groups == null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Cargando…</div></div>
      ) : groups.length === 0 ? (
        <div className="panel"><div className="empty"><span className="label">No hay grupos de aplicaciones</span>Crea uno y úsalo desde tus políticas.</div></div>
      ) : (
        <div className="cfg-grid">
          {groups.map((g) => (
            <div className="cfg-card" key={g.id}>
              <div className="cfg-top"><div className="cfg-nm">{g.name}</div></div>
              {g.description && <div className="cfg-desc">{g.description}</div>}
              <div className="ag-apps">
                {g.apps.length === 0 ? <span className="muted small">Sin apps</span> : g.apps.map((a) => (
                  <span key={a.id} className="chip" title={a.pkg}>{a.name || a.pkg}</span>
                ))}
              </div>
              <div className="cfg-meta">
                {g.apps.length} app{g.apps.length === 1 ? '' : 's'} · usado en {g.policies} política{g.policies === 1 ? '' : 's'}
              </div>
              <div className="cfg-actions">
                <button className="btn btn-sm" onClick={() => setEditing(g)}>Editar</button>
                <button className="btn btn-sm btn-ghost" disabled={g.policies > 0}
                        title={g.policies > 0 ? 'Quítalo de las políticas que lo usan antes de eliminarlo' : undefined}
                        onClick={() => void remove(g)}>Eliminar</button>
              </div>
            </div>
          ))}
        </div>
      )}
      {editing && (
        <GroupEditor
          group={editing === 'new' ? null : editing}
          apps={apps}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void load(); onChanged?.(); }}
        />
      )}
    </>
  );
}

function GroupEditor({ group, apps, onClose, onSaved }: {
  group: AppGroup | null; apps: Application[]; onClose: () => void; onSaved: () => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(group?.name ?? '');
  const [description, setDescription] = useState(group?.description ?? '');
  const [items, setItems] = useState<{ id: number; name?: string; pkg?: string }[]>(group?.apps ?? []);
  const [picker, setPicker] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const add = (list: Application[]) => {
    setItems((cur) => [...cur, ...list.filter((a) => a.id != null && !cur.some((c) => c.id === a.id)).map((a) => ({ id: a.id as number, name: a.name, pkg: a.pkg }))]);
    setPicker(false);
  };

  const upload = async (f: File) => {
    setBusy('Subiendo APK…');
    try {
      const r = await uploadApkToLibrary(f);
      if (r.kind === 'error') toast.push('err', 'No se pudo subir', r.note);
      else { add([r.app]); toast.push('ok', 'APK subido', r.note); }
    } catch (e) {
      toast.push('err', 'No se pudo subir', e instanceof Error ? e.message : '');
    } finally {
      setBusy(null);
    }
  };

  const save = async () => {
    setBusy('Guardando…');
    const body = { name: name.trim(), description: description.trim() || undefined, appIds: items.map((i) => i.id) };
    try {
      if (group) {
        const r = await updateAppGroup(group.id, body);
        toast.push('ok', 'Grupo guardado', r.policies ? `Usado en ${r.policies} política${r.policies === 1 ? '' : 's'}; ${r.queued} instalación${r.queued === 1 ? '' : 'es'} en cola.` : name);
      } else {
        await createAppGroup(body);
        toast.push('ok', 'Grupo creado', 'Ahora elígelo en tus políticas.');
      }
      onSaved();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', e instanceof Error ? e.message : '');
      setBusy(null);
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal ann-modal">
        <h3>{group ? `Editar ${group.name}` : 'Nuevo grupo de aplicaciones'}</h3>
        <label className="field"><span>Nombre</span>
          <input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} placeholder="Ej.: Ecuador distribución" data-testid="ag-name" />
        </label>
        <label className="field"><span>Descripción (opcional)</span>
          <input value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
        </label>
        <div className="cfg-sec-h" style={{ display: 'flex', alignItems: 'center', marginTop: 12 }}>
          <span>Apps del grupo ({items.length})</span>
          <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 8 }}>
            <label className="btn btn-sm" style={{ cursor: busy ? 'wait' : 'pointer' }}>
              Subir APK
              <input type="file" accept=".apk" hidden disabled={!!busy}
                     onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) void upload(f); }} />
            </label>
            <button className="btn btn-sm" onClick={() => setPicker(true)} data-testid="ag-add">Agregar desde la biblioteca</button>
          </span>
        </div>
        {items.length === 0 && <div className="cfg-empty">Agrega apps de la biblioteca o sube un APK.</div>}
        {items.map((a) => (
          <div className="cfg-app" key={a.id}>
            <span className="cfg-app-nm">{a.name ?? a.pkg ?? `#${a.id}`}</span>
            <span className="cfg-app-pkg mono">{a.pkg}</span>
            <button className="btn btn-sm btn-ghost" onClick={() => setItems((cur) => cur.filter((x) => x.id !== a.id))} aria-label="Quitar app">✕</button>
          </div>
        ))}
        <p className="muted small">Se instala siempre la última versión de cada app de la biblioteca; una versión nueva llega sola.</p>
        <div className="modal-actions">
          {busy && <span className="muted small">{busy}</span>}
          <button className="btn" disabled={!!busy} onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" disabled={!!busy || !name.trim()} onClick={() => void save()} data-testid="ag-save">Guardar</button>
        </div>
        {picker && <AppPicker apps={apps} excludeIds={new Set(items.map((i) => i.id))} onAdd={add} onClose={() => setPicker(false)} />}
      </div>
    </div>
  );
}
