import { useCallback, useEffect, useRef, useState } from 'react';
import { getDeviceState, queueCommand, reapplyConfiguration, type DeviceState } from '../api/commands';
import { getConfigStatus } from '../api/configSync';
import { getConfigurations, type Configuration } from '../api/configurations';
import { useToast } from '../ui/toast';
import { KioskEnterModal } from './KioskEnterModal';
import { useAuth } from '../auth/AuthContext';

type Device = { number: string };

/** How long to follow the device after a switch before saying it will apply on its next connection. */
const CONFIRM_LIMIT_MS = 2 * 60 * 1000;

/** True when the agent's dotted version (e.g. "0.9.1") is at least major.minor.patch. */
function agentAtLeast(version: string | null | undefined, major: number, minor: number, patch: number): boolean {
  if (!version) return false;
  const p = version.trim().split(/[.-]/);
  const want = [major, minor, patch];
  for (let i = 0; i < 3; i++) {
    const v = parseInt((p[i] ?? '0').replace(/\D.*$/, ''), 10);
    if (Number.isNaN(v)) return false;
    if (v !== want[i]) return v > want[i];
  }
  return true;
}

/**
 * Whether the device is in its kiosk. On Android 16 the agent (0.9+) runs a SOFT kiosk (persistent HOME +
 * status bar off, no lock-task), so it never reports `kioskActive` — the device still shows the kiosk launcher
 * but `lockTaskModeState` is NONE. For those we treat the kiosk as active when the policy has one and it is
 * applied. On Android ≤15 the real lock-task flag (`kioskActive`) is authoritative.
 */
function kioskEngaged(st: DeviceState | null, policyKiosk: boolean): boolean {
  if (!st) return false;
  if (st.kioskActive) return true;
  const release = parseInt((st.androidRelease ?? '').replace(/\D.*$/, ''), 10);
  const softKiosk = release >= 16 && agentAtLeast(st.agentVersion, 0, 9, 0);
  return softKiosk && policyKiosk;
}

/**
 * Kiosk on/off in one place: the current state, one button to switch it and "Aplicando…" until the phone confirms.
 * Turning it on uses the policy's kiosk when the policy has one (one click); "Personalizar…" picks apps by hand.
 */
export function KioskToggle({ device, compact = false }: { device: Device; compact?: boolean }) {
  const toast = useToast();
  const authUser = useAuth().user;
  const hasPerm = (p: string) => !!authUser && (authUser.superAdmin || authUser.userRole?.superAdmin
    || (authUser.userRole?.permissions ?? []).some((x) => x.name === p));
  // Operating the kiosk — turning the policy's kiosk on (re-apply) or off (exit) — needs only edit_devices,
  // which every folder role has (the view-only "visor" too): both actions are allowed by the server for it.
  const canOperate = hasPerm('edit_devices');
  // Customising the kiosk (picking apps by hand) changes the device's software and needs manage_fleet, plus
  // the app picker reads the Library (applications/configurations). A visor does not get these.
  const canManage = hasPerm('manage_fleet');
  const [active, setActive] = useState<boolean | null>(null);
  const [policy, setPolicy] = useState<Configuration | null>(null);
  const [target, setTarget] = useState<{ want: boolean; since: number } | null>(null);
  const [custom, setCustom] = useState(false);
  const [busy, setBusy] = useState(false);
  // Read inside the poll loop (a stable callback) needs the latest policy-kiosk flag without re-subscribing.
  const policyKioskRef = useRef(false);

  const read = useCallback(async () => {
    const st = await getDeviceState(device.number).catch(() => null);
    const engaged = kioskEngaged(st, policyKioskRef.current);
    if (st) setActive(engaged);
    return st ? engaged : null;
  }, [device.number]);

  useEffect(() => {
    let on = true;
    getConfigStatus(device.number)
      .then(async (s) => {
        const id = s?.configurationId;
        const c = id == null ? null : (await getConfigurations()).find((x) => x.id === id) ?? null;
        if (on) setPolicy(c);
      })
      .catch(() => undefined);
    return () => { on = false; };
  }, [device.number]);

  // Poll: every 10 s normally, every 2 s while a switch is on its way.
  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const loop = async () => {
      const now = await read();
      if (!on) return;
      if (target) {
        if (now === target.want) {
          setTarget(null);
          toast.push('ok', target.want ? 'Quiosco activado' : 'Quiosco desactivado', 'El teléfono ya lo confirmó.');
        } else if (Date.now() - target.since > CONFIRM_LIMIT_MS) {
          setTarget(null);
          toast.push('err', 'El teléfono aún no confirma', 'Se aplicará en su próxima conexión (unos minutos si está bloqueado).');
        }
      }
      t = setTimeout(() => void loop(), target ? 2000 : 10000);
    };
    void loop();
    return () => { on = false; clearTimeout(t); };
  }, [read, target, toast]);

  const policyKiosk = !!policy?.kioskMode;
  useEffect(() => { policyKioskRef.current = policyKiosk; }, [policyKiosk]);

  async function turnOff() {
    setBusy(true);
    try {
      await queueCommand(device.number, { type: 'kiosk.exit' });
      setTarget({ want: false, since: Date.now() });
    } catch (e) {
      toast.push('err', 'No se pudo quitar el quiosco', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function turnOn() {
    // A viewer cannot read the policy (no `configurations` permission), so policyKiosk is unknown to them:
    // re-applying the device's own configuration brings its kiosk back regardless. Only a manager without a
    // policy kiosk opens the app picker to build one by hand.
    if (!policyKiosk && canManage) { setCustom(true); return; }
    setBusy(true);
    try {
      await reapplyConfiguration(device.number);
      setTarget({ want: true, since: Date.now() });
    } catch (e) {
      toast.push('err', 'No se pudo activar el quiosco', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (!canOperate) return null;
  const pending = target != null;
  return (
    <div className={`kt ${compact ? 'kt-compact' : ''}`} data-testid="kiosk-toggle">
      <span className={`kt-state ${active ? 'on' : 'off'}`}>
        <i aria-hidden="true" />
        {active == null ? 'Quiosco: —' : active ? 'Quiosco activo' : 'Quiosco inactivo'}
      </span>
      {pending ? (
        <span className="kt-wait"><span className="spin" /> {target.want ? 'Activando…' : 'Quitando…'}</span>
      ) : active ? (
        <button className="btn btn-sm" disabled={busy} onClick={() => void turnOff()} title="Vuelve a la pantalla de inicio normal del teléfono">
          Quitar quiosco
        </button>
      ) : (
        <>
          <button className="btn btn-sm btn-primary" disabled={busy || active == null} onClick={() => void turnOn()}
                  title={policyKiosk ? `Usa el quiosco de la política «${policy?.name}»` : 'Vuelve a poner el quiosco del equipo'}>
            {policyKiosk || !canManage ? 'Activar quiosco' : 'Activar quiosco…'}
          </button>
          {policyKiosk && canManage && (
            <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => setCustom(true)}>Personalizar…</button>
          )}
        </>
      )}
      {!compact && !pending && active === false && policyKiosk && (
        <span className="muted small">Usa el quiosco de la política «{policy?.name}».</span>
      )}
      {custom && (
        <KioskEnterModal
          device={device}
          onClose={() => setCustom(false)}
          onQueued={() => { setCustom(false); setTarget({ want: true, since: Date.now() }); }}
        />
      )}
    </div>
  );
}
