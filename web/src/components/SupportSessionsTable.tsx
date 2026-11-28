import { Fragment, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  endReasonLabel, engineLabel, fmtDuration, listSupportEvents,
  type SupportEvent, type SupportSessionRow,
} from '../api/support';
import { fmtDateTime, DASH } from '../ui/format';

// The log of remote-support sessions, as the device's "Historial de sesiones" and the fleet page show it:
// one row per session, and the row opens into what happened in it (chat lines, files, calls, actions).

const KIND_LABEL: Record<string, string> = {
  chat: 'Chat', file: 'Archivo', call: 'Llamada', clipboard: 'Portapapeles', action: 'Acción',
};

const CALL_LABEL: Record<string, string> = {
  ringing: 'sonando en el teléfono', active: 'contestada', declined: 'rechazada', missed: 'sin respuesta',
  ended: 'terminada', error: 'error',
};

const ACTION_LABEL: Record<string, string> = { reboot: 'Reiniciar', lock: 'Bloquear' };

function eventDetail(e: SupportEvent): string {
  const d = e.detail ?? '';
  if (e.kind === 'call') return CALL_LABEL[d] ?? d;
  if (e.kind === 'action') return ACTION_LABEL[d] ?? d;
  return d;
}

const modeLabel = (r: SupportSessionRow) => (r.viewOnly ? 'Solo ver' : 'Ver y controlar');
const endLabel = (r: SupportSessionRow) => (r.live ? 'En curso' : endReasonLabel(r.endReason));
const durationLabel = (r: SupportSessionRow) =>
  r.live ? 'En curso' : r.durationSec == null ? 'No se conectó' : fmtDuration(r.durationSec);

/** The rows as a CSV file (for Excel: UTF-8 with BOM, CRLF). */
export function supportSessionsCsv(rows: SupportSessionRow[]): string {
  const cell = (v: unknown) => {
    const s = v == null ? '' : String(v);
    return /[",\n\r;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const iso = (ms?: number | null) => (ms ? new Date(ms).toISOString() : '');
  const head = ['Inicio', 'Fin', 'Dispositivo', 'Nombre', 'Usuario', 'Duración (s)', 'Modo', 'Motor', 'Mensajes de chat',
    'Archivos', 'Voz (s)', 'Acciones de control', 'Cómo terminó'];
  const lines = rows.map((r) => [
    iso(r.startedAt), iso(r.endedAt), r.deviceNumber, r.deviceName ?? '', r.user ?? '', r.durationSec ?? '',
    modeLabel(r), engineLabel(r.engine), r.chatCount ?? 0, r.fileCount ?? 0, r.voiceSeconds ?? 0, r.inputCount ?? 0, endLabel(r),
  ].map(cell).join(','));
  return `﻿${[head.map(cell).join(','), ...lines].join('\r\n')}\r\n`;
}

export function SupportSessionsTable({ rows, showDevice = false }: { rows: SupportSessionRow[]; showDevice?: boolean }) {
  const [open, setOpen] = useState<number | null>(null);
  const [events, setEvents] = useState<Record<number, SupportEvent[] | 'loading' | { error: string }>>({});

  function toggle(id: number) {
    if (open === id) { setOpen(null); return; }
    setOpen(id);
    const have = events[id];
    if (have && have !== 'loading' && !('error' in have)) return;
    setEvents((m) => ({ ...m, [id]: 'loading' }));
    listSupportEvents(id).then(
      (list) => setEvents((m) => ({ ...m, [id]: list })),
      (e: unknown) => setEvents((m) => ({ ...m, [id]: { error: e instanceof Error ? e.message : 'No se pudo cargar' } })),
    );
  }

  const columns = showDevice ? 11 : 10;

  return (
    <table className="table ss-table" data-testid="support-sessions-table">
      <thead>
        <tr>
          <th style={{ width: 36 }} aria-label="Detalle" />
          <th>Fecha</th>
          {showDevice && <th>Dispositivo</th>}
          <th>Usuario</th>
          <th>Duración</th>
          <th>Modo</th>
          <th>Motor</th>
          <th>Chat</th>
          <th>Archivos</th>
          <th>Voz</th>
          <th>Cómo terminó</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => {
          const ev = events[r.id];
          const isOpen = open === r.id;
          return (
            <Fragment key={r.id}>
              <tr className={isOpen ? 'ss-row-open' : undefined}>
                <td>
                  <button className="ss-expand" aria-expanded={isOpen} aria-label={isOpen ? 'Ocultar el detalle' : 'Ver el detalle'}
                          onClick={() => toggle(r.id)}>{isOpen ? '▾' : '▸'}</button>
                </td>
                <td>{r.startedAt ? fmtDateTime(r.startedAt) : DASH}</td>
                {showDevice && (
                  <td>
                    <Link className="row-link-a" to={`/devices/${encodeURIComponent(r.deviceNumber)}`}>
                      {r.deviceName || r.deviceNumber}
                    </Link>
                  </td>
                )}
                <td>{r.user || DASH}</td>
                <td>{durationLabel(r)}</td>
                <td>{modeLabel(r)}</td>
                <td>{engineLabel(r.engine)}</td>
                <td>{r.chatCount ?? 0}</td>
                <td>{r.fileCount ?? 0}</td>
                <td>{r.voiceSeconds ? fmtDuration(r.voiceSeconds) : DASH}</td>
                <td>{r.live ? <span className="chip tone-ok">En curso</span> : endReasonLabel(r.endReason)}</td>
              </tr>
              {isOpen && (
                <tr className="ss-events-row">
                  <td />
                  <td colSpan={columns - 1}>
                    {ev === 'loading' || ev == null ? (
                      <span className="muted">Cargando…</span>
                    ) : 'error' in ev ? (
                      <span className="ss-warn">{ev.error}</span>
                    ) : ev.length === 0 ? (
                      <span className="muted">
                        Sin chat, archivos ni llamadas en esta sesión{r.inputCount ? ` (${r.inputCount} acciones de control)` : ''}.
                      </span>
                    ) : (
                      <ul className="ss-events">
                        {ev.map((e) => (
                          <li key={e.id}>
                            <span className="ss-ev-t">{fmtDateTime(e.ts)}</span>
                            <span className="ss-ev-k">{KIND_LABEL[e.kind] ?? e.kind}</span>
                            <span className="ss-ev-o">{e.origin === 'console' ? 'Soporte' : 'Usuario'}</span>
                            <span className="ss-ev-d">{eventDetail(e)}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </td>
                </tr>
              )}
            </Fragment>
          );
        })}
      </tbody>
    </table>
  );
}
