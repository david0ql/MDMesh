import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { searchDevices, type DeviceView } from '../api/devices';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import {
  getAnnouncement, listAnnouncements, sendAnnouncement, uploadMedia, withdrawAnnouncement,
  type Announcement, type Receipt,
} from '../api/announcements';

const MAX_MB = 95; // the CDN in front of the server refuses uploads over 100 MB

const pct = (n = 0, d = 0) => (d > 0 ? Math.round((n / d) * 100) : 0);
const when = (t?: number | null) => (t ? new Date(t).toLocaleString('es-CO') : '—');

/**
 * Anuncios: messages with an image or a video shown in the DallyControl app on the phones. Mandatory ones open full
 * screen until the person taps "Entendido" (with a video, after watching it); optional ones arrive as a notification
 * and stay in the phone's inbox. Each announcement shows who received, saw and confirmed it.
 */
export function AnnouncementsPage() {
  const [items, setItems] = useState<Announcement[] | null>(null);
  const [composing, setComposing] = useState(false);
  const [open, setOpen] = useState<number | null>(null);

  const load = useCallback(() => listAnnouncements().then(setItems).catch(() => setItems([])), []);
  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 15000);
    return () => clearInterval(t);
  }, [load]);

  return (
    <AppShell title="Anuncios">
      <div className="ann-page">
        <div className="ann-top">
          <h1>Anuncios</h1>
          <div className="sp" />
          <button className="btn btn-primary" onClick={() => setComposing(true)} data-testid="ann-new">Nuevo anuncio</button>
        </div>
        <p className="muted">
          Envía un mensaje con imagen o video a la app DallyControl de los teléfonos. <b>Obligatorio</b>: se abre en pantalla
          completa y no se puede cerrar hasta tocar «Entendido» (si trae video, al terminar de verlo). <b>Opcional</b>: llega
          como notificación y queda en la bandeja «Anuncios» del teléfono.
        </p>

        {items == null ? (
          <p className="muted">Cargando…</p>
        ) : items.length === 0 ? (
          <div className="empty">Aún no has enviado anuncios.</div>
        ) : (
          <div className="ann-list">
            {items.map((a) => (
              <button key={a.id} type="button" className={`ann-card ${a.withdrawnat ? 'off' : ''}`} onClick={() => setOpen(a.id)}>
                <div className="ann-card-top">
                  <span className={`chip ${a.mandatory ? 'tone-warn' : ''}`}>{a.mandatory ? 'Obligatorio' : 'Opcional'}</span>
                  {a.mediatype && <span className="chip">{a.mediatype === 'video' ? 'Video' : 'Imagen'}</span>}
                  {a.withdrawnat && <span className="chip">Retirado</span>}
                  <span className="sp" />
                  <span className="muted small">{when(a.createdat)}</span>
                </div>
                <b className="ann-title">{a.title}</b>
                {a.body && <span className="ann-body muted">{a.body}</span>}
                <span className="muted small">Para: {a.targetlabel || `${a.total} dispositivos`}</span>
                <div className="ann-stats">
                  <Stat label="Recibido" n={a.received} d={a.total} />
                  <Stat label="Visto" n={a.seen} d={a.total} />
                  <Stat label="Confirmado" n={a.acked} d={a.total} />
                </div>
              </button>
            ))}
          </div>
        )}
      </div>
      {composing && <Compose onClose={() => setComposing(false)} onSent={() => { setComposing(false); void load(); }} />}
      {open != null && <Detail id={open} onClose={() => { setOpen(null); void load(); }} />}
    </AppShell>
  );
}

function Stat({ label, n = 0, d = 0 }: { label: string; n?: number; d?: number }) {
  return (
    <div className="ann-stat">
      <span>{label}</span>
      <b>{n}/{d}</b>
      <i><em style={{ width: `${pct(n, d)}%` }} /></i>
    </div>
  );
}

