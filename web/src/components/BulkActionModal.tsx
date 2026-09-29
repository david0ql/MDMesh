import { useEffect, useState } from 'react';
import {
  ACTION_TEMPLATES, type CommandTemplateExt,
  agentPackage, buildInstallCommand, remoteSupportPackage, type AppInstallSpec,
} from '../api/commands';
import { listApplications, type Application } from '../api/applications';
import { BulkKioskModal } from './BulkKioskModal';
import { useToast } from '../ui/toast';
import { queueForTarget, syncAppsFor, systemUpdateCommand, targetLabel, type Target } from '../api/fleet';

// Only safe + disruptive actions run in bulk; the destructive group (passcode-reset, wipe) is excluded.
// kiosk-enter is handled by a dedicated Phase-3 flow, so it is filtered out here too.
const BULK_GROUPS: Array<{ id: 'safe' | 'disruptive'; title: string }> = [
  { id: 'safe', title: 'Acciones' },
  { id: 'disruptive', title: 'Disruptivas' },
];

function bulkable(t: CommandTemplateExt): boolean {
  const g = t.group ?? 'safe';
  if (g === 'destructive') return false;
  if (t.key === 'kiosk-enter') return false; // Phase 3 owns this
  return true;
}

/** Turn a Library app into an install spec (single url or split bundle parts). */
function specForApp(app: Application): AppInstallSpec | null {
  let parts: { url: string; sha256?: string }[] | undefined;
  if (app.parts) {
    try {
      const arr = JSON.parse(app.parts) as { url: string; sha256?: string }[];
      if (Array.isArray(arr) && arr.length) parts = arr;
    } catch { parts = undefined; }
  }
  if (!parts && !app.url) return null; // nothing installable
  return {
    url: app.url ?? '',
    packageName: app.pkg,
    versionCode: app.latestVersion,
    parts,
  };
}

