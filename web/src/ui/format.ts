// Small display helpers. Render an em dash for absent values.

export const DASH = '—';

export function fmtDateTime(ms?: number): string {
  if (!ms) return DASH;
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return DASH;
  return d.toLocaleString('es-CO', {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Compact relative "last seen" — e.g. "hace 3 min", "hace 2 h", "hace 5 d". */
export function fmtRelative(ms?: number, now: number = Date.now()): string {
  if (!ms) return DASH;
  const diff = now - ms;
  if (diff < 0) return 'justo ahora';
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return 'justo ahora';
  const min = Math.floor(sec / 60);
  if (min < 60) return `hace ${min} min`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `hace ${hr} h`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `hace ${day} d`;
  const mo = Math.floor(day / 30);
  if (mo < 12) return `hace ${mo} mes${mo === 1 ? '' : 'es'}`;
  const yr = Math.floor(mo / 12);
  return `hace ${yr} año${yr === 1 ? '' : 's'}`;
}

export function orDash(v?: string | number | null): string {
  if (v === undefined || v === null || v === '') return DASH;
  return String(v);
}
