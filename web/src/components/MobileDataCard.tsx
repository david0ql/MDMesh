import { useCallback, useEffect, useState } from 'react';
import {
  fmtDataBytes, getDeviceMobileData, setDeviceMobileData, MECHANISM_LABEL, REASON_LABEL,
  type DeviceMobileData, type MobileDataOverride,
} from '../api/mobileData';
import { useToast } from '../ui/toast';
import { useAuth } from '../auth/AuthContext';

type Device = { number: string };

const PKG = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const MODE_LABEL: Record<string, string> = {
  open: 'Sin lista (solo límites)',
  blocklist: 'Lista negra: las apps listadas solo con Wi‑Fi',
  allowlist: 'Lista blanca: solo las apps listadas usan datos móviles',
};
const fmtDay = (k: string) => new Date(`${k}T12:00:00`).toLocaleDateString('es-CO', { day: 'numeric', month: 'short', year: 'numeric' });
const ago = (ts: number | null | undefined) => {
  if (!ts) return 'nunca';
  const m = Math.round((Date.now() - ts) / 60000);
  return m < 1 ? 'hace un momento' : m < 60 ? `hace ${m} min` : m < 1440 ? `hace ${Math.floor(m / 60)} h` : `hace ${Math.floor(m / 1440)} d`;
};
const packages = (s: string) => Array.from(new Set(s.split(/[\s,]+/).map((x) => x.trim()).filter(Boolean)));

/**
 * Mobile data of one device: what its policy asks, what the phone is enforcing (mechanism, apps without mobile data),
 * the consumption of the period against the limits, and the device's own override on top of the policy.
 */
