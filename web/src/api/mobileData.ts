import { apiClient } from './client';

// Mobile data per app (see common MobileDataPolicy.java and the agent's MobileDataEnforcer):
//   /rest/private/agent/v1/devices/{number}/mobileData
// The policy holds the lists and the limits (megabytes); a device may carry its own override; the phone enforces
// and reports what it is doing in its state.

/** The policy's section (configurations.dcPolicy.mobileData). */
export interface MobileDataPolicy {
  mode?: 'open' | 'blocklist' | 'allowlist';
  apps?: string[];
  /** Absent = automatic (system restriction on Android 9+, the agent's local VPN before). */
  mechanism?: 'dpm' | 'vpn';
  period?: 'monthly' | 'daily';
  /** Day of the month the monthly period starts (1..28). */
  cutDay?: number;
  /** Percentage of a limit that raises the warning (80 by default). */
  warnPercent?: number;
  deviceLimitMb?: number;
  appLimits?: { pkg: string; limitMb: number }[];
  /** Apps that keep mobile data when the device limit is spent. */
  essential?: string[];
}

/** A device's own setting on top of its policy. */
export interface MobileDataOverride {
  mode?: 'policy' | 'unrestricted';
  allow?: string[];
  block?: string[];
  ignoreLimits?: boolean;
}

/** What the phone reports (telemetry.dynamic.mobileData). */
export interface MobileDataStatus {
  mode: string;
  mechanism: 'dpm' | 'vpn' | 'none' | string;
  vpnActive?: boolean | null;
  blocked?: { pkg: string; reason: 'policy' | 'appLimit' | 'deviceLimit' | string }[];
  blockedCount?: number;
  period?: string;
  periodStart?: number;
  periodEnd?: number;
  deviceBytes?: number;
  deviceLimitBytes?: number | null;
  apps?: { pkg: string; label?: string | null; bytes: number; limitBytes?: number | null }[];
  problem?: string | null;
  evaluatedAt?: number;
}

export interface MobileDataUsageRow {
  scope: 'device' | 'app';
  pkg: string | null;
  label: string;
  usedbytes: number;
  limitbytes: number | null;
  percent: number | null;
  level: 'ok' | 'warn' | 'over' | null;
  estado: string;
}

export interface DeviceMobileData {
  configurationId: number | null;
  configurationName: string | null;
  policy: MobileDataPolicy | null;
  override: MobileDataOverride | null;
  /** What the device receives (bytes); null = nothing restricted. */
  effective: {
    mode: string; apps?: string[]; mechanism?: string; period?: string; cutDay?: number; warnPercent?: number;
    deviceLimitBytes?: number; appLimits?: { pkg: string; bytes: number }[]; essential?: string[];
  } | null;
  status: MobileDataStatus | null;
  statusAt: number | null;
  agentVersion: string | null;
  period: { type: 'monthly' | 'daily'; start: string; end: string };
  /** Where the consumption comes from: the phone's own counters for the period, or the server's daily sums. */
  source: 'agent' | 'server';
  deviceBytes: number;
  rows: MobileDataUsageRow[];
  apps: { pkg: string; label?: string | null; bytes: number }[];
}

const path = (number: string) => `/private/agent/v1/devices/${encodeURIComponent(number)}/mobileData`;

export const getDeviceMobileData = (number: string, signal?: AbortSignal) =>
  apiClient.get<DeviceMobileData>(path(number), signal);

export const setDeviceMobileData = (number: string, override: MobileDataOverride) =>
  apiClient.post<DeviceMobileData>(path(number), override);

export const fmtDataBytes = (b: number | null | undefined) =>
  b == null ? '—' : b >= 1024 ** 3 ? `${(b / 1024 ** 3).toLocaleString('es-CO', { maximumFractionDigits: 2 })} GB`
    : b >= 1024 ** 2 ? `${(b / 1024 ** 2).toLocaleString('es-CO', { maximumFractionDigits: 1 })} MB`
      : b <= 0 ? '0 MB' : `${Math.max(1, Math.round(b / 1024)).toLocaleString('es-CO')} KB`;

export const MECHANISM_LABEL: Record<string, string> = {
  dpm: 'Restricción de datos del sistema (Android 9+)',
  vpn: 'VPN local del agente',
  none: 'Sin restricción activa',
};

export const REASON_LABEL: Record<string, string> = {
  policy: 'Por la lista de la política',
  appLimit: 'Superó su límite',
  deviceLimit: 'El equipo superó su límite',
};
