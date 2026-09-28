import { apiClient } from './client';

// Fleet map: where every device was in a time range.
//   GET /rest/private/agent/v1/locations?from=<ms>&to=<ms>   (at most 31 days)

export interface FleetFix {
  lat: number;
  lon: number;
  accuracy?: number | null;
  provider?: string | null;
  capturedAt: number;
}

export interface FleetDevice {
  number: string;
  description?: string | null;
  /** Oldest first. */
  fixes: FleetFix[];
}

export interface FleetLocations {
  from: number;
  to: number;
  /** The range held more fixes than one response carries; narrow it. */
  truncated: boolean;
  devices: FleetDevice[];
}

export async function listFleetLocations(from: number, to: number, signal?: AbortSignal): Promise<FleetLocations> {
  return apiClient.get<FleetLocations>(`/private/agent/v1/locations?from=${from}&to=${to}`, signal);
}
