import { DcPolicyPanel, parseDcPolicy, serializeDcPolicy, type DcPolicy } from '../components/DcPolicyPanel';
import { listAppVersions, listPolicyDowngrades, listVersionLabels, setPolicyDowngrade, topCode, type LabeledVersion } from '../api/versions';
import { AppGroupsPanel } from '../components/AppGroupsPanel';
import { KioskBrandEditor } from '../components/KioskBrandEditor';
import { listAppGroups, type AppGroup } from '../api/appGroups';
import { PolicyFolders, applyPolicyFolders } from '../components/PolicyFolders';
import { uploadApkToLibrary } from '../api/uploadToLibrary';
import { useEffect, useMemo, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import {
  getConfigurations,
  getConfigurationApps,
  saveConfiguration,
  deleteConfiguration,
  copyConfiguration,
  type Configuration,
  type ConfigApp,
} from '../api/configurations';
import { listApplications, type Application } from '../api/applications';
import { getSyncSummary, type ConfigSyncSummary } from '../api/configSync';
import {
  PRIMARY_FIELDS,
  LEGACY_FIELDS,
  GROUP_ORDER,
  type FieldDef,
} from '../data/configFields';
import { AppPicker } from '../components/AppPicker';
import { SyncBar } from '../components/SyncBar';
import { KioskChangeConfirm, kioskAffectingChanges } from '../components/KioskChangeConfirm';

// The seeded device-template defaults are locked: view-only, and used as bases
// for new configs (start from scratch or from one of these).
const DEFAULT_CONFIG_NAMES = new Set([
  'Managed Launcher',
  'MIUI (Xiaomi Redmi)',
  'Background (Agent) Mode',
]);
const isLocked = (c: Configuration) => DEFAULT_CONFIG_NAMES.has(c.name);

// The configurations INSERT sets every column explicitly, so NOT-NULL columns
// can't fall back to their DB defaults — a blank config must supply them or the
// insert fails (e.g. "null value in column pushoptions"). customerId is set
// server-side (insertRecord); applications must be a (possibly empty) array.
const NEW_CONFIG_DEFAULTS: Partial<Configuration> = {
  type: 0,
  pushOptions: 'mqttWorker',
  appPermissions: 'GRANTALL',
  requestUpdates: 'DONOTTRACK',
  downloadUpdates: 'UNLIMITED',
  desktopHeader: 'NO_HEADER',
  iconSize: 'SMALL',
  defaultFilePath: '/',
  systemUpdateType: 0,
  useDefaultDesignSettings: true,
};

/** A fresh editable draft, optionally seeded from a base config. */
function cloneForNew(base: Configuration | null): Configuration {
  if (!base) return { ...NEW_CONFIG_DEFAULTS, name: '', applications: [] } as Configuration;
  const c: Configuration = { ...base, name: `${base.name} copia` };
  delete c.id;
  delete c.qrCodeKey;
  c.applications = (base.applications ?? []).map((a) => ({ ...a }));
  return c;
}

export function ConfigurationsPage() {
  const toast = useToast();
  const [configs, setConfigs] = useState<Configuration[] | null>(null);
  const [apps, setApps] = useState<Application[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Configuration | null>(null);
  const [readOnly, setReadOnly] = useState(false);
  const [chooserOpen, setChooserOpen] = useState(false);
  const [copyOf, setCopyOf] = useState<Configuration | null>(null);
  const [sync, setSync] = useState<Record<number, ConfigSyncSummary>>({});
  // The list endpoint carries no apps: count each configuration's assigned apps separately.
  const [appCounts, setAppCounts] = useState<Record<number, number>>({});
  const [view, setView] = useState<'policies' | 'groups'>('policies');

  const load = () =>
    getConfigurations()
      .then((list) => {
        setConfigs(list);
        list.forEach((c) => {
          if (c.id == null) return;
          const id = c.id;
          getConfigurationApps(id).then((a) => setAppCounts((m) => ({ ...m, [id]: a.length }))).catch(() => undefined);
        });
      })
      .catch(() => {
        setConfigs([]);
        setError('No se pudieron cargar las políticas.');
      })
      .then(() => getSyncSummary().then((rows) => setSync(Object.fromEntries(rows.map((r) => [r.configurationId, r])))).catch(() => undefined));

  useEffect(() => {
    void load();
    listApplications().then((a) => setApps(a.filter((x) => (x.type ?? 'app') !== 'web'))).catch(() => undefined);
  }, []);

  if (editing) {
    return (
      <AppShell title="Política">
        <ConfigEditor
          initial={editing}
          apps={apps}
          readOnly={readOnly}
          deviceCount={editing.id != null ? affectedDeviceCount(sync[editing.id]) : 0}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void load();
          }}
          onDuplicate={() => {
            setEditing(cloneForNew(editing));
            setReadOnly(false);
          }}
        />
      </AppShell>
    );
  }

  return (
    <AppShell title="Políticas">
      <div className="page-head">
        <h1>Políticas</h1>
        {view === 'policies' && (
          <button className="btn btn-dark" onClick={() => setChooserOpen(true)}>
            Nueva política
          </button>
        )}
      </div>
      <div className="seg" role="tablist" aria-label="Políticas o grupos de aplicaciones" style={{ marginBottom: 14 }}>
        <button type="button" className={view === 'policies' ? 'on' : ''} onClick={() => setView('policies')}>Políticas</button>
        <button type="button" className={view === 'groups' ? 'on' : ''} onClick={() => setView('groups')} data-testid="tab-app-groups">Grupos de aplicaciones</button>
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {view === 'groups' ? (
        <AppGroupsPanel apps={apps} lockedPolicy={isLocked} onChanged={() => void load()} />
      ) : configs === null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Cargando…</div></div>
      ) : configs.length === 0 ? (
        <div className="panel"><div className="empty"><span className="label">No hay políticas</span>Crea una para usarla como plantilla de dispositivos.</div></div>
      ) : (
        <div className="cfg-grid">
          {configs.map((c) => (
            <ConfigCard
              key={c.id}
              c={c}
              locked={isLocked(c)}
              appName={appNameForVersionId(apps, c.mainAppId as number | undefined)}
              appCount={c.id != null ? appCounts[c.id] : undefined}
              sync={c.id != null ? sync[c.id] : undefined}
              onEdit={() => {
                setReadOnly(isLocked(c));
                setEditing(c);
              }}
              onCopy={() => setCopyOf(c)}
              onDelete={() => void doDelete(c)}
            />
          ))}
        </div>
      )}

      {copyOf && (
        <CopyModal
          source={copyOf}
          onClose={() => setCopyOf(null)}
          onDone={() => {
            setCopyOf(null);
            void load();
          }}
        />
      )}

      {chooserOpen && (
        <NewChooser
          defaults={(configs ?? []).filter(isLocked)}
          onClose={() => setChooserOpen(false)}
          onPick={(base) => {
            setEditing(cloneForNew(base));
            setReadOnly(false);
            setChooserOpen(false);
          }}
        />
      )}
    </AppShell>
  );

  async function doDelete(c: Configuration) {
    if (c.id == null) return;
    if (!window.confirm(`¿Eliminar la política "${c.name}"? Esta acción no se puede deshacer.`)) return;
    try {
      await deleteConfiguration(c.id);
      toast.push('ok', 'Política eliminada', c.name);
      void load();
    } catch (e) {
      const msg = e instanceof Error ? e.message : '';
      toast.push('err', 'No se pudo eliminar', /device/i.test(msg) ? 'Todavía hay dispositivos que usan esta política.' : msg);
    }
  }
}

