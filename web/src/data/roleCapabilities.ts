// What each role permission really lets a user do. One list feeds the permission matrix (Roles), the plain-language
// summary of a role ("qué puede hacer este rol") and the role templates. Every permission here is enforced by the
// server (scripts/roles-matrix-e2e.py proves it against a live stack: without the permission the API refuses, with
// it the call goes through) — a permission that only hid a button is not listed.

export interface Capability {
  /** Permission name in the database (see db.changelog.xml). */
  perm: string;
  /** The module this capability belongs to, for grouping in the matrix. */
  module: string;
  /** Row label in the matrix. */
  label: string;
  /** Plain sentence for a role that holds it. */
  can: string;
  /** Plain sentence for a role that lacks it (omitted = not worth saying). */
  cannot?: string;
  /** This permission only has an effect together with another one. */
  needs?: string;
}

export const CAPABILITIES: Capability[] = [
  { module: 'Dispositivos', perm: 'edit_devices', label: 'Operar dispositivos (acciones seguras, soporte y control remoto, datos móviles, hardware, permisos de apps, editar y eliminar)',
    can: 'Opera los dispositivos: sincronizar, hacer sonar, enviar mensajes, soporte y control remoto, datos móviles, excepciones de hardware y puertos, permisos de aplicaciones, ejecutar scripts, cancelar comandos, editar y eliminar equipos.',
    cannot: 'No envía comandos ni modifica dispositivos: solo los ve.' },
  { module: 'Dispositivos', perm: 'disruptive_actions', label: 'Acciones que interrumpen (bloquear, reiniciar, cambiar clave, borrar)',
    can: 'Puede bloquear, reiniciar, cambiar la clave de desbloqueo, activar el modo perdido y borrar (wipe) dispositivos.',
    cannot: 'No puede bloquear, reiniciar, cambiar la clave ni borrar dispositivos.', needs: 'edit_devices' },
  { module: 'Dispositivos', perm: 'edit_device_desc', label: 'Editar la descripción del dispositivo',
    can: 'Edita la descripción (nombre visible) de los dispositivos.' },
  { module: 'Dispositivos', perm: 'edit_device_app_settings', label: 'Editar ajustes de app por dispositivo',
    can: 'Cambia los ajustes de aplicación de un dispositivo.' },
  { module: 'Flota', perm: 'manage_fleet', label: 'Carpetas, asignar políticas, instalar apps, geocercas, protección (FRP), reportes y alertas',
    can: 'Organiza la flota: crea y mueve carpetas, asigna políticas, instala y desinstala apps, administra geocercas y las cuentas de reactivación (FRP), y ve reportes y alertas.',
    cannot: 'No modifica carpetas ni políticas asignadas, no instala apps y no ve reportes ni alertas.' },
  { module: 'Inscripción', perm: 'enroll_devices', label: 'Inscribir dispositivos (códigos y tokens)',
    can: 'Inscribe dispositivos nuevos: crea y revoca códigos de inscripción.',
    cannot: 'No puede inscribir dispositivos nuevos.' },
  { module: 'Políticas', perm: 'configurations', label: 'Ver y editar políticas (incluye certificados Wi-Fi)',
    can: 'Ve y edita las políticas a su alcance, sus grupos de aplicaciones y los certificados de Wi-Fi corporativo.',
    cannot: 'No ve ni edita políticas.' },
  { module: 'Políticas', perm: 'add_config', label: 'Crear políticas nuevas', can: 'Crea políticas nuevas.', needs: 'configurations' },
  { module: 'Políticas', perm: 'copy_config', label: 'Duplicar y eliminar políticas', can: 'Duplica y elimina políticas.', needs: 'configurations' },
  { module: 'Aplicaciones', perm: 'applications', label: 'Ver aplicaciones', can: 'Ve el catálogo de aplicaciones.', cannot: 'No ve el catálogo de aplicaciones.' },
  { module: 'Aplicaciones', perm: 'edit_applications', label: 'Agregar, editar y eliminar aplicaciones',
    can: 'Agrega, edita y elimina aplicaciones del catálogo.' },
  { module: 'Aplicaciones', perm: 'edit_application_versions', label: 'Agregar, editar y eliminar versiones',
    can: 'Agrega y elimina versiones de las aplicaciones.' },
  { module: 'Archivos', perm: 'files', label: 'Ver archivos', can: 'Ve los archivos y APK cargados.' },
  { module: 'Archivos', perm: 'edit_files', label: 'Subir, editar y eliminar archivos y APK', can: 'Sube, edita y elimina archivos y APK.' },
  { module: 'Scripts', perm: 'manage_scripts', label: 'Crear, editar y eliminar scripts',
    can: 'Crea, edita y elimina scripts (ejecutarlos requiere «Operar dispositivos»).' },
  { module: 'Usuarios', perm: 'manage_users', label: 'Gestionar usuarios (sin dar más permisos que los propios)',
    can: 'Crea, edita y elimina usuarios de su alcance, sin poder darles más permisos que los suyos.',
    cannot: 'No gestiona usuarios.' },
  { module: 'Auditoría', perm: 'view_audit', label: 'Ver la auditoría de acciones',
    can: 'Consulta y exporta la auditoría (un usuario de carpetas, solo la de sus carpetas y sus usuarios).',
    cannot: 'No ve la auditoría.' },
  { module: 'Ajustes', perm: 'settings', label: 'Cambiar los ajustes del sistema', can: 'Cambia los ajustes del sistema.' },
  { module: 'API', perm: 'push_api', label: 'Enviar mensajes Push por la API', can: 'Envía mensajes Push por la API.' },
];

