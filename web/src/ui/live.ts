import { useEffect, useState } from 'react';
import { apiClient } from '../api/client';

// Devices whose live channel (the agent's wake socket) is open right now. A phone reports about every 20 minutes when
// idle, so a report a little late showed "Sin conexión" while the console could still reach it at once; the open
// channel is the truth for "connected". Polled while some page shows device status.

let live = new Set<string>();
let listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | undefined;

async function refresh() {
  try {
    live = new Set(await apiClient.get<string[]>('/private/agent/v1/live'));
    listeners.forEach((l) => l());
  } catch {
    // keep the last answer; recency still applies
  }
}

/** Whether this device's live channel is open (as of the last poll). */
export const isLive = (number?: string) => !!number && live.has(number);

/** Keeps the live set fresh (every 30 s) while the calling component is mounted; re-renders it on change. */
export function useLiveDevices(): Set<string> {
  const [, tick] = useState(0);
  useEffect(() => {
    const l = () => tick((n) => n + 1);
    listeners.add(l);
    if (!timer) {
      void refresh();
      timer = setInterval(() => void refresh(), 30_000);
    }
    return () => {
      listeners.delete(l);
      if (listeners.size === 0 && timer) { clearInterval(timer); timer = undefined; }
    };
  }, []);
  return live;
}
