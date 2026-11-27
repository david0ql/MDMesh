import { useCallback, useEffect, useMemo, useState } from 'react';
import { useToast } from '../ui/toast';
import { fmtDateTime } from '../ui/format';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import {
  deleteSchedule, listSchedules, saveSchedule, sendScheduleNow,
  type ReportSchedule, type ScheduleList,
} from '../api/reports';

const DAYS = ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo'];
const pad = (n: number) => String(n).padStart(2, '0');

function blank(): ReportSchedule {
  return {
    name: '', report: 'inventory', format: 'xlsx', csvSeparator: ';', frequency: 'daily', runHour: 7, runMinute: 0,
    dayOfWeek: 1, dayOfMonth: 1, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Bogota',
    recipients: '', groupId: null, enabled: true,
  };
}

function when(s: ReportSchedule): string {
  const t = `${pad(s.runHour)}:${pad(s.runMinute)}`;
  if (s.frequency === 'weekly') return `Cada ${DAYS[(s.dayOfWeek ?? 1) - 1].toLowerCase()} a las ${t}`;
  if (s.frequency === 'monthly') return `El día ${s.dayOfMonth ?? 1} de cada mes a las ${t}`;
  return `Todos los días a las ${t}`;
}

/** Reportes → "Envíos programados": reports e-mailed daily / weekly / monthly. Create, edit, pause, test, delete. */
export function ReportSchedulesPanel() {
  const toast = useToast();
  const [data, setData] = useState<ScheduleList | null>(null);
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const [error, setError] = useState<string | null>(null);
  const [edit, setEdit] = useState<ReportSchedule | null>(null);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await listSchedules());
      setError(null);
    } catch (e) {
      setError((e as Error).message || 'No se pudieron cargar los envíos');
    }
  }, []);
  useEffect(() => {
    void load();
    listGroups().then((o) => setGroups(o.groups)).catch(() => undefined);
  }, [load]);

  const reportLabel = (id: string) => data?.reports.find((r) => r.id === id)?.label ?? id;
  const groupLabel = (id?: number | null) =>
    id == null ? 'Todos los dispositivos' : tree.find((n) => n.group.id === id)?.path ?? `Carpeta ${id}`;

  async function save() {
    if (!edit) return;
    setBusy(true);
    try {
      await saveSchedule(edit);
      toast.push('ok', 'Envío guardado', when(edit));
      setEdit(null);
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', (e as Error).message || '');
    } finally {
      setBusy(false);
    }
  }

  async function toggle(s: ReportSchedule) {
    try {
      await saveSchedule({ ...s, enabled: !s.enabled });
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo cambiar', (e as Error).message || '');
    }
  }

  async function sendNow(s: ReportSchedule) {
    if (s.id == null) return;
    setSending(s.id);
    try {
      const r = await sendScheduleNow(s.id);
      if (r.sent) toast.push('ok', 'Reporte enviado', r.message);
      else toast.push('err', r.status === 'smtp' ? 'El servidor no tiene correo configurado' : 'No se envió', r.message);
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo enviar', (e as Error).message || '');
    } finally {
      setSending(null);
    }
  }

  async function remove(s: ReportSchedule) {
    if (s.id == null || !window.confirm(`¿Eliminar el envío «${s.name}»?`)) return;
    try {
      await deleteSchedule(s.id);
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo eliminar', (e as Error).message || '');
    }
  }

  const set = (patch: Partial<ReportSchedule>) => setEdit((e) => (e ? { ...e, ...patch } : e));

  return (
    <div data-testid="report-schedules">
      {data && !data.smtpConfigured && (
        <div className="banner banner-warn" data-testid="smtp-missing">
          Este servidor no tiene SMTP configurado, así que los envíos no salen por correo. Puedes crear y editar los
          envíos; se enviarán cuando el administrador del servidor configure el correo saliente (SMTP_HOST, SMTP_PORT,
          SMTP_FROM y, si aplica, SMTP_USERNAME / SMTP_PASSWORD).
        </div>
      )}
      {error && <div className="report-error">{error}</div>}

      <div className="report-toolbar">
        <button className="btn btn-primary btn-sm" data-testid="schedule-new" onClick={() => setEdit(blank())}>Nuevo envío</button>
      </div>

      {data && data.schedules.length === 0 && (
        <div className="report-empty muted">Aún no hay envíos programados. Crea uno para recibir el inventario por correo.</div>
      )}
      {data && data.schedules.length > 0 && (
        <div className="report-table-wrap">
          <table className="table">
            <thead>
              <tr><th>Nombre</th><th>Reporte</th><th>Cuándo</th><th>Alcance</th><th>Formato</th><th>Destinatarios</th><th>Próximo</th><th>Último envío</th><th /></tr>
            </thead>
            <tbody>
              {data.schedules.map((s) => (
                <tr key={s.id}>
                  <td>{s.name}{!s.enabled && <span className="chip tone-warn" style={{ marginLeft: 6 }}>En pausa</span>}</td>
                  <td>{reportLabel(s.report)}</td>
                  <td>{when(s)}<div className="muted" style={{ fontSize: 11 }}>{s.timezone}</div></td>
                  <td>{groupLabel(s.groupId)}</td>
                  <td>{s.format === 'csv' ? `CSV (${s.csvSeparator === 'tab' ? 'tabulador' : s.csvSeparator})` : 'Excel'}</td>
                  <td style={{ maxWidth: 220, overflowWrap: 'anywhere' }}>{s.recipients}</td>
                  <td>{s.enabled && s.nextRunAt ? fmtDateTime(s.nextRunAt) : '—'}</td>
                  <td>
                    {s.lastRunAt ? (
                      <span className={`sched-status ${s.lastStatus ?? ''}`} title={s.lastError ?? ''}>
                        {s.lastStatus === 'ok' ? 'Enviado' : s.lastStatus === 'smtp' ? 'Sin SMTP' : 'Falló'} · {fmtDateTime(s.lastRunAt)}
                        {s.lastStatus !== 'ok' && s.lastError ? <div style={{ fontSize: 11 }}>{s.lastError}</div> : null}
                      </span>
                    ) : '—'}
                  </td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="btn btn-sm" disabled={sending === s.id} onClick={() => void sendNow(s)}>
                      {sending === s.id ? 'Enviando…' : 'Enviar ahora'}
                    </button>{' '}
                    <button className="btn btn-sm btn-ghost" onClick={() => setEdit({ ...s })}>Editar</button>{' '}
                    <button className="btn btn-sm btn-ghost" onClick={() => void toggle(s)}>{s.enabled ? 'Pausar' : 'Reanudar'}</button>{' '}
                    <button className="btn btn-sm btn-ghost" onClick={() => void remove(s)}>Eliminar</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {edit && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => !busy && setEdit(null)}>
          <div className="modal" style={{ maxWidth: 640, width: '100%' }} onClick={(e) => e.stopPropagation()}>
            <h3>{edit.id == null ? 'Nuevo envío programado' : 'Editar envío programado'}</h3>
            <div className="sched-form">
              <label className="field wide">
                <span>Nombre</span>
                <input className="input" value={edit.name} maxLength={120} placeholder="Inventario diario para operaciones"
                  onChange={(e) => set({ name: e.target.value })} />
              </label>
              <label className="field">
                <span>Reporte</span>
                <select className="sel" value={edit.report} onChange={(e) => set({ report: e.target.value })}>
                  {(data?.reports ?? []).map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
                </select>
              </label>
              <label className="field">
                <span>Carpeta</span>
                <select className="sel" value={edit.groupId == null ? 'all' : String(edit.groupId)}
                  onChange={(e) => set({ groupId: e.target.value === 'all' ? null : Number(e.target.value) })}>
                  <option value="all">Todos los dispositivos</option>
                  {tree.map((n) => <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>)}
                </select>
              </label>
              <label className="field">
                <span>Frecuencia</span>
                <select className="sel" value={edit.frequency} onChange={(e) => set({ frequency: e.target.value as ReportSchedule['frequency'] })}>
                  <option value="daily">Diaria</option>
                  <option value="weekly">Semanal</option>
                  <option value="monthly">Mensual</option>
                </select>
              </label>
              {edit.frequency === 'weekly' && (
                <label className="field">
                  <span>Día de la semana</span>
                  <select className="sel" value={edit.dayOfWeek ?? 1} onChange={(e) => set({ dayOfWeek: Number(e.target.value) })}>
                    {DAYS.map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
                  </select>
                </label>
              )}
              {edit.frequency === 'monthly' && (
                <label className="field">
                  <span>Día del mes</span>
                  <input className="input" type="number" min={1} max={31} value={edit.dayOfMonth ?? 1}
                    onChange={(e) => set({ dayOfMonth: Math.max(1, Math.min(31, Number(e.target.value) || 1)) })} />
                </label>
              )}
              <label className="field">
                <span>Hora ({edit.timezone})</span>
                <input className="input" type="time" value={`${pad(edit.runHour)}:${pad(edit.runMinute)}`}
                  onChange={(e) => {
                    const [h, m] = e.target.value.split(':').map(Number);
                    if (Number.isFinite(h) && Number.isFinite(m)) set({ runHour: h, runMinute: m });
                  }} />
              </label>
              <label className="field">
                <span>Formato</span>
                <select className="sel" value={edit.format} onChange={(e) => set({ format: e.target.value as ReportSchedule['format'] })}>
                  <option value="xlsx">Excel (.xlsx)</option>
                  <option value="csv">CSV (UTF-8)</option>
                </select>
              </label>
              {edit.format === 'csv' && (
                <label className="field">
                  <span>Separador del CSV</span>
                  <select className="sel" value={edit.csvSeparator} onChange={(e) => set({ csvSeparator: e.target.value })}>
                    <option value=";">Punto y coma ( ; )</option>
                    <option value=",">Coma ( , )</option>
                    <option value="tab">Tabulador</option>
                    <option value="|">Barra vertical ( | )</option>
                  </select>
                </label>
              )}
              <label className="field wide">
                <span>Destinatarios (correos separados por coma)</span>
                <input className="input" value={edit.recipients} placeholder="operaciones@empresa.com, jefe@empresa.com"
                  onChange={(e) => set({ recipients: e.target.value })} />
              </label>
              <label className="field wide" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" style={{ width: 'auto' }} checked={edit.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
                <span>Activo (desmárcalo para dejarlo en pausa)</span>
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn" onClick={() => setEdit(null)} disabled={busy}>Cancelar</button>
              <button className="btn btn-primary" data-testid="schedule-save" onClick={() => void save()} disabled={busy}>
                {busy ? 'Guardando…' : 'Guardar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
