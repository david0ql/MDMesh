import { apiClient } from './client';
import type { HardwareValue } from '../data/hardwareRules';

/** One row of a device's "Restricciones aplicadas" (server: HardwarePolicy.Item). */
export interface HardwareItem {
  key: string;
  kind: 'rule' | 'toggle' | 'device' | 'restriction';
  minSdk: number;
  policy?: HardwareValue | null;
  override?: HardwareValue | null;
  desired?: HardwareValue | null;
  /** applied | unsupported: why | failed: why | pending | absent (nothing asked). */
  outcome?: string | null;
}

export interface HardwareStatus {
  items: HardwareItem[];
  restrictions: HardwareItem[];
  unknownRestrictions: string[];
  ownOptionRestrictions: string[];
  /** The device already reported on the current policy document. */
  reported: boolean;
  reportedAt?: number | null;
}

export const getDeviceHardware = (deviceNumber: string) =>
  apiClient.get<HardwareStatus>(`/private/agent/v1/devices/${encodeURIComponent(deviceNumber)}/hardware`);

/** Change this device's override for some rules: 'policy' removes the override of that rule. */
export const setDeviceHardware = (deviceNumber: string, rules: Record<string, HardwareValue | 'policy'>) =>
  apiClient.put<HardwareStatus>(`/private/agent/v1/devices/${encodeURIComponent(deviceNumber)}/hardware`, { rules });
