import { useEffect, useState } from 'react';
import { getConfigCompliance, type ComplianceDevice, type ConfigCompliance } from '../api/configSync';

const STATUS_LABEL: Record<string, string> = {
  outOfSync: 'No ha aplicado la última versión',
  neverSeen: 'Aún no reporta',
  unsupported: 'Agente desactualizado',
};

function fmtDate(ms?: number | null): string {
  if (!ms) return '—';
  try {
    return new Date(ms).toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' });
  } catch {
    return new Date(ms).toISOString();
  }
}

/**
 * Compliance badge + list for a policy: "N equipos no han aplicado la última versión", backed by the
 * drift already tracked server-side (appliedConfigRevision != current revision). Reusable in the editor.
 */
export function ComplianceCard({ configId }: { configId: number }) {
  const [data, setData] = useState<ConfigCompliance | null>(null);
  const [error, setError] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setError(false);
    getConfigCompliance(configId)
      .then((d) => { if (!cancelled) setData(d); })
      .catch(() => { if (!cancelled) setError(true); });
    return () => { cancelled = true; };
  }, [configId]);

  if (error) return null; // non-blocking: compliance is informational
  if (data === null) return null;

  // Not applied the latest version = supported agents out of sync + those that never reported.
  const pending = data.outOfSync + data.neverSeen;
  const devices = data.devices ?? [];

  if (data.total === 0) return null;

  if (pending === 0 && data.unsupported === 0) {
    return (
      <div className="banner" role="status" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span className="cfg-badge ok">Al día</span>
        <span>Los {data.total} equipos aplicaron la última versión de esta política.</span>
      </div>
    );
  }

  return (
    <div className="banner banner-alert" role="status" style={{ display: 'block' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span className="cfg-badge alert">Fuera de cumplimiento</span>
        <strong>
          {pending > 0
            ? `${pending} equipo${pending === 1 ? '' : 's'} no ${pending === 1 ? 'ha' : 'han'} aplicado la última versión`
            : 'Todos los equipos compatibles están al día'}
        </strong>
        {data.unsupported > 0 ? (
          <span className="muted">· {data.unsupported} con agente desactualizado</span>
        ) : null}
        <span style={{ marginLeft: 'auto' }}>
          <span className="mono muted">{data.inSync}/{data.total} al día</span>
          {devices.length > 0 ? (
            <button className="btn btn-sm" style={{ marginLeft: 10 }} onClick={() => setOpen((v) => !v)}>
              {open ? 'Ocultar equipos' : 'Ver equipos'}
            </button>
          ) : null}
        </span>
      </div>
      {open && devices.length > 0 ? (
        <ul className="compliance-list" style={{ listStyle: 'none', margin: '12px 0 0', padding: 0, display: 'grid', gap: 6 }}>
          {devices.map((d) => <DeviceRow key={d.number} d={d} />)}
        </ul>
      ) : null}
    </div>
  );
}

function DeviceRow({ d }: { d: ComplianceDevice }) {
  return (
    <li style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '4px 0', borderTop: '1px solid var(--line, #e5e7eb)' }}>
      <span style={{ fontWeight: 600 }}>{d.description || d.number}</span>
      {d.description ? <span className="mono muted" style={{ fontSize: 12 }}>{d.number}</span> : null}
      <span className="muted" style={{ marginLeft: 'auto', fontSize: 12 }}>
        {STATUS_LABEL[d.status] ?? d.status}
        {d.status === 'outOfSync' && d.appliedAt ? ` · aplicó ${fmtDate(d.appliedAt)}` : ''}
        {d.status !== 'outOfSync' && d.lastUpdate ? ` · visto ${fmtDate(d.lastUpdate)}` : ''}
      </span>
    </li>
  );
}