/**
 * Devices that will actually re-apply kiosk: agents too old for config.apply ("unsupported") won't.
 * null = no sync summary for this configuration — the caller fails closed (dialog without a number).
 */
function affectedDeviceCount(row: ConfigSyncSummary | undefined): number | null {
  return row ? Math.max(0, row.total - row.unsupported) : null;
}

// configurations.mainAppId is an applicationVersions.id (NOT an applications.id): the server
// maps it back to the assigned app via configurationApplications.applicationVersionId.

/** The version id the main-app picker writes for an app: its assigned version, else its latest. */
function versionIdForApp(app: Application, assigned: ConfigApp[]): number | undefined {
  return assigned.find((x) => x.id === app.id)?.usedVersionId ?? app.latestVersion;
}

/** Display name for a mainAppId (a version id); a dash when it can't be resolved. */
function appNameForVersionId(apps: Application[], versionId?: number, assigned: ConfigApp[] = []): string {
  if (versionId == null) return '—';
  const a = assigned.find((x) => x.usedVersionId === versionId);
  if (a) return a.name ?? a.pkg ?? '—';
  return apps.find((x) => x.latestVersion === versionId)?.name ?? '—';
}

function ConfigCard({
  c,
  locked,
  appName,
  appCount,
  sync,
  onEdit,
  onCopy,
  onDelete,
}: {
  c: Configuration;
  locked: boolean;
  appName: string;
  appCount?: number;
  sync?: ConfigSyncSummary;
  onEdit: () => void;
  onCopy: () => void;
  onDelete: () => void;
}) {
  return (
    <div className="cfg-card">
      <div className="cfg-top">
        <div className="cfg-nm">{c.name}</div>
        {locked ? <span className="cfg-badge default">Predeterminada</span> : null}
        {c.kioskMode ? <span className="cfg-badge">Quiosco</span> : null}
      </div>
      {c.description ? <div className="cfg-desc">{String(c.description)}</div> : null}
      <div className="cfg-meta">
        <span><span className="k">App principal</span><span className="v">{appName}</span></span>
        <span><span className="k">Apps</span><span className="v">{appCount ?? '…'}</span></span>
      </div>
      <SyncBar s={sync} />
      <div className="cfg-actions">
        <button className="btn btn-sm btn-primary" onClick={onEdit}>{locked ? <span key="view">Ver</span> : <span key="edit">Editar</span>}</button>
        <button className="btn btn-sm" onClick={onCopy}>{locked ? <span key="tpl">Usar como plantilla</span> : <span key="copy">Copiar</span>}</button>
        {!locked && <button className="btn btn-sm btn-danger" onClick={onDelete}>Eliminar</button>}
      </div>
    </div>
  );
}