export function MobileDataCard({ device }: { device: Device }) {
  const toast = useToast();
  const authUser = useAuth().user;
  const canOperate = !!authUser && (authUser.superAdmin || authUser.userRole?.superAdmin
    || (authUser.userRole?.permissions ?? []).some((p) => p.name === 'edit_devices'));
  const [data, setData] = useState<DeviceMobileData | null>(null);
  const [failed, setFailed] = useState(false);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<'policy' | 'unrestricted'>('policy');
  const [allow, setAllow] = useState('');
  const [block, setBlock] = useState('');
  const [ignoreLimits, setIgnoreLimits] = useState(false);

  const load = useCallback(async () => {
    try {
      setData(await getDeviceMobileData(device.number));
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, [device.number]);

  useEffect(() => {
    void load();
    const t = setInterval(() => { void load(); }, 30000);
    return () => clearInterval(t);
  }, [load]);

  const openEditor = () => {
    const o = data?.override;
    setMode(o?.mode === 'unrestricted' ? 'unrestricted' : 'policy');
    setAllow((o?.allow ?? []).join('\n'));
    setBlock((o?.block ?? []).join('\n'));
    setIgnoreLimits(o?.ignoreLimits === true);
    setEditing(true);
  };

  const invalid = [...packages(allow), ...packages(block)].filter((p) => !PKG.test(p));

  async function save(next: MobileDataOverride) {
    setBusy(true);
    try {
      setData(await setDeviceMobileData(device.number, next));
      setEditing(false);
      toast.push('ok', 'Datos móviles del equipo actualizados', 'El teléfono lo aplica al conectarse (segundos si está en línea).');
    } catch (e) {
      toast.push('err', 'No se pudo guardar el ajuste de datos móviles', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (failed && !data) return null;
  if (!data) return <div className="panel md-card" data-testid="mobile-data-card"><div className="muted">Cargando datos móviles…</div></div>;

  const { policy, override, effective, status } = data;
  const managed = !!effective || !!status;
  const blocked = status?.blocked ?? [];
  const blockedCount = status?.blockedCount ?? blocked.length;
  const overrideText = !override ? null
    : override.mode === 'unrestricted' ? 'Este equipo no tiene restricción de datos móviles (ajuste del equipo).'
    : [
      override.allow?.length ? `siempre con datos: ${override.allow.join(', ')}` : '',
      override.block?.length ? `nunca con datos: ${override.block.join(', ')}` : '',
      override.ignoreLimits ? 'sin límites' : '',
    ].filter(Boolean).join(' · ');

  return (
    <section className="panel md-card" data-testid="mobile-data-card">
      <div className="md-head">
        <h3>Datos móviles</h3>
        <span className="muted">
          Periodo {data.period.type === 'daily' ? 'diario' : 'mensual'}: desde {fmtDay(data.period.start)} · se reinicia el {fmtDay(data.period.end)}
        </span>
      </div>

      {!managed && !override && (
        <p className="muted" data-testid="md-unmanaged">
          La política de este equipo{data.configurationName ? ` («${data.configurationName}»)` : ''} no restringe los datos móviles ni define límites.
        </p>
      )}

      {(effective || override) && (
        <dl className="md-facts">
          <div><dt>Política</dt><dd>{policy ? MODE_LABEL[policy.mode ?? 'open'] : 'Sin sección de datos móviles'}</dd></div>
          {overrideText && <div data-testid="md-override"><dt>Ajuste del equipo</dt><dd>{overrideText}</dd></div>}
          <div data-testid="md-mechanism-state">
            <dt>Mecanismo en el teléfono</dt>
            <dd>
              {!status ? (effective ? 'El teléfono aún no lo ha reportado (sin conexión o agente anterior a esta función).' : 'Sin restricción')
                : <>{MECHANISM_LABEL[status.mechanism] ?? status.mechanism}
                  {status.mechanism === 'vpn' && (status.vpnActive ? ' · bloqueando ahora (red móvil)' : ' · en espera (Wi‑Fi)')}
                  <span className="muted"> · reportado {ago(data.statusAt)}</span></>}
            </dd>
          </div>
          {status?.problem && <div data-testid="md-problem"><dt>Problema</dt><dd className="field-error">{status.problem}</dd></div>}
        </dl>
      )}

      <table className="md-table" data-testid="md-usage">
        <thead><tr><th>Consumo de datos móviles</th><th>Usado</th><th>Límite</th><th>Estado</th></tr></thead>
        <tbody>
          {data.rows.map((r) => (
            <tr key={r.scope + (r.pkg ?? '')} data-level={r.level ?? 'none'}>
              <td>
                <strong>{r.label}</strong>{r.pkg && r.label !== r.pkg ? <span className="muted mono"> {r.pkg}</span> : null}
                {r.limitbytes ? (
                  <div className="md-meter" role="img" aria-label={`${r.percent ?? 0} % del límite`}>
                    <i className={`md-fill md-${r.level ?? 'ok'}`} style={{ width: `${Math.min(100, r.percent ?? 0)}%` }} />
                  </div>
                ) : null}
              </td>
              <td>{fmtDataBytes(r.usedbytes)}</td>
              <td>{r.limitbytes ? `${fmtDataBytes(r.limitbytes)} (${r.percent ?? 0} %)` : '—'}</td>
              <td><span className={`md-level md-${r.level ?? 'none'}`}>{r.estado}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted md-note">
        {data.source === 'agent'
          ? 'Consumo leído por el teléfono de los contadores de Android para este periodo.'
          : 'Consumo sumado de los reportes diarios del teléfono (aún no hay una lectura del periodo hecha por el agente).'}
      </p>

      {blockedCount > 0 && (
        <div data-testid="md-blocked">
          <h4>Apps sin datos móviles ahora ({blockedCount})</h4>
          <ul className="md-blocked">
            {blocked.slice(0, 40).map((b) => {
              const label = status?.apps?.find((a) => a.pkg === b.pkg)?.label;
              return <li key={b.pkg}><span className="mono">{b.pkg}</span>{label ? <span className="muted"> · {label}</span> : null}
                <span className="chip">{REASON_LABEL[b.reason] ?? b.reason}</span></li>;
            })}
          </ul>
          {blockedCount > 40 && <p className="muted">… y {blockedCount - 40} más.</p>}
        </div>
      )}

      {data.apps.length > 0 && (
        <div data-testid="md-top">
          <h4>Apps que más datos móviles usaron en el periodo</h4>
          <ul className="md-top">
            {data.apps.slice(0, 8).map((a) => (
              <li key={a.pkg}><span>{a.label || a.pkg}</span><b>{fmtDataBytes(a.bytes)}</b></li>
            ))}
          </ul>
        </div>
      )}

      {canOperate && !editing && (
        <div className="md-actions">
          <button className="btn btn-sm" onClick={openEditor} data-testid="md-edit">Ajustar en este equipo</button>
          {override && (
            <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => void save({ mode: 'policy' })} data-testid="md-reset">
              Volver a la política
            </button>
          )}
        </div>
      )}

      {canOperate && editing && (
        <div className="md-editor" data-testid="md-editor">
          <h4>Ajuste de datos móviles de este equipo</h4>
          <p className="muted">Lo que pongas aquí gana sobre la política, solo en este teléfono.</p>
          <label className="md-radio">
            <input type="radio" name="md-mode" checked={mode === 'policy'} onChange={() => setMode('policy')} /> Seguir la política, con estos cambios
          </label>
          <label className="md-radio">
            <input type="radio" name="md-mode" checked={mode === 'unrestricted'} onChange={() => setMode('unrestricted')} data-testid="md-unrestricted" />
            {' '}Sin restricción de datos móviles en este equipo (ignora listas y límites)
          </label>
          {mode === 'policy' && (
            <>
              <label className="md-field">
                <span>Apps que siempre tienen datos móviles aquí (un paquete por línea)</span>
                <textarea className="input mono" rows={2} value={allow} onChange={(e) => setAllow(e.target.value)} data-testid="md-allow"
                  placeholder="com.empresa.ventas" />
              </label>
              <label className="md-field">
                <span>Apps que nunca tienen datos móviles aquí (un paquete por línea)</span>
                <textarea className="input mono" rows={2} value={block} onChange={(e) => setBlock(e.target.value)} data-testid="md-block"
                  placeholder="com.google.android.youtube" />
              </label>
              <label className="md-radio">
                <input type="checkbox" className="dev-check" checked={ignoreLimits} onChange={(e) => setIgnoreLimits(e.target.checked)} data-testid="md-ignore-limits" />
                {' '}No aplicar los límites de datos en este equipo (por ejemplo, si se le compró más plan)
              </label>
              {invalid.length > 0 && <div className="field-error">No es un nombre de paquete: {invalid.join(', ')}</div>}
            </>
          )}
          <div className="md-actions">
            <button className="btn btn-sm btn-primary" disabled={busy || (mode === 'policy' && invalid.length > 0)} data-testid="md-save"
              onClick={() => void save(mode === 'unrestricted' ? { mode: 'unrestricted' }
                : { mode: 'policy', allow: packages(allow), block: packages(block), ignoreLimits })}>
              Guardar
            </button>
            <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => setEditing(false)}>Cancelar</button>
          </div>
        </div>
      )}
    </section>
  );
}
