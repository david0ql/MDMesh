// Field schema for the configuration editor. Drives the "Focused" core fields and
// the grouped "Advanced" section, each with a concise explainer. Field keys are the
// exact Configuration JSON properties (see com.hmdm.persistence.domain.Configuration).
//
// type:
//   text/textarea/password/color/time → string input
//   int    → number
//   enum   → <select> from options
//   switch → plain boolean (Java primitive; false default)
//   tri    → nullable Boolean: null=unmanaged · true=on · false=off
//   app    → application id selector (mainAppId / contentAppId)

export type FieldType =
  | 'text' | 'textarea' | 'password' | 'int' | 'enum'
  | 'switch' | 'tri' | 'time' | 'color' | 'app';

export type FieldGroup =
  | 'Identidad' | 'Apps' | 'Quiosco' | 'Red'
  | 'Seguridad' | 'Restricciones' | 'Pantalla' | 'Actualizaciones' | 'Avanzado';

export interface FieldOption {
  value: string | number;
  label: string;
}

export interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  group: FieldGroup;
  help: string;
  /** Shown in the always-visible core section. */
  focused?: boolean;
  /** Applied on devices by the DallyControl agent via config.apply. Unset = legacy Headwind field, not enforced. */
  enforced?: boolean;
  /** Configuration metadata (name, description): always shown, never sent to devices, not "Enforced". */
  metadata?: boolean;
  options?: FieldOption[];
  min?: number;
  max?: number;
}

export const GROUP_ORDER: FieldGroup[] = [
  'Identidad', 'Apps', 'Quiosco', 'Red',
  'Seguridad', 'Restricciones', 'Pantalla', 'Actualizaciones', 'Avanzado',
];

