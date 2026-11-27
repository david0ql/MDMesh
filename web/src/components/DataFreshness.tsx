import { useEffect, useState } from 'react';
import { refreshDevices } from '../api/fleet';
import { getDeviceState, type DeviceState } from '../api/commands';
import { DataAge } from '../data/deviceColumns';
import { useToast } from '../ui/toast';

/**
 * Device detail: how old the device's data is ("Datos de hace 2 min") and "Actualizar ahora", which asks the device
 * to report at once and says how long the fresh data took to arrive.
 */
export function DataFreshness({
  number,
  updatedAt,
  onState,
}: {
  number: string;
  /** When the device last reported its state (ms). */
  updatedAt?: number | null;
  /** Called with the fresh state as soon as it arrives. */
  onState?: (s: DeviceState) => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [, setTick] = useState(0);
  // Keep "hace N min" moving while the page stays open.
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 15_000);
    return () => clearInterval(t);
  }, []);

  async function refresh() {
    setBusy(true);
    try {
      const r = await refreshDevices({ numbers: [number] });
      if (r.requested === 0) {
        toast.push('err', 'No se pudo actualizar', 'El dispositivo no está a tu alcance.');
        return;
      }
      const t0 = Date.now();
      const tries = r.live > 0 ? 30 : 5;
      for (let i = 0; i < tries; i++) {
        await new Promise((res) => setTimeout(res, 1000));
        const s = await getDeviceState(number).catch(() => null);
        if (s && s.updatedAt >= r.at) {
          onState?.(s);
          toast.push('ok', 'Datos actualizados', `El equipo reportó en ${Math.max(1, Math.round((Date.now() - t0) / 1000))} s.`);
          return;
        }
      }
      toast.push('ok', 'Actualización solicitada', r.live > 0
        ? 'El equipo aún no responde; sus datos se actualizarán cuando reporte.'
        : 'El equipo no tiene el canal en vivo abierto: reportará en su próxima conexión.');
    } catch (e) {
      toast.push('err', 'No se pudo actualizar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="refresh-line">
      <DataAge at={updatedAt} prefix="Datos de" />
      <button className="btn btn-sm" data-testid="refresh-now" disabled={busy} onClick={() => void refresh()}
        title="Pide al equipo que reporte ahora: batería, almacenamiento, red y estado">
        {busy ? 'Actualizando…' : 'Actualizar ahora'}
      </button>
    </div>
  );
}
