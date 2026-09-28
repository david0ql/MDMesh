import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import {
  createGroup, deleteGroup, listGroups, setGlobalConfiguration, updateGroup,
  type FleetGroup, type GroupsOverview, type Target,
} from '../api/fleet';
import { BulkActionModal } from '../components/BulkActionModal';

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

type Dialog =
  | { kind: 'create' }
  | { kind: 'rename'; group: FleetGroup }
  | { kind: 'delete'; group: FleetGroup }
  | null;

/**
 * Groups (companies such as DISAY or AMOVIL) and the three levels of change:
 *  - global: the default configuration and actions on every device;
 *  - group: its configuration (else global) and actions on its devices;
 *  - device: from the device page or the device list (a device's own configuration wins over both).
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

  const total = data ? data.groups.reduce((n, g) => n + g.deviceCount, 0) + data.ungroupedDevices : 0;
  const globalName = data?.global.configurationName ?? '—';

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
      const r = await updateGroup(g.id, g.name, cfg);
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

  function openCreate() {
    setName('');
    setNewConfig('');
    setDialog({ kind: 'create' });
  }

  async function submitDialog() {
    if (!dialog) return;
    let ok = false;
    if (dialog.kind === 'create') {
      ok = await guarded('Group created', async () => {
        const g = await createGroup(name.trim(), newConfig ? Number(newConfig) : null);
        return g.name;
      });
    } else if (dialog.kind === 'rename') {
      const g = dialog.group;
      ok = await guarded('Group renamed', async () => {
        await updateGroup(g.id, name.trim(), g.configurationId);
        return name.trim();
      });
    } else {
      const g = dialog.group;
      ok = await guarded('Group deleted', async () => {
        await deleteGroup(g.id);
        return g.deviceCount ? `${plural(g.deviceCount, 'device')} left without a group.` : g.name;
      });
    }
    if (ok) setDialog(null);
  }

  const nameValid = name.trim().length > 0 && name.trim().length <= 100;

  return (
    <AppShell title="Groups">
      <div className="dv-head">
        <h1>Groups</h1>
        <span className="dv-count">
          {data ? `${plural(data.groups.length, 'group')} · ${plural(total, 'device')}` : 'Loading…'}
        </span>
        <div className="dv-spacer" />
        <button className="btn btn-dark" onClick={openCreate}>New group</button>
      </div>

      {err && <div className="banner banner-alert">{err}</div>}

      <section className="panel gr-global">
        <div className="gr-global-head">
          <div>
            <h2 className="panel-title">Global</h2>
            <p className="muted gr-note">
              Applies to every device. A group&rsquo;s configuration overrides it, and a device&rsquo;s own configuration
              overrides both.
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
            <span className="label">No groups yet</span>
            Create one per company (for example DISAY or AMOVIL) and move its devices into it.
          </div>
        )}
        {data && data.groups.length > 0 && (
          <table className="gr-table">
            <thead>
              <tr><th>Group</th><th>Devices</th><th>Configuration</th><th aria-label="Actions" /></tr>
            </thead>
            <tbody>
              {data.groups.map((g) => (
                <tr key={g.id}>
                  <td data-label="Group">
                    <Link to={`/devices?group=${g.id}`} className="gr-name">{g.name}</Link>
                  </td>
                  <td data-label="Devices">
                    <Link to={`/devices?group=${g.id}`}>{plural(g.deviceCount, 'device')}</Link>
                  </td>
                  <td data-label="Configuration">
                    <select className="sel" value={g.configurationId ?? ''} disabled={busy}
                            onChange={(e) => changeGroupConfig(g, e.target.value)}
                            aria-label={`Configuration of ${g.name}`}>
                      <option value="">Global ({globalName})</option>
                      {configs.map((c) => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
                    </select>
                  </td>
                  <td>
                    <div className="gr-actions">
                    <button className="btn btn-sm" disabled={g.deviceCount === 0}
                            onClick={() => setAction({ kind: 'group', id: g.id, name: g.name, count: g.deviceCount })}>
                      Run action
                    </button>
                    <button className="btn btn-sm btn-ghost" onClick={() => { setName(g.name); setDialog({ kind: 'rename', group: g }); }}>
                      Rename
                    </button>
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
                    : 'The group has no devices.'}
                </p>
              </>
            ) : (
              <>
                <h3>{dialog.kind === 'create' ? 'New group' : `Rename ${dialog.group.name}`}</h3>
                <label className="field">
                  <span>Name</span>
                  <input autoFocus value={name} maxLength={100} placeholder="e.g. AMOVIL"
                         onChange={(e) => setName(e.target.value)}
                         onKeyDown={(e) => { if (e.key === 'Enter' && nameValid) void submitDialog(); }} />
                </label>
                {dialog.kind === 'create' && (
                  <label className="field">
                    <span>Configuration</span>
                    <select className="sel" value={newConfig} onChange={(e) => setNewConfig(e.target.value)}>
                      <option value="">Global ({globalName})</option>
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