const BY_PERM = new Map(CAPABILITIES.map((c) => [c.perm, c]));

export interface RoleSummary {
  can: string[];
  cannot: string[];
  /** Permissions granted that do nothing without another one. */
  warnings: string[];
}

/** "Qué puede hacer este rol", in plain language, from its permission names. */
export function describeRole(perms: Iterable<string>): RoleSummary {
  const held = new Set(perms);
  const can: string[] = ['Ve los dispositivos de su alcance (toda la flota o sus carpetas): estado, ubicación, eventos y aplicaciones instaladas.'];
  const cannot: string[] = [];
  const warnings: string[] = [];
  for (const c of CAPABILITIES) {
    if (held.has(c.perm)) {
      if (c.needs && !held.has(c.needs)) {
        warnings.push(`«${c.label}» no tiene efecto sin «${BY_PERM.get(c.needs)?.label ?? c.needs}».`);
      } else {
        can.push(c.can);
      }
    } else if (c.cannot) {
      cannot.push(c.cannot);
    }
  }
  return { can, cannot, warnings };
}

/** Starting points for a new role. `null` permissions = copy an existing role. */
export const ROLE_TEMPLATES: { id: string; label: string; description: string; perms: string[] }[] = [
  { id: 'blank', label: 'En blanco', description: 'Solo ve los dispositivos de su alcance', perms: [] },
  { id: 'readonly', label: 'Solo lectura', description: 'Solo ve: dispositivos, aplicaciones y archivos. No modifica nada ni envía comandos',
    perms: ['applications', 'files'] },
  { id: 'support', label: 'Técnico de soporte',
    description: 'Opera dispositivos con acciones seguras y control remoto, sin acciones que interrumpen',
    perms: ['edit_devices', 'edit_device_desc', 'applications', 'files'] },
  { id: 'operator', label: 'Operador de flota',
    description: 'Opera dispositivos (también bloquear y reiniciar), inscribe equipos y organiza carpetas; no toca políticas ni usuarios',
    perms: ['edit_devices', 'edit_device_desc', 'disruptive_actions', 'manage_fleet', 'enroll_devices', 'applications', 'files'] },
];
