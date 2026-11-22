import { useEffect, useMemo, useState } from 'react';
import { listDevicesWithApp, bulkUninstallApp, type DeviceWithApp } from '../api/deviceApps';
import { useAuth } from '../auth/AuthContext';
import { hasPermission } from '../api/auth';
import { useToast } from '../ui/toast';

/**
 * Fleet-level app targeting: the devices that report a given package as installed, with a mass silent uninstall over
 * the whole set or a selection. Opened from the app library (AppsPage / VersionGroupCard) and the deploy dialog.
 *
 * Mass uninstall is sensitive (it wipes the app and its data on every chosen phone), so it is gated on `edit_devices`
 * and asks for an explicit typed confirmation before sending.
 */
export function InstalledDevicesModal({
  packageName,
  label,
  onClose,
}: {
  packageName: string;
  label: string;
  onClose: () => void;
}) {
  const toast = useToast();
  const { user } = useAuth();
  const canUninstall = hasPermission(user, 'edit_devices');

  const [devices, setDevices] = useState<DeviceWithApp[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    setDevices(null);
    setErr(null);
    listDevicesWithApp(packageName)
      .then((list) => alive && setDevices(list))
      .catch((e) => alive && setErr(e instanceof Error ? e.message : 'No se pudo cargar la lista'));
    return () => { alive = false; };
  }, [packageName]);

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const list = devices ?? [];
    if (!q) return list;
    return list.filter((d) =>
      `${d.number} ${d.description ?? ''} ${d.appVersion ?? ''}`.toLowerCase().includes(q));
  }, [devices, filter]);

  const allShownPicked = shown.length > 0 && shown.every((d) => picked.has(d.id));
  const toggle = (id: number) => setPicked((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const toggleAllShown = () => setPicked((s) => {
    const n = new Set(s);
    if (allShownPicked) shown.forEach((d) => n.delete(d.id));
    else shown.forEach((d) => n.add(d.id));
    return n;
  });

  async function doUninstall() {
    const ids = [...picked];
    if (ids.length === 0) return;
    setBusy(true);
    try {
      const r = await bulkUninstallApp(ids, packageName);
      const skipped = r.skipped?.length ?? 0;
      toast.push(skipped ? 'err' : 'ok', `Desinstalación de ${label}`,
        `${r.queued} dispositivo${r.queued === 1 ? '' : 's'} en cola${skipped ? `, ${skipped} omitido${skipped === 1 ? '' : 's'}` : ''}.`);
      onClose();
    } catch (e) {
      toast.push('err', 'No se pudo desinstalar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal deploy-modal" onClick={(e) => e.stopPropagation()} data-testid="installed-devices-modal">
        <h3>Dónde está instalada {label}</h3>
        <p className="muted" style={{ marginTop: 2 }}>
          <span className="mono">{packageName}</span>
          {devices != null ? ` · ${devices.length} dispositivo${devices.length === 1 ? '' : 's'}` : ''}
        </p>

        {err && <div className="banner banner-alert">{err}</div>}

        {devices == null && !err ? (
          <div className="empty" style={{ padding: 20 }}><span className="spin" /> Cargando…</div>
        ) : devices && devices.length === 0 ? (
          <div className="empty" style={{ padding: 20 }}>
            <span className="label">Sin instalaciones</span>
            Ningún dispositivo reporta esta app en su último inventario. (El inventario llega en cada conexión; escanea un
            dispositivo si acabas de instalarla.)
          </div>
        ) : (
          <>
            <div className="dv-search" style={{ width: '100%', margin: '6px 0 10px' }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="11" cy="11" r="7" />
                <path d="M21 21l-4-4" />
              </svg>
              <input type="search" placeholder="Filtrar dispositivos" value={filter} onChange={(e) => setFilter(e.target.value)} />
            </div>
            {canUninstall && (
              <label className="deploy-devrow" style={{ fontWeight: 600 }}>
                <input type="checkbox" checked={allShownPicked} onChange={toggleAllShown} />
                <span className="dd-nm">Seleccionar {shown.length}</span>
              </label>
            )}
            <div className="deploy-devlist">
              {shown.length === 0 ? (
                <div className="empty" style={{ padding: 20 }}>Ningún dispositivo coincide.</div>
              ) : shown.map((d) => (
                <label key={d.id} className="deploy-devrow">
                  {canUninstall && (
                    <input type="checkbox" checked={picked.has(d.id)} onChange={() => toggle(d.id)} />
                  )}
                  <span className="dd-nm">{d.number}</span>
                  <span className="dd-sub">{d.description}</span>
                  <span className="dd-seen mono">{d.appVersion ?? '—'}</span>
                </label>
              ))}
            </div>
          </>
        )}

        {canUninstall && (devices?.length ?? 0) > 0 && !confirming && (
          <div className="modal-actions">
            <button className="btn" onClick={onClose} disabled={busy}>Cerrar</button>
            <button className="btn btn-danger" disabled={busy || picked.size === 0}
              onClick={() => { setConfirmText(''); setConfirming(true); }}>
              Desinstalar en {picked.size || ''}
            </button>
          </div>
        )}

        {confirming && (
          <div className="banner banner-warn" style={{ marginTop: 12 }}>
            <p style={{ marginTop: 0 }}>
              Vas a <b>desinstalar {label}</b> en <b>{picked.size} dispositivo{picked.size === 1 ? '' : 's'}</b>. Se borra
              la app y sus datos en cada uno (sesión, pedidos sin enviar). No se puede deshacer. Si una política todavía la
              incluye para instalar, la próxima sincronización la vuelve a instalar.
            </p>
            <label className="field" style={{ maxWidth: 240 }}>
              <span className="label">Escribe DESINSTALAR para confirmar</span>
              <input className="input" autoFocus value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />
            </label>
            <div className="modal-actions">
              <button className="btn" onClick={() => setConfirming(false)} disabled={busy}>Cancelar</button>
              <button className="btn btn-danger" disabled={busy || confirmText.trim().toUpperCase() !== 'DESINSTALAR'}
                onClick={() => void doUninstall()}>
                {busy ? 'Enviando…' : `Desinstalar en ${picked.size}`}
              </button>
            </div>
          </div>
        )}

        {!canUninstall && (devices?.length ?? 0) > 0 && (
          <div className="modal-actions">
            <button className="btn" onClick={onClose}>Cerrar</button>
          </div>
        )}
      </div>
    </div>
  );
}
