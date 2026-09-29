import { useEffect, useMemo, useRef, useState } from 'react';
import { scanApps, fetchIcons, getLatestScan, type AppInfo } from '../api/deviceApps';
import { listApplications, appCategory, type Application } from '../api/applications';
import { queueCommand, reapplyConfiguration } from '../api/commands';
import { getConfigStatus } from '../api/configSync';
import { getConfigurations, type Configuration } from '../api/configurations';
import { useToast } from '../ui/toast';

type Device = { number: string };
type Mode = 'launcher' | 'single';
type Source = 'library' | 'device';

/** System bars shown in kiosk (lock-task features). Recents needs Home. */
export type KioskFeatures = { home?: boolean; recents?: boolean; notifications?: boolean; systemInfo?: boolean; quickSettings?: boolean };

export type KioskChoice = {
  mode: Mode;                 // 'launcher' | 'single'
  packages: string[];         // pinned/allowed package names
  exitMode: 'gesture' | 'visible' | 'remote';
  password?: string;
  features?: KioskFeatures;
  /** Colours / icon size, taken from the device's configuration so a custom kiosk looks the same. */
  theme?: { backgroundColor?: string; textColor?: string; iconSize?: string };
};

const DEFAULT_FEATURES: KioskFeatures = { home: true, recents: false, notifications: true, systemInfo: true, quickSettings: true };

/** Build the `kiosk.enter` payload (device-independent). Mirrors the single-device apply() exactly. */
export function buildKioskPayload(c: KioskChoice): object {
  const f = { ...DEFAULT_FEATURES, ...(c.features ?? {}) };
  const { quickSettings, ...bars } = f;
  const features = { ...bars, recents: bars.home ? bars.recents : false };
  const base = { exitMode: c.exitMode, password: c.password || undefined, features, theme: c.theme ?? {}, quickSettings: !!quickSettings };
  return c.mode === 'single'
    ? { ...base, mode: 'single', pinPackage: c.packages[0], allowedPackages: c.packages }
    : { ...base, mode: 'launcher', allowedPackages: c.packages };
}

/** The last custom kiosk sent to a device, so the next one starts from it. */
const lastKey = (n: string) => `dc.kiosk.last.${n}`;
type LastChoice = { mode: Mode; packages: string[]; exitMode: KioskChoice['exitMode']; features: KioskFeatures };
function loadLast(n: string): LastChoice | null {
  try { const v = localStorage.getItem(lastKey(n)); return v ? (JSON.parse(v) as LastChoice) : null; } catch { return null; }
}
function saveLast(n: string, c: LastChoice) {
  try { localStorage.setItem(lastKey(n), JSON.stringify(c)); } catch { /* storage full or blocked */ }
}

/** A row in either source, normalised so the list renders the same way. */
interface PickItem {
  pkg: string;
  label: string;
  iconUrl?: string;
  system: boolean;
  badge?: string;
}

const CATEGORY_LABELS: Record<string, string> = { system: 'sistema', uploaded: 'subida', web: 'web' };

const iconKey = (pkg: string, v?: number) => `mdm.icon.${pkg}@${v ?? 0}`;
function cachedIcon(pkg: string, v?: number): string | undefined {
  try { return localStorage.getItem(iconKey(pkg, v)) ?? undefined; } catch { return undefined; }
}
/** Remove every localStorage key with the given prefix, except `keep`. */
function evictIcons(prefix: string, keep?: string) {
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const k = localStorage.key(i);
    if (k && k.startsWith(prefix) && k !== keep) localStorage.removeItem(k);
  }
}
function cacheIcon(pkg: string, v: number | undefined, dataUrl: string) {
  const key = iconKey(pkg, v);
  try {
    // Drop stale versions of this package's icon so the cache doesn't grow without bound.
    evictIcons(`mdm.icon.${pkg}@`, key);
    localStorage.setItem(key, dataUrl);
  } catch {
    // Quota — evict every cached icon once and retry before giving up silently.
    try {
      evictIcons('mdm.icon.');
      localStorage.setItem(key, dataUrl);
    } catch { /* still over quota — ignore */ }
  }
}

