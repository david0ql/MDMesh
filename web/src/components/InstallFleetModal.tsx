import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  downloadInstallStatusCsv, getInstallFleetStatus, retryInstall, retryNote,
  INSTALL_STATE_LABEL, INSTALL_STATE_TONE,
  type InstallFleetStatus, type InstallState,
} from '../api/installStatus';
import { useAuth } from '../auth/AuthContext';
import { hasPermission } from '../api/auth';
import { useToast } from '../ui/toast';

const ORDER: InstallState[] = ['failed', 'held', 'installing', 'pending', 'installed', 'not_queued'];
const FILTERS: { id: InstallState | 'all'; label: string }[] = [
  { id: 'all', label: 'Todos' },
  { id: 'installed', label: 'Instalada' },
  { id: 'pending', label: 'Pendientes' },
  { id: 'installing', label: 'Instalando' },
  { id: 'held', label: 'Retenidas' },
  { id: 'failed', label: 'Fallidas' },
];

/**
 * "Instalación en la flota" of one app: how many phones have it at the version ordered, how many are pending or held
 * back, which failed and why (in plain Spanish, with what the phone answered), a retry per phone or for all the
 * failed ones, and a CSV export. Refreshes by itself while it is open.
 */
export function InstallFleetModal({ packageName, label, onClose }: { packageName: string; label: string; onClose: () => void }) {
  const toast = useToast();
  const { user } = useAuth();
  const canRetry = hasPermission(user, 'edit_devices') && hasPermission(user, 'manage_fleet');
  const [status, setStatus] = useState<InstallFleetStatus | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [filter, setFilter] = useState<InstallState | 'all'>('all');
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => getInstallFleetStatus(packageName)
    .then((s) => { setStatus(s); setErr(null); })
    .catch((e) => setErr(e instanceof Error ? e.message : 'No se pudo cargar el estado')), [packageName]);

  useEffect(() => {
    void load();
    const t = window.setInterval(() => void load(), 10_000);
    return () => window.clearInterval(t);
  }, [load]);

  const shown = useMemo(() => {
    const list = (status?.devices ?? []).filter((d) => filter === 'all' || d.state === filter);
    return [...list].sort((a, b) => ORDER.indexOf(a.state) - ORDER.indexOf(b.state) || a.number.localeCompare(b.number));
  }, [status, filter]);

  async function retry(numbers?: string[]) {
    setBusy(numbers ? numbers[0] : 'all');
    try {
      const n = await retryInstall(packageName, numbers);
      toast.push(n > 0 ? 'ok' : 'err', n > 0 ? 'Instalación reenviada' : 'Nada que reintentar',
        n > 0 ? `Se envió de nuevo a ${n} equipo${n === 1 ? '' : 's'}; se instala en su próxima conexión.`
          : 'No hay instalaciones fallidas, o ya hay una orden en camino.');
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo reintentar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(null);
    }
  }

  const c = status?.counts;
  const failed = c?.failed ?? 0;

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal deploy-modal" style={{ maxWidth: 860, width: '94vw' }} onClick={(e) => e.stopPropagation()}
        data-testid="install-fleet-modal">
        <h3>Instalación en la flota: {label}</h3>
        <p className="muted" style={{ marginTop: 2 }}>
          <span className="mono">{packageName}</span>
          {status && status.targetVersionCode > 0 ? ` · versión objetivo: versionCode ${status.targetVersionCode}` : ''}
        </p>

        {err && <div className="banner banner-alert">{err}</div>}

        {status == null && !err ? (
          <div className="empty" style={{ padding: 20 }}><span className="spin" /> Cargando…</div>
        ) : status && status.total === 0 ? (
          <div className="empty" style={{ padding: 20 }}>
            <span className="label">Sin órdenes de instalación</span>
            Ningún equipo ha recibido la orden de instalar esta app. Agrégala a una política (o usa Desplegar) y aquí verás
            cómo avanza en cada equipo.
          </div>
        ) : status && c && (
          <>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', margin: '8px 0 12px' }} data-testid="install-fleet-counts">
              <span className="chip tone-ok" data-testid="count-installed">{c.installed} en la versión objetivo</span>
              <span className="chip" data-testid="count-pending">{c.pending + c.installing} pendientes</span>
              <span className={`chip ${c.held ? 'tone-warn' : ''}`} data-testid="count-held">{c.held} retenidas</span>
              <span className={`chip ${failed ? 'tone-alert' : ''}`} data-testid="count-failed">{failed} fallidas</span>
              <span className="muted small" style={{ alignSelf: 'center' }}>de {status.total} equipo{status.total === 1 ? '' : 's'}</span>
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}>
              {FILTERS.map((f) => (
                <button key={f.id} type="button" className={`btn btn-sm ${filter === f.id ? 'btn-primary' : 'btn-ghost'}`}
                  onClick={() => setFilter(f.id)}>{f.label}</button>
              ))}
            </div>
            <div className="deploy-devlist" style={{ maxHeight: '50vh' }}>
              {shown.length === 0 ? (
                <div className="empty" style={{ padding: 20 }}>Ningún equipo en este estado.</div>
              ) : shown.map((d) => (
                <div key={d.number} className="deploy-devrow" style={{ alignItems: 'flex-start', flexWrap: 'wrap' }}
                  data-testid={`install-row-${d.number}`} data-state={d.state}>
                  <span className={`chip ${INSTALL_STATE_TONE[d.state] ?? ''}`} style={{ minWidth: 86, textAlign: 'center' }}>
                    {INSTALL_STATE_LABEL[d.state] ?? d.state}
                  </span>
                  <span style={{ flex: 1, minWidth: 220 }}>
                    <Link to={`/devices/${d.deviceId}`} className="dd-nm">{d.description || d.number}</Link>
                    <span className="dd-sub"> {d.group ?? ''}{d.installedVersion ? ` · reporta v${d.installedVersion}` : ''}</span>
                    {(d.state === 'failed' || d.state === 'held') && (
                      <span style={{ display: 'block', marginTop: 4 }}>
                        <b>{d.reason}</b>
                        {d.state === 'failed' && <span className="muted small" style={{ display: 'block' }}>{retryNote(d)}</span>}
                        {d.detail && (
                          <span className="muted small mono" style={{ display: 'block', wordBreak: 'break-word' }}>
                            Respuesta del equipo: {d.detail}
                          </span>
                        )}
                      </span>
                    )}
                  </span>
                  {canRetry && d.state === 'failed' && (
                    <button className="btn btn-sm" disabled={busy != null} onClick={() => void retry([d.number])}
                      data-testid={`install-retry-${d.number}`}>
                      {busy === d.number ? 'Enviando…' : 'Reintentar'}
                    </button>
                  )}
                </div>
              ))}
            </div>
          </>
        )}

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cerrar</button>
          {status && status.total > 0 && (
            <button className="btn" onClick={() => downloadInstallStatusCsv(status)} data-testid="install-export">
              Exportar CSV
            </button>
          )}
          {canRetry && failed > 0 && (
            <button className="btn btn-primary" disabled={busy != null} onClick={() => void retry()} data-testid="install-retry-all">
              {busy === 'all' ? 'Enviando…' : `Reintentar las ${failed} fallidas`}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
