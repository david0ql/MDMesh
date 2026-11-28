import { useCallback, useEffect, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { listSupportSessions, type SupportSessionRow } from '../api/support';
import { SupportSessionsTable, supportSessionsCsv } from '../components/SupportSessionsTable';

// "Sesiones de soporte": every remote-support session of the devices the user reaches, newest first, with what
// happened in each one (the row opens into its chat lines, files, calls and actions) and a CSV of the table.

const pad = (n: number) => String(n).padStart(2, '0');
/** A local date as the <input type="date"> value. */
const dayStr = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const startOfDay = (s: string) => new Date(`${s}T00:00:00`).getTime();
const endOfDay = (s: string) => new Date(`${s}T23:59:59.999`).getTime();
const LIMIT = 1000;

export function SupportSessionsPage() {
  const [from, setFrom] = useState(() => dayStr(new Date(Date.now() - 30 * 86_400_000)));
  const [to, setTo] = useState(() => dayStr(new Date()));
  const [rows, setRows] = useState<SupportSessionRow[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (fromDay: string, toDay: string) => {
    const a = startOfDay(fromDay);
    const b = endOfDay(toDay);
    if (!Number.isFinite(a) || !Number.isFinite(b) || a > b) {
      setError('El rango de fechas no es válido.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      setRows(await listSupportSessions({ from: a, to: b, limit: LIMIT }));
    } catch (e) {
      setRows(null);
      setError(e instanceof Error && e.message ? e.message : 'No se pudieron cargar las sesiones');
    } finally {
      setLoading(false);
    }
  }, []);

  // First load. Filter changes are applied with "Actualizar".
  useEffect(() => {
    void load(from, to);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function downloadCsv() {
    if (!rows?.length) return;
    const url = URL.createObjectURL(new Blob([supportSessionsCsv(rows)], { type: 'text/csv;charset=utf-8' }));
    try {
      const a = document.createElement('a');
      a.href = url;
      a.download = `sesiones-de-soporte_${from}_${to}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    }
  }

  const list = rows ?? [];

  return (
    <AppShell title="Sesiones de soporte">
      <div className="page-head">
        <h1>
          Sesiones de soporte
          {rows ? <span className="count">{list.length}</span> : null}
        </h1>
      </div>

      <p className="muted report-hint">
        Cada vez que soporte abrió la pantalla, el chat o una llamada con un teléfono: quién, cuándo, cuánto duró y qué se
        hizo. Abre una fila para ver los mensajes, archivos y llamadas de esa sesión. Las sesiones se inician desde la
        pestaña Remoto de cada dispositivo.
      </p>

      <div className="report-toolbar">
        <label className="report-field">
          <span>Desde</span>
          <input type="date" className="input" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="report-field">
          <span>Hasta</span>
          <input type="date" className="input" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
        </label>
        <button className="btn btn-primary" disabled={loading} onClick={() => void load(from, to)}>
          {loading ? <span key="l">Cargando…</span> : <span key="u">Actualizar</span>}
        </button>
        {list.length > 0 && (
          <div className="report-export">
            <button className="btn btn-sm" onClick={downloadCsv} data-testid="support-sessions-csv">CSV</button>
          </div>
        )}
      </div>

      {error && <div className="report-error">{error}</div>}

      {!error && (
        <div className="report-table-wrap">
          {list.length === 0 ? (
            <div className="report-empty muted">{loading || rows == null ? 'Cargando…' : 'Sin sesiones de soporte en estas fechas.'}</div>
          ) : (
            <SupportSessionsTable rows={list} showDevice />
          )}
        </div>
      )}
      {!error && list.length >= LIMIT && (
        <p className="muted report-hint" style={{ marginTop: 10 }}>Se muestran las {LIMIT} más recientes: acorta el rango para ver el resto.</p>
      )}
    </AppShell>
  );
}
