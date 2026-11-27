import {
  FILE_CHUNK, MAX_BUFFERED, MAX_UPLOAD_BYTES, buildAudio, buildFileChunk, message, parseBinary, parseText,
  type BinaryMessage, type FileEntry, type FileListing, type TextMessage,
} from './protocol';

// The console's end of one remote-support session (ADR 0012): a WebSocket to the server's relay. It signs in with
// the session token, keeps the socket alive, acknowledges each screen frame once it is drawn (the phone sends the
// next one only then), moves files in both directions and counts what went through. No React in here.

/** connecting: no socket yet (or coming back) · waiting: signed in, the phone has not joined · live · ended. */
export type ClientState = 'connecting' | 'waiting' | 'live' | 'ended';

export interface DecodedFrame {
  seq: number;
  width: number;
  height: number;
  image: CanvasImageSource;
}

export interface SupportClientHandlers {
  /** `reason` comes with 'ended': console, device, replaced, timeout, idle, network, unauthorized. */
  onState?(state: ClientState, reason?: string): void;
  /** The socket is signed in; `again` when it came back after a drop (the phone then needs screen.start again). */
  onOpen?(again: boolean): void;
  /** The phone joined or left the session. */
  onPeer?(joined: boolean): void;
  /** Draw it now: the image is released and the frame acknowledged as soon as this returns. */
  onFrame?(frame: DecodedFrame): void;
  /** One µ-law audio frame from the phone. */
  onAudio?(mulaw: Uint8Array): void;
  /** Every other message from the phone (info, error, chat, call.state, call.stats, clip, clip.ok, file.error…). */
  onMessage?(m: TextMessage): void;
}

export interface ClientStats {
  frames: number;
  droppedFrames: number;
  frameBytes: number;
  /** Milliseconds from the start of the session to the first drawn frame. */
  firstFrameMs: number | null;
  lastFrameAt: number;
  txAudioFrames: number;
  rxAudioFrames: number;
  reconnects: number;
}

const PING_MS = 25_000;
const RETRY_MS = 2_000;
/** The server keeps a session whose console dropped for 45 s (SupportSessions.REJOIN_MILLIS). */
const REJOIN_MS = 40_000;
const LIST_TIMEOUT_MS = 15_000;
const TRANSFER_IDLE_MS = 45_000;
const FPS_WINDOW_MS = 2_000;
const END_REASONS = new Set(['console', 'device', 'replaced', 'timeout', 'idle', 'network']);

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

interface Deferred<T> {
  promise: Promise<T>;
  resolve(v: T): void;
  reject(e: Error): void;
  settled: boolean;
}

function deferred<T>(): Deferred<T> {
  const d = { settled: false } as Deferred<T>;
  d.promise = new Promise<T>((resolve, reject) => {
    d.resolve = (v) => { if (!d.settled) { d.settled = true; resolve(v); } };
    d.reject = (e) => { if (!d.settled) { d.settled = true; reject(e); } };
  });
  d.promise.catch(() => undefined); // every caller awaits it; this only silences "unhandled" for abandoned ones
  return d;
}

/** A JPEG as something a canvas can draw, and how to free it. */
async function decodeJpeg(jpeg: Uint8Array<ArrayBuffer>): Promise<{ image: CanvasImageSource; release(): void }> {
  const blob = new Blob([jpeg], { type: 'image/jpeg' });
  if (typeof createImageBitmap === 'function') {
    const bitmap = await createImageBitmap(blob);
    return { image: bitmap, release: () => { try { bitmap.close(); } catch { /* already closed */ } } };
  }
  const url = URL.createObjectURL(blob);
  try {
    const img = new Image();
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve();
      img.onerror = () => reject(new Error('jpeg'));
      img.src = url;
    });
    return { image: img, release: () => URL.revokeObjectURL(url) };
  } catch (e) {
    URL.revokeObjectURL(url);
    throw e;
  }
}

interface Download {
  id: number;
  name: string;
  size: number;
  received: number;
  chunks: Uint8Array<ArrayBuffer>[];
  done: Deferred<{ name: string; blob: Blob }>;
  onProgress?: (received: number, total: number) => void;
  lastAt: number;
}

export class SupportClient {
  state: ClientState = 'connecting';
  endReason: string | null = null;
  readonly stats: ClientStats = {
    frames: 0, droppedFrames: 0, frameBytes: 0, firstFrameMs: null, lastFrameAt: 0,
    txAudioFrames: 0, rxAudioFrames: 0, reconnects: 0,
  };

  private ws: WebSocket | null = null;
  private authed = false;
  private everAuthed = false;
  private finished = false;
  private lostAt = 0;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly startedAt: number;

