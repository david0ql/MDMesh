// "Hardware y puertos": the rules a policy can block, with the text the console shows for each one.
// The keys and Android levels mirror the server (common HardwarePolicy.java) and the agent (HardwareCatalog.kt).

export type HardwareValue = 'allow' | 'block';

export interface HardwareRuleInfo {
  key: string;
  group: string;
  label: string;
  /** What blocking does, in the operator's words. */
  help: string;
  /** First Android API level that honours the rule. */
  minSdk: number;
  /** rule = stored in the policy's hardware map; toggle / device = an older field of the policy. */
  kind: 'rule' | 'toggle' | 'device';
  /** Shown when the rule is set to "Bloquear": what the operator must know before saving. */
  warn?: string;
}

export const HARDWARE_GROUPS = ['Cámara y pantalla', 'USB y almacenamiento', 'Redes y radios', 'Sistema'] as const;

export const HARDWARE_RULES: HardwareRuleInfo[] = [
  { key: 'camera', group: 'Cámara y pantalla', kind: 'rule', minSdk: 21, label: 'Cámara',
    help: 'Ninguna app puede usar las cámaras del equipo (Android muestra «el administrador desactivó la cámara»).' },
  { key: 'screenshots', group: 'Cámara y pantalla', kind: 'toggle', minSdk: 21, label: 'Capturas de pantalla',
    help: 'Sin capturas ni grabación de pantalla; el asistente tampoco puede leer la pantalla.',
    warn: 'El control remoto de pantalla del MDM también deja de ver la imagen mientras esté bloqueado.' },
  { key: 'contentCapture', group: 'Cámara y pantalla', kind: 'rule', minSdk: 29, label: 'Captura de contenido y sugerencias',
    help: 'El sistema no captura el contenido de las apps para sugerencias inteligentes.' },
  { key: 'assistContent', group: 'Cámara y pantalla', kind: 'rule', minSdk: 35, label: 'Asistente (leer la pantalla)',
    help: 'El asistente no recibe el contenido de la pantalla.' },
  { key: 'microphone', group: 'Cámara y pantalla', kind: 'rule', minSdk: 21, label: 'Micrófono',
    help: 'El micrófono queda silenciado y el usuario no puede activarlo.',
    warn: 'Las llamadas se establecen pero la otra persona no escucha: no lo bloquees en equipos que usan el teléfono.' },

  { key: 'usbStorage', group: 'USB y almacenamiento', kind: 'toggle', minSdk: 21, label: 'Transferencia de archivos por USB',
    help: 'El equipo no se abre como unidad (MTP/PTP) al conectarlo a un computador, ni monta memorias externas.' },
  { key: 'usbDebug', group: 'USB y almacenamiento', kind: 'rule', minSdk: 21, label: 'Depuración USB (ADB)',
    help: 'Apaga la depuración USB e inalámbrica y cierra las opciones de desarrollador.',
    warn: 'Soporte ya no podrá usar adb en este equipo. Se revierte desde esta consola (el equipo necesita conexión); el enrolamiento por USB se hace antes de que llegue la política.' },
  { key: 'usbData', group: 'USB y almacenamiento', kind: 'rule', minSdk: 31, label: 'Datos por USB',
    help: 'Corta toda la señal de datos del puerto USB (archivos, ADB, accesorios, módem USB). La carga sigue funcionando.',
    warn: 'Solo algunos equipos lo permiten (depende del fabricante): si el equipo no puede, se reporta «no soportado por el equipo». Sin datos por USB el equipo solo se recupera desde esta consola o con un restablecimiento de fábrica.' },
  { key: 'physicalMedia', group: 'USB y almacenamiento', kind: 'rule', minSdk: 21, label: 'Tarjeta SD y memorias USB',
    help: 'No se montan tarjetas SD ni memorias conectadas por OTG.' },

  { key: 'tethering', group: 'Redes y radios', kind: 'device', minSdk: 21, label: 'Compartir datos (zona Wi‑Fi, USB y Bluetooth)',
    help: 'Nadie puede compartir la conexión del equipo: ni zona Wi‑Fi, ni módem USB, ni Bluetooth.' },
  { key: 'bluetooth', group: 'Redes y radios', kind: 'toggle', minSdk: 26, label: 'Bluetooth',
    help: 'Bluetooth queda apagado y no se puede encender.' },
  { key: 'bluetoothSharing', group: 'Redes y radios', kind: 'rule', minSdk: 26, label: 'Compartir archivos por Bluetooth',
    help: 'Bluetooth funciona (audífonos, impresoras), pero no se pueden enviar archivos.' },
  { key: 'bluetoothConfig', group: 'Redes y radios', kind: 'rule', minSdk: 21, label: 'Configurar Bluetooth',
    help: 'El usuario no puede emparejar ni cambiar los ajustes de Bluetooth.' },
  { key: 'nfc', group: 'Redes y radios', kind: 'rule', minSdk: 35, label: 'NFC',
    help: 'Apaga la radio NFC (pagos y lectura de etiquetas).' },
  { key: 'nfcBeam', group: 'Redes y radios', kind: 'rule', minSdk: 22, label: 'Enviar datos por NFC (Beam)',
    help: 'No se pueden enviar datos a otro equipo acercándolos.' },
  { key: 'wifiDirect', group: 'Redes y radios', kind: 'rule', minSdk: 33, label: 'Wi‑Fi Direct',
    help: 'No se pueden crear conexiones Wi‑Fi directas entre equipos.' },
  { key: 'airplaneMode', group: 'Redes y radios', kind: 'rule', minSdk: 28, label: 'Modo avión',
    help: 'El usuario no puede activar el modo avión (el equipo no se desconecta del MDM).' },
  { key: 'networkReset', group: 'Redes y radios', kind: 'rule', minSdk: 23, label: 'Restablecer ajustes de red',
    help: 'El usuario no puede borrar las redes Wi‑Fi, los datos móviles ni Bluetooth desde Ajustes.' },

  { key: 'locationConfig', group: 'Sistema', kind: 'rule', minSdk: 28, label: 'Cambiar la ubicación (GPS)',
    help: 'El usuario no puede encender ni apagar la ubicación: queda como esté.' },
  { key: 'locationSharing', group: 'Sistema', kind: 'rule', minSdk: 21, label: 'Compartir ubicación',
    help: 'Apaga la ubicación para todas las apps.',
    warn: 'El MDM tampoco recibe la posición del equipo: se pierden el mapa, el recorrido y las geocercas.' },
  { key: 'dateTime', group: 'Sistema', kind: 'rule', minSdk: 21, label: 'Cambiar fecha y hora',
    help: 'El usuario no puede cambiar la fecha, la hora ni la zona horaria (antes de Android 9: se fuerza la hora de la red).' },
  { key: 'unknownSources', group: 'Sistema', kind: 'rule', minSdk: 21, label: 'Instalar de orígenes desconocidos',
    help: 'No se pueden instalar APK fuera de la tienda. Las instalaciones del MDM siguen funcionando.' },
  { key: 'printing', group: 'Sistema', kind: 'rule', minSdk: 28, label: 'Imprimir',
    help: 'Ninguna app puede imprimir.' },
];

