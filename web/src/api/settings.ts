import { apiClient } from './client';

/**
 * The console settings record — see com.hmdm.persistence.domain.Settings.
 * We only read/write a couple of dashboard fields, but misc settings are saved as a whole object
 * (the server's INSERT ... ON CONFLICT rewrites every misc column), so callers must round-trip the
 * full record: GET, change the field, POST it back. Unknown fields are preserved by the server.
 */
export type Settings = Record<string, unknown> & {
  dashboardRefreshSeconds?: number | null;
  offlineThresholdDays?: number | null;
};

export async function getSettings(): Promise<Settings> {
  return apiClient.get<Settings>('/private/settings');
}

/** Save the misc settings block. Pass the full record from getSettings() with the fields changed. */
export async function saveMiscSettings(settings: Settings): Promise<void> {
  await apiClient.post('/private/settings/misc', settings);
}
