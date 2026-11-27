import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { fmtDateTime, DASH } from '../ui/format';
import {
  listAlerts,
  reviewAlert,
  downloadAlerts,
  type AlertRow,
  type AlertsResult,
  type AlertType,
  type AlertStatus,
  type ExportFormat,
} from '../api/alerts';

// ---------------------------------------------------------------------------------------------
// Filters
// ---------------------------------------------------------------------------------------------

const TYPE_OPTIONS: { id: AlertType | ''; label: string }[] = [
  { id: '', label: 'Todas' },
  { id: 'disconnected', label: 'Desconexión' },
  { id: 'kioskExit', label: 'Salida de kiosko' },
  { id: 'simChange', label: 'Cambio de SIM' },
];

const STATUS_OPTIONS: { id: AlertStatus | ''; label: string }[] = [
  { id: '', label: 'Todas' },
  { id: 'pending', label: 'Pendientes' },
  { id: 'reviewed', label: 'Revisadas' },
];

const todayStr = () => new Date().toISOString().slice(0, 10);
const daysAgoStr = (d: number) => new Date(Date.now() - d * 86_400_000).toISOString().slice(0, 10);
const startOfDay = (s: string) => new Date(`${s}T00:00:00`).getTime();
const endOfDay = (s: string) => new Date(`${s}T23:59:59.999`).getTime();

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

export function AlertsPage() {
  const toast = useToast();

  const [type, setType] = useState<AlertType | ''>('');
  const [status, setStatus] = useState<AlertStatus | ''>('');
  const [from, setFrom] = useState(daysAgoStr(30));
  const [to, setTo] = useState(todayStr());
  const [minutes, setMinutes] = useState(20);

  const [result, setResult] = useState<AlertsResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savingId, setSavingId] = useState<number | null>(null);

  const filters = useCallback(
    () => ({ type, status, from: startOfDay(from), to: endOfDay(to), minutes }),
    [type, status, from, to, minutes],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setResult(await listAlerts(filters()));
    } catch (e) {
      setResult(null);
      setError((e as Error).message || 'No se pudieron cargar las alertas');
    } finally {
      setLoading(false);
    }
  }, [filters]);

  // First load. Filter changes are applied with "Actualizar".
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onReview = useCallback(
    async (row: AlertRow, reviewed: boolean) => {
      setSavingId(row.id);
      // Optimistic: flip locally, roll back on error.
      setResult((prev) =>
        prev
          ? { ...prev, rows: prev.rows.map((r) => (r.id === row.id ? { ...r, reviewed, estado: reviewed ? 'Revisada' : 'Pendiente' } : r)) }
          : prev,
      );
      try {
        await reviewAlert(row.id, reviewed);
        // Refresh the acting user's name / timestamp from the server.
        void load();
      } catch (e) {
        setResult((prev) =>
          prev
            ? { ...prev, rows: prev.rows.map((r) => (r.id === row.id ? { ...r, reviewed: !reviewed, estado: !reviewed ? 'Revisada' : 'Pendiente' } : r)) }
            : prev,
        );
        toast.push('err', 'Error', (e as Error).message || 'No se pudo guardar');
      } finally {
        setSavingId(null);
      }
    },
    [load, toast],
  );

  const onExport = useCallback(
    async (format: ExportFormat) => {
      try {
        await downloadAlerts(format, filters());
      } catch (e) {
        toast.push('err', 'Error', (e as Error).message || 'No se pudo exportar');
      }
    },
    [filters, toast],
  );

  const rows = result?.rows ?? [];
  const canExport = rows.length > 0;

  return (
    <AppShell title="Alertas">
      <div className="page-head">
        <h1>
          Alertas
          {result ? <span className="count">{rows.length}</span> : null}
        </h1>
      </div>

      <p className="muted report-hint">
        Alertas de desconexión, salida de modo kiosko y cambio de SIM. Marca cada una como revisada cuando la
        atiendas; el reporte puede exportarse a Excel o CSV.
      </p>

      <div className="report-toolbar">
        <label className="report-field">
          <span>Tipo</span>
          <select className="input" value={type} onChange={(e) => setType(e.target.value as AlertType | '')}>
            {TYPE_OPTIONS.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label className="report-field">
          <span>Estado</span>
          <select className="input" value={status} onChange={(e) => setStatus(e.target.value as AlertStatus | '')}>
            {STATUS_OPTIONS.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label className="report-field">
          <span>Desde</span>
          <input className="input" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="report-field">
          <span>Hasta</span>
          <input className="input" type="date" value={to} min={from} max={todayStr()} onChange={(e) => setTo(e.target.value)} />
        </label>
        <label className="report-field">
          <span>Umbral desconexión</span>
          <span className="report-field-input">
            <input
              className="input"
              type="number"
              min={1}
              max={43200}
              value={minutes}
              onChange={(e) => {
                const n = Number(e.target.value);
                if (Number.isFinite(n)) setMinutes(Math.max(1, Math.min(43200, n)));
              }}
              style={{ width: 90 }}
            />
            <span className="muted">min</span>
          </span>
        </label>

        <button className="btn btn-primary btn-sm" disabled={loading} onClick={() => void load()}>
          {loading ? 'Cargando…' : 'Actualizar'}
        </button>
        {canExport && (
          <div className="report-export">
            <button className="btn btn-sm" disabled={loading} onClick={() => void onExport('xlsx')}>
              Excel
            </button>
            <button className="btn btn-sm" disabled={loading} onClick={() => void onExport('csv')}>
              CSV
            </button>
          </div>
        )}
      </div>

      {error && <div className="report-error">{error}</div>}

      {result?.title && !error && (
        <div className="report-title muted">
          {result.title}
          {result.generatedAt ? ` · generado ${fmtDateTime(result.generatedAt)}` : ''}
        </div>
      )}

      {!error && (
        <div className="report-table-wrap">
          {rows.length === 0 ? (
            <div className="report-empty muted">{loading ? 'Cargando…' : 'Sin alertas para estos filtros.'}</div>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: 90 }}>Revisada</th>
                  <th>Tipo</th>
                  <th>Equipo</th>
                  <th>Carpeta</th>
                  <th>Fecha</th>
                  <th>Detalle</th>
                  <th>Revisada por</th>
                  <th>Revisada el</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} style={row.reviewed ? { opacity: 0.55 } : undefined}>
                    <td>
                      <input
                        type="checkbox"
                        checked={!!row.reviewed}
                        disabled={savingId === row.id}
                        onChange={(e) => void onReview(row, e.target.checked)}
                        aria-label={row.reviewed ? 'Marcar como pendiente' : 'Marcar como revisada'}
                      />
                    </td>
                    <td>{row.tipo}</td>
                    <td>
                      {row.number ? (
                        <Link className="row-link-a" to={`/devices/${encodeURIComponent(String(row.number))}`}>
                          {String(row.name ?? row.number)}
                        </Link>
                      ) : (
                        String(row.name ?? DASH)
                      )}
                    </td>
                    <td>{row.group ?? DASH}</td>
                    <td>{row.createdat ? fmtDateTime(Number(row.createdat)) : <span className="muted">{DASH}</span>}</td>
                    <td>{row.detail ? String(row.detail) : <span className="muted">{DASH}</span>}</td>
                    <td>{row.reviewedbyname ? String(row.reviewedbyname) : <span className="muted">{DASH}</span>}</td>
                    <td>{row.reviewedat ? fmtDateTime(Number(row.reviewedat)) : <span className="muted">{DASH}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </AppShell>
  );
}
