import { Fragment, useCallback, useEffect, useState, type ReactNode } from 'react';
import { DASH } from '../ui/format';
import { errorCode } from '../api/client';
import {
  getDeviceActivity,
  getUserActivity,
  parseDetail,
  RESULT_LABEL,
  type AuditEntry,
  type AuditPage,
} from '../api/audit';

// Shared pieces of the audit trail: the result chip, the expandable detail of a row, and the compact "Actividad"
// list used in the device page (what each technician did on this device) and in Usuarios (what a user did).

export function AuditResultChip({ result }: { result: string }) {
  const tone = result === 'ok' ? 'tone-ok' : result === 'denied' ? 'tone-alert' : 'tone-warn';
  return <span className={`chip ${tone} audit-result`} data-result={result}>{RESULT_LABEL[result] ?? result}</span>;
}

/** Second-precise timestamp: an audit row is evidence, minutes are not enough. */
export function fmtAuditTime(ms?: number): string {
  if (!ms) return DASH;
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return DASH;
  return d.toLocaleString('es-CO', {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

const DETAIL_LABELS: Record<string, string> = {
  duracion: 'Duración de la sesión',
  politica: 'Política',
  carpeta: 'Carpeta',
  carpetaSuperior: 'Carpeta superior',
  archivo: 'Archivo',
  dispositivosAlcanzados: 'Dispositivos alcanzados',
  accionDisruptiva: 'Acción que interrumpe al usuario',
  registrosEliminados: 'Registros eliminados',
  retencionDias: 'Retención (días)',
  anterioresA: 'Anteriores a',
  recortado: 'Detalle recortado',
  resumen: 'Resumen',
  detalle: 'Detalle',
};

function show(v: unknown): string {
  if (v === null || v === undefined) return DASH;
  if (typeof v === 'boolean') return v ? 'Sí' : 'No';
  if (typeof v === 'object') return JSON.stringify(v);
  const s = String(v);
  return s === '' ? DASH : s;
}

function KeyValues({ data }: { data: Record<string, unknown> }) {
  return (
    <dl className="audit-kv">
      {Object.entries(data).map(([k, v]) => (
        <Fragment key={k}>
          <dt>{k}</dt>
          <dd>{typeof v === 'object' && v !== null ? <code>{JSON.stringify(v)}</code> : show(v)}</dd>
        </Fragment>
      ))}
    </dl>
  );
}

/** Everything a row knows: the change (before / after), what was asked, on which devices, and from where. */
export function AuditDetail({ entry }: { entry: AuditEntry }) {
  const d = parseDetail(entry) ?? {};
  const changes = d.cambios;
  const removed = d.eliminado;
  const request = d.solicitud;
  const query = d.consulta;
  const devices = Array.isArray(d.dispositivos) ? (d.dispositivos as unknown[]) : null;
  const extras = Object.entries(d).filter(([k]) => !['cambios', 'eliminado', 'solicitud', 'consulta', 'dispositivos'].includes(k));
  const blocks: ReactNode[] = [];

  if (changes && typeof changes === 'object') {
    blocks.push(
      <div key="c" className="audit-block">
        <h4>Qué cambió</h4>
        <table className="audit-diff">
          <thead><tr><th>Campo</th><th>Antes</th><th>Después</th></tr></thead>
          <tbody>
            {Object.entries(changes as Record<string, unknown>).map(([field, v]) => {
              const pair = v && typeof v === 'object' ? (v as { antes?: unknown; despues?: unknown; agregado?: string; quitado?: string }) : null;
              // A list (permissions, folders) says what was removed and what was added instead of two long lists.
              const asList = pair && (pair.agregado || pair.quitado);
              return (
                <tr key={field}>
                  <td>{field}</td>
                  {asList ? (
                    <>
                      <td className="audit-before">{pair.quitado ? `Quitado: ${pair.quitado}` : DASH}</td>
                      <td className="audit-after">{pair.agregado ? `Agregado: ${pair.agregado}` : DASH}</td>
                    </>
                  ) : pair ? (<><td className="audit-before">{show(pair.antes)}</td><td className="audit-after">{show(pair.despues)}</td></>)
                    : <td colSpan={2} className="muted">{show(v)}</td>}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>,
    );
  } else if (typeof changes === 'string') {
    blocks.push(<div key="c" className="audit-block"><h4>Qué cambió</h4><p className="muted small">{changes}</p></div>);
  }
  if (removed && typeof removed === 'object') {
    blocks.push(<div key="r" className="audit-block"><h4>Lo que se eliminó</h4><KeyValues data={removed as Record<string, unknown>} /></div>);
  }
  if (devices && devices.length > 0) {
    blocks.push(
      <div key="d" className="audit-block">
        <h4>Dispositivos</h4>
        <ul className="audit-devices">{devices.map((x, i) => <li key={i}>{show(x)}</li>)}</ul>
      </div>,
    );
  }
  if (extras.length > 0) {
    blocks.push(
      <div key="e" className="audit-block">
        <h4>Datos de la acción</h4>
        <dl className="audit-kv">
          {extras.map(([k, v]) => (<Fragment key={k}><dt>{DETAIL_LABELS[k] ?? k}</dt><dd>{show(v)}</dd></Fragment>))}
        </dl>
      </div>,
    );
  }
  if (request !== undefined && request !== null) {
    blocks.push(
      <div key="s" className="audit-block">
        <h4>Lo que se solicitó <span className="muted small">(sin contraseñas ni claves)</span></h4>
        {typeof request === 'object' && !Array.isArray(request)
          ? <KeyValues data={request as Record<string, unknown>} />
          : <p className="small">{show(request)}</p>}
      </div>,
    );
  }
  if (query && typeof query === 'object') {
    blocks.push(<div key="q" className="audit-block"><h4>Filtros de la consulta</h4><KeyValues data={query as Record<string, unknown>} /></div>);
  }

  return (
    <div className="audit-detail" data-testid="audit-detail">
      {blocks}
      <div className="audit-block">
        <h4>Origen</h4>
        <dl className="audit-kv">
          <dt>Técnico</dt><dd>{entry.userName || entry.login || DASH}{entry.login && entry.userName ? ` (${entry.login})` : ''}</dd>
          <dt>Rol</dt><dd>{entry.roleName || DASH}</dd>
          <dt>Dirección IP</dt><dd className="mono">{entry.ip || DASH}</dd>
          <dt>Navegador</dt><dd>{entry.userAgent || DASH}</dd>
          <dt>Solicitud</dt><dd className="mono">{entry.method} {entry.path}{entry.httpStatus ? ` → HTTP ${entry.httpStatus}` : ''}</dd>
          {entry.message ? (<><dt>Mensaje</dt><dd>{entry.message}</dd></>) : null}
          <dt>Registro</dt><dd className="mono">#{entry.id}{entry.hash ? ` · huella ${entry.hash.slice(0, 16)}…` : ''}</dd>
        </dl>
      </div>
    </div>
  );
}

/**
 * Compact activity list. `device` = what every technician did on that device; `userId` = what that user did.
 * Loads 20 rows at a time; each row opens its detail.
 */
export function AuditActivity({ device, userId, pageSize = 20 }: { device?: string; userId?: number; pageSize?: number }) {
  const [data, setData] = useState<AuditPage | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<number | null>(null);

  const load = useCallback(async (p: number) => {
    setError(null);
    try {
      const res = device !== undefined ? await getDeviceActivity(device, p, pageSize) : await getUserActivity(userId ?? 0, p, pageSize);
      setData(res);
      setPage(p);
    } catch (e) {
      setData(null);
      setError(errorCode(e) === 'error.permission.denied' ? 'No tienes permiso para ver esta actividad.' : ((e as Error).message || 'No se pudo cargar la actividad'));
    }
  }, [device, userId, pageSize]);

  useEffect(() => { void load(1); }, [load]);

  if (error) return <div className="report-error" data-testid="audit-activity-error">{error}</div>;
  if (!data) return <p className="muted" style={{ padding: 12 }}>Cargando…</p>;
  const pages = Math.max(1, Math.ceil(data.total / data.pageSize));

  return (
    <div className="audit-activity" data-testid="audit-activity">
      <div className="audit-activity-head">
        <span className="muted small">
          {data.total === 0 ? 'Sin actividad registrada.' : `${data.total} ${data.total === 1 ? 'acción registrada' : 'acciones registradas'}`}
        </span>
        <button className="btn btn-sm btn-ghost" onClick={() => void load(page)}>Actualizar</button>
      </div>
      {data.items.length > 0 && (
        <div className="report-table-wrap">
          <table className="table audit-table">
            <thead>
              <tr>
                <th>Fecha</th>
                {device !== undefined ? <th>Técnico</th> : <th>Sobre</th>}
                <th>Acción</th>
                <th>Resultado</th>
                <th aria-label="Detalle" />
              </tr>
            </thead>
            <tbody>
              {data.items.map((e) => (
                <Fragment key={e.id}>
                  <tr className={open === e.id ? 'audit-open' : ''}>
                    <td className="audit-when">{fmtAuditTime(e.createdAt)}</td>
                    {device !== undefined
                      ? <td>{e.userName || e.login || DASH}{e.roleName ? <span className="muted small"> · {e.roleName}</span> : null}</td>
                      : <td>{e.targetName || e.targetId || DASH}</td>}
                    <td>{e.label}{e.message ? <div className="muted small">{e.message}</div> : null}</td>
                    <td><AuditResultChip result={e.result} /></td>
                    <td>
                      <button className="btn btn-sm btn-ghost" aria-expanded={open === e.id} onClick={() => setOpen(open === e.id ? null : e.id)}>
                        {open === e.id ? 'Ocultar' : 'Detalle'}
                      </button>
                    </td>
                  </tr>
                  {open === e.id && (<tr className="audit-detail-row"><td colSpan={5}><AuditDetail entry={e} /></td></tr>)}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {pages > 1 && (
        <div className="audit-pager">
          <button className="btn btn-sm" disabled={page <= 1} onClick={() => void load(page - 1)}>Anterior</button>
          <span className="muted small">Página {page} de {pages}</span>
          <button className="btn btn-sm" disabled={page >= pages} onClick={() => void load(page + 1)}>Siguiente</button>
        </div>
      )}
    </div>
  );
}
