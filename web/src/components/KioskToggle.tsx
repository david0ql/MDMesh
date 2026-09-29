import { useCallback, useEffect, useState } from 'react';
import { getDeviceState, queueCommand, reapplyConfiguration } from '../api/commands';
import { getConfigStatus } from '../api/configSync';
import { getConfigurations, type Configuration } from '../api/configurations';
import { useToast } from '../ui/toast';
import { KioskEnterModal } from './KioskEnterModal';

type Device = { number: string };

/** How long to follow the device after a switch before saying it will apply on its next connection. */
const CONFIRM_LIMIT_MS = 2 * 60 * 1000;

/**
 * Kiosk on/off in one place: the current state, one button to switch it and "Aplicando…" until the phone confirms.
 * Turning it on uses the policy's kiosk when the policy has one (one click); "Personalizar…" picks apps by hand.
 */
export function KioskToggle({ device, compact = false }: { device: Device; compact?: boolean }) {
  const toast = useToast();
  const [active, setActive] = useState<boolean | null>(null);
  const [policy, setPolicy] = useState<Configuration | null>(null);
  const [target, setTarget] = useState<{ want: boolean; since: number } | null>(null);
  const [custom, setCustom] = useState(false);
  const [busy, setBusy] = useState(false);

  const read = useCallback(async () => {
    const st = await getDeviceState(device.number).catch(() => null);
    if (st) setActive(st.kioskActive);
    return st?.kioskActive ?? null;
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
    if (!policyKiosk) { setCustom(true); return; }
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
                  title={policyKiosk ? `Usa el quiosco de la política «${policy?.name}»` : 'Elige las apps del quiosco'}>
            {policyKiosk ? 'Activar quiosco' : 'Activar quiosco…'}
          </button>
          {policyKiosk && (
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
