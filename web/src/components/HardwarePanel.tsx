import { useCallback, useEffect, useState } from 'react';
import { getDeviceHardware, setDeviceHardware, type HardwareItem, type HardwareStatus } from '../api/hardware';
import {
  FREE_RESTRICTIONS, HARDWARE_BY_KEY, HARDWARE_GROUPS, HARDWARE_RULES, describeOutcome, sinceLabel, androidName,
  type HardwareValue,
} from '../data/hardwareRules';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../ui/toast';
import { fmtRelative } from '../ui/format';

type Device = { number: string };

const VALUE_LABEL: Record<string, string> = { allow: 'Permitir', block: 'Bloquear' };

/**
 * Device tab "Restricciones": for every hardware / port rule, what the policy asks, this device's own exception, and
 * what the agent reported as really applied (or why not). The exception wins over the policy and is how a blocked
 * port is opened again for one phone (for example USB debugging for a support session).
 */
export function HardwarePanel({ device }: { device: Device }) {
  const toast = useToast();
  const authUser = useAuth().user;
  const canEdit = !!authUser && (authUser.superAdmin || authUser.userRole?.superAdmin
    || (authUser.userRole?.permissions ?? []).some((p) => p.name === 'edit_devices'));
  const [status, setStatus] = useState<HardwareStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [onlyManaged, setOnlyManaged] = useState(false);

  const read = useCallback(async () => {
    try {
      setStatus(await getDeviceHardware(device.number));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo leer el estado.');
    }
  }, [device.number]);

  useEffect(() => { void read(); }, [read]);

  // While the phone has not reported on the current policy, look again every few seconds.
  const pending = !!status && !status.reported && [...status.items, ...status.restrictions].some((i) => i.outcome === 'pending');
  useEffect(() => {
    if (!pending) return undefined;
    const t = window.setInterval(() => { void read(); }, 5000);
    return () => window.clearInterval(t);
  }, [pending, read]);

  async function override(key: string, value: HardwareValue | 'policy') {
    setBusy(key);
    try {
      setStatus(await setDeviceHardware(device.number, { [key]: value }));
      toast.push('ok', 'Excepción del equipo guardada',
        value === 'policy' ? 'Vuelve a seguir la política.' : `${HARDWARE_BY_KEY[key]?.label ?? key}: ${VALUE_LABEL[value].toLowerCase()} en este equipo.`);
    } catch (e) {
      toast.push('err', 'No se pudo guardar la excepción', e instanceof Error ? e.message : '');
    } finally {
      setBusy(null);
    }
  }

  if (error && !status) return <div className="banner banner-alert" role="alert">No se pudo leer el estado de las restricciones: {error}</div>;
  if (!status) return <div className="muted">Cargando…</div>;

  const byKey: Record<string, HardwareItem> = Object.fromEntries(status.items.map((i) => [i.key, i]));
  const managed = status.items.filter((i) => i.desired).length + status.restrictions.length;
  const problems = [...status.items, ...status.restrictions].filter((i) => i.outcome && i.outcome !== 'applied' && i.outcome !== 'pending');

  return (
    <div data-testid="hardware-panel">
      <div className="hw-head">
        <div>
          <h3 style={{ margin: 0 }}>Restricciones aplicadas</h3>
          <div className="muted small" data-testid="hardware-summary">
            {managed === 0 ? 'La política de este equipo no gestiona hardware ni puertos.'
              : !status.reported ? `${managed} gestionadas · esperando el reporte del equipo…`
              : `${managed} gestionadas · ${problems.length === 0 ? 'todas aplicadas' : `${problems.length} sin aplicar`}`
                + (status.reportedAt ? ` · reporte del equipo ${fmtRelative(status.reportedAt)}` : '')}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <label className="small"><input type="checkbox" className="dev-check" checked={onlyManaged} onChange={(e) => setOnlyManaged(e.target.checked)} /> Solo gestionadas</label>
          <button className="btn btn-sm" onClick={() => void read()}>Actualizar</button>
        </div>
      </div>
      <p className="muted small">
        «Este equipo» es una excepción que gana sobre la política: sirve, por ejemplo, para abrir la depuración USB de un solo
        teléfono durante un soporte y volver a cerrarla con «Según política». El cambio llega al equipo en su siguiente conexión.
      </p>

      {HARDWARE_GROUPS.map((g) => {
        const rows = HARDWARE_RULES.filter((r) => r.group === g && byKey[r.key] && (!onlyManaged || byKey[r.key].desired));
        if (rows.length === 0) return null;
        return (
          <table className="table hw-table" key={g}>
            <thead>
              <tr><th>{g}</th><th>Política</th><th>Este equipo</th><th>Resultado en el equipo</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const i = byKey[r.key];
                const o = describeOutcome(i.outcome, i.desired);
                const since = sinceLabel(r.minSdk);
                return (
                  <tr key={r.key} data-testid={`hw-row-${r.key}`} data-outcome={i.outcome ?? ''} data-desired={i.desired ?? ''}>
                    <td>
                      <div>{r.label}</div>
                      <div className="muted small">{since ? `${since} · ` : ''}{r.help}</div>
                    </td>
                    <td>{i.policy ? VALUE_LABEL[i.policy] : <span className="muted">Sin gestionar</span>}</td>
                    <td>
                      {i.kind === 'device' ? (
                        <span className="muted small">Se cambia en Control → Compartir datos</span>
                      ) : (
                        <select className="sel" aria-label={`Excepción de ${r.label}`} disabled={!canEdit || busy === r.key}
                          value={i.override ?? 'policy'} onChange={(e) => void override(r.key, e.target.value as HardwareValue | 'policy')}>
                          <option value="policy">Según política</option>
                          <option value="allow">Permitir</option>
                          <option value="block">Bloquear</option>
                        </select>
                      )}
                    </td>
                    <td>
                      <span className={`chip tone-${o.tone}`}>{o.label}</span>
                      {o.detail && <div className="muted small">{o.detail}</div>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        );
      })}

      {(status.restrictions.length > 0 || status.unknownRestrictions.length > 0 || status.ownOptionRestrictions.length > 0) && (
        <table className="table hw-table" data-testid="hw-restrictions">
          <thead>
            <tr><th>Otras restricciones de Android</th><th>Política</th><th>Resultado en el equipo</th></tr>
          </thead>
          <tbody>
            {status.restrictions.map((i) => {
              const info = FREE_RESTRICTIONS.find((r) => r.value === i.key);
              const o = describeOutcome(i.outcome, 'block');
              return (
                <tr key={i.key} data-testid={`hw-restriction-${i.key}`} data-outcome={i.outcome ?? ''}>
                  <td>
                    <div>{info?.label ?? i.key}</div>
                    <div className="muted small mono">{i.key} · {androidName(i.minSdk)}+</div>
                  </td>
                  <td>Bloquear</td>
                  <td>
                    <span className={`chip tone-${o.tone}`}>{o.label}</span>
                    {o.detail && <div className="muted small">{o.detail}</div>}
                  </td>
                </tr>
              );
            })}
            {status.unknownRestrictions.map((k) => (
              <tr key={`u-${k}`}>
                <td className="mono">{k}</td><td>—</td>
                <td><span className="chip tone-warn">No se envía</span><div className="muted small">No está en la lista de restricciones aceptadas.</div></td>
              </tr>
            ))}
            {status.ownOptionRestrictions.map((k) => (
              <tr key={`o-${k}`}>
                <td className="mono">{k}</td><td>—</td>
                <td><span className="chip tone-warn">No se envía</span><div className="muted small">Tiene su propia opción en «Hardware y puertos».</div></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
