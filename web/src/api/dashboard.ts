import { apiClient } from './client';

/** One labelled count — mirrors the server's ChartItem (stringAttr + number). */
export interface ChartItem {
  stringAttr: string;
  number: number;
}

export interface StorageAggregate {
  freeBytes: number;
  totalBytes: number;
  devicesReporting: number;
}

/**
 * Extra fleet aggregates for the Resumen view — see the server's
 * com.hmdm.rest.resource.SummaryResource#getDashboard (GET /private/summary/dashboard).
 * These need stored agent state (device_state / device_event) that the device list does not carry.
 */
export interface DashboardSummary {
  /** Devices currently reported locked (blocked) by their agent. */
  blocked: number;
  /** Devices not seen within offlineThresholdDays. */
  offline: number;
  /** SIM add/remove/swap events across the fleet within simWindowDays. */
  simEventsRecent: number;
  simWindowDays: number;
  /** Console auto-refresh interval, seconds (resolved, with server default applied). */
  dashboardRefreshSeconds: number;
  /** Offline threshold, days (resolved, with server default applied). */
  offlineThresholdDays: number;
  storage: StorageAggregate;
  androidVersions: ChartItem[];
  appStatus: ChartItem[];
}

export async function getDashboardSummary(simDays?: number): Promise<DashboardSummary> {
  const q = simDays ? `?simDays=${simDays}` : '';
  return apiClient.get<DashboardSummary>(`/private/summary/dashboard${q}`);
}
