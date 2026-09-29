import { useEffect, useMemo, useState } from 'react';
import { groupTree, listGroups, updateGroup, type FleetGroup } from '../api/fleet';

/**
 * Folders that use a policy, chosen from the policy itself (MobiControl-style: assign the policy to whole folders).
 * A ticked folder runs this policy; its sub-folders inherit it unless they have their own. Changes are applied when
 * the policy is saved ({@link applyPolicyFolders}).
 */
export function PolicyFolders({ policyId, value, onChange, disabled }: {
  policyId?: number;
  value: Set<number> | null;
  onChange: (next: Set<number>) => void;
  disabled?: boolean;
}) {
  const [groups, setGroups] = useState<FleetGroup[] | null>(null);
  useEffect(() => { listGroups().then((o) => setGroups(o.groups)).catch(() => setGroups([])); }, []);
  const tree = useMemo(() => groupTree(groups ?? []), [groups]);
  // Initial selection: the folders whose own policy is this one.
  useEffect(() => {
    if (!groups || value !== null) return;
    onChange(new Set(groups.filter((g) => policyId != null && g.configurationId === policyId).map((g) => g.id)));
  }, [groups, policyId, value, onChange]);
  const sel = value ?? new Set<number>();
  const inherited = (id: number) => {
    // True when an ancestor (ticked) passes the policy down and the folder has none of its own ticked.
    const node = tree.find((n) => n.group.id === id);
    return !!node && tree.some((n) => n.group.id !== id && sel.has(n.group.id) && n.subtree.has(id)) && !sel.has(id);
  };

  return (
    <section className="panel cfg-panel" data-testid="policy-folders">
      <div className="cfg-sec-h">Carpetas que usan esta política</div>
      <p className="note" style={{ margin: '0 0 10px' }}>
        Marca las carpetas a las que se aplica. Sus subcarpetas la heredan salvo que tengan otra política. Se aplica al guardar.
      </p>
      {groups === null ? <p className="muted">Cargando carpetas…</p> : tree.length === 0 ? <p className="muted">Aún no hay carpetas.</p> : (
        <div className="deploy-devlist" style={{ maxHeight: 280 }}>
          {tree.map((n) => {
            const other = n.group.configurationId != null && n.group.configurationId !== policyId && !sel.has(n.group.id);
            return (
              <label key={n.group.id} className="deploy-devrow" style={{ paddingLeft: 12 + n.depth * 18 }}>
                <input type="checkbox" checked={sel.has(n.group.id)} disabled={disabled}
                  onChange={() => { const x = new Set(sel); if (x.has(n.group.id)) x.delete(n.group.id); else x.add(n.group.id); onChange(x); }} />
                <span className="dd-nm">{n.group.name}</span>
                <span className="dd-seen">
                  {sel.has(n.group.id) ? 'esta política' : inherited(n.group.id) ? 'la hereda'
                    : other ? `usa ${n.group.configurationName ?? 'otra'}` : `hereda ${n.group.effectiveConfigurationName ?? 'la global'}`}
                  {' · '}{n.totalDevices} disp.
                </span>
              </label>
            );
          })}
        </div>
      )}
    </section>
  );
}

/** Assign / unassign the policy on the folders whose ticks changed. @return how many devices were reconfigured. */
export async function applyPolicyFolders(policyId: number, wanted: Set<number>): Promise<number> {
  const { groups } = await listGroups();
  let changed = 0;
  for (const g of groups) {
    const has = g.configurationId === policyId;
    if (wanted.has(g.id) && !has) changed += (await updateGroup(g.id, g.name, policyId, g.parentId)).devicesReconfigured;
    if (!wanted.has(g.id) && has) changed += (await updateGroup(g.id, g.name, null, g.parentId)).devicesReconfigured;
  }
  return changed;
}
