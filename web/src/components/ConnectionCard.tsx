import type { DeviceConnection } from '../ui/connection';
import { fmtDateTime, fmtRelative } from '../ui/format';

function duration(ms: number): string {
  const min = Math.max(1, Math.round(ms / 60_000));
  if (min < 120) return `${min} min`;
  return `${Math.floor(min / 60)} h ${min % 60} min`;
}

/**
 * Left-rail card: what the agent reported about its connection — the last network failure (kind, network it
 * happened on, who issued the certificate it was shown) and the last silence with its cause. This is the evidence
 * for "was it our server or the site's network".
 */
export function ConnectionCard({ connection }: { connection?: DeviceConnection }) {
  const f = connection?.lastFailure;
  const s = connection?.lastStop;
  return (
    <div>
      <div className="grp">Última falla de conexión</div>
      {!connection || (!f && !s) ? (
        <p className="muted">
          Sin fallas reportadas. El agente informa la causa de cada corte desde la versión 0.9.9.
        </p>
      ) : null}
      {f ? (
        <>
          <div className="row">
            <span className="k">Falla</span>
            <span className="v" title={f.detail ?? undefined}>{f.label}</span>
          </div>
          <div className="row">
            <span className="k">Cuándo</span>
            <span className="v">
              {f.at ? `${fmtDateTime(f.at)} · ${fmtRelative(f.at)}` : '—'}
              {f.count && f.count > 1 ? ` · ${f.count} intentos` : ''}
            </span>
          </div>
          <div className="row">
            <span className="k">Red del equipo</span>
            <span className="v">{f.networkLabel ?? '—'}</span>
          </div>
          <div className="row">
            <span className="k">Canal</span>
            <span className="v">
              {f.channel === 'socket' ? 'Canal en vivo (WebSocket)' : 'Reporte (HTTPS)'}
              {f.host ? ` · ${f.host}` : ''}
            </span>
          </div>
          {f.certIssuer ? (
            <div className="row">
              <span className="k">Emisor del certificado</span>
              <span className="v mono" title="Si no es la autoridad pública del servidor, la red inspecciona el tráfico TLS.">
                {f.certIssuer}
              </span>
            </div>
          ) : null}
          {f.detail ? <p className="muted mono" style={{ wordBreak: 'break-word' }}>{f.detail}</p> : null}
        </>
      ) : null}
      {s ? (
        <>
          <div className="row">
            <span className="k">Último corte</span>
            <span className="v">{s.label}</span>
          </div>
          <div className="row">
            <span className="k">Desde</span>
            <span className="v">
              {s.at ? fmtDateTime(s.at) : '—'}
              {s.at && s.resumedAt ? ` · duró ${duration(s.resumedAt - s.at)}` : s.at ? ' · sin volver aún' : ''}
              {s.battery != null ? ` · batería ${s.battery}%` : ''}
            </span>
          </div>
        </>
      ) : null}
      {connection && (connection.reconnects || connection.usingBackup || connection.serverHost) ? (
        <div className="row">
          <span className="k">Servidor</span>
          <span className="v">
            {connection.serverHost ?? '—'}
            {connection.usingBackup ? ' (dirección de respaldo)' : ''}
            {connection.reconnects ? ` · ${connection.reconnects} reconexiones` : ''}
          </span>
        </div>
      ) : null}
    </div>
  );
}
