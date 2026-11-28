// The remote-support session channel (ADR 0012), console side: pure helpers to read and build what travels on the
// socket. Text messages are small JSON objects ({"t": "<type>", …}); binary messages name their content in byte 0.

export const BIN_FRAME = 1;
export const BIN_AUDIO = 2;
export const BIN_FILE = 3;

export const FRAME_HEADER = 9;
/** File chunks: small enough to interleave with audio without a gap. */
export const FILE_CHUNK = 48 * 1024;
/** Sending waits while the socket holds more than this. */
export const MAX_BUFFERED = 1024 * 1024;
/** The phone refuses bigger uploads (SupportProtocol.MAX_UPLOAD_BYTES). */
export const MAX_UPLOAD_BYTES = 200 * 1024 * 1024;
export const MAX_SWIPE_POINTS = 40;
/** The phone keeps at most this many typed characters per message. */
export const MAX_TEXT = 4000;

export type SupportKey =
  | 'back' | 'home' | 'recents' | 'notifications' | 'quicksettings' | 'power' | 'lock' | 'backspace' | 'enter';

/** What the phone says about itself (`info`), re-sent whenever it changes. */
export interface SupportInfo {
  sdk?: number;
  release?: string;
  model?: string;
  agent?: string;
  engine?: 'builtin' | 'none' | string;
  screen?: 'ready' | 'needsAuthorization' | 'unsupported' | 'disabled' | string;
  capture?: string;
  control?: boolean;
  voice?: string;
  /** The agent's screen service is running. */
  connected?: boolean;
  viewOnly?: boolean;
  notice?: boolean;
  callAccept?: 'ask' | 'auto' | string;
  probe?: unknown;
  w?: number;
  h?: number;
}

export interface FileEntry {
  name: string;
  path: string;
  dir: boolean;
  size: number;
  modified: number;
}

export interface FileListing {
  path: string;
  /** Without all-files access: only photos, videos, audio and the Soporte folder. */
  limited: boolean;
  entries: FileEntry[];
}

export interface CallStats {
  tx?: number;
  rx?: number;
  micRms?: number;
  rxRms?: number;
  recording?: boolean;
  muted?: boolean;
}

export type TextMessage = { t: string } & Record<string, unknown>;

export type BinaryMessage =
  | { kind: 'frame'; seq: number; width: number; height: number; jpeg: Uint8Array<ArrayBuffer> }
  | { kind: 'audio'; mulaw: Uint8Array<ArrayBuffer> }
  | { kind: 'file'; id: number; data: Uint8Array<ArrayBuffer> };

/** A text message of ours, or null when it is not JSON with a `t`. */
export function parseText(raw: string): TextMessage | null {
  try {
    const m: unknown = JSON.parse(raw);
    if (m && typeof m === 'object' && !Array.isArray(m) && typeof (m as { t?: unknown }).t === 'string') {
      return m as TextMessage;
    }
  } catch { /* not ours */ }
  return null;
}

export function message(type: string, body: Record<string, unknown> = {}): string {
  return JSON.stringify({ t: type, ...body });
}

/** `[1][seq u32][width u16][height u16][jpeg…]`, `[2][µ-law…]` or `[3][id u8][bytes…]`; null for anything else. */
export function parseBinary(buf: ArrayBuffer): BinaryMessage | null {
  const bytes = new Uint8Array(buf);
  if (bytes.length < 1) return null;
  switch (bytes[0]) {
    case BIN_FRAME: {
      if (bytes.length <= FRAME_HEADER) return null;
      const v = new DataView(buf);
      return {
        kind: 'frame', seq: v.getUint32(1, false), width: v.getUint16(5, false), height: v.getUint16(7, false),
        jpeg: bytes.subarray(FRAME_HEADER),
      };
    }
    case BIN_AUDIO:
      return { kind: 'audio', mulaw: bytes.subarray(1) };
    case BIN_FILE:
      if (bytes.length < 2) return null;
      return { kind: 'file', id: bytes[1], data: bytes.subarray(2) };
    default:
      return null;
  }
}

export function buildAudio(mulaw: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(1 + mulaw.length);
  out[0] = BIN_AUDIO;
  out.set(mulaw, 1);
  return out;
}

export function buildFileChunk(id: number, data: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(2 + data.length);
  out[0] = BIN_FILE;
  out[1] = id & 0xff;
  out.set(data, 2);
  return out;
}

export function clamp01(n: number): number {
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0;
}

const round4 = (n: number) => Math.round(clamp01(n) * 10000) / 10000;

export type SwipePoint = [x: number, y: number, tMs: number];

/** At most `max` points of a drag, always keeping the first and the last. */
export function samplePoints(pts: SwipePoint[], max: number = MAX_SWIPE_POINTS): SwipePoint[] {
  if (pts.length <= max) return pts;
  const out: SwipePoint[] = [];
  const step = (pts.length - 1) / (max - 1);
  for (let i = 0; i < max; i++) out.push(pts[Math.round(i * step)]);
  out[max - 1] = pts[pts.length - 1];
  return out;
}

export function tapMessage(x: number, y: number, long = false): string {
  return message('tap', long ? { x: round4(x), y: round4(y), long: true } : { x: round4(x), y: round4(y) });
}

/** A swipe needs two points at least; null otherwise. Times are sent relative to the first point. */
export function swipeMessage(pts: SwipePoint[]): string | null {
  if (pts.length < 2) return null;
  const t0 = pts[0][2];
  return message('swipe', {
    pts: samplePoints(pts).map(([x, y, t]) => [round4(x), round4(y), Math.max(0, Math.round(t - t0))]),
  });
}

/** What a finished press was. Distance in CSS pixels, time in ms. */
export function classifyPress(durationMs: number, movedPx: number, slopPx = 8): 'tap' | 'long' | 'swipe' {
  if (movedPx > slopPx) return 'swipe';
  return durationMs >= 600 ? 'long' : 'tap';
}

/** A file name the browser can save as: no path, never empty. */
export function safeDownloadName(name: string | undefined | null): string {
  const base = (name ?? '').split(/[\\/]/).pop()?.trim() ?? '';
  return base || 'archivo';
}
