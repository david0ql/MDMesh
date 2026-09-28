import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getRemoteStatus, startRemoteSession, stopRemoteSession, viewerUrl,
  type RemoteSession, type RemoteStatus,
} from '../api/remote';
import { listCommandHistory, queueCommand } from '../api/commands';
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
      setStatusErr(e instanceof Error ? e.message : 'Could not read the device');
    }
  }, [device.number]);

  useEffect(() => { void refreshStatus(); }, [refreshStatus]);

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
        setPhase({ kind: 'failed', message: cmd.detail || `The device answered "${cmd.status}".` });
        return;
      }
      if (Date.now() - phase.since > WAIT_LIMIT_MS) {
        setPhase({ kind: 'failed', message: 'The device did not answer within 6 minutes (offline or asleep?).' });
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
      toast.push('err', 'Could not start the session', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function stop() {
    setBusy(true);
    try {
      await stopRemoteSession(device.number);
      toast.push('ok', 'Session ended', 'The device stops sharing its screen.');
    } catch (e) {
      toast.push('err', 'Could not end the session', e instanceof Error ? e.message : '');
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
      toast.push('ok', 'Always-on queued', 'Applies the next time the device checks in.');
    } catch (e) {
      toast.push('err', 'Could not change the power mode', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  function fullscreen() {
    void frame.current?.requestFullscreen?.();
  }

  const tier = status?.tier ?? 'none';
  const available = tier === 'view' || tier === 'control';
  const sessionOpen = phase.kind === 'waiting' || phase.kind === 'live' || phase.kind === 'popped';

  return (
    <div className="panel rp">
      {/* keep: tab bodies hide panel heads, but this one carries the encryption state */}
      <div className="panel-head keep">
        <h2 className="panel-title">Remote control</h2>
        {status && available && (
          <span className={`chip ${status.encrypted ? 'tone-ok' : 'tone-warn'}`}
                title={status.encrypted
                  ? 'Phone ↔ server travels inside the server’s HTTPS (TLS).'
                  : 'This agent dials the repeater port directly: only the password exchange is protected.'}>
            {status.encrypted ? 'Encrypted (TLS)' : 'Not encrypted'}
          </span>
        )}
      </div>

      {statusErr && <div className="banner banner-alert">{statusErr}</div>}

      {status && !available && (
        <p className="muted">
          This device does not offer remote view. It needs Android 7 or later and droidVNC-NG, enrolled with
          remote support (<span className="mono">scripts/adb-enroll.sh … --remote --vnc-apk …</span>).
        </p>
      )}

      {status && available && !status.encrypted && (
        <div className="banner banner-warn">
          This device’s agent is older than the encrypted tunnel, so the screen travels unencrypted between the
          phone and the repeater. Update the agent, or keep the repeater port behind a VPN.
        </div>
      )}

      {status && available && status.powerMode !== 'alwaysOn' && (
        <div className="banner banner-warn rp-power">
          <span>
            <strong>Battery-saver mode.</strong> While the phone is locked on battery it picks up a session
            only at its next heartbeat, a few minutes later. For instant support keep it in Always-on.
          </span>
          <button className="btn btn-sm" disabled={busy} onClick={() => void setAlwaysOn()}>
            Set Always-on
          </button>
        </div>
      )}

      {status && available && !sessionOpen && (
        <div className="rp-start">
          <button className="btn btn-primary" disabled={busy} title="See the screen, tap and type"
                  onClick={() => void start(false)}>
            View &amp; control
          </button>
          <button className="btn" disabled={busy} onClick={() => void start(true)}>
            View only
          </button>
          {tier === 'view' && (
            <span className="muted rp-note">
              The device last reported view only (input service off). Control starts once it reports input.
            </span>
          )}
        </div>
      )}

      {phase.kind === 'failed' && (
        <div className="banner banner-alert rp-power">
          <span>{phase.message}</span>
          <button className="btn btn-sm" onClick={() => setPhase({ kind: 'idle' })}>Dismiss</button>
        </div>
      )}

      {phase.kind === 'waiting' && (
        <div className="rp-wait">
          <span className="spin" />
          <span>
            Waiting for the device to connect… {Math.round((now - phase.since) / 1000)} s
            {status?.powerMode !== 'alwaysOn' && ' (battery-saver: can take a few minutes while locked)'}
          </span>
          <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => void stop()}>Cancel</button>
        </div>
      )}

      {phase.kind === 'live' && (
        <>
          <div className="rp-bar">
            <span className="chip tone-ok">{phase.session.viewOnly ? 'Viewing' : 'Controlling'}</span>
            <div style={{ flex: 1 }} />
            <button className="btn btn-sm" onClick={fullscreen}>Full screen</button>
            {/* The repeater pairs one viewer per session: hand it to the new tab and drop the inline one. */}
            <a className="btn btn-sm" href={viewerUrl(phase.session)} target="_blank" rel="noopener noreferrer"
               onClick={() => setPhase({ kind: 'popped', session: phase.session })}>
              Open in new tab
            </a>
            <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void stop()}>
              End session
            </button>
          </div>
          <iframe
            ref={frame}
            className="rp-viewer"
            title={`Screen of ${device.number}`}
            src={viewerUrl(phase.session)}
            allow="fullscreen; clipboard-read; clipboard-write"
          />
          {!phase.session.viewOnly && (
            <NavBar
              frame={frame}
              onUnavailable={() => toast.push('err', 'Viewer not connected', 'Wait until the screen shows, then try again.')}
            />
          )}
        </>
      )}

      {phase.kind === 'popped' && (
        <div className="rp-bar">
          <span className="muted">The viewer is open in another tab.</span>
          <div style={{ flex: 1 }} />
          <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void stop()}>
            End session
          </button>
        </div>
      )}
    </div>
  );
}
