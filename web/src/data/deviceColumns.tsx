import type { ReactNode } from 'react';
import type { DeviceView } from '../api/devices';
import type { DeviceSummary } from '../api/fleet';
import { fmtRelative, orDash } from '../ui/format';

/** Extra columns of the device list (the name column is always there). Chosen in Ajustes, saved per browser. */
export interface DeviceColumn {
  key: string;
  label: string;
  /** Grid track, e.g. '110px'. */
  width: string;
  render: (d: DeviceView, s: DeviceSummary | undefined, ctx: { online: boolean; policy: string; group: string }) => ReactNode;
}

const gb = (n?: number | null) => (n == null ? '—' : `${(n / 1024 ** 3).toLocaleString('es-CO', { maximumFractionDigits: 1 })} GB`);

/** Signal bars (0-4) + label; the network type as an icon. */
export function NetworkCell({ s }: { s?: DeviceSummary }) {
  if (!s?.networkType || s.networkType === 'none') return <span className="net net-none" title="Sin red">Sin red</span>;
  const lvl = s.signalLevel ?? 0;
  const bars = [1, 2, 3, 4].map((i) => (
    <i key={i} className={i <= lvl ? 'on' : ''} style={{ height: 3 + i * 3 }} />
  ));
  const kind = s.networkType === 'wifi' ? 'Wi‑Fi' : 'Datos';
  const title = `${kind}${s.networkType === 'wifi' && s.wifiSsid ? ` (${s.wifiSsid})` : ''}${s.operator && s.networkType !== 'wifi' ? ` · ${s.operator}` : ''} · señal ${s.signalLabel ?? 'desconocida'}`;
  return (
    <span className={`net q${lvl}`} title={title}>
      <span className="net-bars" aria-hidden="true">{bars}</span>
      <span className="net-kind">{kind}</span>
      <span className="net-q">{s.signalLabel ?? ''}</span>
    </span>
  );
}

/** On/off icon: a lit power symbol when the device reported in the last 10 minutes. */
export function PowerIcon({ online }: { online: boolean }) {
  return (
    <span className={`pwr ${online ? 'on' : 'off'}`} title={online ? 'En línea' : 'Sin conexión'} aria-label={online ? 'En línea' : 'Sin conexión'}>
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
        <path d="M12 3v8" />
        <path d="M6.3 6.8a8 8 0 1 0 11.4 0" />
      </svg>
      <span className="pwr-t">{online ? 'En línea' : 'Apagado'}</span>
    </span>
  );
}

export const DEVICE_COLUMNS: DeviceColumn[] = [
  { key: 'status', label: 'Estado', width: '104px', render: (_d, _s, c) => <PowerIcon online={c.online} /> },
  { key: 'network', label: 'Red', width: '150px', render: (_d, s) => <NetworkCell s={s} /> },
  { key: 'model', label: 'Modelo', width: '130px', render: (_d, s) => orDash(s?.model ?? undefined) },
  { key: 'android', label: 'Android', width: '72px', render: (d, s) => orDash(s?.androidVersion ?? d.androidVersion) },
  { key: 'agent', label: 'Agente', width: '78px', render: (d, s) => orDash(s?.agentVersion ?? d.launcherVersion) },
  { key: 'battery', label: 'Batería', width: '80px', render: (_d, s) => (s?.battery == null || s.battery < 0 ? '—' : `${s.battery}%${s.charging ? ' ⚡' : ''}`) },
  { key: 'storage', label: 'Libre', width: '92px', render: (_d, s) => gb(s?.freeStorageBytes) },
  { key: 'operator', label: 'Operador', width: '110px', render: (_d, s) => orDash(s?.operator ?? undefined) },
  { key: 'policy', label: 'Política', width: '140px', render: (_d, _s, c) => c.policy },
  { key: 'group', label: 'Carpeta', width: '130px', render: (_d, _s, c) => c.group },
  { key: 'kiosk', label: 'Quiosco', width: '76px', render: (d, s) => ((s?.kioskActive ?? d.kioskMode) ? 'Sí' : 'No') },
  { key: 'serial', label: 'Serie', width: '130px', render: (d) => orDash(d.serial) },
  { key: 'imei', label: 'IMEI', width: '150px', render: (d) => orDash(d.imei) },
  { key: 'lastSeen', label: 'Última conexión', width: '110px', render: (d) => fmtRelative(d.lastUpdate) },
];

export const DEFAULT_DEVICE_COLUMNS = ['status', 'network', 'model', 'android', 'battery', 'policy', 'group', 'lastSeen'];
const KEY = 'dc.devices.columns';

/** How many extra columns fit next to the name at this width (sidebar and name column taken off). */
export function maxDeviceColumns(width = typeof window === 'undefined' ? 1440 : window.innerWidth): number {
  return Math.max(3, Math.min(DEVICE_COLUMNS.length, Math.floor((width - 240 - 300) / 118)));
}

export function loadDeviceColumns(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    if (Array.isArray(v)) return v.filter((k) => DEVICE_COLUMNS.some((c) => c.key === k));
  } catch { /* storage blocked */ }
  return DEFAULT_DEVICE_COLUMNS;
}

export function saveDeviceColumns(keys: string[]) {
  try { localStorage.setItem(KEY, JSON.stringify(keys)); } catch { /* storage blocked */ }
  window.dispatchEvent(new Event('dc-columns'));
}