  private frameBusy = false;
  private nextFrame: Extract<BinaryMessage, { kind: 'frame' }> | null = null;
  private drawTimes: number[] = [];

  private lastId = -1;
  private readonly uploads = new Map<number, Deferred<string>>();
  private download: Download | null = null;
  private listing: Deferred<FileListing> | null = null;

  constructor(
    private readonly opts: { socket: string; token: string; startedAt?: number },
    private readonly handlers: SupportClientHandlers,
  ) {
    this.startedAt = opts.startedAt ?? Date.now();
  }

  connect(): void {
    if (this.finished || this.ws) return;
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    let ws: WebSocket;
    try {
      ws = new WebSocket(`${scheme}://${window.location.host}${this.opts.socket}`);
    } catch {
      this.lost();
      return;
    }
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    this.authed = false;
    ws.onopen = () => {
      if (this.ws !== ws) return;
      try { ws.send(message('auth', { token: this.opts.token })); } catch { /* onclose follows */ }
    };
    ws.onmessage = (e: MessageEvent) => {
      if (this.ws !== ws) return;
      try {
        if (typeof e.data === 'string') this.onText(e.data);
        else if (e.data instanceof ArrayBuffer) this.onBinary(e.data);
      } catch { /* one bad message must not take the session down */ }
    };
    ws.onerror = () => undefined; // onclose always follows
    ws.onclose = (e: CloseEvent) => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.stopPing();
      if (this.finished) return;
      if (END_REASONS.has(e.reason)) { this.finish(e.reason); return; }
      if (e.reason === 'unauthorized') { this.finish(this.everAuthed ? 'network' : 'unauthorized'); return; }
      this.lost();
    };
  }

  get open(): boolean {
    return this.authed && this.ws?.readyState === WebSocket.OPEN;
  }

  /** Frames drawn per second, over the last two seconds. */
  fps(now: number = Date.now()): number {
    const from = now - FPS_WINDOW_MS;
    while (this.drawTimes.length && this.drawTimes[0] < from) this.drawTimes.shift();
    return this.drawTimes.length / (FPS_WINDOW_MS / 1000);
  }

  // --- sending --------------------------------------------------------------------------------------------------

  /** Send a text message; false when the socket is not up. */
  send(type: string, body: Record<string, unknown> = {}): boolean {
    return this.sendRaw(message(type, body));
  }

  /** Send any `{t: …}` object as is (the test hook uses it). */
  sendObject(obj: unknown): boolean {
    if (!obj || typeof obj !== 'object' || typeof (obj as { t?: unknown }).t !== 'string') return false;
    return this.sendRaw(JSON.stringify(obj));
  }

  /** Send a message already built by the protocol helpers. */
  sendText(raw: string): boolean {
    return this.sendRaw(raw);
  }

  private sendRaw(data: string | Uint8Array<ArrayBuffer>): boolean {
    const ws = this.ws;
    if (!ws || !this.authed || ws.readyState !== WebSocket.OPEN) return false;
    try {
      ws.send(data);
      return true;
    } catch {
      return false;
    }
  }

  /** One µ-law frame of the operator's microphone. Dropped (never queued) when the socket is behind. */
  sendAudio(mulaw: Uint8Array): boolean {
    const ws = this.ws;
    if (!ws || ws.bufferedAmount > MAX_BUFFERED) return false;
    if (!this.sendRaw(buildAudio(mulaw))) return false;
    this.stats.txAudioFrames++;
    return true;
  }

  /** End the session from the console: tell the phone, close the socket. */
  end(reason = 'console'): void {
    if (this.finished) return;
    this.send('bye');
    this.finish(reason);
  }

  /** Close without ending the session on the server (it ends by itself if nobody comes back). */
  dispose(): void {
    this.finished = true;
    this.cleanup();
  }

  // --- receiving ------------------------------------------------------------------------------------------------

  private onText(raw: string): void {
    const m = parseText(raw);
    if (!m) return;
    switch (m.t) {
      case 'auth.ok': {
        const again = this.everAuthed;
        this.authed = true;
        this.everAuthed = true;
        this.lostAt = 0;
        if (again) this.stats.reconnects++;
        this.startPing();
        this.setState('waiting');
        this.handlers.onOpen?.(again);
        return;
      }
      case 'peer':
        if (m.role !== 'device') return;
        this.setState(m.state === 'joined' ? 'live' : 'waiting');
        this.handlers.onPeer?.(m.state === 'joined');
        return;
      case 'end':
        this.finish(typeof m.reason === 'string' && m.reason ? m.reason : 'device');
        return;
      case 'bye':
        this.finish('device');
        return;
      case 'file.entries': {
        const l = this.listing;
        this.listing = null;
        l?.resolve({
          path: typeof m.path === 'string' ? m.path : '',
          limited: m.limited === true,
          entries: Array.isArray(m.entries) ? (m.entries as FileEntry[]).filter((e) => e && typeof e.path === 'string') : [],
        });
        return;
      }
      case 'file.put': {
        const d = this.download;
        if (d && d.id === m.id) {
          if (typeof m.name === 'string' && m.name) d.name = m.name;
          d.size = typeof m.size === 'number' ? m.size : 0;
          d.lastAt = Date.now();
        }
        return;
      }
      case 'file.end': {
        const d = this.download;
        if (d && d.id === m.id) {
          this.download = null;
          d.done.resolve({ name: d.name, blob: new Blob(d.chunks) });
        }
        return;
      }
      case 'file.saved': {
        const id = typeof m.id === 'number' ? m.id : -1;
        this.uploads.get(id)?.resolve(typeof m.path === 'string' ? m.path : '');
        return;
      }
      case 'file.error': {
        const detail = typeof m.detail === 'string' && m.detail ? m.detail : 'error';
        const id = typeof m.id === 'number' ? m.id : null;
        const up = id == null ? undefined : this.uploads.get(id);
        if (up) { up.reject(new Error(detail)); return; }
        if (id != null && this.download?.id === id) {
          const d = this.download;
          this.download = null;
          d.done.reject(new Error(detail));
          return;
        }
        if (id == null && this.listing) {
          const l = this.listing;
          this.listing = null;
          l.reject(new Error(detail));
          return;
        }
        this.handlers.onMessage?.(m);
        return;
      }
      default:
        this.handlers.onMessage?.(m);
    }
  }

  private onBinary(buf: ArrayBuffer): void {
    const b = parseBinary(buf);
    if (!b) return;
    if (b.kind === 'frame') {
      if (this.frameBusy) {
        // Still drawing: keep only the newest, but acknowledge the one let go (the phone waits for every ack).
        if (this.nextFrame) { this.ack(this.nextFrame.seq); this.stats.droppedFrames++; }
        this.nextFrame = b;
      } else {
        void this.draw(b);
      }
    } else if (b.kind === 'audio') {
      this.stats.rxAudioFrames++;
      this.handlers.onAudio?.(b.mulaw);
    } else {
      const d = this.download;
      if (d && d.id === b.id) {
        d.chunks.push(b.data);
        d.received += b.data.length;
        d.lastAt = Date.now();
        try { d.onProgress?.(d.received, d.size); } catch { /* the UI's problem */ }
      }
    }
  }

  private ack(seq: number): void {
    this.sendRaw(`{"t":"ack","seq":${seq}}`);
  }

  private async draw(f: Extract<BinaryMessage, { kind: 'frame' }>): Promise<void> {
    this.frameBusy = true;
    try {
      const { image, release } = await decodeJpeg(f.jpeg);
      try {
        if (!this.finished) this.handlers.onFrame?.({ seq: f.seq, width: f.width, height: f.height, image });
      } finally {
        release();
      }
      const now = Date.now();
      this.stats.frames++;
      this.stats.frameBytes += f.jpeg.length;
      this.stats.lastFrameAt = now;
      if (this.stats.firstFrameMs == null) this.stats.firstFrameMs = now - this.startedAt;
      this.drawTimes.push(now);
      if (this.drawTimes.length > 120) this.drawTimes.shift();
    } catch {
      this.stats.droppedFrames++;
    } finally {
      this.ack(f.seq);
      this.frameBusy = false;
      const next = this.nextFrame;
      this.nextFrame = null;
      if (next && !this.finished) void this.draw(next);
    }
  }

  // --- files ----------------------------------------------------------------------------------------------------

  /** A transfer id (0..255) no transfer of ours is using. */
  private newId(): number {
    for (let i = 0; i < 256; i++) {
      this.lastId = (this.lastId + 1) % 256;
      if (!this.uploads.has(this.lastId) && this.download?.id !== this.lastId) return this.lastId;
    }
    throw new Error('Demasiadas transferencias a la vez.');
  }

  /** Send a file to the phone. Resolves with the path it was saved at. */
  async upload(file: File, onProgress?: (sent: number, total: number) => void): Promise<string> {
    if (file.size > MAX_UPLOAD_BYTES) throw new Error('El archivo supera el máximo de 200 MB.');
    if (!this.open) throw new Error('La sesión no está conectada.');
    const id = this.newId();
    const saved = deferred<string>();
    this.uploads.set(id, saved);
    try {
      this.send('file.put', { id, name: file.name, size: file.size });
      let offset = 0;
      let waited = 0;
      while (offset < file.size && !saved.settled) {
        const ws = this.ws;
        if (!ws || !this.open) throw new Error('Se perdió la conexión durante el envío.');
        if (ws.bufferedAmount > MAX_BUFFERED) {
          await sleep(20);
          waited += 20;
          if (waited > TRANSFER_IDLE_MS) throw new Error('La conexión está demasiado lenta.');
          continue;
        }
        waited = 0;
        const chunk = new Uint8Array(await file.slice(offset, offset + FILE_CHUNK).arrayBuffer());
        if (chunk.length === 0) throw new Error('No se pudo leer el archivo.');
        if (!this.sendRaw(buildFileChunk(id, chunk))) throw new Error('Se perdió la conexión durante el envío.');
        offset += chunk.length;
        try { onProgress?.(offset, file.size); } catch { /* the UI's problem */ }
      }
      if (!saved.settled) this.send('file.end', { id });
      const timer = setTimeout(() => saved.reject(new Error('El teléfono no confirmó el archivo.')), TRANSFER_IDLE_MS);
      try {
        return await saved.promise;
      } finally {
        clearTimeout(timer);
      }
    } finally {
      this.uploads.delete(id);
    }
  }

  /** Bring a file from the phone (one at a time: the phone drops the previous one). */
  async fetchFile(path: string, onProgress?: (received: number, total: number) => void): Promise<{ name: string; blob: Blob }> {
    if (!this.open) throw new Error('La sesión no está conectada.');
    this.download?.done.reject(new Error('Reemplazada por otra descarga.'));
    const id = this.newId();
    const d: Download = {
      id, name: path.split('/').pop() || 'archivo', size: 0, received: 0, chunks: [],
      done: deferred(), onProgress, lastAt: Date.now(),
    };
    this.download = d;
    this.send('file.get', { id, path });
    const watch = setInterval(() => {
      if (Date.now() - d.lastAt > TRANSFER_IDLE_MS) d.done.reject(new Error('El teléfono dejó de enviar el archivo.'));
    }, 2_000);
    try {
      return await d.done.promise;
    } finally {
      clearInterval(watch);
      if (this.download === d) this.download = null;
    }
  }

  /** The phone's folder at `path` (its root when omitted). */
  async list(path?: string): Promise<FileListing> {
    if (!this.open) throw new Error('La sesión no está conectada.');
    this.listing?.reject(new Error('Reemplazada por otra consulta.'));
    const l = deferred<FileListing>();
    this.listing = l;
    this.send('file.list', path ? { path } : {});
    const timer = setTimeout(() => l.reject(new Error('El teléfono no respondió.')), LIST_TIMEOUT_MS);
    try {
      return await l.promise;
    } finally {
      clearTimeout(timer);
      if (this.listing === l) this.listing = null;
    }
  }

  private failTransfers(why: string): void {
    const e = new Error(why);
    this.uploads.forEach((u) => u.reject(e));
    this.download?.done.reject(e);
    this.download = null;
    this.listing?.reject(e);
    this.listing = null;
  }

  // --- lifecycle ------------------------------------------------------------------------------------------------

  private setState(state: ClientState, reason?: string): void {
    if (this.state === state && state !== 'ended') return;
    this.state = state;
    try { this.handlers.onState?.(state, reason); } catch { /* the UI's problem */ }
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = setInterval(() => { this.sendRaw('{"t":"ping"}'); }, PING_MS);
  }

  private stopPing(): void {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = null;
  }

  /** The socket dropped without the session ending: come back while the server still holds it. */
  private lost(): void {
    if (this.finished) return;
    this.authed = false;
    this.failTransfers('Se perdió la conexión.');
    const now = Date.now();
    if (this.lostAt === 0) this.lostAt = now;
    if (now - this.lostAt > REJOIN_MS) { this.finish('network'); return; }
    this.setState('connecting');
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => { this.retryTimer = null; this.connect(); }, RETRY_MS);
  }

  private finish(reason: string): void {
    if (this.finished) return;
    this.finished = true;
    this.endReason = reason;
    this.cleanup();
    this.setState('ended', reason);
  }

  private cleanup(): void {
    this.stopPing();
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.failTransfers('La sesión terminó.');
    this.nextFrame = null;
    const ws = this.ws;
    this.ws = null;
    this.authed = false;
    if (ws) {
      ws.onopen = ws.onmessage = ws.onclose = ws.onerror = null;
      try { ws.close(); } catch { /* already closed */ }
    }
  }
}