function Compose({ onClose, onSent }: { onClose: () => void; onSent: () => void }) {
  const toast = useToast();
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [mandatory, setMandatory] = useState(false);
  const [expires, setExpires] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const preview = useMemo(() => (file ? URL.createObjectURL(file) : null), [file]);
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);
  const [who, setWho] = useState<'folders' | 'devices' | 'all'>('folders');
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [folders, setFolders] = useState<Set<number>>(new Set());
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [filter, setFilter] = useState('');
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    listGroups().then((o) => setGroups(o.groups)).catch(() => undefined);
    searchDevices({ pageSize: 1000 }).then((r) => setDevices(r.devices?.items ?? [])).catch(() => undefined);
  }, []);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return q ? devices.filter((d) => `${d.description ?? ''} ${d.number} ${d.serial ?? ''} ${d.imei ?? ''}`.toLowerCase().includes(q)) : devices;
  }, [devices, filter]);

  const kind = file ? (file.type.startsWith('video/') ? 'video' : file.type.startsWith('image/') ? 'image' : null) : null;
  const tooBig = !!file && file.size > MAX_MB * 1024 * 1024;
  // A folder covers its sub-folders: count and send only the top-most chosen ones.
  const topFolders = [...folders].filter((id) => !tree.some((n) => n.group.id !== id && folders.has(n.group.id) && n.subtree.has(id)));
  const reach = who === 'all' ? devices.length
    : who === 'devices' ? picked.size
    : tree.filter((n) => topFolders.includes(n.group.id)).reduce((s, n) => s + n.totalDevices, 0);
  const label = who === 'all' ? 'Todos los dispositivos'
    : who === 'devices' ? `${picked.size} dispositivo${picked.size === 1 ? '' : 's'}`
    : tree.filter((n) => topFolders.includes(n.group.id)).map((n) => n.path).join(', ');
  const canSend = title.trim() !== '' && reach > 0 && !tooBig && (!file || kind != null) && !busy;

  const send = async () => {
    try {
      let mediaUrl: string | undefined;
      if (file && kind) {
        setBusy(`Subiendo ${kind === 'video' ? 'el video' : 'la imagen'}…`);
        mediaUrl = await uploadMedia(file);
      }
      setBusy('Enviando…');
      const r = await sendAnnouncement({
        title: title.trim(), body: body.trim() || undefined, mediaUrl, mediaType: kind ?? undefined, mandatory,
        expiresInDays: expires ? Number(expires) : undefined,
        all: who === 'all', groupIds: who === 'folders' ? topFolders : [], deviceIds: who === 'devices' ? [...picked] : [],
        targetLabel: label,
      });
      toast.push('ok', 'Anuncio enviado', `A ${r.devices} dispositivo${r.devices === 1 ? '' : 's'}. Los apagados lo reciben al conectarse.`);
      onSent();
    } catch (e) {
      toast.push('err', 'No se pudo enviar', e instanceof Error ? e.message : '');
      setBusy(null);
    }
  };

  const toggle = <T,>(s: Set<T>, v: T, put: (x: Set<T>) => void) => { const n = new Set(s); if (n.has(v)) n.delete(v); else n.add(v); put(n); };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal ann-modal">
        <h3>Nuevo anuncio</h3>
        <div className="ann-compose">
          <div className="ann-form">
            <label className="field"><span>Título</span>
              <input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} placeholder="Ej.: Nueva lista de precios" data-testid="ann-title" />
            </label>
            <label className="field"><span>Mensaje</span>
              <textarea rows={5} value={body} maxLength={5000} onChange={(e) => setBody(e.target.value)} placeholder="Lo que deben saber…" />
            </label>
            <label className="field"><span>Imagen o video (opcional, hasta {MAX_MB} MB)</span>
              <input type="file" accept="image/*,video/mp4,video/webm,video/3gpp" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </label>
            {tooBig && <p className="field-error">El archivo pesa más de {MAX_MB} MB.</p>}
            {file && !kind && <p className="field-error">Solo imágenes o videos.</p>}

            <div className="ann-mode">
              <label className={`ann-opt ${!mandatory ? 'on' : ''}`}>
                <input type="radio" checked={!mandatory} onChange={() => setMandatory(false)} />
                <b>Opcional</b><span>Notificación + bandeja «Anuncios». Lo abren cuando quieran.</span>
              </label>
              <label className={`ann-opt ${mandatory ? 'on' : ''}`}>
                <input type="radio" checked={mandatory} onChange={() => setMandatory(true)} data-testid="ann-mandatory" />
                <b>Obligatorio</b><span>Pantalla completa hasta tocar «Entendido» (si hay video, al terminarlo). Vuelve a salir si lo cierran.</span>
              </label>
            </div>
            <label className="field"><span>Vigencia</span>
              <select className="sel" value={expires} onChange={(e) => setExpires(e.target.value)}>
                <option value="">Sin vencimiento</option>
                <option value="1">1 día</option><option value="7">7 días</option><option value="30">30 días</option><option value="90">90 días</option>
              </select>
            </label>
          </div>

          <div className="ann-side">
            <div className="ann-preview" aria-label="Vista previa en el teléfono">
              <span className="ann-pv-k">{mandatory ? 'LECTURA OBLIGATORIA' : 'ANUNCIO'}</span>
              <b>{title || 'Título del anuncio'}</b>
              {preview && kind === 'image' && <img src={preview} alt="" />}
              {preview && kind === 'video' && <video src={preview} controls muted />}
              <p>{body || 'El mensaje aparece aquí.'}</p>
              <span className="ann-pv-btn">Entendido</span>
            </div>
          </div>
        </div>

        <h4 className="ann-h">Para quién</h4>
        <div className="seg" role="group" aria-label="Destinatarios">
          <button type="button" className={who === 'folders' ? 'on' : ''} onClick={() => setWho('folders')}>Carpetas</button>
          <button type="button" className={who === 'devices' ? 'on' : ''} onClick={() => setWho('devices')}>Dispositivos</button>
          <button type="button" className={who === 'all' ? 'on' : ''} onClick={() => setWho('all')}>Todos</button>
        </div>
        {who === 'folders' && (
          <div className="deploy-devlist">
            {tree.length === 0 ? <div className="empty" style={{ padding: 16 }}>No hay carpetas.</div> : tree.map((n) => (
              <label key={n.group.id} className="deploy-devrow" style={{ paddingLeft: 12 + n.depth * 18 }}>
                <input type="checkbox" checked={folders.has(n.group.id)} onChange={() => toggle(folders, n.group.id, setFolders)} />
                <span className="dd-nm">{n.group.name}</span>
                <span className="dd-seen">{n.totalDevices} disp.</span>
              </label>
            ))}
          </div>
        )}
        {who === 'devices' && (
          <>
            <input className="ann-filter" type="search" placeholder="Buscar por nombre, serie o IMEI" value={filter} onChange={(e) => setFilter(e.target.value)} />
            <div className="deploy-devlist">
              {shown.map((d) => (
                <label key={d.id} className="deploy-devrow">
                  <input type="checkbox" checked={picked.has(d.id)} onChange={() => toggle(picked, d.id, setPicked)} />
                  <span className="dd-nm">{d.description || d.number}</span>
                  <span className="dd-seen">{d.serial ?? ''}</span>
                </label>
              ))}
            </div>
          </>
        )}
        {who === 'all' && <p className="muted">Se envía a los {devices.length} dispositivos.</p>}

        <div className="modal-actions">
          {busy && <span className="muted small">{busy}</span>}
          <button className="btn" disabled={!!busy} onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" disabled={!canSend} onClick={() => void send()} data-testid="ann-send">
            Enviar{reach > 0 ? ` a ${reach}` : ''}
          </button>
        </div>
      </div>
    </div>
  );
}

