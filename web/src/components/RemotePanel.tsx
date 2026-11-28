import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getRemoteStatus, startRemoteSession, stopRemoteSession, viewerUrl,
  type RemoteSession, type RemoteStatus,
} from '../api/remote';
import { listCommandHistory, queueCommand, setupRemoteSupport } from '../api/commands';
import { errorCode } from '../api/client';
import {
  getSupportStatus, listDeviceSupportSessions, startSupportSession,
  type SupportSessionRow, type SupportStart, type SupportStatus,
} from '../api/support';
import { useToast } from '../ui/toast';
import { NavBar } from './NavBar';
import { KioskToggle } from './KioskToggle';
import { SupportSession, type ScreenMode } from './SupportSession';
import { SupportSessionsTable } from './SupportSessionsTable';

type Device = { number: string };

/** Plain-language reasons for the device's remote-control errors, with what to do. */
function explainRemote(detail?: string | null): string | undefined {
  if (!detail) return undefined;
  if (/input service is off/i.test(detail)) {
    return 'El teléfono no tiene activado el servicio de entrada de droidVNC-NG (accesibilidad) ni la «Captura de pantalla». '
      + 'Vuelve a pasar el inscriptor de Windows por ese teléfono (Inscribir → Sin formatear; no borra nada ni lo vuelve a inscribir).';
  }
  if (/did not confirm its start/i.test(detail)) {
    return 'droidVNC-NG no arrancó a tiempo en el teléfono. Si es un Xiaomi, el sistema pudo cerrarlo en segundo plano: '
      + 'inténtalo de nuevo con el teléfono desbloqueado.';
  }
  if (/Not allowed to start service/i.test(detail)) {
    return 'El sistema del teléfono (Xiaomi/HyperOS) cerró el soporte remoto en segundo plano. Vuelve a pasar el inscriptor de Windows '
      + '(activa «Inicio automático» y batería sin restricciones), o en el teléfono: Ajustes → Apps → droidVNC-NG → Inicio automático.';
  }
  if (/could not reach the repeater/i.test(detail)) {
    return 'El teléfono no alcanzó el servidor de control remoto: revisa su conexión a internet e inténtalo de nuevo.';
  }
  return detail;
}

/** Where a session is: nothing yet, waiting for the device to pick up the command, live, or failed. */
type Phase =
  | { kind: 'idle' }
  | { kind: 'waiting'; session: RemoteSession; since: number }
  | { kind: 'live'; session: RemoteSession }
  | { kind: 'popped'; session: RemoteSession }
  | { kind: 'failed'; message: string };

const POLL_MS = 2000;
/** How often the open panel re-reads the device's remote status. */
const STATUS_POLL_MS = 15000;
/** How long "Set Always-on" waits for the device to confirm before saying it will switch later. */
const SWITCH_LIMIT_MS = 2 * 60 * 1000;
/** The device answers remote.vnc.start once it is connected to the repeater; give up after this. */
const WAIT_LIMIT_MS = 6 * 60 * 1000;

/** An open support session (ADR 0012) and whether the legacy droidVNC viewer stands in for its screen. */
interface OpenSupport { start: SupportStart; startedAt: number; withLegacy: boolean }

/**
 * Remote support of one device. Agents that speak the session channel (ADR 0012) get the built-in session:
 * screen, touch, voice, chat and files over one socket (SupportSession). Older agents keep the legacy engine
 * (ADR 0010): queue a session, wait for the device to connect to the repeater, then show the noVNC viewer inline
 * (or in its own tab); that one is encrypted end to end when the agent tunnels it over HTTPS. When a new agent's
 * own screen is not authorised yet but droidVNC-NG is there, both run: the legacy viewer is the session's screen.
 */
