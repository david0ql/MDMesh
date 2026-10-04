import { apiClient } from './client';

// Queue a command for a device (agent v1 contract):
//   POST /rest/private/agent/v1/devices/{deviceId}/commands
//   body: { type, requiresCapability?, payload? }   payload is a JSON STRING.
// Returns the queued command (we read its id defensively for the toast).

export interface QueueCommandRequest {
  type: string;
  requiresCapability?: string;
  /** A JSON-encoded string, not an object. */
  payload?: string;
}

export interface QueuedCommand {
  id?: number | string;
  type?: string;
  status?: string;
  [key: string]: unknown;
}

/** A user-facing command the console can dispatch. */
export interface CommandTemplate {
  key: string;
  label: string;
  description: string;
  /** Visual tone: a destructive/disruptive action is flagged. */
  danger?: boolean;
  request: QueueCommandRequest;
}

export const COMMAND_TEMPLATES: CommandTemplate[] = [
  {
    key: 'wifi-off',
    label: 'Desactivar Wi‑Fi',
    description: 'Aplica una política que apaga el Wi‑Fi del dispositivo.',
    request: {
      type: 'policy.apply',
      requiresCapability: 'policy.wifi',
      payload: JSON.stringify({ policy: 'wifi', value: false }),
    },
  },
  {
    key: 'wifi-on',
    label: 'Activar Wi‑Fi',
    description: 'Aplica una política que enciende el Wi‑Fi del dispositivo.',
    request: {
      type: 'policy.apply',
      requiresCapability: 'policy.wifi',
      payload: JSON.stringify({ policy: 'wifi', value: true }),
    },
  },
  {
    key: 'camera-off',
    label: 'Desactivar cámara',
    description: 'Aplica una política que bloquea la cámara del dispositivo.',
    request: {
      type: 'policy.apply',
      requiresCapability: 'policy.camera',
      payload: JSON.stringify({ policy: 'camera', value: false }),
    },
  },
  {
    key: 'reboot',
    label: 'Reiniciar',
    description: 'Reinicia el dispositivo ahora.',
    danger: true,
    request: { type: 'device.reboot' },
  },
  {
    key: 'lock',
    label: 'Bloquear',
    description: 'Bloquea la pantalla del dispositivo de inmediato.',
    danger: true,
    request: { type: 'device.lock' },
  },
];

export async function queueCommand(
  deviceId: number | string,
  req: QueueCommandRequest,
  signal?: AbortSignal,
): Promise<QueuedCommand> {
  return apiClient.post<QueuedCommand>(
    `/private/agent/v1/devices/${deviceId}/commands`,
    req,
    signal,
  );
}

export interface BulkCommandResult {
  queued: number;
  skipped: number[];
}

/**
 * Queue one opaque command for many devices at once (agent-v1 bulk contract):
 *   POST /rest/private/agent/v1/bulk/commands   body: { deviceIds, command }
 * `deviceIds` are numeric device ids (as held by the DevicesPage selection); the server resolves each
 * to its device number. Destructive types are rejected server-side. Fire-and-forget.
 */
export async function bulkQueueCommand(
  deviceIds: number[],
  req: QueueCommandRequest,
  signal?: AbortSignal,
): Promise<BulkCommandResult> {
  return apiClient.post<BulkCommandResult>(
    '/private/agent/v1/bulk/commands',
    { deviceIds, command: req },
    signal,
  );
}

// --- Remote Actions catalog + device state/history/sync ----------------------------

export interface ActionParam {
  key: string;
  label: string;
  kind: 'text' | 'password' | 'number';
  required?: boolean;
  placeholder?: string;
}

export interface CommandTemplateExt extends CommandTemplate {
  /** Inputs gathered before sending; values folded into the payload by `build`. */
  params?: ActionParam[];
  /** Confirmation strength required before queueing. */
  confirm?: 'simple' | 'type-to-confirm';
  /** Bucket for grouping in the UI. */
  group?: 'safe' | 'disruptive' | 'destructive';
  /** Builds the request from gathered params (overrides static `request` when present). */
  build?: (values: Record<string, string>) => QueueCommandRequest;
  /** A message when the gathered params are not valid yet (sending stays disabled). */
  validate?: (values: Record<string, string>) => string | null;
}

