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

/**
 * On-device user-activity event types (what the user did ON the phone, as opposed to what the console sent TO it).
 * Only what a Device Owner can actually observe via UsageStats: which app came to the foreground and when.
 */
export const ACTIVITY_TYPES = ['appOpened', 'cameraOpened'] as const;

/** The "activity on the device" slice of the event timeline: app/camera openings, newest first. */
export async function getActivity(deviceId: number | string, since = 0): Promise<DeviceEvent[]> {
  const types = ACTIVITY_TYPES.join(',');
  return apiClient.get<DeviceEvent[]>(
    `/private/agent/v1/devices/${deviceId}/events?since=${since}&types=${encodeURIComponent(types)}`,
  );
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
