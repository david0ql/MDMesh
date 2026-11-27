// App permissions: the vocabulary shared with the server (AppPermissionRules.java) and the agent
// (PermissionCatalog in Permissions.kt). Keys must match on the three sides.

export type PermissionRule = 'grant' | 'deny' | 'prompt';

/** What the console decides about one app (in a policy, or on one device on top of its policy). */
export interface AppPermissionRules {
  /** Preset for every runtime permission the app declares. */
  all?: PermissionRule;
  /** Per group; wins over the preset. */
  permissions?: Record<string, PermissionRule>;
  /** Special accesses the app should have. */
  special?: string[];
}

export type PermissionRulesByApp = Record<string, AppPermissionRules>;

export const RULE_LABEL: Record<PermissionRule, string> = { grant: 'Conceder', deny: 'Denegar', prompt: 'Preguntar' };

export const DEFAULT_POLICY_LABEL: Record<string, string> = {
  grant: 'Conceder todo lo que pidan las apps',
  prompt: 'Preguntar al usuario',
  deny: 'Denegar todo lo que pidan las apps',
  unknown: 'Desconocida',
};

export interface PermissionGroup { key: string; label: string; help: string; sensor?: boolean }

/** `sensor`: on Android 12+ the phone may forbid the administrator from GRANTING it (it can always be denied). */
export const PERMISSION_GROUPS: PermissionGroup[] = [
  { key: 'locationFine', label: 'Ubicación precisa', help: 'GPS (ACCESS_FINE_LOCATION).', sensor: true },
  { key: 'locationCoarse', label: 'Ubicación aproximada', help: 'Por red (ACCESS_COARSE_LOCATION).', sensor: true },
  { key: 'locationBackground', label: 'Ubicación en segundo plano', help: 'Con la app cerrada (Android 10+). Se concede después de la precisa/aproximada.', sensor: true },
  { key: 'camera', label: 'Cámara', help: 'CAMERA.', sensor: true },
  { key: 'microphone', label: 'Micrófono', help: 'RECORD_AUDIO.', sensor: true },
  { key: 'storage', label: 'Almacenamiento / fotos y videos', help: 'Archivos (Android ≤12) y fotos, videos y audio (Android 13+).' },
  { key: 'contacts', label: 'Contactos', help: 'Leer y escribir contactos, cuentas.' },
  { key: 'phone', label: 'Teléfono', help: 'Estado del teléfono, llamar, registro de llamadas.' },
  { key: 'sms', label: 'SMS', help: 'Enviar, recibir y leer SMS/MMS.' },
  { key: 'notifications', label: 'Notificaciones', help: 'Mostrar notificaciones (POST_NOTIFICATIONS, Android 13+).' },
  { key: 'nearbyDevices', label: 'Bluetooth / dispositivos cercanos', help: 'Buscar y conectar Bluetooth, Wi‑Fi cercano (Android 12+).' },
  { key: 'activity', label: 'Actividad física y sensores corporales', help: 'ACTIVITY_RECOGNITION, BODY_SENSORS.', sensor: true },
  { key: 'calendar', label: 'Calendario', help: 'Leer y escribir el calendario.' },
];

export const GROUP_LABEL: Record<string, string> = {
  ...Object.fromEntries(PERMISSION_GROUPS.map((g) => [g.key, g.label])),
  other: 'Otros permisos',
};

export interface SpecialAccess {
  key: string;
  label: string;
  /** What the MDM does on its own, and what Android leaves to the person holding the phone. */
  help: string;
  /** Android API reference of the limitation. */
  api: string;
}

/**
 * Special accesses are NOT runtime permissions: DevicePolicyManager.setPermissionGrantState does not cover them, and
 * Android gives a Device Owner no public API to turn them on. The agent does what exists and opens the exact screen.
 */