export const CONFIG_FIELDS: FieldDef[] = [
  // ── Identity ──────────────────────────────────────────────────────────────
  { key: 'name', label: 'Nombre', type: 'text', group: 'Identidad', focused: true, metadata: true, help: 'Nombre único de esta plantilla de política.' },
  { key: 'description', label: 'Descripción', type: 'textarea', group: 'Identidad', focused: true, metadata: true, help: 'Notas opcionales sobre para qué sirve esta plantilla.' },

  // ── Apps ──────────────────────────────────────────────────────────────────
  { key: 'mainAppId', label: 'App principal', type: 'app', group: 'Apps', focused: true, enforced: true, help: 'App principal que se abre en el dispositivo (la app del quiosco en modo quiosco).' },
  { key: 'contentAppId', label: 'App de contenido', type: 'app', group: 'Apps', help: 'App opcional para entregar contenido.' },
  { key: 'autostartForeground', label: 'Mantener apps en primer plano', type: 'tri', group: 'Apps', help: 'Mantiene en primer plano las apps que se inician solas.' },

  // ── Kiosk ───────────────────────────────────────────────────────────────-─
  { key: 'kioskMode', label: 'Modo quiosco', type: 'switch', group: 'Quiosco', focused: true, enforced: true, help: 'Limita el dispositivo a la app principal (bloqueo en una sola app).' },
  { key: 'runDefaultLauncher', label: 'Permitir launcher de fábrica', type: 'tri', group: 'Quiosco', help: 'Permite el launcher de Android de fábrica en lugar de la pantalla de inicio del MDM.' },
  { key: 'kioskExit', label: 'Botón Salir del quiosco', type: 'tri', group: 'Quiosco', enforced: true, help: 'Muestra un botón para salir del modo quiosco. Auto = oculto en el quiosco (se sale con el gesto de 7 toques en la esquina); elige «Sí» para mostrarlo.' },
  { key: 'kioskHome', label: 'Botón Inicio', type: 'tri', group: 'Quiosco', enforced: true, help: 'Permite el botón Inicio en el quiosco. Auto = oculto en el quiosco; elige «Sí» para mostrarlo.' },
  { key: 'kioskRecents', label: 'Botón Recientes', type: 'tri', group: 'Quiosco', enforced: true, help: 'Permite el botón de apps recientes en el quiosco (requiere el botón Inicio). Auto = oculto en el quiosco; elige «Sí» para mostrarlo.' },
  { key: 'kioskNotifications', label: 'Notificaciones', type: 'tri', group: 'Quiosco', enforced: true, help: 'Permite la barra de notificaciones en el quiosco. Auto = oculta en el quiosco; elige «Sí» para mostrarla.' },
  { key: 'kioskSystemInfo', label: 'Hora, batería y señal', type: 'tri', group: 'Quiosco', enforced: true, help: 'Muestra la barra de estado (hora, batería y señal) en el quiosco. Auto = oculta en el quiosco; elige «Sí» para mostrarla.' },
  { key: 'kioskKeyguard', label: 'Pantalla de bloqueo', type: 'tri', group: 'Quiosco', enforced: true, help: 'Permite la pantalla de bloqueo en el quiosco.' },
  { key: 'kioskLockButtons', label: 'Bloquear botones físicos', type: 'tri', group: 'Quiosco', enforced: true, help: 'Desactiva los botones de encendido y volumen en el quiosco.' },
  { key: 'kioskScreenOn', label: 'Mantener pantalla encendida', type: 'tri', group: 'Quiosco', help: 'Obliga a que la pantalla no se apague en el quiosco.' },
  { key: 'showWifi', label: 'Mostrar Wi‑Fi si hay error', type: 'tri', group: 'Quiosco', help: 'Muestra los ajustes de Wi‑Fi si el dispositivo pierde conexión en el quiosco.' },

  // ── Network ─────────────────────────────────────────────────────────────-─
  { key: 'wifi', label: 'Wi‑Fi', type: 'tri', group: 'Red', focused: true, enforced: true, help: 'Radio Wi‑Fi: sin administrar, forzar encendido o forzar apagado.' },
  { key: 'mobileData', label: 'Datos móviles', type: 'tri', group: 'Red', focused: true, help: 'Datos móviles: sin administrar, encendidos o apagados.' },
  { key: 'bluetooth', label: 'Bluetooth', type: 'tri', group: 'Red', focused: true, enforced: true, help: 'Radio Bluetooth: sin administrar, encendido o apagado.' },
  { key: 'gps', label: 'GPS / ubicación', type: 'tri', group: 'Red', focused: true, help: 'Ubicación: sin administrar, encendida o apagada.' },
  { key: 'requestUpdates', label: 'Reporte de ubicación', type: 'enum', group: 'Red', enforced: true, help: 'Captura de ubicación: GPS = posición nueva en cada reporte (precisa); si no, la última conocida de forma pasiva.', options: [
    { value: 'DONOTTRACK', label: 'No rastrear' }, { value: 'GPS', label: 'GPS' }, { value: 'WIFI', label: 'Red (Wi‑Fi/celular)' },
  ] },
  { key: 'wifiSSID', label: 'SSID Wi‑Fi de aprovisionamiento', type: 'text', group: 'Red', help: 'Red Wi‑Fi a la que se conecta automáticamente durante la inscripción.' },
  { key: 'wifiPassword', label: 'Contraseña Wi‑Fi de aprovisionamiento', type: 'password', group: 'Red', help: 'Contraseña de la red Wi‑Fi de aprovisionamiento.' },
  { key: 'wifiSecurityType', label: 'Seguridad Wi‑Fi de aprovisionamiento', type: 'enum', group: 'Red', help: 'Tipo de seguridad de la red Wi‑Fi de aprovisionamiento.', options: [
    { value: 'NONE', label: 'Abierta' }, { value: 'WPA', label: 'WPA/WPA2' }, { value: 'WEP', label: 'WEP' }, { value: 'EAP', label: 'Empresarial (EAP)' },
  ] },
  { key: 'mobileEnrollment', label: 'Inscribir con datos móviles', type: 'switch', group: 'Red', help: 'Prefiere los datos móviles sobre el Wi‑Fi durante el aprovisionamiento.' },

  // ── Security ───────────────────────────────────────────────────────────-─
  { key: 'password', label: 'Contraseña de administrador', type: 'password', group: 'Seguridad', enforced: true, help: 'Contraseña para salir del quiosco (se guarda tal como se escribe).' },
  { key: 'appPermissions', label: 'Permisos de apps', type: 'enum', group: 'Seguridad', help: 'Cómo se manejan los permisos en tiempo de ejecución de las apps administradas.', options: [
    { value: 'GRANTALL', label: 'Conceder todos automáticamente' }, { value: 'ASKLOCATION', label: 'Preguntar solo por ubicación' },
    { value: 'DENYLOCATION', label: 'Denegar ubicación' }, { value: 'ASKALL', label: 'Preguntar por todo' },
  ] },
  { key: 'encryptDevice', label: 'Exigir cifrado', type: 'switch', group: 'Seguridad', help: 'Exige el cifrado completo del dispositivo.' },
  { key: 'permissive', label: 'Modo permisivo', type: 'tri', group: 'Seguridad', help: 'Relaja la aplicación de políticas (modo permisivo).' },
  { key: 'lockSafeSettings', label: 'Bloquear ajustes de modo seguro', type: 'tri', group: 'Seguridad', help: 'Bloquea el acceso a ajustes que podrían saltarse el MDM.' },
  { key: 'disableLocation', label: 'Bloquear permiso de ubicación', type: 'tri', group: 'Seguridad', help: 'Impide que se conceda a las apps el permiso de ubicación.' },
  { key: 'passwordMode', label: 'Política de contraseña', type: 'text', group: 'Seguridad', help: 'Política del código de bloqueo del dispositivo (avanzado; texto JSON).' },

  // ── Restrictions ─────────────────────────────────────────────────────────
  { key: 'usbStorage', label: 'Almacenamiento USB', type: 'tri', group: 'Restricciones', focused: true, enforced: true, help: 'Permite el acceso a almacenamiento masivo USB.' },
  { key: 'blockStatusBar', label: 'Bloquear barra de estado', type: 'switch', group: 'Restricciones', help: 'Impide desplegar la barra de estado del sistema.' },
  { key: 'disableScreenshots', label: 'Bloquear capturas de pantalla', type: 'tri', group: 'Restricciones', enforced: true, help: 'Impide las capturas y la grabación de pantalla.' },
  { key: 'lockVolume', label: 'Bloquear volumen', type: 'tri', group: 'Restricciones', help: 'Desactiva los botones de volumen.' },
  { key: 'allowedClasses', label: 'Clases de apps permitidas', type: 'text', group: 'Restricciones', help: 'Lista separada por comas de las clases de componentes de apps permitidas.' },
  { key: 'restrictions', label: 'Restricciones de Android', type: 'textarea', group: 'Restricciones', help: 'Restricciones de usuario de Android, separadas por comas, que se aplican en modo MDM.' },

  // ── Display ────────────────────────────────────────────────────────────-─
  { key: 'autoBrightness', label: 'Brillo automático', type: 'tri', group: 'Pantalla', help: 'Administra el brillo automático de la pantalla.' },
  { key: 'brightness', label: 'Brillo', type: 'int', group: 'Pantalla', min: 0, max: 255, help: 'Brillo manual 0–255 (cuando el brillo automático está apagado).' },
  { key: 'manageTimeout', label: 'Administrar tiempo de pantalla', type: 'tri', group: 'Pantalla', help: 'Controla el tiempo antes de que se apague la pantalla.' },
  { key: 'timeout', label: 'Tiempo de pantalla (s)', type: 'int', group: 'Pantalla', min: 0, help: 'Segundos antes de que se apague la pantalla (si se administra).' },
  { key: 'manageVolume', label: 'Administrar volumen', type: 'tri', group: 'Pantalla', help: 'Controla el nivel de volumen del sistema.' },
  { key: 'volume', label: 'Volumen (%)', type: 'int', group: 'Pantalla', min: 0, max: 100, help: 'Volumen del sistema 0–100 % (si se administra).' },
  { key: 'orientation', label: 'Orientación de pantalla', type: 'enum', group: 'Pantalla', help: 'Fija la orientación de la pantalla.', options: [
    { value: 0, label: 'Sin fijar (auto)' }, { value: 1, label: 'Vertical' }, { value: 2, label: 'Horizontal' },
  ] },
  { key: 'useDefaultDesignSettings', label: 'Diseño de launcher predeterminado', type: 'switch', group: 'Pantalla', help: 'Usa el aspecto de launcher predeterminado (ignora los colores personalizados de abajo).' },
  { key: 'backgroundColor', label: 'Color de fondo', type: 'color', group: 'Pantalla', enforced: true, help: 'Color de fondo del launcher.' },
  { key: 'textColor', label: 'Color del texto', type: 'color', group: 'Pantalla', enforced: true, help: 'Color del texto del launcher.' },
  { key: 'backgroundImageUrl', label: 'URL de imagen de fondo', type: 'text', group: 'Pantalla', help: 'URL de una imagen de fondo personalizada para el launcher.' },
  { key: 'iconSize', label: 'Tamaño de íconos', type: 'enum', group: 'Pantalla', enforced: true, help: 'Tamaño de los íconos de apps en el launcher.', options: [
    { value: 'SMALL', label: 'Pequeño' }, { value: 'MEDIUM', label: 'Mediano' }, { value: 'LARGE', label: 'Grande' },
  ] },
  { key: 'desktopHeader', label: 'Encabezado del launcher', type: 'enum', group: 'Pantalla', help: 'Qué mostrar en el encabezado encima del launcher.', options: [
    { value: 'NO_HEADER', label: 'Ninguno' }, { value: 'DEVICE_ID', label: 'ID del dispositivo' }, { value: 'DESCRIPTION', label: 'Descripción' },
    { value: 'CUSTOM1', label: 'Personalizado 1' }, { value: 'CUSTOM2', label: 'Personalizado 2' }, { value: 'CUSTOM3', label: 'Personalizado 3' }, { value: 'TEMPLATE', label: 'Plantilla' },
  ] },
  { key: 'desktopHeaderTemplate', label: 'Plantilla del encabezado', type: 'text', group: 'Pantalla', help: 'Texto personalizado del encabezado (cuando el encabezado = Plantilla).' },
  { key: 'displayStatus', label: 'Mostrar barra de estado', type: 'switch', group: 'Pantalla', help: 'Muestra el estado del dispositivo (batería, hora) en el launcher.' },

  // ── Updates ───────────────────────────────────────────────────────────-──
  { key: 'systemUpdateType', label: 'Actualizaciones del sistema', type: 'enum', group: 'Actualizaciones', help: 'Cuándo se instalan las actualizaciones de Android.', options: [
    { value: 0, label: 'Predeterminado' }, { value: 1, label: 'De inmediato' }, { value: 2, label: 'Programadas' }, { value: 3, label: 'Aplazadas' },
  ] },
  { key: 'systemUpdateFrom', label: 'Actualización del sistema desde', type: 'time', group: 'Actualizaciones', help: 'Inicio de la ventana de actualización del sistema (HH:MM, si es Programadas).' },
  { key: 'systemUpdateTo', label: 'Actualización del sistema hasta', type: 'time', group: 'Actualizaciones', help: 'Fin de la ventana de actualización del sistema (HH:MM, si es Programadas).' },
  { key: 'scheduleAppUpdate', label: 'Programar actualizaciones de apps', type: 'switch', group: 'Actualizaciones', help: 'Solo instala actualizaciones de apps dentro de una franja horaria.' },
  { key: 'appUpdateFrom', label: 'Actualización de apps desde', type: 'time', group: 'Actualizaciones', help: 'Inicio de la ventana de actualización de apps (HH:MM).' },
  { key: 'appUpdateTo', label: 'Actualización de apps hasta', type: 'time', group: 'Actualizaciones', help: 'Fin de la ventana de actualización de apps (HH:MM).' },
  { key: 'downloadUpdates', label: 'Descargar actualizaciones por', type: 'enum', group: 'Actualizaciones', help: 'Qué conexión pueden usar las descargas de apps y del sistema.', options: [
    { value: 'UNLIMITED', label: 'Cualquier conexión' }, { value: 'LIMITED', label: 'Se permiten datos limitados' }, { value: 'WIFI', label: 'Solo Wi‑Fi' },
  ] },

  // ── Advanced / Other ───────────────────────────────────────────────────-─
  { key: 'timeZone', label: 'Zona horaria', type: 'text', group: 'Avanzado', help: "Zona horaria Olson (p. ej. America/Bogota) o 'auto'." },
  { key: 'newServerUrl', label: 'Migrar a la URL del servidor', type: 'text', group: 'Avanzado', help: 'Mueve los dispositivos a otra URL de servidor MDM.' },
  { key: 'eventReceivingComponent', label: 'Receptor de eventos', type: 'text', group: 'Avanzado', help: 'paquete/clase que recibe los broadcasts del MDM.' },
  { key: 'qrParameters', label: 'Parámetros QR adicionales', type: 'textarea', group: 'Avanzado', help: 'Campos adicionales que se agregan al QR de aprovisionamiento.' },
  { key: 'adminExtras', label: 'Extras de administrador del QR', type: 'textarea', group: 'Avanzado', help: 'Entradas adicionales para el paquete de administrador del QR.' },
];

export const ENFORCED_FIELDS = CONFIG_FIELDS.filter((f) => f.enforced);
/** Always-visible editor fields: metadata + enforced. */
export const PRIMARY_FIELDS = CONFIG_FIELDS.filter((f) => f.enforced || f.metadata);
export const LEGACY_FIELDS = CONFIG_FIELDS.filter((f) => !f.enforced && !f.metadata);
export const ENFORCED_KEYS: ReadonlySet<string> = new Set(ENFORCED_FIELDS.map((f) => f.key));
/** Changing any of these re-enters/exits kiosk on every device of the configuration. */
export const KIOSK_AFFECTING_KEYS: ReadonlySet<string> = new Set([
  'kioskMode', 'mainAppId', 'kioskExit', 'kioskHome', 'kioskRecents', 'kioskNotifications', 'kioskSystemInfo',
  'kioskKeyguard', 'kioskLockButtons', 'password', 'backgroundColor', 'textColor', 'iconSize', 'applications', 'dcPolicy',
]);
