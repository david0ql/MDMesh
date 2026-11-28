import { useCallback, useEffect, useState } from 'react';
import { fmtDateTime } from '../ui/format';
import { HelpLink } from './HelpLink';
import { MANUAL_ENROLL } from '../data/help';
import {
  attemptHint, attemptReason, getEnrollAttemptsSummary, listEnrollAttempts, pathLabel,
  type EnrollAttempt, type EnrollAttemptsSummary,
} from '../api/enroll';

/**
 * The server's log of enrollment attempts: every agent download a QR triggered and every enroll the server accepted or
 * refused, with the reason and the phone (maker, model, Android). A failed enrollment is diagnosed here without the
 * phone in hand - and a QR that never even produced a download points at the phone's network, clock or camera.
 */
export function EnrollAttemptsPanel() {
  const [rows, setRows] = useState<EnrollAttempt[] | null>(null);
  const [onlyProblems, setOnlyProblems] = useState(false);
  const [summary, setSummary] = useState<EnrollAttemptsSummary | null>(null);

  const load = useCallback(async () => {
    try { setRows(await listEnrollAttempts(60)); } catch { setRows((r) => r ?? []); }
    try { setSummary(await getEnrollAttemptsSummary()); } catch { /* an older server has no summary */ }
  }, []);
  useEffect(() => {
    void load();
    const t = window.setInterval(() => void load(), 10_000);
    return () => window.clearInterval(t);
  }, [load]);

  const now = Date.now();
  const shown = (rows ?? []).filter((a) => !onlyProblems || a.outcome === 'failed' || a.outcome === 'note'
    || attemptHint(a, rows ?? [], now) !== null);

  return (
    <section className="panel" data-testid="enroll-attempts" style={{ marginTop: 20 }}>
      <div className="panel-head">
        <h2 className="panel-title">Intentos de inscripción</h2>
        <HelpLink manual={MANUAL_ENROLL} seccion="si-falla" tema="intentos de inscripción">¿Qué significa cada motivo?</HelpLink>
        <label className="muted" style={{ fontSize: 12.5, display: 'flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={onlyProblems} onChange={(e) => setOnlyProblems(e.target.checked)} />
          Solo con problemas
        </label>
        <button className="btn btn-sm" onClick={() => void load()}>Actualizar</button>
      </div>
      <div style={{ padding: '0 20px 16px' }}>
        <p className="note" style={{ marginTop: 12 }}>
          Lo que el servidor vio de cada teléfono: la descarga del agente que dispara el QR, y cada inscripción aceptada
          o rechazada con su motivo. Si un teléfono escaneó el QR y aquí no aparece ni la descarga, el problema está antes:
          Wi‑Fi sin internet o con portal cautivo, o fecha del teléfono incorrecta.
        </p>
        {summary && summary.unresolvedDevices != null && summary.unresolvedDevices > 0 && (
          <div className="banner banner-warn" data-testid="enroll-unresolved">
            <b>{summary.unresolvedDevices}</b> {summary.unresolvedDevices === 1 ? 'equipo intentó' : 'equipos intentaron'} inscribirse
            en los últimos {summary.days} días y {summary.unresolvedDevices === 1 ? 'sigue' : 'siguen'} sin lograrlo
            {summary.ownerWithoutToken ? <> · <b>{summary.ownerWithoutToken}</b> ya {summary.ownerWithoutToken === 1 ? 'está administrado' : 'están administrados'} (Device Owner)
              pero sin código: en el teléfono, DallyControl → «Escanear el QR de la consola»</> : null}.
          </div>
        )}
        {rows === null && <p className="muted">Cargando…</p>}
        {rows !== null && shown.length === 0 && (
          <p className="muted">{onlyProblems ? 'Sin problemas registrados.' : 'Aún no hay intentos registrados.'}</p>
        )}
        {shown.length > 0 && (
          <table className="gr-table">
            <thead>
              <tr><th>Cuándo</th><th>Resultado</th><th>Equipo</th><th>Android</th><th>Vía</th><th>Carpeta / código</th><th>IP</th></tr>
            </thead>
            <tbody>
              {shown.map((a) => {
                const hint = attemptHint(a, rows ?? [], now);
                const tone = a.outcome === 'ok' ? 'tone-ok' : a.outcome === 'download' ? (hint ? 'tone-warn' : '') : 'tone-alert';
                return (
                  <tr key={a.id} data-outcome={a.outcome}>
                    <td data-label="Cuándo">
                      {fmtDateTime(a.lastAt)}
                      {a.count > 1 && <span className="muted"> · {a.count} veces</span>}
                    </td>
                    <td data-label="Resultado">
                      <span className={`chip ${tone}`}>{attemptReason(a)}</span>
                      {a.tries != null && a.tries > 1 && <span className="muted"> · intento {a.tries} del teléfono</span>}
                      {hint && <div className="muted" style={{ fontSize: 12, marginTop: 4, maxWidth: 420 }}>{hint}</div>}
                      {a.detail && a.outcome !== 'download' && (
                        <div className="muted mono" style={{ fontSize: 11, marginTop: 4, maxWidth: 420, wordBreak: 'break-word' }}>{a.detail}</div>
                      )}
                    </td>
                    <td data-label="Equipo">
                      {[a.manufacturer, a.model].filter(Boolean).join(' ') || '—'}
                      {a.serial && <div className="muted mono" style={{ fontSize: 11 }}>{a.serial}</div>}
                    </td>
                    <td data-label="Android">
                      {a.androidRelease ?? '—'}{a.sdkInt ? <span className="muted"> (API {a.sdkInt})</span> : null}
                      {a.agentVersion && <div className="muted" style={{ fontSize: 11 }}>agente {a.agentVersion}</div>}
                    </td>
                    <td data-label="Vía">{pathLabel(a.path)}</td>
                    <td data-label="Carpeta / código">
                      {a.groupName ?? (a.tokenId == null ? 'código desconocido' : 'sin carpeta')}
                      {a.tokenLabel && <div className="muted" style={{ fontSize: 11 }}>{a.tokenLabel}</div>}
                    </td>
                    <td data-label="IP" className="mono" style={{ fontSize: 12 }}>{a.ip ?? '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
