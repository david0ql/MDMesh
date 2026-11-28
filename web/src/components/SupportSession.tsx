import {
  useCallback, useEffect, useRef, useState,
  type ClipboardEvent as ReactClipboardEvent, type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent, type ReactNode,
} from 'react';
import { queueCommand } from '../api/commands';
import { stopSupportSession, stopSupportSessionOnUnload, type SupportStart } from '../api/support';
import { SupportClient, type ClientState, type DecodedFrame } from '../support/SupportClient';
import { Voice } from '../support/voice';
import {
  MAX_TEXT, clamp01, message, safeDownloadName, swipeMessage, tapMessage,
  type CallStats, type FileEntry, type FileListing, type SupportInfo, type SupportKey, type SwipePoint, type TextMessage,
} from '../support/protocol';
import { useToast } from '../ui/toast';

// The live remote-support session (ADR 0012): the phone's screen on a canvas with touch and keyboard, a chat, a
// voice call, the clipboard and files, all over one socket (SupportClient). When the phone's own screen service is
// not authorised yet, everything but the screen still works, and the legacy droidVNC viewer can stand in for it.

type Device = { number: string };

export type ScreenMode = 'builtin' | 'alt' | 'none';

interface Props {
  device: Device;
  /** The answer of POST …/start. */
  start: SupportStart;
  /** When the operator pressed the button (for "first image in N s"). */
  startedAt: number;
  /** Stands in for the screen while the agent's own is not ready (the legacy droidVNC viewer). */
  altScreen?: ReactNode;
  onScreenMode?: (mode: ScreenMode) => void;
  /** The session is over and its panel can go away. */
  onClosed: () => void;
}

interface ChatLine { from: 'console' | 'device'; text: string; at: number }

type CallPhase = 'idle' | 'calling' | 'ringing' | 'active' | 'declined' | 'missed' | 'ended' | 'error';
interface CallUi { s: CallPhase; detail?: string; since?: number; remoteMuted?: boolean }

/** What the Playwright e2e reads (window.__dcSupport). Plain data, updated in place while the session is open. */
interface DcSupportHook {
  state: ClientState;
  info: SupportInfo | null;
  frames: number;
  firstFrameMs: number | null;
  lastFrameAt: number;
  callState: string;
  callStats: CallStats | null;
  txAudioFrames: number;
  rxAudioFrames: number;
  rxAudioRms: number;
  chat: { from: 'console' | 'device'; text: string }[];
  send: (obj: unknown) => boolean;
}

declare global {
  interface Window { __dcSupport?: DcSupportHook }
}

const QUALITY = {
  low: { label: 'Baja', maxDim: 540, quality: 40 },
  mid: { label: 'Media', maxDim: 960, quality: 55 },
  high: { label: 'Alta', maxDim: 1440, quality: 75 },
} as const;
type QualityKey = keyof typeof QUALITY;

const TAP_SLOP_PX = 8;
const LONG_PRESS_MS = 600;
const MOVE_SAMPLE_MS = 16;
const TEXT_BATCH_MS = 80;
const WHEEL_GAP_MS = 120;

const END_TEXT: Record<string, string> = {
  console: 'La sesión terminó desde la consola.',
  device: 'El usuario terminó la sesión en el teléfono, o el teléfono dejó de responder.',
  replaced: 'Otra sesión de soporte reemplazó a esta en el mismo teléfono.',
  timeout: 'El teléfono no se conectó a tiempo (¿apagado, sin internet o dormido?).',
  idle: 'La sesión se cerró sola por inactividad.',
  network: 'Se perdió la conexión con el servidor.',
  unauthorized: 'El servidor no aceptó la sesión (ya había terminado).',
};

function callText(c: CallUi): string {
  switch (c.s) {
    case 'calling': return 'Llamando…';
    case 'ringing': return 'Llamando… esperando que acepten';
    case 'active': return 'En llamada';
    case 'declined': return 'El usuario rechazó la llamada.';
    case 'missed': return 'Nadie contestó en el teléfono.';
    case 'ended': return 'La llamada terminó.';
    case 'error':
      if (c.detail && /microphone permission/i.test(c.detail)) {
        return 'El teléfono no tiene el permiso de micrófono para DallyControl. Hay que darlo una vez en el teléfono (Ajustes → Apps → DallyControl → Permisos).';
      }
      if (c.detail === 'cancelled') return 'La llamada se canceló antes de empezar.';
      return `No se pudo iniciar la llamada en el teléfono${c.detail ? ` (${c.detail})` : ''}.`;
    default: return '';
  }
}

const fmtClock = (ms: number) => new Date(ms).toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit' });
const fmtTimer = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
};
const fmtSize = (n: number) => {
  if (!Number.isFinite(n) || n < 0) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};
const decimal = (n: number) => n.toFixed(1).replace('.', ',');
const stamp = () => new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);

function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = safeDownloadName(name);
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 15_000);
  }
}

const canRecordCanvas = typeof MediaRecorder !== 'undefined'
  && typeof HTMLCanvasElement !== 'undefined' && typeof HTMLCanvasElement.prototype.captureStream === 'function';

const testTone = () => {
  try { return new URLSearchParams(window.location.search).get('supportTestTone') === '1'; } catch { return false; }
};

