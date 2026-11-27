import { useState } from 'react';
import type { MobileDataPolicy } from '../api/mobileData';

// "Datos móviles" section of a policy (configurations.dcPolicy.mobileData, see common MobileDataPolicy.java): which
// apps may use mobile data, and the data limits of the period. The server validates and normalises what is saved;
// this editor only builds the object.

export interface PolicyAppOption { pkg: string; name?: string }

const PKG = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

/** The object to store: undefined when the section asks for nothing. */
export function cleanMobileData(m: MobileDataPolicy | undefined): MobileDataPolicy | undefined {
  if (!m) return undefined;
  const mode = m.mode === 'blocklist' || m.mode === 'allowlist' ? m.mode : 'open';
  const hasLimits = !!m.deviceLimitMb || !!m.appLimits?.length;
  if (mode === 'open' && !hasLimits) return undefined;
  const out: MobileDataPolicy = { mode };
  if (mode !== 'open' && m.apps?.length) out.apps = m.apps;
  if (m.mechanism) out.mechanism = m.mechanism;
  if (m.deviceLimitMb) out.deviceLimitMb = m.deviceLimitMb;
  if (m.appLimits?.length) out.appLimits = m.appLimits; // rows still being typed stay; the server drops invalid ones
  if (hasLimits) {
    if (m.period === 'daily') out.period = 'daily';
    if (m.period !== 'daily' && m.cutDay && m.cutDay > 1) out.cutDay = m.cutDay;
    if (m.warnPercent && m.warnPercent !== 80) out.warnPercent = m.warnPercent;
  }
  if ((m.deviceLimitMb || mode === 'allowlist') && m.essential?.length) out.essential = m.essential;
  return out;
}

