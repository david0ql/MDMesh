import { ScriptPanel } from './ScriptPanel';
import { useCallback, useEffect, useState } from 'react';
import {
  ACTION_TEMPLATES, type CommandTemplateExt, queueCommand, getDeviceState,
  listCommandHistory, forceSync, type DeviceState, type CommandHistoryItem,
} from '../api/commands';
import { useToast } from '../ui/toast';
import { KioskToggle } from './KioskToggle';

type Device = { number: string };

const GROUPS: Array<{ id: 'safe' | 'disruptive' | 'destructive'; title: string }> = [
  { id: 'safe', title: 'Seguras' },
  { id: 'disruptive', title: 'Interrumpen' },
  { id: 'destructive', title: 'Destructivas' },
];

export function ActionConsole({ device }: { device: Device }) {
  const toast = useToast();
  const [state, setState] = useState<DeviceState | null>(null);
  const [history, setHistory] = useState<CommandHistoryItem[]>([]);
  const [active, setActive] = useState<CommandTemplateExt | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const [st, hist] = await Promise.all([
      getDeviceState(device.number).catch(() => null),
      listCommandHistory(device.number).catch(() => []),
    ]);
    setState(st);
    setHistory(hist);
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
      toast.push('ok', `${t.label}: enviado`, id ? `Orden ${id}` : '');
      await forceSync(device.number).catch(() => undefined); // nudge (no-op until MQTT lands)
      await refresh();
    } catch (e) {
      toast.push('err', `${t.label}: falló`, e instanceof Error ? e.message : '');
    } finally {
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
    : true;

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

      {GROUPS.map((g) => (
        <section key={g.id} className="action-group">
          <h3 className="action-group-title">{g.title}</h3>
          <div className="action-grid">
            {ACTION_TEMPLATES.filter((t) => (t.group ?? 'safe') === g.id && !t.key.startsWith('kiosk-')).map((t) => (
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

      <ScriptPanel device={device} onQueued={() => { void refresh(); }} />

      <CommandTimeline items={history} />


      {active && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="modal">
            <h3>{active.label}</h3>
            <p className="muted">{active.description}</p>
            {active.params?.map((p) => (
              <label key={p.key} className="field">
                <span>{p.label}</span>
                <input
                  type={p.kind === 'password' ? 'password' : p.kind === 'number' ? 'number' : 'text'}
                  placeholder={p.placeholder}
                  value={values[p.key] ?? ''}
                  onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
                />
              </label>
            ))}
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

function CommandTimeline({ items }: { items: CommandHistoryItem[] }) {
  if (!items.length) return null;
  return (
    <section className="timeline">
      <h3 className="action-group-title">Órdenes recientes</h3>
      <ul>
        {items.map((c) => (
          <li key={String(c.id)} className={`timeline-item status-${c.status}`}>
            <span className="t-type" title={c.type}>{COMMAND_LABELS[c.type] ?? c.type}{c.subject ? ` · ${c.subject}` : ''}</span>
            <span className={`t-status status-${c.status}`}>{STATUS_LABELS[c.status] ?? c.status}</span>
            {(c.completedAt ?? c.createdAt) && (
              <span className="t-when muted small">{new Date((c.completedAt ?? c.createdAt) as number).toLocaleString('es-CO')}</span>
            )}
            {c.detail && <CommandDetail text={c.detail} />}
          </li>
        ))}
      </ul>
    </section>
  );
}

const STATUS_LABELS: Record<string, string> = {
  pending: 'En cola', delivered: 'Entregada', accepted: 'Recibida', done: 'Hecha',
  failed: 'Falló', unsupported: 'No soportada', expired: 'Venció',
};

const COMMAND_LABELS: Record<string, string> = {
  'kiosk.enter': 'Entrar en quiosco', 'kiosk.exit': 'Salir del quiosco', 'config.apply': 'Aplicar política',
  'app.install': 'Instalar app', 'app.uninstall': 'Desinstalar app', 'agent.update': 'Actualizar agente',
  'device.lock': 'Bloquear', 'device.reboot': 'Reiniciar', 'device.ring': 'Hacer sonar', 'device.ringStop': 'Dejar de sonar',
  'device.alert': 'Mensaje', 'device.lockscreenMessage': 'Mensaje en pantalla de bloqueo', 'device.passcodeReset': 'Cambiar código',
  'device.wipe': 'Borrar dispositivo', 'device.powerMode': 'Modo de conexión', 'device.locationMode': 'Modo de ubicación',
  'device.appLaunch': 'Abrir app', 'device.openStore': 'Abrir en Play Store', 'apps.scan': 'Escanear apps', 'apps.icons': 'Leer íconos', 'policy.apply': 'Aplicar restricción',
  'device.storageScan': 'Analizar almacenamiento', 'device.storageClean': 'Liberar espacio', 'device.storageAccess': 'Pedir acceso',
  'remote.vnc.start': 'Iniciar remoto', 'remote.vnc.stop': 'Terminar remoto', 'remote.inputSetup': 'Activar control remoto',
  'config.sync': 'Sincronizar política',
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
