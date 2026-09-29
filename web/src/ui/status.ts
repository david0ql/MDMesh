// Maps the server's device statusCode colour (green/red/yellow/brown/grey)
// onto our instrument status tones (ok/warn/alert/idle) + a human label.
// Brand amber is never used for status — only ok/warn/alert/idle.

export type StatusTone = 'ok' | 'warn' | 'alert' | 'idle';

interface StatusMeta {
  tone: StatusTone;
  label: string;
}

const MAP: Record<string, StatusMeta> = {
  green: { tone: 'ok', label: 'En línea' },
  yellow: { tone: 'warn', label: 'Inactivo' },
  brown: { tone: 'warn', label: 'Desactualizado' },
  red: { tone: 'alert', label: 'Sin conexión' },
  grey: { tone: 'idle', label: 'Desconocido' },
};

export function statusMeta(code?: string): StatusMeta {
  return MAP[code ?? 'grey'] ?? { tone: 'idle', label: code ?? 'Desconocido' };
}

/** Online if the last report is within this window (ms): 20 min, the server's connection gap. A locked phone in battery-saving mode reports about every 10-15 min (Android Doze), so a shorter window showed it offline while it was on. */
export const ONLINE_WINDOW_MS = 20 * 60 * 1000;

export function isOnline(lastUpdate?: number, now: number = Date.now()): boolean {
  if (!lastUpdate) return false;
  return now - lastUpdate <= ONLINE_WINDOW_MS;
}
