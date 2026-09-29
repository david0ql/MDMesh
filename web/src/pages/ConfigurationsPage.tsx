import { DcPolicyPanel } from '../components/DcPolicyPanel';
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
        setError('No se pudieron cargar las configuraciones.');
      })
      .then(() => getSyncSummary().then((rows) => setSync(Object.fromEntries(rows.map((r) => [r.configurationId, r])))).catch(() => undefined));

  useEffect(() => {
    void load();
    listApplications().then((a) => setApps(a.filter((x) => (x.type ?? 'app') !== 'web'))).catch(() => undefined);
  }, []);

  if (editing) {
    return (
      <AppShell title="Configuración">
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
    <AppShell title="Configuraciones">
      <div className="page-head">
        <h1>Configuraciones</h1>
        <button className="btn btn-dark" onClick={() => setChooserOpen(true)}>
          Nueva configuración
        </button>
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {configs === null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Cargando…</div></div>
      ) : configs.length === 0 ? (
        <div className="panel"><div className="empty"><span className="label">No hay configuraciones</span>Crea una para usarla como plantilla de dispositivos.</div></div>
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
    if (!window.confirm(`¿Eliminar la configuración "${c.name}"? Esta acción no se puede deshacer.`)) return;
    try {
      await deleteConfiguration(c.id);
      toast.push('ok', 'Configuración eliminada', c.name);
      void load();
    } catch (e) {
      const msg = e instanceof Error ? e.message : '';
      toast.push('err', 'No se pudo eliminar', /device/i.test(msg) ? 'Todavía hay dispositivos que usan esta configuración.' : msg);
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
        <h3>Nueva configuración</h3>
        <p className="muted" style={{ margin: '2px 0 14px' }}>Empieza desde cero o parte de una plantilla predeterminada.</p>
        <div className="chooser-list">
          <button className="chooser-opt" onClick={() => onPick(null)}>
            <span className="chooser-nm">Configuración en blanco</span>
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
      toast.push('ok', 'Configuración copiada', name.trim());
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
        <h3>Copiar configuración</h3>
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
      toast.push('err', 'Falta el nombre', 'Ponle un nombre a la configuración.');
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

  async function doSave() {
    setBusy(true);
    try {
      await saveConfiguration(draft);
      toast.push('ok', isNew ? 'Configuración creada' : 'Configuración guardada', String(draft.name));
      onSaved();
    } catch (e) {
      const msg = e instanceof Error ? e.message : '';
      toast.push('err', 'No se pudo guardar', /duplicate/i.test(msg) ? 'Ya existe una configuración con ese nombre.' : msg);
    } finally {
      setBusy(false);
    }
  }

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
        <a href="/configs" onClick={(e) => { e.preventDefault(); onCancel(); }}>Configuraciones</a>
        {' / '}{isNew ? 'Nueva' : String(initial.name)}
      </div>

      <div className="cfg-editbar">
        <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>
          {isNew ? 'Nueva configuración' : String(initial.name)}
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
          <span>No se pudieron cargar las apps asignadas a esta configuración. Guardar está desactivado para no borrar la lista de apps (ni las apps permitidas del quiosco).</span>
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
            <button className="btn btn-sm" style={{ marginLeft: 'auto' }} onClick={() => setPickerOpen(true)}>
              Agregar apps
            </button>
          )}
        </div>
        <p className="note" style={{ margin: '0 0 12px' }}>
          Apps que esta plantilla instala en sus dispositivos. Marca una app como “Desinstalar” para quitarla.
        </p>
        {allowed.length === 0 && <div className="cfg-empty">No hay apps asignadas.</div>}
        {allowed.map((a) => (
          <div className="cfg-app" key={a.id}>
            <span className="cfg-app-nm">{a.name ?? a.pkg ?? `#${a.id}`}</span>
            <span className="cfg-app-pkg mono">{a.pkg}</span>
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
            {!readOnly && (
              <button className="btn btn-sm btn-ghost" onClick={() => removeApp(a.id)} aria-label="Quitar app">✕</button>
            )}
          </div>
        ))}
      </section>

      <DcPolicyPanel value={draft.dcPolicy} disabled={readOnly} onChange={(v) => set('dcPolicy', v)} />

      <button className="cfg-adv-toggle" onClick={() => setAdvanced((v) => !v)}>
        {advanced ? '▾' : '▸'} Campos heredados de Headwind ({LEGACY_FIELDS.length}): el agente DallyControl no los aplica
      </button>
      {advanced && (
        <p className="cfg-legacy-note">Estos campos se guardan con la configuración, pero el agente DallyControl todavía no los aplica. Se conservan para el launcher incluido y para futuras migraciones.</p>
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
