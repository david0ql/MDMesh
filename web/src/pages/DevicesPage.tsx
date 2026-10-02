import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { DeviceGlyph } from '../ui/DeviceGlyph';
import { useDevices } from '../data/useDevices';
import { isOnline as isOnlineByRecency } from '../ui/status';
import { useToast } from '../ui/toast';
import { fmtRelative, orDash } from '../ui/format';
import {
  deleteDevicesBulk,
  type DeviceView,
  type ConfigurationLookup,
} from '../api/devices';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import { BulkActionModal } from '../components/BulkActionModal';
import { downloadDevicesExcel, groupTree, listDeviceSummaries, listGroups, moveDevicesToGroup, setDevicesConfiguration, type DeviceSummary, type FleetGroup } from '../api/fleet';
import { DEVICE_COLUMNS, loadDeviceColumns, maxDeviceColumns, type DeviceColumn } from '../data/deviceColumns';

type View = 'grid' | 'list';
type StatusFilter = 'all' | 'online' | 'offline';

function configName(
  d: DeviceView,
  configs: Record<string, ConfigurationLookup>,
): string {
  if (d.configurationId == null) return '—';
  return configs[String(d.configurationId)]?.name ?? '—';
}

/** A device's group (company): DallyControl keeps one per device. */
const groupOf = (d: DeviceView) => d.groups?.[0] ?? null;

// Online = checked in recently. statusCode is config-compliance colour (green even for a device
// that was factory-reset and stopped reporting), so it must NOT drive the online/offline dot.
const isOnline = (d: DeviceView, now?: number) => isOnlineByRecency(d.lastUpdate, now);

function IconSearch() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4-4" />
    </svg>
  );
}
function IconGrid() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </svg>
  );
}
function IconList() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <line x1="4" y1="6" x2="20" y2="6" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <line x1="4" y1="18" x2="20" y2="18" />
    </svg>
  );
}

