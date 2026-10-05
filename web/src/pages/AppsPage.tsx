import { useEffect, useMemo, useRef, useState } from 'react';
import { VersionsDialog } from '../components/VersionsDialog';
import { listAppVersions, setVersionLabel } from '../api/versions';
import { hasApk, obtainApk } from '../api/apkFetch';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import {
  listApplications,
  getVersions,
  addApplicationVersion,
  type ApplicationVersion,
  uploadApk,
  uploadBundle,
  commitUpload,
  isNewVersion,
  uniqueApkName,
  saveAndroidApplication,
  lookupPlayApp,
  type PlayApp,
  type Application,
  type BundleUploadResult,
} from '../api/applications';
import { searchFdroid, type FDroidApp } from '../api/fdroid';
import { DeployModal, type DeploySubject } from '../components/DeployModal';

type SourceId = 'library' | 'custom' | 'device' | 'fdroid' | 'play';

interface Source {
  id: SourceId;
  label: string;
  enabled: boolean;
  tip: string;
}

const SOURCES: Source[] = [
  { id: 'library', label: 'Biblioteca', enabled: true, tip: 'Apps ya subidas a este servidor de DallyControl.' },
  { id: 'custom', label: 'APK propio', enabled: true, tip: 'Despliega cualquier APK por archivo o URL, incluidas descargas de APKMirror / APKPure.' },
  { id: 'device', label: 'En el teléfono', enabled: true, tip: 'Registra una app que los teléfonos ya tienen (Chrome, WhatsApp de la Play Store…) para que las políticas puedan permitirla. No se instala nada.' },
  { id: 'fdroid', label: 'F-Droid', enabled: true, tip: 'Busca en el catálogo de código abierto de F-Droid y despliega directamente desde f-droid.org.' },
  { id: 'play', label: 'Play Store', enabled: true, tip: 'Agrega una app de la Play Store por paquete o enlace. Al desplegarla se abre su página de la Play Store en los teléfonos (la persona toca Instalar); una política la permite en el quiosco y en la política de apps.' },
];

// APKMirror / APKPure have no usable API and forbid embedding — they're search
// shortcuts into the Custom APK flow (open the site, download, drop the file).
const EXT_SOURCES: { label: string; url: string }[] = [
  { label: 'APKMirror', url: 'https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=' },
  { label: 'APKPure', url: 'https://apkpure.com/search?q=' },
];

async function resolveApp(app: Application): Promise<DeploySubject> {
  let url = app.url;
  let versionCode = app.versionCode;
  let sha256: string | undefined;
  let partsJson: string | undefined = app.parts;
  try {
    const vs = await getVersions(app.id);
    const latest = [...vs]
      .filter((v) => v.url || v.parts) // a split-bundle version has parts but no single url
      .sort((a, b) => (b.versionCode ?? 0) - (a.versionCode ?? 0))[0];
    if (latest) {
      url = latest.url ?? url;
      versionCode = latest.versionCode ?? versionCode;
      sha256 = latest.apkHash || undefined;
      partsJson = latest.parts ?? partsJson;
    }
  } catch {
    /* fall back to the app's own fields */
  }
  let parts: { url: string; sha256?: string }[] | undefined;
  if (partsJson) {
    try {
      parts = (JSON.parse(partsJson) as { url: string; sha256?: string }[]).map((p) => ({ url: p.url, sha256: p.sha256 }));
    } catch {
      /* malformed parts — ignore, fall back to url */
    }
  }
  // No APK (a Play Store / on-the-phone app): the deploy dialog opens its Play Store page instead of installing it.
  return { label: app.name, packageName: app.pkg, url: url ?? '', versionCode, sha256, applicationId: app.id, parts };
}

export function AppsPage() {
  const toast = useToast();
  const [source, setSource] = useState<SourceId>('library');
  const [deploy, setDeploy] = useState<DeploySubject | null>(null);

  return (
    <AppShell title="Apps">
      <div className="page-head">
        <h1>Apps</h1>
      </div>

      <span className="seg modes" role="tablist" aria-label="Origen de la app" style={{ marginBottom: 16 }}>
        {SOURCES.map((s) => (
          <span className="tip" key={s.id}>
            <button
              className={source === s.id ? 'on' : ''}
              disabled={!s.enabled}
              onClick={() => s.enabled && setSource(s.id)}
              role="tab"
              aria-selected={source === s.id}
              aria-describedby={`src-${s.id}`}
            >
              {s.label}
              {!s.enabled && <span className="src-soon">pronto</span>}
            </button>
            <span className="tip-pop" role="tooltip" id={`src-${s.id}`}>
              {s.tip}
            </span>
          </span>
        ))}
      </span>

      {source === 'library' && (
        <LibrarySource onDeploy={(app) => {
          resolveApp(app)
            .then(setDeploy)
            .catch((e) => toast.push('err', 'No se puede desplegar', e instanceof Error ? e.message : ''));
        }} />
      )}
      {source === 'custom' && <CustomSource onDeploy={setDeploy} />}
      {source === 'device' && <DeviceAppSource />}
      {source === 'fdroid' && <FDroidSource onDeploy={setDeploy} />}
      {source === 'play' && <PlayStoreSource />}

      {deploy && <DeployModal subject={deploy} onClose={() => setDeploy(null)} />}
    </AppShell>
  );
}

