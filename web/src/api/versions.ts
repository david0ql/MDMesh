import { apiClient } from './client';

/** A library version with the name the customer gave it and the policies that use it. */
export interface LabeledVersion {
  id: number;
  version?: string | null;
  versionCode?: number | null;
  url?: string | null;
  label?: string | null;
  policies: { id: number; name: string }[];
}

export const listAppVersions = (applicationId: number) =>
  apiClient.get<LabeledVersion[]>(`/private/dc/versions/app/${applicationId}`);

/** {versionId: label} for every named version. */
export const listVersionLabels = () => apiClient.get<Record<string, string>>('/private/dc/versions/labels');

export const setVersionLabel = (versionId: number, label: string) =>
  apiClient.put<void>(`/private/dc/versions/${versionId}/label`, { label });