export function DevicesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [exporting, setExporting] = useState(false);
  // Extra list columns: chosen in Ajustes (per browser), capped by what fits at this width.
  const [colKeys, setColKeys] = useState<string[]>(loadDeviceColumns);
  const [maxCols, setMaxCols] = useState(maxDeviceColumns);
  useEffect(() => {
    const onCols = () => setColKeys(loadDeviceColumns());
    const onResize = () => setMaxCols(maxDeviceColumns());
    window.addEventListener('dc-columns', onCols);
    window.addEventListener('resize', onResize);
    return () => { window.removeEventListener('dc-columns', onCols); window.removeEventListener('resize', onResize); };
  }, []);
  const columns: DeviceColumn[] = useMemo(
    () => colKeys.map((k) => DEVICE_COLUMNS.find((c) => c.key === k)).filter((c): c is DeviceColumn => !!c).slice(0, maxCols),
    [colKeys, maxCols],
  );
  const [summaries, setSummaries] = useState<Record<string, DeviceSummary>>({});
  useEffect(() => {
    let on = true;
    const load = () => listDeviceSummaries().then((l) => { if (on) setSummaries(Object.fromEntries(l.map((x) => [x.number, x]))); }).catch(() => undefined);
    void load();
    const t = setInterval(load, 30_000);
    return () => { on = false; clearInterval(t); };
  }, []);
  const { devices, total, configurations, loading, error, reload } = useDevices();
  const [view, setView] = useState<View>('grid');
  const [status, setStatus] = useState<StatusFilter>('all');
  const [config, setConfig] = useState('all');
  const [params, setParams] = useSearchParams();
  const group = params.get('group') ?? 'all';
  const setGroup = (v: string) => setParams((p) => { if (v === 'all') p.delete('group'); else p.set('group', v); return p; }, { replace: true });
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const [groupOpen, setGroupOpen] = useState(false);
  const [groupTarget, setGroupTarget] = useState('');
  const [android, setAndroid] = useState('all');
  const [q, setQ] = useState('');
  const [dupOnly, setDupOnly] = useState(false);

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [allConfigs, setAllConfigs] = useState<ConfigurationSummary[]>([]);
  const [moveOpen, setMoveOpen] = useState(false);
  const [delOpen, setDelOpen] = useState(false);
  const [actionsOpen, setActionsOpen] = useState(false);
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);

  const loadGroups = () => listGroups().then((o) => setGroups(o.groups)).catch(() => undefined);
  useEffect(() => { void loadGroups(); }, []);

  useEffect(() => {
    listConfigurations()
      .then((l) => setAllConfigs([...l].sort((a, b) => a.name.localeCompare(b.name))))
      .catch(() => undefined);
  }, []);

  // Tick every 30s so online/offline chips and dots decay as devices go quiet.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(t);
  }, []);

  // Server-side search: the list is capped at one page, so let the query hit the API too
  // (debounced); the client-side filters below still apply on top of what came back.
  const firstSearch = useRef(true);
  useEffect(() => {
    if (firstSearch.current) { firstSearch.current = false; return; } // mount load is in useDevices
    const t = setTimeout(() => { void reload(q.trim()); }, 300);
    return () => clearTimeout(t);
  }, [q, reload]);

  const onlineCount = useMemo(
    () => devices.filter((d) => isOnline(d, now)).length,
    [devices, now],
  );

  // Group by hardware id: a value shared by >1 row = same physical device enrolled twice.
  const dupCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const d of devices) if (d.hardwareId) m.set(d.hardwareId, (m.get(d.hardwareId) ?? 0) + 1);
    return m;
  }, [devices]);
  const dupOf = (d: DeviceView) => (d.hardwareId ? dupCount.get(d.hardwareId) ?? 0 : 0);
  const dupTotal = useMemo(
    () => devices.filter((d) => (d.hardwareId ? (dupCount.get(d.hardwareId) ?? 0) : 0) > 1).length,
    [devices, dupCount],
  );

  const configOptions = useMemo(() => {
    const names = new Set<string>();
    for (const d of devices) {
      const n = configName(d, configurations);
      if (n !== '—') names.add(n);
    }
    return [...names].sort();
  }, [devices, configurations]);

  const androidOptions = useMemo(() => {
    const v = new Set<string>();
    for (const d of devices) if (d.androidVersion) v.add(d.androidVersion);
    return [...v].sort();
  }, [devices]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return devices.filter((d) => {
      if (status === 'online' && !isOnline(d, now)) return false;
      if (status === 'offline' && isOnline(d, now)) return false;
      if (config !== 'all' && configName(d, configurations) !== config) return false;
      if (group === 'none' && groupOf(d)) return false;
      // A folder shows its sub-folders' devices too.
      if (group !== 'all' && group !== 'none') {
        const sub = tree.find((n) => String(n.group.id) === group)?.subtree;
        const gid = groupOf(d)?.id;
        if (gid == null || !(sub ? sub.has(gid) : String(gid) === group)) return false;
      }
      if (android !== 'all' && d.androidVersion !== android) return false;
      if (dupOnly && (d.hardwareId ? (dupCount.get(d.hardwareId) ?? 0) : 0) <= 1) return false;
      if (needle) {
        const hay = `${d.number ?? ''} ${d.description ?? ''} ${d.imei ?? ''} ${d.serial ?? ''}`.toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      return true;
    });
  }, [devices, status, config, group, tree, android, q, dupOnly, dupCount, configurations, now]);

  // Route by number (not id) so the detail page can fetch the device with a narrow search.
  const go = (d: DeviceView) => navigate(`/devices/${encodeURIComponent(d.number)}`);

  const toggle = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const clearSel = () => setSelected(new Set());
  const selectAllFiltered = () => setSelected(new Set(filtered.map((d) => d.id)));
  const selectionActive = selected.size > 0;
  const allFilteredSelected =
    filtered.length > 0 && filtered.every((d) => selected.has(d.id));
  const toggleAll = () => (allFilteredSelected ? clearSel() : selectAllFiltered());

  // Device-level configuration: pin one on the selected devices, or let them inherit (group, then global).
  async function applyMove() {
    if (!target) return;
    setBusy(true);
    try {
      const inherit = target === 'inherit';
      await setDevicesConfiguration([...selected], inherit ? null : Number(target));
      const name = inherit ? 'heredada de la carpeta / global'
        : allConfigs.find((c) => c.id === Number(target))?.name ?? 'política';
      toast.push('ok', 'Política cambiada', `${selected.size} dispositivo(s) → ${name}.`);
      setMoveOpen(false);
      setTarget('');
      clearSel();
      await reload();
    } catch (e) {
      toast.push('err', 'No se pudo cambiar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function applyGroup() {
    if (!groupTarget) return;
    setBusy(true);
    try {
      const gid = groupTarget === 'none' ? null : Number(groupTarget);
      const r = await moveDevicesToGroup([...selected], gid);
      const name = gid == null ? 'sin carpeta' : groups.find((g) => g.id === gid)?.name ?? 'carpeta';
      toast.push('ok', 'Carpeta cambiada',
        `${r.moved} dispositivo(s) → ${name}` + (r.devicesReconfigured ? ` · ${r.devicesReconfigured} reconfigurado(s)` : '') + '.');
      setGroupOpen(false);
      setGroupTarget('');
      clearSel();
      await Promise.all([reload(), loadGroups()]);
    } catch (e) {
      toast.push('err', 'No se pudo mover', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function applyDelete() {
    setBusy(true);
    try {
      await deleteDevicesBulk([...selected]);
      toast.push('ok', 'Dispositivos eliminados', `${selected.size} dispositivo(s) eliminado(s).`);
      setDelOpen(false);
      clearSel();
      await reload();
    } catch (e) {
      toast.push('err', 'No se pudo eliminar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AppShell title="Dispositivos">
      <div className="dv-head">
        <h1>Dispositivos</h1>
        <span className="dv-count">
          {total > devices.length ? `${devices.length} de ${total}` : devices.length} en total · {onlineCount} en línea
        </span>
        <div className="dv-spacer" />
        <button className="btn" data-testid="export-excel" disabled={exporting} title="Resumen, dispositivos, carpetas y conexiones (30 días); respeta la carpeta filtrada"
          onClick={() => {
            setExporting(true);
            const gid = group !== 'all' && group !== 'none' ? Number(group) : undefined;
            downloadDevicesExcel(gid).catch((e) => toast.push('err', 'No se pudo descargar', e instanceof Error ? e.message : ''))
              .finally(() => setExporting(false));
          }}>
          {exporting ? <span key="x">Generando…</span> : <span key="d">Descargar Excel</span>}
        </button>
        <div className="dv-search">
          <IconSearch />
          <input
            type="search"
            placeholder="Buscar dispositivos"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <div className="toggle" role="group" aria-label="Vista">
          <button className={view === 'grid' ? 'on' : ''} onClick={() => setView('grid')} aria-label="Vista de cuadrícula" title="Cuadrícula">
            <IconGrid />
          </button>
          <button className={view === 'list' ? 'on' : ''} onClick={() => setView('list')} aria-label="Vista de lista" title="Lista">
            <IconList />
          </button>
        </div>
        <button className="btn btn-dark" onClick={() => navigate('/enroll')}>
          Inscribir dispositivo
        </button>
      </div>

      <div className="filters">
        <button className={`filter-chip ${status === 'all' ? 'on' : ''}`} onClick={() => setStatus('all')}>
          Todos <b>{devices.length}</b>
        </button>
        <button className={`filter-chip ${status === 'online' ? 'on' : ''}`} onClick={() => setStatus('online')}>
          En línea <b>{onlineCount}</b>
        </button>
        <button className={`filter-chip ${status === 'offline' ? 'on' : ''}`} onClick={() => setStatus('offline')}>
          Sin conexión <b>{devices.length - onlineCount}</b>
        </button>
        {dupTotal > 0 && (
          <button
            className={`filter-chip dup ${dupOnly ? 'on' : ''}`}
            onClick={() => setDupOnly((v) => !v)}
            title="Dispositivos que comparten el ID de hardware con otra fila: probablemente el mismo equipo físico inscrito más de una vez"
          >
            ⚠ Duplicados <b>{dupTotal}</b>
          </button>
        )}
        <span className="filter-div" />
        <select className="sel" value={group} onChange={(e) => setGroup(e.target.value)} aria-label="Filtrar por carpeta">
          <option value="all">Carpeta: todas</option>
          {tree.map((n) => (
            <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>
          ))}
          <option value="none">Sin carpeta</option>
        </select>
        <select className="sel" value={config} onChange={(e) => setConfig(e.target.value)} aria-label="Filtrar por política">
          <option value="all">Política: todas</option>
          {configOptions.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
        <select className="sel" value={android} onChange={(e) => setAndroid(e.target.value)} aria-label="Filtrar por versión de Android">
          <option value="all">Android: todas</option>
          {androidOptions.map((a) => (
            <option key={a} value={a}>Android {a}</option>
          ))}
        </select>
      </div>

      {selected.size > 0 && (
        <div className="bulk-bar">
          <span className="bulk-count">{selected.size} seleccionado(s)</span>
          <button className="btn btn-sm" onClick={() => setActionsOpen(true)}>
            Acciones
          </button>
          <button className="btn btn-sm" onClick={() => setGroupOpen(true)}>
            Mover a carpeta
          </button>
          <button className="btn btn-sm" onClick={() => setMoveOpen(true)}>
            Cambiar política
          </button>
          <button className="btn btn-sm btn-danger" onClick={() => setDelOpen(true)}>
            Eliminar
          </button>
          <div style={{ flex: 1 }} />
          {selected.size < filtered.length && (
            <button className="btn btn-sm btn-ghost" onClick={selectAllFiltered}>
              Seleccionar los {filtered.length}
            </button>
          )}
          <button className="btn btn-sm btn-ghost" onClick={clearSel}>
            Limpiar
          </button>
        </div>
      )}

      {error && <div className="banner banner-alert">{error}</div>}

      {loading ? (
        <div className="panel">
          <div className="empty">
            <span className="spin" /> Cargando dispositivos…
          </div>
        </div>
      ) : filtered.length === 0 ? (
        <div className="panel">
          <div className="empty">
            <span className="label">Sin dispositivos</span>
            {devices.length === 0 ? 'Aún no hay dispositivos inscritos.' : 'Ningún dispositivo coincide con estos filtros.'}
          </div>
        </div>
      ) : (
        <>
          <div className="select-all">
            <input
              type="checkbox"
              className="dev-check"
              checked={allFilteredSelected}
              ref={(el) => {
                if (el) el.indeterminate = selectionActive && !allFilteredSelected;
              }}
              onChange={toggleAll}
              aria-label="Seleccionar todos los dispositivos"
            />
            <span onClick={toggleAll} style={{ cursor: 'pointer' }}>
              {allFilteredSelected ? 'Quitar selección' : 'Seleccionar todo'} · {filtered.length} dispositivo
              {filtered.length === 1 ? '' : 's'}
            </span>
          </div>
          {view === 'grid' ? (
            <div className="dev-grid">
              {filtered.map((d) => (
                <DeviceCard
                  key={d.id}
                  d={d}
                  now={now}
                  config={configName(d, configurations)}
                  group={groupOf(d)?.name ?? '—'}
                  dup={dupOf(d)}
                  selected={selected.has(d.id)}
                  selectionActive={selectionActive}
                  onToggle={() => toggle(d.id)}
                  onOpen={() => go(d)}
                />
              ))}
            </div>
          ) : (
            <div className="dev-list">
              {filtered.map((d) => (
                <DeviceRow
                  key={d.id}
                  d={d}
                  now={now}
                  config={configName(d, configurations)}
                  group={groupOf(d)?.name ?? '—'}
                  dup={dupOf(d)}
                  columns={columns}
                  summary={summaries[d.number]}
                  selected={selected.has(d.id)}
                  selectionActive={selectionActive}
                  onToggle={() => toggle(d.id)}
                  onOpen={() => go(d)}
                />
              ))}
            </div>
          )}
        </>
      )}

      {actionsOpen && (
        <BulkActionModal
          target={{ kind: 'devices', ids: [...selected] }}
          onClose={() => setActionsOpen(false)}
          onDone={() => clearSel()}
        />
      )}

      {moveOpen && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setMoveOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Cambiar política</h3>
            <p className="muted" style={{ marginTop: 2 }}>
              Política propia para {selected.size} dispositivo{selected.size === 1 ? '' : 's'}: la política
              que elijas aquí tiene prioridad sobre la de la carpeta y la global. &ldquo;Heredar&rdquo; los devuelve a la de su carpeta
              (o a la política global).
            </p>
            <label className="field">
              <span>Política</span>
              <select className="sel" value={target} onChange={(e) => setTarget(e.target.value)} style={{ width: '100%' }}>
                <option value="">Elige una política…</option>
                <option value="inherit">Heredar de la carpeta / global</option>
                {allConfigs.map((c) => (
                  <option key={c.id} value={String(c.id)}>{c.name}</option>
                ))}
              </select>
            </label>
            <div className="modal-actions">
              <button className="btn" onClick={() => setMoveOpen(false)} disabled={busy}>Cancelar</button>
              <button className="btn btn-primary" disabled={busy || !target} onClick={() => void applyMove()}>
                {busy ? 'Moviendo…' : 'Mover'}
              </button>
            </div>
          </div>
        </div>
      )}

      {groupOpen && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setGroupOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Mover a carpeta</h3>
            <p className="muted" style={{ marginTop: 2 }}>
              Pon {selected.size} dispositivo{selected.size === 1 ? '' : 's'} en una carpeta (empresa). Toman la política
              de la carpeta, salvo que tengan una propia.
            </p>
            <label className="field">
              <span>Carpeta</span>
              <select className="sel" value={groupTarget} onChange={(e) => setGroupTarget(e.target.value)} style={{ width: '100%' }}>
                <option value="">Elige una carpeta…</option>
                {tree.map((n) => (
                  <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>
                ))}
                <option value="none">Sin carpeta</option>
              </select>
            </label>
            {groups.length === 0 && <p className="muted">Aún no hay carpetas: crea una en Carpetas.</p>}
            <div className="modal-actions">
              <button className="btn" onClick={() => setGroupOpen(false)} disabled={busy}>Cancelar</button>
              <button className="btn btn-primary" disabled={busy || !groupTarget} onClick={() => void applyGroup()}>
                {busy ? 'Moviendo…' : 'Mover'}
              </button>
            </div>
          </div>
        </div>
      )}

      {delOpen && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={() => setDelOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Eliminar dispositivos</h3>
            <p className="muted" style={{ marginTop: 2 }}>
              ¿Eliminar {selected.size} dispositivo{selected.size === 1 ? '' : 's'} de la consola? Si el teléfono sigue inscrito
              (con el agente), vuelve a aparecer solo en su carpeta la próxima vez que se conecte. Para sacarlo de verdad,
              primero restablécelo de fábrica desde su pestaña Control.
            </p>
            <div className="modal-actions">
              <button className="btn" onClick={() => setDelOpen(false)} disabled={busy}>Cancelar</button>
              <button className="btn btn-danger" disabled={busy} onClick={() => void applyDelete()}>
                {busy ? 'Eliminando…' : `Eliminar ${selected.size}`}
              </button>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  );
}

function SelectBox({ selected, onToggle }: { selected: boolean; onToggle: () => void }) {
  return (
    <input
      type="checkbox"
      className="dev-check"
      checked={selected}
      onClick={(e) => e.stopPropagation()}
      onChange={onToggle}
      aria-label="Seleccionar dispositivo"
    />
  );
}

function DupBadge({ n }: { n: number }) {
  return (
    <span
      className="dup-badge"
      title={`Comparte el ID de hardware con ${n - 1} dispositivo${n - 1 === 1 ? '' : 's'} más: probablemente el mismo equipo físico inscrito más de una vez`}
    >
      ⚠ {n}×
    </span>
  );
}

function DeviceCard({
  d,
  now,
  config,
  group,
  dup,
  selected,
  selectionActive,
  onToggle,
  onOpen,
}: {
  d: DeviceView;
  now: number;
  config: string;
  group: string;
  dup: number;
  selected: boolean;
  selectionActive: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const online = isOnline(d, now);
  // Once a selection is in progress, clicking a card toggles it instead of opening it.
  const act = selectionActive ? onToggle : onOpen;
  return (
    <div
      className={`dev ${selected ? 'sel' : ''}`}
      role="button"
      tabIndex={0}
      onClick={act}
      onKeyDown={(e) => e.key === 'Enter' && act()}
    >
      <div className="h">
        <SelectBox selected={selected} onToggle={onToggle} />
        <span className={`dot ${online ? 'on' : 'off'}`} />
        <span className="nm">{orDash(d.number)}</span>
        {dup > 1 && <DupBadge n={dup} />}
        <DeviceGlyph className="ico" name={d.description || d.number} size={16} />
      </div>
      {d.description && <div className="sub">{d.description}</div>}
      <div className="kv">
        <div>
          <div className="k">Android</div>
          <div className="v">{orDash(d.androidVersion)}</div>
        </div>
        <div>
          <div className="k">Política</div>
          <div className="v">{config}</div>
        </div>
        <div>
          <div className="k">Carpeta</div>
          <div className="v">{group}</div>
        </div>
        <div>
          <div className="k">Última conexión</div>
          <div className="v">{fmtRelative(d.lastUpdate)}</div>
        </div>
      </div>
    </div>
  );
}

function DeviceRow({
  d,
  now,
  config,
  group,
  dup,
  columns,
  summary,
  selected,
  selectionActive,
  onToggle,
  onOpen,
}: {
  d: DeviceView;
  now: number;
  config: string;
  group: string;
  dup: number;
  columns: DeviceColumn[];
  summary?: DeviceSummary;
  selected: boolean;
  selectionActive: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const online = isOnline(d, now);
  const act = selectionActive ? onToggle : onOpen;
  return (
    <div
      className={`dev-row ${selected ? 'sel' : ''}`}
      style={{ gridTemplateColumns: `minmax(200px, 1fr) ${columns.map((c) => c.width).join(' ')}` }}
      role="button"
      tabIndex={0}
      onClick={act}
      onKeyDown={(e) => e.key === 'Enter' && act()}
    >
      <div className="id">
        <SelectBox selected={selected} onToggle={onToggle} />
        <span className={`dot ${online ? 'on' : 'off'}`} />
        <DeviceGlyph className="ico" name={d.description || d.number} size={15} />
        <div style={{ minWidth: 0 }}>
          <div className="nm">{d.description || orDash(d.number)}</div>
          <div className="sub mono">{d.description ? d.number : summary?.model ?? ''}</div>
        </div>
        {dup > 1 && <DupBadge n={dup} />}
      </div>
      {columns.map((c) => (
        <div className="lc" key={c.key}>
          <span className="lk">{c.label}</span>
          <span className="lv">{c.render(d, summary, { online, policy: config, group })}</span>
        </div>
      ))}
    </div>
  );
}
