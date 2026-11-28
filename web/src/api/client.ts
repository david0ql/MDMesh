// Thin fetch wrapper around the Headwind MDM REST API.
//
// The server (see com.hmdm.rest.json.Response) wraps every response in an
// envelope: { status: "OK" | "ERROR" | ..., message: string | null, data: T }.
// Authentication is *session based*: AuthResource#login stores the user in the
// HttpSession and the browser receives a JSESSIONID cookie. There is no bearer
// token to attach, so every request must be sent with credentials so the
// cookie rides along.

import { serverErrorMessage } from './errorMessages';

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE ?? '/rest').replace(/\/$/, '');

export type ResponseStatus = 'OK' | 'ERROR' | string;

export interface ApiEnvelope<T> {
  status: ResponseStatus;
  message: string | null;
  data: T;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: ResponseStatus,
    readonly httpStatus: number,
    /** The server's own message, untranslated: the key (`error.permission.denied`) when it sent one. Compare
     *  against this, never against `message`, which is the Spanish sentence shown to the operator. */
    readonly code: string = message,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** Build the error of a server envelope that is not OK: Spanish `message`, raw key in `code`. */
export function envelopeError(message: string | null | undefined, status: ResponseStatus, httpStatus: number): ApiError {
  return new ApiError(serverErrorMessage(message), status, httpStatus, message ?? '');
}

/** The raw server key of a failure (`error.x.y`), for the few places that branch on it; otherwise its message. */
export function errorCode(e: unknown): string {
  if (e instanceof ApiError) return e.code;
  return e instanceof Error ? e.message : '';
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Optional cancellation, forwarded to fetch — lets callers abort long polls. */
  signal?: AbortSignal;
  // Some endpoints (e.g. /devices/search) consume a raw JSON string rather than
  // an object. We always JSON.stringify, which is correct for both.
  /** Send `body` verbatim as text/plain instead of JSON — for endpoints that read
   *  a raw String (e.g. POST /devices/{id}/description). */
  text?: boolean;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const url = `${API_BASE}${path}`;
  const init: RequestInit = {
    method: opts.method ?? 'GET',
    // Always send the session cookie. Works same-origin and (with proper CORS)
    // cross-origin.
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
    signal: opts.signal,
  };

  if (opts.body !== undefined) {
    if (opts.body instanceof FormData) {
      // Let the browser set the multipart boundary; don't force a Content-Type.
      init.body = opts.body;
    } else if (opts.text) {
      // Raw text body for endpoints that read a String (not JSON) — sending it
      // JSON-encoded would store the surrounding quotes.
      init.headers = { ...init.headers, 'Content-Type': 'text/plain' };
      init.body = String(opts.body);
    } else {
      init.headers = {
        ...init.headers,
        'Content-Type': 'application/json',
      };
      init.body = JSON.stringify(opts.body);
    }
  }

  let res: globalThis.Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    throw new ApiError(
      `No se pudo contactar al servidor: ${(e as Error).message}`,
      'ERROR',
      0,
    );
  }

  if (res.status === 401 || res.status === 403) {
    throw new ApiError('No autenticado', 'ERROR', res.status);
  }

  if (!res.ok) {
    throw new ApiError(
      `La solicitud falló (HTTP ${res.status})`,
      'ERROR',
      res.status,
    );
  }

  // Most endpoints return the JSON envelope. Some (logout) return no body.
  const text = await res.text();
  if (!text) {
    return undefined as unknown as T;
  }

  let envelope: ApiEnvelope<T>;
  try {
    envelope = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    // e.g. an HTML error page from a proxy — surface it as an API error, not a crash.
    throw new ApiError('Respuesta inesperada del servidor (no es JSON)', 'ERROR', res.status);
  }
  if (envelope.status && envelope.status !== 'OK') {
    throw envelopeError(envelope.message, envelope.status, res.status);
  }
  return envelope.data;
}

export const apiClient = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body, signal }),
  postText: <T>(path: string, body: string, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body, text: true, signal }),
  put: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'PUT', body, signal }),
  del: <T>(path: string, signal?: AbortSignal) =>
    request<T>(path, { method: 'DELETE', signal }),
  postForm: <T>(path: string, form: FormData, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body: form, signal }),
};
