import { Fragment, useEffect, useMemo, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { listPermissions, listRoles, saveRole, type Permission, type Role } from '../api/roles';

// Per-role module permissions. An administrator decides, for each role, which modules it sees and whether it can
// modify them. Each row maps to one permission in the database (userRolePermissions); the console menu and routes
// read the same permissions, so toggling here changes what that role sees. The built-in Admin role keeps full
// access and is not listed (editing it could lock administrators out). Folder isolation is independent of these
// toggles — a folder user always reaches only their own folders' devices.

interface Capability {
  /** Permission name in the database (see db.changelog.xml). */
  perm: string;
  /** What this grant lets the role do, in plain words. */
  label: string;
  /** The module this capability belongs to, for grouping. */
  module: string;
}

// Capabilities grouped by module. Only the ones whose permission actually exists in this install are shown.
const CAPABILITIES: Capability[] = [
  { module: 'Políticas', perm: 'configurations', label: 'Ver políticas, aplicaciones y archivos' },
  { module: 'Políticas', perm: 'add_config', label: 'Crear políticas nuevas' },
  { module: 'Políticas', perm: 'copy_config', label: 'Duplicar / eliminar políticas' },
  { module: 'Aplicaciones', perm: 'applications', label: 'Ver aplicaciones' },
  { module: 'Aplicaciones', perm: 'edit_applications', label: 'Agregar y editar aplicaciones' },
  { module: 'Aplicaciones', perm: 'edit_application_versions', label: 'Agregar y editar versiones' },
  { module: 'Aplicaciones', perm: 'get_updates', label: 'Actualizar aplicaciones automáticamente' },
  { module: 'Archivos', perm: 'files', label: 'Ver archivos' },
  { module: 'Archivos', perm: 'edit_files', label: 'Agregar y editar archivos' },
  { module: 'Dispositivos', perm: 'edit_devices', label: 'Editar y agregar dispositivos' },
  { module: 'Dispositivos', perm: 'edit_device_desc', label: 'Editar la descripción del dispositivo' },
  { module: 'Dispositivos', perm: 'edit_device_app_settings', label: 'Editar ajustes de app por dispositivo' },
  { module: 'Dispositivos', perm: 'disruptive_actions', label: 'Acciones que interrumpen (bloquear, reiniciar, borrar)' },
  { module: 'Flota', perm: 'manage_fleet', label: 'Modificar carpetas, asignar políticas y ver reportes' },
  { module: 'Inscripción', perm: 'enroll_devices', label: 'Inscribir dispositivos por código QR' },
  { module: 'Scripts', perm: 'manage_scripts', label: 'Ver y ejecutar scripts' },
  { module: 'Usuarios', perm: 'manage_users', label: 'Gestionar usuarios (editor: solo en sus carpetas)' },
  { module: 'Ajustes', perm: 'settings', label: 'Acceder a los ajustes del sistema' },
  { module: 'API', perm: 'push_api', label: 'Enviar mensajes Push por la API' },
];

export function RolesPage() {
  const toast = useToast();
  const [roles, setRoles] = useState<Role[] | null>(null);
  const [perms, setPerms] = useState<Permission[]>([]);
  // roleId -> set of granted permission names (only the ones this screen manages).
  const [grants, setGrants] = useState<Record<number, Set<string>>>({});
  const [original, setOriginal] = useState<Record<number, Set<string>>>({});
  const [busy, setBusy] = useState(false);

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
        setRoles(rs);
        const managed = new Set(CAPABILITIES.map((c) => c.perm));
        const g: Record<number, Set<string>> = {};
        for (const r of rs) g[r.id] = new Set((r.permissions ?? []).map((p) => p.name).filter((n) => managed.has(n)));
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

  return (
    <AppShell title="Roles">
      <div className="page-head">
        <h1>Roles y permisos</h1>
        <div className="sp" />
        {dirty && <button className="btn btn-primary" disabled={busy} onClick={() => void save()} data-testid="roles-save">{busy ? 'Guardando…' : 'Guardar cambios'}</button>}
      </div>
      <p className="note" style={{ marginTop: 0 }}>
        Define qué módulos ve y modifica cada rol. El <b>Administrador</b> siempre tiene acceso total y no aparece aquí.
        El aislamiento por carpetas es independiente: un usuario de carpetas solo verá los dispositivos de sus carpetas,
        tenga los permisos que tenga.
      </p>

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

// Deep-clone a map of sets (structuredClone is fine for Sets in modern browsers, but keep it explicit).
function clone(g: Record<number, Set<string>>): Record<number, Set<string>> {
  const out: Record<number, Set<string>> = {};
  for (const k of Object.keys(g)) out[Number(k)] = new Set(g[Number(k)]);
  return out;
}
