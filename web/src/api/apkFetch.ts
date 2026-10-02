import { apiClient } from './client';
import { addApplicationVersion, type Application } from './applications';

export interface FetchedBundle {
  packageName: string;
  version?: string;
  versionCode: number;
  parts: { url: string; sha256: string; name: string; split?: string }[];
  signerSha256?: string;
  signerSubject?: string;
  /** Set when the signing certificate is a known publisher's (Google, WhatsApp…). */
  publisher?: string | null;
}

interface FetchStatus { state: 'running' | 'done' | 'error'; bytes: number; total: number; result?: FetchedBundle | null; error?: string | null }

/** True when the library has an installable file for the app (a hosted APK or bundle), not just a store listing. */
export function hasApk(a: Application): boolean {
  return !!a.parts || (!!a.url && /^https?:\/\//.test(a.url));
}

/**
 * Get the app's public APK onto this server and make it the app's new library version: every policy that has the
 * app then installs it on its phones by itself. [onProgress] gets 0..1 while downloading.
 */
export async function obtainApk(app: Application, onProgress?: (fraction: number | null) => void): Promise<FetchedBundle> {
  const { job } = await apiClient.post<{ job: string }>('/private/web-ui-files/fetch', { packageName: app.pkg });
  const until = Date.now() + 25 * 60_000;
  let st: FetchStatus;
  for (;;) {
    await new Promise((r) => setTimeout(r, 2000));
    st = await apiClient.get<FetchStatus>(`/private/web-ui-files/fetch/${job}`);
    if (st.state !== 'running') break;
    onProgress?.(st.total > 0 ? st.bytes / st.total : null);
    if (Date.now() > until) throw new Error('La descarga tardó demasiado.');
  }
  if (st.state === 'error' || !st.result) throw new Error(st.error || 'No se pudo descargar.');
  const b = st.result;
  const base = b.parts.find((p) => !p.split || !/(^|\.)config\./.test(p.split)) ?? b.parts[0];
  await addApplicationVersion({
    applicationId: app.id,
    version: b.version,
    versionCode: b.versionCode,
    url: base.url,
    parts: b.parts.length > 1 ? JSON.stringify(b.parts.map((p) => ({ url: p.url, sha256: p.sha256, name: p.name, split: p.split }))) : undefined,
  });
  return b;
}
