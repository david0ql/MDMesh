import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getRemoteStatus, startRemoteSession, stopRemoteSession, viewerUrl,
  type RemoteSession, type RemoteStatus,
} from '../api/remote';
import { listCommandHistory, queueCommand, setupRemoteSupport } from '../api/commands';
import { useToast } from '../ui/toast';
import { NavBar } from './NavBar';

type Device = { number: string };

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

/**
 * Remote view/control of one device (ADR 0010): queue a session, wait for the device to connect to the
 * repeater, then show the noVNC viewer inline (or in its own tab). The session is encrypted end to end
 * when the agent tunnels it over HTTPS; older agents fall back to the plain repeater port.
 */
export function RemotePanel({ device }: { device: Device }) {
  const toast = useToast();
  const [status, setStatus] = useState<RemoteStatus | null>(null);
  const [statusErr, setStatusErr] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now());
  const frame = useRef<HTMLIFrameElement>(null);

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await getRemoteStatus(device.number));
      setStatusErr(null);
    } catch (e) {
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
        setPhase({ kind: 'failed', message: cmd.detail || `El dispositivo respondió "${cmd.status}".` });
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

  async function start(viewOnly: boolean) {
    setBusy(true);
    try {
      const session = await startRemoteSession(device.number, viewOnly);
      setPhase({ kind: 'waiting', session, since: Date.now() });
    } catch (e) {
      toast.push('err', 'No se pudo iniciar la sesión', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
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

  const tier = status?.tier ?? 'none';
  const available = tier === 'view' || tier === 'control';
  const sessionOpen = phase.kind === 'waiting' || phase.kind === 'live' || phase.kind === 'popped';

  return (
    <div className="panel rp">
      {/* keep: tab bodies hide panel heads, but this one carries the encryption state */}
      <div className="panel-head keep">
        <h2 className="panel-title">Control remoto</h2>
        {status && available && (
          <span className={`chip ${status.encrypted ? 'tone-ok' : 'tone-warn'}`}
                title={status.encrypted
                  ? 'Teléfono ↔ servidor viaja dentro del HTTPS del servidor (TLS).'
                  : 'Este agente se conecta directo al puerto del repetidor: solo el intercambio de la contraseña va protegido.'}>
            {status.encrypted ? 'Cifrado (TLS)' : 'Sin cifrar'}
          </span>
        )}
      </div>

      {statusErr && <div className="banner banner-alert">{statusErr}</div>}

      {status && !available && (
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

      {status && tier === 'view' && (
        <div className="banner banner-warn rp-power" data-testid="remote-enable-control">
          <span>
            <strong>Solo ver.</strong> Para controlar (tocar y escribir) hay que activar una vez el servicio de accesibilidad de
            droidVNC‑NG en el teléfono. Este botón abre ese ajuste en el teléfono; quien lo tenga activa <b>droidVNC‑NG</b>.
          </span>
          <button className="btn btn-sm" disabled={busy} onClick={() => void enableControl()}>Abrir el ajuste en el teléfono</button>
        </div>
      )}

      {status && available && !status.encrypted && (
        <div className="banner banner-warn">
          El agente de este dispositivo es anterior al túnel cifrado, así que la pantalla viaja sin cifrar entre el
          teléfono y el repetidor. Actualiza el agente o deja el puerto del repetidor detrás de una VPN.
        </div>
      )}

      {status && available && status.powerMode !== 'alwaysOn' && (
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

      {status && available && !sessionOpen && (
        <div className="rp-start">
          <button className="btn btn-primary" disabled={busy} title="Ver la pantalla, tocar y escribir"
                  onClick={() => void start(false)}>
            Ver y controlar
          </button>
          <button className="btn" disabled={busy} onClick={() => void start(true)}>
            Solo ver
          </button>
          {tier === 'view' && (
            <span className="muted rp-note">
              El dispositivo reportó solo ver (servicio de entrada apagado). El control empieza en cuanto reporte entrada.
            </span>
          )}
        </div>
      )}

      {phase.kind === 'failed' && (
        <div className="banner banner-alert rp-power">
          <span>{phase.message}</span>
          <button className="btn btn-sm" onClick={() => setPhase({ kind: 'idle' })}>Cerrar</button>
        </div>
      )}

      {phase.kind === 'waiting' && (
        <div className="rp-wait">
          <span className="spin" />
          <span>
            Esperando a que el dispositivo se conecte… {Math.round((now - phase.since) / 1000)} s
            {status?.powerMode !== 'alwaysOn' && ' (ahorro de batería: puede tardar unos minutos si está bloqueado)'}
          </span>
          <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => void stop()}>Cancelar</button>
        </div>
      )}

      {phase.kind === 'live' && (
        <>
          <div className="rp-bar">
            <span className="chip tone-ok">{phase.session.viewOnly ? 'Viendo' : 'Controlando'}</span>
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

      {phase.kind === 'popped' && (
        <div className="rp-bar">
          <span className="muted">El visor está abierto en otra pestaña.</span>
          <div style={{ flex: 1 }} />
          <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void stop()}>
            Terminar sesión
          </button>
        </div>
      )}
    </div>
  );
}
