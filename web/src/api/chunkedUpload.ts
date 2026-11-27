// Upload of big files in parts (see FilesResource /private/web-ui-files/chunked on the server).
//
// The console's traffic goes through a tunnel that refuses request bodies over 100 MB, so an APK or bundle bigger
// than that cannot travel in one request. It is cut in parts (the server says the size, at most 50 MB), each sent with
// its SHA-256 and retried on its own when it fails; the server puts them together and checks the whole file. An upload
// cut short (page reloaded, connection lost) continues from the parts the server already has.
import { API_BASE, ApiError, apiClient, type ApiEnvelope } from './client';

/** Files this big or bigger go in parts; smaller ones in one request as always. */
export const CHUNKED_THRESHOLD = 50 * 1024 * 1024;
/** What we ask for; the server caps it at 50 MB. */
const PART_SIZE = 24 * 1024 * 1024;
const TRIES_PER_PART = 4;
const BASE = '/private/web-ui-files/chunked';

export type UploadAs = 'apk' | 'raw' | 'bundle';

export interface UploadProgress {
  /** Bytes the server has, including the part in flight. */
  sent: number;
  total: number;
  /** 1-based part being sent, and how many there are (1/1 for a one-request upload). */
  part: number;
  parts: number;
  /** Set while a part that failed is being sent again: which try this is. */
  retry?: number;
}

