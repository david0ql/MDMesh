import { apiClient } from './client';

export interface AppGroupApp { id: number; name?: string; pkg?: string; version?: string; installable?: boolean }
/** Where an app group is used; kiosk = its apps also show in the kiosk there. */
export interface AppGroupRef { id: number; kiosk?: boolean; name?: string }
export interface AppGroup {
  id: number; name: string; description?: string | null; apps: AppGroupApp[];
  /** How many policies use it. */
  policies: number;
  policyRefs?: AppGroupRef[];
  /** Device folders (with their sub-folders) it is assigned to directly. */
  folders?: AppGroupRef[];
}
export interface AppGroupBody { name: string; description?: string; appIds: number[]; folders?: AppGroupRef[]; policies?: AppGroupRef[] }

const BASE = '/private/app-groups';

export const listAppGroups = () => apiClient.get<AppGroup[]>(BASE);
export interface AppGroupSaved { id?: number; policies: number; folders: number; queued: number }
export const createAppGroup = (b: AppGroupBody) => apiClient.post<AppGroupSaved>(BASE, b);
export const updateAppGroup = (id: number, b: AppGroupBody) => apiClient.put<AppGroupSaved>(`${BASE}/${id}`, b);
export const deleteAppGroup = (id: number) => apiClient.del<void>(`${BASE}/${id}`);
