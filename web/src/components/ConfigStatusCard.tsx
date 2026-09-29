import { parseOutcomes, summarizeStatus, type ConfigStatus } from '../api/configSync';
import { fmtRelative, DASH } from '../ui/format';

const OUTCOME_LABELS: Record<string, string> = { applied: 'aplicado', unsupported: 'no compatible', failed: 'falló' };

const short = (r?: string | null): string => (r ? r.slice(0, 8) : DASH);

/** Left-rail card: does this device run its configuration's current revision, and what happened per key. */
export function ConfigStatusCard({ status }: { status: ConfigStatus | null }) {
  const v = summarizeStatus(status);
  const outcomes = parseOutcomes(status?.lastCommand?.detail);
  return (
    <div>
      <div className="grp">Estado de la configuración</div>
      <div className="row">
        <span className="k">Estado</span>
        <span className={`v chip tone-${v.tone}`}>{v.label}</span>
      </div>
      <div className="row">
        <span className="k">Aplicada</span>
        <span className="v mono" title={status?.appliedRevision ?? undefined}>
          {short(status?.appliedRevision)}
          {status?.appliedAt ? ` · ${fmtRelative(status.appliedAt)}` : ''}
        </span>
      </div>
      <div className="row">
        <span className="k">Actual</span>
        <span className="v mono" title={status?.currentRevision ?? undefined}>
          {short(status?.currentRevision)}
        </span>
      </div>
      {v.label === 'Agente desactualizado' ? (
        <p className="muted">Actualiza el agente (Ajustes → Actualizaciones) para que se aplique la configuración.</p>
      ) : null}
      {outcomes ? (
        <ul className="cfg-outcomes">
          {Object.entries(outcomes.outcomes).map(([k, o]) => (
            <li key={k} className={o === 'applied' ? 'ok' : o === 'unsupported' ? 'muted' : 'alert'}>
              <span className="mono">{k}</span> <span>{OUTCOME_LABELS[o] ?? o}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