function AppIcon({ name, url }: { name: string; url?: string | null }) {
  const [broken, setBroken] = useState(false);
  if (url && !broken) {
    return (
      <img
        className="app-ic app-ic-img"
        src={url}
        alt=""
        loading="lazy"
        onError={() => setBroken(true)}
      />
    );
  }
  const ch = (name.trim()[0] ?? '?').toUpperCase();
  return <span className="app-ic" aria-hidden="true">{ch}</span>;
}

function LibrarySource({ onDeploy }: { onDeploy: (app: Application) => void }) {
  const toast = useToast();
  const [versionsOf, setVersionsOf] = useState<Application | null>(null);
  const [apps, setApps] = useState<Application[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState('');
  // Package -> download progress (0..1, or -1 = unknown size) while the server fetches its APK.
  const [fetching, setFetching] = useState<Record<string, number>>({});
  const [bulk, setBulk] = useState<string | null>(null);

  const load = () => listApplications()
    .then((list) => setApps(list.filter((a) => (a.type ?? 'app') !== 'web')))
    .catch(() => (setApps([]), setError('No se pudo cargar la Biblioteca de apps.')));
  useEffect(() => { void load(); }, []);

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!apps) return [];
    if (!needle) return apps;
    return apps.filter((a) => `${a.name} ${a.pkg}`.toLowerCase().includes(needle));
  }, [apps, q]);
  const missing = (apps ?? []).filter((a) => !hasApk(a));

  /** @return true when it worked (errors are shown as a toast). */
  const obtain = async (a: Application, quiet = false): Promise<boolean> => {
    setFetching((m) => ({ ...m, [a.pkg]: -1 }));
    try {
      const b = await obtainApk(a, (f) => setFetching((m) => ({ ...m, [a.pkg]: f == null ? -1 : f })));
      if (!quiet) {
        toast.push('ok', `${a.name} ${b.version ?? ''} lista para instalar`,
          `${b.publisher ? `Firmada por ${b.publisher}. ` : 'Firma no reconocida: revisa el certificado en la biblioteca. '}Las políticas que la tienen la instalan solas en sus teléfonos.`);
      }
      return true;
    } catch (e) {
      toast.push('err', `${a.name}: no se pudo obtener el APK`, e instanceof Error ? e.message : '');
      return false;
    } finally {
      setFetching((m) => { const n = { ...m }; delete n[a.pkg]; return n; });
    }
  };

  const obtainAll = async () => {
    let ok = 0;
    for (let i = 0; i < missing.length; i++) {
      setBulk(`Descargando ${i + 1} de ${missing.length}: ${missing[i].name}`);
      if (await obtain(missing[i], true)) ok++;
    }
    setBulk(null);
    toast.push('ok', 'Descargas terminadas', `${ok} de ${missing.length} apps quedaron listas; las políticas que las tienen las instalan solas.`);
    void load();
  };

  return (
    <>
      {versionsOf && <VersionsDialog app={{ id: versionsOf.id!, name: versionsOf.name, pkg: versionsOf.pkg }} onClose={() => setVersionsOf(null)} />}
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' }}>
        <div className="dv-search" style={{ width: 260 }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="7" />
            <path d="M21 21l-4-4" />
          </svg>
          <input type="search" placeholder="Buscar apps" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        {missing.length > 0 && (
          <button className="btn btn-sm" disabled={!!bulk} onClick={() => void obtainAll()} data-testid="lib-obtain-all"
                  title="El servidor descarga el APK público de cada app que hoy solo es una ficha de la Play Store">
            Obtener APK de las {missing.length} que no lo tienen
          </button>
        )}
        {bulk && <span className="muted small"><span className="spin" /> {bulk}</span>}
      </div>
      {missing.length > 0 && (
        <p className="note" style={{ marginTop: 0 }}>
          Las apps marcadas «Sin APK» son fichas de la Play Store: el MDM no puede instalarlas solo. «Obtener APK» hace que el
          servidor descargue el APK público de la app (la versión más reciente), compruebe que es ese paquete y quién lo firma, y lo
          guarde aquí; desde ese momento cada política que la tenga la instala sola en sus teléfonos, sin cuenta de Google.
        </p>
      )}

      {error && <div className="banner banner-alert">{error}</div>}

      {apps === null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Cargando apps…</div></div>
      ) : shown.length === 0 ? (
        <div className="panel">
          <div className="empty">
            <span className="label">Sin apps</span>
            {apps.length === 0 ? 'Aún no hay apps en la Biblioteca.' : 'Ninguna app coincide con tu búsqueda.'}
          </div>
        </div>
      ) : (
        <div className="app-grid">
          {shown.map((a) => {
            const busy = a.pkg in fetching;
            const f = fetching[a.pkg];
            return (
              <div className="app-card" key={a.id}>
                <div className="app-top">
                  <AppIcon name={a.name} />
                  <div className="app-meta">
                    <div className="app-nm">{a.name}</div>
                    <div className="app-pkg mono">{a.pkg}</div>
                  </div>
                </div>
                <div className="app-foot">
                  {hasApk(a)
                    ? <span className="app-ver">{a.version ? `v${a.version}` : '—'}</span>
                    : <span className="chip tone-warn" title="Solo es una ficha de la Play Store: el MDM no la puede instalar todavía">Sin APK</span>}
                  {!hasApk(a) ? (
                    <button className="btn btn-sm btn-primary" disabled={busy || !!bulk} onClick={() => void obtain(a).then(load)} data-testid={`obtain-${a.pkg}`}>
                      {busy ? (f >= 0 ? `Descargando ${Math.round(f * 100)} %` : 'Descargando…') : 'Obtener APK'}
                    </button>
                  ) : (
                    <>
                      <button className="btn btn-sm btn-ghost" onClick={() => setVersionsOf(a)} data-testid={`versions-${a.pkg}`}>Versiones</button>
                      <button className="btn btn-sm btn-primary" onClick={() => onDeploy(a)}>
                        Desplegar
                      </button>
                    </>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}

// Split-APK bundle containers the /bundle endpoint unpacks into installable parts.
const BUNDLE_EXTS = ['.xapk', '.apks', '.apkm', '.zip'];
function isBundleName(n: string): boolean {
  const lower = n.toLowerCase();
  return BUNDLE_EXTS.some((e) => lower.endsWith(e));
}

function CustomSource({ onDeploy }: { onDeploy: (s: DeploySubject) => void }) {
  const toast = useToast();
  const [query, setQuery] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [pkg, setPkg] = useState('');
  const [vc, setVc] = useState('');
  const [sha, setSha] = useState('');
  const [bundle, setBundle] = useState<BundleUploadResult | null>(null);
  const [savedAppId, setSavedAppId] = useState<number | undefined>(undefined);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [dropped, setDropped] = useState<string | null>(null);
  /** Optional name for the uploaded version (to tell builds apart and see which one each policy uses). */
  const [verLabel, setVerLabel] = useState('');
  async function nameVersion(appId: number, version: string | undefined, code: number) {
    if (!verLabel.trim()) return;
    const v = (await listAppVersions(appId).catch(() => [])).find((x) => (x.versionCode ?? 0) === code && (x.version ?? '').trim() === (version ?? '').trim());
    if (v) { await setVersionLabel(v.id, verLabel.trim()).catch(() => undefined); setVerLabel(''); }
  }
  const fileRef = useRef<HTMLInputElement>(null);

  async function onFile(file: File) {
    setSavedAppId(undefined); // fresh file → drop any prior Library id
    if (isBundleName(file.name)) {
      await onBundle(file);
      return;
    }
    if (!file.name.toLowerCase().endsWith('.apk')) {
      toast.push('err', 'No es un APK', 'Suelta un archivo .apk, .xapk, .apks, .apkm o .zip.');
      return;
    }
    setBundle(null);
    setUploading(true);
    setDropped(file.name);
    try {
      const up = await uploadApk(file);
      const fd = up.fileDetails;
      if (fd) {
        if (fd.name) setName(fd.name);
        if (fd.pkg) setPkg(fd.pkg);
        if (fd.versionCode) setVc(String(fd.versionCode));
      }
      // An app already in the Library is updated through versions (never a second Library entry): a higher
      // versionCode, or the same one under a new version name, becomes its new version and the server moves the
      // configurations using it there (their phones then update).
      const existing = fd?.pkg
        ? (await listApplications(fd.pkg).catch(() => [] as Application[])).find((a) => a.pkg === fd.pkg)
        : undefined;
      let committedUrl: string | undefined;
      try {
        committedUrl = (await commitUpload(up.serverPath, uniqueApkName(fd?.pkg, fd?.version, file.name))).url || undefined;
      } catch {
        committedUrl = undefined;
      }
      if (committedUrl) setUrl(committedUrl);
      if (existing?.id && fd?.pkg) {
        setSavedAppId(existing.id);
        const versions = await getVersions(existing.id).catch(() => [] as ApplicationVersion[]);
        const current = versions.reduce((m, v) => Math.max(m, v.versionCode ?? 0), 0);
        const vc = fd.versionCode ?? 0;
        const fresh = isNewVersion(versions, vc, fd.version);
        if (fresh && committedUrl) {
          await addApplicationVersion({ applicationId: existing.id, version: fd.version, versionCode: vc, url: committedUrl });
          await nameVersion(existing.id, fd.version, vc);
          toast.push('ok', 'Nueva versión agregada',
            `${fd.name || fd.pkg} ${fd.version ?? ''} (versionCode ${vc}): las políticas que la usan ahora instalan esta versión.`);
        } else if (fresh) {
          toast.push('err', 'No se pudo alojar el archivo', 'El servidor no guardó el APK: inténtalo de nuevo.');
        } else if (vc === current || versions.some((v) => (v.versionCode ?? 0) === vc && (v.version ?? '').trim() === (fd.version ?? '').trim())) {
          const named = verLabel.trim();
          await nameVersion(existing.id, fd.version, vc);
          toast.push(named ? 'ok' : 'err', 'Esa versión ya está en la Biblioteca',
            `${fd.pkg} ${fd.version ?? ''} (versionCode ${vc}) ya está cargada${named ? `; ahora se llama «${named}»` : ''}. Para que una política la use, elígela en la política (selector de versión) o súbela desde la política.`);
        } else {
          toast.push('err', 'Más antigua que la de la Biblioteca',
            `El versionCode ${vc} es menor que ${current}. Los teléfonos nunca bajan de versión; desinstálala primero si de verdad la necesitas.`);
        }
      } else if (committedUrl && fd?.pkg) {
        // New app: add it to the Library so it shows up everywhere (incl. the configuration and kiosk pickers).
        try {
          const saved = await saveAndroidApplication({
            name: fd.name || fd.pkg,
            pkg: fd.pkg,
            url: committedUrl,
            version: fd.version,
            versionCode: fd.versionCode,
            type: 'app', // applications.type is NOT NULL — send it explicitly so the save can't fail
          });
          setSavedAppId(saved.id); // enables the deploy dialog's "Add to a configuration" tab
          if (saved.id) await nameVersion(saved.id, fd.version, fd.versionCode ?? 0);
          toast.push('ok', 'APK listo', 'Alojado y agregado a tu Biblioteca: revísalo y despliégalo.');
        } catch {
          toast.push('ok', 'APK listo', 'Alojado: revísalo y despliégalo. (No se pudo agregar a la Biblioteca).');
        }
      } else if (committedUrl) {
        toast.push('ok', 'APK listo', 'Datos completados: revísalos y despliégalo.');
      } else {
        toast.push('ok', 'Datos extraídos', 'No se pudo alojar el archivo: pega una URL para desplegarlo.');
      }
    } catch (e) {
      toast.push('err', 'Falló la subida', e instanceof Error ? e.message : '');
      setDropped(null);
    } finally {
      setUploading(false);
    }
  }

  // Split bundles (.xapk/.apks/.apkm/.zip): the server unpacks + hosts every part.
  // There's no single URL, so we hold the parts and deploy them together.
  async function onBundle(file: File) {
    setUploading(true);
    setDropped(file.name);
    try {
      const b = await uploadBundle(file);
      setBundle(b);
      setUrl('');
      setSha('');
      if (b.name) setName(b.name);
      if (b.packageName) setPkg(b.packageName);
      if (b.versionCode) setVc(String(b.versionCode));
      // Register in the Library so it shows in the config picker + is assignable to a configuration.
      // A single-part bundle (a universal.apk .apks) is an ordinary single-URL app; a multi-part bundle
      // stores its parts as a JSON string on the version.
      try {
        const version = b.version || String(b.versionCode); // applicationVersions.version is NOT NULL
        const saved = await saveAndroidApplication(
          b.parts.length === 1
            ? { name: b.name || b.packageName, pkg: b.packageName, url: b.parts[0].url, version, versionCode: b.versionCode, type: 'app' }
            : {
                name: b.name || b.packageName,
                pkg: b.packageName,
                version,
                versionCode: b.versionCode,
                type: 'app',
                parts: JSON.stringify(b.parts.map((p) => ({ url: p.url, sha256: p.sha256, name: p.name }))),
              },
        );
        setSavedAppId(saved.id);
      } catch {
        // Non-fatal: still deployable via push-now; may already be in the Library.
      }
      toast.push(
        'ok',
        'Bundle listo',
        `${b.parts.length} parte${b.parts.length === 1 ? '' : 's'} alojada${b.parts.length === 1 ? '' : 's'}: agregado a tu Biblioteca.`,
      );
    } catch (e) {
      // The server returns a clear message for encrypted .apkm / no-apks bundles.
      toast.push('err', 'Falló la subida del bundle', e instanceof Error ? e.message : '');
      setDropped(null);
    } finally {
      setUploading(false);
    }
  }

  function submit() {
    if (bundle) {
      onDeploy({
        label: name.trim() || bundle.name || 'Bundle dividido',
        packageName: (pkg.trim() || bundle.packageName),
        // No single URL for a bundle; parts carry the hosted splits.
        url: '',
        versionCode: vc ? Number(vc) : bundle.versionCode,
        parts: bundle.parts.map((p) => ({ url: p.url, sha256: p.sha256 })),
      });
      return;
    }
    if (!url.trim() || !pkg.trim()) {
      toast.push('err', 'Faltan campos', 'La URL del APK y el nombre del paquete son obligatorios.');
      return;
    }
    onDeploy({
      label: name.trim() || 'APK propio',
      packageName: pkg.trim(),
      url: url.trim(),
      versionCode: vc ? Number(vc) : undefined,
      sha256: sha.trim() || undefined,
      applicationId: savedAppId, // present once the APK is in the Library → enables "Add to a configuration"
    });
  }

  // Explicit "Add to Library" — the drop-time save is automatic but silent; this gives a visible action
  // (with real success/error feedback) and a retry, and captures the app id so it becomes config-assignable.
  async function saveToLibrary() {
    // A multi-part bundle has no single URL — its parts stand in for one.
    const isMultiPart = !!bundle && bundle.parts.length > 1;
    if (!pkg.trim() || (!url.trim() && !isMultiPart)) {
      toast.push('err', 'Faltan campos', 'Para agregarla a tu Biblioteca se necesitan el nombre del paquete y la URL del APK (o un bundle).');
      return;
    }
    try {
      const saved = await saveAndroidApplication({
        name: name.trim() || pkg.trim(),
        pkg: pkg.trim(),
        url: isMultiPart ? undefined : url.trim(),
        versionCode: vc ? Number(vc) : undefined,
        type: 'app',
        parts: isMultiPart
          ? JSON.stringify(bundle!.parts.map((p) => ({ url: p.url, sha256: p.sha256, name: p.name })))
          : undefined,
      });
      setSavedAppId(saved.id);
      toast.push('ok', 'Agregada a la Biblioteca', `${name.trim() || pkg.trim()} está en tu Biblioteca: ya se puede asignar a una política.`);
    } catch (e) {
      const existing = (await listApplications(pkg.trim()).catch(() => [])).find((a) => a.pkg === pkg.trim());
      if (existing?.id) {
        setSavedAppId(existing.id);
        toast.push('ok', 'Ya está en la Biblioteca', 'Esta app ya está en tu Biblioteca: puedes asignarla a una política.');
      } else {
        toast.push('err', 'No se pudo agregar a la Biblioteca', e instanceof Error ? e.message : 'El servidor rechazó el guardado.');
      }
    }
  }

  return (
    <div className="panel" style={{ maxWidth: 640 }}>
      <div className="panel-head">
        <h2 className="panel-title">Desplegar un APK propio</h2>
        <div className="ext-search">
          <input
            className="input"
            style={{ width: 150, padding: '6px 10px' }}
            placeholder="buscar app…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {EXT_SOURCES.map((s) => (
            <button
              key={s.label}
              type="button"
              className="btn btn-sm"
              onClick={() => window.open(s.url + encodeURIComponent(query), '_blank', 'noopener')}
            >
              {s.label} ↗
            </button>
          ))}
        </div>
      </div>
      <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 14 }}>
        <input className="input" style={{ marginBottom: 8, maxWidth: 360 }} placeholder="Nombre de esta versión (opcional), p. ej. Disay sept."
          value={verLabel} maxLength={80} onChange={(e) => setVerLabel(e.target.value)} aria-label="Nombre de esta versión" />
        <div
          className={`dropzone ${dragging ? 'over' : ''} ${uploading ? 'busy' : ''}`}
          onClick={() => !uploading && fileRef.current?.click()}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            const f = e.dataTransfer.files?.[0];
            if (f) void onFile(f);
          }}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => e.key === 'Enter' && fileRef.current?.click()}
        >
          <input
            ref={fileRef}
            type="file"
            accept=".apk,.xapk,.apks,.apkm,.zip,application/vnd.android.package-archive"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) void onFile(f);
              e.target.value = '';
            }}
          />
          {uploading ? (
            <span className="dz-main"><span className="spin" /> Analizando {dropped}…</span>
          ) : dropped ? (
            <span className="dz-main">
              ✓ {dropped}
              <span className="dz-sub">
                {bundle
                  ? `Bundle dividido · ${bundle.parts.length} parte${bundle.parts.length === 1 ? '' : 's'} · suelta otro para reemplazarlo`
                  : 'Suelta otro para reemplazarlo'}
              </span>
            </span>
          ) : (
            <span className="dz-main">
              Suelta aquí un APK o un bundle dividido, o haz clic para buscarlo
              <span className="dz-sub">
                APK, o .xapk / .apks / .apkm / .zip: completa automáticamente el paquete, la versión y las URL alojadas
              </span>
            </span>
          )}
        </div>
        <p className="note" style={{ margin: 0 }}>
          Indica al agente cualquier APK accesible, o suelta un archivo para subirlo y alojarlo aquí.
          Los bundles divididos (<span className="mono">.xapk</span> / <span className="mono">.apks</span> /{' '}
          <span className="mono">.apkm</span> / <span className="mono">.zip</span>) se descomprimen y
          se instalan en una sola sesión. ¿Necesitas una app de APKMirror o APKPure? Búscala arriba, descárgala
          y suéltala aquí: son fuentes no oficiales, bajo tu propio riesgo. La instalación silenciosa requiere
          Device Owner (la capacidad <span className="mono">silentInstall</span>).
        </p>
        {bundle ? (
          <label className="field">
            <span className="label">Bundle dividido</span>
            <input
              className="input mono"
              value={`${bundle.parts.length} parte${bundle.parts.length === 1 ? '' : 's'}: ${bundle.parts.map((p) => p.name).join(', ')}`}
              readOnly
            />
          </label>
        ) : (
          <label className="field">
            <span className="label">URL del APK *</span>
            <input className="input" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…/app.apk" />
          </label>
        )}
        <label className="field">
          <span className="label">Nombre del paquete *</span>
          <input className="input mono" value={pkg} onChange={(e) => setPkg(e.target.value)} placeholder="com.example.app" />
        </label>
        <div style={{ display: 'flex', gap: 12 }}>
          <label className="field" style={{ flex: 1 }}>
            <span className="label">Código de versión</span>
            <input className="input" type="number" value={vc} onChange={(e) => setVc(e.target.value)} placeholder="opcional" />
          </label>
          <label className="field" style={{ flex: 1 }}>
            <span className="label">Nombre visible</span>
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="opcional" />
          </label>
        </div>
        {!bundle && (
          <label className="field">
            <span className="label">SHA-256 (base64)</span>
            <input className="input mono" value={sha} onChange={(e) => setSha(e.target.value)} placeholder="opcional: verificación de integridad" />
          </label>
        )}
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <button
            className="btn"
            onClick={() => void saveToLibrary()}
            disabled={!pkg.trim() || (!url.trim() && !(bundle && bundle.parts.length > 1))}
          >
            {savedAppId ? '✓ En la Biblioteca' : 'Agregar a la Biblioteca'}
          </button>
          <button className="btn btn-primary" onClick={submit}>
            Desplegar…
          </button>
          {savedAppId && <span className="note" style={{ margin: 0 }}>Guardada: se puede asignar a una política.</span>}
        </div>
      </div>
    </div>
  );
}

