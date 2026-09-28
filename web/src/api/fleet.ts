import { apiClient } from './client';
import { bulkQueueCommand, type QueueCommandRequest } from './commands';

// Fleet organisation: groups (companies) and changes at three levels — device, group, global.
//   /rest/private/fleet/v1/...

export interface FleetGroup {
  id: number;
  name: string;
  /** null: the group's devices use the global configuration. */
  configurationId: number | null;
  configurationName: string | null;
  deviceCount: number;
}

export interface GlobalConfig {
  configurationId: number | null;
  configurationName: string | null;
}

export interface GroupsOverview {
  groups: FleetGroup[];
  ungroupedDevices: number;
  global: GlobalConfig;
}

export type ScopeSource = 'device' | 'group' | 'global';

export interface DeviceScope {
  deviceId: number;
  groupId: number | null;
  groupName: string | null;
  configurationId: number | null;
  configurationName: string | null;
  pinned: boolean;
  source: ScopeSource;
  groupConfigurationId: number | null;
  groupConfigurationName: string | null;
  globalConfigurationId: number | null;
  globalConfigurationName: string | null;
}

const BASE = '/private/fleet/v1';

export const listGroups = () => apiClient.get<GroupsOverview>(`${BASE}/groups`);

export const createGroup = (name: string, configurationId: number | null) =>
  apiClient.post<FleetGroup>(`${BASE}/groups`, { name, configurationId });

export const updateGroup = (id: number, name: string, configurationId: number | null) =>
  apiClient.put<{ group: FleetGroup; devicesReconfigured: number }>(`${BASE}/groups/${id}`, { name, configurationId });

export const deleteGroup = (id: number) => apiClient.del<void>(`${BASE}/groups/${id}`);

/** Put devices (numeric ids) in a group, or in none with null. */
export const moveDevicesToGroup = (deviceIds: number[], groupId: number | null) =>
  apiClient.post<{ moved: number; skipped: number[]; devicesReconfigured: number }>(
    `${BASE}/devices/group`, { deviceIds, groupId });

/** Pin a configuration on devices, or null to let them inherit their group's / the global one again. */
export const setDevicesConfiguration = (deviceIds: number[], configurationId: number | null) =>
  apiClient.put<{ updated: number; devicesReconfigured: number }>(
    `${BASE}/devices/configuration`, { deviceIds, configurationId });

export const getDeviceScope = (deviceId: number) => apiClient.get<DeviceScope>(`${BASE}/devices/${deviceId}/scope`);

export const setGlobalConfiguration = (configurationId: number) =>
  apiClient.put<GlobalConfig & { devicesReconfigured: number }>(`${BASE}/global`, { configurationId });

/** Who an action runs on: some devices, a whole group, or every device. */
export type Target =
  | { kind: 'devices'; ids: number[] }
  | { kind: 'group'; id: number; name: string; count: number }
  | { kind: 'all'; count: number };

const plural = (n: number) => `${n} device${n === 1 ? '' : 's'}`;

export function targetLabel(t: Target): string {
  switch (t.kind) {
    case 'devices': return plural(t.ids.length);
    case 'group': return `group ${t.name} (${plural(t.count)})`;
    default: return `all devices (${t.count})`;
  }
}

/** Queue one command for the target. Destructive commands are refused for groups and all devices. */
export async function queueForTarget(t: Target, req: QueueCommandRequest): Promise<{ queued: number; skipped: number }> {
  if (t.kind === 'devices') {
    const r = await bulkQueueCommand(t.ids, req);
    return { queued: r.queued, skipped: r.skipped?.length ?? 0 };
  }
  const path = t.kind === 'group' ? `${BASE}/groups/${t.id}/commands` : `${BASE}/global/commands`;
  const r = await apiClient.post<{ queued: number }>(path, { command: req });
  return { queued: r.queued, skipped: 0 };
}
