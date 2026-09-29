import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import {
  getDeviceScope, listGroups, moveDevicesToGroup, setDevicesConfiguration,
  type DeviceScope, type FleetGroup, groupTree,
} from '../api/fleet';
import { useToast } from '../ui/toast';

const SOURCE_LABEL: Record<DeviceScope['source'], string> = {
  device: 'Dispositivo',
  group: 'Carpeta',
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
      toast.push('err', `${what}: falló`, e instanceof Error ? e.message : '');
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
      <div className="grp">Carpeta y configuración</div>
      <div className="row">
        <span className="k">Carpeta</span>
        <select className="sel v" value={scope.groupId ?? ''} disabled={busy} aria-label="Carpeta"
                onChange={(e) => {
                  const gid = e.target.value === '' ? null : Number(e.target.value);
                  void change('Carpeta cambiada', () => moveDevicesToGroup([deviceId], gid));
                }}>
          <option value="">Sin carpeta</option>
          {groupTree(groups).map((n) => <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>)}
        </select>
      </div>
      <div className="row">
        <span className="k">Configuración</span>
        <span className="v">
          {scope.configurationName ?? '—'}{' '}
          <span className={`chip scope-${scope.source}`} title="De dónde viene esta configuración">
            {SOURCE_LABEL[scope.source]}
          </span>
        </span>
      </div>
      <div className="row">
        <span className="k">Fijar en el dispositivo</span>
        <select className="sel v" value={scope.pinned ? String(scope.configurationId) : ''} disabled={busy}
                aria-label="Configuración del dispositivo"
                onChange={(e) => {
                  const cfg = e.target.value === '' ? null : Number(e.target.value);
                  void change(cfg == null ? 'Configuración heredada' : 'Configuración fijada en el dispositivo',
                    () => setDevicesConfiguration([deviceId], cfg));
                }}>
          <option value="">Heredar ({inheritName})</option>
          {configs.map((c) => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
        </select>
      </div>
      {scope.groupId != null && (
        <p className="muted scope-note">
          <Link to={`/devices?group=${scope.groupId}`}>Otros dispositivos en {scope.groupName}</Link>
        </p>
      )}
    </div>
  );
}
