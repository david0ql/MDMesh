import { apiClient } from './client';
import { queueCommand } from './commands';
import type { PermissionRule, PermissionRulesByApp } from '../data/permissions';

// App permissions of one device:
//   GET /rest/private/agent/v1/devices/{number}/permissions
//   PUT /rest/private/agent/v1/devices/{number}/permissions   body: { apps: { <package>: rules } }
// The agent enforces them with config.apply and reports the real state with its check-ins.

export interface DesiredAppPermissions {
  packageName: string;
  all?: PermissionRule;
  permissions?: Record<string, PermissionRule>;
  special?: string[];
}

export interface DesiredPermissions {
  defaultPolicy?: string;
  apps?: DesiredAppPermissions[];
}

export interface PermissionState {
  group: string;
  name: string;
  granted: boolean;
  /** granted / denied = fixed by the policy (the user cannot change it); none = the user's or the default's answer. */
  fixed?: 'none' | 'granted' | 'denied';
  wanted?: PermissionRule | null;
  note?: string | null;
}

export interface SpecialAccessState {
  key: string;
  state: 'allowed' | 'needsUser' | 'notDeclared' | 'unknown';
  wanted?: boolean;
  managed?: string | null;
}

export interface AppPermissionState {
  packageName: string;
  installed: boolean;
  label?: string | null;
  targetSdk?: number | null;
  permissions?: PermissionState[];
  special?: SpecialAccessState[];
}

export interface PermissionReport {
  /** Effective default policy on the phone: grant / prompt / deny / unknown. */
  policy: string;
  deviceOwner: boolean;
  sdkInt: number;
  /** Android 12+: may the administrator grant sensor permissions on this phone? null on older versions. */
  canGrantSensors?: boolean | null;
  lastOutcome?: string | null;
  apps?: AppPermissionState[];
  at?: number;
}

export interface DevicePermissions {
  /** The agent enforces and reports permissions (it advertises device.permissionScan). */
  supported: boolean;
  policy: DesiredPermissions | null;
  effective: DesiredPermissions | null;
  override: PermissionRulesByApp;
  report: PermissionReport | null;
  reportAt: number | null;
}

export const getDevicePermissions = (deviceNumber: string, signal?: AbortSignal) =>
  apiClient.get<DevicePermissions>(`/private/agent/v1/devices/${encodeURIComponent(deviceNumber)}/permissions`, signal);

export const saveDevicePermissions = (deviceNumber: string, apps: PermissionRulesByApp) =>
  apiClient.put<DevicePermissions>(`/private/agent/v1/devices/${encodeURIComponent(deviceNumber)}/permissions`, { apps });

/** Enforce the rules again on the phone and refresh its report. */
export const scanDevicePermissions = (deviceNumber: string) =>
  queueCommand(deviceNumber, { type: 'device.permissionScan', requiresCapability: 'device.permissionScan' });

/** Open on the phone the Android screen where the person turns on a special access for an app. */
export const openPermissionScreen = (deviceNumber: string, packageName: string, access: string) =>
  queueCommand(deviceNumber, {
    type: 'device.permissionScreen',
    requiresCapability: 'device.permissionScreen',
    payload: JSON.stringify({ packageName, access }),
  });
