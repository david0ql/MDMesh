import { apiClient } from './client';

// Saved device scripts (admin-authored, reusable). Server: com.hmdm.rest.resource.ScriptsResource
//   GET    /rest/private/scripts                list (any user; authoring is admin-only)
//   PUT    /rest/private/scripts               create/update (admin)   body: SavedScriptInput
//   DELETE /rest/private/scripts/{id}          delete (admin)
//   POST   /rest/private/scripts/{id}/run      run on devices   body: { deviceIds, params }
//
// A script's body is a short list of action verbs, one per line (same grammar as the ad-hoc
// ScriptPanel) plus `cleardata <paquete>` which borra los datos de una app
// (device.storageClean {clearData:[pkg]}). Arguments may embed {{param}} placeholders filled at run time.

/** An editable parameter declared by a script (e.g. the target package). */
export interface ScriptParam {
  key: string;
  label: string;
  placeholder?: string;
  default?: string;
}

export interface SavedScript {
  id: number;
  customerId: number;
  name: string;
  description?: string | null;
  body: string;
  paramsJson?: string | null;
  quickAction: boolean;
  createdBy?: number | null;
  createdAt?: number | null;
  updatedAt?: number | null;
}

export interface SavedScriptInput {
  id?: number;
  name: string;
  description?: string;
  body: string;
  paramsJson?: string;
  quickAction: boolean;
}

export interface RunScriptResult {
  devices: number;
  queued: number;
  skipped: number[];
  steps: number;
}

/** Parse a script's declared parameters (tolerant: bad JSON yields none). */
export function scriptParams(s: SavedScript): ScriptParam[] {
  if (!s.paramsJson) return [];
  try {
    const v = JSON.parse(s.paramsJson);
    if (!Array.isArray(v)) return [];
    return v.filter((p) => p && typeof p.key === 'string' && typeof p.label === 'string');
  } catch {
    return [];
  }
}

export async function listScripts(signal?: AbortSignal): Promise<SavedScript[]> {
  return apiClient.get<SavedScript[]>('/private/scripts', signal);
}

export async function saveScript(input: SavedScriptInput): Promise<SavedScript> {
  return apiClient.put<SavedScript>('/private/scripts', input);
}

export async function deleteScript(id: number): Promise<void> {
  await apiClient.del(`/private/scripts/${id}`);
}

export async function runScript(
  id: number,
  deviceIds: number[],
  params: Record<string, string> = {},
): Promise<RunScriptResult> {
  return apiClient.post<RunScriptResult>(`/private/scripts/${id}/run`, { deviceIds, params });
}
