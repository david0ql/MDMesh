import { apiClient } from './client';

// Mint an enrollment token (see server agent v1 contract):
//   POST /rest/private/agent/v1/token  ->  { token, ... }
// The token is embedded into the device's QR provisioning bundle. The exact
// shape is not strongly typed server-side here, so we read defensively.

export interface EnrollTokenResponse {
  token?: string;
  expiresAt?: number;
  [key: string]: unknown;
}

/**
 * groupId puts the enrolled device in that group (company); it then runs the group's configuration, or the
 * global one. configurationId would pin a configuration on the device instead (device level wins over both).
 */
export async function mintEnrollToken(opts: { groupId?: number; configurationId?: number } = {}): Promise<EnrollTokenResponse> {
  return apiClient.post<EnrollTokenResponse>('/private/agent/v1/token', {
    ...(opts.groupId ? { groupId: opts.groupId } : {}),
    ...(opts.configurationId ? { configurationId: opts.configurationId } : {}),
  });
}

/** A folder's reusable enrollment code (see AgentAdminResource /codes). Shown as ABCD-EFGH. */
export interface EnrollmentCode {
  id: number;
  code: string;
  label: string | null;
  groupId: number | null;
  groupName: string | null;
  uses: number;
  revoked: boolean;
  createdAt: number;
  expiresAt: number | null;
}

export const displayCode = (c: string) => (c.length === 8 ? `${c.slice(0, 4)}-${c.slice(4)}` : c);

export const listEnrollmentCodes = () => apiClient.get<EnrollmentCode[]>('/private/agent/v1/codes');

export const createEnrollmentCode = (groupId: number, label?: string, expiresAt?: number) =>
  apiClient.post<EnrollmentCode>('/private/agent/v1/codes', {
    groupId,
    ...(label ? { label } : {}),
    ...(expiresAt ? { expiresAt } : {}),
  });

export const revokeEnrollmentCode = (id: number) => apiClient.del<void>(`/private/agent/v1/codes/${id}`);
