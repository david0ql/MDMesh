import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { DeviceGlyph } from '../ui/DeviceGlyph';
import {
  searchDevices, updateDeviceDescription, type DeviceView, type ConfigurationLookup,
} from '../api/devices';
import { ActionConsole } from '../components/ActionConsole';
import { TelemetryCard } from '../components/TelemetryCard';
import { EventTimeline } from '../components/EventTimeline';
import { LocationPanel } from '../components/LocationPanel';
import { AppsPanel } from '../components/AppsPanel';
import { RemotePanel } from '../components/RemotePanel';
import { ConfigStatusCard } from '../components/ConfigStatusCard';
import { DeviceScopeCard } from '../components/DeviceScopeCard';
import { getTelemetry, type TelemetrySnapshot } from '../api/telemetry';
import { getConfigStatus, type ConfigStatus } from '../api/configSync';
import {
  getDeviceState, forceSync, queueCommand, syncConfigApps, type DeviceState,
} from '../api/commands';
import { ApiError } from '../api/client';
import { isOnline as isOnlineByRecency } from '../ui/status';
import { useToast } from '../ui/toast';
import { fmtDateTime, fmtRelative, orDash } from '../ui/format';

type Tab = 'control' | 'remote' | 'apps' | 'telemetry' | 'events' | 'location';

interface Row {
  k: string;
  v: import('react').ReactNode;
  mono?: boolean;
}

function powerLabel(mode?: string | null): string {
  if (mode === 'alwaysOn') return 'Siempre conectado';
  if (mode === 'adaptive') return 'Ahorro de batería';
  return '—';
}

/** Inline editor for the device's friendly name (description). Click to edit,
 *  Enter/Save to persist, Esc/Cancel to revert. An empty value clears the name. */
function NameField({
  device,
  onSaved,
}: {
  device: DeviceView;
  onSaved: (description: string) => void;
}) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(device.description ?? '');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!editing) setValue(device.description ?? '');
  }, [device.description, editing]);

  const cancel = () => {
    setValue(device.description ?? '');
    setEditing(false);
  };

  async function save() {
    const next = value.trim();
    if (next === (device.description ?? '')) {
      setEditing(false);
      return;
    }
    setSaving(true);
    try {
      await updateDeviceDescription(device.id, next);
      onSaved(next);
      setEditing(false);
      toast.push('ok', 'Nombre guardado', next || 'Nombre borrado.');
    } catch (e) {
      toast.push('err', 'No se pudo cambiar el nombre', e instanceof Error ? e.message : '');
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <div className="dd-name-edit">
        <input
          autoFocus
          value={value}
          maxLength={200}
          placeholder="Nombre del dispositivo"
          disabled={saving}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void save();
            else if (e.key === 'Escape') cancel();
          }}
        />
        <button className="pri" disabled={saving} onClick={() => void save()}>
          {saving ? <span key="saving">Guardando…</span> : <span key="save">Guardar</span>}
        </button>
        <button className="sec" disabled={saving} onClick={cancel}>
          Cancelar
        </button>
      </div>
    );
  }

  return (
    <button type="button" className="dd-name" onClick={() => setEditing(true)} title="Cambiar el nombre de este dispositivo">
      <span className={`mfr ${device.description ? '' : 'muted'}`}>
        {device.description || 'Añade un nombre'}
      </span>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 20h9" />
        <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
      </svg>
    </button>
  );
}

/** "12,4 GB libres de 64 GB" from telemetry byte counts; a dash when unknown. */
function freeOf(free: unknown, total: unknown): string {
  const gb = (n: number) => (n / 1024 ** 3).toLocaleString('es-CO', { maximumFractionDigits: 1 });
  if (typeof free !== 'number' || typeof total !== 'number' || total <= 0) return '—';
  return `${gb(free)} GB libres de ${gb(total)} GB`;
}

