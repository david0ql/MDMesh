import { useEffect, useMemo, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import { deleteUser, listUsers, saveUser, type ConsoleUser, type SaveUser } from '../api/users';

// Users of the console: administrators (everything) and folder administrators, who manage only the devices of the
// folders they are given — those folders and every folder below them, also folders created there later.

const EMPTY: SaveUser = { login: '', name: '', email: '', password: '', role: 'folders', folders: [] };

export function UsersPage() {
  const toast = useToast();
  const [users, setUsers] = useState<ConsoleUser[] | null>(null);
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [edit, setEdit] = useState<SaveUser | null>(null);
  const [repeat, setRepeat] = useState('');
  const [busy, setBusy] = useState(false);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const pathOf = useMemo(() => new Map(tree.map((n) => [n.group.id, n.path])), [tree]);

  const load = () => {
    listUsers().then(setUsers).catch((e) => { setUsers([]); toast.push('err', 'No se pudieron cargar los usuarios', String(e?.message ?? e)); });
    listGroups().then((o) => setGroups(o.groups)).catch(() => undefined);
  };
  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** Folders already covered because an ancestor is chosen (shown checked, not editable). */
  const covered = useMemo(() => {
    const out = new Set<number>();
    if (!edit) return out;
    for (const n of tree) if (edit.folders.includes(n.group.id)) n.subtree.forEach((id) => { if (id !== n.group.id) out.add(id); });
    return out;
  }, [edit, tree]);

  function toggle(id: number) {
    if (!edit) return;
    const node = tree.find((n) => n.group.id === id);
    const on = !edit.folders.includes(id);
    // Choosing a folder makes its sub-folders redundant: keep only the top ones.
    const next = on ? [...edit.folders.filter((f) => !node?.subtree.has(f)), id] : edit.folders.filter((f) => f !== id);
    setEdit({ ...edit, folders: next });
  }

  async function save() {
    if (!edit) return;
    if (!edit.id && !edit.password) return toast.push('err', 'Falta la contraseña', 'Escribe una contraseña para el usuario nuevo.');
    if (edit.password && edit.password.length < 8) return toast.push('err', 'Contraseña muy corta', 'Usa al menos 8 caracteres.');
    if (edit.password && edit.password !== repeat) return toast.push('err', 'Las contraseñas no coinciden', 'Escríbela igual en los dos campos.');
    if (edit.role === 'folders' && edit.folders.length === 0) return toast.push('err', 'Faltan carpetas', 'Elige al menos una carpeta.');
    setBusy(true);
    try {
      await saveUser(edit);
      toast.push('ok', 'Usuario guardado', edit.login);
      setEdit(null); setRepeat('');
      load();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  }

  async function remove(u: ConsoleUser) {
    if (!window.confirm(`¿Eliminar el usuario «${u.login}»? Ya no podrá entrar a la consola.`)) return;
    try { await deleteUser(u.id); toast.push('ok', 'Usuario eliminado', u.login); load(); }
    catch (e) { toast.push('err', 'No se pudo eliminar', e instanceof Error ? e.message : String(e)); }
  }

  return (
    <AppShell title="Usuarios">
      <div className="page-head">
        <h1>Usuarios</h1>
        <div className="sp" />
        {!edit && <button className="btn btn-primary" onClick={() => { setEdit({ ...EMPTY }); setRepeat(''); }} data-testid="user-new">Nuevo usuario</button>}
      </div>
      <p className="note" style={{ marginTop: 0 }}>
        <b>Administrador</b>: ve y maneja todo. <b>Administrador de carpetas</b>: solo ve y maneja los dispositivos de las carpetas que le
        asignes (por ejemplo una empresa o un país), con todas sus subcarpetas, también las que se creen después. No ve políticas,
        aplicaciones, anuncios, ajustes ni usuarios.
      </p>

      {edit && (
        <section className="panel" data-testid="user-editor">
          <div className="panel-head keep"><h2 className="panel-title">{edit.id ? `Editar «${edit.login}»` : 'Nuevo usuario'}</h2></div>
          <div className="user-form">
            <label>Nombre<input className="input" value={edit.name ?? ''} onChange={(e) => setEdit({ ...edit, name: e.target.value })} placeholder="Juliana Pérez" /></label>
            <label>Usuario (para entrar)<input className="input mono" value={edit.login} onChange={(e) => setEdit({ ...edit, login: e.target.value.trim() })} placeholder="juliana" autoComplete="off" /></label>
            <label>Correo (opcional)<input className="input" type="email" value={edit.email ?? ''} onChange={(e) => setEdit({ ...edit, email: e.target.value })} placeholder="juliana@empresa.com" /></label>
            <label>{edit.id ? 'Contraseña nueva (vacía = no cambia)' : 'Contraseña'}<input className="input" type="password" value={edit.password ?? ''} onChange={(e) => setEdit({ ...edit, password: e.target.value })} autoComplete="new-password" /></label>
            <label>Repite la contraseña<input className="input" type="password" value={repeat} onChange={(e) => setRepeat(e.target.value)} autoComplete="new-password" /></label>
            <label>Tipo
              <select className="sel" value={edit.role} onChange={(e) => setEdit({ ...edit, role: e.target.value as 'admin' | 'folders' })} aria-label="Tipo de usuario">
                <option value="folders">Administrador de carpetas</option>
                <option value="admin">Administrador (todo)</option>
              </select>
            </label>
          </div>
          {edit.role === 'folders' && (
            <div className="user-folders">
              <div className="sub-h">Carpetas que maneja <span className="muted small">(con todas sus subcarpetas)</span></div>
              {tree.length === 0 ? <p className="muted small">No hay carpetas.</p> : (
                <ul aria-label="Carpetas del usuario">
                  {tree.map((n) => {
                    const inherited = covered.has(n.group.id);
                    return (
                      <li key={n.group.id} style={{ paddingLeft: n.depth * 20 }}>
                        <label className={inherited ? 'muted' : ''}>
                          <input type="checkbox" className="dev-check" checked={inherited || edit.folders.includes(n.group.id)} disabled={inherited}
                            onChange={() => toggle(n.group.id)} />
                          {' '}{n.group.name}{inherited ? ' (incluida)' : ''}
                        </label>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          )}
          <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()} data-testid="user-save">{busy ? 'Guardando…' : 'Guardar'}</button>
            <button className="btn" onClick={() => { setEdit(null); setRepeat(''); }}>Cancelar</button>
          </div>
        </section>
      )}

      <section className="panel">
        {users === null ? <p className="muted" style={{ padding: 16 }}>Cargando…</p> : (
          <table className="gr-table" data-testid="users-table">
            <thead><tr><th>Nombre</th><th>Usuario</th><th>Correo</th><th>Tipo</th><th>Carpetas</th><th aria-label="Acciones" /></tr></thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td data-label="Nombre">{u.name || '—'}{u.me ? <span className="muted small"> (tú)</span> : null}</td>
                  <td data-label="Usuario" className="mono">{u.login}</td>
                  <td data-label="Correo">{u.email || '—'}</td>
                  <td data-label="Tipo">{u.role === 'admin' ? 'Administrador' : 'Administrador de carpetas'}</td>
                  <td data-label="Carpetas">{u.role === 'admin' ? 'Todas' : u.folders.map((f) => pathOf.get(f) ?? `#${f}`).join(' · ') || '—'}</td>
                  <td>
                    <div className="gr-actions">
                      <button className="btn btn-sm" onClick={() => { setEdit({ id: u.id, login: u.login, name: u.name ?? '', email: u.email ?? '', password: '', role: u.role, folders: u.folders }); setRepeat(''); }}>Editar</button>
                      {!u.me && <button className="btn btn-sm btn-ghost gr-del" onClick={() => void remove(u)}>Eliminar</button>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </AppShell>
  );
}
