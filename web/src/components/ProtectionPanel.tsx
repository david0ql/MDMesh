import { useCallback, useEffect, useState } from 'react';
import { queueCommand, forceSync, OFFLINE_QUEUED_NOTE } from '../api/commands';
import {
  getDeviceProtection, lostModeProblem, lostModeRequest, wipeDefaults, wipeRequest,
  type DeviceProtection, type LostModeOptions, type WipeIntent, type WipeOptions,
} from '../api/protection';
import { useToast } from '../ui/toast';

type Device = { number: string };

const SOURCE_LABEL: Record<string, string> = {
  policy: 'de la política', folder: 'de la carpeta', company: 'de la empresa', none: '', off: '',
};

const RULE_LABEL: Record<string, string> = {
  factoryReset: 'Restablecer desde Ajustes', safeBoot: 'Modo seguro', debugging: 'Depuración USB', oemUnlock: 'Desbloqueo del bootloader',
};

const fmt = (ts?: number | null) => (ts ? new Date(ts).toLocaleString('es-CO') : '—');

/**
 * Protection of one device: whether its factory-reset protection is in force (as the agent read it back from the
 * system), lost mode, and the remote wipe with its options. The two anti-theft actions need "disruptive_actions".
 */
export function ProtectionPanel({ device, canDisrupt, onQueued }: {
  device: Device; canDisrupt: boolean; onQueued: () => void;
}) {
  const toast = useToast();
  const [info, setInfo] = useState<DeviceProtection | null>(null);
  const [dialog, setDialog] = useState<'wipe' | 'lost' | null>(null);
  const [busy, setBusy] = useState(false);

  const read = useCallback(async () => {
    const r = await getDeviceProtection(device.number).catch(() => null);
    if (r) setInfo(r);
  }, [device.number]);

  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    const loop = async () => {
      await read();
      if (!on) return;
      t = setTimeout(() => void loop(), 15000);
    };
    void loop();
    return () => { on = false; clearTimeout(t); };
  }, [read]);

  async function send(label: string, req: ReturnType<typeof wipeRequest>) {
    setBusy(true);
    try {
      const res = await queueCommand(device.number, req);
      if (res?.deviceOnline === false) toast.push('ok', `${label}: en cola`, OFFLINE_QUEUED_NOTE);
      else toast.push('ok', `${label}: enviado`, res?.id ? `Orden ${res.id}` : '');
      await forceSync(device.number).catch(() => undefined);
      onQueued();
      setDialog(null);
      await read();
    } catch (e) {
      toast.push('err', `${label}: falló`, e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (!info) return null;
  const lost = info.reported?.lostMode?.active ? info.reported.lostMode : null;
  const held = info.reported?.restrictions ?? {};
  const wanted = (Object.keys(RULE_LABEL) as Array<keyof DeviceProtection['policy']>).filter((k) => info.policy[k] === true);
  const errors = info.reported?.errors ?? [];
  return (
    <section className="prot" data-testid="protection-panel">
      <h3 className="action-group-title">Protección contra restablecimiento y robo</h3>
      <div className={`prot-status prot-${info.state}`} data-testid="frp-status">
        <i aria-hidden="true" />
        <strong>{info.summary}</strong>
      </div>
      <dl className="prot-facts">
        <div>
          <dt>Cuentas que le corresponden</dt>
          <dd>
            {info.expected.source === 'off' ? 'Protección desactivada en su política'
              : info.expected.accounts.length === 0 ? 'Ninguna configurada'
              : `${info.expected.accounts.map((a) => a.email || a.id).join(', ')} (${SOURCE_LABEL[info.expected.source]}${info.expected.groupName ? ` «${info.expected.groupName}»` : ''})`}
          </dd>
        </div>
        <div>
          <dt>Bloqueos de la política</dt>
          <dd>
            {wanted.length === 0 ? 'Ninguno' : wanted.map((k) => (
              <span key={k} className={`prot-rule ${info.reported ? (held[k] ? 'ok' : 'bad') : ''}`}>
                {RULE_LABEL[k]}: {info.reported ? (held[k] ? 'aplicado' : 'no aplicado') : 'sin confirmar'}
              </span>
            ))}
          </dd>
        </div>
        <div>
          <dt>Borrado por intentos fallidos</dt>
          <dd>{info.reported?.maxFailedPasswordsForWipe ? `Tras ${info.reported.maxFailedPasswordsForWipe} intentos` : 'No'}</dd>
        </div>
        <div>
          <dt>Modo perdido</dt>
          <dd>
            {lost ? (
              <span key="on" className="prot-lost">
                Activo desde {fmt(lost.since)}
                {lost.wipeAfterFailedUnlocks ? ` · borra tras ${lost.wipeAfterFailedUnlocks} intentos fallidos` : ''}
                {lost.wipeIfSilentDays ? ` · borra si no reporta en ${lost.wipeIfSilentDays} días (último reporte ${fmt(lost.lastContactAt)})` : ''}
              </span>
            ) : <span key="off">Inactivo</span>}
          </dd>
        </div>
      </dl>
      {errors.length > 0 && (
        <p className="field-error" role="alert">El equipo no pudo aplicar: {errors.join(' · ')}</p>
      )}
      {canDisrupt && (
        <div className="action-grid">
          <button className="btn" disabled={busy || !info.capabilities.lostMode} onClick={() => setDialog('lost')}
            title={info.capabilities.lostMode ? 'Bloquea, muestra un contacto, hace sonar y sigue la ubicación'
              : 'El agente de este equipo es anterior y no tiene modo perdido: actualízalo'}>
            {lost ? <span key="edit">Modo perdido: cambiar o desactivar</span> : <span key="start">Modo perdido</span>}
          </button>
          <button className="btn btn-danger" disabled={busy} onClick={() => setDialog('wipe')}>
            Borrado remoto (restablecer de fábrica)
          </button>
        </div>
      )}
      {canDisrupt && !info.capabilities.lostMode && (
        <p className="muted small">El modo perdido necesita un agente más reciente en este equipo.</p>
      )}
      {dialog === 'wipe' && (
        <WipeDialog info={info} busy={busy} onCancel={() => setDialog(null)}
          onSend={(o) => { void send('Borrado remoto', wipeRequest(o)); }} />
      )}
      {dialog === 'lost' && (
        <LostModeDialog active={!!lost} busy={busy} onCancel={() => setDialog(null)}
          onSend={(o) => { void send(o ? 'Modo perdido' : 'Desactivar modo perdido', lostModeRequest(o)); }} />
      )}
    </section>
  );
}

const INTENTS: Array<{ id: WipeIntent; title: string; text: string }> = [
  {
    id: 'lost', title: 'Equipo perdido o robado',
    text: 'Borra todo y conserva la protección: después del borrado el teléfono solo se activa con la cuenta de la empresa.',
  },
  {
    id: 'reassign', title: 'Preparar para reasignar',
    text: 'Borra todo y quita la protección: el teléfono queda como nuevo para inscribirlo otra vez o entregarlo a otra persona.',
  },
];

function WipeDialog({ info, busy, onCancel, onSend }: {
  info: DeviceProtection; busy: boolean; onCancel: () => void; onSend: (o: WipeOptions) => void;
}) {
  const [o, setO] = useState<WipeOptions | null>(null);
  const [confirm, setConfirm] = useState('');
  const frpWorks = info.state === 'active';
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Borrado remoto">
      <div className="modal prot-modal">
        <h3>Borrado remoto</h3>
        <p className="muted">Restablece el equipo de fábrica. No se puede deshacer. Si el equipo no tiene enlace, la orden
          queda en cola sin vencer y se ejecuta en cuanto vuelva a conectarse.</p>
        <div className="prot-intents" role="radiogroup" aria-label="Motivo del borrado">
          {INTENTS.map((i) => (
            <label key={i.id} className={`prot-intent ${o?.intent === i.id ? 'on' : ''}`}>
              <input type="radio" name="wipe-intent" checked={o?.intent === i.id} onChange={() => setO(wipeDefaults(i.id))} />
              <span><strong>{i.title}</strong><span className="muted small">{i.text}</span></span>
            </label>
          ))}
        </div>
        {o && (
          <div className="prot-options">
            <label><input type="checkbox" checked={o.keepFrp} onChange={(e) => setO({ ...o, keepFrp: e.target.checked })} />
              <span>Conservar la protección de restablecimiento (FRP)</span></label>
            <label><input type="checkbox" checked={o.externalStorage} onChange={(e) => setO({ ...o, externalStorage: e.target.checked })} />
              <span>Borrar también la tarjeta SD / almacenamiento externo</span></label>
            <label><input type="checkbox" checked={o.euicc} onChange={(e) => setO({ ...o, euicc: e.target.checked })} />
              <span>Borrar las eSIM (Android 9 o posterior)</span></label>
            <label><input type="checkbox" checked={o.silent} onChange={(e) => setO({ ...o, silent: e.target.checked })} />
              <span>Borrar sin aviso en pantalla (Android 10 o posterior)</span></label>
            <label className="field">
              <span>Motivo que se muestra en el teléfono (Android 9 a 13)</span>
              <input value={o.reason} maxLength={200} disabled={o.silent} placeholder="Equipo reportado como perdido"
                onChange={(e) => setO({ ...o, reason: e.target.value })} />
            </label>
            {o.keepFrp && !frpWorks && (
              <p className="frp-warning" role="alert">
                Este equipo no tiene la protección activa ({info.summary}). Después del borrado quedará libre para
                quien lo tenga. Configura la cuenta de reactivación y espera a que el equipo la aplique, si aún tiene enlace.
              </p>
            )}
            {!o.keepFrp && o.intent === 'lost' && (
              <p className="frp-warning" role="alert">
                Sin la protección, quien tenga el teléfono podrá usarlo después del borrado.
              </p>
            )}
            {!info.capabilities.wipeOptions && (
              <p className="frp-warning" role="alert">
                El agente de este equipo es anterior: borra el equipo pero no aplica estas opciones (conserva la
                protección y no borra la tarjeta SD).
              </p>
            )}
          </div>
        )}
        <label className="field">
          <span>Escribe <strong>BORRAR</strong> para confirmar</span>
          <input aria-label="Confirmación" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </label>
        <div className="modal-actions">
          <button className="btn" disabled={busy} onClick={onCancel}>Cancelar</button>
          <button className="btn btn-danger" disabled={busy || !o || confirm !== 'BORRAR'} onClick={() => { if (o) onSend(o); }}>
            Borrar el equipo
          </button>
        </div>
      </div>
    </div>
  );
}

function LostModeDialog({ active, busy, onCancel, onSend }: {
  active: boolean; busy: boolean; onCancel: () => void; onSend: (o: LostModeOptions | null) => void;
}) {
  const [o, setO] = useState<LostModeOptions>({
    message: 'Este equipo pertenece a la empresa. Si lo encontró, por favor llame.', phone: '', ring: true,
    wipeAfterFailedUnlocks: '', wipeIfSilentDays: '',
  });
  const [tried, setTried] = useState(false);
  const problem = lostModeProblem(o);
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Modo perdido">
      <div className="modal prot-modal">
        <h3>Modo perdido</h3>
        <p className="muted">Bloquea el equipo de inmediato, muestra el mensaje y el teléfono en la pantalla de bloqueo
          y reporta la ubicación cada minuto. Si no tiene enlace, la orden espera en cola sin vencer.</p>
        <label className="field">
          <span>Mensaje en la pantalla de bloqueo</span>
          <input aria-label="Mensaje" value={o.message} maxLength={240} onChange={(e) => setO({ ...o, message: e.target.value })} />
        </label>
        <label className="field">
          <span>Teléfono de contacto</span>
          <input aria-label="Teléfono de contacto" value={o.phone} maxLength={40} placeholder="+57 300 000 0000"
            onChange={(e) => setO({ ...o, phone: e.target.value })} />
        </label>
        <div className="prot-options">
          <label><input type="checkbox" checked={o.ring} onChange={(e) => setO({ ...o, ring: e.target.checked })} />
            <span>Hacer sonar durante 1 minuto</span></label>
        </div>
        <label className="field">
          <span>Borrar automáticamente tras N intentos fallidos de desbloqueo (opcional)</span>
          <input aria-label="Intentos fallidos" type="number" min={3} max={100} placeholder="no borrar" value={o.wipeAfterFailedUnlocks}
            onChange={(e) => setO({ ...o, wipeAfterFailedUnlocks: e.target.value })} />
        </label>
        <label className="field">
          <span>Borrar automáticamente si no reporta en N días (opcional)</span>
          <input aria-label="Días sin reportar" type="number" min={1} max={365} placeholder="no borrar" value={o.wipeIfSilentDays}
            onChange={(e) => setO({ ...o, wipeIfSilentDays: e.target.value })} />
        </label>
        <p className="muted small">
          Los borrados automáticos los ejecuta el propio teléfono, aunque esté sin SIM ni Wi-Fi, y conservan la
          protección de restablecimiento. El de intentos fallidos solo aplica si el equipo tiene bloqueo de pantalla.
        </p>
        {tried && problem && <p className="field-error" role="alert">{problem}</p>}
        <div className="modal-actions">
          <button className="btn" disabled={busy} onClick={onCancel}>Cancelar</button>
          {active && <button className="btn" disabled={busy} onClick={() => onSend(null)}>Desactivar modo perdido</button>}
          <button className="btn btn-primary" disabled={busy}
            onClick={() => { setTried(true); if (!problem) onSend(o); }}>
            {active ? <span key="update">Actualizar modo perdido</span> : <span key="start">Activar modo perdido</span>}
          </button>
        </div>
      </div>
    </div>
  );
}
