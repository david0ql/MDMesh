import { useCallback, useEffect, useState } from 'react';
import { getDataSharing, setDataSharing, type DataSharingState } from '../api/deviceApps';
import { useToast } from '../ui/toast';
import { useAuth } from '../auth/AuthContext';

type Device = { number: string };

/**
 * Per-device "share data" (tethering / hotspot) override, on top of the policy. Three states:
 *  - Permitir  → the device may share data even if the policy blocks it;
 *  - Bloquear  → data sharing is blocked on this device even if the policy allows it;
 *  - Según política → no override, the device follows its policy.
 * The device override wins over the policy (like other per-device settings). Setting it queues an immediate
 * `device.dataSharing` command and, through the changed config revision, a durable config.apply.
 */
export function DataSharingToggle({ device }: { device: Device }) {
  const toast = useToast();
  const authUser = useAuth().user;
  const canOperate = !!authUser && (authUser.superAdmin || authUser.userRole?.superAdmin
    || (authUser.userRole?.permissions ?? []).some((p) => p.name === 'edit_devices'));
  const [state, setState] = useState<DataSharingState | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  const read = useCallback(async () => {
    const r = await getDataSharing(device.number).catch(() => null);
    if (r) setState(r.state);
  }, [device.number]);

  useEffect(() => { void read(); }, [read]);

  async function choose(next: 'allow' | 'block' | 'policy') {
    setBusy(true);
    try {
      const r = await setDataSharing(device.number, next);
      setState(r.state);
      toast.push('ok', 'Compartir datos actualizado',
        next === 'policy' ? 'El equipo vuelve a seguir la política.'
          : next === 'allow' ? 'Permitido en este equipo.' : 'Bloqueado en este equipo.');
    } catch (e) {
      toast.push('err', 'No se pudo cambiar «Compartir datos»', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (!canOperate) return null;
  // undefined = not loaded yet; null = follows the policy.
  const effective = state === undefined ? null : state;
  const follows = state !== 'allow' && state !== 'block';
  return (
    <div className="kt" data-testid="data-sharing-toggle">
      <span className={`kt-state ${effective === 'block' ? 'off' : 'on'}`}>
        <i aria-hidden="true" />
        {state === undefined ? 'Compartir datos: —'
          : follows ? 'Compartir datos: según la política'
          : state === 'allow' ? 'Compartir datos: permitido' : 'Compartir datos: bloqueado'}
      </span>
      <button className={`btn btn-sm ${state === 'allow' ? 'btn-primary' : ''}`} disabled={busy || state === undefined}
              onClick={() => void choose('allow')} title="Permite compartir datos en este equipo, aunque la política lo bloquee">
        Permitir
      </button>
      <button className={`btn btn-sm ${state === 'block' ? 'btn-primary' : ''}`} disabled={busy || state === undefined}
              onClick={() => void choose('block')} title="Bloquea compartir datos en este equipo, aunque la política lo permita">
        Bloquear
      </button>
      <button className={`btn btn-sm btn-ghost ${follows ? 'btn-primary' : ''}`} disabled={busy || state === undefined || follows}
              onClick={() => void choose('policy')} title="Quita el ajuste del equipo y vuelve a seguir la política">
        Según política
      </button>
    </div>
  );
}