/** Packages edited one at a time: type one (or tap one of the policy's apps) and add it; remove with ✕. */
function PackageList({ label, value, disabled, options, empty, onChange }: {
  label: string; value: string[]; disabled?: boolean; options: PolicyAppOption[]; empty: string; onChange: (v: string[]) => void;
}) {
  const [draft, setDraft] = useState('');
  const fresh = draft.split(/[\s,]+/).map((x) => x.trim()).filter(Boolean);
  const invalid = fresh.filter((x) => !PKG.test(x));
  const add = () => {
    const ok = fresh.filter((x) => PKG.test(x) && !value.includes(x));
    if (ok.length) onChange([...value, ...Array.from(new Set(ok))]);
    if (!invalid.length) setDraft('');
  };
  const nameOf = (pkg: string) => options.find((o) => o.pkg === pkg)?.name;
  const offered = options.filter((o) => !value.includes(o.pkg));
  return (
    <div className="dcp-sites">
      {!disabled && (
        <div className="dcp-sites-add">
          <input className="mono" aria-label={`Agregar a ${label.toLowerCase()}`} placeholder="com.empresa.app" value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
          <button type="button" className="btn btn-sm" disabled={!draft.trim()} onClick={add}>Agregar</button>
        </div>
      )}
      {!disabled && invalid.length > 0 && <div className="field-error">No es un nombre de paquete: {invalid.join(', ')}</div>}
      {!disabled && offered.length > 0 && (
        <div className="dcp-sites-add" style={{ flexWrap: 'wrap' }} aria-label={`Apps de la política para ${label.toLowerCase()}`}>
          {offered.slice(0, 30).map((o) => (
            <button key={o.pkg} type="button" className="btn btn-sm btn-ghost" title={o.pkg} onClick={() => onChange([...value, o.pkg])}>
              + {o.name || o.pkg}
            </button>
          ))}
        </div>
      )}
      {value.length === 0 ? (
        <div className="cfg-empty">{empty}</div>
      ) : (
        <ul className="dcp-sites-list" aria-label={label}>
          {value.map((pkg) => (
            <li key={pkg}>
              <span><span className="mono">{pkg}</span>{nameOf(pkg) ? <span className="muted"> · {nameOf(pkg)}</span> : null}</span>
              {!disabled && (
                <button type="button" className="btn btn-sm btn-ghost" aria-label={`Quitar ${pkg}`} onClick={() => onChange(value.filter((x) => x !== pkg))}>✕</button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const int = (s: string, min: number, max: number): number | undefined => {
  const n = parseInt(s, 10);
  return Number.isFinite(n) && n >= min ? Math.min(n, max) : undefined;
};

export function MobileDataPolicySection({ value, disabled, apps = [], onChange }: {
  value: MobileDataPolicy | undefined; disabled?: boolean; apps?: PolicyAppOption[];
  onChange: (v: MobileDataPolicy | undefined) => void;
}) {
  const m: MobileDataPolicy = value ?? {};
  const mode = m.mode ?? 'open';
  const limits = m.appLimits ?? [];
  const hasLimits = !!m.deviceLimitMb || limits.length > 0;
  const set = (patch: Partial<MobileDataPolicy>) => onChange({ ...m, ...patch });
  const setLimit = (i: number, patch: Partial<{ pkg: string; limitMb: number }>) =>
    set({ appLimits: limits.map((x, j) => (j === i ? { ...x, ...patch } : x)) });

  return (
    <div data-testid="policy-mobile-data">
      <div className="cfg-sec-h" style={{ marginTop: 18 }}>Datos móviles</div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Datos móviles por aplicación</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Qué apps pueden gastar el plan de datos del teléfono. Las apps restringidas siguen funcionando con Wi‑Fi. El agente
            DallyControl y la conexión con la consola nunca se restringen. Requiere que el equipo esté inscrito como dueño del dispositivo.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select className="sel" aria-label="Datos móviles por aplicación" value={mode} disabled={disabled} data-testid="md-mode"
            onChange={(e) => set({ mode: e.target.value as MobileDataPolicy['mode'] })}>
            <option value="open">Sin restricción por app</option>
            <option value="blocklist">Estas apps solo con Wi‑Fi (lista negra)</option>
            <option value="allowlist">Solo estas apps usan datos móviles (lista blanca)</option>
          </select>
        </div>
      </div>

      {mode !== 'open' && (
        <div className="cfg-field">
          <div className="cfg-field-label">
            <label>{mode === 'blocklist' ? 'Apps sin datos móviles (solo Wi‑Fi)' : 'Apps que pueden usar datos móviles'}</label>
            <span className="cfg-field-help">
              {mode === 'blocklist'
                ? 'Estas apps no pueden usar datos móviles; con Wi‑Fi funcionan normal. Toca una app de la política o escribe el nombre del paquete.'
                : 'Solo estas apps (y las esenciales) usan datos móviles; todas las demás apps con ícono quedan solo con Wi‑Fi. Los servicios internos de Android y de Google no se tocan.'}
            </span>
          </div>
          <div className="cfg-field-ctl" data-testid="md-apps">
            <PackageList label={mode === 'blocklist' ? 'Apps sin datos móviles' : 'Apps con datos móviles'} value={m.apps ?? []}
              disabled={disabled} options={apps} onChange={(v) => set({ apps: v })}
              empty={mode === 'blocklist' ? 'Aún no hay apps en la lista.' : 'Lista vacía: ninguna app con ícono usa datos móviles.'} />
          </div>
        </div>
      )}

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Límite de datos del equipo</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Megabytes de datos móviles por periodo para todo el teléfono. Al llegar al umbral de aviso se genera una alerta; al superar el
            límite, todas las apps menos las esenciales quedan sin datos móviles hasta el siguiente periodo. Vacío = sin límite.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <input type="number" className="input" aria-label="Límite de datos del equipo en megabytes" min={1} placeholder="sin límite"
            style={{ width: 130 }} disabled={disabled} value={m.deviceLimitMb ?? ''} data-testid="md-device-limit"
            onChange={(e) => set({ deviceLimitMb: int(e.target.value, 1, 1048576) })} />{' '}
          <span className="muted">MB</span>
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Límite de datos por app</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Megabytes de datos móviles por periodo para una app. Al superarlo, esa app queda solo con Wi‑Fi hasta el siguiente periodo.
          </span>
        </div>
        <div className="cfg-field-ctl" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }} data-testid="md-app-limits">
          {limits.map((l, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 90px auto', gap: 6 }}>
              <input className="input mono" placeholder="com.empresa.app" list="md-policy-apps" value={l.pkg} disabled={disabled}
                aria-label={`Límite ${i + 1} paquete`} onChange={(e) => setLimit(i, { pkg: e.target.value.trim() })} />
              <input className="input" type="number" min={1} placeholder="MB" value={l.limitMb || ''} disabled={disabled}
                aria-label={`Límite ${i + 1} megabytes`} onChange={(e) => setLimit(i, { limitMb: int(e.target.value, 1, 1048576) ?? 0 })} />
              <button className="btn btn-sm btn-ghost" disabled={disabled} aria-label="Quitar límite"
                onClick={() => set({ appLimits: limits.filter((_, j) => j !== i) })}>✕</button>
            </div>
          ))}
          <datalist id="md-policy-apps">
            {apps.map((o) => <option key={o.pkg} value={o.pkg}>{o.name}</option>)}
          </datalist>
          <button className="btn btn-sm" disabled={disabled} style={{ alignSelf: 'flex-end' }}
            onClick={() => set({ appLimits: [...limits, { pkg: '', limitMb: 0 }] })}>+ Agregar límite</button>
        </div>
      </div>

      {hasLimits && (
        <>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>Periodo de los límites</label>
              <span className="cfg-field-help">
                Mensual: el consumo vuelve a cero el día de corte del plan (1 a 28). Diario: cada día a medianoche, hora del teléfono.
              </span>
            </div>
            <div className="cfg-field-ctl" style={{ gap: 8, alignItems: 'center' }}>
              <select className="sel" aria-label="Periodo de los límites" value={m.period === 'daily' ? 'daily' : 'monthly'} disabled={disabled}
                data-testid="md-period" onChange={(e) => set({ period: e.target.value as 'monthly' | 'daily' })}>
                <option value="monthly">Mensual</option>
                <option value="daily">Diario</option>
              </select>
              {m.period !== 'daily' && (
                <input type="number" className="input" aria-label="Día de corte" min={1} max={28} placeholder="1" style={{ width: 70 }}
                  title="Día de corte" disabled={disabled} value={m.cutDay ?? ''} data-testid="md-cut-day"
                  onChange={(e) => set({ cutDay: int(e.target.value, 1, 28) })} />
              )}
            </div>
          </div>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>Umbral de aviso</label>
              <span className="cfg-field-help">
                Porcentaje del límite que genera la alerta «Datos móviles: cerca del límite» (y su correo, si hay destinatarios en
                Geocercas → Notificaciones). Vacío = 80 %. Con 100 solo se avisa al superar el límite.
              </span>
            </div>
            <div className="cfg-field-ctl">
              <input type="number" className="input" aria-label="Umbral de aviso en porcentaje" min={1} max={100} placeholder="80"
                style={{ width: 100 }} disabled={disabled} value={m.warnPercent ?? ''} data-testid="md-warn"
                onChange={(e) => set({ warnPercent: int(e.target.value, 1, 100) })} />{' '}
              <span className="muted">%</span>
            </div>
          </div>
        </>
      )}

      {(!!m.deviceLimitMb || mode === 'allowlist') && (
        <div className="cfg-field">
          <div className="cfg-field-label">
            <label>Apps esenciales</label>
            <span className="cfg-field-help">
              Conservan los datos móviles aunque el equipo supere su límite (por ejemplo la app de ventas). El agente DallyControl
              siempre es esencial.
            </span>
          </div>
          <div className="cfg-field-ctl" data-testid="md-essential">
            <PackageList label="Apps esenciales" value={m.essential ?? []} disabled={disabled} options={apps}
              onChange={(v) => set({ essential: v })} empty="Ninguna app esencial." />
          </div>
        </div>
      )}

      {(mode !== 'open' || hasLimits) && (
        <div className="cfg-field">
          <div className="cfg-field-label">
            <label>Mecanismo de bloqueo</label>
            <span className="cfg-field-help">
              Automático usa la restricción de datos del propio Android (versión 9 o superior) y, en Android 7 y 8, una VPN local del
              agente que no envía nada a ningún servidor. Fuerza la VPN solo si un modelo no respeta la restricción del sistema.
            </span>
          </div>
          <div className="cfg-field-ctl">
            <select className="sel" aria-label="Mecanismo de bloqueo" value={m.mechanism ?? 'auto'} disabled={disabled} data-testid="md-mechanism"
              onChange={(e) => set({ mechanism: e.target.value === 'auto' ? undefined : (e.target.value as 'dpm' | 'vpn') })}>
              <option value="auto">Automático (recomendado)</option>
              <option value="dpm">Restricción del sistema</option>
              <option value="vpn">VPN local del agente</option>
            </select>
          </div>
        </div>
      )}
    </div>
  );
}
