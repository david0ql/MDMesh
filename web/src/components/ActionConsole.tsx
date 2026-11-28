import { queuedNote } from '../api/wake';
import { ScriptPanel } from './ScriptPanel';
import { SavedScriptsPanel } from './SavedScriptsPanel';
import { useCallback, useEffect, useState } from 'react';
import {
  ACTION_TEMPLATES, type CommandTemplateExt, queueCommand, getDeviceState,
  listCommandHistory, forceSync, cancelCommand, OFFLINE_QUEUED_NOTE, type DeviceState, type CommandHistoryItem,
} from '../api/commands';
import { getActivity, type DeviceEvent } from '../api/events';
import { useToast } from '../ui/toast';
import { KioskToggle } from './KioskToggle';
import { DataSharingToggle } from './DataSharingToggle';
import { useAuth } from '../auth/AuthContext';
import { ProtectionPanel } from './ProtectionPanel';

type Device = { id: number; number: string };

/** A view-only folder role lacks "disruptive_actions": it may run the safe actions but not lock/reboot/etc. */
function canRunDisruptive(user: ReturnType<typeof useAuth>['user']): boolean {
  if (!user) return false;
  if (user.superAdmin || user.userRole?.superAdmin) return true;
  return (user.userRole?.permissions ?? []).some((p) => p.name === 'disruptive_actions');
}

const GROUPS: Array<{ id: 'safe' | 'disruptive' | 'destructive'; title: string }> = [
  { id: 'safe', title: 'Seguras' },
  { id: 'disruptive', title: 'Interrumpen' },
  // 'destructive' (el borrado remoto) vive en el panel «Protección contra restablecimiento y robo», con su diálogo propio.
];

