import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import {
  getDeviceScope, listGroups, moveDevicesToGroup, setDevicesConfiguration,
  type DeviceScope, type FleetGroup,
} from '../api/fleet';
import { useToast } from '../ui/toast';

const SOURCE_LABEL: Record<DeviceScope['source'], string> = {
  device: 'Device',
  group: 'Group',
  global: 'Global',
};

/**
 * Left-rail card: the device's group (company) and its configuration, with where that configuration comes from.
 * A configuration chosen here is the device's own and wins over the group's and the global one; "Inherit" hands
 * the device back to its group (or the global configuration).
 */
export function DeviceScopeCard({ deviceId, onChanged }: { deviceId: number; onChanged?: () => void }) {
  const toast = useToast();
  const [scope, setScope] = useState<DeviceScope | null>(null);
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [configs, setConfigs] = useState<ConfigurationSummary[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [s, g] = await Promise.all([getDeviceScope(deviceId), listGroups()]);
    setScope(s);
    setGroups(g.groups);
  }, [deviceId]);

  useEffect(() => {
    void load().catch(() => undefined);
    listConfigurations()
      .then((l) => setConfigs([...l].sort((a, b) => a.name.localeCompare(b.name))))
      .catch(() => undefined);
  }, [load]);

  async function change(what: string, fn: () => Promise<unknown>) {
    setBusy(true);
    try {
      await fn();
      await load();
      onChanged?.();
      toast.push('ok', what, '');
    } catch (e) {
      toast.push('err', `${what} failed`, e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (!scope) return null;

  const inheritName = scope.groupConfigurationName
    ? `${scope.groupName}: ${scope.groupConfigurationName}`
    : `Global: ${scope.globalConfigurationName ?? '—'}`;

  return (
    <div className="scope-card">
      <div className="grp">Group &amp; configuration</div>
      <div className="row">
        <span className="k">Group</span>
        <select className="sel v" value={scope.groupId ?? ''} disabled={busy} aria-label="Group"
                onChange={(e) => {
                  const gid = e.target.value === '' ? null : Number(e.target.value);
                  void change('Group changed', () => moveDevicesToGroup([deviceId], gid));
                }}>
          <option value="">No group</option>
          {groups.map((g) => <option key={g.id} value={String(g.id)}>{g.name}</option>)}
        </select>
      </div>
      <div className="row">
        <span className="k">Configuration</span>
        <span className="v">
          {scope.configurationName ?? '—'}{' '}
          <span className={`chip scope-${scope.source}`} title="Where this configuration comes from">
            {SOURCE_LABEL[scope.source]}
          </span>
        </span>
      </div>
      <div className="row">
        <span className="k">Set on device</span>
        <select className="sel v" value={scope.pinned ? String(scope.configurationId) : ''} disabled={busy}
                aria-label="Device configuration"
                onChange={(e) => {
                  const cfg = e.target.value === '' ? null : Number(e.target.value);
                  void change(cfg == null ? 'Configuration inherited' : 'Configuration set on device',
                    () => setDevicesConfiguration([deviceId], cfg));
                }}>
          <option value="">Inherit ({inheritName})</option>
          {configs.map((c) => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
        </select>
      </div>
      {scope.groupId != null && (
        <p className="muted scope-note">
          <Link to={`/devices?group=${scope.groupId}`}>Other devices in {scope.groupName}</Link>
        </p>
      )}
    </div>
  );
}