export function BulkActionModal({
  target, onClose, onDone,
}: { target: Target; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const who = targetLabel(target);
  const [active, setActive] = useState<CommandTemplateExt | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const [appPicker, setAppPicker] = useState(false);
  const [apps, setApps] = useState<Application[] | null>(null);
  const [appErr, setAppErr] = useState<string | null>(null);
  const [appQuery, setAppQuery] = useState('');
  const [kioskOpen, setKioskOpen] = useState(false);

  useEffect(() => {
    if (!appPicker) return;
    let cancelled = false;
    listApplications()
      .then((r) => { if (!cancelled) setApps(r); })
      .catch((e) => { if (!cancelled) setAppErr(e instanceof Error ? e.message : 'No se pudieron cargar las aplicaciones'); });
    return () => { cancelled = true; };
  }, [appPicker]);

  async function updateApps() {
    setBusy(true);
    try {
      const r = await syncAppsFor(target);
      toast.push('ok', 'Apps de la política',
        r.queued > 0 ? `${r.queued} instalación${r.queued === 1 ? '' : 'es'} en cola en ${r.devices} dispositivo${r.devices === 1 ? '' : 's'}.`
          : `Los ${r.devices} dispositivos ya tienen las apps de su política al día.`);
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', 'Apps de la política: falló', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function updateAgent() {
    setBusy(true);
    try {
      const spec = await agentPackage();
      const res = await queueForTarget(target, buildInstallCommand(spec));
      toast.push('ok', `Agente ${spec.version ?? ''}: en cola`,
        `Para ${res.queued} dispositivo${res.queued === 1 ? '' : 's'}; los que ya la tienen no cambian.`);
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', 'Actualizar agente: falló', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function osUpdates(type: 'automatic' | 'default') {
    setBusy(true);
    try {
      const res = await queueForTarget(target, systemUpdateCommand(type));
      toast.push('ok', type === 'automatic' ? 'Android: actualizar ya' : 'Android: predeterminado',
        `En cola para ${res.queued} dispositivo${res.queued === 1 ? '' : 's'}. Requiere agente 0.4.0 o superior.`);
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', 'Actualización de Android: falló', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function installRemote() {
    setBusy(true);
    try {
      const spec = await remoteSupportPackage();
      const res = await queueForTarget(target, buildInstallCommand(spec));
      toast.push('ok', 'Soporte remoto: en cola',
        `Se instala en ${res.queued} dispositivo${res.queued === 1 ? '' : 's'}` + (res.skipped ? ` (${res.skipped} omitidos)` : '') +
        '. Los apagados lo toman si se conectan en la próxima hora; si no, vuelve a enviarlo.');
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', 'Soporte remoto: falló', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function run(t: CommandTemplateExt, vals: Record<string, string>) {
    setBusy(true);
    try {
      const req = t.build ? t.build(vals) : t.request;
      const res = await queueForTarget(target, req);
      const skipped = res.skipped;
      toast.push('ok', `${t.label}: en cola`,
        `En cola para ${res.queued} dispositivo${res.queued === 1 ? '' : 's'}` +
        (skipped ? ` (${skipped} omitido${skipped === 1 ? '' : 's'})` : '') + '.');
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', `${t.label}: falló`, e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function runInstall(app: Application) {
    const spec = specForApp(app);
    if (!spec) { toast.push('err', 'No se puede instalar', `${app.name} no tiene un APK alojado.`); return; }
    setBusy(true);
    try {
      const res = await queueForTarget(target, buildInstallCommand(spec));
      const skipped = res.skipped;
      toast.push('ok', 'Instalación en cola',
        `${app.name} → ${res.queued} dispositivo${res.queued === 1 ? '' : 's'}` +
        (skipped ? ` (${skipped} omitido${skipped === 1 ? '' : 's'})` : '') + '.');
      onDone();
      onClose();
    } catch (e) {
      toast.push('err', 'La instalación falló', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  function onPick(t: CommandTemplateExt) {
    if (t.params && t.params.length > 0) { setActive(t); setValues({}); return; }
    void run(t, {});
  }

  const canSend = !active
    ? false
    : !active.params?.some((p) => p.required && !values[p.key]);

  const showCatalog = !active && !appPicker;

  // Kiosk-enter has its own Library picker; swap it in wholesale (no stacked backdrops).
  if (kioskOpen) {
    return (
      <BulkKioskModal
        target={target}
        onClose={() => setKioskOpen(false)}
        onDone={() => { onDone(); onClose(); }}
      />
    );
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Ejecutar acción en {who}</h3>

        {showCatalog && (
          <>
            {BULK_GROUPS.map((g) => (
              <section key={g.id} className="action-group">
                <h4 className="action-group-title">{g.title}</h4>
                <div className="action-grid">
                  {ACTION_TEMPLATES.filter(bulkable).filter((t) => (t.group ?? 'safe') === g.id).map((t) => (
                    <button
                      key={t.key}
                      className={`btn ${t.danger ? 'btn-danger' : ''}`}
                      disabled={busy}
                      title={t.description}
                      onClick={() => onPick(t)}
                    >
                      {t.label}
                    </button>
                  ))}
                </div>
              </section>
            ))}
            <section className="action-group">
              <h4 className="action-group-title">Aplicaciones</h4>
              <div className="action-grid">
                <button className="btn" disabled={busy} onClick={() => setAppPicker(true)}>Instalar aplicación…</button>
              </div>
            </section>
            <section className="action-group">
              <h4 className="action-group-title">Actualizaciones</h4>
              <div className="action-grid">
                <button className="btn" disabled={busy} onClick={() => void updateApps()} data-testid="bulk-sync-apps"
                        title="Cada teléfono instala ya las apps de su política que le falten o tenga en versión vieja">
                  Actualizar apps de la política ya
                </button>
                <button className="btn" disabled={busy} onClick={() => void osUpdates('automatic')} data-testid="bulk-os-auto"
                        title="Android instala sus actualizaciones en cuanto estén disponibles, sin preguntar">
                  Android: instalar actualizaciones ya
                </button>
                <button className="btn" disabled={busy} onClick={() => void updateAgent()} data-testid="bulk-agent-update"
                        title="Instala en los teléfonos la versión del agente DallyControl publicada en este servidor">
                  Actualizar agente DallyControl
                </button>
                <button className="btn btn-ghost" disabled={busy} onClick={() => void osUpdates('default')}>
                  Android: como venga de fábrica
                </button>
              </div>
              <p className="muted small">
                Para que quede siempre (también en los que entren después), ponlo en la política: «Actualizaciones del sistema».
                Las apps nuevas que subas a una política se instalan solas.
              </p>
            </section>
            <section className="action-group">
              <h4 className="action-group-title">Control remoto</h4>
              <div className="action-grid">
                <button className="btn" disabled={busy} onClick={() => void installRemote()} data-testid="bulk-remote-setup"
                        title="Instala droidVNC-NG (alojado en este servidor) en todos a la vez, sin cable">
                  Instalar soporte remoto
                </button>
              </div>
              <p className="muted small">
                Los que ya lo tienen no cambian. La primera vez que se vea cada pantalla, el teléfono pide permiso para
                compartirla (Iniciar ahora); para controlar, además se activa una vez en Accesibilidad.
              </p>
            </section>
            <section className="action-group">
              <h4 className="action-group-title">Kiosco</h4>
              <div className="action-grid">
                <button className="btn btn-danger" disabled={busy} onClick={() => setKioskOpen(true)}>Activar modo kiosco…</button>
              </div>
            </section>
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={onClose}>Cerrar</button>
            </div>
          </>
        )}

        {active && (
          <>
            <h4>{active.label}</h4>
            <p className="muted">{active.description}</p>
            <p className="muted">Se ejecutará en <strong>{who}</strong>.</p>
            {active.params?.map((p) => (
              <label key={p.key} className="field">
                <span>{p.label}</span>
                <input
                  type={p.kind === 'password' ? 'password' : p.kind === 'number' ? 'number' : 'text'}
                  placeholder={p.placeholder}
                  value={values[p.key] ?? ''}
                  onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
                />
              </label>
            ))}
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={() => setActive(null)}>Atrás</button>
              <button
                className={`btn ${active.danger ? 'btn-danger' : 'btn-primary'}`}
                disabled={busy || !canSend}
                onClick={() => { void run(active, values); }}
              >
                {busy ? <span key="busy">Poniendo en cola…</span> : <span key="idle">Ejecutar</span>}
              </button>
            </div>
          </>
        )}

        {appPicker && (
          <>
            <h4>Instalar aplicación en {who}</h4>
            <input className="field" placeholder="Filtrar aplicaciones" value={appQuery}
                   onChange={(e) => setAppQuery(e.target.value)} />
            {appErr && <p className="muted">{appErr}</p>}
            {!apps && !appErr && <p className="muted">Cargando biblioteca…</p>}
            <div className="action-grid">
              {(apps ?? [])
                .filter((a) => `${a.name} ${a.pkg}`.toLowerCase().includes(appQuery.toLowerCase()))
                .map((a) => (
                  <button key={a.id ?? a.pkg} className="btn" disabled={busy}
                          title={a.pkg} onClick={() => { void runInstall(a); }}>
                    {a.name}{a.version ? ` (${a.version})` : ''}
                  </button>
                ))}
            </div>
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={() => setAppPicker(false)}>Atrás</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
