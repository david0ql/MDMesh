import { useEffect, useState } from 'react';
import { getEvents, type DeviceEvent } from '../api/events';
import { listCommandHistory, syncConfigApps, type CommandHistoryItem } from '../api/commands';

type Device = { number: string };

/** One line of the update trail: from the phone's own events or from an install the console sent. */
interface Trace { key: string; ts: number; kind: string; what: string; status: 'ok' | 'fail' | 'wait' | 'info' }

const EVENT_KINDS: Record<string, string> = {
  appInstalled: 'App instalada',
  appUpdated: 'App actualizada',
  systemUpdated: 'Android actualizado',
  systemUpdatePending: 'Actualización de Android disponible',
};

const COMMAND_TYPES = new Set(['app.install', 'agent.update', 'device.openStore']);

const STATUS: Record<string, Trace['status']> = {
  SUCCEEDED: 'ok', OK: 'ok', DONE: 'ok', COMPLETED: 'ok',
  FAILED: 'fail', UNSUPPORTED: 'fail', ERROR: 'fail', REJECTED: 'fail', EXPIRED: 'fail', CANCELLED: 'fail',
};

const LABEL: Record<Trace['status'], string> = { ok: 'Hecho', fail: 'Falló', wait: 'Pendiente', info: '' };

function fromCommand(c: CommandHistoryItem): Trace {
  const st = STATUS[String(c.status).toUpperCase()] ?? 'wait';
  const kind = c.type === 'agent.update' ? 'Actualizar agente' : c.type === 'device.openStore' ? 'Abrir en Play Store' : 'Instalar / actualizar app';
  return {
    key: `c${c.id}`,
    ts: c.completedAt ?? c.deliveredAt ?? c.createdAt ?? 0,
    kind: `${kind} (desde la consola)`,
    what: [c.subject, st === 'fail' ? c.detail : null].filter(Boolean).join(' · '),
    status: st,
  };
}

function fromEvent(e: DeviceEvent): Trace {
  return { key: `e${e.id}`, ts: e.ts, kind: EVENT_KINDS[e.type] ?? e.type, what: e.detail ?? '', status: e.type === 'systemUpdatePending' ? 'wait' : 'info' };
}

/**
 * Update traces for one device: apps installed or updated on the phone (with the version it went to), installs and
 * agent updates sent from the console with their result, and Android / security-patch changes. Refreshes every 10 s.
 */
export function UpdateTrail({ device, android, patch, pendingSince }: {
  device: Device; android?: string; patch?: string; pendingSince?: number | null;
}) {
  const [traces, setTraces] = useState<Trace[] | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const load = async () => {
      const [evs, cmds] = await Promise.all([
        getEvents(device.number).catch(() => [] as DeviceEvent[]),
        listCommandHistory(device.number).catch(() => [] as CommandHistoryItem[]),
      ]);
      if (!on) return;
      const all = [
        ...evs.filter((e) => e.type in EVENT_KINDS).map(fromEvent),
        ...cmds.filter((c) => COMMAND_TYPES.has(c.type)).map(fromCommand),
      ].sort((a, b) => b.ts - a.ts);
      setTraces(all);
      t = setTimeout(() => void load(), 10000);
    };
    void load();
    return () => { on = false; clearTimeout(t); };
  }, [device.number]);

  const forceApps = async () => {
    setMsg(null);
    try {
      const r = await syncConfigApps(device.number);
      setMsg(r.queued > 0 ? `Se enviaron ${r.queued} instalaciones/actualizaciones de la política.` : 'Las apps de la política ya están al día.');
    } catch (e) {
      setMsg(`No se pudo enviar: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  return (
    <div className="panel">
      <h2 className="panel-title">Actualizaciones</h2>
      <div className="upd-now">
        <div><span className="muted">Android</span> <b>{android ?? '—'}</b></div>
        <div><span className="muted">Parche de seguridad</span> <b>{patch ?? '—'}</b></div>
        <div>
          <span className="muted">Actualización del sistema</span>{' '}
          <b>{pendingSince ? `pendiente desde ${new Date(pendingSince).toLocaleString('es-CO')}` : 'ninguna pendiente'}</b>
        </div>
      </div>
      <p className="muted small">
        Para obligar las actualizaciones de Android, en la política elige «Actualizaciones del sistema»: automática, en un
        horario o posponer. Para las apps, cada versión nueva que subas a la política se instala sola; este botón la
        exige ya en este equipo.
      </p>
      <button className="btn" type="button" onClick={() => void forceApps()}>Actualizar ya las apps de la política</button>
      {msg && <p className="small">{msg}</p>}

      {traces == null ? (
        <p className="muted">Cargando…</p>
      ) : traces.length === 0 ? (
        <p className="muted">Aún no hay actualizaciones registradas.</p>
      ) : (
        <ul className="timeline">
          {traces.map((x) => (
            <li key={x.key} className="timeline-item">
              <span className="t-status">{x.ts ? new Date(x.ts).toLocaleString('es-CO') : '—'}</span>
              <span className="t-type">{x.kind}</span>
              {x.what && <span className="t-detail">{x.what}</span>}
              {x.status !== 'info' && <span className={`upd-st ${x.status}`}>{LABEL[x.status]}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
