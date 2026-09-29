import { useEffect, useRef, useState } from 'react';
import { getUpdateStatus, applyUpdate, isApplyTerminal, type UpdateStatus } from '../api/updates';

const PHASE_LABEL: Record<string, string> = {
  authorizing: 'Autorizando…',
  backup: 'Respaldando la base de datos…',
  pull: 'Descargando las nuevas imágenes…',
  recreate: 'Reiniciando los servicios…',
  healthcheck: 'Verificando el estado…',
  done: 'Actualización completada',
  rollback: 'Revirtiendo…',
  rolled_back: 'La actualización falló: se revirtió',
  failed: 'La actualización falló',
};

/** "Update available" banner + one-click apply with live phase progress. Talks to the decoupled
 *  supervisor (origin /update/*), so it works even while the API server is mid-restart. */
export function UpdateBanner() {
  const [s, setS] = useState<UpdateStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const poke = useRef<() => void>(() => {});

  useEffect(() => {
    let on = true;
    const loop = async () => {
      const x = await getUpdateStatus();
      if (!on) return;
      setS(x);
      const active = !!x?.apply && !isApplyTerminal(x.apply.phase);
      clearTimeout(timer.current);
      timer.current = setTimeout(loop, active ? 3000 : 30 * 60 * 1000); // poll fast while an apply runs
    };
    poke.current = () => { clearTimeout(timer.current); void loop(); };
    void loop();
    return () => { on = false; clearTimeout(timer.current); };
  }, []);

  if (!s) return null;
  const apply = s.apply;
  const active = !!apply && !isApplyTerminal(apply.phase);
  const failed = !!apply && (apply.phase === 'failed' || apply.phase === 'rolled_back');

  // While an apply is running (or just failed), that takes over the banner.
  if (apply && (active || failed)) {
    return (
      <div className={`update-banner ${failed ? 'ub-fail' : 'ub-busy'}`}>
        <span>
          {failed ? '⚠ ' : ''}{PHASE_LABEL[apply.phase] || apply.phase}
          {apply.toVersion ? <span className="ub-ch"> → v{apply.toVersion}</span> : null}
          {apply.error ? <span className="ub-warn"> · {apply.error}</span> : null}
        </span>
        {failed
          ? <a className="btn btn-sm" href="/recovery">Recuperación…</a>
          : <span className="ub-spin" aria-label="trabajando" />}
      </div>
    );
  }

  if (!s.updateAvailable) return null;

  const onClick = async () => {
    if (!window.confirm(
      `¿Actualizar a v${s.latest}?\n\nEl servidor se reinicia por un momento. Primero se respalda la base de datos y la `
      + `actualización se revierte automáticamente si falla.`,
    )) return;
    setBusy(true);
    setErr(null);
    const r = await applyUpdate();
    setBusy(false);
    if (!r.ok) setErr(r.error || 'No se pudo iniciar la actualización');
    else poke.current(); // re-poll immediately so progress shows right away
  };

  return (
    <div className="update-banner">
      <span>
        Actualización disponible: <b>v{s.latest}</b>
        {s.verified ? <span className="ub-ok"> ✓ verificada</span> : <span className="ub-warn"> · sin verificar</span>}
        {s.channel ? <span className="ub-ch"> ({s.channel})</span> : null}
        {err ? <span className="ub-warn"> · {err}</span> : null}
      </span>
      {s.applySupported !== false ? (
        <button className="btn btn-sm" onClick={onClick} disabled={busy || !s.verified}>
          {busy ? <span key="busy">Iniciando…</span> : <span key="idle">Actualizar…</span>}
        </button>
      ) : (
        // Source installs (Docker from source or native) can't self-apply — Settings › Updates has the steps.
        <a className="btn btn-sm" href="/settings">Detalles…</a>
      )}
    </div>
  );
}