export const HARDWARE_BY_KEY: Record<string, HardwareRuleInfo> = Object.fromEntries(HARDWARE_RULES.map((r) => [r.key, r]));

/** Extra Android restrictions an administrator may add by name (mirrors HardwarePolicy.FREE on the server). */
export const FREE_RESTRICTIONS: { value: string; constant: string; minSdk: number; label: string }[] = [
  { value: 'no_sms', constant: 'DISALLOW_SMS', minSdk: 21, label: 'Enviar y recibir SMS' },
  { value: 'no_outgoing_calls', constant: 'DISALLOW_OUTGOING_CALLS', minSdk: 21, label: 'Llamadas salientes' },
  { value: 'no_config_wifi', constant: 'DISALLOW_CONFIG_WIFI', minSdk: 21, label: 'Cambiar redes Wi‑Fi' },
  { value: 'no_config_mobile_networks', constant: 'DISALLOW_CONFIG_MOBILE_NETWORKS', minSdk: 21, label: 'Cambiar redes móviles' },
  { value: 'no_config_vpn', constant: 'DISALLOW_CONFIG_VPN', minSdk: 21, label: 'Configurar VPN' },
  { value: 'no_config_cell_broadcasts', constant: 'DISALLOW_CONFIG_CELL_BROADCASTS', minSdk: 21, label: 'Configurar alertas de emergencia' },
  { value: 'no_config_credentials', constant: 'DISALLOW_CONFIG_CREDENTIALS', minSdk: 21, label: 'Configurar certificados' },
  { value: 'no_modify_accounts', constant: 'DISALLOW_MODIFY_ACCOUNTS', minSdk: 21, label: 'Agregar o quitar cuentas' },
  { value: 'no_add_user', constant: 'DISALLOW_ADD_USER', minSdk: 21, label: 'Agregar usuarios' },
  { value: 'no_remove_user', constant: 'DISALLOW_REMOVE_USER', minSdk: 21, label: 'Quitar usuarios' },
  { value: 'no_uninstall_apps', constant: 'DISALLOW_UNINSTALL_APPS', minSdk: 21, label: 'Desinstalar apps' },
  { value: 'no_control_apps', constant: 'DISALLOW_APPS_CONTROL', minSdk: 21, label: 'Forzar detención y borrar datos de apps' },
  { value: 'no_adjust_volume', constant: 'DISALLOW_ADJUST_VOLUME', minSdk: 21, label: 'Cambiar el volumen' },
  { value: 'no_create_windows', constant: 'DISALLOW_CREATE_WINDOWS', minSdk: 21, label: 'Ventanas flotantes y avisos del sistema' },
  { value: 'no_cross_profile_copy_paste', constant: 'DISALLOW_CROSS_PROFILE_COPY_PASTE', minSdk: 21, label: 'Copiar y pegar entre perfiles' },
  { value: 'no_safe_boot', constant: 'DISALLOW_SAFE_BOOT', minSdk: 23, label: 'Arrancar en modo seguro' },
  { value: 'no_fun', constant: 'DISALLOW_FUN', minSdk: 23, label: 'Juegos ocultos del sistema' },
  { value: 'no_data_roaming', constant: 'DISALLOW_DATA_ROAMING', minSdk: 24, label: 'Datos en roaming' },
  { value: 'no_set_wallpaper', constant: 'DISALLOW_SET_WALLPAPER', minSdk: 24, label: 'Cambiar el fondo de pantalla' },
  { value: 'no_set_user_icon', constant: 'DISALLOW_SET_USER_ICON', minSdk: 24, label: 'Cambiar la foto del usuario' },
  { value: 'no_autofill', constant: 'DISALLOW_AUTOFILL', minSdk: 26, label: 'Autocompletar' },
  { value: 'no_user_switch', constant: 'DISALLOW_USER_SWITCH', minSdk: 28, label: 'Cambiar de usuario' },
  { value: 'no_system_error_dialogs', constant: 'DISALLOW_SYSTEM_ERROR_DIALOGS', minSdk: 28, label: 'Diálogos de error de apps' },
  { value: 'no_config_locale', constant: 'DISALLOW_CONFIG_LOCALE', minSdk: 28, label: 'Cambiar el idioma' },
  { value: 'no_config_brightness', constant: 'DISALLOW_CONFIG_BRIGHTNESS', minSdk: 28, label: 'Cambiar el brillo' },
  { value: 'no_config_screen_timeout', constant: 'DISALLOW_CONFIG_SCREEN_TIMEOUT', minSdk: 28, label: 'Cambiar el tiempo de pantalla' },
  { value: 'no_ambient_display', constant: 'DISALLOW_AMBIENT_DISPLAY', minSdk: 28, label: 'Pantalla ambiente' },
  { value: 'no_config_private_dns', constant: 'DISALLOW_CONFIG_PRIVATE_DNS', minSdk: 29, label: 'Cambiar el DNS privado' },
  { value: 'no_add_wifi_config', constant: 'DISALLOW_ADD_WIFI_CONFIG', minSdk: 33, label: 'Agregar redes Wi‑Fi' },
  { value: 'no_change_wifi_state', constant: 'DISALLOW_CHANGE_WIFI_STATE', minSdk: 33, label: 'Encender o apagar Wi‑Fi' },
  { value: 'no_wifi_tethering', constant: 'DISALLOW_WIFI_TETHERING', minSdk: 33, label: 'Zona Wi‑Fi (solo Wi‑Fi)' },
  { value: 'no_sharing_admin_configured_wifi', constant: 'DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI', minSdk: 33, label: 'Compartir las redes Wi‑Fi de la empresa' },
  { value: 'no_cellular_2g', constant: 'DISALLOW_CELLULAR_2G', minSdk: 34, label: 'Redes 2G' },
  { value: 'no_ultra_wideband_radio', constant: 'DISALLOW_ULTRA_WIDEBAND_RADIO', minSdk: 34, label: 'Banda ultraancha (UWB)' },
  { value: 'no_config_default_apps', constant: 'DISALLOW_CONFIG_DEFAULT_APPS', minSdk: 34, label: 'Cambiar las apps predeterminadas' },
];

