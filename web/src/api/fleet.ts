import { apiClient, API_BASE } from './client';
import { bulkQueueCommand, type QueueCommandRequest } from './commands';

// Fleet organisation: groups (companies) and changes at three levels — device, group, global.
//   /rest/private/fleet/v1/...

export interface FleetGroup {
  id: number;
  name: string;
  /** Parent folder; null = top level. */
  parentId: number | null;
  /** The group's own configuration; null = it inherits (nearest ancestor with one, else global). */
  configurationId: number | null;
  configurationName: string | null;
  /** What its devices run when they do not pin their own (null = the global configuration). */
  effectiveConfigurationId: number | null;
  effectiveConfigurationName: string | null;
  /** The folder's own kiosk branding; null = inherits. */
  brand?: KioskBrand | null;
  /** Devices directly in this group (not its sub-folders). */
  deviceCount: number;
}

export interface GroupNode {
  group: FleetGroup;
  depth: number;
  /** "Colombia / Preventa / Agencia Norte" */
  path: string;
  /** Devices in this folder and every folder below it. */
  totalDevices: number;
  /** This folder and all its descendants' ids. */
  subtree: Set<number>;
}

/** The folders as a tree, depth-first with siblings by name — for tables and selects. */
export function groupTree(groups: FleetGroup[]): GroupNode[] {
  const byParent = new Map<number | null, FleetGroup[]>();
  const ids = new Set(groups.map((g) => g.id));
  for (const g of groups) {
    const parent = g.parentId != null && ids.has(g.parentId) ? g.parentId : null;
    byParent.set(parent, [...(byParent.get(parent) ?? []), g]);
  }
  for (const list of byParent.values()) list.sort((a, b) => a.name.localeCompare(b.name));
  const out: GroupNode[] = [];
  const walk = (parent: number | null, depth: number, prefix: string, seen: Set<number>): { total: number; ids: Set<number> } => {
    let total = 0;
    const all = new Set<number>();
    for (const g of byParent.get(parent) ?? []) {
      if (seen.has(g.id)) continue; // defensive: never loop on bad data
      const path = prefix ? `${prefix} / ${g.name}` : g.name;
      const node: GroupNode = { group: g, depth, path, totalDevices: 0, subtree: new Set([g.id]) };
      out.push(node);
      const below = walk(g.id, depth + 1, path, new Set([...seen, g.id]));
      node.totalDevices = g.deviceCount + below.total;
      below.ids.forEach((id) => node.subtree.add(id));
      total += node.totalDevices;
      node.subtree.forEach((id) => all.add(id));
    }
    return { total, ids: all };
  };
  walk(null, 0, '', new Set());
  return out;
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

export const createGroup = (name: string, configurationId: number | null, parentId: number | null = null) =>
  apiClient.post<FleetGroup>(`${BASE}/groups`, { name, configurationId, parentId });

export const updateGroup = (id: number, name: string, configurationId: number | null, parentId: number | null) =>
  apiClient.put<{ group: FleetGroup; devicesReconfigured: number }>(`${BASE}/groups/${id}`, { name, configurationId, parentId });

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

const plural = (n: number) => `${n} dispositivo${n === 1 ? '' : 's'}`;

export function targetLabel(t: Target): string {
  switch (t.kind) {
    case 'devices': return plural(t.ids.length);
    case 'group': return `carpeta ${t.name} (${plural(t.count)})`;
    default: return `todos los dispositivos (${t.count})`;
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

/** Kiosk branding (see common KioskBrand): logos, wallpaper, serial and a support line. */
export interface KioskBrand {
  logoUrl?: string;
  footerLogoUrl?: string;
  backgroundUrl?: string;
  showSerial?: boolean;
  supportPhone?: string;
  supportLabel?: string;
}

/** A folder's own branding; an empty object makes it inherit again (parent folder, then the policy). */
export const setGroupBrand = (id: number, brand: KioskBrand) =>
  apiClient.put<{ group: FleetGroup; devices: number }>(`${BASE}/groups/${id}/brand`, brand);

/** Install/update now, on every device of the target, the policy apps it lacks or has in an older version. */
export async function syncAppsFor(t: Target): Promise<{ devices: number; queued: number }> {
  const body = t.kind === 'devices' ? { deviceIds: t.ids } : t.kind === 'group' ? { groupIds: [t.id] } : { all: true };
  return apiClient.post<{ devices: number; queued: number }>(`${BASE}/syncApps`, body);
}

/** The Android update policy as a command (applies at once; a policy with its own setting takes over on its next apply). */
export function systemUpdateCommand(type: 'automatic' | 'postpone' | 'default') {
  return { type: 'device.systemUpdate', requiresCapability: 'device.systemUpdate', payload: JSON.stringify({ type }) };
}

/**
 * Download the devices workbook (Resumen, Dispositivos, Carpetas, Conexiones). groupId limits it to a folder and its
 * sub-folders; days is the connection history window.
 */
export async function downloadDevicesExcel(groupId?: number, days = 30): Promise<void> {
  const q = new URLSearchParams({ days: String(days), tz: Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Bogota' });
  if (groupId != null) q.set('group', String(groupId));
  const res = await fetch(`${API_BASE}${BASE}/export.xlsx?${q}`, { credentials: 'include' });
  if (!res.ok) throw new Error(`No se pudo generar el Excel (${res.status})`);
  const blob = await res.blob();
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? 'dispositivos.xlsx';
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** A device's list summary from its last report (FleetResource /devices/summary). */
export interface DeviceSummary {
  number: string;
  manufacturer?: string | null;
  model?: string | null;
  androidVersion?: string | null;
  agentVersion?: string | null;
  battery?: number | null;
  charging?: boolean | null;
  kioskActive?: boolean | null;
  powerMode?: string | null;
  networkType?: 'wifi' | 'cellular' | 'none' | null;
  /** 0 (no signal) … 4 (excellent). */
  signalLevel?: number | null;
  signalLabel?: string | null;
  wifiSsid?: string | null;
  operator?: string | null;
  freeStorageBytes?: number | null;
  totalStorageBytes?: number | null;
  stateAt?: number | null;
}

export const listDeviceSummaries = () => apiClient.get<DeviceSummary[]>(`${BASE}/devices/summary`);
