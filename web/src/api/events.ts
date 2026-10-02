import { apiClient } from './client';

export interface DeviceEvent {
  id: number;
  type: string;
  ts: number;
  detail?: string | null;
}

export async function getEvents(deviceId: number | string, since = 0): Promise<DeviceEvent[]> {
  return apiClient.get<DeviceEvent[]>(`/private/agent/v1/devices/${deviceId}/events?since=${since}`);
}

/** One history sample (every ~5 min while the device checks in). */
export interface MetricSample {
  ts: number;
  battery?: number | null;
  charging?: boolean | null;
  networktype?: string | null;
  wifirssi?: number | null;
  signallevel?: number | null;
  freestoragebytes?: number | null;
  freerambytes?: number | null;
  kioskactive?: boolean | null;
  locked?: boolean | null;
}

export interface DeviceHistory {
  from: number;
  gapMs: number;
  metrics: MetricSample[];
  connections: { connectedat: number; lastseenat: number }[];
}

export async function getHistory(deviceId: number | string, days: number): Promise<DeviceHistory> {
  return apiClient.get<DeviceHistory>(`/private/agent/v1/devices/${deviceId}/history?days=${days}`);
}

/** One app on one local day (see the server's device_app_usage). */
export interface AppUsageRow {
  day: string;
  pkg: string;
  label?: string | null;
  foregroundms: number;
  wifibytes: number;
  mobilebytes: number;
  batterypct: number;
  updatedat?: number;
}

export async function getAppUsage(deviceId: number | string, days: number): Promise<AppUsageRow[]> {
  return apiClient.get<AppUsageRow[]>(`/private/agent/v1/devices/${deviceId}/usage?days=${days}`);
}
