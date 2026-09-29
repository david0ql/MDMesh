import { useEffect, useMemo, useState } from 'react';
import { searchDevices, type DeviceView } from '../api/devices';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import {
  getAppConfigLinks,
  updateAppConfigLinks,
  type AppConfigLink,
} from '../api/applications';
import { installApp, buildInstallCommand, queueCommand } from '../api/commands';
import { groupTree, listGroups, queueForTarget, type FleetGroup } from '../api/fleet';
import { statusMeta } from '../ui/status';
import { fmtRelative, orDash } from '../ui/format';
import { useToast } from '../ui/toast';

/** An app resolved to everything needed to deploy it. */
export interface DeploySubject {
  label: string;
  packageName: string;
  url: string;
  versionCode?: number;
  sha256?: string;
  /** Present only for library apps — required for assign-to-configuration. */
  applicationId?: number;
  /** Split-APK bundle parts — when set, push-to-device installs these together.
   *  (Config-assign stays single-URL and ignores this.) */
  parts?: { url: string; sha256?: string }[];
}

type Tab = 'device' | 'folder' | 'config';

export function DeployModal({
  subject,
  onClose,
}: {
  subject: DeploySubject;
  onClose: () => void;
}) {
  const toast = useToast();
  const canAssign = subject.applicationId != null;
  const [tab, setTab] = useState<Tab>('device');
  const [devices, setDevices] = useState<DeviceView[]>([]);
  // Folders: install now on every device of the chosen folders and their sub-folders.
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [folders, setFolders] = useState<Set<number>>(new Set());
  useEffect(() => { listGroups().then((o) => setGroups(o.groups)).catch(() => undefined); }, []);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const installable = !!subject.url || !!subject.parts?.length;
  const [configs, setConfigs] = useState<ConfigurationSummary[]>([]);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [config, setConfig] = useState('');
  const [runAfter, setRunAfter] = useState(false);
  const [filter, setFilter] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    searchDevices({ pageSize: 1000 })
      .then((r) => !cancelled && setDevices(r.devices?.items ?? []))
      .catch(() => undefined);
    listConfigurations()
      .then((l) => !cancelled && setConfigs(l))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return devices;
    return devices.filter((d) =>
      `${d.number ?? ''} ${d.description ?? ''}`.toLowerCase().includes(q),
    );
  }, [devices, filter]);

  function toggle(num: string) {
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(num)) next.delete(num);
      else next.add(num);
      return next;
    });
  }

  async function pushNow() {
    if (picked.size === 0) return;
    setBusy(true);
    let ok = 0;
    let fail = 0;
    for (const num of picked) {
      try {
        if (!installable) { await queueCommand(num, openStoreCmd); ok++; continue; }
        await installApp(num, {
          url: subject.url,
          packageName: subject.packageName,
          versionCode: subject.versionCode,
          sha256: subject.sha256,
          runAfterInstall: runAfter,
          parts: subject.parts,
        });
        ok++;
      } catch {
        fail++;
      }
    }
    setBusy(false);
    toast.push(
      fail ? 'err' : 'ok',
      `Deploy ${subject.label}`,
      `${ok} device${ok === 1 ? '' : 's'} queued${fail ? `, ${fail} failed` : ''}.`,
    );
    onClose();
  }

  async function pushToFolders() {
    if (folders.size === 0) return;
    // A folder already covers its sub-folders: send only the top-most chosen ones, so no device gets it twice.
    const top = [...folders].filter((id) => !tree.some((n) => n.group.id !== id && folders.has(n.group.id) && n.subtree.has(id)));
    const cmd = installable ? buildInstallCommand({
      url: subject.url, packageName: subject.packageName, versionCode: subject.versionCode,
      sha256: subject.sha256, runAfterInstall: runAfter, parts: subject.parts,
    }) : openStoreCmd;
    setBusy(true);
    let queued = 0;
    try {
      for (const id of top) {
        const n = tree.find((x) => x.group.id === id);
        queued += (await queueForTarget({ kind: 'group', id, name: n?.path ?? String(id), count: n?.totalDevices ?? 0 }, cmd)).queued;
      }
      toast.push('ok', `Despliegue de ${subject.label}`, `${queued} dispositivo${queued === 1 ? '' : 's'} en ${top.length} carpeta${top.length === 1 ? '' : 's'}.`);
      onClose();
    } catch (e) {
      toast.push('err', 'No se pudo desplegar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function assignConfig() {
    if (!config || subject.applicationId == null) return;
    setBusy(true);
    try {
      const cid = Number(config);
      const links = await getAppConfigLinks(subject.applicationId);
      let found = false;
      const updated: AppConfigLink[] = links.map((l) => {
        if (l.configurationId !== cid) return l;
        found = true;
        return { ...l, action: 1, remove: false, notify: true };
      });
      if (!found) {
        updated.push({
          configurationId: cid,
          applicationId: subject.applicationId,
          action: 1,
          showIcon: true, // configurationApplications.showIcon is NOT NULL
          remove: false,
          notify: true,
        });
      }
      await updateAppConfigLinks({
        applicationId: subject.applicationId,
        configurations: updated,
      });
      const name = configs.find((c) => c.id === cid)?.name ?? 'configuration';
      toast.push('ok', `Added ${subject.label}`, `Assigned to ${name}.`);
      onClose();
    } catch (e) {
      toast.push('err', 'Assign failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  const canSubmit = tab === 'device' ? picked.size > 0 : tab === 'folder' ? folders.size > 0 : !!config;
  // No APK here: the phones open the app's Play Store page instead of installing it.
  const openStoreCmd = { type: 'device.openStore', requiresCapability: 'device.openStore', payload: JSON.stringify({ packageName: subject.packageName }) };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal deploy-modal" onClick={(e) => e.stopPropagation()}>
        <h3>Deploy {subject.label}</h3>
        <p className="muted" style={{ marginTop: 2 }}>
          <span className="mono">{subject.packageName}</span>
          {subject.versionCode != null ? ` · v${subject.versionCode}` : ''}
        </p>

        <div className="tabs" style={{ margin: '14px 0 14px' }}>
          <button className={tab === 'device' ? 'on' : ''} onClick={() => setTab('device')}>
            Push to device(s)
          </button>
          <button className={tab === 'folder' ? 'on' : ''} onClick={() => setTab('folder')}>
            Carpetas
          </button>
          <button
            className={tab === 'config' ? 'on' : ''}
            onClick={() => canAssign && setTab('config')}
            disabled={!canAssign}
            title={canAssign ? undefined : 'Only library apps can be assigned to a configuration'}
          >
            Add to a configuration
          </button>
        </div>

        {!installable && tab !== 'config' && (
          <div className="banner banner-warn">
            Esta app no tiene APK en el servidor (viene de la Play Store): en los teléfonos elegidos se abre su página de la
            Play Store para que la persona toque <b>Instalar</b>. Agrégala también a una configuración para permitirla en el
            quiosco y en la política de apps.
          </div>
        )}
        {tab === 'folder' ? (
          <>
            <p className="note" style={{ marginTop: 0 }}>
              Se instala ahora en todos los dispositivos de las carpetas elegidas, <b>incluidas sus subcarpetas</b>. Para que
              quede siempre (también en los que entren después), agrégala a la configuración de esas carpetas.
            </p>
            <div className="deploy-devlist" data-testid="deploy-folders">
              {tree.length === 0 ? <div className="empty" style={{ padding: 20 }}>No hay carpetas.</div> : tree.map((n) => (
                <label key={n.group.id} className="deploy-devrow" style={{ paddingLeft: 12 + n.depth * 18 }}>
                  <input type="checkbox" checked={folders.has(n.group.id)}
                    onChange={() => setFolders((prev) => { const x = new Set(prev); if (x.has(n.group.id)) x.delete(n.group.id); else x.add(n.group.id); return x; })} />
                  <span className="dd-nm">{n.group.name}</span>
                  <span className="dd-seen">{n.totalDevices} disp.</span>
                </label>
              ))}
            </div>
          </>
        ) : tab === 'device' ? (
          <>
            <div className="dv-search" style={{ width: '100%', marginBottom: 10 }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="11" cy="11" r="7" />
                <path d="M21 21l-4-4" />
              </svg>
              <input
                type="search"
                placeholder="Filter devices"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
              />
            </div>
            <div className="deploy-devlist">
              {shown.length === 0 ? (
                <div className="empty" style={{ padding: 20 }}>
                  No devices match.
                </div>
              ) : (
                shown.map((d) => {
                  const m = statusMeta(d.statusCode);
                  const checked = picked.has(d.number);
                  return (
                    <label key={d.id} className="deploy-devrow">
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggle(d.number)}
                      />
                      <span className={`dot dot-${m.tone}`} />
                      <span className="dd-nm">{orDash(d.number)}</span>
                      <span className="dd-sub">{d.description}</span>
                      <span className="dd-seen">{fmtRelative(d.lastUpdate)}</span>
                    </label>
                  );
                })
              )}
            </div>
          </>
        ) : (
          <div className="field" style={{ margin: '4px 0 8px' }}>
            <span>Configuration</span>
            <select
              className="sel"
              value={config}
              onChange={(e) => setConfig(e.target.value)}
              style={{ width: '100%' }}
            >
              <option value="">Select a configuration…</option>
              {configs.map((c) => (
                <option key={c.id} value={String(c.id)}>
                  {c.name}
                </option>
              ))}
            </select>
            <p className="note" style={{ marginTop: 8 }}>
              Every device on this configuration installs the app on its next sync.
            </p>
          </div>
        )}

        <label className="deploy-runafter">
          <input
            type="checkbox"
            checked={runAfter}
            onChange={(e) => setRunAfter(e.target.checked)}
          />
          Launch the app after install
        </label>

        <div className="modal-actions">
          <button className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            className="btn btn-primary"
            disabled={busy || !canSubmit}
            onClick={() => void (tab === 'device' ? pushNow() : tab === 'folder' ? pushToFolders() : assignConfig())}
          >
            {busy ? <span key="b">Deploying…</span> : tab === 'device' ? <span key="d">{`Deploy to ${picked.size || ''}`.trim()}</span>
              : tab === 'folder' ? <span key="f">Desplegar en carpetas</span> : <span key="a">Assign</span>}
          </button>
        </div>
      </div>
    </div>
  );
}