function Detail({ id, onClose }: { id: number; onClose: () => void }) {
  const toast = useToast();
  const [data, setData] = useState<{ announcement: Announcement; receipts: Receipt[] } | null>(null);
  const [confirmWithdraw, setConfirmWithdraw] = useState(false);
  const load = useCallback(() => getAnnouncement(id).then(setData).catch(() => undefined), [id]);
  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 10000);
    return () => clearInterval(t);
  }, [load]);

  const withdraw = async () => {
    setConfirmWithdraw(false);
    try {
      await withdrawAnnouncement(id);
      toast.push('ok', 'Anuncio retirado', 'Se quita de la app de los teléfonos.');
      void load();
    } catch (e) {
      toast.push('err', 'No se pudo retirar', e instanceof Error ? e.message : '');
    }
  };

  const a = data?.announcement;
  const state = (r: Receipt) => (r.ackat ? ['ok', 'Confirmado'] : r.seenat ? ['seen', 'Visto'] : r.receivedat ? ['got', 'Recibido'] : ['wait', 'Pendiente']);
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal ann-modal" onClick={(e) => e.stopPropagation()}>
        {!a ? <p className="muted">Cargando…</p> : (
          <>
            <h3>{a.title}</h3>
            <p className="muted small">
              {a.mandatory ? 'Obligatorio' : 'Opcional'} · enviado {when(a.createdat)}{a.createdby ? ` por ${a.createdby}` : ''}
              {a.expiresat ? ` · vence ${when(a.expiresat)}` : ''}{a.withdrawnat ? ` · retirado ${when(a.withdrawnat)}` : ''}
            </p>
            {a.mediaurl && a.mediatype === 'image' && <img className="ann-media" src={a.mediaurl} alt="" />}
            {a.mediaurl && a.mediatype === 'video' && <video className="ann-media" src={a.mediaurl} controls />}
            {a.body && <p className="ann-text">{a.body}</p>}
            <table className="st-table">
              <thead><tr><th>Dispositivo</th><th>Estado</th><th>Recibido</th><th>Visto</th><th>Confirmado</th></tr></thead>
              <tbody>
                {data.receipts.map((r) => {
                  const [cls, txt] = state(r);
                  return (
                    <tr key={r.devicenumber}>
                      <td>{r.deviceid != null ? <Link to={`/devices/${r.devicenumber}`}>{r.description || r.devicenumber}</Link> : r.devicenumber}</td>
                      <td><span className={`upd-st ${cls === 'ok' ? 'ok' : cls === 'wait' ? 'wait' : ''}`}>{txt}</span></td>
                      <td>{when(r.receivedat)}</td><td>{when(r.seenat)}</td><td>{when(r.ackat)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p className="muted small">Los teléfonos apagados o sin conexión lo reciben en cuanto se conectan.</p>
          </>
        )}
        <div className="modal-actions">
          {a && !a.withdrawnat && <button className="btn btn-danger" onClick={() => setConfirmWithdraw(true)}>Retirar anuncio</button>}
          <button className="btn" onClick={onClose}>Cerrar</button>
        </div>
        {confirmWithdraw && (
          <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setConfirmWithdraw(false)}>
            <div className="modal" onClick={(e) => e.stopPropagation()}>
              <h3>¿Retirar este anuncio?</h3>
              <p>Deja de enviarse y desaparece de la app de los teléfonos que ya lo tenían.</p>
              <div className="modal-actions">
                <button className="btn" onClick={() => setConfirmWithdraw(false)}>Cancelar</button>
                <button className="btn btn-danger" onClick={() => void withdraw()}>Retirar</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
