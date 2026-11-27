import { apiClient } from './client';

// The second wake channel: by which way a device learns that a command is waiting.
//   GET /rest/private/agent/v1/devices/{number}/reach   → DeviceReach
//   GET /rest/private/agent/v1/push/status              → PushStatus (administrators)

export type WakeChannelKind = 'live' | 'push' | 'poll';

export interface DeviceReach {
  channel: WakeChannelKind;
  /** Upper end of the usual wait, in seconds. */
  expectedSeconds: number;
  /** The server has a push project. */
  pushConfigured: boolean;
  /** Without a battery exemption a parked phone stretches its own check-ins. */
  mayBeLonger?: boolean;
  pushState?: 'ready' | 'unconfigured' | 'unavailable' | 'error' | null;
  pushError?: string | null;
  hasToken?: boolean;
  tokenAt?: number | null;
  batteryExempt?: boolean | null;
  exactAlarms?: boolean | null;
  bgRestricted?: boolean | null;
  pollMinutes?: number | null;
  lastWokenBy?: string | null;
  lastSentAt?: number | null;
  lastSentOk?: boolean | null;
  lastSendError?: string | null;
  lastWakeMs?: number | null;
}

export interface PushStatus {
  configured: boolean;
  canSend: boolean;
  canRegister: boolean;
  projectId?: string | null;
  problem?: string | null;
  sent: number;
  failed: number;
  answered: number;
  lastOkAt?: number | null;
  recentErrors: { at: number; device: string; message: string }[];
  devicesWithToken: number;
  sms?: string | null;
}

export function getReach(deviceNumber: string): Promise<DeviceReach> {
  return apiClient.get<DeviceReach>(`/private/agent/v1/devices/${encodeURIComponent(deviceNumber)}/reach`);
}

export function getPushStatus(): Promise<PushStatus> {
  return apiClient.get<PushStatus>('/private/agent/v1/push/status');
}

export const CHANNEL_LABEL: Record<WakeChannelKind, string> = {
  live: 'Canal en vivo',
  push: 'Mensaje push (FCM)',
  poll: 'Solo sondeo del equipo',
};

/** "unos 5 s", "hasta 5 min". */
export function expectedWait(seconds: number | undefined | null): string {
  if (seconds == null) return '—';
  if (seconds < 60) return `unos ${seconds} s`;
  const min = Math.round(seconds / 60);
  return `hasta ${min} min`;
}

export const WOKEN_BY_LABEL: Record<string, string> = {
  push: 'un mensaje push',
  socket: 'el canal en vivo',
  alarm: 'su alarma de respaldo',
  screen: 'encender la pantalla',
  unlock: 'desbloquear el equipo',
  power: 'conectar el cargador',
  network: 'recuperar la red',
  worker: 'la tarea periódica',
  boot: 'el arranque',
  other: 'otra causa',
};

export const PUSH_STATE_LABEL: Record<string, string> = {
  ready: 'Registrado',
  unconfigured: 'El servidor no tiene proyecto de mensajes',
  unavailable: 'El equipo no tiene servicios de Google',
  error: 'No se pudo registrar',
};

/**
 * What to tell the operator right after queueing a command for a device, from the channel the server reported in
 * its answer. `urgent` = a lock, an alarm, a message…: those are also pushed when the live channel looks open.
 */
export function queuedNote(channel: WakeChannelKind | undefined, expectedSeconds: number | undefined): string | null {
  if (channel === 'live' || !channel) return null;
  if (channel === 'push') {
    return `El equipo no tiene el canal en vivo abierto: se le despierta con un mensaje push y debería ejecutar la orden en ${expectedWait(expectedSeconds)}. Si no responde, la orden sigue en cola.`;
  }
  return `El equipo no tiene el canal en vivo ni mensajes push: la orden queda en cola y se ejecuta en su próximo reporte (${expectedWait(expectedSeconds)}; más si el equipo está en reposo profundo o apagado).`;
}
