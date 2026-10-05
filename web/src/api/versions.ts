import { apiClient } from './client';

/** A library version with the name the customer gave it and the policies that use it. */
export interface LabeledVersion {
  id: number;
  version?: string | null;
  versionCode?: number | null;
  url?: string | null;
  /** Split-bundle parts (raw JSON), for bundle versions. */
  parts?: string | null;
  label?: string | null;
  policies: { id: number; name: string }[];
}

export const listAppVersions = (applicationId: number) =>
  apiClient.get<LabeledVersion[]>(`/private/dc/versions/app/${applicationId}`);

/** {versionId: label} for every named version. */
export const listVersionLabels = () => apiClient.get<Record<string, string>>('/private/dc/versions/labels');

export const setVersionLabel = (versionId: number, label: string) =>
  apiClient.put<void>(`/private/dc/versions/${versionId}/label`, { label });

/** Apps a policy may take back to an older version (the phone reinstalls them; its data in that app is lost). */
export const listPolicyDowngrades = (configurationId: number) =>
  apiClient.get<number[]>(`/private/dc/versions/policy/${configurationId}/downgrades`);

export const setPolicyDowngrade = (configurationId: number, applicationId: number, allow: boolean) =>
  apiClient.put<void>(`/private/dc/versions/policy/${configurationId}/downgrade/${applicationId}`, { allow });

/** The highest version code among an app's library versions. */
export const topCode = (list: LabeledVersion[]) => list.reduce((m, v) => Math.max(m, v.versionCode ?? 0), 0);

/** A group of builds: an app with several versions (or named ones); each policy uses one of its builds. */
export interface VersionGroup { applicationId: number; members: LabeledVersion[] }

export const listVersionGroups = () => apiClient.get<VersionGroup[]>('/private/dc/versions/groups');

/**
 * Make exactly [configurationIds] use this build (policies that used it and are left out drop the app). Phones with
 * another build of the group get this one; a newer one than this is reinstalled.
 */
export const assignVersion = (versionId: number, configurationIds: number[]) =>
  apiClient.put<{ assigned: number; removed: number; queued: number }>(`/private/dc/versions/${versionId}/policies`, { configurationIds });

/** Remove a build from the Library (and its APK file). */
export const deleteVersion = (versionId: number) => apiClient.del<void>(`/private/applications/versions/${versionId}`);
