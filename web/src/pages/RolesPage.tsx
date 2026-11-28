import { Fragment, useEffect, useMemo, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { BUILT_IN_ROLE_IDS, deleteRole, listPermissions, listRoles, saveRole, type Permission, type Role } from '../api/roles';
import { errorCode } from '../api/client';
import { CAPABILITIES, ROLE_TEMPLATES, describeRole } from '../data/roleCapabilities';

// Roles and permissions. An administrator creates roles with any combination of capabilities (from scratch, from a
// template or by duplicating another role), decides in the matrix what each role may do, and reads the result in
// plain language. Each matrix row maps to one permission in the database (userRolePermissions) that the server
// checks on every call — the console menu and routes read the same permissions. The built-in Admin role keeps full
// access and is not listed (the server refuses to edit it). Folder isolation is independent of these toggles — a
// folder user always reaches only their own folders' devices.

export function RolesPage() {
  const toast = useToast();
  const [roles, setRoles] = useState<Role[] | null>(null);
  const [perms, setPerms] = useState<Permission[]>([]);
  // roleId -> set of granted permission names (only the ones this screen manages).
  const [grants, setGrants] = useState<Record<number, Set<string>>>({});
  const [original, setOriginal] = useState<Record<number, Set<string>>>({});
  const [busy, setBusy] = useState(false);
  // New role form.
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDescription, setNewDescription] = useState('');
  /** A template id ("blank", "support"…) or "role:<id>" to start from an existing role. */
  const [newFrom, setNewFrom] = useState('blank');
  // The role whose plain-language summary is open.
  const [explain, setExplain] = useState<number | null>(null);

  const permByName = useMemo(() => new Map(perms.map((p) => [p.name, p])), [perms]);
  // Only capabilities whose permission exists in this install (keeps the matrix honest across versions).
  const rows = useMemo(() => CAPABILITIES.filter((c) => permByName.has(c.perm)), [permByName]);
  const modules = useMemo(() => {
    const seen = new Set<string>();
    const out: string[] = [];
    for (const r of rows) if (!seen.has(r.module)) { seen.add(r.module); out.push(r.module); }
    return out;
  }, [rows]);

  const load = () => {
    Promise.all([listRoles(), listPermissions()])
      .then(([rs, ps]) => {
        setPerms(ps);
        const sorted = [...rs].sort((a, b) => a.id - b.id);
        setRoles(sorted);
        const managed = new Set(CAPABILITIES.map((c) => c.perm));
        const g: Record<number, Set<string>> = {};
        for (const r of sorted) g[r.id] = new Set((r.permissions ?? []).map((p) => p.name).filter((n) => managed.has(n)));
        setGrants(g);
        setOriginal(clone(g));
      })
      .catch((e) => { setRoles([]); toast.push('err', 'No se pudieron cargar los roles', String(e?.message ?? e)); });
  };
  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps

  function has(roleId: number, perm: string) {
    return grants[roleId]?.has(perm) ?? false;
  }

  function toggle(roleId: number, perm: string) {
    setGrants((prev) => {
      const set = new Set(prev[roleId] ?? []);
      if (set.has(perm)) set.delete(perm); else set.add(perm);
      return { ...prev, [roleId]: set };
    });
  }

  const dirty = useMemo(() => {
    if (!roles) return false;
    for (const r of roles) {
      const a = grants[r.id] ?? new Set<string>();
      const b = original[r.id] ?? new Set<string>();
      if (a.size !== b.size) return true;
      for (const x of a) if (!b.has(x)) return true;
    }
    return false;
  }, [roles, grants, original]);

  async function save() {
    if (!roles) return;
    const managedIds = new Set(rows.map((c) => permByName.get(c.perm)!.id));
    setBusy(true);
    try {
      for (const r of roles) {
        const a = grants[r.id] ?? new Set<string>();
        const b = original[r.id] ?? new Set<string>();
        const changed = a.size !== b.size || [...a].some((x) => !b.has(x));
        if (!changed) continue;
        // Keep any permission the role has that this screen does not manage; replace only the managed ones.
        const preserved = (r.permissions ?? []).map((p) => p.id).filter((id) => !managedIds.has(id));
        const picked = [...a].map((name) => permByName.get(name)!.id);
        const ids = Array.from(new Set([...preserved, ...picked]));
        await saveRole({ id: r.id, name: r.name, description: r.description ?? '', permissions: ids.map((id) => ({ id })) });
      }
      toast.push('ok', 'Roles guardados', 'Los permisos por rol se actualizaron.');
      load();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  }

  /** Permission ids a new role starts with: a template's, or every permission of the role it copies. */
  function startingPermissions(from: string): number[] {
    if (from.startsWith('role:')) {
      const src = roles?.find((r) => r.id === Number(from.slice(5)));
      return (src?.permissions ?? []).map((p) => p.id);
    }
    const tpl = ROLE_TEMPLATES.find((t) => t.id === from);
    return (tpl?.perms ?? []).map((n) => permByName.get(n)?.id).filter((id): id is number => id !== undefined);
  }

  async function create() {
    const name = newName.trim();
    if (name.length < 3) return toast.push('err', 'Falta el nombre', 'Escribe un nombre de al menos 3 caracteres para el rol.');
    if (roles?.some((r) => r.name.toLowerCase() === name.toLowerCase())) return toast.push('err', 'Nombre repetido', 'Ya existe un rol con ese nombre.');
    setBusy(true);
    try {
      const tpl = ROLE_TEMPLATES.find((t) => t.id === newFrom);
      await saveRole({
        name,
        description: newDescription.trim() || tpl?.description || '',
        permissions: startingPermissions(newFrom).map((id) => ({ id })),
      });
      toast.push('ok', 'Rol creado', `«${name}» ya se puede asignar en Usuarios. Ajusta sus permisos en la tabla.`);
      setCreating(false); setNewName(''); setNewDescription(''); setNewFrom('blank');
      load();
    } catch (e) {
      toast.push('err', 'No se pudo crear el rol', roleError(e));
    }
    setBusy(false);
  }

  function duplicate(r: Role) {
    let name = `Copia de ${r.name}`;
    for (let i = 2; roles?.some((x) => x.name.toLowerCase() === name.toLowerCase()); i++) name = `Copia de ${r.name} (${i})`;
    setCreating(true);
    setNewName(name.slice(0, 50));
    setNewDescription(r.description ?? '');
    setNewFrom(`role:${r.id}`);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  async function remove(r: Role) {
    if (!window.confirm(`¿Eliminar el rol «${r.name}»? No se puede deshacer.`)) return;
    try {
      await deleteRole(r.id);
      toast.push('ok', 'Rol eliminado', r.name);
      load();
    } catch (e) {
      toast.push('err', 'No se pudo eliminar', roleError(e));
    }
  }

  return (
    <AppShell title="Roles">
      <div className="page-head">
        <h1>Roles y permisos</h1>
        <div className="sp" />
        {!creating && <button className="btn" onClick={() => setCreating(true)} data-testid="role-new">Nuevo rol</button>}
        {dirty && <button className="btn btn-primary" disabled={busy} onClick={() => void save()} data-testid="roles-save">{busy ? 'Guardando…' : 'Guardar cambios'}</button>}
      </div>
      <p className="note" style={{ marginTop: 0 }}>
        Define qué puede hacer cada rol y asígnalo en <b>Usuarios</b>. Cada casilla es un permiso que el servidor comprueba
        en cada operación (no solo oculta botones). El <b>Administrador</b> siempre tiene acceso total y no aparece aquí.
        El aislamiento por carpetas es independiente: un usuario de carpetas solo verá los dispositivos de sus carpetas,
        tenga los permisos que tenga.
      </p>

      {creating && (
        <section className="panel" data-testid="role-editor">
          <div className="panel-head keep"><h2 className="panel-title">Nuevo rol</h2></div>
          <div className="role-new">
            <label className="report-field">
              <span>Nombre</span>
              <input className="input" value={newName} maxLength={50} onChange={(e) => setNewName(e.target.value)} placeholder="Mesa de ayuda" style={{ width: 220 }} data-testid="role-name" />
            </label>
            <label className="report-field">
              <span>Partir de</span>
              <select className="input" value={newFrom} onChange={(e) => setNewFrom(e.target.value)} data-testid="role-from">
                <optgroup label="Plantillas">
                  {ROLE_TEMPLATES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
                </optgroup>
                <optgroup label="Copiar un rol existente">
                  {(roles ?? []).map((r) => <option key={r.id} value={`role:${r.id}`}>{r.name}</option>)}
                </optgroup>
              </select>
            </label>
            <label className="report-field" style={{ flex: '1 1 260px' }}>
              <span>Descripción (opcional)</span>
              <input className="input" value={newDescription} maxLength={200} onChange={(e) => setNewDescription(e.target.value)}
                placeholder={ROLE_TEMPLATES.find((t) => t.id === newFrom)?.description ?? ''} data-testid="role-description" />
            </label>
            <button className="btn btn-primary" disabled={busy} onClick={() => void create()} data-testid="role-create">{busy ? 'Creando…' : 'Crear rol'}</button>
            <button className="btn" onClick={() => setCreating(false)}>Cancelar</button>
          </div>
        </section>
      )}

      {roles && roles.length > 0 && (
        <div className="role-cards" data-testid="role-cards">
          {roles.map((r) => {
            const summary = describeRole(grants[r.id] ?? []);
            const builtIn = BUILT_IN_ROLE_IDS.has(r.id);
            return (
              <div className="role-card" key={r.id} data-testid="role-card" data-role={r.name}>
                <h3>{r.name}{builtIn ? <span className="chip" style={{ marginLeft: 8 }}>del sistema</span> : null}</h3>
                <div className="muted small">{r.description || 'Sin descripción'}</div>
                {explain === r.id && (
                  <div data-testid="role-summary">
                    <div className="sub-h" style={{ marginTop: 6 }}>Qué puede hacer</div>
                    <ul className="role-summary">{summary.can.map((s) => <li key={s}>{s}</li>)}</ul>
                    {summary.cannot.length > 0 && (
                      <>
                        <div className="sub-h">Qué no puede hacer</div>
                        <ul className="role-summary">{summary.cannot.map((s) => <li key={s} className="no">{s}</li>)}</ul>
                      </>
                    )}
                    {summary.warnings.map((w) => <p key={w} className="banner banner-warn small" style={{ marginTop: 8 }}>{w}</p>)}
                  </div>
                )}
                <div className="role-actions">
                  <button className="btn btn-sm" onClick={() => setExplain(explain === r.id ? null : r.id)} aria-expanded={explain === r.id} data-testid="role-explain">
                    {explain === r.id ? 'Ocultar' : 'Qué puede hacer'}
                  </button>
                  <button className="btn btn-sm" onClick={() => duplicate(r)} data-testid="role-duplicate">Duplicar</button>
                  {!builtIn && <button className="btn btn-sm btn-ghost gr-del" onClick={() => void remove(r)} data-testid="role-delete">Eliminar</button>}
                </div>
              </div>
            );
          })}
        </div>
      )}

      <section className="panel">
        {roles === null ? <p className="muted" style={{ padding: 16 }}>Cargando…</p>
          : roles.length === 0 ? <p className="muted" style={{ padding: 16 }}>No hay roles configurables.</p> : (
          <div style={{ overflowX: 'auto' }}>
            <table className="gr-table" data-testid="roles-matrix">
              <thead>
                <tr>
                  <th style={{ minWidth: 260 }}>Módulo / Capacidad</th>
                  {roles.map((r) => <th key={r.id} style={{ textAlign: 'center' }}>{r.name}</th>)}
                </tr>
              </thead>
              <tbody>
                {modules.map((mod) => (
                  <Fragment key={`m-${mod}`}>
                    <tr className="role-module-row">
                      <td colSpan={roles.length + 1} style={{ fontWeight: 600, background: 'var(--surface-2, rgba(127,127,127,.08))' }}>{mod}</td>
                    </tr>
                    {rows.filter((c) => c.module === mod).map((c) => (
                      <tr key={c.perm}>
                        <td data-label="Capacidad" style={{ paddingLeft: 18 }}>{c.label}</td>
                        {roles.map((r) => (
                          <td key={r.id} style={{ textAlign: 'center' }} data-label={r.name}>
                            <input
                              type="checkbox"
                              className="dev-check"
                              aria-label={`${c.label} — ${r.name}`}
                              data-perm={c.perm}
                              data-role={r.name}
                              checked={has(r.id, c.perm)}
                              onChange={() => toggle(r.id, c.perm)}
                            />
                          </td>
                        ))}
                      </tr>
                    ))}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </AppShell>
  );
}

/** The server's message keys for roles, in words. */
function roleError(e: unknown): string {
  const m = e instanceof Error ? e.message : String(e);
  if (errorCode(e) === 'error.duplicate.role') return 'Ya existe un rol con ese nombre.';
  if (errorCode(e) === 'error.permission.denied') return 'Solo un administrador puede cambiar los roles.';
  return m;
}

// Deep-clone a map of sets (structuredClone is fine for Sets in modern browsers, but keep it explicit).
function clone(g: Record<number, Set<string>>): Record<number, Set<string>> {
  const out: Record<number, Set<string>> = {};
  for (const k of Object.keys(g)) out[Number(k)] = new Set(g[Number(k)]);
  return out;
}
