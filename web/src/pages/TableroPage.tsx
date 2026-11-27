import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLiveDevices } from '../ui/live';
import { AppShell } from '../ui/AppShell';
import { useDevices } from '../data/useDevices';
import {
  statusMeta,
  isOnline as isOnlineByRecency,
  isOffline as isOfflineByThreshold,
  DEFAULT_OFFLINE_THRESHOLD_MS,
} from '../ui/status';
import { getDashboardSummary, type DashboardSummary } from '../api/dashboard';
import type { DeviceView, ConfigurationLookup } from '../api/devices';

/**
 * Tablero — relación de los dispositivos configurados (fleet board).
 *
 * A single-glance board of the configured fleet: how many devices there are, how
 * they are configured (policy / agent / Android) and their health. It reuses the
 * folder-scoped device list (DevicesPage data, which already carries configuration,
 * folders, Android and agent version per device) and the server aggregates from
 * GET /private/summary/dashboard (blocked, offline, SIM, storage, app status) that
 * the device list cannot derive. No new server aggregates were needed: policy and
 * agent-version breakdowns are computed from the already-scoped device list.
 */

type Bucket = 'online' | 'attention' | 'offline';

const APP_STATUS_LABELS: Record<string, string> = {
  SUCCESS: 'Al día',
  VERSION_MISMATCH: 'Versión distinta',
  FAILURE: 'Con fallos',
};

function configName(d: DeviceView, configs: Record<string, ConfigurationLookup>): string {
  if (d.configurationId == null) return 'Sin asignar';
  return configs[String(d.configurationId)]?.name ?? 'Sin asignar';
}

function bucketOf(d: DeviceView, now: number, offlineMs: number): Bucket {
  if (isOnlineByRecency(d.lastUpdate, now, d.number)) {
    return statusMeta(d.statusCode).tone === 'warn' ? 'attention' : 'online';
  }
  if (isOfflineByThreshold(d.lastUpdate, now, offlineMs, d.number)) return 'offline';
  return 'attention';
}

function fmtBytes(b?: number): string {
  if (!b || b <= 0) return '0 GB';
  if (b >= 1024 ** 4) return `${(b / 1024 ** 4).toLocaleString('es-CO', { maximumFractionDigits: 1 })} TB`;
  if (b >= 1024 ** 3) return `${(b / 1024 ** 3).toLocaleString('es-CO', { maximumFractionDigits: 1 })} GB`;
  return `${Math.max(1, Math.round(b / 1024 ** 2)).toLocaleString('es-CO')} MB`;
}

/** Order version-ish labels descending (3.10 > 3.9 > 3.2 …); non-versions sort last by name. */
function compareVersionDesc(a: string, b: string): number {
  const pa = a.match(/\d+/g);
  const pb = b.match(/\d+/g);
  if (pa && pb) {
    const n = Math.max(pa.length, pb.length);
    for (let i = 0; i < n; i++) {
      const d = (Number(pb[i] ?? 0)) - (Number(pa[i] ?? 0));
      if (d !== 0) return d;
    }
    return 0;
  }
  if (pa) return -1;
  if (pb) return 1;
  return a.localeCompare(b);
}

interface Row {
  key: string;
  n: number;
  tone?: 'ok' | 'warn' | 'alert' | 'idle';
}

/** A labelled breakdown rendered as a mini bar chart; the widest row sets the scale. */
function Breakdown({
  title,
  rows,
  empty,
}: {
  title: string;
  rows: Row[];
  empty: string;
}) {
  const max = rows.reduce((m, r) => Math.max(m, r.n), 0) || 1;
  return (
    <section className="panel">
      <div className="panel-head">
        <h2 className="panel-title">{title}</h2>
      </div>
      <div className="ov-listpad">
        {rows.length === 0 ? (
          <div className="empty">
            <span className="label">{title}</span>
            {empty}
          </div>
        ) : (
          rows.map((r) => (
            <div className="brk-row" key={r.key}>
              <span className="brk-k" title={r.key}>
                {r.tone && <span className={`dot dot-${r.tone}`} />}
                {r.key}
              </span>
              <span className="brk-track">
                <span
                  className={`brk-fill${r.tone ? ` brk-${r.tone}` : ''}`}
                  style={{ width: `${(r.n / max) * 100}%` }}
                />
              </span>
              <span className="brk-v">{r.n}</span>
            </div>
          ))
        )}
      </div>
    </section>
  );
}