export function RemotePanel({ device }: { device: Device }) {
  const toast = useToast();
  const [status, setStatus] = useState<RemoteStatus | null>(null);
  const [sup, setSup] = useState<SupportStatus | null>(null);
  const [sess, setSess] = useState<OpenSupport | null>(null);
  const [statusErr, setStatusErr] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  const frame = useRef<HTMLIFrameElement>(null);

  const refreshStatus = useCallback(async () => {
    // Both at once, so the panel never shows the legacy texts for a moment on a phone with the new channel.
    // A server without the support API (or an error there) just leaves the legacy panel.
    const [legacy, support] = await Promise.allSettled([getRemoteStatus(device.number), getSupportStatus(device.number)]);
    setSup(support.status === 'fulfilled' ? support.value : null);
    if (legacy.status === 'fulfilled') {
      setStatus(legacy.value);
      setStatusErr(null);
    } else {
      const e: unknown = legacy.reason;
      setStatusErr(e instanceof Error ? e.message : 'No se pudo leer el dispositivo');
    }
  }, [device.number]);

  // Keep the panel current while it is open (power mode, capabilities): a light poll, self-scheduling.
  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const loop = async () => {
      await refreshStatus();
      if (on) t = setTimeout(() => void loop(), STATUS_POLL_MS);
    };
    void loop();
    return () => { on = false; clearTimeout(t); };
  }, [refreshStatus]);

  // After "Set Always-on": follow the device until it reports always-on (it applies on its next check-in).
  const [switching, setSwitching] = useState(false);
  useEffect(() => {
    if (!switching) return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const since = Date.now();
    const tick = async () => {
      const s = await getRemoteStatus(device.number).catch(() => null);
      if (!on) return;
      if (s) setStatus(s);
      if (s?.powerMode === 'alwaysOn') {
        setSwitching(false);
        toast.push('ok', 'Siempre conectado activo', 'El dispositivo ya responde al instante a las sesiones remotas.');
        return;
      }
      if (Date.now() - since > SWITCH_LIMIT_MS) {
        setSwitching(false);
        toast.push('err', 'Sigue en ahorro de batería',
          'El dispositivo aún no confirma; cambiará en su próxima conexión (unos minutos si está bloqueado).');
        return;
      }
      t = setTimeout(() => void tick(), POLL_MS);
    };
    void tick();
    return () => { on = false; clearTimeout(t); };
  }, [switching, device.number, toast]);

  // While waiting: follow the start command until the device reports it connected (or refused).
  useEffect(() => {
    if (phase.kind !== 'waiting') return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const tick = async () => {
      setNow(Date.now());
      const hist = await listCommandHistory(device.number).catch(() => []);
      if (!on) return;
      const cmd = hist.find((c) => String(c.id) === String(phase.session.commandId));
      if (cmd?.status === 'done') {
        setPhase({ kind: 'live', session: phase.session });
        return;
      }
      if (cmd && ['failed', 'unsupported', 'expired'].includes(cmd.status)) {
        setPhase({ kind: 'failed', message: explainRemote(cmd.detail) || `El dispositivo respondió "${cmd.status}".` });
        return;
      }
      if (Date.now() - phase.since > WAIT_LIMIT_MS) {
        setPhase({ kind: 'failed', message: 'El dispositivo no respondió en 6 minutos (¿sin conexión o dormido?).' });
        return;
      }
      t = setTimeout(() => void tick(), POLL_MS);
    };
    void tick();
    return () => { on = false; clearTimeout(t); };
  }, [phase, device.number]);

  // --- Never leave a session (or droidVNC's capture dialog) lingering on the device ---
  const sessionActive = phase.kind === 'live' || phase.kind === 'waiting';
  /** The legacy viewer is the screen of an open support session. */
  const combined = !!sess;
  /** The agent speaks the session channel (ADR 0012); otherwise everything below is the legacy engine. */
  const useSession = sup?.support?.session === true;
  const tier = status?.tier ?? sup?.vncTier ?? 'none';
  const available = tier === 'view' || tier === 'control';

  // End the session if the operator leaves this panel (switches tab / device / closes the detail).
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  useEffect(() => {
    const num = device.number;
    return () => {
      if (phaseRef.current.kind === 'live' || phaseRef.current.kind === 'waiting') {
        void stopRemoteSession(num).catch(() => undefined);
      }
    };
  }, [device.number]);

  // Force-end after 15 min of inactivity, and on tab/window close (best-effort keepalive POST).
  useEffect(() => {
    if (!sessionActive) return;
    const num = device.number;
    const IDLE_MS = 15 * 60 * 1000;
    let idle: ReturnType<typeof setTimeout> | undefined;
    const endIdle = () => {
      void stopRemoteSession(num).catch(() => undefined);
      setPhase({ kind: 'idle' });
      toast.push('ok', 'Sesión remota finalizada', 'Se cerró sola tras 15 minutos de inactividad.');
    };
    // Inside a support session the server's own idle rule ends it; the viewer follows the session.
    const arm = () => { clearTimeout(idle); if (!combined) idle = setTimeout(endIdle, IDLE_MS); };
    arm();
    window.addEventListener('pointerdown', arm);
    window.addEventListener('pointermove', arm, { passive: true });
    window.addEventListener('keydown', arm);
    const onUnload = () => {
      try {
        fetch(`/rest/private/agent/v1/devices/${encodeURIComponent(num)}/commands`, {
          method: 'POST', keepalive: true, credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ type: 'remote.vnc.stop' }),
        });
      } catch { /* best effort */ }
    };
    window.addEventListener('beforeunload', onUnload);
    window.addEventListener('pagehide', onUnload);
    return () => {
      clearTimeout(idle);
      window.removeEventListener('pointerdown', arm);
      window.removeEventListener('pointermove', arm);
      window.removeEventListener('keydown', arm);
      window.removeEventListener('beforeunload', onUnload);
      window.removeEventListener('pagehide', onUnload);
    };
  }, [sessionActive, combined, device.number, toast]);

  async function start(viewOnly: boolean) {
    setBusy(true);
    const startedAt = Date.now();
    try {
      if (useSession) {
        let opened: SupportStart | null = null;
        try {
          opened = await startSupportSession(device.number, viewOnly);
        } catch (e) {
          if (!available) throw e; // nothing to fall back to
        }
        if (opened) {
          // The agent's own screen is not authorised (or is off) but droidVNC-NG is there: it shows the screen.
          const withLegacy = sup?.readiness !== 'ready' && available;
          setSess({ start: opened, startedAt, withLegacy });
          if (!withLegacy) return;
          try {
            const session = await startRemoteSession(device.number, viewOnly);
            setPhase({ kind: 'waiting', session, since: Date.now() });
          } catch (e) {
            toast.push('err', 'Sin pantalla por droidVNC-NG', e instanceof Error ? e.message : '');
          }
          return;
        }
      }
      const session = await startRemoteSession(device.number, viewOnly);
      setPhase({ kind: 'waiting', session, since: Date.now() });
    } catch (e) {
      const m = e instanceof Error ? e.message : '';
      toast.push('err', 'No se pudo iniciar la sesión',
        errorCode(e) === 'error.agent.remote.unsupported' ? 'El agente de este teléfono no admite sesiones de soporte: actualízalo.' : m);
    } finally {
      setBusy(false);
    }
  }

  // --- Historial de sesiones (loaded when opened) ---
  const [histOpen, setHistOpen] = useState(false);
  const [history, setHistory] = useState<SupportSessionRow[] | null>(null);
  const [histErr, setHistErr] = useState<string | null>(null);
  const [histBusy, setHistBusy] = useState(false);
  const histOpenRef = useRef(false);
  histOpenRef.current = histOpen;
  const loadHistory = useCallback(async () => {
    if (!histOpenRef.current) return;
    setHistBusy(true);
    try {
      setHistory(await listDeviceSupportSessions(device.number, 50));
      setHistErr(null);
    } catch (e) {
      setHistErr(e instanceof Error ? e.message : 'No se pudo cargar el historial');
    } finally {
      setHistBusy(false);
    }
  }, [device.number]);
  useEffect(() => { if (histOpen) void loadHistory(); }, [histOpen, loadHistory]);
  // Another device in the same panel: its own session and log (the open one ends as its component unmounts).
  useEffect(() => { setSess(null); setSup(null); setHistory(null); setHistOpen(false); }, [device.number]);

  // The support session closed (ended, or the operator closed its panel): the legacy viewer goes with it.
  const closeSupport = useCallback(() => {
    const k = phaseRef.current.kind;
    if (k === 'live' || k === 'waiting') void stopRemoteSession(device.number).catch(() => undefined);
    setPhase({ kind: 'idle' });
    setSess(null);
    void refreshStatus();
    // The server writes the session's end as it closes: read the log a moment later.
    setTimeout(() => { void loadHistory(); }, 1200);
  }, [device.number, refreshStatus, loadHistory]);

  // The agent's own screen came up (authorised meanwhile): droidVNC-NG is no longer needed for this session.
  const onScreenMode = useCallback((mode: ScreenMode) => {
    if (mode !== 'builtin') return;
    const k = phaseRef.current.kind;
    if (k !== 'live' && k !== 'waiting') return;
    void stopRemoteSession(device.number).catch(() => undefined);
    setPhase({ kind: 'idle' });
  }, [device.number]);

  async function askAuthorization() {
    try {
      await queueCommand(device.number, { type: 'remote.inputSetup' });
      toast.push('ok', 'Solicitud enviada', 'En el teléfono se abre el ajuste para activar «Soporte remoto DallyControl».');
    } catch (e) {
      toast.push('err', 'No se pudo enviar', e instanceof Error ? e.message : '');
    }
  }

  async function stop() {
    setBusy(true);
    try {
      await stopRemoteSession(device.number);
      toast.push('ok', 'Sesión terminada', 'El dispositivo deja de compartir su pantalla.');
    } catch (e) {
      toast.push('err', 'No se pudo terminar la sesión', e instanceof Error ? e.message : '');
    } finally {
      setPhase({ kind: 'idle' });
      setBusy(false);
    }
  }

  async function setAlwaysOn() {
    setBusy(true);
    try {
      await queueCommand(device.number, {
        type: 'device.powerMode', requiresCapability: 'device.powerMode',
        payload: JSON.stringify({ mode: 'alwaysOn' }),
      });
      setSwitching(true);
    } catch (e) {
      toast.push('err', 'No se pudo cambiar el modo de conectividad', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  function fullscreen() {
    void frame.current?.requestFullscreen?.();
  }

  const [setupSent, setSetupSent] = useState(false);
  async function setupRemote() {
    try {
      await setupRemoteSupport(device.number);
      setSetupSent(true);
      toast.push('ok', 'Instalando el soporte remoto', 'El teléfono lo descarga y lo prepara solo.');
    } catch (e) {
      toast.push('err', 'No se pudo enviar', e instanceof Error ? e.message : '');
    }
  }
  async function enableControl() {
    try {
      await queueCommand(device.number, { type: 'remote.inputSetup' });
      toast.push('ok', 'Ajuste abierto en el teléfono', 'Quien tenga el teléfono debe activar droidVNC-NG en Accesibilidad.');
    } catch (e) {
      toast.push('err', 'No se pudo enviar', e instanceof Error ? e.message : '');
    }
  }

  const sessionOpen = phase.kind === 'waiting' || phase.kind === 'live' || phase.kind === 'popped' || combined;
  const readiness = useSession ? sup?.readiness : undefined;
  /** The legacy engine's own notices: for old agents, and while it stands in for the agent's screen. */
  const legacyNotes = !useSession || readiness !== 'ready';
  const powerMode = status?.powerMode ?? sup?.powerMode;

  // What the open support session shows as its screen while the agent's own is not ready.
  const legacyScreen = !sess?.withLegacy ? undefined
    : phase.kind === 'waiting' ? (
      <div className="ss-noscreen">
        <p><span className="spin" /> Esperando la pantalla por droidVNC‑NG… {Math.round((now - phase.since) / 1000)} s</p>
      </div>
    ) : phase.kind === 'live' ? (
      <div className="ss-legacy">
        <iframe
          ref={frame}
          className="rp-viewer"
          title={`Pantalla de ${device.number}`}
          src={viewerUrl(phase.session)}
          allow="fullscreen; clipboard-read; clipboard-write"
        />
        {!phase.session.viewOnly && (
          <NavBar
            frame={frame}
            onUnavailable={() => toast.push('err', 'Visor no conectado', 'Espera a que aparezca la pantalla y vuelve a intentarlo.')}
          />
        )}
      </div>
    ) : phase.kind === 'failed' ? (
      <div className="ss-noscreen">
        <h3>Sin pantalla por ahora</h3>
        <p>{phase.message}</p>
        <p className="muted">El chat, la llamada y los archivos siguen funcionando.</p>
      </div>
    ) : undefined;

  // #4: while the remote support installs, poll fast so it shows "listo" within seconds instead of up
  // to the 15 s status poll — the install itself is small (~6.5 MB); the wait was mostly this lag.
  useEffect(() => { if (available && setupSent) setSetupSent(false); }, [available, setupSent]);
  useEffect(() => {
    if (!setupSent || available) return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const tick = async () => { await refreshStatus(); if (on) t = setTimeout(() => void tick(), 3000); };
    t = setTimeout(() => void tick(), 3000);
    return () => { on = false; clearTimeout(t); };
  }, [setupSent, available, refreshStatus]);

  return (
    <div className="panel rp">
      {/* keep: tab bodies hide panel heads, but this one carries the encryption state */}
      <div className="panel-head keep">
        <h2 className="panel-title">Control remoto</h2>
        {useSession && readiness === 'ready' && <span className="chip tone-ok" data-testid="remote-readiness">Remoto listo</span>}
        {useSession && readiness === 'needsAuthorization' && <span className="chip tone-warn" data-testid="remote-readiness">Falta autorización</span>}
        {useSession && readiness === 'disabled' && <span className="chip tone-warn" data-testid="remote-readiness">Remoto apagado por la política</span>}
        {useSession && readiness === 'unsupported' && <span className="chip tone-warn" data-testid="remote-readiness">Sin pantalla del agente</span>}
        {status && available && legacyNotes && (
          <span className={`chip ${status.encrypted ? 'tone-ok' : 'tone-warn'}`}
                title={status.encrypted
                  ? 'Teléfono ↔ servidor viaja dentro del HTTPS del servidor (TLS).'
                  : 'Este agente se conecta directo al puerto del repetidor: solo el intercambio de la contraseña va protegido.'}>
            {status.encrypted ? 'Cifrado (TLS)' : 'Sin cifrar'}
          </span>
        )}
      </div>

      {statusErr && <div className="banner banner-alert">{statusErr}</div>}

      {/* Kiosk on/off from here: with the screen live, the change is seen right away. */}
      {phase.kind !== 'live' && !combined && <KioskToggle device={device} />}

      {useSession && readiness === 'needsAuthorization' && !combined && (
        <div className="banner banner-warn rp-power" data-testid="remote-authorize">
          <span>
            <strong>Falta autorización.</strong> Una sola vez: en el teléfono, activar «Soporte remoto DallyControl» en
            Accesibilidad. Después las sesiones no requieren a nadie en el teléfono.
          </span>
          <button className="btn btn-sm" disabled={busy} onClick={() => void askAuthorization()}>Pedir autorización en el teléfono</button>
        </div>
      )}

      {useSession && readiness === 'disabled' && !combined && (
        <div className="banner banner-warn">
          La política de este teléfono apagó la pantalla del agente (Políticas → Soporte remoto → Motor).
          {available ? ' La pantalla se muestra con droidVNC‑NG; ' : ' Sin pantalla: '}
          el chat, la llamada y los archivos funcionan igual.
        </div>
      )}

      {status && !useSession && !available && (
        <div className="rp-setup" data-testid="remote-setup">
          <p className="muted">
            Este dispositivo aún no tiene el soporte remoto (droidVNC‑NG). Se instala desde aquí, sin cable: el agente lo
            descarga de este servidor y lo deja listo. Necesita Android 7 o superior.
          </p>
          <button className="btn btn-primary" disabled={busy || setupSent} onClick={() => void setupRemote()}>
            {setupSent ? <span key="s">Instalando… (aparece aquí en cuanto termine)</span> : <span key="i">Instalar soporte remoto</span>}
          </button>
          <p className="muted rp-note">
            La primera vez que veas la pantalla, el teléfono pedirá permiso para compartirla: quien lo tenga debe tocar
            <b> Iniciar ahora</b> (en Android 10 a 13 puede marcar <b>No volver a mostrar</b> para que no se pida más).
          </p>
        </div>
      )}

      {status && !useSession && tier === 'view' && (
        <div className="banner banner-warn rp-power" data-testid="remote-enable-control">
          <span>
            <strong>Solo ver.</strong> Para controlar (tocar y escribir) hay que activar una vez el servicio de accesibilidad de
            droidVNC‑NG en el teléfono. Este botón abre ese ajuste en el teléfono; quien lo tenga activa <b>droidVNC‑NG</b>.
          </span>
          <button className="btn btn-sm" disabled={busy} onClick={() => void enableControl()}>Abrir el ajuste en el teléfono</button>
        </div>
      )}

      {status && available && legacyNotes && !status.encrypted && (
        <div className="banner banner-warn">
          El agente de este dispositivo es anterior al túnel cifrado, así que la pantalla viaja sin cifrar entre el
          teléfono y el repetidor. Actualiza el agente o deja el puerto del repetidor detrás de una VPN.
        </div>
      )}

      {(status || sup) && (available || useSession) && !combined && powerMode !== 'alwaysOn' && (
        <div className="banner banner-warn rp-power">
          <span>
            <strong>Modo ahorro de batería.</strong> Mientras el teléfono está bloqueado y sin cargar, solo toma una
            sesión en su próxima conexión, unos minutos después. Para soporte inmediato déjalo en Siempre conectado.
          </span>
          <button className="btn btn-sm" disabled={busy || switching} onClick={() => void setAlwaysOn()}>
            {switching ? <span key="switching">Cambiando…</span> : <span key="set">Activar Siempre conectado</span>}
          </button>
        </div>
      )}

      {(status || sup) && (available || useSession) && !sessionOpen && (
        <div className="rp-start">
          <button className="btn btn-primary" disabled={busy} title="Ver la pantalla, tocar y escribir"
                  onClick={() => void start(false)}>
            Ver y controlar
          </button>
          <button className="btn" disabled={busy} onClick={() => void start(true)}>
            Solo ver
          </button>
          {!useSession && tier === 'view' && (
            <span className="muted rp-note">
              El dispositivo reportó solo ver (servicio de entrada apagado). El control empieza en cuanto reporte entrada.
            </span>
          )}
          {useSession && readiness !== 'ready' && !available && (
            <span className="muted rp-note">
              Sin pantalla por ahora: la sesión abre con chat, llamada y archivos.
            </span>
          )}
        </div>
      )}

      {sess && (
        <>
          <div className="rp-bar"><KioskToggle device={device} compact /></div>
          <SupportSession
            key={sess.start.sessionKey}
            device={device}
            start={sess.start}
            startedAt={sess.startedAt}
            altScreen={legacyScreen}
            onScreenMode={onScreenMode}
            onClosed={closeSupport}
          />
        </>
      )}

      {!combined && phase.kind === 'failed' && (
        <div className="banner banner-alert rp-power">
          <span>{phase.message}</span>
          <button className="btn btn-sm" onClick={() => setPhase({ kind: 'idle' })}>Cerrar</button>
        </div>
      )}

      {!combined && phase.kind === 'waiting' && (
        <div className="rp-wait">
          <span className="spin" />
          <span>
            Esperando a que el dispositivo se conecte… {Math.round((now - phase.since) / 1000)} s
            {powerMode !== 'alwaysOn' && ' (ahorro de batería: puede tardar unos minutos si está bloqueado)'}
          </span>
          <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => void stop()}>Cancelar</button>
        </div>
      )}

      {!combined && phase.kind === 'live' && (
        <>
          <div className="rp-bar">
            <span className="chip tone-ok">{phase.session.viewOnly ? 'Viendo' : 'Controlando'}</span>
            <KioskToggle device={device} compact />
            <div style={{ flex: 1 }} />
            <button className="btn btn-sm" onClick={fullscreen}>Pantalla completa</button>
            {/* The repeater pairs one viewer per session: hand it to the new tab and drop the inline one. */}
            <a className="btn btn-sm" href={viewerUrl(phase.session)} target="_blank" rel="noopener noreferrer"
               onClick={() => setPhase({ kind: 'popped', session: phase.session })}>
              Abrir en otra pestaña
            </a>
            <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void stop()}>
              Terminar sesión
            </button>
          </div>
          <iframe
            ref={frame}
            className="rp-viewer"
            title={`Pantalla de ${device.number}`}
            src={viewerUrl(phase.session)}
            allow="fullscreen; clipboard-read; clipboard-write"
          />
          {!phase.session.viewOnly && (
            <NavBar
              frame={frame}
              onUnavailable={() => toast.push('err', 'Visor no conectado', 'Espera a que aparezca la pantalla y vuelve a intentarlo.')}
            />
          )}
        </>
      )}

      {!combined && phase.kind === 'popped' && (
        <div className="rp-bar">
          <span className="muted">El visor está abierto en otra pestaña.</span>
          <div style={{ flex: 1 }} />
          <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void stop()}>
            Terminar sesión
          </button>
        </div>
      )}

      {sup && (
        <details className="ss-history" open={histOpen} data-testid="support-history"
                 onToggle={(e) => setHistOpen((e.currentTarget as HTMLDetailsElement).open)}>
          <summary>Historial de sesiones</summary>
          {histErr && <div className="banner banner-alert">{histErr}</div>}
          {!histErr && history == null && <p className="muted">Cargando…</p>}
          {!histErr && history != null && history.length === 0 && (
            <p className="muted">Este dispositivo aún no tiene sesiones de soporte registradas.</p>
          )}
          {!histErr && history != null && history.length > 0 && (
            <div className="report-table-wrap"><SupportSessionsTable rows={history} /></div>
          )}
          <button className="btn btn-sm btn-ghost" disabled={histBusy} onClick={() => void loadHistory()}>Actualizar</button>
        </details>
      )}
    </div>
  );
}