export function DeviceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const toast = useToast();
  const [device, setDevice] = useState<DeviceView | null>(null);
  const [configs, setConfigs] = useState<Record<string, ConfigurationLookup>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tele, setTele] = useState<TelemetrySnapshot | null>(null);
  const [ds, setDs] = useState<DeviceState | null>(null);
  const [cfgStatus, setCfgStatus] = useState<ConfigStatus | null>(null);
  const [tab, setTab] = useState<Tab>('control');
  const workRef = useRef<HTMLElement>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // The route param is the device number (see DevicesPage/DashboardPage links), so a narrow
      // server-side search finds it without downloading the fleet. Old id-style links (and rare
      // substring collisions on the number) miss it and fall back to the broad fetch.
      const matches = (d: DeviceView) => d.number === id || String(d.id) === id;
      let res = await searchDevices({ value: id, pageSize: 1 });
      let found = (res.devices?.items ?? []).find(matches) ?? null;
      if (!found) {
        res = await searchDevices({ pageSize: 1000 });
        found = (res.devices?.items ?? []).find(matches) ?? null;
      }
      setConfigs(res.configurations ?? {});
      setDevice(found);
      if (!found) setError('Dispositivo no encontrado.');
    } catch (err) {
      if (err instanceof ApiError && err.httpStatus === 0)
        setError('No se puede conectar con el servidor.');
      else setError('No se pudo cargar el dispositivo.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!device) return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    // Self-scheduling poll: the next tick is armed only after the current one finishes, so slow
    // responses can't stack overlapping requests.
    const poll = async () => {
      await Promise.all([
        getTelemetry(device.number).then((v) => { if (on) setTele(v); }).catch(() => undefined),
        getDeviceState(device.number).then((v) => { if (on) setDs(v); }).catch(() => undefined),
        getConfigStatus(device.number).then((v) => { if (on) setCfgStatus(v); }).catch(() => undefined),
      ]);
      if (!on) return;
      t = setTimeout(() => void poll(), 5000);
    };
    void poll();
    return () => { on = false; clearTimeout(t); };
  }, [device]);

  const configName =
    device?.configurationId != null
      ? configs[String(device.configurationId)]?.name ?? '—'
      : '—';

  const hw = (tele?.hardware ?? {}) as Record<string, unknown>;
  const idn = (tele?.identity ?? {}) as Record<string, unknown>;
  const sec = (tele?.security ?? {}) as Record<string, unknown>;
  const dyn = (tele?.dynamic ?? {}) as Record<string, unknown>;
  const teleStr = (v: unknown): string | undefined =>
    v == null ? undefined : Array.isArray(v) ? (v[0] != null ? String(v[0]) : undefined) : String(v);
  const onOff = (v: unknown, fallback: boolean | null | undefined): string =>
    v === true ? 'Sí' : v === false ? 'No' : fallback == null ? '—' : fallback ? 'Sí' : 'No';

  async function syncNow() {
    if (!device) return;
    setBusy(true);
    try {
      await forceSync(device.number);
      void getDeviceState(device.number).then(setDs).catch(() => undefined);
      void getConfigStatus(device.number).then(setCfgStatus).catch(() => undefined);
      toast.push('ok', 'Sincronización solicitada', '');
    } catch (e) {
      toast.push('err', 'No se pudo sincronizar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function lock() {
    if (!device) return;
    setBusy(true);
    try {
      await queueCommand(device.number, {
        type: 'device.lock',
        requiresCapability: 'device.lock',
      });
      await forceSync(device.number).catch(() => undefined);
      toast.push('ok', 'Bloqueo enviado', '');
    } catch (e) {
      toast.push('err', 'No se pudo bloquear', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  // Re-queue the device's configuration apps (action=install) — for devices enrolled before the
  // config's app list changed, or enrolled before config-driven install existed.
  async function installConfigApps() {
    if (!device) return;
    setBusy(true);
    try {
      const res = await syncConfigApps(device.number);
      toast.push('ok', 'Apps de la política enviadas', `${res.queued} ${res.queued === 1 ? 'orden de instalación enviada' : 'órdenes de instalación enviadas'}.`);
    } catch (e) {
      toast.push('err', 'No se pudieron sincronizar las apps', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <AppShell title="Dispositivo">
        <div className="panel">
          <div className="empty">
            <span className="spin" /> Cargando dispositivo…
          </div>
        </div>
      </AppShell>
    );
  }

  if (!device) {
    return (
      <AppShell title="Dispositivo">
        <div className="crumb">
          <a href="/devices" onClick={(e) => { e.preventDefault(); navigate('/devices'); }}>
            Dispositivos
          </a>
        </div>
        <div className="banner banner-alert">{error ?? 'Dispositivo no encontrado.'}</div>
      </AppShell>
    );
  }

  // Online/offline is recency of last check-in — NOT statusCode (which is config compliance and
  // stays green for a device that was factory-reset and stopped reporting).
  const online = isOnlineByRecency(device.lastUpdate);
  const statusLabel = online ? 'En línea' : 'Sin conexión';

  const statusRows: Row[] = [
    { k: 'Batería', v: ds ? (ds.battery < 0 ? '—' : `${ds.battery}% · ${ds.charging ? 'cargando' : 'sin cargar'}`) : '—' },
    { k: 'Pantalla', v: ds ? (ds.locked ? 'Bloqueada' : 'Desbloqueada') : '—' },
    { k: 'Quiosco', v: ds ? (ds.kioskActive ? 'Activo' : 'Inactivo') : '—' },
    { k: 'Conectividad', v: powerLabel(ds?.powerMode) },
  ];
  const hardwareRows: Row[] = [
    { k: 'Android', v: orDash(teleStr(hw.osRelease) ?? ds?.androidRelease ?? device.androidVersion) },
    { k: 'Almacenamiento', v: freeOf(dyn.freeStorageBytes, hw.totalStorageBytes) },
    { k: 'RAM', v: freeOf(dyn.freeRamBytes, hw.totalRamBytes) },
    { k: 'Serie', v: orDash(teleStr(idn.serial) ?? device.serial), mono: true },
    { k: 'IMEI', v: orDash(teleStr(idn.imei) ?? device.imei), mono: true },
    { k: 'MAC Wi‑Fi', v: orDash(teleStr(idn.wifiMac)), mono: true },
  ];
  // SIM (agent SimMonitor): state + carrier + number per slot; the number only when the carrier stores it on the SIM.
  const sim = dyn.sim as { state?: string; slots?: { slot: number; carrier?: string; number?: string }[] } | undefined;
  const simSlots = sim?.slots ?? [];
  const phoneNumbers = simSlots.map((s) => s.number).filter(Boolean) as string[];
  const idnNumbers = Array.isArray(idn.phoneNumber) ? (idn.phoneNumber as unknown[]).filter((x): x is string => typeof x === 'string' && x !== '') : [];
  const simLabel = !sim?.state ? '—'
    : sim.state === 'absent' ? 'Sin SIM'
    : sim.state === 'locked' ? 'Bloqueada (PIN/PUK)'
    : sim.state === 'ready' ? (simSlots.map((s) => s.carrier).filter(Boolean).join(', ') || 'Lista')
    : 'Desconocido';
  const networkRows: Row[] = [
    { k: 'SIM', v: simLabel },
    { k: 'Número de teléfono', v: orDash((phoneNumbers.length ? phoneNumbers : idnNumbers).join(', ') || undefined), mono: true },
    { k: 'Tipo', v: orDash(teleStr(dyn.networkType) ?? teleStr(dyn.network)) },
    { k: 'IP local', v: orDash(teleStr(dyn.ipAddress) ?? teleStr(dyn.localIp)), mono: true },
    { k: 'IP pública', v: orDash(teleStr((tele as Record<string, unknown> | null)?.publicIp) ?? device.publicIp), mono: true },
  ];
  const managementRows: Row[] = [
    { k: 'Política', v: configName },
    { k: 'Agente', v: orDash(ds?.agentVersion ?? device.launcherVersion) },
    { k: 'Modo MDM', v: onOff(sec.isDeviceOwner, device.mdmMode) },
    { k: 'Inscrito', v: fmtDateTime(device.enrollTime) },
  ];

  const loc = dyn.location as
    | { lat?: number; lon?: number; accuracyM?: number; provider?: string; capturedAt?: number }
    | undefined;
  const hasFix = !!loc && typeof loc.lat === 'number' && typeof loc.lon === 'number';
  const locationRows: Row[] = hasFix
    ? [
        {
          k: 'Coordenadas',
          v: (
            <a href={`https://www.google.com/maps?q=${loc!.lat},${loc!.lon}`} target="_blank" rel="noopener noreferrer">
              {loc!.lat!.toFixed(5)}, {loc!.lon!.toFixed(5)} ↗
            </a>
          ),
          mono: true,
        },
        { k: 'Precisión', v: loc!.accuracyM != null ? `±${Math.round(loc!.accuracyM)} m` : '—' },
        { k: 'Fuente', v: orDash(loc!.provider) },
        { k: 'Antigüedad', v: loc!.capturedAt ? fmtRelative(loc!.capturedAt) : '—' },
      ]
    : [{ k: 'Ubicación', v: 'Aún no ha reportado ubicación' }];

  const groups: Array<{ title: string; rows: Row[] }> = [
    { title: 'Estado', rows: statusRows },
    { title: 'Ubicación', rows: locationRows },
    { title: 'Hardware', rows: hardwareRows },
    { title: 'Red', rows: networkRows },
    { title: 'Gestión', rows: managementRows },
  ];

  return (
    <AppShell title={device.number}>
      <div className="crumb">
        <a href="/devices" onClick={(e) => { e.preventDefault(); navigate('/devices'); }}>
          Dispositivos
        </a>{' '}
        / {device.number}
      </div>

      <div className="dd-cols">
        {/* LEFT: the device */}
        <aside className="panel detail-rail">
          <div className="top">
            <span className={`dot ${online ? 'on' : 'off'}`} />
            <span className={`st ${online ? 'on' : 'off'}`}>{statusLabel}</span>
            <span className="ago">· {fmtRelative(device.lastUpdate)}</span>
            <DeviceGlyph className="ico" name={device.description || device.number} size={20} />
          </div>
          <h1>{device.number}</h1>
          <NameField
            device={device}
            onSaved={(desc) => setDevice((d) => (d ? { ...d, description: desc } : d))}
          />

          <div className="actions">
            <button className="pri" disabled={busy} onClick={() => void syncNow()}>
              Sincronizar ahora
            </button>
            <button
              className="sec"
              disabled={busy}
              onClick={() => {
                setTab('remote');
                // On a phone the tabs sit below the rail: bring the Remote panel into view.
                requestAnimationFrame(() => workRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
              }}
            >
              Remoto
            </button>
            <button className="sec" disabled={busy} onClick={() => void lock()}>
              Bloquear
            </button>
            <button className="sec" disabled={busy} onClick={() => void installConfigApps()}>
              Instalar apps de la política
            </button>
          </div>

          {groups.map((g) => (
            <div key={g.title}>
              <div className="grp">{g.title}</div>
              {g.rows.map((r) => (
                <div className="row" key={r.k}>
                  <span className="k">{r.k}</span>
                  <span className={`v ${r.mono ? 'mono' : ''}`}>{r.v}</span>
                </div>
              ))}
            </div>
          ))}

          {sim?.state === 'absent' && (
            <div className="banner banner-alert" role="alert" data-testid="sim-absent">
              <b>Sin tarjeta SIM.</b> La SIM fue retirada (mira en Eventos cuándo y cuál era).
            </div>
          )}

          <DeviceScopeCard
            deviceId={device.id}
            onChanged={() => { void getConfigStatus(device.number).then(setCfgStatus).catch(() => undefined); }}
          />

          <ConfigStatusCard status={cfgStatus} />
        </aside>

        {/* RIGHT: work */}
        <section className="panel detail-main" ref={workRef}>
          <div className="tabs" role="tablist">
            <button className={tab === 'control' ? 'on' : ''} onClick={() => setTab('control')}>
              Control
            </button>
            <button className={tab === 'remote' ? 'on' : ''} onClick={() => setTab('remote')}>
              Remoto
            </button>
            <button className={tab === 'apps' ? 'on' : ''} onClick={() => setTab('apps')}>
              Aplicaciones
            </button>
            <button className={tab === 'telemetry' ? 'on' : ''} onClick={() => setTab('telemetry')}>
              Telemetría
            </button>
            <button className={tab === 'events' ? 'on' : ''} onClick={() => setTab('events')}>
              Eventos
            </button>
            <button className={tab === 'location' ? 'on' : ''} onClick={() => setTab('location')}>
              Ubicación
            </button>
          </div>

          <div className="tabbody">
            {tab === 'control' && <ActionConsole device={device} />}
            {tab === 'remote' && <RemotePanel device={device} />}
            {tab === 'apps' && <AppsPanel device={device} />}
            {tab === 'telemetry' && <TelemetryCard device={device} />}
            {tab === 'events' && <EventTimeline device={device} />}
            {tab === 'location' && <LocationPanel device={device} />}
          </div>
        </section>
      </div>
    </AppShell>
  );
}