export const SPECIAL_ACCESS: SpecialAccess[] = [
  { key: 'allFiles', label: 'Acceso a todos los archivos',
    help: 'Android 11+. Requiere un toque del usuario: Android solo lo activa desde su pantalla de ajustes. En Android 10 o anterior lo cubre el permiso «Almacenamiento».',
    api: 'MANAGE_EXTERNAL_STORAGE es un app‑op: Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION' },
  { key: 'overlay', label: 'Mostrar sobre otras apps',
    help: 'Requiere un toque del usuario (el agente abre la pantalla exacta).',
    api: 'SYSTEM_ALERT_WINDOW es un app‑op: Settings.ACTION_MANAGE_OVERLAY_PERMISSION' },
  { key: 'writeSettings', label: 'Modificar ajustes del sistema',
    help: 'Requiere un toque del usuario (el agente abre la pantalla exacta).',
    api: 'WRITE_SETTINGS es un app‑op: Settings.ACTION_MANAGE_WRITE_SETTINGS' },
  { key: 'unknownSources', label: 'Instalar apps desconocidas',
    help: 'Android 7 o anterior: el MDM lo activa solo. Android 8+: es un permiso por app que requiere un toque del usuario.',
    api: 'DevicePolicyManager.setSecureSetting(INSTALL_NON_MARKET_APPS) hasta Android 7; desde Android 8, app‑op REQUEST_INSTALL_PACKAGES: Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES' },
  { key: 'battery', label: 'Sin restricciones de batería',
    help: 'El MDM protege la app (Android 11+): no se puede forzar su detención y queda exenta del reposo de apps. La lista «sin optimizar batería» requiere un toque del usuario.',
    api: 'DevicePolicyManager.setUserControlDisabledPackages (API 30); la lista de Doze no tiene API pública para el administrador (setApplicationExemptions es @SystemApi): Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' },
  { key: 'unrestrictedData', label: 'Uso de datos sin restricción',
    help: 'Requiere un toque del usuario. Android no deja leer este estado de otra app: la consola no puede confirmarlo.',
    api: 'Sin API de administrador: Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS' },
  { key: 'notificationAccess', label: 'Acceso a notificaciones',
    help: 'Requiere un toque del usuario (el agente abre la pantalla del servicio de la app).',
    api: 'NotificationManager.setNotificationListenerAccessGranted es @SystemApi: Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS' },
  { key: 'accessibility', label: 'Accesibilidad',
    help: 'El MDM solo puede permitir o impedir que se active (lista de servicios permitidos); activarlo requiere al usuario.',
    api: 'DevicePolicyManager.setPermittedAccessibilityServices solo restringe: Settings.ACTION_ACCESSIBILITY_SETTINGS' },
];

export const SPECIAL_LABEL: Record<string, string> = Object.fromEntries(SPECIAL_ACCESS.map((s) => [s.key, s.label]));

export const SPECIAL_STATE_LABEL: Record<string, string> = {
  allowed: 'Activo',
  needsUser: 'Requiere un toque del usuario',
  notDeclared: 'La app no lo pide',
  unknown: 'Android no deja consultarlo',
};

/** Rules with nothing set are dropped, so "no rule" never gets stored as an empty object. */
export function cleanRules(rules: PermissionRulesByApp): PermissionRulesByApp {
  const out: PermissionRulesByApp = {};
  for (const [pkg, r] of Object.entries(rules)) {
    const perms = Object.fromEntries(Object.entries(r.permissions ?? {}).filter(([, v]) => !!v));
    const c: AppPermissionRules = {};
    if (r.all) c.all = r.all;
    if (Object.keys(perms).length) c.permissions = perms as Record<string, PermissionRule>;
    if (r.special?.length) c.special = r.special;
    if (Object.keys(c).length) out[pkg] = c;
  }
  return out;
}

/** The rule in force for a group: its own, else the preset. */
export function ruleFor(r: AppPermissionRules | undefined, group: string): PermissionRule | undefined {
  return r?.permissions?.[group] ?? r?.all;
}
