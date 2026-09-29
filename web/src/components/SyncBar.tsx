import type { ConfigSyncSummary } from '../api/configSync';

/** "N of M in sync" for one configuration, same visual language as the rollout cohort bar. */
export function SyncBar({ s }: { s: ConfigSyncSummary | undefined }) {
  if (!s || s.total === 0) return <div className="cfg-sync muted">Sin dispositivos</div>;
  const pct = Math.round((s.inSync / s.total) * 100);
  const extras = [
    s.outOfSync > 0 ? `${s.outOfSync} aplicando` : null,
    s.neverSeen > 0 ? `${s.neverSeen} sin reportar` : null,
    s.unsupported > 0 ? `${s.unsupported} desactualizado${s.unsupported === 1 ? '' : 's'}` : null,
  ].filter(Boolean).join(' · ');
  return (
    <div className="cfg-sync" title={extras || undefined}>
      <span className="mono">{s.inSync}/{s.total} sincronizados</span>
      <div className="rollout-track"><div className="rollout-fill" style={{ width: `${pct}%` }} /></div>
      {s.unsupported > 0 ? <span className="ub-warn">{s.unsupported} desactualizado{s.unsupported === 1 ? '' : 's'}</span> : null}
    </div>
  );
}
