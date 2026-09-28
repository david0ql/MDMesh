import { apiClient } from './client';
import { queueCommand, type QueuedCommand } from './commands';

// Remote view/control (ADR 0010): droidVNC-NG on the device, a Mode-II repeater + noVNC on the server.
//   GET  /rest/private/agent/v1/devices/{id}/remote          what the device offers
//   POST /rest/private/agent/v1/devices/{id}/remote/start    { viewOnly } → session id + VNC password

export interface RemoteStatus {
  /** 'none' | 'view' | 'control' */
  tier: string;
  transports: string[];
  /** The agent can tunnel the session over the server's HTTPS origin (encrypted end to end). */
  encrypted: boolean;
  /** 'adaptive' (battery-saver) | 'alwaysOn' | null when never reported. */
  powerMode?: string | null;
  locked?: boolean | null;
  lastSeen?: number | null;
}

export interface RemoteSession {
  commandId: number | string;
  sessionId: string;
  password: string;
  viewOnly: boolean;
  encrypted: boolean;
}

export async function getRemoteStatus(deviceId: string): Promise<RemoteStatus> {
  return apiClient.get<RemoteStatus>(`/private/agent/v1/devices/${deviceId}/remote`);
}

export async function startRemoteSession(deviceId: string, viewOnly: boolean): Promise<RemoteSession> {
  return apiClient.post<RemoteSession>(`/private/agent/v1/devices/${deviceId}/remote/start`, { viewOnly });
}

export async function stopRemoteSession(deviceId: string): Promise<QueuedCommand> {
  return queueCommand(deviceId, { type: 'remote.vnc.stop' });
}

/**
 * The noVNC viewer link. Everything rides in the URL fragment, which the browser never sends to the
 * server, so the session password stays out of proxy and access logs. The viewer is behind the console
 * session (Caddy forward_auth), so it opens only in a browser signed in to this console.
 */
export function viewerUrl(s: Pick<RemoteSession, 'sessionId' | 'password'>, origin = window.location.origin): string {
  const hash = new URLSearchParams({
    path: 'remote/vnc/websockify',
    repeaterID: s.sessionId,
    password: s.password,
    autoconnect: 'true',
    resize: 'scale',
    reconnect: 'false',
    show_dot: 'true',
  });
  return `${origin}/remote/vnc/vnc.html#${hash.toString()}`;
}