/** Restrictions that have their own option above: refused in the free field. */
const OWN_OPTION = new Set([
  'no_debugging_features', 'no_physical_media', 'no_bluetooth_sharing', 'no_config_bluetooth', 'no_outgoing_beam',
  'no_near_field_communication_radio', 'no_unmute_microphone', 'no_airplane_mode', 'no_wifi_direct', 'no_printing',
  'no_network_reset', 'no_config_date_time', 'no_install_unknown_sources', 'no_install_unknown_sources_globally',
  'no_config_location', 'no_share_location', 'no_content_capture', 'no_content_suggestions', 'no_assist_content',
  'no_config_tethering', 'no_factory_reset', 'no_install_apps', 'no_bluetooth', 'no_usb_file_transfer',
]);

/** Same parsing as the server: what will be sent, what is not on the list, and what has its own option. */
export function parseRestrictions(field: unknown): { accepted: string[]; unknown: string[]; ownOption: string[] } {
  const accepted = new Set<string>();
  const unknown: string[] = [];
  const ownOption: string[] = [];
  for (const raw of (typeof field === 'string' ? field : '').split(/[,;\s]+/)) {
    const t = raw.trim();
    if (!t) continue;
    const constant = t.toUpperCase().replace(/^USERMANAGER\./, '');
    const value = FREE_RESTRICTIONS.find((r) => r.constant === constant)?.value ?? t.toLowerCase();
    if (FREE_RESTRICTIONS.some((r) => r.value === value)) accepted.add(value);
    else if (OWN_OPTION.has(value)) { if (!ownOption.includes(value)) ownOption.push(value); }
    else if (!unknown.includes(t)) unknown.push(t);
  }
  return { accepted: Array.from(accepted).sort(), unknown, ownOption };
}

