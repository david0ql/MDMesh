import { SAMPLE_RATE, decode, encode, rms } from './mulaw';

// The operator's side of a support voice call: microphone → 16 kHz mono → µ-law frames for the socket, and the
// phone's µ-law frames → the speakers through a short jitter buffer. The capture runs in an AudioWorklet loaded
// from /support-audio-worklet.js (the console's CSP allows no inline or blob scripts).

/** A late frame is played this far ahead, so the next ones arrive before the buffer runs dry. */
const JITTER_S = 0.08;
/** More than this queued means the playback fell behind: frames are dropped until it catches up. */
const MAX_QUEUED_S = 0.3;

type AudioContextCtor = typeof AudioContext;

function contextCtor(): AudioContextCtor | undefined {
  const w = window as unknown as { AudioContext?: AudioContextCtor; webkitAudioContext?: AudioContextCtor };
  return w.AudioContext ?? w.webkitAudioContext;
}

function micErrorText(e: unknown): string {
  const name = e instanceof DOMException || e instanceof Error ? e.name : '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'El navegador no dio permiso para el micrófono: escuchas al usuario, pero él no te oye.';
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return 'No hay micrófono en este equipo: escuchas al usuario, pero él no te oye.';
  }
  if (name === 'NotReadableError') {
    return 'Otra aplicación está usando el micrófono: escuchas al usuario, pero él no te oye.';
  }
  return `No se pudo abrir el micrófono${e instanceof Error && e.message ? ` (${e.message})` : ''}: escuchas al usuario, pero él no te oye.`;
}

export class Voice {
  /** The operator's microphone is muted (nothing is sent). */
  muted = false;
  /** Why the microphone is not sending, in Spanish; null when it works. */
  micError: string | null = null;
  micActive = false;
  /** RMS (0..32767) of the last frame received from the phone. */
  rxRms = 0;
  /** RMS of the last microphone frame. */
  txRms = 0;
  droppedFrames = 0;

  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private node: AudioWorkletNode | null = null;
  private sink: GainNode | null = null;
  private nextTime = 0;
  private sending = false;
  /** Bumped by start/stop, so a start that was still opening the microphone notices it was cancelled. */
  private run = 0;

  constructor(private readonly onFrame: (mulaw: Uint8Array) => void) {}

  get started(): boolean {
    return this.ctx != null;
  }

  /** Whether microphone frames go out (only while the call is active). */
  setSending(on: boolean): void {
    this.sending = on;
  }

  setMuted(on: boolean): void {
    this.muted = on;
    try { this.stream?.getAudioTracks().forEach((t) => { t.enabled = !on; }); } catch { /* the track is gone */ }
  }

  /**
   * Open the speakers and the microphone. Call it from a click (browsers only start audio on a gesture).
   * Never throws: without a microphone the call still plays what the phone sends, and `micError` says why.
   */
  async start(): Promise<void> {
    this.stop();
    const run = ++this.run;
    this.micError = null;
    const Ctx = contextCtor();
    if (!Ctx) {
      this.micError = 'Este navegador no admite audio.';
      return;
    }
    let ctx: AudioContext;
    try {
      ctx = new Ctx({ latencyHint: 'interactive' });
    } catch {
      this.micError = 'Este navegador no pudo abrir el audio.';
      return;
    }
    this.ctx = ctx;
    this.nextTime = 0;
    try { await ctx.resume(); } catch { /* stays suspended until the next gesture */ }
    if (run !== this.run) return;

    if (!navigator.mediaDevices?.getUserMedia || !ctx.audioWorklet) {
      this.micError = 'El micrófono solo funciona con la consola abierta por HTTPS: escuchas al usuario, pero él no te oye.';
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      if (run !== this.run) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      this.stream = stream;
      await ctx.audioWorklet.addModule(`${import.meta.env.BASE_URL}support-audio-worklet.js`);
      if (run !== this.run) return;
      const node = new AudioWorkletNode(ctx, 'dc-support-capture', {
        numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1, channelCountMode: 'explicit',
      });
      node.port.onmessage = (e: MessageEvent) => {
        if (!(e.data instanceof ArrayBuffer)) return;
        const pcm = new Int16Array(e.data);
        this.txRms = rms(pcm);
        if (!this.sending || this.muted) return;
        try { this.onFrame(encode(pcm)); } catch { /* the socket's problem */ }
      };
      const source = ctx.createMediaStreamSource(stream);
      // The worklet outputs silence; it is wired to the destination only so the browser keeps running it.
      const sink = ctx.createGain();
      sink.gain.value = 0;
      source.connect(node);
      node.connect(sink);
      sink.connect(ctx.destination);
      this.source = source;
      this.node = node;
      this.sink = sink;
      this.micActive = true;
      this.setMuted(this.muted);
    } catch (e) {
      if (run !== this.run) return;
      this.micError = micErrorText(e);
      this.releaseMic();
    }
  }

  /** Play one µ-law frame from the phone. */
  play(mulaw: Uint8Array): void {
    if (mulaw.length === 0) return;
    const pcm = decode(mulaw);
    this.rxRms = rms(pcm);
    const ctx = this.ctx;
    if (!ctx || ctx.state === 'closed') return;
    try {
      const now = ctx.currentTime;
      if (this.nextTime < now + 0.01) {
        this.nextTime = now + JITTER_S; // the buffer ran dry (start, or a network gap): build it again
      } else if (this.nextTime - now > MAX_QUEUED_S) {
        this.droppedFrames++;
        return;
      }
      const buffer = ctx.createBuffer(1, pcm.length, SAMPLE_RATE);
      const out = buffer.getChannelData(0);
      for (let i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768;
      const src = ctx.createBufferSource();
      src.buffer = buffer;
      src.connect(ctx.destination);
      src.start(this.nextTime);
      this.nextTime += buffer.duration;
    } catch {
      this.droppedFrames++;
    }
  }

  private releaseMic(): void {
    this.micActive = false;
    try { if (this.node) this.node.port.onmessage = null; } catch { /* gone */ }
    for (const n of [this.source, this.node, this.sink]) {
      try { n?.disconnect(); } catch { /* gone */ }
    }
    this.source = null;
    this.node = null;
    this.sink = null;
    try { this.stream?.getTracks().forEach((t) => t.stop()); } catch { /* gone */ }
    this.stream = null;
  }

  /** Hang up: release the microphone and the audio device. Safe to call at any time, any number of times. */
  stop(): void {
    this.run++;
    this.sending = false;
    this.releaseMic();
    const ctx = this.ctx;
    this.ctx = null;
    this.nextTime = 0;
    if (ctx && ctx.state !== 'closed') {
      try { void ctx.close().catch(() => undefined); } catch { /* already closed */ }
    }
  }
}
