import { apiClient } from './client';
import { queueCommand, listCommandHistory, bulkQueueCommand, type QueueCommandRequest, type BulkCommandResult } from './commands';

// Device app inventory for the kiosk picker. The agent answers `apps.scan` / `apps.icons`
// commands and returns the data as JSON in the command-result `detail`; we read it back from
// the command-history endpoint we already poll — so there is no dedicated server API.

export interface AppInfo {
  pkg: string;
  label: string;
  system?: boolean;
  launchable?: boolean;
  versionName?: string;
  versionCode?: number;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Queue a command and poll command history until it completes, returning its result `detail`. */
async function runForResult(
  deviceId: number | string,
  req: QueueCommandRequest,
  timeoutMs = 75000,
  signal?: AbortSignal,
): Promise<string> {
  const queued = await queueCommand(deviceId, req, signal);
  const id = queued?.id;
  if (id == null) throw new Error('La orden no quedó en cola');
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (signal?.aborted) throw new Error('Cancelado');
    await sleep(1500);
    if (signal?.aborted) throw new Error('Cancelado');
    const hist = await listCommandHistory(deviceId, 0, signal).catch(() => []);
    const cmd = hist.find((c) => String(c.id) === String(id));
    if (!cmd) continue;
    if (cmd.status === 'done') return cmd.detail ?? '';
    if (cmd.status === 'failed' || cmd.status === 'unsupported' || cmd.status === 'expired') {
      throw new Error(cmd.detail || `El dispositivo respondió ${cmd.status}`);
    }
  }
  throw new Error('Se agotó el tiempo esperando al dispositivo (¿está en línea?)');
}

/** Scan the device for installed packages (metadata only — no icons). */
export async function scanApps(deviceId: number | string, signal?: AbortSignal): Promise<AppInfo[]> {
  const detail = await runForResult(deviceId, { type: 'apps.scan' }, 75000, signal);
  const parsed = JSON.parse(detail || '{}') as { apps?: AppInfo[] };
  return parsed.apps ?? [];
}

export interface ScanSnapshot {
  apps: AppInfo[];
  scannedAt?: number;
}

/**
 * The device's most recent saved scan, read from command history (every apps.scan result is
 * durably stored per device in the command's detail). Returns null if the device was never scanned,
 * so the picker can show the saved list instantly instead of re-scanning every time.
 */
export async function getLatestScan(deviceId: number | string): Promise<ScanSnapshot | null> {
  const hist = await listCommandHistory(deviceId).catch(() => []); // newest-first (id DESC)
  const scan = hist.find((c) => c.type === 'apps.scan' && c.status === 'done' && !!c.detail);
  if (!scan?.detail) return null;
  try {
    const apps = (JSON.parse(scan.detail).apps ?? []) as AppInfo[];
    return apps.length ? { apps, scannedAt: scan.completedAt } : null;
  } catch {
    return null;
  }
}

function chunk<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

/** Overall cap across all icon batches — on top of the per-command timeout. */
const ICONS_DEADLINE_MS = 3 * 60 * 1000;

/** Fetch base64-PNG icons for the given packages, in batches. Returns a pkg→dataURL map. */
export async function fetchIcons(
  deviceId: number | string,
  packages: string[],
  onBatch?: (icons: Record<string, string>) => void,
  signal?: AbortSignal,
): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  const deadline = Date.now() + ICONS_DEADLINE_MS;
  for (const batch of chunk(packages, 24)) {
    const left = deadline - Date.now();
    if (left <= 0) throw new Error('Los íconos tardaron demasiado — inténtalo de nuevo');
    const detail = await runForResult(deviceId, {
      type: 'apps.icons',
      payload: JSON.stringify({ packages: batch }),
    }, Math.min(75000, left), signal);
    const parsed = JSON.parse(detail || '{}') as { icons?: Array<{ pkg: string; pngBase64: string }> };
    const got: Record<string, string> = {};
    for (const ic of parsed.icons ?? []) got[ic.pkg] = `data:image/png;base64,${ic.pngBase64}`;
    Object.assign(out, got);
    onBatch?.(got);
  }
  return out;
}

// --- Fleet-level app targeting (which devices have an app, and mass-uninstall) ----------------------

/** One device that reports a given package as installed (server: GET /private/agent/v1/apps/devices?pkg=). */
export interface DeviceWithApp {
  /** Numeric device id — feed straight into the bulk command endpoint. */
  id: number;
  number: string;
  description?: string | null;
  configurationId?: number | null;
  /** The version of the app installed on this device. */
  appVersion?: string | null;
  appName?: string | null;
}

/**
 * Every device of the customer whose last-reported inventory contains the package. Folder administrators only get
 * their own folders' devices (enforced server-side).
 */
export async function listDevicesWithApp(packageName: string): Promise<DeviceWithApp[]> {
  return apiClient.get<DeviceWithApp[]>(
    `/private/agent/v1/apps/devices?pkg=${encodeURIComponent(packageName)}`,
  );
}

/**
 * Queue a silent uninstall of a package on many devices at once, by numeric device id. Reuses the bulk command
 * endpoint (gated server-side on edit_devices); each device must advertise `app.silentInstall`. Fire-and-forget.
 */
export async function bulkUninstallApp(
  deviceIds: number[],
  packageName: string,
  signal?: AbortSignal,
): Promise<BulkCommandResult> {
  return bulkQueueCommand(
    deviceIds,
    { type: 'app.uninstall', requiresCapability: 'app.silentInstall', payload: JSON.stringify({ packageName }) },
    signal,
  );
}

/**
 * Silently uninstall a package (Device Owner) and wait for the device's answer. Gated like install on
 * `app.silentInstall`, so the server never queues it for a device that can't do it.
 */
export async function uninstallApp(
  deviceId: number | string,
  packageName: string,
  signal?: AbortSignal,
): Promise<void> {
  await runForResult(deviceId, {
    type: 'app.uninstall',
    requiresCapability: 'app.silentInstall',
    payload: JSON.stringify({ packageName }),
  }, 120000, signal);
}

// --- Per-device kiosk apps: show an installed app in THIS device's kiosk (no per-device policy) ---
export const listDeviceKioskApps = (deviceId: number | string) =>
  apiClient.get<string[]>(`/private/agent/v1/devices/${deviceId}/kioskApps`);

export const addDeviceKioskApp = (deviceId: number | string, packageName: string) =>
  apiClient.post<string[]>(`/private/agent/v1/devices/${deviceId}/kioskApps`, { packageName });

export const removeDeviceKioskApp = (deviceId: number | string, packageName: string) =>
  apiClient.del<string[]>(`/private/agent/v1/devices/${deviceId}/kioskApps/${encodeURIComponent(packageName)}`);

// --- Per-device "share data" (tethering) override: wins over the policy's setting ---
/** The stored override, or null when the device follows its policy. */
export type DataSharingState = 'allow' | 'block' | null;

export const getDataSharing = (deviceId: number | string) =>
  apiClient.get<{ state: DataSharingState }>(`/private/agent/v1/devices/${deviceId}/dataSharing`);

/** Set the override: 'allow' / 'block', or 'policy' to drop it and follow the policy again. */
export const setDataSharing = (deviceId: number | string, state: 'allow' | 'block' | 'policy') =>
  apiClient.post<{ state: DataSharingState }>(`/private/agent/v1/devices/${deviceId}/dataSharing`, { state });
