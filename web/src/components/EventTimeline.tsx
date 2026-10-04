import { useEffect, useState } from 'react';
import { getEvents, type DeviceEvent } from '../api/events';

type Device = { number: string };

const LABELS: Record<string, string> = {
  boot: 'Encendido',
  appInstalled: 'App instalada',
  appUninstalled: 'App desinstalada',
  commandResult: 'Orden',
  connectivityChange: 'Cambio de red',
  lowBattery: 'Batería baja',
  enrolled: 'Inscrito',
  simRemoved: 'SIM retirada',
  simInserted: 'SIM insertada',
  simChanged: 'SIM cambiada',
  appBlocked: 'App bloqueada (no permitida)',
  appUpdated: 'App actualizada',
  announcementReceived: 'Anuncio recibido',
  announcementSeen: 'Anuncio visto',
  announcementAck: 'Anuncio confirmado',
  kioskExit: 'Salió del quiosco',
  accountRemoved: 'Cuenta de Google quitada (no es del dominio permitido)',
  recovered: 'Recuperado: se había eliminado de la consola con el teléfono aún inscrito',
  systemUpdated: 'Android actualizado',
  systemUpdatePending: 'Actualización de Android disponible',
  kioskCrashLoop: 'La app del quiosco se cerró varias veces',
  soundAlert: 'Está sonando (alarma o timbre)',
  soundEnded: 'Dejó de sonar',
};

export function EventTimeline({ device }: { device: Device }) {
  const [events, setEvents] = useState<DeviceEvent[]>([]);
  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    // Self-scheduling poll — the next tick is armed only after the current one finishes.
    const load = async () => {
      await getEvents(device.number)
        .then((evs) => { if (on) setEvents(evs); })
        .catch(() => undefined);
      if (!on) return;
      t = setTimeout(() => void load(), 5000);
    };
    void load();
    return () => { on = false; clearTimeout(t); };
  }, [device.number]);

  return (
    <div className="panel">
      <h2 className="panel-title">Eventos</h2>
      {events.length === 0 ? (
        <p className="muted">Aún no hay eventos.</p>
      ) : (
        <ul className="timeline">
          {events.map((e) => (
            <li key={e.id} className="timeline-item">
              <span className="t-status">{new Date(e.ts).toLocaleString()}</span>
              <span className="t-type">{LABELS[e.type] ?? e.type}</span>
              {e.detail && <span className="t-detail">{e.detail}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