export function ActionConsole({ device }: { device: Device }) {
  const toast = useToast();
  const { user } = useAuth();
  const visibleGroups = canRunDisruptive(user) ? GROUPS : GROUPS.filter((g) => g.id === 'safe');
  const [state, setState] = useState<DeviceState | null>(null);
  const [history, setHistory] = useState<CommandHistoryItem[]>([]);
  const [activity, setActivity] = useState<DeviceEvent[]>([]);
  const [active, setActive] = useState<CommandTemplateExt | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const [st, hist, act] = await Promise.all([
      getDeviceState(device.number).catch(() => null),
      listCommandHistory(device.number).catch(() => []),
      getActivity(device.number).catch(() => []),
    ]);
    setState(st);
    setHistory(hist);
    setActivity(act);
  }, [device.number]);

  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    // Light UI poll of server-side state — self-scheduling so slow responses can't overlap.
    const loop = async () => {
      await refresh();
      if (!on) return;
      t = setTimeout(() => void loop(), 5000);
    };
    void loop();
    return () => { on = false; clearTimeout(t); };
  }, [refresh]);

  function start(t: CommandTemplateExt) {
    setActive(t);
    setValues({});
    setConfirmText('');
  }

  async function send(t: CommandTemplateExt, values: Record<string, string>) {
    setBusy(true);
    try {
      const req = t.build ? t.build(values) : t.request;
      const res = await queueCommand(device.number, req);
      const id = (res?.id as number | string | undefined) ?? '';
      const how = queuedNote(res?.wakeChannel, res?.wakeExpectedSeconds);
      if (how) {
        // No live channel: say how the device will be woken (push) or that it waits for its next report.
        toast.push('ok', `${t.label}: ${res?.wakeChannel === 'push' ? 'despertando al equipo' : 'en cola'}`, how);
      } else if (res?.deviceOnline === false) {
        toast.push('ok', `${t.label}: en cola`, OFFLINE_QUEUED_NOTE);
      } else {
        toast.push('ok', `${t.label}: enviado`, id ? `Orden ${id}` : '');
      }
      await forceSync(device.number).catch(() => undefined); // nudge (no-op until MQTT lands)
      await refresh();
    } catch (e) {
      toast.push('err', `${t.label}: falló`, e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function cancel(c: CommandHistoryItem) {
    const name = COMMAND_LABELS[c.type] ?? c.type;
    const warn = c.status === 'delivered'
      ? '\n\nEl equipo ya la recibió: cancelarla evita que se reenvíe, pero pudo haberse ejecutado.' : '';
    if (!window.confirm(`¿Cancelar la orden «${name}»?${warn}`)) return;
    setBusy(true);
    try {
      await cancelCommand(device.number, c.id);
      toast.push('ok', `${name}: cancelada`, '');
    } catch (e) {
      toast.push('err', `${name}: no se pudo cancelar`, e instanceof Error ? e.message : '');
    } finally {
      await refresh();
      setBusy(false);
    }
  }

  const needsModal = (t: CommandTemplateExt) =>
    (t.params && t.params.length > 0) || !!t.confirm;

  async function onClick(t: CommandTemplateExt) {
    if (needsModal(t)) { start(t); return; }
    await send(t, {});
  }

  async function confirmAndSend() {
    if (!active) return;
    const t = active;
    setActive(null);
    await send(t, values);
  }

  const canSend =
    !active ? false
    : active.confirm === 'type-to-confirm' ? confirmText === 'BORRAR'
    : active.params?.some((p) => p.required && !values[p.key]) ? false
    : active.validate?.(values) ? false
    : true;
  const invalid = active?.validate?.(values) ?? null;

  return (
    <div className="panel">
      <div className="panel-head">
        <h2 className="panel-title">Control del dispositivo</h2>
        <button
          className="btn"
          disabled={busy}
          onClick={() => { void forceSync(device.number).then(refresh).catch(() => undefined); }}
        >
          Sincronizar ahora
        </button>
      </div>

      <DeviceStatePanel state={state} />
      <KioskToggle device={device} />
      <DataSharingToggle device={device} />
      <ProtectionPanel device={device} canDisrupt={canRunDisruptive(user)} onQueued={() => { void refresh(); }} />

      {visibleGroups.map((g) => (
        <section key={g.id} className="action-group">
          <h3 className="action-group-title">{g.title}</h3>
          <div className="action-grid">
            {ACTION_TEMPLATES.filter((t) => (t.group ?? 'safe') === g.id && !t.key.startsWith('kiosk-')
              // 'set-lock-password' es la variante masiva; para un solo equipo 'passcode-reset' ya fija y quita.
              && t.key !== 'set-lock-password'
              // El borrado remoto tiene su propio diálogo (intención y opciones) en «Protección contra restablecimiento y robo».
              && t.key !== 'wipe').map((t) => (
              <button
                key={t.key}
                className={`btn ${t.danger ? 'btn-danger' : ''}`}
                disabled={busy}
                title={t.description}
                onClick={() => { void onClick(t); }}
              >
                {t.label}
              </button>
            ))}
          </div>
        </section>
      ))}

      <SavedScriptsPanel device={device} onQueued={() => { void refresh(); }} />

      <ScriptPanel device={device} onQueued={() => { void refresh(); }} />

      <RecentActivity history={history} activity={activity} onCancel={(c) => { void cancel(c); }} busy={busy} />


      {active && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="modal">
            <h3>{active.label}</h3>
            <p className="muted">{active.description}</p>
            {active.params?.map((p) => (
              <label key={p.key} className="field">
                <span>{p.label}</span>
                {p.kind === 'select' ? (
                  <select className="sel" value={values[p.key] ?? ''} onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}>
                    <option value="">Elegir…</option>
                    {p.options?.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                ) : (
                  <input
                    type={p.kind === 'password' ? 'password' : p.kind === 'number' ? 'number' : 'text'}
                    placeholder={p.placeholder}
                    value={values[p.key] ?? ''}
                    onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
                  />
                )}
              </label>
            ))}
            {invalid && Object.values(values).some(Boolean) && <p className="field-error">{invalid}</p>}
            {active.confirm === 'type-to-confirm' && (
              <label className="field">
                <span>Escribe <strong>BORRAR</strong> para confirmar</span>
                <input value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />
              </label>
            )}
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={() => setActive(null)}>Cancelar</button>
              <button
                className={`btn ${active.danger ? 'btn-danger' : 'btn-primary'}`}
                disabled={busy || !canSend}
                onClick={() => { void confirmAndSend(); }}
              >
                {active.danger ? <span key="confirm">Confirmar</span> : <span key="send">Enviar</span>}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function powerLabel(mode?: string | null): string {
  if (mode === 'alwaysOn') return 'Siempre conectado';
  if (mode === 'adaptive') return 'Ahorro de batería';
  return '—';
}

function DeviceStatePanel({ state }: { state: DeviceState | null }) {
  if (!state) return <p className="muted">Aún no ha reportado su estado.</p>;
  const seen = state.updatedAt ? new Date(state.updatedAt).toLocaleTimeString() : '—';
  return (
    <dl className="state-grid">
      <div><dt>Batería</dt><dd>{state.battery < 0 ? '—' : `${state.battery}%`}{state.charging ? ' ⚡' : ''}</dd></div>
      <div><dt>Pantalla</dt><dd>{state.locked ? 'Bloqueada' : 'Desbloqueada'}</dd></div>
      <div><dt>Quiosco</dt><dd>{state.kioskActive ? 'Activo' : 'Inactivo'}</dd></div>
      <div><dt>Android</dt><dd>{state.androidRelease || '—'}</dd></div>
      <div><dt>Agente</dt><dd>{state.agentVersion || '—'}</dd></div>
      <div><dt>Conectividad</dt><dd>{powerLabel(state.powerMode)}</dd></div>
      <div><dt>Estado a las</dt><dd>{seen}</dd></div>
    </dl>
  );
}

/**
 * "Órdenes recientes" split into two tabs:
 *  - "Enviados desde consola": the command history (what the console sent TO the device).
 *  - "Actividad en el dispositivo": on-device user activity (what the user did ON the device — app/camera openings).
 */
function RecentActivity({ history, activity, onCancel, busy }: {
  history: CommandHistoryItem[]; activity: DeviceEvent[]; onCancel?: (c: CommandHistoryItem) => void; busy?: boolean;
}) {
  const [tab, setTab] = useState<'sent' | 'onDevice'>('sent');
  return (
    <section className="timeline">
      <div className="tabs" role="tablist" style={{ marginBottom: 12 }}>
        <button role="tab" className={tab === 'sent' ? 'on' : ''} onClick={() => setTab('sent')}>
          Enviados desde consola
        </button>
        <button role="tab" className={tab === 'onDevice' ? 'on' : ''} onClick={() => setTab('onDevice')}>
          Actividad en el dispositivo
        </button>
      </div>
      {tab === 'sent' ? <CommandTimeline items={history} onCancel={onCancel} busy={!!busy} /> : <ActivityTimeline items={activity} />}
    </section>
  );
}

/** "1,4 s" under a minute, then "3 min 20 s", "2 h 5 min", "3 d 4 h". */
function fmtLatency(ms?: number): string | null {
  if (ms === undefined || ms === null || ms < 0) return null;
  if (ms < 60_000) return `${(ms / 1000).toLocaleString('es-CO', { maximumFractionDigits: 1 })} s`;
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 3600) return `${Math.floor(totalSec / 60)} min ${totalSec % 60} s`;
  const h = Math.floor(totalSec / 3600);
  if (h < 48) return `${h} h ${Math.floor((totalSec % 3600) / 60)} min`;
  return `${Math.floor(h / 24)} d ${h % 24} h`;
}

const fmtClock = (ts?: number) => (ts ? new Date(ts).toLocaleString('es-CO') : null);

function CommandTimeline({ items, onCancel, busy }: {
  items: CommandHistoryItem[]; onCancel?: (c: CommandHistoryItem) => void; busy: boolean;
}) {
  if (!items.length) return <p className="muted">Aún no se han enviado órdenes a este dispositivo.</p>;
  return (
    <ul>
      {items.map((c) => {
        const open = c.status === 'pending' || c.status === 'delivered';
        const ran = c.status === 'done' || c.status === 'failed' || c.status === 'unsupported';
        const deliveryLat = fmtLatency(c.deliveryMs);
        const totalLat = fmtLatency(c.totalMs);
        const endWord = c.status === 'cancelled' ? 'Cancelada' : c.status === 'expired' ? 'Venció' : 'Completada';
        return (
          <li key={String(c.id)} className={`timeline-item status-${c.status}`}>
            <span className="t-type" title={c.type}>{COMMAND_LABELS[c.type] ?? c.type}{c.subject ? ` · ${c.subject}` : ''}</span>
            <span className={`t-status status-${c.status}`}>
              {c.type === 'device.wipe' && c.status === 'accepted' ? 'Borrado iniciado por el equipo'
                : c.type === 'device.wipe' && c.status === 'done' ? 'Borrado ejecutado' : STATUS_LABELS[c.status] ?? c.status}
            </span>
            {open && onCancel && (
              <button type="button" className="btn btn-sm t-cancel" disabled={busy} onClick={() => onCancel(c)}>
                Cancelar
              </button>
            )}
            <span className="t-steps muted small">
              <span>Creada {fmtClock(c.createdAt) ?? '—'}</span>
              <span>→ Entregada {c.deliveredAt ? `${fmtClock(c.deliveredAt)}${deliveryLat ? ` (${deliveryLat})` : ''}` : '—'}</span>
              <span>→ {endWord} {c.completedAt ? `${fmtClock(c.completedAt)}${ran && totalLat ? ` (${totalLat})` : ''}` : '—'}</span>
            </span>
            <span className="t-who muted small">
              Enviada por {c.issuerName || '—'}{c.issuerIp ? ` · ${c.issuerIp}` : ''}
              {(c.deliveries ?? 0) > 1 ? ` · entregada ${c.deliveries} veces` : ''}
            </span>
            {c.status === 'pending' && (
              <span className="t-detail">En cola: se ejecutará cuando el equipo vuelva a conectarse.</span>
            )}
            {c.detail && <CommandDetail text={c.detail} />}
          </li>
        );
      })}
    </ul>
  );
}

const ACTIVITY_LABELS: Record<string, string> = {
  appOpened: 'Abrió una app',
  cameraOpened: 'Abrió la cámara',
};

function ActivityTimeline({ items }: { items: DeviceEvent[] }) {
  if (!items.length) {
    return (
      <p className="muted">
        Sin actividad registrada. Requiere acceso de uso en el agente; solo se observa qué app pasa a primer plano
        (no toques ni contenido).
      </p>
    );
  }
  return (
    <ul>
      {items.map((e) => (
        <li key={e.id} className="timeline-item">
          <span className="t-type">{ACTIVITY_LABELS[e.type] ?? e.type}</span>
          {e.detail && <span className="t-detail">{e.detail}</span>}
          <span className="t-when muted small">{new Date(e.ts).toLocaleString('es-CO')}</span>
        </li>
      ))}
    </ul>
  );
}

const STATUS_LABELS: Record<string, string> = {
  pending: 'Pendiente', delivered: 'Entregado', accepted: 'Entregado', done: 'Ejecutado',
  failed: 'Fallido', unsupported: 'No soportado', expired: 'Vencido', cancelled: 'Cancelado',
};

const COMMAND_LABELS: Record<string, string> = {
  'kiosk.enter': 'Entrar en quiosco', 'kiosk.exit': 'Salir del quiosco', 'config.apply': 'Aplicar política',
  'app.install': 'Instalar app', 'app.uninstall': 'Desinstalar app', 'agent.update': 'Actualizar agente',
  'device.lock': 'Bloquear', 'device.reboot': 'Reiniciar', 'device.ring': 'Hacer sonar', 'device.ringStop': 'Dejar de sonar', 'device.display': 'Pantalla y letra', 'device.diagnose': 'Diagnóstico', 'device.silence': 'Silenciar', 'device.notificationAction': 'Botón de notificación',
  'device.alert': 'Mensaje', 'device.lockscreenMessage': 'Mensaje en pantalla de bloqueo', 'device.passcodeReset': 'Cambiar código',
  'device.wipe': 'Borrar dispositivo', 'device.powerMode': 'Modo de conexión', 'device.locationMode': 'Modo de ubicación', 'device.dataSharing': 'Compartir datos',
  'device.appLaunch': 'Abrir app', 'device.openStore': 'Abrir en Play Store', 'apps.scan': 'Escanear apps', 'apps.icons': 'Leer íconos', 'policy.apply': 'Aplicar restricción',
  'device.storageScan': 'Analizar almacenamiento', 'device.storageClean': 'Liberar espacio', 'device.storageAccess': 'Pedir acceso',
  'device.announce': 'Anuncio', 'device.clearCache': 'Borrar caché', 'device.systemUpdate': 'Actualización de Android', 'remote.vnc.start': 'Iniciar remoto', 'remote.vnc.stop': 'Terminar remoto', 'remote.inputSetup': 'Activar control remoto',
  'config.sync': 'Sincronizar política',
  'device.lostMode': 'Modo perdido',
};

const DETAIL_LIMIT = 160;

function CommandDetail({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  if (text.length <= DETAIL_LIMIT) return <span className="t-detail">{text}</span>;
  return (
    <span className={`t-detail ${open ? 'open' : 'clamped'}`}>
      {open ? text : `${text.slice(0, DETAIL_LIMIT)}…`}
      <button type="button" className="t-more" onClick={() => setOpen((v) => !v)}>
        {open ? <span key="less">ver menos</span> : <span key="more">ver más</span>}
      </button>
    </span>
  );
}