export function KioskEnterModal({
  device, onClose, onQueued,
}: { device: Device; onClose: () => void; onQueued: () => void }) {
  const toast = useToast();
  const last = useMemo(() => loadLast(device.number), [device.number]);
  const [source, setSource] = useState<Source>('library');
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [mode, setMode] = useState<Mode>(last?.mode ?? 'launcher');
  const [selected, setSelected] = useState<Set<string>>(new Set(last?.packages ?? []));
  const [exitMode, setExitMode] = useState<'gesture' | 'visible' | 'remote'>(last?.exitMode ?? 'gesture');
  const [features, setFeatures] = useState<KioskFeatures>(last?.features ?? DEFAULT_FEATURES);
  // The device's configuration: "use its kiosk" is the default when it has one.
  const [config, setConfig] = useState<Configuration | null | undefined>(undefined);
  const [kind, setKind] = useState<'config' | 'custom'>('config');
  useEffect(() => {
    getConfigStatus(device.number)
      .then(async (st) => {
        const id = st?.configurationId;
        const c = id == null ? null : (await getConfigurations()).find((x) => x.id === id) ?? null;
        setConfig(c);
        if (!c?.kioskMode) setKind('custom');
      })
      .catch(() => { setConfig(null); setKind('custom'); });
  }, [device.number]);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  // Library (default source) — the app catalog / uploaded apps.
  const [lib, setLib] = useState<Application[] | null>(null);
  const [libErr, setLibErr] = useState<string | null>(null);

  // Device scan (optional source).
  const [apps, setApps] = useState<AppInfo[] | null>(null);
  const [scannedAt, setScannedAt] = useState<number | null>(null);
  const [scanning, setScanning] = useState(false);
  const [loadedSaved, setLoadedSaved] = useState(false);
  const [scanErr, setScanErr] = useState<string | null>(null);
  const [deviceIcons, setDeviceIcons] = useState<Record<string, string>>({});
  const [loadingIcons, setLoadingIcons] = useState(false);

  // Cancels in-flight scan / icon polls when the modal closes (it unmounts on close).
  const abortRef = useRef<AbortController | null>(null);
  useEffect(() => {
    const ac = new AbortController();
    abortRef.current = ac;
    return () => ac.abort();
  }, []);

  useEffect(() => {
    listApplications()
      .then(setLib)
      .catch((e) => setLibErr(e instanceof Error ? e.message : 'No se pudo cargar la biblioteca'));
  }, []);

  // First time the Device tab is opened, load the last saved scan (from command history) so apps
  // appear instantly without re-scanning. A scan only happens when the user asks for one.
  useEffect(() => {
    if (source !== 'device' || loadedSaved) return;
    setLoadedSaved(true);
    getLatestScan(device.number).then((snap) => {
      if (snap) { primeFrom(snap.apps); setApps(snap.apps); setScannedAt(snap.scannedAt ?? null); }
    }).catch(() => undefined);
  }, [source, loadedSaved, device.number]);

  function primeFrom(list: AppInfo[]) {
    const primed: Record<string, string> = {};
    for (const a of list) { const c = cachedIcon(a.pkg, a.versionCode); if (c) primed[a.pkg] = c; }
    setDeviceIcons((prev) => ({ ...prev, ...primed }));
  }

  async function scan() {
    setScanning(true);
    setScanErr(null);
    try {
      const list = await scanApps(device.number, abortRef.current?.signal);
      setApps(list);
      setScannedAt(Date.now());
      primeFrom(list);
      if (!list.length) setScanErr('El dispositivo no reportó apps. Dale un momento después de una actualización y vuelve a intentar.');
    } catch (e) {
      if (abortRef.current?.signal.aborted) return; // modal closed — nothing to report
      setScanErr(e instanceof Error ? e.message : 'Falló el escaneo');
    } finally {
      setScanning(false);
    }
  }

  const items: PickItem[] = useMemo(() => {
    const q = query.trim().toLowerCase();
    const match = (label: string, pkg: string) => !q || label.toLowerCase().includes(q) || pkg.toLowerCase().includes(q);
    if (source === 'library') {
      // The library is a curated catalogue (~dozens) — show all of it, search-filtered, with the
      // uploaded/web apps first. (Most catalogue entries carry the "system" flag, so hiding them by
      // default would leave the list nearly empty.)
      return (lib ?? [])
        .filter((a) => a.pkg)
        .map((a) => ({ pkg: a.pkg, label: a.name || a.pkg, iconUrl: a.icon || undefined, system: appCategory(a) === 'system', badge: CATEGORY_LABELS[appCategory(a)] }))
        .filter((i) => match(i.label, i.pkg))
        .sort((a, b) => Number(a.system) - Number(b.system) || a.label.localeCompare(b.label));
    }
    // Device scan: only launchable apps (kiosk can't pin a non-launchable package). New scans
    // already exclude them; this also filters any older saved scan that still carries them.
    // The toggle now reveals system apps; non-launchable are never shown.
    return (apps ?? [])
      .filter((a) => a.launchable !== false)
      .map((a) => ({ pkg: a.pkg, label: a.label, iconUrl: deviceIcons[a.pkg], system: !!a.system, badge: a.system ? 'sistema' : undefined }))
      .filter((i) => (showAll || !i.system) && match(i.label, i.pkg));
  }, [source, lib, apps, deviceIcons, query, showAll]);

  async function loadDeviceIcons() {
    if (source !== 'device' || !apps) return;
    const byPkg = new Map(apps.map((a) => [a.pkg, a]));
    const need = items.filter((i) => !deviceIcons[i.pkg]).map((i) => i.pkg);
    if (!need.length) return;
    setLoadingIcons(true);
    try {
      await fetchIcons(device.number, need, (batch) => {
        setDeviceIcons((prev) => ({ ...prev, ...batch }));
        for (const [pkg, url] of Object.entries(batch)) cacheIcon(pkg, byPkg.get(pkg)?.versionCode, url);
      }, abortRef.current?.signal);
    } catch (e) {
      if (abortRef.current?.signal.aborted) return; // modal closed — nothing to report
      toast.push('err', 'No se pudieron cargar los íconos', e instanceof Error ? e.message : '');
    } finally {
      setLoadingIcons(false);
    }
  }

  function toggle(pkg: string) {
    setSelected((prev) => {
      if (mode === 'single') return new Set(prev.has(pkg) ? [] : [pkg]);
      const next = new Set(prev);
      if (next.has(pkg)) next.delete(pkg); else next.add(pkg);
      return next;
    });
  }

  function switchMode(m: Mode) {
    setMode(m);
    if (m === 'single' && selected.size > 1) setSelected(new Set());
  }

  const canApply = selected.size > 0 && (mode === 'launcher' || selected.size === 1);

  async function apply() {
    setBusy(true);
    if (kind === 'config') {
      try {
        await reapplyConfiguration(device.number);
        toast.push('ok', 'Quiosco de la política enviado', String(config?.name ?? ''));
        onQueued();
        onClose();
      } catch (e) {
        toast.push('err', 'No se pudo enviar', e instanceof Error ? e.message : '');
      } finally {
        setBusy(false);
      }
      return;
    }
    const pkgs = [...selected];
    const theme = config ? {
      backgroundColor: (config.backgroundColor as string) || undefined,
      textColor: (config.textColor as string) || undefined,
      iconSize: (config.iconSize as string) || undefined,
    } : undefined;
    const payload = buildKioskPayload({ mode, packages: pkgs, exitMode, password, features, theme });
    try {
      await queueCommand(device.number, { type: 'kiosk.enter', payload: JSON.stringify(payload) });
      saveLast(device.number, { mode, packages: pkgs, exitMode, features });
      toast.push('ok', 'Entrada al quiosco en cola', `${pkgs.length} app${pkgs.length === 1 ? '' : 's'}`);
      onQueued();
      onClose();
    } catch (e) {
      toast.push('err', 'No se pudo entrar en quiosco', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal kiosk-modal">
        <h3>Entrar en quiosco</h3>

        <div className="kiosk-mode" role="radiogroup" aria-label="Tipo de quiosco">
          <label>
            <input type="radio" checked={kind === 'config'} disabled={!config?.kioskMode} onChange={() => setKind('config')} />{' '}
            Usar el quiosco de la política{config?.name ? ` (${String(config.name)})` : ''}
          </label>
          <label>
            <input type="radio" checked={kind === 'custom'} onChange={() => setKind('custom')} /> Personalizado
          </label>
        </div>
        {kind === 'config' ? (
          <p className="muted" data-testid="kiosk-use-config">
            {config === undefined
              ? 'Leyendo la política del dispositivo…'
              : <>El dispositivo vuelve al quiosco de <b>{String(config?.name ?? '')}</b> tal como está configurado: sus apps,
                funciones, colores, botones y barra. Para cambiarlo, edita la política.</>}
          </p>
        ) : (
        <>
        <p className="muted">Elige las apps a las que se limita el dispositivo — de tu biblioteca o escaneando el dispositivo.
          {last ? ' Se parte de lo último que enviaste a este dispositivo.' : ''}</p>

        <div className="kiosk-source">
          <button className={`seg-btn ${source === 'library' ? 'on' : ''}`} onClick={() => setSource('library')}>Biblioteca</button>
          <button className={`seg-btn ${source === 'device' ? 'on' : ''}`} onClick={() => setSource('device')}>Apps del dispositivo</button>
        </div>

        <div className="kiosk-mode">
          <label><input type="radio" checked={mode === 'launcher'} onChange={() => switchMode('launcher')} /> Apps permitidas (cuadrícula de inicio)</label>
          <label><input type="radio" checked={mode === 'single'} onChange={() => switchMode('single')} /> Fijar una sola app</label>
        </div>

        <div className="kiosk-toolbar">
          <input className="kiosk-search" placeholder="Buscar apps…" value={query} onChange={(e) => setQuery(e.target.value)} />
          {source === 'device' && (
            <label className="kiosk-toggle">
              <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} /> Mostrar todas (incl. sistema)
            </label>
          )}
          {source === 'device' && apps && (
            <>
              <button className="btn" disabled={loadingIcons} onClick={() => { void loadDeviceIcons(); }}>
                {loadingIcons ? <span key="loading">Cargando íconos…</span> : <span key="idle">Cargar íconos</span>}
              </button>
              <button className="btn" disabled={scanning} onClick={() => { void scan(); }}>
                {scanning ? <span key="scanning">Escaneando…</span> : <span key="idle">Volver a escanear</span>}
              </button>
            </>
          )}
        </div>

        {source === 'device' && apps && (
          <p className="kiosk-saved muted">
            {scannedAt ? `Escaneo guardado del ${new Date(scannedAt).toLocaleString()} · ${apps.length} apps` : `${apps.length} apps`}. Vuelve a escanear para actualizar.
          </p>
        )}

        {source === 'device' && !apps && (
          <div className="kiosk-scan">
            <button className="btn btn-primary" disabled={scanning} onClick={() => { void scan(); }}>
              {scanning ? <span key="scanning">Escaneando dispositivo…</span> : <span key="idle">Escanear apps del dispositivo</span>}
            </button>
            {scanning && <p className="muted">Pidiendo al dispositivo sus apps instaladas…</p>}
            {scanErr && <p className="err-text">{scanErr}</p>}
          </div>
        )}

        {(source === 'library' || apps) && (
          <div className="kiosk-applist">
            {libErr && source === 'library' && <p className="err-text" style={{ padding: 10 }}>{libErr}</p>}
            {source === 'library' && !lib && !libErr && <p className="muted" style={{ padding: 10 }}>Cargando biblioteca…</p>}
            {items.map((i) => {
              const on = selected.has(i.pkg);
              return (
                <button key={i.pkg} type="button" className={`kiosk-app ${on ? 'on' : ''}`} onClick={() => toggle(i.pkg)} title={i.pkg}>
                  {i.iconUrl
                    ? <img className="kiosk-ico" src={i.iconUrl} alt="" onError={(e) => { (e.target as HTMLImageElement).style.visibility = 'hidden'; }} />
                    : <span className="kiosk-ico ph">{i.label.slice(0, 1).toUpperCase()}</span>}
                  <span className="kiosk-app-meta">
                    <span className="kiosk-app-label">{i.label}</span>
                    <span className="kiosk-app-pkg">{i.pkg}</span>
                  </span>
                  <span className="kiosk-badges">
                    {i.badge && <span className={`kiosk-badge ${i.system ? 'sys' : ''}`}>{i.badge}</span>}
                    {on && <span className="kiosk-badge on">✓</span>}
                  </span>
                </button>
              );
            })}
            {!items.length && (source === 'library' ? lib : apps) && <p className="muted" style={{ padding: 10 }}>Ninguna app coincide.</p>}
          </div>
        )}

        <div className="kiosk-features" role="group" aria-label="Botones y barra en el quiosco">
          <span className="muted">Mostrar en el quiosco:</span>
          <label><input type="checkbox" checked={!!features.home} onChange={(e) => setFeatures((f) => ({ ...f, home: e.target.checked, recents: e.target.checked ? f.recents : false }))} /> Botón Inicio</label>
          <label><input type="checkbox" checked={!!features.recents} disabled={!features.home} onChange={(e) => setFeatures((f) => ({ ...f, recents: e.target.checked }))} /> Botón Recientes</label>
          <label><input type="checkbox" checked={!!features.notifications} onChange={(e) => setFeatures((f) => ({ ...f, notifications: e.target.checked }))} /> Notificaciones (barra desplegable)</label>
          <label><input type="checkbox" checked={!!features.systemInfo} onChange={(e) => setFeatures((f) => ({ ...f, systemInfo: e.target.checked }))} /> Hora, batería y señal</label>
          <label><input type="checkbox" checked={!!features.quickSettings} onChange={(e) => setFeatures((f) => ({ ...f, quickSettings: e.target.checked }))} /> Ajustes rápidos (Wi‑Fi, brillo, volumen, Bluetooth)</label>
        </div>

        <div className="kiosk-exit-row">
          <label className="field">
            <span>Modo de salida</span>
            <select value={exitMode} onChange={(e) => setExitMode(e.target.value as typeof exitMode)}>
              <option value="gesture">Gesto (7 toques en la esquina)</option>
              <option value="visible">Botón visible</option>
              <option value="remote">Solo remoto</option>
            </select>
          </label>
          <label className="field">
            <span>Contraseña de salida</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="opcional" />
          </label>
        </div>

        </>
        )}

        <div className="modal-actions">
          <button className="btn" disabled={busy} onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" disabled={busy || (kind === 'custom' && !canApply) || (kind === 'config' && !config?.kioskMode)}
                  onClick={() => { void apply(); }}>
            {busy ? <span key="b">Enviando…</span> : kind === 'config' ? <span key="c">Entrar en quiosco</span> : <span key="p">{`Entrar en quiosco (${selected.size})`}</span>}
          </button>
        </div>
      </div>
    </div>
  );
}
