import { useCallback, useEffect, useState } from 'react';
import { listCommandHistory, queueCommand } from '../api/commands';
import { getEvents, type DeviceEvent } from '../api/events';

// "Diagnóstico": what makes the phone ring or misbehave. A snapshot taken on the phone (sounds now and lately with their
// likely source, notifications with their buttons, the next alarm, volumes, the call, the kiosk, the apps that came to
// the front) and the sound events it recorded on its own. Notification buttons can be pressed from here (e.g. "Detener"
// on an alarm the kiosk keeps hidden).

type Device = { number: string };

interface Snapshot {
  at: number; android?: string; model?: string;
  sound?: { playingNow?: string[]; ringerMode?: string; volumes?: Record<string, string>; recent?: { start: number; end: number | null; kind: string; source: string }[] };
  call?: string;
  nextAlarm?: { at: number; setBy: string | null } | null;
  notificationAccess?: boolean;
  notifications?: { key: string; app: string; at: number; category: string | null; alerting: boolean; title: string | null; text: string | null; ongoing: boolean; insistent: boolean; fullScreen: boolean; actions: string[] }[];
  kiosk?: { locked?: string; allowedApps?: string[] };
  foreground?: { at: number; app: string }[];
  battery?: number;
}

const time = (t?: number | null) => (t ? new Date(t).toLocaleString('es-CO', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—');
const SOUND_EVENTS = new Set(['soundAlert', 'soundEnded']);

/** Queue a command and wait (up to [waitMs]) for its result. */
async function run(number: string, type: string, payload?: unknown, waitMs = 60_000): Promise<{ status: string; detail?: string | null }> {
  const since = Date.now() - 5_000;
  const q = await queueCommand(number, { type, requiresCapability: type, ...(payload === undefined ? {} : { payload: JSON.stringify(payload) }) });
  const end = Date.now() + waitMs;
  while (Date.now() < end) {
    await new Promise((r) => setTimeout(r, 2000));
    const c = (await listCommandHistory(number, since)).find((x) => String(x.id) === String(q.id));
    if (c && c.status !== 'pending' && c.status !== 'delivered') return c;
  }
  return { status: 'timeout', detail: 'El teléfono no respondió en un minuto: está sin conexión o todavía no tiene el agente 0.7.4 (la orden queda en cola y se cumple cuando se conecte o se actualice).' };
}

export function DiagnosticsPanel({ device }: { device: Device }) {
  const [snap, setSnap] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [sounds, setSounds] = useState<DeviceEvent[]>([]);

  const loadSounds = useCallback(async () => {
    const ev = await getEvents(device.number, Date.now() - 7 * 86_400_000).catch(() => [] as DeviceEvent[]);
    setSounds(ev.filter((e) => SOUND_EVENTS.has(e.type)));
  }, [device.number]);
  useEffect(() => { void loadSounds(); }, [loadSounds]);

  async function diagnose() {
    setBusy('diag'); setMsg(null);
    try {
      const r = await run(device.number, 'device.diagnose');
      if (r.status === 'done' && r.detail) setSnap(JSON.parse(r.detail) as Snapshot);
      else setMsg(r.status === 'unsupported' ? 'Este teléfono necesita el agente 0.7.4 o superior.' : r.detail ?? `Sin resultado (${r.status}).`);
      void loadSounds();
    } catch (e) { setMsg(e instanceof Error ? e.message : 'No se pudo diagnosticar.'); }
    setBusy(null);
  }

  async function act(label: string, type: string, payload?: unknown) {
    setBusy(label); setMsg(null);
    const r = await run(device.number, type, payload).catch((e) => ({ status: 'error', detail: String(e) }));
    setMsg(r.status === 'done' ? `Listo: ${r.detail ?? label}` : `No se pudo: ${r.detail ?? r.status}`);
    setBusy(null);
    if (r.status === 'done' && type === 'device.notificationAction') void diagnose();
  }

  const s = snap?.sound;
  return (
    <section className="panel" data-testid="diagnostics">
      <div className="panel-head">
        <h2 className="panel-title">Diagnóstico</h2>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button className="btn btn-sm" disabled={!!busy} onClick={() => void act('Silenciado', 'device.silence', { minutes: 10 })}>
            {busy === 'Silenciado' ? 'Silenciando…' : 'Silenciar 10 min'}
          </button>
          <button className="btn btn-sm btn-primary" disabled={!!busy} onClick={() => void diagnose()}>
            {busy === 'diag' ? 'Diagnosticando…' : 'Diagnosticar ahora'}
          </button>
        </div>
      </div>
      <p className="muted small" style={{ marginTop: 0 }}>
        Toma una foto del estado del teléfono: qué suena y por qué, notificaciones, alarmas, volumen, llamada y quiosco. Desde
        aquí se pueden tocar los botones de una notificación (por ejemplo «Detener» de una alarma que el quiosco no deja ver).
      </p>
      {msg && <div className="banner">{msg}</div>}

      {snap && (
        <>
          <table className="kv-table">
            <tbody>
              <tr><th>Tomado</th><td>{time(snap.at)} · {snap.model} · Android {snap.android} · batería {snap.battery}%</td></tr>
              <tr><th>Sonando ahora</th><td>{s?.playingNow?.length ? s.playingNow.join(', ') : 'nada'}</td></tr>
              <tr><th>Llamada</th><td>{snap.call}</td></tr>
              <tr><th>Modo de sonido</th><td>{s?.ringerMode} · {Object.entries(s?.volumes ?? {}).map(([k, v]) => `${k} ${v}`).join(' · ')}</td></tr>
              <tr><th>Próxima alarma</th><td>{snap.nextAlarm ? `${time(snap.nextAlarm.at)} (puesta por ${snap.nextAlarm.setBy ?? '?'})` : 'ninguna'}</td></tr>
              <tr><th>Quiosco</th><td>{snap.kiosk?.locked} · {snap.kiosk?.allowedApps?.length ?? 0} apps permitidas</td></tr>
              <tr><th>Notificaciones</th><td>{snap.notificationAccess ? 'con acceso' : 'sin acceso (el agente no las ve: el teléfono no se inscribió por cable)'}</td></tr>
            </tbody>
          </table>

          <h3 className="sub-h">Sonidos recientes (desde que arrancó el agente)</h3>
          {s?.recent?.length ? (
            <table className="gr-table"><thead><tr><th>Inicio</th><th>Fin</th><th>Qué</th><th>Fuente probable</th></tr></thead>
              <tbody>{s.recent.map((r) => <tr key={r.start}><td>{time(r.start)}</td><td>{r.end ? time(r.end) : 'sonando'}</td><td>{r.kind}</td><td>{r.source}</td></tr>)}</tbody>
            </table>
          ) : <p className="muted small">Ninguno.</p>}

          <h3 className="sub-h">Notificaciones en el teléfono</h3>
          {snap.notifications?.length ? (
            <ul className="diag-notifs">
              {snap.notifications.map((n) => (
                <li key={n.key} className={n.alerting ? 'alerting' : ''}>
                  <div><b>{n.title ?? n.app}</b> <span className="muted small">{n.app} · {n.category ?? 'sin categoría'} · {time(n.at)}{n.insistent ? ' · insistente' : ''}{n.fullScreen ? ' · pantalla completa' : ''}</span></div>
                  {n.text && <div className="small">{n.text}</div>}
                  {n.alerting && (
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 4 }}>
                      {n.actions.map((a, i) => (
                        <button key={i} className="btn btn-sm" disabled={!!busy} onClick={() => void act(a, 'device.notificationAction', { key: n.key, action: String(i) })}>{a || `Botón ${i + 1}`}</button>
                      ))}
                      {n.fullScreen && <button className="btn btn-sm btn-ghost" disabled={!!busy} onClick={() => void act('Pantalla abierta', 'device.notificationAction', { key: n.key, action: 'fullscreen' })}>Abrir su pantalla</button>}
                      <button className="btn btn-sm btn-ghost" disabled={!!busy} onClick={() => void act('Abierta', 'device.notificationAction', { key: n.key, action: 'open' })}>Abrir</button>
                      <button className="btn btn-sm btn-ghost" disabled={!!busy} onClick={() => void act('Descartada', 'device.notificationAction', { key: n.key, action: 'dismiss' })}>Descartar</button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          ) : <p className="muted small">{snap.notificationAccess ? 'Ninguna.' : 'Sin acceso a notificaciones.'}</p>}

          <h3 className="sub-h">Apps que pasaron al frente (últimos 30 min)</h3>
          <p className="small">{snap.foreground?.length ? snap.foreground.map((f) => `${time(f.at)} ${f.app}`).join(' → ') : 'Ninguna (o sin acceso de uso).'}</p>
        </>
      )}

      <h3 className="sub-h">Sonidos registrados (7 días)</h3>
      {sounds.length ? (
        <ul className="diag-sounds">{sounds.map((e, i) => <li key={i}><span className="muted small">{time(e.ts)}</span> {e.detail}</li>)}</ul>
      ) : <p className="muted small">Ninguno todavía. El agente 0.7.4 registra cada alarma o timbre que suene más de unos segundos, con su fuente.</p>}
    </section>
  );
}