interface Session {
  id: string;
  size: number;
  partSize: number;
  partCount: number;
  received: number[];
  receivedBytes: number;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Where an unfinished upload of this very file is remembered (so reloading the page resumes it). */
function resumeKey(file: File): string {
  return `dc-upload:${file.name}:${file.size}:${file.lastModified}`;
}
function remember(file: File, id: string | null) {
  try {
    if (id) localStorage.setItem(resumeKey(file), id);
    else localStorage.removeItem(resumeKey(file));
  } catch { /* private mode: no resume across reloads, the upload still works */ }
}
function recall(file: File): string | null {
  try { return localStorage.getItem(resumeKey(file)); } catch { return null; }
}

async function sha256Hex(buf: ArrayBuffer): Promise<string | undefined> {
  // crypto.subtle only exists on https / localhost; without it the server still checks each part's size.
  if (!globalThis.crypto?.subtle) return undefined;
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', buf));
  return Array.from(d, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Send one part with XMLHttpRequest (fetch cannot report upload progress). */
function sendPart(id: string, index: number, body: ArrayBuffer, sha: string | undefined,
  onBytes: (n: number) => void, signal?: AbortSignal): Promise<Session> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${API_BASE}${BASE}/${id}/${index}`);
    xhr.withCredentials = true;
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.setRequestHeader('Accept', 'application/json');
    if (sha) xhr.setRequestHeader('X-Part-Sha256', sha);
    xhr.upload.onprogress = (e) => onBytes(e.loaded);
    xhr.onerror = () => reject(new ApiError('Se cortó la conexión con el servidor', 'ERROR', 0));
    xhr.onabort = () => reject(new ApiError('Subida cancelada', 'CANCELLED', 0));
    xhr.onload = () => {
      if (xhr.status === 401 || xhr.status === 403) return reject(new ApiError('No autenticado', 'ERROR', xhr.status));
      if (xhr.status === 413) return reject(new ApiError('El servidor rechazó la parte por tamaño (HTTP 413)', 'ERROR', 413));
      if (xhr.status < 200 || xhr.status >= 300) return reject(new ApiError(`La solicitud falló (HTTP ${xhr.status})`, 'ERROR', xhr.status));
      try {
        const env = JSON.parse(xhr.responseText) as ApiEnvelope<Session>;
        if (env.status !== 'OK') return reject(new ApiError(env.message ?? 'El servidor devolvió un error', env.status, xhr.status));
        resolve(env.data);
      } catch {
        reject(new ApiError('Respuesta inesperada del servidor (no es JSON)', 'ERROR', xhr.status));
      }
    };
    signal?.addEventListener('abort', () => xhr.abort(), { once: true });
    xhr.send(body);
  });
}

/** An error that sending the same part again cannot fix. */
function isFinal(e: unknown): boolean {
  if (!(e instanceof ApiError)) return false;
  if (e.status === 'CANCELLED' || e.httpStatus === 401 || e.httpStatus === 403 || e.httpStatus === 413) return true;
  // The upload is gone on the server (expired, or it restarted without its temp files).
  return /ya no existe/.test(e.message);
}

/**
 * Upload [file] in parts and finish it [as] an APK (parsed), a raw file or a bundle (unpacked and hosted). Returns
 * exactly what the matching one-request endpoint returns. Resumes an unfinished upload of the same file.
 */
export async function uploadInParts<T>(file: File, as: UploadAs, onProgress?: (p: UploadProgress) => void,
  signal?: AbortSignal): Promise<T> {
  let session: Session | null = null;
  const known = recall(file);
  if (known) {
    session = await apiClient.get<Session>(`${BASE}/${known}`, signal).catch(() => null);
    if (session && session.size !== file.size) session = null;
  }
  if (!session) {
    session = await apiClient.post<Session>(BASE, { fileName: file.name, size: file.size, partSize: PART_SIZE }, signal);
    remember(file, session.id);
  }
  const { id, partSize, partCount } = session;
  const have = new Set(session.received);
  let done = session.receivedBytes;
  for (let i = 0; i < partCount; i++) {
    if (have.has(i)) continue;
    const blob = file.slice(i * partSize, Math.min(file.size, (i + 1) * partSize));
    const buf = await blob.arrayBuffer();
    const sha = await sha256Hex(buf);
    let lastError: unknown;
    let ok = false;
    for (let attempt = 1; attempt <= TRIES_PER_PART; attempt++) {
      if (signal?.aborted) throw new ApiError('Subida cancelada', 'CANCELLED', 0);
      try {
        onProgress?.({ sent: done, total: file.size, part: i + 1, parts: partCount, retry: attempt > 1 ? attempt : undefined });
        await sendPart(id, i, buf, sha, (n) => onProgress?.({
          sent: done + Math.min(n, buf.byteLength), total: file.size, part: i + 1, parts: partCount,
          retry: attempt > 1 ? attempt : undefined,
        }), signal);
        ok = true;
        break;
      } catch (e) {
        lastError = e;
        if (isFinal(e)) break;
        await sleep(1500 * attempt);
      }
    }
    if (!ok) {
      if (lastError instanceof ApiError && /ya no existe/.test(lastError.message)) remember(file, null);
      const why = lastError instanceof Error ? lastError.message : 'error desconocido';
      // The parts already sent stay on the server: choosing the same file again continues from here.
      throw new ApiError(`No se pudo subir la parte ${i + 1} de ${partCount}: ${why}. `
        + 'Vuelve a elegir el mismo archivo para continuar desde donde quedó.', 'ERROR', 0);
    }
    done += buf.byteLength;
  }
  onProgress?.({ sent: file.size, total: file.size, part: partCount, parts: partCount });
  try {
    return await apiClient.post<T>(`${BASE}/${id}/complete?as=${as}`, undefined, signal);
  } finally {
    // Finished or refused, the server no longer has it.
    remember(file, null);
  }
}

/** Human size, for the progress line. */
export function formatBytes(n: number): string {
  if (n >= 1024 * 1024 * 1024) return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(n / 1024))} KB`;
}

/** "Parte 3 de 7 · 61.2 MB de 160.0 MB (38 %)", plus the retry when there is one. */
export function describeProgress(p: UploadProgress): string {
  const pct = p.total > 0 ? Math.floor((p.sent / p.total) * 100) : 0;
  const base = `${formatBytes(p.sent)} de ${formatBytes(p.total)} (${pct} %)`;
  if (p.parts <= 1) return base;
  return `Parte ${p.part} de ${p.parts} · ${base}${p.retry ? ` · reintento ${p.retry}` : ''}`;
}

/** What to tell someone who picked an .aab (the server answers the same). */
export const AAB_MESSAGE =
  'Un .aab (Android App Bundle) no se puede instalar en un teléfono: es el formato que se entrega a Google Play. '
  + 'Conviértelo con tu propia firma («bundletool build-apks --mode=universal --bundle=app.aab --output=app.apks '
  + '--ks=tu.keystore --ks-key-alias=tu_alias») y sube el .apks, o pide a tu desarrollador el APK firmado.';