function NewChooser({
  defaults,
  onClose,
  onPick,
}: {
  defaults: Configuration[];
  onClose: () => void;
  onPick: (base: Configuration | null) => void;
}) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Nueva política</h3>
        <p className="muted" style={{ margin: '2px 0 14px' }}>Empieza desde cero o parte de una plantilla predeterminada.</p>
        <div className="chooser-list">
          <button className="chooser-opt" onClick={() => onPick(null)}>
            <span className="chooser-nm">Política en blanco</span>
            <span className="chooser-sub">Plantilla vacía: tú defines todo.</span>
          </button>
          {defaults.map((d) => (
            <button key={d.id} className="chooser-opt" onClick={() => onPick(d)}>
              <span className="chooser-nm">Basada en “{d.name}”</span>
              <span className="chooser-sub">Copia los ajustes y las apps de esta plantilla y luego personalízala.</span>
            </button>
          ))}
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancelar</button>
        </div>
      </div>
    </div>
  );
}

function CopyModal({ source, onClose, onDone }: { source: Configuration; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [name, setName] = useState(`${source.name} copia`);
  const [busy, setBusy] = useState(false);
  async function go() {
    if (!name.trim() || source.id == null) return;
    setBusy(true);
    try {
      await copyConfiguration(source.id, name.trim(), source.description as string | undefined);
      toast.push('ok', 'Política copiada', name.trim());
      onDone();
    } catch (e) {
      toast.push('err', 'No se pudo copiar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Copiar política</h3>
        <label className="field"><span>Nombre nuevo</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </label>
        <div className="modal-actions">
          <button className="btn" onClick={onClose} disabled={busy}>Cancelar</button>
          <button className="btn btn-primary" disabled={busy || !name.trim()} onClick={() => void go()}>
            {busy ? <span key="busy">Copiando…</span> : <span key="idle">Copiar</span>}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Editor ────────────────────────────────────────────────────────────────-
function ConfigEditor({
  initial,
  apps,
  readOnly,
  deviceCount,
  onCancel,
  onSaved,
  onDuplicate,
}: {
  initial: Configuration;
  apps: Application[];
  readOnly: boolean;
  /** Devices that will re-apply kiosk; null when unknown (confirm without a number). */
  deviceCount: number | null;
  onCancel: () => void;
  onSaved: () => void;
  onDuplicate: () => void;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<Configuration>(() => ({ ...initial }));
  // The unmodified starting point for change detection (e.g. kioskAffectingChanges).
  // Kept separate from `initial` because the list endpoint omits `applications` —
  // once the real assigned apps load, both `draft` and `baseline` are updated so
  // comparisons don't see a spurious apps diff on every save.
  const [baseline, setBaseline] = useState<Configuration>(() => ({ ...initial }));
  const [advanced, setAdvanced] = useState(false);
  const [busy, setBusy] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [confirmKeys, setConfirmKeys] = useState<string[] | null>(null);
  const isNew = initial.id == null;

  // The list endpoint doesn't carry a config's assigned apps, so for an existing
  // config we must fetch them here. Until they arrive, block save — otherwise a
  // PUT (which replaces the whole app set) would wipe the config's apps.
  // A failed fetch does NOT unblock save (fail closed): with desired state, saving an
  // empty app set would also wipe the kiosk allowlist on every device. Offer a retry.
  const [appsReady, setAppsReady] = useState(isNew);
  const [appsError, setAppsError] = useState(false);
  const [appsAttempt, setAppsAttempt] = useState(0);
  useEffect(() => {
    if (initial.id == null) return;
    let cancelled = false;
    setAppsError(false);
    getConfigurationApps(initial.id)
      .then((assigned) => {
        if (cancelled) return;
        setDraft((d) => ({ ...d, applications: assigned }));
        setBaseline((b) => ({ ...b, applications: assigned }));
        setAppsReady(true);
      })
      .catch(() => {
        if (!cancelled) setAppsError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [initial.id, appsAttempt]);

  const set = (key: string, value: unknown) => setDraft((d) => ({ ...d, [key]: value }));

  const allowed: ConfigApp[] = (draft.applications as ConfigApp[] | undefined) ?? [];
  const allowedIds = useMemo(() => new Set(allowed.map((a) => a.id)), [allowed]);
  // The Library versions of each app (loaded when its selector opens) and every version name: each policy can use its
  // own version of an app, and names tell builds apart.
  const [versions, setVersions] = useState<Record<number, LabeledVersion[]>>({});
  const [labels, setLabels] = useState<Record<string, string>>({});
  const [uploadLabel, setUploadLabel] = useState('');
  /** Apps this policy may take back to an older version (phones with a newer one reinstall it), as saved and as edited. */
  const [downgradeSaved, setDowngradeSaved] = useState<Set<number>>(new Set());
  const [downgrade, setDowngrade] = useState<Set<number>>(new Set());
  useEffect(() => {
    if (initial.id == null) return;
    listPolicyDowngrades(initial.id as number).then((ids) => { setDowngradeSaved(new Set(ids)); setDowngrade(new Set(ids)); }).catch(() => undefined);
  }, [initial.id]);
  useEffect(() => { listVersionLabels().then(setLabels).catch(() => undefined); }, []);
  async function loadVersions(appId: number, force = false) {
    if (!force && versions[appId]) return;
    const list = await listAppVersions(appId).catch(() => [] as LabeledVersion[]);
    setVersions((v) => ({ ...v, [appId]: list }));
  }

  function addApps(chosen: Application[]) {
    const entries: ConfigApp[] = chosen.map((app) => ({
      id: app.id,
      name: app.name,
      pkg: app.pkg,
      version: app.version,
      // Same version the server would default to; lets the main-app picker resolve it.
      usedVersionId: app.latestVersion,
      action: 1,
      showIcon: true,
      remove: false,
    }));
    set('applications', [...allowed, ...entries]);
  }
  function removeApp(id: number) {
    set('applications', allowed.filter((a) => a.id !== id));
  }
  function setAppAction(id: number, action: number) {
    set('applications', allowed.map((a) => (a.id === id ? { ...a, action } : a)));
  }

  function requestSave() {
    if (!String(draft.name ?? '').trim()) {
      toast.push('err', 'Falta el nombre', 'Ponle un nombre a la política.');
      return;
    }
    if (!appsReady) {
      toast.push('err', 'Aún cargando', 'Las apps asignadas todavía se están cargando; intenta de nuevo en un momento.');
      return;
    }
    const keys = isNew ? [] : kioskAffectingChanges(baseline, draft);
    // deviceCount null = unknown -> confirm anyway (fail closed); 0 = no device will re-apply.
    if (keys.length > 0 && deviceCount !== 0) { setConfirmKeys(keys); return; }
    void doSave();
  }

  async function uploadIntoPolicy(file: File) {
    setUploadingApk(true);
    try {
      const r = await uploadApkToLibrary(file, uploadLabel);
      if (r.kind === 'error') { toast.push('err', 'No se agregó el APK', r.note); return; }
      // This policy uses exactly the uploaded version (new, or the one already in the Library).
      const pin = (list: ConfigApp[]) => list.map((a) => (a.id === r.app.id && r.versionId
        ? { ...a, usedVersionId: r.versionId, version: r.version ?? a.version } : a));
      if (!allowed.some((a) => a.id === r.app.id)) {
        const base: ConfigApp = { id: r.app.id, name: r.app.name, pkg: r.app.pkg, version: r.version ?? r.app.version,
          usedVersionId: r.versionId ?? r.app.latestVersion, action: 1, showIcon: true, remove: false };
        set('applications', [...allowed, base]);
      } else {
        set('applications', pin(allowed));
      }
      setUploadLabel('');
      void loadVersions(r.app.id, true);
      toast.push('ok', r.kind === 'new' ? 'APK agregado a la política' : r.kind === 'existing' ? 'La política usará esa versión' : 'Nueva versión',
        `${r.note} Guarda la política para aplicarla.`);
    } catch (e) {
      toast.push('err', 'Falló la subida', e instanceof Error ? e.message : '');
    } finally {
      setUploadingApk(false);
    }
  }

  async function doSave() {
    setBusy(true);
    try {
      const saved = await saveConfiguration(draft);
      const policyId = (saved?.id ?? draft.id) as number | undefined;
      // Going back to older versions: what the version pickers allowed (after the warning), saved with the policy.
      if (policyId != null) {
        for (const id of new Set([...downgrade, ...downgradeSaved])) {
          if (downgrade.has(id) !== downgradeSaved.has(id)) await setPolicyDowngrade(policyId, id, downgrade.has(id)).catch(() => undefined);
        }
      }
      if (policyId != null && folders !== null) {
        const n = await applyPolicyFolders(policyId, folders);
        if (n) toast.push('ok', 'Carpetas actualizadas', `${n} dispositivo${n === 1 ? '' : 's'} toman esta política.`);
      }
      toast.push('ok', isNew ? 'Política creada' : 'Política guardada', String(draft.name));
      onSaved();
    } catch (e) {
      const msg = e instanceof Error ? e.message : '';
      toast.push('err', 'No se pudo guardar', /duplicate/i.test(msg) ? 'Ya existe una política con ese nombre.' : msg);
    } finally {
      setBusy(false);
    }
  }

  const [folders, setFolders] = useState<Set<number> | null>(null);
  const [uploadingApk, setUploadingApk] = useState(false);

  const enforcedByGroup = GROUP_ORDER.map((g) => ({
    group: g,
    fields: PRIMARY_FIELDS.filter((f) => f.group === g),
  })).filter((x) => x.fields.length > 0);

  const legacyByGroup = GROUP_ORDER.map((g) => ({
    group: g,
    fields: LEGACY_FIELDS.filter((f) => f.group === g),
  })).filter((x) => x.fields.length > 0);

  return (
    <>
      <div className="crumb">
        <a href="/configs" onClick={(e) => { e.preventDefault(); onCancel(); }}>Políticas</a>
        {' / '}{isNew ? 'Nueva' : String(initial.name)}
      </div>

      <div className="cfg-editbar">
        <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>
          {isNew ? 'Nueva política' : String(initial.name)}
        </h1>
        <div style={{ flex: 1 }} />
        <button className="btn" onClick={onCancel} disabled={busy}>{readOnly ? <span key="back">Volver</span> : <span key="cancel">Cancelar</span>}</button>
        {readOnly ? (
          <button className="btn btn-primary" onClick={onDuplicate}>Duplicar para editar</button>
        ) : (
          <button className="btn btn-primary" onClick={requestSave} disabled={busy || !appsReady}>
            {busy ? <span key="saving">Guardando…</span> : !appsReady && !appsError ? <span key="loading">Cargando…</span> : <span key="save">Guardar</span>}
          </button>
        )}
      </div>

      {appsError && !appsReady ? (
        <div className="banner banner-alert" role="alert" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span>No se pudieron cargar las apps asignadas a esta política. Guardar está desactivado para no borrar la lista de apps (ni las apps permitidas del quiosco).</span>
          <button className="btn btn-sm" style={{ marginLeft: 'auto' }} onClick={() => setAppsAttempt((n) => n + 1)}>
            Reintentar
          </button>
        </div>
      ) : null}

      {confirmKeys ? (
        <KioskChangeConfirm
          count={deviceCount}
          keys={confirmKeys}
          onCancel={() => setConfirmKeys(null)}
          onConfirm={() => { setConfirmKeys(null); void doSave(); }}
        />
      ) : null}

      {readOnly && (
        <div className="banner cfg-default-note">
          Esta es una plantilla predeterminada incluida: solo lectura. Usa <b>Duplicar para editar</b> para crear
          tu propia copia editable.
        </div>
      )}

      {enforcedByGroup.map(({ group, fields }) => (
        <section className="panel cfg-panel" key={group}>
          <div className="cfg-sec-h">{group}</div>
          {fields.map((f) => (
            <Field key={f.key} def={f} value={draft[f.key]} apps={apps} assigned={allowed} disabled={readOnly} onChange={(v) => set(f.key, v)} />
          ))}
        </section>
      ))}

      <section className="panel cfg-panel">
        <div className="cfg-sec-h" style={{ display: 'flex', alignItems: 'center' }}>
          <span>Apps permitidas</span>
          {!readOnly && (
            <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 8 }}>
              <input className="input" style={{ width: 200 }} placeholder="Nombre de la versión (opcional)" value={uploadLabel}
                maxLength={80} onChange={(e) => setUploadLabel(e.target.value)} aria-label="Nombre de la versión" />
              <label className="btn btn-sm" data-testid="policy-upload-apk" style={{ cursor: uploadingApk ? 'wait' : 'pointer' }}>
                {uploadingApk ? <span key="u">Subiendo…</span> : <span key="s">Subir APK</span>}
                <input type="file" accept=".apk" hidden disabled={uploadingApk}
                  onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) void uploadIntoPolicy(f); }} />
              </label>
              <button className="btn btn-sm" onClick={() => setPickerOpen(true)}>
                Agregar desde la biblioteca
              </button>
            </span>
          )}
        </div>
        <p className="note" style={{ margin: '0 0 12px' }}>
          Apps que esta política instala y mantiene en sus dispositivos (sube el APK aquí mismo; una versión nueva llega sola
          a los teléfonos). Marca una app como “Desinstalar” para quitarla.
        </p>
        {allowed.length === 0 && <div className="cfg-empty">No hay apps asignadas.</div>}
        {allowed.map((a) => (
          <div className="cfg-app" key={a.id}>
            <span className="cfg-app-nm">{a.name ?? a.pkg ?? `#${a.id}`}</span>
            <span className="cfg-app-pkg mono">{a.pkg}</span>
            <select className="sel" aria-label={`Versión de ${a.name ?? a.pkg}`} disabled={readOnly}
              value={a.usedVersionId ?? ''} onFocus={() => void loadVersions(a.id)}
              onChange={(e) => {
                const list = versions[a.id] ?? [];
                const v = list.find((x) => x.id === Number(e.target.value));
                if (!v) return;
                const isOlder = (v.versionCode ?? 0) < topCode(list);
                if (isOlder && !window.confirm(
                  `${v.version} (código ${v.versionCode}) es más vieja que otra versión de la Biblioteca (código ${topCode(list)}).\n\n` +
                  'Los teléfonos de esta política que tengan una versión más nueva la desinstalarán e instalarán esta: se borran los datos ' +
                  'de la app en esos teléfonos (sesión, lo que no se haya sincronizado).\n\n¿Usar esta versión?')) return;
                setDowngrade((d) => { const n = new Set(d); if (isOlder) n.add(a.id); else n.delete(a.id); return n; });
                set('applications', allowed.map((x) => (x.id === a.id ? { ...x, usedVersionId: v.id, version: v.version ?? x.version } : x)));
              }}>
              {!(versions[a.id] ?? []).some((v) => v.id === a.usedVersionId) && (
                <option value={a.usedVersionId ?? ''}>{a.usedVersionId && labels[String(a.usedVersionId)] ? `${labels[String(a.usedVersionId)]} · ` : ''}{a.version ?? '—'}</option>
              )}
              {(versions[a.id] ?? []).map((v) => (
                <option key={v.id} value={v.id}>{v.label ? `${v.label} · ` : ''}{v.version ?? '—'} ({v.versionCode ?? '?'})</option>
              ))}
            </select>
            <select
              className="sel"
              value={a.action ?? 1}
              disabled={readOnly}
              onChange={(e) => setAppAction(a.id, Number(e.target.value))}
            >
              <option value={1}>Instalar</option>
              <option value={2}>Desinstalar</option>
              <option value={0}>Ocultar ícono</option>
            </select>
            {!!draft.kioskMode && (a.action ?? 1) === 1 && !!a.pkg && (
              <label className="cfg-kiosk-chk" title="Si lo desmarcas, la app se instala y se permite, pero no aparece en el quiosco">
                <input type="checkbox" disabled={readOnly}
                       checked={!(parseDcPolicy(draft.dcPolicy).notInKiosk ?? []).includes(a.pkg)}
                       onChange={(e) => {
                         const dc = parseDcPolicy(draft.dcPolicy);
                         const cur = new Set(dc.notInKiosk ?? []);
                         if (e.target.checked) cur.delete(a.pkg as string); else cur.add(a.pkg as string);
                         set('dcPolicy', serializeDcPolicy({ ...dc, notInKiosk: [...cur] }));
                       }} />
                En quiosco
              </label>
            )}
            {!readOnly && (
              <button className="btn btn-sm btn-ghost" onClick={() => removeApp(a.id)} aria-label="Quitar app">✕</button>
            )}
          </div>
        ))}
      </section>

      {!!draft.kioskMode && (
        <section className="panel cfg-panel">
          <div className="cfg-sec-h">Marca del quiosco</div>
          <p className="note" style={{ margin: '0 0 12px' }}>
            Logo arriba de las apps, logo y serial en el pie, fondo y un botón para llamar a soporte. Cada carpeta puede poner
            sus propios logos, fondo y número (Carpetas → Marca): gana la carpeta más cercana al equipo.
          </p>
          <KioskBrandEditor
            value={parseDcPolicy(draft.dcPolicy).kioskBrand ?? {}}
            disabled={readOnly}
            colors={{ bg: draft.backgroundColor as string | undefined, text: draft.textColor as string | undefined }}
            onChange={(b) => set('dcPolicy', serializeDcPolicy({ ...parseDcPolicy(draft.dcPolicy), kioskBrand: b }))}
          />
        </section>
      )}

      <PolicyAppGroups
        dc={parseDcPolicy(draft.dcPolicy)}
        kiosk={!!draft.kioskMode}
        disabled={readOnly}
        onChange={(dc) => set('dcPolicy', serializeDcPolicy(dc))}
      />

      <DcPolicyPanel value={draft.dcPolicy} disabled={readOnly} onChange={(v) => set('dcPolicy', v)} />

      {!readOnly && <PolicyFolders policyId={draft.id as number | undefined} value={folders} onChange={setFolders} />}

      <button className="cfg-adv-toggle" onClick={() => setAdvanced((v) => !v)}>
        {advanced ? '▾' : '▸'} Campos heredados de Headwind ({LEGACY_FIELDS.length}): el agente DallyControl no los aplica
      </button>
      {advanced && (
        <p className="cfg-legacy-note">Estos campos se guardan con la política, pero el agente DallyControl todavía no los aplica. Se conservan para el launcher incluido y para futuras migraciones.</p>
      )}

      {advanced &&
        legacyByGroup.map(({ group, fields }) => (
          <section className="panel cfg-panel" key={group}>
            <div className="cfg-sec-h">{group}</div>
            {fields.map((f) => (
              <Field key={f.key} def={f} value={draft[f.key]} apps={apps} assigned={allowed} disabled={readOnly} onChange={(v) => set(f.key, v)} />
            ))}
          </section>
        ))}

      {pickerOpen && (
        <AppPicker
          apps={apps}
          excludeIds={allowedIds}
          onAdd={addApps}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </>
  );
}

// ── Field control ──────────────────────────────────────────────────────────
function Field({
  def,
  value,
  apps,
  assigned,
  disabled,
  onChange,
}: {
  def: FieldDef;
  value: unknown;
  apps: Application[];
  assigned: ConfigApp[];
  disabled?: boolean;
  onChange: (v: unknown) => void;
}) {
  return (
    <div className="cfg-field">
      <div className="cfg-field-label">
        <label>{def.label}</label>
        {def.enforced ? <span className="chip chip-enforced" title="El agente DallyControl lo aplica en los dispositivos">Aplicado</span> : null}
        <span className="cfg-field-help">{def.help}</span>
      </div>
      <div className="cfg-field-ctl">
        <FieldControl def={def} value={value} apps={apps} assigned={assigned} disabled={disabled} onChange={onChange} />
      </div>
    </div>
  );
}

function FieldControl({ def, value, apps, assigned, disabled, onChange }: { def: FieldDef; value: unknown; apps: Application[]; assigned: ConfigApp[]; disabled?: boolean; onChange: (v: unknown) => void }) {
  switch (def.type) {
    case 'switch':
      return (
        <input type="checkbox" className="dev-check" checked={value === true} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      );
    case 'tri':
      return (
        <span className="seg">
          {[
            { v: null, l: 'Auto' },
            { v: true, l: 'Sí' },
            { v: false, l: 'No' },
          ].map((o) => (
            <button key={String(o.v)} className={value === o.v || (o.v === null && value == null) ? 'on' : ''} disabled={disabled} onClick={() => onChange(o.v)}>
              {o.l}
            </button>
          ))}
        </span>
      );
    case 'enum': {
      const numeric = typeof def.options?.[0]?.value === 'number';
      return (
        <select className="sel" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : numeric ? Number(e.target.value) : e.target.value)}>
          <option value="">—</option>
          {def.options?.map((o) => (
            <option key={String(o.value)} value={String(o.value)}>{o.label}</option>
          ))}
        </select>
      );
    }
    case 'app': {
      // The stored value is an applicationVersions.id (see versionIdForApp).
      const options = apps
        .map((a) => ({ a, vid: versionIdForApp(a, assigned) }))
        .filter((o): o is { a: Application; vid: number } => o.vid != null);
      const known = value == null || options.some((o) => o.vid === value);
      return (
        <select className="sel" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}>
          <option value="">— ninguna —</option>
          {!known && <option value={String(value)}>{appNameForVersionId(apps, value as number, assigned)} (versión #{String(value)})</option>}
          {options.map(({ a, vid }) => (
            <option key={a.id} value={vid}>{a.name} ({a.pkg})</option>
          ))}
        </select>
      );
    }
    case 'int':
      return (
        <input className="input" type="number" min={def.min} max={def.max} value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} />
      );
    case 'textarea':
      return <textarea className="input" rows={2} value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    case 'time':
      return <input className="input" type="time" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value || null)} />;
    case 'color':
      return <input type="color" value={value ? String(value) : '#ffffff'} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    case 'password':
      return <input className="input" type="password" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} autoComplete="new-password" />;
    default:
      return <input className="input" type="text" value={value == null ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
  }
}

/** The app groups this policy uses; per group, whether its apps also show in the kiosk. */
function PolicyAppGroups({ dc, kiosk, disabled, onChange }: {
  dc: DcPolicy; kiosk: boolean; disabled?: boolean; onChange: (dc: DcPolicy) => void;
}) {
  const [groups, setGroups] = useState<AppGroup[] | null>(null);
  useEffect(() => { listAppGroups().then(setGroups).catch(() => setGroups([])); }, []);
  const used = new Map((dc.appGroups ?? []).map((g) => [g.id, !!g.kiosk]));
  const put = (id: number, on: boolean, showInKiosk: boolean) => {
    const next = (dc.appGroups ?? []).filter((g) => g.id !== id);
    if (on) next.push({ id, kiosk: showInKiosk || undefined });
    onChange({ ...dc, appGroups: next });
  };
  return (
    <section className="panel cfg-panel">
      <div className="cfg-sec-h">Grupos de aplicaciones</div>
      <p className="note" style={{ margin: '0 0 12px' }}>
        Todas las apps de los grupos elegidos se instalan y se permiten en los teléfonos de esta política (siempre la última
        versión). {kiosk ? 'Marca «En quiosco» para que además aparezcan en el quiosco; si no, solo quedan instaladas.' : ''}
        {' '}Los grupos se crean en la pestaña «Grupos de aplicaciones».
      </p>
      {groups == null ? <div className="cfg-empty">Cargando…</div> : groups.length === 0 ? (
        <div className="cfg-empty">Aún no hay grupos de aplicaciones.</div>
      ) : groups.map((g) => (
        <div className="cfg-app" key={g.id}>
          <label className="cfg-app-nm" style={{ display: 'inline-flex', gap: 8, alignItems: 'center', cursor: disabled ? 'default' : 'pointer' }}>
            <input type="checkbox" disabled={disabled} checked={used.has(g.id)} data-testid={`policy-group-${g.id}`}
                   onChange={(e) => put(g.id, e.target.checked, used.get(g.id) ?? false)} />
            {g.name}
          </label>
          <span className="cfg-app-pkg">{g.apps.map((a) => a.name || a.pkg).join(', ') || 'sin apps'}</span>
          {kiosk && used.has(g.id) && (
            <label className="cfg-kiosk-chk">
              <input type="checkbox" disabled={disabled} checked={!!used.get(g.id)} onChange={(e) => put(g.id, true, e.target.checked)} />
              En quiosco
            </label>
          )}
        </div>
      ))}
    </section>
  );
}
