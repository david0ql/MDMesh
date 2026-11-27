import { useEffect, useState } from 'react';
import { apiClient } from '../api/client';

// Connection state and diagnostics per device (server: /private/connection/v1). The server tells a silence with a
// known, harmless reason ("En reposo": switched off, battery spent, screen off) from "Sin conexión", and keeps the
// last network failure the agent reported. Polled while some page shows device status, like ui/live.

export interface ConnectionFailure {
  kind: string;
  label: string;
  at?: number | null;
  detail?: string | null;
  httpCode?: number | null;
  channel?: 'checkin' | 'socket' | null;
  network?: string | null;
  networkLabel?: string | null;
  ssid?: string | null;
  host?: string | null;
  certIssuer?: string | null;
  count?: number | null;
}

export interface ConnectionStop {
  reason: string;
  label: string;
  at?: number | null;
  resumedAt?: number | null;
  battery?: number | null;
}

export interface DeviceConnection {
  number: string;
  state: 'online' | 'resting' | 'offline';
  /** Why it rests: shutdown | lowBattery | screenOff. */
  reason?: string;
  label?: string;
  serverHost?: string | null;
  usingBackup?: boolean | null;
  reconnects?: number | null;
  lastFailure?: ConnectionFailure;
  lastStop?: ConnectionStop;
}

export type ConnectionTone = 'online' | 'resting' | 'offline';

/**
 * What to show for a device. `online` is the console's own verdict (live channel or a recent report) and wins; a
 * device that is not online shows "En reposo" only when the server found a reason, else "Sin conexión" as before.
 */
export function connectionLabel(online: boolean, c?: DeviceConnection): { tone: ConnectionTone; label: string; hint?: string } {
  if (online) return { tone: 'online', label: 'En línea' };
  if (c?.state === 'resting') return { tone: 'resting', label: 'En reposo', hint: c.label };
  return { tone: 'offline', label: 'Sin conexión' };
}

let byNumber = new Map<string, DeviceConnection>();
const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | undefined;

async function refresh() {
  try {
    const rows = await apiClient.get<DeviceConnection[]>('/private/connection/v1/devices');
    byNumber = new Map((rows ?? []).map((r) => [r.number, r]));
    listeners.forEach((l) => l());
  } catch {
    // an older server, or a hiccup: keep the last answer; devices then show online / sin conexión as before
  }
}

/** Connection state of a device as of the last poll (undefined: unknown). */
export const connectionOf = (number?: string) => (number ? byNumber.get(number) : undefined);

/** Keeps the states fresh (every 60 s) while the calling component is mounted; re-renders it on change. */
export function useConnectionStates(): Map<string, DeviceConnection> {
  const [, tick] = useState(0);
  useEffect(() => {
    const l = () => tick((n) => n + 1);
    listeners.add(l);
    if (!timer) {
      void refresh();
      timer = setInterval(() => void refresh(), 60_000);
    }
    return () => {
      listeners.delete(l);
      if (listeners.size === 0 && timer) { clearInterval(timer); timer = undefined; }
    };
  }, []);
  return byNumber;
}