export const ACTION_TEMPLATES: CommandTemplateExt[] = [
  {
    key: 'lockscreen-message', label: 'Mensaje en pantalla de bloqueo', group: 'safe',
    description: 'Muestra un mensaje en la pantalla de bloqueo del dispositivo (vacío lo borra).',
    params: [{ key: 'message', label: 'Mensaje', kind: 'text', placeholder: 'Propiedad de ACME TI' }],
    request: { type: 'device.lockscreenMessage', requiresCapability: 'device.lockscreenMessage' },
    build: (v) => ({
      type: 'device.lockscreenMessage', requiresCapability: 'device.lockscreenMessage',
      payload: JSON.stringify({ message: v.message ?? '' }),
    }),
  },
  {
    key: 'alert', label: 'Enviar mensaje', group: 'safe',
    description: 'Muestra un mensaje de alta prioridad en el dispositivo.',
    params: [
      { key: 'title', label: 'Título', kind: 'text', placeholder: 'Mensaje de TI' },
      { key: 'body', label: 'Mensaje', kind: 'text', required: true },
    ],
    request: { type: 'device.alert', requiresCapability: 'device.alert' },
    build: (v) => ({
      type: 'device.alert', requiresCapability: 'device.alert',
      payload: JSON.stringify({ title: v.title || undefined, body: v.body ?? '' }),
    }),
  },
  {
    key: 'ring', label: 'Hacer sonar', group: 'safe',
    description: 'Reproduce un tono fuerte durante 30 segundos para encontrarlo.',
    request: {
      type: 'device.ring', requiresCapability: 'device.ring',
      payload: JSON.stringify({ durationMs: 30000 }),
    },
  },
  {
    key: 'ring-stop', label: 'Dejar de sonar', group: 'safe',
    description: 'Detiene el tono de localización y, sea lo que sea que esté sonando (alarma, timbre, «Encontrar mi dispositivo»), silencia el teléfono 5 minutos (agente 0.7.4+).',
    request: { type: 'device.ringStop', requiresCapability: 'device.ringStop' },
  },
  {
    key: 'app-launch', label: 'Abrir app', group: 'safe',
    description: 'Trae al frente una app instalada (en quiosco, solo las apps que el quiosco permite).',
    params: [{ key: 'packageName', label: 'Paquete', kind: 'text', required: true, placeholder: 'co.amovil.preventa' }],
    request: { type: 'device.appLaunch', requiresCapability: 'device.appLaunch' },
    build: (v) => ({
      type: 'device.appLaunch', requiresCapability: 'device.appLaunch',
      payload: JSON.stringify({ packageName: (v.packageName ?? '').trim() }),
    }),
  },
  {
    key: 'lock', label: 'Bloquear', group: 'disruptive', danger: true,
    description: 'Bloquea la pantalla del dispositivo de inmediato.',
    request: { type: 'device.lock', requiresCapability: 'device.lock' },
  },
  {
    key: 'reboot', label: 'Reiniciar', group: 'disruptive', danger: true,
    description: 'Reinicia el dispositivo ahora.', confirm: 'simple',
    request: { type: 'device.reboot', requiresCapability: 'device.reboot' },
  },
  {
    key: 'passcode-reset', label: 'Cambiar clave del dispositivo', group: 'disruptive', danger: true,
    description: 'Pone una clave nueva (PIN o contraseña) para desbloquear el teléfono. Déjala vacía para quitarla. Si el teléfono ya tenía clave antes de inscribirse, hay que desbloquearlo una vez con la clave actual para que el cambio remoto funcione.',
    confirm: 'simple',
    params: [
      { key: 'newPassword', label: 'Clave nueva (vacía para quitarla)', kind: 'password' },
      { key: 'repeat', label: 'Repite la clave', kind: 'password' },
    ],
    validate: (v) => (v.newPassword ?? '') !== (v.repeat ?? '') ? 'Las claves no coinciden.'
      : (v.newPassword ?? '').length > 0 && (v.newPassword ?? '').length < 4 ? 'Mínimo 4 caracteres.' : null,
    request: { type: 'device.passcodeReset', requiresCapability: 'device.passcodeReset' },
    build: (v) => ({
      type: 'device.passcodeReset', requiresCapability: 'device.passcodeReset',
      payload: JSON.stringify({ newPassword: v.newPassword ?? '' }),
    }),
  },
  {
    key: 'clear-cache', label: 'Borrar caché de las apps', group: 'safe',
    description: 'Abre en el teléfono la confirmación de Android para borrar la caché de todas las apps (Android 11 o superior; la persona toca Aceptar). No borra datos ni sesiones.',
    request: { type: 'device.clearCache', requiresCapability: 'device.clearCache' },
  },
  {
    key: 'wipe', label: 'Restablecer de fábrica (borrar todo)', group: 'destructive', danger: true,
    description: 'Borra el dispositivo por completo. No se puede deshacer.', confirm: 'type-to-confirm',
    request: { type: 'device.wipe', requiresCapability: 'device.wipe' },
  },
  {
    key: 'power-adaptive', label: 'Conectividad: ahorro de batería', group: 'safe',
    description: 'Mantiene la conexión en vivo solo con la pantalla encendida o cargando; en reposo usa el latido de bajo consumo. Opción por defecto, cuida la batería.',
    request: {
      type: 'device.powerMode', requiresCapability: 'device.powerMode',
      payload: JSON.stringify({ mode: 'adaptive' }),
    },
  },
  {
    key: 'power-always', label: 'Conectividad: Siempre conectado', group: 'safe',
    description: 'Mantiene la conexión en vivo 24/7 para que las órdenes y las sesiones remotas lleguen al instante, incluso bloqueado y sin cargar (gasta más batería). Úsalo en dispositivos que necesitan soporte inmediato.',
    request: {
      type: 'device.powerMode', requiresCapability: 'device.powerMode',
      payload: JSON.stringify({ mode: 'alwaysOn' }),
    },
  },
  {
    key: 'location-passive', label: 'Ubicación: ahorro de batería', group: 'safe',
    description: 'Reporta la última ubicación conocida en cada conexión — casi sin gasto de batería, sin GPS activo.',
    request: {
      type: 'device.locationMode', requiresCapability: 'device.locationMode',
      payload: JSON.stringify({ mode: 'passive' }),
    },
  },
  {
    key: 'location-active', label: 'Ubicación: precisa', group: 'safe',
    description: 'Toma una posición GPS nueva en cada conexión para un seguimiento más preciso (gasta más batería).',
    request: {
      type: 'device.locationMode', requiresCapability: 'device.locationMode',
      payload: JSON.stringify({ mode: 'active' }),
    },
  },
  {
    // Handled specially by ActionConsole: opens the app-picker modal (scans the device, builds the
    // KioskApplyPayload, queues kiosk.enter ungated). Listed here only for the button + grouping.
    key: 'kiosk-enter', label: 'Entrar en quiosco', group: 'disruptive', danger: true,
    description: 'Limita el dispositivo a una app o a un grupo de apps, elegidas a partir de un escaneo del dispositivo.',
    request: { type: 'kiosk.enter' },
  },
  {
    key: 'kiosk-exit', label: 'Salir del quiosco', group: 'disruptive',
    description: 'Sale del modo quiosco y restaura la pantalla de inicio normal.',
    request: { type: 'kiosk.exit' },
  },
];

