import { useEffect, useState } from 'react';
import { AppPicker } from './AppPicker';
import { useToast } from '../ui/toast';
import { uploadApkToLibrary } from '../api/uploadToLibrary';
import type { Application } from '../api/applications';
import { createAppGroup, deleteAppGroup, listAppGroups, updateAppGroup, type AppGroup, type AppGroupRef } from '../api/appGroups';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import { getConfigurations, type Configuration } from '../api/configurations';

/**
 * Grupos de aplicaciones: named sets of library apps. A policy picks whole groups (and whether each shows in the
 * kiosk), instead of adding apps one by one. Editing a group reaches every policy that uses it.
 */
export function AppGroupsPanel({ apps, onChanged, lockedPolicy }: {
  apps: Application[]; onChanged?: () => void;
  /** Policies the console keeps read-only (the built-in defaults). */
  lockedPolicy?: (c: Configuration) => boolean;
}) {
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
                {g.apps.length} app{g.apps.length === 1 ? '' : 's'} · {g.policies} política{g.policies === 1 ? '' : 's'} · {g.folders?.length ?? 0} carpeta{(g.folders?.length ?? 0) === 1 ? '' : 's'}
              </div>
              <div className="cfg-actions">
                <button className="btn btn-sm" onClick={() => setEditing(g)}>Editar</button>
                <button className="btn btn-sm btn-ghost" disabled={g.policies > 0 || (g.folders?.length ?? 0) > 0}
                        title={g.policies > 0 || (g.folders?.length ?? 0) > 0 ? 'Quita sus asignaciones (políticas y carpetas) antes de eliminarlo' : undefined}
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
          lockedPolicy={lockedPolicy}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void load(); onChanged?.(); }}
        />
      )}
    </>
  );
}

function GroupEditor({ group, apps, lockedPolicy, onClose, onSaved }: {
  group: AppGroup | null; apps: Application[]; lockedPolicy?: (c: Configuration) => boolean; onClose: () => void; onSaved: () => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(group?.name ?? '');
  const [description, setDescription] = useState(group?.description ?? '');
  const [items, setItems] = useState<{ id: number; name?: string; pkg?: string }[]>(group?.apps ?? []);
  const [picker, setPicker] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  // Where the group is used: device folders (with their sub-folders) and policies; value = "also in the kiosk".
  const [folders, setFolders] = useState<Map<number, boolean>>(new Map((group?.folders ?? []).map((f) => [f.id, !!f.kiosk])));
  const [policies, setPolicies] = useState<Map<number, boolean>>(new Map((group?.policyRefs ?? []).map((p) => [p.id, !!p.kiosk])));
  const [tree, setTree] = useState<ReturnType<typeof groupTree>>([]);
  const [allPolicies, setAllPolicies] = useState<Configuration[]>([]);
  useEffect(() => {
    listGroups().then((o: { groups: FleetGroup[] }) => setTree(groupTree(o.groups))).catch(() => undefined);
    getConfigurations().then(setAllPolicies).catch(() => undefined);
  }, []);
  const toggle = (m: Map<number, boolean>, put: (x: Map<number, boolean>) => void, id: number, on: boolean, kiosk: boolean) => {
    const n = new Map(m);
    if (on) n.set(id, kiosk); else n.delete(id);
    put(n);
  };
  const refs = (m: Map<number, boolean>): AppGroupRef[] => [...m].map(([id, kiosk]) => ({ id, kiosk }));

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

  const used = (r: { policies: number; folders: number; queued: number }) =>
    r.policies + r.folders === 0 ? 'Aún sin asignar: márcalo en carpetas o políticas.'
      : `Asignado a ${r.folders} carpeta${r.folders === 1 ? '' : 's'} y ${r.policies} política${r.policies === 1 ? '' : 's'}; ${r.queued} instalación${r.queued === 1 ? '' : 'es'} en cola.`;

  const save = async () => {
    setBusy('Guardando…');
    const body = {
      name: name.trim(), description: description.trim() || undefined, appIds: items.map((i) => i.id),
      folders: refs(folders), policies: refs(policies),
    };
    try {
      if (group) {
        const r = await updateAppGroup(group.id, body);
        toast.push('ok', 'Grupo guardado', used(r));
      } else {
        const r = await createAppGroup(body);
        toast.push('ok', 'Grupo creado', used(r));
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

        <div className="cfg-sec-h" style={{ marginTop: 16 }}>Asignar a carpetas ({folders.size})</div>
        <p className="muted small" style={{ margin: '0 0 6px' }}>
          Los equipos de la carpeta y de sus subcarpetas instalan estas apps, tengan la política que tengan. «En quiosco»: además
          aparecen en su quiosco.
        </p>
        <div className="deploy-devlist ag-assign" data-testid="ag-folders">
          {tree.length === 0 ? <div className="empty" style={{ padding: 14 }}>No hay carpetas.</div> : tree.map((n) => (
            <div key={n.group.id} className="deploy-devrow" style={{ paddingLeft: 12 + n.depth * 18 }}>
              <label className="ag-assign-nm">
                <input type="checkbox" checked={folders.has(n.group.id)} data-testid={`ag-folder-${n.group.id}`}
                       onChange={(e) => toggle(folders, setFolders, n.group.id, e.target.checked, folders.get(n.group.id) ?? false)} />
                <span className="dd-nm">{n.group.name}</span>
              </label>
              <span className="dd-seen">{n.totalDevices} disp.</span>
              {folders.has(n.group.id) && (
                <label className="cfg-kiosk-chk">
                  <input type="checkbox" checked={!!folders.get(n.group.id)} onChange={(e) => toggle(folders, setFolders, n.group.id, true, e.target.checked)} />
                  En quiosco
                </label>
              )}
            </div>
          ))}
        </div>

        <div className="cfg-sec-h" style={{ marginTop: 16 }}>Asignar a políticas ({policies.size})</div>
        <p className="muted small" style={{ margin: '0 0 6px' }}>Todos los equipos con esa política instalan estas apps (es lo mismo que marcar el grupo dentro de la política).</p>
        <div className="deploy-devlist ag-assign" data-testid="ag-policies">
          {allPolicies.filter((c) => c.id != null).map((c) => {
            const id = c.id as number;
            const locked = !!lockedPolicy?.(c) && !policies.has(id);
            return (
              <div key={id} className="deploy-devrow">
                <label className="ag-assign-nm" title={locked ? 'Política predeterminada (solo lectura): cópiala para modificarla' : undefined}>
                  <input type="checkbox" disabled={locked} checked={policies.has(id)} data-testid={`ag-policy-${id}`}
                         onChange={(e) => toggle(policies, setPolicies, id, e.target.checked, policies.get(id) ?? false)} />
                  <span className="dd-nm">{c.name}</span>
                </label>
                <span className="dd-seen">{c.kioskMode ? 'Quiosco' : ''}</span>
                {policies.has(id) && !!c.kioskMode && (
                  <label className="cfg-kiosk-chk">
                    <input type="checkbox" checked={!!policies.get(id)} onChange={(e) => toggle(policies, setPolicies, id, true, e.target.checked)} />
                    En quiosco
                  </label>
                )}
              </div>
            );
          })}
        </div>
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
