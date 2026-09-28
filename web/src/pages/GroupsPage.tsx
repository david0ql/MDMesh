import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import {
  createGroup, deleteGroup, groupTree, listGroups, setGlobalConfiguration, updateGroup,
  type FleetGroup, type GroupNode, type GroupsOverview, type Target,
} from '../api/fleet';
import { BulkActionModal } from '../components/BulkActionModal';

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

type Dialog =
  | { kind: 'create'; parentId: number | null }
  | { kind: 'edit'; group: FleetGroup }
  | { kind: 'delete'; group: FleetGroup }
  | null;

/**
 * Folders (groups) and the three levels of change, like MobiControl's device tree:
 *  - global: the default configuration and actions on every device;
 *  - folder: its configuration — or, without one, its parent's (nearest ancestor), else global — and actions on its
 *    devices and its sub-folders' (e.g. Colombia → Preventa → Agencia Norte → Samsung A15);
 *  - device: from the device page or the device list (a device's own configuration wins over everything).
 */
export function GroupsPage() {
  const toast = useToast();
  const [data, setData] = useState<GroupsOverview | null>(null);
  const [configs, setConfigs] = useState<ConfigurationSummary[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [name, setName] = useState('');
  const [newConfig, setNewConfig] = useState('');
  const [parent, setParent] = useState('');
  const [action, setAction] = useState<Target | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await listGroups());
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load groups');
    }
  }, []);

  useEffect(() => {
    void load();
    listConfigurations()
      .then((l) => setConfigs([...l].sort((a, b) => a.name.localeCompare(b.name))))
      .catch(() => undefined);
  }, [load]);

  const tree = useMemo(() => groupTree(data?.groups ?? []), [data]);
  const nodeOf = useMemo(() => new Map(tree.map((n) => [n.group.id, n])), [tree]);
  const total = data ? data.groups.reduce((n, g) => n + g.deviceCount, 0) + data.ungroupedDevices : 0;
  const globalName = data?.global.configurationName ?? '—';

  /** What a folder without its own configuration runs: its parent's effective one, else global. */
  const inheritedName = (g: FleetGroup | null | undefined): string => {
    const p = g?.parentId != null ? nodeOf.get(g.parentId)?.group : undefined;
    return p?.effectiveConfigurationName ?? globalName;
  };

  async function guarded(what: string, fn: () => Promise<string | void>) {
    setBusy(true);
    try {
      const msg = await fn();
      toast.push('ok', what, msg || '');
      await load();
      return true;
    } catch (e) {
      toast.push('err', `${what} failed`, e instanceof Error ? e.message : '');
      return false;
    } finally {
      setBusy(false);
    }
  }

  const reconfigured = (n: number) => (n ? `${plural(n, 'device')} reconfigured.` : 'No device needed a change.');

  function changeGroupConfig(g: FleetGroup, value: string) {
    const cfg = value === '' ? null : Number(value);
    void guarded(`Configuration of ${g.name}`, async () => {
      const r = await updateGroup(g.id, g.name, cfg, g.parentId);
      return reconfigured(r.devicesReconfigured);
    });
  }

  function changeGlobal(value: string) {
    if (!value) return;
    void guarded('Global configuration', async () => {
      const r = await setGlobalConfiguration(Number(value));
      return reconfigured(r.devicesReconfigured);
    });
  }

  function openCreate(parentId: number | null) {
    setName('');
    setNewConfig('');
    setParent(parentId == null ? '' : String(parentId));
    setDialog({ kind: 'create', parentId });
  }

  function openEdit(g: FleetGroup) {
    setName(g.name);
    setParent(g.parentId == null ? '' : String(g.parentId));
    setDialog({ kind: 'edit', group: g });
  }

  async function submitDialog() {
    if (!dialog) return;
    const parentId = parent === '' ? null : Number(parent);
    let ok = false;
    if (dialog.kind === 'create') {
      ok = await guarded('Folder created', async () => {
        const g = await createGroup(name.trim(), newConfig ? Number(newConfig) : null, parentId);
        return g.name;
      });
    } else if (dialog.kind === 'edit') {
      const g = dialog.group;
      ok = await guarded('Folder saved', async () => {
        const r = await updateGroup(g.id, name.trim(), g.configurationId, parentId);
        return `${name.trim()}${parentId !== g.parentId ? ` — ${reconfigured(r.devicesReconfigured)}` : ''}`;
      });
    } else {
      const g = dialog.group;
      ok = await guarded('Folder deleted', async () => {
        await deleteGroup(g.id);
        return g.deviceCount ? `${plural(g.deviceCount, 'device')} left without a group.` : g.name;
      });
    }
    if (ok) setDialog(null);
  }

  const nameValid = name.trim().length > 0 && name.trim().length <= 100;
  // A folder cannot move under itself or under one of its own sub-folders.
  const parentChoices: GroupNode[] =
    dialog?.kind === 'edit' ? tree.filter((n) => !nodeOf.get(dialog.group.id)?.subtree.has(n.group.id)) : tree;

  return (
    <AppShell title="Groups">
      <div className="dv-head">
        <h1>Groups</h1>
        <span className="dv-count">
          {data ? `${plural(data.groups.length, 'folder')} · ${plural(total, 'device')}` : 'Loading…'}
        </span>
        <div className="dv-spacer" />
        <button className="btn btn-dark" onClick={() => openCreate(null)}>New folder</button>
      </div>

      {err && <div className="banner banner-alert">{err}</div>}

      <section className="panel gr-global">
        <div className="gr-global-head">
          <div>
            <h2 className="panel-title">Global</h2>
            <p className="muted gr-note">
              Applies to every device. A folder&rsquo;s configuration overrides it (and reaches its sub-folders that have
              none), and a device&rsquo;s own configuration overrides both.
            </p>
          </div>
          <button className="btn" disabled={!data || total === 0}
                  onClick={() => setAction({ kind: 'all', count: total })}>
            Run action on all devices
          </button>
        </div>
        <label className="field gr-field">
          <span>Default configuration</span>
          <select className="sel" value={data?.global.configurationId ?? ''} disabled={busy || !data}
                  onChange={(e) => changeGlobal(e.target.value)}>
            {data?.global.configurationId == null && <option value="">Select…</option>}
            {configs.map((c) => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
          </select>
        </label>
      </section>

      <section className="panel gr-list">
        {data && data.groups.length === 0 && (
          <div className="empty">
            <span className="label">No folders yet</span>
            Create one per country, app, agency or phone model (for example Colombia → Preventa → Agencia Norte) and move
            devices into them.
          </div>
        )}
        {data && data.groups.length > 0 && (
          <table className="gr-table">
            <thead>
              <tr><th>Folder</th><th>Devices</th><th>Configuration</th><th aria-label="Actions" /></tr>
            </thead>
            <tbody>
              {tree.map(({ group: g, depth, totalDevices, path }) => (
                <tr key={g.id} data-testid={`group-row-${g.id}`}>
                  <td data-label="Folder">
                    <span className="gr-tree" style={{ paddingLeft: depth * 20 }}>
                      {depth > 0 && <span className="gr-branch" aria-hidden="true">└</span>}
                      <Link to={`/devices?group=${g.id}`} className="gr-name" title={path}>{g.name}</Link>
                    </span>
                  </td>
                  <td data-label="Devices">
                    <Link to={`/devices?group=${g.id}`}>{plural(g.deviceCount, 'device')}</Link>
                    {totalDevices > g.deviceCount && (
                      <span className="muted"> · {totalDevices} with sub-folders</span>
                    )}
                  </td>
                  <td data-label="Configuration">
                    <select className="sel" value={g.configurationId ?? ''} disabled={busy}
                            onChange={(e) => changeGroupConfig(g, e.target.value)}
                            aria-label={`Configuration of ${g.name}`}>
                      <option value="">
                        {g.parentId != null ? `Inherit (${inheritedName(g)})` : `Global (${globalName})`}
                      </option>
                      {configs.map((c) => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
                    </select>
                  </td>
                  <td>
                    <div className="gr-actions">
                    <button className="btn btn-sm" disabled={totalDevices === 0}
                            onClick={() => setAction({ kind: 'group', id: g.id, name: path, count: totalDevices })}>
                      Run action
                    </button>
                    <button className="btn btn-sm btn-ghost" onClick={() => openCreate(g.id)}>+ Sub-folder</button>
                    <button className="btn btn-sm btn-ghost" onClick={() => openEdit(g)}>Edit</button>
                    <button className="btn btn-sm btn-ghost gr-del" onClick={() => setDialog({ kind: 'delete', group: g })}>
                      Delete
                    </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && data.ungroupedDevices > 0 && (
          <p className="muted gr-ungrouped">
            <Link to="/devices?group=none">{plural(data.ungroupedDevices, 'device')} without a group</Link> — they use the
            global configuration unless they have their own.
          </p>
        )}
      </section>

      {action && (
        <BulkActionModal target={action} onClose={() => setAction(null)} onDone={() => undefined} />
      )}

      {dialog && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setDialog(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            {dialog.kind === 'delete' ? (
              <>
                <h3>Delete {dialog.group.name}</h3>
                <p className="muted">
                  {dialog.group.deviceCount
                    ? `Its ${plural(dialog.group.deviceCount, 'device')} stay enrolled, without a group, and fall back to the global configuration unless they have their own.`
                    : 'The folder has no devices of its own.'}
                  {tree.some((n) => n.group.parentId === dialog.group.id)
                    ? ' Its sub-folders move up one level.'
                    : ''}
                </p>
              </>
            ) : (
              <>
                <h3>{dialog.kind === 'create' ? 'New folder' : `Edit ${dialog.group.name}`}</h3>
                <label className="field">
                  <span>Name</span>
                  <input autoFocus value={name} maxLength={100} placeholder="e.g. Colombia, Preventa, Agencia Norte"
                         onChange={(e) => setName(e.target.value)}
                         onKeyDown={(e) => { if (e.key === 'Enter' && nameValid) void submitDialog(); }} />
                </label>
                <label className="field">
                  <span>Inside</span>
                  <select className="sel" value={parent} onChange={(e) => setParent(e.target.value)} aria-label="Parent folder">
                    <option value="">— Top level —</option>
                    {parentChoices.map((n) => <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>)}
                  </select>
                </label>
                {dialog.kind === 'create' && (
                  <label className="field">
                    <span>Configuration</span>
                    <select className="sel" value={newConfig} onChange={(e) => setNewConfig(e.target.value)}>
                      <option value="">
                        {parent ? `Inherit (${nodeOf.get(Number(parent))?.group.effectiveConfigurationName ?? globalName})` : `Global (${globalName})`}
                      </option>
                      {configs.map((c) => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
                    </select>
                  </label>
                )}
              </>
            )}
            <div className="modal-actions">
              <button className="btn" onClick={() => setDialog(null)} disabled={busy}>Cancel</button>
              <button className={`btn ${dialog.kind === 'delete' ? 'btn-danger' : 'btn-primary'}`}
                      disabled={busy || (dialog.kind !== 'delete' && !nameValid)}
                      onClick={() => void submitDialog()}>
                {dialog.kind === 'delete' ? 'Delete' : dialog.kind === 'create' ? 'Create' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  );
}
