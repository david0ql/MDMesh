import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { useAuth } from '../auth/AuthContext';
import { isFolderAdmin, hasPermission } from '../api/auth';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import { useDevices } from '../data/useDevices';
import { ZoneEditorMap, type DrawMode } from '../components/ZoneEditorMap';
import { RULE_LABEL } from '../ui/zoneLayer';
import {
  listZones, saveZone, deleteZone, listZoneStates, getGeofenceSettings, saveGeofenceSettings, getMailStatus,
  sendTestMail, listRecipients, saveRecipient, deleteRecipient, parseSchedule,
  type Zone, type ZoneState, type ZoneSchedule, type GeofenceSettings, type AlertRecipient,
} from '../api/geofences';

const COLORS = ['#2563eb', '#dc2626', '#16a34a', '#9333ea', '#ea580c', '#0891b2', '#c026d3', '#b45309'];
const DAYS: { id: number; label: string }[] = [
  { id: 1, label: 'L' }, { id: 2, label: 'M' }, { id: 3, label: 'X' }, { id: 4, label: 'J' },
  { id: 5, label: 'V' }, { id: 6, label: 'S' }, { id: 7, label: 'D' },
];
const ALERT_TYPES: { id: string; label: string }[] = [
  { id: 'geofenceExit', label: 'Salida de zona' },
  { id: 'geofenceEnter', label: 'Entrada a zona' },
  { id: 'disconnected', label: 'Desconexión' },
  { id: 'kioskExit', label: 'Salida de kiosko' },
  { id: 'simChange', label: 'Cambio de SIM' },
  { id: 'dataWarning', label: 'Datos móviles: cerca del límite' },
  { id: 'dataLimit', label: 'Datos móviles: límite superado' },
];

type Tab = 'zones' | 'mail' | 'settings';

const newZone = (shape: Zone['shape'], n: number): Zone => ({
  name: '', color: COLORS[n % COLORS.length], shape, rule: 'stayInside', active: true, alertOnReturn: true,
  allDevices: false, schedule: null, emails: '', trackingMinutes: null, groupIds: [], deviceIds: [],
  centerLat: null, centerLon: null, radiusM: null, points: shape === 'polygon' ? [] : null,
});

const errText = (e: unknown, fallback: string) => {
  const m = e instanceof Error ? e.message : '';
  const known: Record<string, string> = {
    'error.geofence.name': 'Escribe un nombre para la zona (máximo 100 caracteres).',
    'error.geofence.circle': 'El círculo necesita un centro y un radio entre 20 m y 500 km.',
    'error.geofence.polygon': 'El polígono necesita entre 3 y 200 vértices.',
    'error.geofence.schedule': 'El horario no es válido.',
    'error.geofence.email': 'Escribe al menos un correo válido.',
    'error.geofence.smtp.missing': 'El servidor no tiene SMTP configurado.',
    'error.geofence.smtp.failed': 'El servidor de correo rechazó el mensaje; revisa la configuración SMTP.',
    'error.permission.denied': 'No tienes permiso: la zona debe quedar asignada solo a tus carpetas o dispositivos.',
  };
  return known[m] ?? (m || fallback);
};