export interface DeviceState {
  battery: number; charging: boolean; locked: boolean; kioskActive: boolean;
  androidRelease: string; lastBootAt: number; updatedAt: number;
  agentVersion?: string | null;
  powerMode?: string | null;
  appliedConfigRevision?: string | null;
}

export interface CommandHistoryItem {
  id: number | string; type: string; status: string;
  detail?: string | null; createdAt?: number; deliveredAt?: number; completedAt?: number;
  /** Server-derived package name for app.install / app.uninstall; absent otherwise. Payloads are never returned. */
  subject?: string | null;
}

export async function getDeviceState(deviceId: number | string): Promise<DeviceState | null> {
  return apiClient.get<DeviceState | null>(`/private/agent/v1/devices/${deviceId}/state`);
}

export async function listCommandHistory(
  deviceId: number | string, since = 0, signal?: AbortSignal,
): Promise<CommandHistoryItem[]> {
  return apiClient.get<CommandHistoryItem[]>(
    `/private/agent/v1/devices/${deviceId}/commands?since=${since}`,
    signal,
  );
}

export async function forceSync(deviceId: number | string): Promise<void> {
  await apiClient.post(`/private/agent/v1/devices/${deviceId}/sync`, {});
}

/** Queue app.install for the device's configuration apps marked action=install. */
export async function syncConfigApps(deviceId: number | string): Promise<{ queued: number }> {
  return apiClient.post<{ queued: number }>(`/private/agent/v1/devices/${deviceId}/syncApps`, {});
}