function FDroidSource({ onDeploy }: { onDeploy: (s: DeploySubject) => void }) {
  const [q, setQ] = useState('');
  const [apps, setApps] = useState<FDroidApp[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    const t = setTimeout(
      () => {
        searchFdroid(q, 60)
          .then((r) => !cancelled && setApps(r))
          .catch(() => {
            if (cancelled) return;
            setApps([]);
            setError('No se pudo conectar con el catálogo de F-Droid.');
          });
      },
      q ? 350 : 0,
    );
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [q]);

  function deploy(a: FDroidApp) {
    onDeploy({
      label: a.name,
      packageName: a.packageName,
      url: a.apkUrl,
      versionCode: a.versionCode,
      // F-Droid publishes a HEX sha256; the agent's expected format isn't confirmed
      // (the server stores base64), so omit it for now — HTTPS covers transit integrity.
      sha256: undefined,
    });
  }

  return (
    <>
      <div className="dv-search" style={{ width: 320, marginBottom: 16 }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4-4" />
        </svg>
        <input type="search" placeholder="Buscar en F-Droid (p. ej., firefox, keepass)" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {apps === null ? (
        <div className="panel"><div className="empty"><span className="spin" /> Buscando en F-Droid…</div></div>
      ) : apps.length === 0 ? (
        <div className="panel">
          <div className="empty">
            <span className="label">Sin resultados</span>
            {error ? 'El servidor no pudo cargar el catálogo.' : 'Ninguna app coincide con tu búsqueda.'}
          </div>
        </div>
      ) : (
        <div className="app-grid">
          {apps.map((a) => (
            <div className="app-card" key={a.packageName}>
              <div className="app-top">
                <AppIcon name={a.name} url={a.iconUrl} />
                <div className="app-meta">
                  <div className="app-nm">{a.name}</div>
                  <div className="app-pkg mono">{a.packageName}</div>
                </div>
              </div>
              {a.summary && <div className="app-sum">{a.summary}</div>}
              <div className="app-foot">
                <span className="app-ver">{a.versionName ? `v${a.versionName}` : `v${a.versionCode}`}</span>
                <button className="btn btn-sm btn-primary" onClick={() => deploy(a)}>
                  Desplegar
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
      <p className="note" style={{ marginTop: 14 }}>
        El dispositivo descarga las apps directamente de f-droid.org, así que debe poder acceder a ese sitio.
      </p>
    </>
  );
}

const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const COMMON_DEVICE_APPS: { name: string; pkg: string }[] = [
  { name: 'Chrome', pkg: 'com.android.chrome' },
  { name: 'WhatsApp', pkg: 'com.whatsapp' },
  { name: 'WhatsApp Business', pkg: 'com.whatsapp.w4b' },
  { name: 'Google Maps', pkg: 'com.google.android.apps.maps' },
  { name: 'Waze', pkg: 'com.waze' },
  { name: 'Gmail', pkg: 'com.google.android.gm' },
];

/**
 * Register an app that is already on the phones (preinstalled, or installed by the user from the Play Store): no APK,
 * nothing is installed — it only becomes pickable in configurations (allowed in kiosk / by the app policy).
 * For the phone's dialer, contacts, browser… prefer the configuration's "functions", which work on every brand.
 */
function DeviceAppSource() {
  const toast = useToast();
  const [name, setName] = useState('');
  const [pkg, setPkg] = useState('');
  const [busy, setBusy] = useState(false);
  const [existing, setExisting] = useState<Application[]>([]);
  useEffect(() => { listApplications().then(setExisting).catch(() => undefined); }, []);
  const known = new Set(existing.map((a) => a.pkg));

  async function add(n: string, p: string) {
    const nm = n.trim() || p.trim();
    const pk = p.trim();
    if (!PACKAGE_RE.test(pk)) {
      toast.push('err', 'Nombre de paquete no válido', 'Usa el paquete de la app, p. ej., com.android.chrome.');
      return;
    }
    if (known.has(pk)) {
      toast.push('ok', 'Ya está en tu Biblioteca', pk);
      return;
    }
    setBusy(true);
    try {
      await saveAndroidApplication({ name: nm, pkg: pk, type: 'app' });
      toast.push('ok', 'Agregada a la Biblioteca', `${nm}: permítela en una política (Apps permitidas).`);
      setName(''); setPkg('');
      setExisting(await listApplications());
    } catch (e) {
      toast.push('err', 'No se pudo agregar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" data-testid="device-app-source">
      <h2 className="panel-title">App que ya está en el teléfono</h2>
      <p className="note">
        Para apps que los teléfonos ya tienen: Chrome, o WhatsApp instalado desde la Play Store. No se sube ningún APK ni se
        instala nada: la app queda disponible para tus políticas (quiosco y política de apps). Para el marcador, los contactos o el
        navegador del teléfono, usa mejor las <b>funciones</b> de una política: sirven en todas las marcas.
      </p>
      <div className="dcp-roles" style={{ margin: '8px 0 16px' }}>
        {COMMON_DEVICE_APPS.map((a) => (
          <button key={a.pkg} className="btn btn-sm" disabled={busy || known.has(a.pkg)} onClick={() => void add(a.name, a.pkg)}>
            {known.has(a.pkg) ? '✓ ' : '+ '}{a.name}
          </button>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 10, alignItems: 'end' }}>
        <label className="field">
          <span className="label">Nombre</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Chrome" />
        </label>
        <label className="field">
          <span className="label">Nombre del paquete *</span>
          <input className="input mono" value={pkg} onChange={(e) => setPkg(e.target.value)} placeholder="com.android.chrome" />
        </label>
        <button className="btn btn-primary" disabled={busy || !pkg.trim()} onClick={() => void add(name, pkg)}>Agregar a la Biblioteca</button>
      </div>
    </section>
  );
}

/**
 * Play Store apps: looked up by package or link (name + icon), added to the Library without an APK. Installing from the
 * Play Store without anyone touching the phone needs Google's managed Play (Android Enterprise), which DallyControl does
 * not use — so deploying one opens its Play Store page on the phones, and a configuration allows it.
 */
function PlayStoreSource() {
  const toast = useToast();
  const [q, setQ] = useState('');
  const [found, setFound] = useState<PlayApp | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function search() {
    setBusy(true); setErr(null); setFound(null);
    try {
      setFound(await lookupPlayApp(q.trim()));
    } catch (e) {
      const m = e instanceof Error ? e.message : '';
      setErr(/invalid/.test(m) ? 'Escribe el paquete (com.whatsapp) o pega el enlace de la Play Store.'
        : /not\.found/.test(m) ? 'Esa app no existe en la Play Store.' : 'No se pudo consultar la Play Store. Intenta de nuevo.');
    } finally {
      setBusy(false);
    }
  }

  async function add(app: PlayApp) {
    setBusy(true);
    try {
      let lib = (await listApplications(app.packageName)).find((a) => a.pkg === app.packageName);
      if (!lib) {
        await saveAndroidApplication({ name: app.name, pkg: app.packageName, type: 'app', icon: app.icon ?? undefined });
        lib = (await listApplications(app.packageName)).find((a) => a.pkg === app.packageName);
      }
      setFound(null); setQ('');
      // Straight away, the server fetches its APK so the MDM can install it by itself (no Google account needed).
      if (lib && !hasApk(lib)) {
        toast.push('ok', `${app.name}: descargando el APK…`, 'Puede tardar un par de minutos; queda en la Biblioteca lista para instalar.');
        try {
          const b = await obtainApk(lib);
          toast.push('ok', `${app.name} ${b.version ?? ''} lista para instalar`,
            `${b.publisher ? `Firmada por ${b.publisher}. ` : ''}Agrégala a una política (o despliégala) y se instala sola.`);
        } catch (e) {
          toast.push('err', `${app.name} quedó en la Biblioteca sin APK`, `${e instanceof Error ? e.message : ''} Al desplegarla se abrirá su página de la Play Store.`);
        }
      } else {
        toast.push('ok', 'Ya está en la Biblioteca', app.name);
      }
    } catch (e) {
      toast.push('err', 'No se pudo agregar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" data-testid="play-store-source">
      <h2 className="panel-title">Play Store</h2>
      <p className="note">
        Busca por paquete o pega el enlace de la Play Store. Al <b>desplegarla</b>, en los teléfonos se abre su página de la
        Play Store y la persona toca <b>Instalar</b>: instalarla sin tocar el teléfono exige la Play administrada de Google, que
        este sistema no usa. Si la agregas a una política, queda permitida en el quiosco y en la política de apps.
      </p>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', margin: '10px 0' }}>
        <input className="input mono" style={{ flex: 1 }} value={q} placeholder="com.whatsapp  o  https://play.google.com/store/apps/details?id=…"
          aria-label="Paquete o enlace de la Play Store" onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && q.trim()) void search(); }} />
        <button className="btn btn-primary" disabled={busy || !q.trim()} onClick={() => void search()}>Buscar</button>
      </div>
      {err && <div className="banner banner-alert">{err}</div>}
      {found && (
        <div className="play-found" data-testid="play-found">
          {found.icon ? <img src={found.icon} alt="" width={48} height={48} style={{ borderRadius: 10 }} /> : null}
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 600 }}>{found.name}</div>
            <div className="mono muted">{found.packageName}</div>
          </div>
          <button className="btn btn-primary" disabled={busy} onClick={() => void add(found)}>Agregar a la Biblioteca</button>
        </div>
      )}
    </section>
  );
}
