import { useEffect, useMemo, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { useAuth } from '../auth/AuthContext';
import { isFolderAdmin, hasPermission } from '../api/auth';
import { AuditActivity } from '../components/AuditActivity';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import { deleteUser, listAssignableRoles, listUsers, saveUser, type AssignableRole, type ConsoleUser, type SaveUser } from '../api/users';
import { describeRole } from '../data/roleCapabilities';

// Users of the console: administrators (everything) and folder administrators, who manage only the devices of the
// folders they are given — those folders and every folder below them, also folders created there later.

const EMPTY: SaveUser = { login: '', name: '', email: '', password: '', role: 'folders', folders: [] };

export function UsersPage() {
  const toast = useToast();
  const { user } = useAuth();
  // A folder editor managing users: they see/create only users inside their own folders, never administrators.
  const folderManager = isFolderAdmin(user);
  const [users, setUsers] = useState<ConsoleUser[] | null>(null);
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [edit, setEdit] = useState<SaveUser | null>(null);
  const [repeat, setRepeat] = useState('');
  const [busy, setBusy] = useState(false);
  // Custom roles (Roles page) this user may hand out, and whether a custom role covers the whole fleet.
  const [roles, setRoles] = useState<AssignableRole[]>([]);
  const [allFleet, setAllFleet] = useState(false);
  // The three built-in kinds already cover the editor (3) and view-only (200) roles.
  const customRoles = useMemo(() => roles.filter((r) => r.id !== 3 && r.id !== 200), [roles]);
  // Recent activity of one user (audit trail); only for those who may read the audit.
  const canSeeAudit = hasPermission(user, 'view_audit');
  const [activityOf, setActivityOf] = useState<ConsoleUser | null>(null);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const pathOf = useMemo(() => new Map(tree.map((n) => [n.group.id, n.path])), [tree]);

  const load = () => {
    listUsers().then(setUsers).catch((e) => { setUsers([]); toast.push('err', 'No se pudieron cargar los usuarios', String(e?.message ?? e)); });
    listGroups().then((o) => setGroups(o.groups)).catch(() => undefined);
    listAssignableRoles().then(setRoles).catch(() => setRoles([]));
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
    const wholeFleet = edit.role === 'custom' && allFleet;
    if (edit.role === 'custom' && !edit.roleId) return toast.push('err', 'Falta el rol', 'Elige el rol del usuario.');
    if (edit.role !== 'admin' && !wholeFleet && edit.folders.length === 0) return toast.push('err', 'Faltan carpetas', 'Elige al menos una carpeta.');
    setBusy(true);
    try {
      await saveUser(wholeFleet ? { ...edit, folders: [] } : edit);
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
        {!edit && <button className="btn btn-primary" onClick={() => { setEdit({ ...EMPTY }); setAllFleet(false); setRepeat(''); }} data-testid="user-new">Nuevo usuario</button>}
      </div>
      {folderManager ? (
        <p className="note" style={{ marginTop: 0 }}>
          Puedes crear y gestionar usuarios <b>dentro de tus carpetas</b>: solo verás y asignarás las carpetas que
          tú administras, y no puedes crear administradores.
        </p>
      ) : (
        <p className="note" style={{ marginTop: 0 }}>
          <b>Administrador</b>: ve y maneja todo. <b>Administrador de carpetas</b>: solo ve y maneja los dispositivos de las carpetas que le
          asignes (por ejemplo una empresa o un país), con todas sus subcarpetas, también las que se creen después. No ve políticas,
          aplicaciones, anuncios, ajustes ni usuarios.
        </p>
      )}

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
              <select className="sel" value={edit.role} onChange={(e) => setEdit({ ...edit, role: e.target.value as SaveUser['role'] })} aria-label="Tipo de usuario" data-testid="user-type">
                <option value="foldersView">Carpetas — solo lectura (ver y acciones seguras)</option>
                <option value="folders">Carpetas — con edición (modifica políticas y carpetas)</option>
                {customRoles.length > 0 && <option value="custom">Rol personalizado…</option>}
                {!folderManager && <option value="admin">Administrador (todo)</option>}
              </select>
            </label>
            {edit.role === 'custom' && (
              <label>Rol
                <select className="sel" value={edit.roleId ?? ''} onChange={(e) => setEdit({ ...edit, roleId: e.target.value ? Number(e.target.value) : null })} aria-label="Rol del usuario" data-testid="user-role">
                  <option value="">Elige un rol…</option>
                  {customRoles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </label>
            )}
          </div>
          {edit.role === 'custom' && (
            <div className="user-folders" data-testid="user-role-info">
              {!folderManager && (
                <label>
                  <input type="checkbox" className="dev-check" checked={allFleet} onChange={(e) => setAllFleet(e.target.checked)} data-testid="user-all-fleet" />
                  {' '}Toda la flota <span className="muted small">(sin marcar: solo las carpetas que elijas abajo)</span>
                </label>
              )}
              {edit.roleId ? (
                <>
                  <div className="sub-h">Qué podrá hacer con este rol</div>
                  <ul className="role-summary">
                    {describeRole(customRoles.find((r) => r.id === edit.roleId)?.permissions ?? []).can.map((s) => <li key={s}>{s}</li>)}
                  </ul>
                </>
              ) : null}
            </div>
          )}
          {edit.role !== 'admin' && !(edit.role === 'custom' && allFleet) && (
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
                  <td data-label="Tipo">{u.role === 'admin' ? 'Administrador' : u.role === 'foldersView' ? 'Carpetas (solo lectura)' : u.role === 'custom' ? `Rol: ${u.roleName ?? '—'}` : 'Carpetas (edición)'}</td>
                  <td data-label="Carpetas">{u.role === 'admin' || u.allFleet ? 'Todas' : u.folders.map((f) => pathOf.get(f) ?? `#${f}`).join(' · ') || '—'}</td>
                  <td>
                    <div className="gr-actions">
                      <button className="btn btn-sm" onClick={() => { setEdit({ id: u.id, login: u.login, name: u.name ?? '', email: u.email ?? '', password: '', role: u.role, roleId: u.roleId ?? null, folders: u.folders }); setAllFleet(u.role === 'custom' && !!u.allFleet); setRepeat(''); }}>Editar</button>
                      {canSeeAudit && <button className="btn btn-sm btn-ghost" onClick={() => setActivityOf(u)} data-testid="user-activity">Actividad</button>}
                      {!u.me && <button className="btn btn-sm btn-ghost gr-del" onClick={() => void remove(u)}>Eliminar</button>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {activityOf && (
        <div className="modal-backdrop" onClick={() => setActivityOf(null)}>
          <div className="modal audit-modal" role="dialog" aria-label={`Actividad de ${activityOf.login}`} onClick={(e) => e.stopPropagation()} data-testid="user-activity-modal">
            <h3>Actividad reciente de «{activityOf.name || activityOf.login}»</h3>
            <AuditActivity userId={activityOf.id} />
            <div className="modal-actions"><button className="btn" onClick={() => setActivityOf(null)}>Cerrar</button></div>
          </div>
        </div>
      )}
    </AppShell>
  );
}
