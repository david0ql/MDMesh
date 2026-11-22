import { useEffect, useState } from 'react';
import { getConfigurationHistory, parseChanges, type ConfigAuditEntry } from '../api/configurations';
import { PRIMARY_FIELDS, LEGACY_FIELDS } from '../data/configFields';

// Human label for a configuration field key (falls back to the raw key).
const FIELD_LABELS: Record<string, string> = Object.fromEntries(
  [...PRIMARY_FIELDS, ...LEGACY_FIELDS].map((f) => [f.key, f.label]),
);
// A few keys the editor writes that aren't in the schema-driven field list.
const EXTRA_LABELS: Record<string, string> = {
  dcPolicy: 'Políticas del agente (DallyControl)',
  applications: 'Apps asignadas',
  kioskMode: 'Modo quiosco',
};
const fieldLabel = (key: string) => FIELD_LABELS[key] ?? EXTRA_LABELS[key] ?? key;

const ACTION_LABEL: Record<string, string> = {
  create: 'Creó la política',
  update: 'Editó la política',
  delete: 'Eliminó la política',
};
const ACTION_CLASS: Record<string, string> = { create: 'ok', update: '', delete: 'alert' };

function fmtDate(ms?: number | null): string {
  if (!ms) return '—';
  try {
    return new Date(ms).toLocaleString('es', { dateStyle: 'medium', timeStyle: 'short' });
  } catch {
    return new Date(ms).toISOString();
  }
}

function fmtValue(v: unknown): string {
  if (v === null || v === undefined || v === '') return '∅ (vacío)';
  if (typeof v === 'boolean') return v ? 'Sí' : 'No';
  if (Array.isArray(v)) return v.length === 0 ? '∅ (vacío)' : `${v.length} elemento${v.length === 1 ? '' : 's'}`;
  if (typeof v === 'object') return 'objeto';
  const s = String(v);
  return s.length > 80 ? `${s.slice(0, 77)}…` : s;
}

/**
 * "Historial de cambios": who changed the policy, when, and what fields changed (the diff),
 * from the server-side audit trail (config_audit). Read-only.
 */
export function ConfigHistoryPanel({ configId }: { configId: number }) {
  const [entries, setEntries] = useState<ConfigAuditEntry[] | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setError(false);
    getConfigurationHistory(configId)
      .then((rows) => { if (!cancelled) setEntries(rows); })
      .catch(() => { if (!cancelled) setError(true); });
    return () => { cancelled = true; };
  }, [configId]);

  if (error) {
    return <div className="banner banner-alert">No se pudo cargar el historial de cambios.</div>;
  }
  if (entries === null) {
    return <div className="panel"><div className="empty"><span className="spin" /> Cargando historial…</div></div>;
  }
  if (entries.length === 0) {
    return (
      <div className="panel">
        <div className="empty"><span className="label">Sin cambios registrados</span>
          Los cambios que hagas a partir de ahora quedarán aquí: quién, cuándo y qué cambió.
        </div>
      </div>
    );
  }

  return (
    <section className="panel cfg-panel">
      <div className="cfg-sec-h">Historial de cambios</div>
      <p className="note" style={{ margin: '0 0 12px' }}>
        Cada vez que alguien guarda esta política queda registrado quién fue, cuándo y qué cambió.
      </p>
      <ol className="audit-list" style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 12 }}>
        {entries.map((e) => <HistoryItem key={e.id} entry={e} />)}
      </ol>
    </section>
  );
}

function HistoryItem({ entry }: { entry: ConfigAuditEntry }) {
  const changes = parseChanges(entry.changesJson);
  const diff = changes?.diff ?? null;
  const diffKeys = diff ? Object.keys(diff) : [];
  return (
    <li className="audit-item" style={{ border: '1px solid var(--line, #e5e7eb)', borderRadius: 10, padding: '10px 12px' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
        <span className={`cfg-badge ${ACTION_CLASS[entry.action] ?? ''}`.trim()}>{ACTION_LABEL[entry.action] ?? entry.action}</span>
        <strong>{entry.userName ?? 'Usuario desconocido'}</strong>
        <span className="muted" style={{ marginLeft: 'auto' }}>{fmtDate(entry.createdAt)}</span>
      </div>
      {entry.action === 'update' && diff ? (
        diffKeys.length === 0 ? (
          <div className="muted" style={{ marginTop: 6 }}>Sin cambios en los campos (guardado sin modificaciones).</div>
        ) : (
          <table className="audit-diff" style={{ marginTop: 8, width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--muted, #6b7280)' }}>
                <th style={{ padding: '2px 8px 2px 0' }}>Campo</th>
                <th style={{ padding: '2px 8px' }}>Antes</th>
                <th style={{ padding: '2px 8px' }}>Después</th>
              </tr>
            </thead>
            <tbody>
              {diffKeys.map((k) => (
                <tr key={k}>
                  <td style={{ padding: '2px 8px 2px 0', fontWeight: 600 }}>{fieldLabel(k)}</td>
                  <td className="mono" style={{ padding: '2px 8px', color: 'var(--muted, #6b7280)' }}>{fmtValue(diff[k].from)}</td>
                  <td className="mono" style={{ padding: '2px 8px' }}>{fmtValue(diff[k].to)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      ) : null}
      {entry.revision ? (
        <div className="muted mono" style={{ marginTop: 8, fontSize: 12 }} title="Revisión (SHA-256) resultante del documento de configuración">
          revisión {entry.revision.slice(0, 12)}
        </div>
      ) : null}
    </li>
  );
}