export function SupportSession({ device, start, startedAt, altScreen, onScreenMode, onClosed }: Props) {
  const toast = useToast();
  const viewOnly = start.viewOnly;

  const [cstate, setCstate] = useState<ClientState>('connecting');
  const [endReason, setEndReason] = useState<string | null>(null);
  const [info, setInfo] = useState<SupportInfo | null>(null);
  const [screenError, setScreenError] = useState<string | null>(null);
  const [hasFrame, setHasFrame] = useState(false);
  const [now, setNow] = useState(Date.now());

  const [chat, setChat] = useState<ChatLine[]>([]);
  const [draft, setDraft] = useState('');
  const [chatOpen, setChatOpen] = useState(true);
  const [unread, setUnread] = useState(0);

  const [call, setCall] = useState<CallUi>({ s: 'idle' });
  const [callStats, setCallStats] = useState<CallStats | null>(null);
  const [muted, setMuted] = useState(false);
  const [micNote, setMicNote] = useState<string | null>(null);

  const [quality, setQuality] = useState<QualityKey>('mid');
  const [scale, setScale] = useState<'fit' | 'real'>('fit');
  const [full, setFull] = useState(false);
  const [recording, setRecording] = useState(false);
  const [tool, setTool] = useState<'clip' | 'files' | null>(null);

  const [clipDraft, setClipDraft] = useState('');
  const [clipBack, setClipBack] = useState<{ s: string; source: string } | null>(null);
  const [clipNote, setClipNote] = useState<string | null>(null);

  const [upload, setUpload] = useState<{ name: string; sent: number; total: number; path?: string; error?: string } | null>(null);
  const [listing, setListing] = useState<FileListing | null>(null);
  const [listBusy, setListBusy] = useState(false);
  const [fileNote, setFileNote] = useState<string | null>(null);
  const [download, setDownload] = useState<{ name: string; received: number; total: number } | null>(null);
  const [authSent, setAuthSent] = useState(false);

  const rootRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chatListRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const clientRef = useRef<SupportClient | null>(null);
  const voiceRef = useRef<Voice | null>(null);
  const hookRef = useRef<DcSupportHook | null>(null);
  const mountedRef = useRef(false);
  const tornDownRef = useRef(false);
  const disposeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const infoRef = useRef<SupportInfo | null>(null);
  const streamActive = useRef(false);
  const pendingError = useRef(false);
  const qualityRef = useRef<QualityKey>('mid');
  const chatSeen = useRef(true);
  const hasFrameRef = useRef(false);
  /** The operator hung up: the phone's "ended" that follows needs no notice. */
  const selfHangup = useRef(false);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const textBuf = useRef('');
  const textTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastWheel = useRef(0);
  const gesture = useRef<{
    id: number; x0: number; y0: number; t0: number; pts: SwipePoint[]; moved: boolean; done: boolean;
    lastT: number; timer: ReturnType<typeof setTimeout> | null;
  } | null>(null);

  qualityRef.current = quality;
  chatSeen.current = chatOpen;

  const send = useCallback((type: string, body?: Record<string, unknown>) => clientRef.current?.send(type, body) ?? false, []);

  // --- test hook --------------------------------------------------------------------------------------------------
  const syncHook = useCallback(() => {
    const h = hookRef.current;
    const c = clientRef.current;
    if (!h || !c) return;
    h.state = c.state;
    h.frames = c.stats.frames;
    h.firstFrameMs = c.stats.firstFrameMs;
    h.lastFrameAt = c.stats.lastFrameAt;
    h.txAudioFrames = c.stats.txAudioFrames;
    h.rxAudioFrames = c.stats.rxAudioFrames;
    h.rxAudioRms = voiceRef.current?.rxRms ?? 0;
  }, []);

  // --- screen stream ----------------------------------------------------------------------------------------------
  const startScreen = useCallback(() => {
    const q = QUALITY[qualityRef.current];
    if (!clientRef.current?.send('screen.start', { maxDim: q.maxDim, quality: q.quality })) return;
    streamActive.current = true;
    pendingError.current = false;
    setScreenError(null);
  }, []);

  const stopRecording = useCallback(() => {
    const r = recorderRef.current;
    recorderRef.current = null;
    try { if (r && r.state !== 'inactive') r.stop(); } catch { /* already stopped */ }
    if (mountedRef.current) setRecording(false);
  }, []);

  // --- the session: one client + one voice engine for the life of this component -----------------------------------
  useEffect(() => {
    mountedRef.current = true;
    // React's development double-mount runs the cleanup below and then this again: the pending teardown is called off.
    if (disposeTimer.current) { clearTimeout(disposeTimer.current); disposeTimer.current = null; }

    const teardown = (unloading: boolean) => {
      if (tornDownRef.current) return;
      tornDownRef.current = true;
      const c = clientRef.current;
      // Only end on the server what is still ours: after "replaced" the device's open session is somebody else's.
      const wasOpen = !!c && c.state !== 'ended';
      try { c?.end('console'); } catch { /* closing anyway */ }
      try { voiceRef.current?.stop(); } catch { /* closing anyway */ }
      try { const r = recorderRef.current; if (r && r.state !== 'inactive') r.stop(); } catch { /* closing anyway */ }
      recorderRef.current = null;
      if (textTimer.current) clearTimeout(textTimer.current);
      if (gesture.current?.timer) clearTimeout(gesture.current.timer);
      if (hookRef.current) hookRef.current.state = 'ended';
      if (wasOpen) {
        if (unloading) stopSupportSessionOnUnload(device.number);
        else void stopSupportSession(device.number).catch(() => undefined);
      }
    };

    if (!clientRef.current && !tornDownRef.current) {
      const pushChat = (line: ChatLine) => {
        hookRef.current?.chat.push({ from: line.from, text: line.text });
        if (mountedRef.current) setChat((c) => [...c, line]);
      };

      const onInfo = (next: SupportInfo) => {
        const prev = infoRef.current;
        infoRef.current = next;
        if (hookRef.current) hookRef.current.info = next;
        if (mountedRef.current) setInfo(next);
        if (next.screen !== 'ready') { streamActive.current = false; return; }
        const cameBack = prev?.screen !== 'ready' || (prev?.connected === false && next.connected === true);
        // After a capture error the phone repeats `info` unchanged: starting again then would loop, so only a
        // change (authorised now, the screen service connected) restarts by itself. "Reintentar" does the rest.
        if (!streamActive.current && (!pendingError.current || cameBack)) startScreen();
      };

      const onCall = (m: TextMessage) => {
        const s = typeof m.s === 'string' ? m.s : '';
        const detail = typeof m.detail === 'string' ? m.detail : undefined;
        if (hookRef.current) hookRef.current.callState = s;
        if (s === 'ringing') {
          setCall({ s: 'ringing' });
        } else if (s === 'active') {
          voiceRef.current?.setSending(true);
          setCall((c) => ({ s: 'active', since: c.s === 'active' && c.since ? c.since : Date.now(), remoteMuted: m.muted === true }));
        } else if (s === 'declined' || s === 'missed' || s === 'ended' || s === 'error') {
          voiceRef.current?.stop();
          const quiet = s === 'ended' && selfHangup.current;
          selfHangup.current = false;
          setCall(quiet ? { s: 'idle' } : { s, detail });
        }
      };

      const voice = new Voice((mulaw) => { clientRef.current?.sendAudio(mulaw); });
      const client = new SupportClient({ socket: start.socket, token: start.token, startedAt }, {
        onState: (s, reason) => {
          if (hookRef.current) hookRef.current.state = s;
          if (s === 'ended') {
            try { voice.stop(); } catch { /* closing */ }
            try { const r = recorderRef.current; if (r && r.state !== 'inactive') r.stop(); } catch { /* closing */ }
            recorderRef.current = null;
          }
          if (!mountedRef.current) return;
          setCstate(s);
          if (s === 'ended') {
            setEndReason(reason ?? 'network');
            setRecording(false);
            setCall((c) => (c.s === 'active' || c.s === 'ringing' || c.s === 'calling' ? { s: 'ended' } : c));
          }
        },
        onOpen: () => { streamActive.current = false; pendingError.current = false; },
        onPeer: (joined) => {
          streamActive.current = false;
          if (joined) pendingError.current = false;
        },
        onFrame: (f: DecodedFrame) => {
          const canvas = canvasRef.current;
          if (!canvas) return;
          if (canvas.width !== f.width || canvas.height !== f.height) {
            canvas.width = f.width;
            canvas.height = f.height;
          }
          canvas.getContext('2d')?.drawImage(f.image, 0, 0, f.width, f.height);
          if (!hasFrameRef.current) {
            hasFrameRef.current = true;
            if (mountedRef.current) setHasFrame(true);
          }
          const h = hookRef.current;
          if (h) {
            h.frames = client.stats.frames + 1;
            h.lastFrameAt = Date.now();
            if (h.firstFrameMs == null) h.firstFrameMs = h.lastFrameAt - startedAt;
          }
        },
        onAudio: (mulaw) => {
          voice.play(mulaw);
          const h = hookRef.current;
          if (h) { h.rxAudioFrames = client.stats.rxAudioFrames; h.rxAudioRms = voice.rxRms; }
        },
        onMessage: (m) => {
          switch (m.t) {
            case 'info':
              onInfo(m as SupportInfo);
              break;
            case 'error':
              if (m.what === 'screen') {
                streamActive.current = false;
                pendingError.current = true;
                if (mountedRef.current) setScreenError(typeof m.detail === 'string' && m.detail ? m.detail : 'error');
              }
              break;
            case 'chat':
              if (typeof m.s === 'string' && m.s) {
                pushChat({ from: 'device', text: m.s, at: Date.now() });
                if (mountedRef.current && (!chatSeen.current || document.hidden)) setUnread((u) => u + 1);
              }
              break;
            case 'call.state':
              if (mountedRef.current) onCall(m);
              break;
            case 'call.stats': {
              const stats = m as CallStats;
              if (hookRef.current) hookRef.current.callStats = stats;
              if (mountedRef.current) setCallStats(stats);
              break;
            }
            case 'clip': {
              const s = typeof m.s === 'string' ? m.s : '';
              const source = typeof m.source === 'string' ? m.source : 'none';
              if (!mountedRef.current) break;
              setClipBack({ s, source });
              setClipNote(null);
              if (s) {
                // Only allowed while this tab has the focus and the browser grants it; the text stays on screen anyway.
                try {
                  void navigator.clipboard?.writeText(s).then(
                    () => { if (mountedRef.current) setClipNote('Copiado al portapapeles de este equipo.'); },
                    () => undefined,
                  );
                } catch { /* no clipboard API here */ }
              }
              break;
            }
            case 'clip.ok':
              if (mountedRef.current) {
                setClipNote(m.ok === true ? 'El texto quedó en el portapapeles del teléfono.' : 'El teléfono no pudo guardar el texto en su portapapeles.');
              }
              break;
            case 'file.error':
              if (mountedRef.current) setFileNote(typeof m.detail === 'string' && m.detail ? m.detail : 'Error con el archivo.');
              break;
            default:
          }
        },
      });

      clientRef.current = client;
      voiceRef.current = voice;
      const hook: DcSupportHook = {
        state: 'connecting', info: null, frames: 0, firstFrameMs: null, lastFrameAt: 0, callState: 'idle',
        callStats: null, txAudioFrames: 0, rxAudioFrames: 0, rxAudioRms: 0, chat: [],
        send: (obj) => client.sendObject(obj),
      };
      hookRef.current = hook;
      window.__dcSupport = hook;
      client.connect();
    }

    const onPageHide = () => teardown(true);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      mountedRef.current = false;
      window.removeEventListener('pagehide', onPageHide);
      disposeTimer.current = setTimeout(() => teardown(false), 80);
    };
    // One session per mount: the panel remounts this component (key = session) for a new one.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A clock for the timers and the status line, and the counters of the test hook.
  useEffect(() => {
    if (cstate === 'ended') return;
    const t = setInterval(() => { setNow(Date.now()); syncHook(); }, 1000);
    return () => clearInterval(t);
  }, [cstate, syncHook]);

  // --- what the screen area shows ---------------------------------------------------------------------------------
  const builtin = info?.screen === 'ready' && !screenError;
  const mode: ScreenMode = builtin ? 'builtin' : altScreen ? 'alt' : 'none';
  const canControl = !viewOnly && builtin && cstate !== 'ended';
  const canControlRef = useRef(canControl);
  canControlRef.current = canControl;
  const live = cstate === 'live';
  const over = cstate === 'ended';

  useEffect(() => { onScreenMode?.(mode); }, [mode, onScreenMode]);
  useEffect(() => { if (!builtin) stopRecording(); }, [builtin, stopRecording]);

  // --- chat -------------------------------------------------------------------------------------------------------
  useEffect(() => {
    const el = chatListRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [chat, chatOpen]);

  useEffect(() => {
    if (chatOpen) setUnread(0);
    const onVisible = () => { if (!document.hidden && chatSeen.current) setUnread(0); };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [chatOpen]);

  function sendChat() {
    const text = draft.trim().slice(0, MAX_TEXT);
    if (!text) return;
    if (!send('chat', { s: text })) {
      toast.push('err', 'No se envió', 'La sesión no está conectada.');
      return;
    }
    const line: ChatLine = { from: 'console', text, at: Date.now() };
    hookRef.current?.chat.push({ from: 'console', text });
    setChat((c) => [...c, line]);
    setDraft('');
  }

  // --- keys, text, pointer ----------------------------------------------------------------------------------------
  const flushText = useCallback(() => {
    if (textTimer.current) { clearTimeout(textTimer.current); textTimer.current = null; }
    const s = textBuf.current;
    textBuf.current = '';
    if (s) clientRef.current?.send('text', { s: s.slice(0, MAX_TEXT) });
  }, []);

  const key = useCallback((k: SupportKey) => {
    flushText();
    clientRef.current?.send('key', { k });
  }, [flushText]);

  function onKeyDown(e: ReactKeyboardEvent<HTMLCanvasElement>) {
    if (!canControl || e.ctrlKey || e.metaKey || e.altKey || e.nativeEvent.isComposing) return;
    // Text still waiting in the batch goes first, so the keys act on what was typed before them.
    if (e.key === 'Backspace') { e.preventDefault(); flushText(); key('backspace'); return; }
    if (e.key === 'Enter') { e.preventDefault(); flushText(); key('enter'); return; }
    if (e.key === 'Escape') { e.preventDefault(); flushText(); key('back'); return; }
    if (e.key.length === 1 || [...e.key].length === 1) {
      e.preventDefault(); // the space bar would scroll the page
      textBuf.current += e.key;
      if (!textTimer.current) textTimer.current = setTimeout(flushText, TEXT_BATCH_MS);
    }
  }

  function onPaste(e: ReactClipboardEvent<HTMLCanvasElement>) {
    if (!canControl) return;
    const s = e.clipboardData?.getData('text') ?? '';
    if (!s) return;
    e.preventDefault();
    flushText();
    for (let i = 0; i < s.length && i < MAX_TEXT * 5; i += MAX_TEXT) send('text', { s: s.slice(i, i + MAX_TEXT) });
  }

  const fraction = (clientX: number, clientY: number): [number, number] => {
    const r = canvasRef.current?.getBoundingClientRect();
    if (!r || r.width <= 0 || r.height <= 0) return [0, 0];
    return [clamp01((clientX - r.left) / r.width), clamp01((clientY - r.top) / r.height)];
  };

  function onPointerDown(e: ReactPointerEvent<HTMLCanvasElement>) {
    try { e.currentTarget.focus({ preventScroll: true }); } catch { /* not focusable right now */ }
    if (!canControl || (e.pointerType === 'mouse' && e.button !== 0)) return;
    if (gesture.current?.timer) clearTimeout(gesture.current.timer);
    try { e.currentTarget.setPointerCapture(e.pointerId); } catch { /* the pointer is already gone */ }
    const [x, y] = fraction(e.clientX, e.clientY);
    const g = {
      id: e.pointerId, x0: e.clientX, y0: e.clientY, t0: performance.now(), pts: [[x, y, 0]] as SwipePoint[],
      moved: false, done: false, lastT: 0, timer: null as ReturnType<typeof setTimeout> | null,
    };
    g.timer = setTimeout(() => {
      if (gesture.current !== g || g.moved || g.done) return;
      g.done = true; // held still: a long press, sent while the button is still down
      clientRef.current?.sendText(tapMessage(x, y, true));
    }, LONG_PRESS_MS);
    gesture.current = g;
  }

  function onPointerMove(e: ReactPointerEvent<HTMLCanvasElement>) {
    const g = gesture.current;
    if (!g || g.done || g.id !== e.pointerId) return;
    if (!g.moved && Math.hypot(e.clientX - g.x0, e.clientY - g.y0) > TAP_SLOP_PX) {
      g.moved = true;
      if (g.timer) { clearTimeout(g.timer); g.timer = null; }
    }
    if (!g.moved) return;
    const t = performance.now() - g.t0;
    if (t - g.lastT < MOVE_SAMPLE_MS) return;
    g.lastT = t;
    const [x, y] = fraction(e.clientX, e.clientY);
    g.pts.push([x, y, t]);
  }

  function onPointerUp(e: ReactPointerEvent<HTMLCanvasElement>) {
    const g = gesture.current;
    if (!g || g.id !== e.pointerId) return;
    gesture.current = null;
    if (g.timer) clearTimeout(g.timer);
    try { e.currentTarget.releasePointerCapture(e.pointerId); } catch { /* released already */ }
    if (g.done || !canControl) return;
    const [x, y] = fraction(e.clientX, e.clientY);
    const c = clientRef.current;
    if (!c) return;
    flushText();
    if (g.moved) {
      g.pts.push([x, y, Math.max(performance.now() - g.t0, g.lastT + 1)]);
      const m = swipeMessage(g.pts);
      if (m) c.sendText(m);
    } else {
      // A press that never moved is a tap, however long it took up to the long-press mark.
      c.sendText(tapMessage(g.pts[0][0], g.pts[0][1]));
    }
  }

  function onPointerCancel() {
    const g = gesture.current;
    gesture.current = null;
    if (g?.timer) clearTimeout(g.timer);
  }

  // The wheel scrolls the phone with a short vertical swipe. Native listener: React's wheel events are passive.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const onWheel = (e: WheelEvent) => {
      if (!canControlRef.current || e.ctrlKey || e.metaKey) return;
      e.preventDefault();
      const t = performance.now();
      if (!e.deltaY || t - lastWheel.current < WHEEL_GAP_MS) return;
      lastWheel.current = t;
      const r = canvas.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return;
      const x = clamp01((e.clientX - r.left) / r.width);
      const y = clamp01((e.clientY - r.top) / r.height);
      // Wheel down = see what is below = the finger moves up.
      const y0 = Math.min(0.85, Math.max(0.15, y));
      const y1 = clamp01(y0 - Math.sign(e.deltaY) * 0.22);
      clientRef.current?.sendText(message('swipe', { pts: [[x, y0, 0], [x, (y0 + y1) / 2, 70], [x, y1, 140]] }));
    };
    canvas.addEventListener('wheel', onWheel, { passive: false });
    return () => canvas.removeEventListener('wheel', onWheel);
  }, []);

  // --- bar actions ------------------------------------------------------------------------------------------------
  function changeQuality(q: QualityKey) {
    setQuality(q);
    send('screen.set', { maxDim: QUALITY[q].maxDim, quality: QUALITY[q].quality });
  }

  useEffect(() => {
    const onFs = () => setFull(document.fullscreenElement === rootRef.current && !!rootRef.current);
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);

  function toggleFullscreen() {
    try {
      if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
      else void rootRef.current?.requestFullscreen?.().catch(() => undefined);
    } catch { /* fullscreen is not available here */ }
  }

  function capture() {
    const canvas = canvasRef.current;
    if (!canvas || !hasFrame) return;
    try {
      canvas.toBlob((b) => { if (b) saveBlob(b, `pantalla-${device.number}-${stamp()}.png`); }, 'image/png');
    } catch (e) {
      toast.push('err', 'No se pudo capturar', e instanceof Error ? e.message : '');
    }
  }

  function toggleRecording() {
    if (recorderRef.current) { stopRecording(); return; }
    const canvas = canvasRef.current;
    if (!canvas || !canRecordCanvas) return;
    try {
      const stream = canvas.captureStream(12);
      const mime = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'].find((m) => {
        try { return MediaRecorder.isTypeSupported(m); } catch { return false; }
      });
      const rec = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
      const chunks: Blob[] = [];
      rec.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data); };
      rec.onstop = () => {
        try { stream.getTracks().forEach((t) => t.stop()); } catch { /* stopped */ }
        if (chunks.length) saveBlob(new Blob(chunks, { type: 'video/webm' }), `sesion-${device.number}-${stamp()}.webm`);
      };
      rec.start(1000);
      recorderRef.current = rec;
      setRecording(true);
    } catch (e) {
      toast.push('err', 'No se pudo grabar', e instanceof Error ? e.message : '');
    }
  }

  async function deviceAction(k: 'reboot' | 'lock') {
    if (k === 'reboot' && !window.confirm('¿Reiniciar el teléfono ahora? La sesión se corta mientras vuelve a encender.')) return;
    send('action', { k }); // the audit line of the session; the command below is what acts
    try {
      await queueCommand(device.number, { type: `device.${k}`, requiresCapability: `device.${k}` });
      toast.push('ok', k === 'reboot' ? 'Reinicio enviado' : 'Bloqueo enviado', 'El teléfono lo ejecuta en cuanto lo recibe.');
    } catch (e) {
      toast.push('err', 'No se pudo enviar', e instanceof Error ? e.message : '');
    }
  }

  async function askAuthorization() {
    try {
      await queueCommand(device.number, { type: 'remote.inputSetup' });
      setAuthSent(true);
      toast.push('ok', 'Solicitud enviada', 'En el teléfono se abre el ajuste para activar «Soporte remoto DallyControl».');
    } catch (e) {
      toast.push('err', 'No se pudo enviar', e instanceof Error ? e.message : '');
    }
  }

  function endSession() {
    const c = clientRef.current;
    const wasOpen = !!c && c.state !== 'ended';
    tornDownRef.current = true;
    try { c?.end('console'); } catch { /* closing anyway */ }
    try { voiceRef.current?.stop(); } catch { /* closing anyway */ }
    stopRecording();
    if (hookRef.current) hookRef.current.state = 'ended';
    if (wasOpen) void stopSupportSession(device.number).catch(() => undefined);
    onClosed();
  }

  // --- voice ------------------------------------------------------------------------------------------------------
  const inCall = call.s === 'calling' || call.s === 'ringing' || call.s === 'active';

  function startCall() {
    const voice = voiceRef.current;
    if (!voice || inCall) return;
    if (!send('call.start', testTone() ? { testTone: true } : {})) {
      toast.push('err', 'No se pudo llamar', 'La sesión no está conectada.');
      return;
    }
    selfHangup.current = false;
    if (hookRef.current) hookRef.current.callState = 'calling';
    setCall({ s: 'calling' });
    setCallStats(null);
    setMuted(false);
    setMicNote(null);
    voice.muted = false;
    // From this click: browsers only open audio on a gesture. Frames go out once the phone says "active".
    void voice.start().then(() => { if (mountedRef.current) setMicNote(voice.micError); });
  }

  function hangUp() {
    selfHangup.current = true;
    send('call.end');
    voiceRef.current?.stop();
    if (hookRef.current) hookRef.current.callState = 'ended';
    setCall({ s: 'idle' });
  }

  function toggleMute() {
    const next = !muted;
    voiceRef.current?.setMuted(next);
    setMuted(next);
  }

  // --- clipboard --------------------------------------------------------------------------------------------------
  function clipSend() {
    if (!clipDraft) return;
    setClipNote(send('clip.set', { s: clipDraft }) ? 'Enviando…' : 'La sesión no está conectada.');
  }

  function clipFetch() {
    setClipBack(null);
    setClipNote(send('clip.get') ? 'Pidiendo el texto al teléfono…' : 'La sesión no está conectada.');
  }

  async function clipCopy() {
    if (!clipBack?.s) return;
    try {
      await navigator.clipboard.writeText(clipBack.s);
      setClipNote('Copiado al portapapeles de este equipo.');
    } catch {
      setClipNote('El navegador no dejó copiar: selecciona el texto y cópialo a mano.');
    }
  }

  // --- files ------------------------------------------------------------------------------------------------------
  async function sendFile(file: File | undefined) {
    const c = clientRef.current;
    if (!file || !c) return;
    setFileNote(null);
    setUpload({ name: file.name, sent: 0, total: file.size });
    try {
      const path = await c.upload(file, (sent, total) => {
        if (mountedRef.current) setUpload((u) => (u ? { ...u, sent, total } : u));
      });
      if (mountedRef.current) setUpload({ name: file.name, sent: file.size, total: file.size, path });
    } catch (e) {
      if (mountedRef.current) setUpload({ name: file.name, sent: 0, total: file.size, error: e instanceof Error ? e.message : 'error' });
    } finally {
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  async function browse(path?: string) {
    const c = clientRef.current;
    if (!c) return;
    setListBusy(true);
    setFileNote(null);
    try {
      const l = await c.list(path);
      if (mountedRef.current) setListing(l);
    } catch (e) {
      if (mountedRef.current) setFileNote(e instanceof Error ? e.message : 'No se pudo listar.');
    } finally {
      if (mountedRef.current) setListBusy(false);
    }
  }

  async function fetchEntry(entry: FileEntry) {
    const c = clientRef.current;
    if (!c) return;
    setFileNote(null);
    setDownload({ name: entry.name, received: 0, total: entry.size });
    try {
      const { name, blob } = await c.fetchFile(entry.path, (received, total) => {
        if (mountedRef.current) setDownload({ name: entry.name, received, total: total > 0 ? total : entry.size });
      });
      saveBlob(blob, name || entry.name);
    } catch (e) {
      if (mountedRef.current) setFileNote(`No se pudo traer «${entry.name}»: ${e instanceof Error ? e.message : 'error'}`);
    } finally {
      if (mountedRef.current) setDownload(null);
    }
  }

  // --- render -----------------------------------------------------------------------------------------------------
  const client = clientRef.current;
  const stats = client?.stats;
  const waited = Math.max(0, Math.round((now - startedAt) / 1000));
  const screenState = screenError && ['needsAuthorization', 'disabled', 'unsupported'].includes(screenError)
    ? screenError : info?.screen;
  const parentPath = listing && listing.path ? listing.path.split('/').slice(0, -1).join('/') : null;

  const statusParts: string[] = [];
  if (info) statusParts.push(`${info.model ?? 'Teléfono'} · Android ${info.release ?? '?'}${info.agent ? ` · agente ${info.agent}` : ''}`);
  if (builtin && stats) {
    if (stats.firstFrameMs != null) statusParts.push(`Primera imagen en ${decimal(stats.firstFrameMs / 1000)} s`);
    statusParts.push(`${decimal(client?.fps(now) ?? 0)} fps`);
    statusParts.push(`${stats.frames} cuadros`);
    if (stats.reconnects > 0) statusParts.push(`${stats.reconnects} reconexiones`);
  }
  if (call.s === 'active' && stats) {
    statusParts.push(`Voz: enviados ${stats.txAudioFrames} · recibidos ${stats.rxAudioFrames}`
      + (callStats ? ` · teléfono tx ${callStats.tx ?? 0} / rx ${callStats.rx ?? 0}, nivel ${callStats.micRms ?? 0}` : ''));
  }

  return (
    <div className={`ss${full ? ' ss-full' : ''}${over ? ' ss-over' : ''}`} ref={rootRef} data-testid="support-session">
      {over && (
        <div className="banner banner-warn ss-ended" data-testid="support-ended">
          <span><strong>Sesión terminada.</strong> {END_TEXT[endReason ?? ''] ?? `Motivo: ${endReason}.`}</span>
          <button className="btn btn-sm" onClick={onClosed}>Cerrar</button>
        </div>
      )}

      {!over && (
        <div className="ss-bar" role="toolbar" aria-label="Sesión de soporte">
          <span className={`chip ${live ? 'tone-ok' : 'tone-warn'}`}>{viewOnly ? 'Viendo' : 'Controlando'}</span>

          {canControl && (
            <div className="ss-group">
              <button className="btn btn-sm" data-testid="support-key-back" onClick={() => key('back')}>Atrás</button>
              <button className="btn btn-sm" data-testid="support-key-home" onClick={() => key('home')}>Inicio</button>
              <button className="btn btn-sm" data-testid="support-key-recents" onClick={() => key('recents')}>Recientes</button>
              <button className="btn btn-sm" onClick={() => key('notifications')}>Notificaciones</button>
              <button className="btn btn-sm" title="Enciende la pantalla del teléfono" onClick={() => send('wake')}>Encender pantalla</button>
            </div>
          )}

          <div className="ss-group">
            <button className="btn btn-sm" disabled={!live} onClick={() => void deviceAction('lock')}>Bloquear</button>
            <button className="btn btn-sm" disabled={!live} onClick={() => void deviceAction('reboot')}>Reiniciar</button>
          </div>

          {builtin && (
            <div className="ss-group">
              <label className="ss-pick">
                <span>Calidad</span>
                <select className="sel" aria-label="Calidad de la imagen" value={quality}
                        onChange={(e) => changeQuality(e.target.value as QualityKey)}>
                  {(Object.keys(QUALITY) as QualityKey[]).map((q) => <option key={q} value={q}>{QUALITY[q].label}</option>)}
                </select>
              </label>
              <label className="ss-pick">
                <span>Escala</span>
                <select className="sel" aria-label="Escala de la pantalla" value={scale}
                        onChange={(e) => setScale(e.target.value === 'real' ? 'real' : 'fit')}>
                  <option value="fit">Ajustar</option>
                  <option value="real">Tamaño real</option>
                </select>
              </label>
              <button className="btn btn-sm" disabled={!hasFrame} onClick={capture}>Captura</button>
              {canRecordCanvas && (
                <button className={`btn btn-sm${recording ? ' ss-rec' : ''}`} disabled={!hasFrame} onClick={toggleRecording}>
                  {recording ? <span key="stop">Detener grabación</span> : <span key="rec">Grabar</span>}
                </button>
              )}
            </div>
          )}

          <div className="ss-group">
            <button className="btn btn-sm" onClick={toggleFullscreen}>
              {full ? <span key="exit">Salir de pantalla completa</span> : <span key="enter">Pantalla completa</span>}
            </button>
            <button className={`btn btn-sm${tool === 'clip' ? ' on' : ''}`} aria-pressed={tool === 'clip'}
                    onClick={() => setTool(tool === 'clip' ? null : 'clip')}>Portapapeles</button>
            <button className={`btn btn-sm${tool === 'files' ? ' on' : ''}`} aria-pressed={tool === 'files'}
                    onClick={() => setTool(tool === 'files' ? null : 'files')}>Archivos</button>
            <button className={`btn btn-sm${chatOpen ? ' on' : ''}`} aria-pressed={chatOpen} onClick={() => setChatOpen((v) => !v)}>
              Chat{unread > 0 && <span className="ss-badge" data-testid="support-chat-unread">{unread}</span>}
            </button>
            <button className="btn btn-sm" data-testid="support-call" disabled={!live || inCall} onClick={startCall}>Llamar</button>
          </div>

          <div className="ss-spacer" />
          <button className="btn btn-sm btn-danger" data-testid="support-end" onClick={endSession}>Terminar sesión</button>
        </div>
      )}

      {!over && cstate !== 'live' && (
        <div className="ss-wait">
          <span className="spin" />
          <span>
            {cstate === 'connecting'
              ? <span key="c">Conectando con el servidor…</span>
              : <span key="w">Esperando a que el teléfono se conecte… {waited} s{!start.online && ' (no estaba en línea: entra en su próxima conexión, puede tardar unos minutos si está bloqueado)'}</span>}
          </span>
        </div>
      )}

      <div className="ss-main">
        <div className={`ss-stage ss-scale-${scale}`}>
          <div className="ss-screen" hidden={!builtin}>
            <canvas
              ref={canvasRef}
              className={`ss-canvas${hasFrame ? '' : ' ss-blank'}${canControl ? ' ss-control' : ''}`}
              data-testid="support-viewer"
              tabIndex={0}
              aria-label={`Pantalla de ${device.number}`}
              onKeyDown={onKeyDown}
              onPaste={onPaste}
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              onPointerCancel={onPointerCancel}
              onContextMenu={(e) => e.preventDefault()}
            />
            {builtin && !hasFrame && (
              <div className="ss-first"><span className="spin" /> Esperando la primera imagen…</div>
            )}
          </div>

          {!builtin && altScreen}

          {!builtin && !altScreen && info && (
            <div className="ss-noscreen" data-testid="support-noscreen">
              {screenState === 'needsAuthorization' && (
                <>
                  <h3>Falta autorización en el teléfono</h3>
                  <p>
                    Una sola vez: en el teléfono, activar «Soporte remoto DallyControl» en Accesibilidad. Después las sesiones
                    no requieren a nadie en el teléfono. Mientras tanto el chat, la llamada y los archivos ya funcionan.
                  </p>
                  <button className="btn btn-primary" disabled={over} onClick={() => void askAuthorization()}>
                    Pedir autorización en el teléfono
                  </button>
                  {authSent && <p className="muted">Enviado. La pantalla aparece aquí sola en cuanto la activen.</p>}
                </>
              )}
              {screenState === 'disabled' && (
                <>
                  <h3>Pantalla apagada por la política</h3>
                  <p>La política de este teléfono no usa la pantalla del agente. El chat, la llamada y los archivos sí funcionan.</p>
                </>
              )}
              {screenState === 'unsupported' && (
                <>
                  <h3>Este teléfono no puede mostrar su pantalla</h3>
                  <p>Su versión de Android no lo permite (hace falta Android 7 o superior). El chat, la llamada y los archivos sí funcionan.</p>
                </>
              )}
              {screenState !== 'needsAuthorization' && screenState !== 'disabled' && screenState !== 'unsupported' && (
                <>
                  <h3>No se pudo mostrar la pantalla</h3>
                  <p>
                    {screenError === 'capture refused'
                      ? 'El teléfono no aceptó la captura de pantalla.'
                      : `El teléfono respondió: ${screenError ?? screenState ?? 'sin detalle'}.`}
                  </p>
                  <button className="btn btn-primary" disabled={!live} onClick={startScreen}>Reintentar</button>
                </>
              )}
            </div>
          )}

          {!builtin && !altScreen && !info && !over && (
            <div className="ss-noscreen ss-idle"><p className="muted">La pantalla aparece aquí cuando el teléfono se conecte.</p></div>
          )}
        </div>

        <aside className="ss-side">
          {(call.s !== 'idle' || micNote) && (
            <div className={`ss-card ss-call ss-call-${call.s}`} data-testid="support-call-panel">
              <div className="ss-call-line">
                <strong data-testid="support-call-state">{callText(call)}</strong>
                {call.s === 'active' && call.since && <span className="ss-timer">{fmtTimer(now - call.since)}</span>}
              </div>
              {call.s === 'active' && call.remoteMuted && <p className="muted">El usuario silenció su micrófono.</p>}
              {inCall && micNote && <p className="ss-warn">{micNote}</p>}
              {inCall ? (
                <div className="ss-row">
                  <button className="btn btn-sm" data-testid="support-mute" onClick={toggleMute}>
                    {muted ? <span key="on">Activar micrófono</span> : <span key="off">Silenciar</span>}
                  </button>
                  <button className="btn btn-sm btn-danger" data-testid="support-hangup" onClick={hangUp}>Colgar</button>
                </div>
              ) : (
                <div className="ss-row">
                  <button className="btn btn-sm btn-ghost" onClick={() => { setCall({ s: 'idle' }); setMicNote(null); }}>Cerrar</button>
                </div>
              )}
            </div>
          )}

          {tool === 'clip' && (
            <div className="ss-card" data-testid="support-clip-panel">
              <div className="ss-card-h">Portapapeles</div>
              {!viewOnly && (
                <>
                  <textarea className="input ss-text" rows={3} placeholder="Texto para el teléfono" aria-label="Texto para el teléfono"
                            value={clipDraft} onChange={(e) => setClipDraft(e.target.value)} />
                  <div className="ss-row">
                    <button className="btn btn-sm" disabled={!live || !clipDraft} onClick={clipSend}>Enviar texto al teléfono</button>
                  </div>
                </>
              )}
              <div className="ss-row">
                <button className="btn btn-sm" disabled={!live} onClick={clipFetch}>Traer del teléfono</button>
                {clipBack?.s && <button className="btn btn-sm btn-ghost" onClick={() => void clipCopy()}>Copiar</button>}
              </div>
              {clipBack && (clipBack.s ? (
                <>
                  <pre className="ss-clip">{clipBack.s}</pre>
                  {clipBack.source === 'field' && <p className="muted">Es el texto del campo enfocado en el teléfono (Android no deja leer su portapapeles desde el fondo).</p>}
                </>
              ) : (
                <p className="muted">El teléfono no tiene texto que traer (Android 10 o superior solo deja leer el portapapeles a la app en primer plano).</p>
              ))}
              {clipNote && <p className="muted">{clipNote}</p>}
            </div>
          )}

          {tool === 'files' && (
            <div className="ss-card" data-testid="support-files-panel">
              <div className="ss-card-h">Archivos</div>
              <div className="ss-row">
                <label className={`btn btn-sm${live && !(upload && !upload.path && !upload.error) ? '' : ' ss-disabled'}`}>
                  Enviar archivo
                  <input ref={fileInputRef} type="file" hidden data-testid="support-file-input"
                         disabled={!live || !!(upload && !upload.path && !upload.error)}
                         onChange={(e) => void sendFile(e.target.files?.[0])} />
                </label>
                <button className="btn btn-sm" disabled={!live || listBusy} onClick={() => void browse()}>Explorar el teléfono</button>
              </div>
              {upload && (
                <div className="ss-xfer">
                  <span className="ss-xfer-name">{upload.name}</span>
                  {upload.error ? <span className="ss-warn">No se envió: {upload.error}</span>
                    : upload.path ? <span data-testid="support-file-saved">Guardado en {upload.path}</span>
                      : (
                        <>
                          <progress max={Math.max(1, upload.total)} value={upload.sent} />
                          <span className="muted">{fmtSize(upload.sent)} de {fmtSize(upload.total)}</span>
                        </>
                      )}
                </div>
              )}
              {download && (
                <div className="ss-xfer">
                  <span className="ss-xfer-name">Trayendo {download.name}</span>
                  <progress max={Math.max(1, download.total)} value={Math.min(download.received, Math.max(1, download.total))} />
                  <span className="muted">{fmtSize(download.received)}{download.total > 0 ? ` de ${fmtSize(download.total)}` : ''}</span>
                </div>
              )}
              {fileNote && <p className="ss-warn">{fileNote}</p>}
              {listing && (
                <div className="ss-files">
                  {listing.limited && (
                    <p className="muted">Sin acceso a todos los archivos: solo fotos, videos, audio y la carpeta Soporte</p>
                  )}
                  <div className="ss-path">
                    <span title={listing.path || 'Almacenamiento'}>/{listing.path}</span>
                    {parentPath != null && (
                      <button className="btn btn-sm btn-ghost" disabled={listBusy} onClick={() => void browse(parentPath || undefined)}>Subir</button>
                    )}
                  </div>
                  <ul>
                    {listing.entries.length === 0 && <li className="muted">Carpeta vacía.</li>}
                    {listing.entries.map((entry) => (
                      <li key={entry.path}>
                        {entry.dir ? (
                          <button className="ss-entry ss-dir" disabled={listBusy} onClick={() => void browse(entry.path)}>
                            <span className="ss-entry-n">{entry.name}/</span>
                          </button>
                        ) : (
                          <button className="ss-entry" disabled={!!download || !live} title="Descargar" onClick={() => void fetchEntry(entry)}>
                            <span className="ss-entry-n">{entry.name}</span>
                            <span className="muted">{fmtSize(entry.size)}</span>
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}

          <div className="ss-card ss-chat" hidden={!chatOpen}>
            <div className="ss-card-h">Chat con el usuario</div>
            <div className="ss-chat-list" ref={chatListRef} data-testid="support-chat-list" aria-live="polite">
              {chat.length === 0 && <p className="muted">Lo que escribas aparece en el teléfono, y sus respuestas aquí.</p>}
              {chat.map((line, i) => (
                <div key={i} className={`ss-msg ss-msg-${line.from}`}>
                  <span className="ss-msg-h">{line.from === 'console' ? 'Soporte' : 'Usuario'} · {fmtClock(line.at)}</span>
                  <span className="ss-msg-t">{line.text}</span>
                </div>
              ))}
            </div>
            <form className="ss-chat-form" onSubmit={(e) => { e.preventDefault(); sendChat(); }}>
              <input className="input" data-testid="support-chat-input" placeholder="Escribe un mensaje" aria-label="Mensaje"
                     maxLength={MAX_TEXT} disabled={over} value={draft} onChange={(e) => setDraft(e.target.value)} />
              <button type="submit" className="btn btn-sm btn-primary" data-testid="support-chat-send" disabled={over || !draft.trim()}>
                Enviar
              </button>
            </form>
          </div>
        </aside>
      </div>

      <div className="ss-status" data-testid="support-status">
        {statusParts.length ? statusParts.join(' · ') : 'Sin datos del teléfono todavía.'}
      </div>
    </div>
  );
}