/** Console page "Geocercas": zones on the map with enter/exit alerts, their e-mail notification and thresholds. */
export function GeofencesPage() {
  const toast = useToast();
  const { user } = useAuth();
  const fullAdmin = !isFolderAdmin(user);
  const canEdit = hasPermission(user, 'manage_fleet');

  const [tab, setTab] = useState<Tab>('zones');
  const [zones, setZones] = useState<Zone[]>([]);
  const [states, setStates] = useState<ZoneState[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState<Zone | null>(null);
  const [mode, setMode] = useState<DrawMode>('idle');
  const [saving, setSaving] = useState(false);
  const [focusKey, setFocusKey] = useState('all');
  const [deviceQuery, setDeviceQuery] = useState('');

  const [groups, setGroups] = useState<FleetGroup[]>([]);
  useEffect(() => { listGroups().then((o) => setGroups(o.groups)).catch(() => undefined); }, []);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const { devices } = useDevices('', 1000);
  const deviceById = useMemo(() => new Map(devices.map((d) => [d.id, d])), [devices]);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [z, s] = await Promise.all([listZones(), listZoneStates()]);
      setZones(z);
      setStates(s);
    } catch (e) {
      setError(errText(e, 'No se pudieron cargar las zonas'));
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const statesByZone = useMemo(() => {
    const m = new Map<number, ZoneState[]>();
    for (const s of states) m.set(s.zoneId, [...(m.get(s.zoneId) ?? []), s]);
    return m;
  }, [states]);

  const patch = useCallback((p: Partial<Zone>) => setDraft((d) => (d ? { ...d, ...p } : d)), []);
  const schedule = parseSchedule(draft?.schedule);
  const setSchedule = (s: ZoneSchedule | null) => patch({ schedule: s ? JSON.stringify(s) : null });

  function startNew(shape: Zone['shape']) {
    setDraft(newZone(shape, zones.length));
    setMode(shape);
    setFocusKey(`new-${Date.now()}`);
  }

  function edit(z: Zone) {
    setDraft({ ...z, groupIds: [...z.groupIds], deviceIds: [...z.deviceIds], emails: z.emails ?? '' });
    setMode(z.shape);
    setFocusKey(`zone-${z.id}`);
  }

  function cancel() {
    setDraft(null);
    setMode('idle');
    setDeviceQuery('');
  }

  const geometryReady = draft
    ? draft.shape === 'circle'
      ? draft.centerLat != null && !!draft.radiusM
      : (draft.points?.length ?? 0) >= 3
    : false;

  async function save() {
    if (!draft) return;
    if (!draft.name.trim()) { toast.push('err', 'Falta el nombre', 'Escribe un nombre para la zona.'); return; }
    if (!geometryReady) {
      toast.push('err', 'Falta dibujar la zona',
        draft.shape === 'circle' ? 'Haz clic en el mapa para poner el centro del círculo.' : 'Marca al menos 3 puntos en el mapa.');
      return;
    }
    setSaving(true);
    try {
      await saveZone({ ...draft, name: draft.name.trim() });
      toast.push('ok', 'Zona guardada', draft.name.trim());
      cancel();
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', errText(e, 'Error al guardar la zona'));
    } finally {
      setSaving(false);
    }
  }

  async function remove(z: Zone) {
    if (!z.id || !window.confirm(`¿Borrar la zona «${z.name}»? Sus alertas ya generadas se conservan.`)) return;
    try {
      await deleteZone(z.id);
      toast.push('ok', 'Zona borrada', z.name);
      if (draft?.id === z.id) cancel();
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo borrar', errText(e, 'Error al borrar la zona'));
    }
  }

  const deviceMatches = useMemo(() => {
    const q = deviceQuery.trim().toLowerCase();
    if (!q || !draft) return [];
    return devices
      .filter((d) => !draft.deviceIds.includes(d.id)
        && ((d.description ?? '').toLowerCase().includes(q) || d.number.toLowerCase().includes(q)))
      .slice(0, 8);
  }, [deviceQuery, devices, draft]);

  const assignedText = (z: Zone) => {
    if (z.allDevices) return 'Toda la flota';
    const parts: string[] = [];
    if (z.groupIds.length) parts.push(`${z.groupIds.length} ${z.groupIds.length === 1 ? 'carpeta' : 'carpetas'}`);
    if (z.deviceIds.length) parts.push(`${z.deviceIds.length} ${z.deviceIds.length === 1 ? 'dispositivo' : 'dispositivos'}`);
    return parts.length ? parts.join(' · ') : 'Sin asignar';
  };

  return (
    <AppShell title="Geocercas">
      <div className="page-head">
        <h1>
          Geocercas
          {!loading ? <span className="count">{zones.length}</span> : null}
        </h1>
      </div>
      <p className="muted report-hint">
        Zonas sobre el mapa que avisan cuando un equipo sale de donde debe permanecer o entra a donde no debe. Se evalúan en el
        servidor con cada posición que reporta el equipo, así que funcionan con cualquier versión del agente.
      </p>

      <div className="tabs" style={{ marginBottom: 14 }}>
        <button className={tab === 'zones' ? 'on' : ''} onClick={() => setTab('zones')}>Zonas</button>
        <button className={tab === 'mail' ? 'on' : ''} onClick={() => setTab('mail')}>Notificaciones por correo</button>
        <button className={tab === 'settings' ? 'on' : ''} onClick={() => setTab('settings')}>Precisión e historial</button>
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {tab === 'zones' && (
        <div className="fm-cols gz-cols">
          <div className="panel fm-mapwrap">
            <ZoneEditorMap zones={zones} draft={draft} mode={mode} onChange={patch} focusKey={focusKey} />
            <div className="fm-time gz-hint">
              {!draft && <span className="fm-at">Elige una zona para editarla o crea una nueva. Línea continua: debe permanecer dentro · punteada: no debe entrar.</span>}
              {draft && draft.shape === 'circle' && (
                <span className="fm-at">
                  {draft.centerLat == null
                    ? 'Haz clic en el mapa para poner el centro del círculo.'
                    : 'Arrastra el centro para mover la zona y el punto del borde para cambiar el radio.'}
                </span>
              )}
              {draft && draft.shape === 'polygon' && (
                <span className="fm-at">
                  {(draft.points?.length ?? 0) < 3
                    ? `Haz clic en el mapa para marcar los vértices (${draft.points?.length ?? 0}/3 mínimo).`
                    : 'Clic en el mapa agrega vértices · arrastra para moverlos · clic derecho en uno para quitarlo.'}
                </span>
              )}
            </div>
          </div>

          <aside className="panel fm-list gz-side">
            {!draft && (
              <>
                {canEdit && (
                  <div className="gz-new">
                    <button className="btn btn-primary btn-sm" onClick={() => startNew('circle')}>Nuevo círculo</button>
                    <button className="btn btn-sm" onClick={() => startNew('polygon')}>Nuevo polígono</button>
                  </div>
                )}
                {loading && <p className="muted">Cargando…</p>}
                {!loading && zones.length === 0 && <p className="muted">Aún no hay zonas.</p>}
                <ul>
                  {zones.map((z) => {
                    const st = statesByZone.get(z.id!) ?? [];
                    const bad = st.filter((s) => s.violating);
                    return (
                      <li key={z.id} className={z.active ? '' : 'off'}>
                        <label style={{ cursor: 'default' }}>
                          <i style={{ background: z.color }} />
                          <span className="fm-name" title={z.name}><strong>{z.name}</strong></span>
                        </label>
                        <span className="fm-meta gz-meta">
                          {RULE_LABEL[z.rule]} · {assignedText(z)}{z.active ? '' : ' · inactiva'}
                        </span>
                        <span className="fm-meta gz-meta">
                          {st.length === 0 ? 'Sin posiciones evaluadas todavía' : (
                            <>
                              <span className="chip tone-ok">{st.filter((s) => s.state === 'inside').length} dentro</span>{' '}
                              <span className="chip">{st.filter((s) => s.state === 'outside').length} fuera</span>{' '}
                              {bad.length > 0 && <span className="chip tone-alert">{bad.length} en alerta</span>}
                            </>
                          )}
                        </span>
                        {bad.length > 0 && (
                          <span className="fm-meta gz-meta">
                            {bad.slice(0, 5).map((s, i) => (
                              <span key={s.number}>
                                {i > 0 ? ', ' : ''}
                                <Link to={`/devices/${encodeURIComponent(s.number)}?tab=location`}>{s.description?.trim() || s.number}</Link>
                              </span>
                            ))}
                            {bad.length > 5 ? ` y ${bad.length - 5} más` : ''}
                          </span>
                        )}
                        <span className="fm-actions gz-meta">
                          <button type="button" className="t-more" onClick={() => { setFocusKey(`see-${z.id}-${Date.now()}`); edit(z); }}>
                            {canEdit && z.editable ? 'Editar' : 'Ver'}
                          </button>
                          {canEdit && z.editable && (
                            <button type="button" className="t-more" onClick={() => void remove(z)}>Borrar</button>
                          )}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </>
            )}

            {draft && (
              <fieldset className="gz-form" disabled={saving || (draft.id != null && !(canEdit && draft.editable))}>
                <h3 className="gz-title">{draft.id ? (canEdit && draft.editable ? 'Editar zona' : 'Zona') : 'Nueva zona'}</h3>
                <label className="report-field">
                  <span>Nombre</span>
                  <input className="input" value={draft.name} maxLength={100} placeholder="Bodega principal"
                         aria-label="Nombre de la zona" onChange={(e) => patch({ name: e.target.value })} />
                </label>
                <label className="report-field">
                  <span>Regla</span>
                  <select className="input" value={draft.rule} aria-label="Regla"
                          onChange={(e) => patch({ rule: e.target.value as Zone['rule'] })}>
                    <option value="stayInside">Debe permanecer dentro (alerta al salir)</option>
                    <option value="keepOut">No debe entrar (alerta al entrar)</option>
                  </select>
                </label>
                <div className="report-field">
                  <span>Color</span>
                  <span className="gz-colors">
                    {COLORS.map((c) => (
                      <button key={c} type="button" aria-label={`Color ${c}`} className={draft.color === c ? 'on' : ''}
                              style={{ background: c }} onClick={() => patch({ color: c })} />
                    ))}
                  </span>
                </div>
                {draft.shape === 'circle' && (
                  <label className="report-field">
                    <span>Radio</span>
                    <span className="report-field-input">
                      <input className="input" type="number" min={20} max={500000} style={{ width: 110 }}
                             aria-label="Radio en metros" value={draft.radiusM ? Math.round(draft.radiusM) : ''}
                             onChange={(e) => { const n = Number(e.target.value); patch({ radiusM: Number.isFinite(n) && n > 0 ? n : null }); }} />
                      <span className="muted">metros</span>
                    </span>
                  </label>
                )}
                {draft.shape === 'polygon' && (
                  <div className="report-field">
                    <span>Polígono</span>
                    <span className="report-field-input">
                      <span className="muted">{draft.points?.length ?? 0} vértices</span>
                      <button type="button" className="btn btn-sm" disabled={!draft.points?.length}
                              onClick={() => patch({ points: (draft.points ?? []).slice(0, -1) })}>Deshacer punto</button>
                      <button type="button" className="btn btn-sm" disabled={!draft.points?.length}
                              onClick={() => patch({ points: [] })}>Borrar dibujo</button>
                    </span>
                  </div>
                )}
                {draft.shape === 'circle' && draft.centerLat != null && (
                  <div className="report-field">
                    <span>Centro</span>
                    <span className="report-field-input">
                      <span className="muted">{draft.centerLat.toFixed(5)}, {draft.centerLon!.toFixed(5)}</span>
                      <button type="button" className="btn btn-sm" onClick={() => patch({ centerLat: null, centerLon: null })}>Volver a ubicar</button>
                    </span>
                  </div>
                )}

                <div className="report-field">
                  <span>Aplica a</span>
                  {fullAdmin && (
                    <label className="fm-check" style={{ paddingBottom: 0 }}>
                      <input type="checkbox" checked={draft.allDevices} onChange={(e) => patch({ allDevices: e.target.checked })} />
                      <span>Toda la flota</span>
                    </label>
                  )}
                  {!draft.allDevices && (
                    <>
                      <div className="gz-folders" role="group" aria-label="Carpetas">
                        {tree.length === 0 && <span className="muted">No hay carpetas.</span>}
                        {tree.map((n) => (
                          <label key={n.group.id} style={{ paddingLeft: n.depth * 14 }}>
                            <input type="checkbox" checked={draft.groupIds.includes(n.group.id)}
                                   onChange={(e) => patch({
                                     groupIds: e.target.checked
                                       ? [...draft.groupIds, n.group.id]
                                       : draft.groupIds.filter((g) => g !== n.group.id),
                                   })} />
                            <span>{n.group.name}</span>
                            <span className="muted"> · {n.totalDevices}</span>
                          </label>
                        ))}
                      </div>
                      <span className="muted gz-help">Una carpeta incluye sus subcarpetas (herencia, como las políticas).</span>
                      <input className="input" type="search" placeholder="Agregar un dispositivo…" value={deviceQuery}
                             aria-label="Buscar dispositivo" onChange={(e) => setDeviceQuery(e.target.value)} />
                      {deviceMatches.length > 0 && (
                        <div className="gz-matches">
                          {deviceMatches.map((d) => (
                            <button key={d.id} type="button" onClick={() => { patch({ deviceIds: [...draft.deviceIds, d.id] }); setDeviceQuery(''); }}>
                              {d.description?.trim() || d.number} <span className="muted">{d.number}</span>
                            </button>
                          ))}
                        </div>
                      )}
                      {draft.deviceIds.length > 0 && (
                        <div className="gz-chips">
                          {draft.deviceIds.map((id) => {
                            const d = deviceById.get(id);
                            return (
                              <span key={id} className="chip">
                                {d ? d.description?.trim() || d.number : `#${id}`}{' '}
                                <button type="button" aria-label="Quitar dispositivo" className="gz-x"
                                        onClick={() => patch({ deviceIds: draft.deviceIds.filter((x) => x !== id) })}>×</button>
                              </span>
                            );
                          })}
                        </div>
                      )}
                    </>
                  )}
                </div>

                <div className="report-field">
                  <span>Horario</span>
                  <label className="fm-check" style={{ paddingBottom: 0 }}>
                    <input type="checkbox" checked={!schedule}
                           onChange={(e) => setSchedule(e.target.checked ? null : {
                             days: [1, 2, 3, 4, 5], from: '08:00', to: '18:00',
                             tz: Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Bogota',
                           })} />
                    <span>Siempre</span>
                  </label>
                  {schedule && (
                    <>
                      <span className="gz-days" role="group" aria-label="Días">
                        {DAYS.map((d) => (
                          <button key={d.id} type="button" className={schedule.days.includes(d.id) ? 'on' : ''}
                                  onClick={() => setSchedule({
                                    ...schedule,
                                    days: schedule.days.includes(d.id)
                                      ? schedule.days.filter((x) => x !== d.id)
                                      : [...schedule.days, d.id].sort(),
                                  })}>{d.label}</button>
                        ))}
                      </span>
                      <span className="report-field-input">
                        <input className="input" type="time" aria-label="Desde" value={schedule.from}
                               onChange={(e) => setSchedule({ ...schedule, from: e.target.value || '00:00' })} />
                        <span className="muted">a</span>
                        <input className="input" type="time" aria-label="Hasta" value={schedule.to}
                               onChange={(e) => setSchedule({ ...schedule, to: e.target.value || '23:59' })} />
                      </span>
                      <span className="muted gz-help">
                        Fuera del horario no se alerta; si el equipo sigue fuera de lugar cuando el horario empieza, se alerta entonces.
                        Sin días marcados = todos los días. Hora local ({schedule.tz || 'America/Bogota'}).
                      </span>
                    </>
                  )}
                </div>

                <label className="report-field">
                  <span>Frecuencia de ubicación</span>
                  <span className="report-field-input">
                    <input className="input" type="number" min={1} max={1440} style={{ width: 90 }} placeholder="política"
                           aria-label="Minutos entre posiciones" value={draft.trackingMinutes ?? ''}
                           onChange={(e) => { const n = parseInt(e.target.value, 10); patch({ trackingMinutes: Number.isFinite(n) && n > 0 ? Math.min(n, 1440) : null }); }} />
                    <span className="muted">minutos</span>
                  </span>
                  <span className="muted gz-help">
                    Mientras la zona esté activa, sus equipos reportan una posición al menos con esta frecuencia (si la política pide menos).
                    Vacío = lo que diga la política. Más frecuencia detecta antes la salida y gasta más batería.
                  </span>
                </label>

                <label className="report-field">
                  <span>Avisar por correo a</span>
                  <input className="input" value={draft.emails ?? ''} placeholder="seguridad@empresa.com, jefe@empresa.com"
                         aria-label="Correos de la zona" onChange={(e) => patch({ emails: e.target.value })} />
                  <span className="muted gz-help">Además de los destinatarios por carpeta (pestaña Notificaciones por correo).</span>
                </label>

                <label className="fm-check">
                  <input type="checkbox" checked={draft.alertOnReturn} onChange={(e) => patch({ alertOnReturn: e.target.checked })} />
                  <span>{draft.rule === 'keepOut' ? 'Avisar también cuando salga' : 'Avisar también cuando regrese'}</span>
                </label>
                <label className="fm-check">
                  <input type="checkbox" checked={draft.active} onChange={(e) => patch({ active: e.target.checked })} />
                  <span>Zona activa</span>
                </label>
              </fieldset>
            )}
            {draft && (
              <div className="gz-buttons">
                {(draft.id == null || (canEdit && draft.editable)) && (
                  <button className="btn btn-primary btn-sm" disabled={saving} onClick={() => void save()}>
                    {saving ? 'Guardando…' : 'Guardar zona'}
                  </button>
                )}
                <button className="btn btn-sm" disabled={saving} onClick={cancel}>
                  {draft.id == null || (canEdit && draft.editable) ? 'Cancelar' : 'Volver'}
                </button>
              </div>
            )}
          </aside>
        </div>
      )}

      {tab === 'mail' && <MailTab tree={tree} fullAdmin={fullAdmin} canEdit={canEdit} />}
      {tab === 'settings' && <SettingsTab fullAdmin={fullAdmin && canEdit} />}
    </AppShell>
  );
}

// ---------------------------------------------------------------------------------------------
// Notificaciones por correo
// ---------------------------------------------------------------------------------------------

function MailTab({ tree, fullAdmin, canEdit }: { tree: ReturnType<typeof groupTree>; fullAdmin: boolean; canEdit: boolean }) {
  const toast = useToast();
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [rows, setRows] = useState<AlertRecipient[]>([]);
  const [form, setForm] = useState<AlertRecipient>({ groupId: null, emails: '', types: [], active: true });
  const [testTo, setTestTo] = useState('');
  const [busy, setBusy] = useState(false);
  const paths = useMemo(() => new Map(tree.map((n) => [n.group.id, n.path])), [tree]);

  const load = useCallback(async () => {
    try {
      const [m, r] = await Promise.all([getMailStatus(), listRecipients()]);
      setConfigured(m.configured);
      setRows(r);
    } catch (e) {
      toast.push('err', 'Error', errText(e, 'No se pudieron cargar los destinatarios'));
    }
  }, [toast]);
  useEffect(() => { void load(); }, [load]);

  // A folder administrator can only notify about their folders: default to the first one.
  useEffect(() => {
    if (!fullAdmin && form.groupId == null && tree.length) setForm((f) => ({ ...f, groupId: tree[0].group.id }));
  }, [fullAdmin, tree, form.groupId]);

  async function add() {
    setBusy(true);
    try {
      await saveRecipient(form);
      setForm({ groupId: fullAdmin ? null : form.groupId, emails: '', types: [], active: true });
      toast.push('ok', 'Destinatarios guardados', 'Recibirán las alertas nuevas a partir de ahora.');
      await load();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', errText(e, 'Error al guardar'));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(r: AlertRecipient) {
    try { await saveRecipient({ ...r, active: !r.active }); await load(); }
    catch (e) { toast.push('err', 'Error', errText(e, 'No se pudo cambiar')); }
  }

  async function remove(r: AlertRecipient) {
    if (!r.id || !window.confirm('¿Quitar estos destinatarios?')) return;
    try { await deleteRecipient(r.id); await load(); }
    catch (e) { toast.push('err', 'Error', errText(e, 'No se pudo quitar')); }
  }

  async function test() {
    setBusy(true);
    try {
      await sendTestMail(testTo);
      toast.push('ok', 'Correo de prueba enviado', testTo);
    } catch (e) {
      toast.push('err', 'No se envió', errText(e, 'No se pudo enviar el correo de prueba'));
    } finally {
      setBusy(false);
    }
  }

  const typesText = (t: string[]) => (t.length ? t.map((x) => ALERT_TYPES.find((a) => a.id === x)?.label ?? x).join(', ') : 'Todas las alertas');

  return (
    <div className="panel" style={{ padding: 16 }}>
      {configured === false && (
        <div className="banner banner-warn" role="status">
          El servidor no tiene correo saliente (SMTP) configurado: las alertas se registran y se ven en la consola, pero no se envía
          ningún correo. Define SMTP_HOST, SMTP_PORT y SMTP_FROM (y SMTP_USERNAME / SMTP_PASSWORD si aplica) en el servidor y reinícialo.
        </div>
      )}
      {configured === true && <div className="banner" role="status">Correo saliente configurado: las alertas nuevas se envían a los destinatarios de abajo.</div>}
      <p className="muted report-hint">
        Cada destinatario recibe como máximo un correo por intervalo (ver Precisión e historial), con todas las alertas ocurridas
        desde el anterior; las repetidas del mismo equipo y zona se agrupan en una línea. Aplica a las alertas de geocercas,
        desconexión, salida de kiosko y cambio de SIM. Una regla sobre una carpeta cubre también sus subcarpetas.
      </p>

      <div className="report-table-wrap">
        {rows.length === 0 ? (
          <div className="report-empty muted">Aún no hay destinatarios.</div>
        ) : (
          <table className="table">
            <thead><tr><th>Carpeta</th><th>Correos</th><th>Alertas</th><th>Activa</th><th /></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} style={r.active ? undefined : { opacity: 0.55 }}>
                  <td>{r.groupId == null ? 'Toda la flota' : paths.get(r.groupId) ?? `Carpeta ${r.groupId}`}</td>
                  <td>{r.emails.split(',').join(', ')}</td>
                  <td>{typesText(r.types)}</td>
                  <td><input type="checkbox" checked={r.active} disabled={!canEdit} aria-label="Activa" onChange={() => void toggle(r)} /></td>
                  <td>{canEdit && <button className="t-more" onClick={() => void remove(r)}>Quitar</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {canEdit && (
        <div className="report-toolbar" style={{ marginTop: 16 }}>
          <label className="report-field">
            <span>Carpeta</span>
            <select className="input" value={form.groupId ?? ''} aria-label="Carpeta de los destinatarios"
                    onChange={(e) => setForm({ ...form, groupId: e.target.value ? Number(e.target.value) : null })}>
              {fullAdmin && <option value="">Toda la flota</option>}
              {tree.map((n) => <option key={n.group.id} value={n.group.id}>{n.path}</option>)}
            </select>
          </label>
          <label className="report-field" style={{ flex: '1 1 260px' }}>
            <span>Correos (separados por coma)</span>
            <input className="input" value={form.emails} placeholder="operaciones@empresa.com"
                   aria-label="Correos destinatarios" onChange={(e) => setForm({ ...form, emails: e.target.value })} />
          </label>
          <div className="report-field">
            <span>Alertas (ninguna marcada = todas)</span>
            <span className="gz-types">
              {ALERT_TYPES.map((t) => (
                <label key={t.id} className="fm-check" style={{ paddingBottom: 0 }}>
                  <input type="checkbox" checked={form.types.includes(t.id)}
                         onChange={(e) => setForm({ ...form, types: e.target.checked ? [...form.types, t.id] : form.types.filter((x) => x !== t.id) })} />
                  <span>{t.label}</span>
                </label>
              ))}
            </span>
          </div>
          <button className="btn btn-primary btn-sm" disabled={busy || !form.emails.trim()} onClick={() => void add()}>Agregar destinatarios</button>
        </div>
      )}

      {fullAdmin && canEdit && (
        <div className="report-toolbar" style={{ marginTop: 16 }}>
          <label className="report-field" style={{ flex: '0 1 300px' }}>
            <span>Probar el correo</span>
            <input className="input" type="email" value={testTo} placeholder="tu@correo.com" aria-label="Correo de prueba"
                   onChange={(e) => setTestTo(e.target.value)} />
          </label>
          <button className="btn btn-sm" disabled={busy || !testTo.trim() || configured === false} onClick={() => void test()}>Enviar prueba</button>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Precisión e historial
// ---------------------------------------------------------------------------------------------

function SettingsTab({ fullAdmin }: { fullAdmin: boolean }) {
  const toast = useToast();
  const [s, setS] = useState<GeofenceSettings | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { getGeofenceSettings().then(setS).catch(() => undefined); }, []);
  if (!s) return <div className="panel" style={{ padding: 16 }}><p className="muted">Cargando…</p></div>;

  const num = (key: keyof GeofenceSettings, label: string, unit: string, help: string, min: number, max: number) => (
    <label className="report-field gz-setting">
      <span>{label}</span>
      <span className="report-field-input">
        <input className="input" type="number" min={min} max={max} style={{ width: 110 }} disabled={!fullAdmin} aria-label={label}
               value={s[key]} onChange={(e) => { const n = Number(e.target.value); if (Number.isFinite(n)) setS({ ...s, [key]: n }); }} />
        <span className="muted">{unit}</span>
      </span>
      <span className="muted gz-help">{help}</span>
    </label>
  );

  async function save() {
    if (!s) return;
    setBusy(true);
    try {
      setS(await saveGeofenceSettings(s));
      toast.push('ok', 'Ajustes guardados', 'Aplican a las posiciones que lleguen desde ahora.');
    } catch (e) {
      toast.push('err', 'No se pudo guardar', errText(e, 'Error al guardar'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel gz-settings" style={{ padding: 16 }}>
      {!fullAdmin && <p className="muted">Solo un administrador completo puede cambiar estos valores.</p>}
      {num('maxAccuracyM', 'Precisión máxima aceptada', 'metros (±)',
        'Las posiciones con un margen de error mayor no cambian el estado de las zonas y se marcan como “baja precisión” en el historial.', 5, 5000)}
      {num('hysteresisM', 'Margen del borde', 'metros',
        'Distancia extra más allá del borde (sumada a la precisión de la posición) para dar por cierto que el equipo cruzó. Evita alertas de equipos parados junto al límite.', 0, 1000)}
      {num('confirmFixes', 'Posiciones de confirmación', 'posiciones seguidas',
        'Cuántas posiciones consecutivas deben coincidir para confirmar una entrada o salida. Con 2 o más, un punto saltón del GPS no alerta; con 1 la alerta es inmediata.', 1, 10)}
      {num('emailIntervalMin', 'Intervalo de correos', 'minutos',
        'Cada destinatario recibe como máximo un correo en este tiempo, con todo lo ocurrido.', 1, 1440)}
      {num('retentionDays', 'Retención del historial de ubicación', 'días',
        '0 = conservar siempre. Las posiciones más antiguas se borran automáticamente (cada 6 horas).', 0, 3650)}
      {fullAdmin && <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => void save()}>{busy ? 'Guardando…' : 'Guardar ajustes'}</button>}
    </div>
  );
}