export function TableroPage() {
  useLiveDevices();
  const navigate = useNavigate();
  const { devices, total, configurations, loading, error, reload } = useDevices();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);

  const refreshSeconds = summary?.dashboardRefreshSeconds ?? 30;
  const offlineMs = summary
    ? summary.offlineThresholdDays * 24 * 60 * 60 * 1000
    : DEFAULT_OFFLINE_THRESHOLD_MS;

  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const ms = Math.max(5, refreshSeconds) * 1000;
    const t = setInterval(() => {
      setNow(Date.now());
      getDashboardSummary().then(setSummary).catch(() => undefined);
      void reload();
    }, ms);
    return () => clearInterval(t);
  }, [refreshSeconds, reload]);

  useEffect(() => {
    let cancelled = false;
    getDashboardSummary()
      .then((s) => !cancelled && setSummary(s))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const counts = useMemo(() => {
    let online = 0,
      attention = 0,
      offline = 0;
    for (const d of devices) {
      const b = bucketOf(d, now, offlineMs);
      if (b === 'online') online++;
      else if (b === 'attention') attention++;
      else offline++;
    }
    return { online, attention, offline };
  }, [devices, now, offlineMs]);

  const shown = devices.length;
  const pct = (n: number) => (shown ? `${(n / shown) * 100}%` : '0%');

  // Breakdown: by folder (a device can live in several folders; count in each).
  const byFolder = useMemo<Row[]>(() => {
    const m = new Map<string, number>();
    for (const d of devices) {
      const groups = d.groups ?? [];
      if (groups.length === 0) m.set('Sin carpeta', (m.get('Sin carpeta') ?? 0) + 1);
      else for (const g of groups) m.set(g.name, (m.get(g.name) ?? 0) + 1);
    }
    return [...m.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([key, n]) => ({ key, n }));
  }, [devices]);

  // Breakdown: by policy / configuration.
  const byConfig = useMemo<Row[]>(() => {
    const m = new Map<string, number>();
    for (const d of devices) {
      const name = configName(d, configurations);
      m.set(name, (m.get(name) ?? 0) + 1);
    }
    return [...m.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([key, n]) => ({ key, n }));
  }, [devices, configurations]);

  // Breakdown: by agent (launcher) version — the configured app version per device.
  const byAgent = useMemo<Row[]>(() => {
    const m = new Map<string, number>();
    for (const d of devices) m.set(d.launcherVersion || '—', (m.get(d.launcherVersion || '—') ?? 0) + 1);
    return [...m.entries()]
      .sort((a, b) => compareVersionDesc(a[0], b[0]))
      .map(([key, n]) => ({ key, n }));
  }, [devices]);

  // Latest agent version in the fleet and how many devices trail it (desactualizados).
  const agentOutdated = useMemo(() => {
    const versions = byAgent.map((r) => r.key).filter((v) => v && v !== '—');
    if (versions.length === 0) return { latest: null as string | null, outdated: 0 };
    const latest = [...versions].sort(compareVersionDesc)[0];
    let outdated = 0;
    for (const d of devices) {
      const v = d.launcherVersion || '';
      if (v && v !== latest) outdated++;
    }
    return { latest, outdated };
  }, [byAgent, devices]);

  // Breakdown: by Android version (prefer the server census aggregate; else derive).
  const byAndroid = useMemo<Row[]>(() => {
    if (summary?.androidVersions?.length) {
      return summary.androidVersions
        .map((c) => ({ key: c.stringAttr || '—', n: c.number }))
        .sort((a, b) => b.n - a.n);
    }
    const m = new Map<string, number>();
    for (const d of devices) m.set(d.androidVersion || '—', (m.get(d.androidVersion || '—') ?? 0) + 1);
    return [...m.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([key, n]) => ({ key, n }));
  }, [summary, devices]);

  // Breakdown: by reported status (agent colour), tone-coloured.
  const byStatus = useMemo<Row[]>(() => {
    const m = new Map<string, number>();
    for (const d of devices) m.set(d.statusCode ?? 'grey', (m.get(d.statusCode ?? 'grey') ?? 0) + 1);
    return [...m.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([code, n]) => {
        const meta = statusMeta(code);
        return { key: meta.label, n, tone: meta.tone };
      });
  }, [devices]);

  // Breakdown: app-installation compliance (from the server summary).
  const byApps = useMemo<Row[]>(() => {
    if (!summary?.appStatus?.length) return [];
    const tone: Record<string, Row['tone']> = {
      SUCCESS: 'ok',
      VERSION_MISMATCH: 'warn',
      FAILURE: 'alert',
    };
    return summary.appStatus.map((c) => ({
      key: APP_STATUS_LABELS[c.stringAttr] ?? c.stringAttr,
      n: c.number,
      tone: tone[c.stringAttr],
    }));
  }, [summary]);

  // Non-compliance: devices with apps failing or on a mismatched version.
  const nonCompliant = useMemo(() => {
    if (!summary?.appStatus?.length) return null;
    return summary.appStatus
      .filter((c) => c.stringAttr === 'FAILURE' || c.stringAttr === 'VERSION_MISMATCH')
      .reduce((s, c) => s + c.number, 0);
  }, [summary]);

  const storagePct =
    summary && summary.storage.totalBytes > 0
      ? Math.round(((summary.storage.totalBytes - summary.storage.freeBytes) / summary.storage.totalBytes) * 100)
      : null;

  const kioskCount = useMemo(() => devices.filter((d) => d.kioskMode).length, [devices]);

  return (
    <AppShell title="Tablero">
      <div className="page-head">
        <h1>Tablero</h1>
        <span className="count">Relación de los dispositivos configurados</span>
      </div>

      {error && <div className="banner banner-alert">{error}</div>}

      {/* Headline: total enrolados + the activo/atención/sin conexión/bloqueado split. */}
      <div className="panel fleet">
        <div className="fleet-big">
          {total}
          <small>{total === 1 ? 'equipo enrolado' : 'equipos enrolados'}</small>
        </div>
        <div className="fleet-barwrap">
          <div className="fleet-legend">
            <span>
              <i style={{ background: 'var(--online)' }} />
              {counts.online} activos
            </span>
            <span>
              <i style={{ background: 'var(--warn)' }} />
              {counts.attention} requiere{counts.attention === 1 ? '' : 'n'} atención
            </span>
            <span>
              <i style={{ background: 'var(--offline)' }} />
              {counts.offline} sin conexión
            </span>
            <span>
              <i style={{ background: 'var(--alert, #c0392b)' }} />
              {summary?.blocked ?? 0} bloqueado{(summary?.blocked ?? 0) === 1 ? '' : 's'}
            </span>
          </div>
          <div className="fleet-bar">
            <i style={{ background: 'var(--online)', width: pct(counts.online) }} />
            <i style={{ background: 'var(--warn)', width: pct(counts.attention) }} />
            <i style={{ background: 'var(--offline)', width: pct(counts.offline) }} />
          </div>
        </div>
      </div>

      {/* KPIs — a glance at the configured fleet's size, configuration and health. */}
      <div className="dash-tiles tablero-kpis">
        <div
          className="dash-tile"
          role="button"
          tabIndex={0}
          style={{ cursor: 'pointer' }}
          onClick={() => navigate('/devices')}
          onKeyDown={(e) => e.key === 'Enter' && navigate('/devices')}
        >
          <span className="dash-tile-v">{total}</span>
          <span className="dash-tile-k">Enrolados</span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{counts.online}</span>
          <span className="dash-tile-k">Activos ahora</span>
        </div>
        <div
          className="dash-tile"
          role="button"
          tabIndex={0}
          style={{ cursor: 'pointer' }}
          onClick={() => navigate('/devices')}
          onKeyDown={(e) => e.key === 'Enter' && navigate('/devices')}
        >
          <span className="dash-tile-v">{summary?.offline ?? counts.offline}</span>
          <span className="dash-tile-k">
            Sin conexión
            <small> &gt; {summary?.offlineThresholdDays ?? 1} día{(summary?.offlineThresholdDays ?? 1) === 1 ? '' : 's'}</small>
          </span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{summary?.blocked ?? '—'}</span>
          <span className="dash-tile-k">Bloqueados</span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{byConfig.length}</span>
          <span className="dash-tile-k">Políticas en uso</span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{kioskCount}</span>
          <span className="dash-tile-k">En modo kiosco</span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{agentOutdated.outdated}</span>
          <span className="dash-tile-k">
            Agente desactualizado
            {agentOutdated.latest && <small> última {agentOutdated.latest}</small>}
          </span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{nonCompliant ?? '—'}</span>
          <span className="dash-tile-k">Apps en incumplimiento</span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{summary?.simEventsRecent ?? '—'}</span>
          <span className="dash-tile-k">
            Movimientos de SIM
            <small> últimos {summary?.simWindowDays ?? 7} días</small>
          </span>
        </div>
        <div className="dash-tile">
          <span className="dash-tile-v">{storagePct != null ? `${storagePct} %` : '—'}</span>
          <span className="dash-tile-k">
            Almacenamiento usado
            {summary && summary.storage.totalBytes > 0 && (
              <small> {fmtBytes(summary.storage.freeBytes)} libres</small>
            )}
          </span>
        </div>
      </div>

      {loading && devices.length === 0 ? (
        <div className="empty">
          <span className="spin" /> Cargando la flota…
        </div>
      ) : (
        <div className="tablero-grid">
          <Breakdown title="Por carpeta" rows={byFolder} empty="Aún no hay dispositivos." />
          <Breakdown title="Por política" rows={byConfig} empty="Aún no hay dispositivos." />
          <Breakdown title="Por estado" rows={byStatus} empty="Sin datos todavía." />
          <Breakdown title="Por versión de agente" rows={byAgent} empty="Sin datos todavía." />
          <Breakdown title="Por versión de Android" rows={byAndroid} empty="Sin datos todavía." />
          <Breakdown title="Configuración de apps" rows={byApps} empty="Sin datos todavía." />
        </div>
      )}
    </AppShell>
  );
}