const ANDROID_BY_SDK: Record<number, string> = {
  21: '5', 22: '5.1', 23: '6', 24: '7', 25: '7.1', 26: '8', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L',
  33: '13', 34: '14', 35: '15', 36: '16',
};

export const androidName = (sdk: number): string => `Android ${ANDROID_BY_SDK[sdk] ?? `API ${sdk}`}`;

/** "Desde Android N" only when it is newer than every phone the agent supports (Android 6). */
export const sinceLabel = (sdk: number): string | null => (sdk > 23 ? `Desde ${androidName(sdk)}` : null);

export type OutcomeTone = 'ok' | 'warn' | 'alert' | 'idle';

/** What the device reported, in the operator's words. */
export function describeOutcome(outcome: string | null | undefined, desired?: string | null): { tone: OutcomeTone; label: string; detail?: string } {
  if (!outcome) return { tone: 'idle', label: 'Sin gestionar' };
  if (outcome === 'pending') return { tone: 'warn', label: 'Pendiente', detail: 'El equipo aún no ha aplicado la política actual.' };
  if (outcome === 'applied') return { tone: 'ok', label: desired === 'allow' ? 'Permitido en el equipo' : 'Bloqueado en el equipo' };
  if (outcome.startsWith('unsupported')) {
    const why = outcome.replace(/^unsupported:?\s*/, '');
    const sdk = /^sdk-(\d+)$/.exec(why);
    if (sdk) return { tone: 'warn', label: 'No soportado', detail: `Este equipo tiene un Android anterior: requiere ${androidName(Number(sdk[1]))} o superior.` };
    if (why === 'device') return { tone: 'warn', label: 'No soportado por el equipo', detail: 'El fabricante no permite cortar los datos del puerto USB en este modelo.' };
    if (why === 'agent') return { tone: 'warn', label: 'Agente desactualizado', detail: 'El agente instalado no conoce esta opción: actualízalo para que la aplique.' };
    if (why === 'held') return { tone: 'warn', label: 'Sigue bloqueado', detail: 'Otra opción de la política mantiene este bloqueo.' };
    if (why === 'not-device-owner') return { tone: 'warn', label: 'No soportado', detail: 'El agente no es propietario del dispositivo (Device Owner).' };
    return { tone: 'warn', label: 'No soportado', detail: why && why !== 'unknown' ? why : 'El agente no reconoce esta opción.' };
  }
  const why = outcome.replace(/^failed:?\s*/, '');
  return { tone: 'alert', label: 'No se aplicó', detail: why === 'not-effective' ? 'Android aceptó la orden pero la restricción no quedó activa.' : why };
}
