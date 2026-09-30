import { apiClient } from './client';

export interface AppGroupApp { id: number; name?: string; pkg?: string; version?: string; installable?: boolean }
export interface AppGroup { id: number; name: string; description?: string | null; apps: AppGroupApp[]; policies: number }

const BASE = '/private/app-groups';

export const listAppGroups = () => apiClient.get<AppGroup[]>(BASE);
export const createAppGroup = (b: { name: string; description?: string; appIds: number[] }) => apiClient.post<{ id: number }>(BASE, b);
export const updateAppGroup = (id: number, b: { name: string; description?: string; appIds: number[] }) =>
  apiClient.put<{ policies: number; queued: number }>(`${BASE}/${id}`, b);
export const deleteAppGroup = (id: number) => apiClient.del<void>(`${BASE}/${id}`);
