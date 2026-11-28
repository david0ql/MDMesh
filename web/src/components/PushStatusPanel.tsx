import { useEffect, useState } from 'react';
import { getPushStatus, type PushStatus } from '../api/wake';
import { fmtDateTime, fmtRelative } from '../ui/format';

/**
 * Settings: state of the push wake channel (Firebase Cloud Messaging). Read-only — the project's credential is
 * loaded on the server (environment / secret), never through the console. See docs/DESPERTADOR-FCM.md.
 */
export function PushStatusPanel() {
  const [status, setStatus] = useState<PushStatus | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let on = true;
    const load = () => getPushStatus()
      .then((s) => { if (on) { setStatus(s); setFailed(false); } })
      .catch(() => { if (on) setFailed(true); });
    void load();
    const t = setInterval(() => void load(), 30_000);
    return () => { on = false; clearInterval(t); };
  }, []);

  if (failed && !status) return null; // not an administrator, or an older server
  return (
    <section className="panel" data-testid="push-status">
      <div className="panel-head">
        <h2 className="panel-title">Despertador push (FCM)</h2>
        {status ? (
          <span className={status.configured ? 'chip tone-ok' : status.problem ? 'chip tone-warn' : 'chip'}
                data-testid="push-status-chip">
            {status.configured ? 'Configurado' : status.problem ? 'Incompleto' : 'No configurado'}
          </span>
        ) : null}
      </div>
      <div style={{ padding: '0 16px 14px' }}>
        {!status ? <p className="muted">Cargando…</p> : (
          <>
            <p className="muted">
              Segundo canal para despertar a un equipo cuando su canal en vivo está cerrado: un mensaje de alta
              prioridad que solo dice «repórtate»; la orden viaja después por el canal seguro de siempre.
              {status.configured
                ? ' Se usa cuando hay una orden para un equipo sin canal en vivo y, como refuerzo, en órdenes urgentes (bloqueo, alarma, mensaje, borrado, sesión remota).'
                : ' Sin configurar, todo funciona como hasta ahora: las órdenes esperan al próximo reporte del equipo.'}
            </p>
            {status.problem ? <div className="banner banner-alert" role="alert">{status.problem}</div> : null}
            {!status.configured && !status.problem ? (
              <p className="muted">
                Para activarlo se necesita un proyecto de Firebase de su organización y cargar su credencial en el
                servidor. Los pasos están en <span className="mono">docs/DESPERTADOR-FCM.md</span>.
              </p>
            ) : null}
            <div>
              <div className="set-row"><span className="k">Proyecto</span><span className="v mono">{status.projectId ?? '—'}</span></div>
              <div className="set-row">
                <span className="k">Equipos registrados</span>
                <span className="v" data-testid="push-devices">{status.devicesWithToken}</span>
              </div>
              <div className="set-row">
                <span className="k">Mensajes desde el arranque</span>
                <span className="v" data-testid="push-counters">
                  {status.sent} enviados · {status.answered} respondidos · {status.failed} con error
                </span>
              </div>
              <div className="set-row">
                <span className="k">Último envío correcto</span>
                <span className="v">{status.lastOkAt ? `${fmtDateTime(status.lastOkAt)} · ${fmtRelative(status.lastOkAt)}` : '—'}</span>
              </div>
              {status.sms ? (
                <div className="set-row"><span className="k">SMS de despertador</span><span className="v">{status.sms}</span></div>
              ) : null}
            </div>
            {status.recentErrors.length > 0 ? (
              <>
                <div className="grp" style={{ marginTop: 12 }}>Errores recientes</div>
                <ul data-testid="push-errors" style={{ margin: 0, paddingLeft: 18 }}>
                  {status.recentErrors.map((e, i) => (
                    <li key={`${e.at}-${i}`} className="small">
                      <span className="muted">{fmtDateTime(e.at)}</span> · <span className="mono">{e.device}</span> · {e.message}
                    </li>
                  ))}
                </ul>
              </>
            ) : status.configured ? <p className="muted small">Sin errores recientes.</p> : null}
          </>
        )}
      </div>
    </section>
  );
}
