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