// --- App deploy (app store) -------------------------------------------------

export interface AppInstallSpec {
  url: string;
  packageName: string;
  versionCode?: number;
  sha256?: string;
  runAfterInstall?: boolean;
  /** Split-APK bundle: when present the agent installs all parts in one session
   *  and `url`/`sha256` are ignored. */
  parts?: { url: string; sha256?: string }[];
}

/** Build the agent-v1 `app.install` command request for a spec (single APK or split bundle). */
export function buildInstallCommand(spec: AppInstallSpec): QueueCommandRequest {
  // A split bundle installs from `parts`; a single APK from `url`/`sha256`.
  const payload = spec.parts?.length
    ? {
        packageName: spec.packageName,
        versionCode: spec.versionCode,
        runAfterInstall: spec.runAfterInstall ?? false,
        parts: spec.parts,
      }
    : {
        url: spec.url,
        packageName: spec.packageName,
        versionCode: spec.versionCode,
        sha256: spec.sha256,
        runAfterInstall: spec.runAfterInstall ?? false,
      };
  return {
    type: 'app.install',
    // The gate token is the prefixed appManagement key (app.<key>); the agent advertises
    // app.silentInstall. (Bare 'silentInstall' never matched — see proto/endpoints.md.)
    requiresCapability: 'app.silentInstall',
    payload: JSON.stringify(payload),
  };
}

/** Queue an `app.install` for a device (the proven OTA path). */
export async function installApp(
  deviceId: number | string,
  spec: AppInstallSpec,
): Promise<QueuedCommand> {
  return queueCommand(deviceId, buildInstallCommand(spec));
}

/** Send the device its configuration again (e.g. back into the configuration's kiosk after a manual exit). */
export async function reapplyConfiguration(deviceId: number | string): Promise<void> {
  await apiClient.post(`/private/agent/v1/devices/${deviceId}/config/reapply`, {});
}

/** The DallyControl agent this server hosts, as an install spec (the phone installs it over itself, sha256-checked). */
export async function agentPackage(): Promise<AppInstallSpec & { version?: string }> {
  return apiClient.get<AppInstallSpec & { version?: string }>('/private/agent-package');
}

/** The remote-support app (droidVNC-NG) the server hosts, as an install spec (for installing it on many devices). */
export async function remoteSupportPackage(): Promise<AppInstallSpec> {
  return apiClient.get<AppInstallSpec>('/private/agent/v1/remote/package');
}

/** Install remote support (droidVNC-NG, hosted by the server) on a device enrolled without USB remote support. */
export async function setupRemoteSupport(deviceId: number | string): Promise<void> {
  await apiClient.post(`/private/agent/v1/devices/${deviceId}/remote/setup`, {});
}
