import { parseDcPolicy, serializeDcPolicy, type DcPolicy } from './DcPolicyPanel';
import {
  FREE_RESTRICTIONS, HARDWARE_GROUPS, HARDWARE_RULES, parseRestrictions, sinceLabel, androidName,
  type HardwareRuleInfo, type HardwareValue,
} from '../data/hardwareRules';

type Draft = Record<string, unknown>;
type Choice = HardwareValue | '';

/**
 * "Hardware y puertos" of a policy: one Permitir / Bloquear / Sin gestionar choice per device function, plus the
 * validated list of extra Android restrictions. Most rules live in dcPolicy.hardware; screenshots, USB storage,
 * Bluetooth and data sharing are the policy's older fields, shown here too so the whole set is in one place.
 */
export function HardwarePolicyPanel({ draft, disabled, set }: { draft: Draft; disabled?: boolean; set: (key: string, value: unknown) => void }) {
  const dc = parseDcPolicy(draft.dcPolicy);
  const setDc = (next: DcPolicy) => set('dcPolicy', serializeDcPolicy(next));

  const read = (r: HardwareRuleInfo): Choice => {
    switch (r.key) {
      // The policy stores "disable screenshots": true = blocked.
      case 'screenshots': return draft.disableScreenshots === true ? 'block' : draft.disableScreenshots === false ? 'allow' : '';
      case 'usbStorage': return draft.usbStorage === false ? 'block' : draft.usbStorage === true ? 'allow' : '';
      case 'bluetooth': return draft.bluetooth === false ? 'block' : draft.bluetooth === true ? 'allow' : '';
      case 'tethering': return dc.device?.tethering === 'block' ? 'block' : '';
      default: return dc.hardware?.[r.key] ?? '';
    }
  };

  const write = (r: HardwareRuleInfo, v: Choice) => {
    switch (r.key) {
      case 'screenshots': set('disableScreenshots', v === '' ? null : v === 'block'); return;
      case 'usbStorage': set('usbStorage', v === '' ? null : v === 'allow'); return;
      case 'bluetooth': set('bluetooth', v === '' ? null : v === 'allow'); return;
      case 'tethering': setDc({ ...dc, device: { ...(dc.device ?? {}), tethering: v === 'block' ? 'block' : undefined } }); return;
      default: {
        const next = { ...(dc.hardware ?? {}) };
        if (v) next[r.key] = v; else delete next[r.key];
        setDc({ ...dc, hardware: next });
      }
    }
  };

  const cameraInKiosk = !!draft.kioskMode && (dc.kioskRoles ?? []).includes('camera');
  const free = parseRestrictions(draft.restrictions);
  const blocked = HARDWARE_RULES.filter((r) => read(r) === 'block').length;

  return (
    <section className="panel cfg-panel" data-testid="hardware-policy">
      <div className="cfg-sec-h">Hardware y puertos</div>
      <p className="muted" style={{ marginTop: 0 }}>
        «Bloquear» apaga la función en todos los equipos de la política; «Permitir» la deja libre y quita un bloqueo anterior;
        «Sin gestionar» no la toca. Cada equipo puede tener su propia excepción (ficha del equipo → Restricciones), y allí se ve
        qué quedó aplicado de verdad. {blocked > 0 && <b data-testid="hardware-blocked-count">{blocked} bloqueadas.</b>}
      </p>

      {HARDWARE_GROUPS.map((g) => (
        <div key={g}>
          <div className="hw-group">{g}</div>
          {HARDWARE_RULES.filter((r) => r.group === g).map((r) => {
            const v = read(r);
            const since = sinceLabel(r.minSdk);
            return (
              <div className="cfg-field" key={r.key} data-testid={`hw-rule-${r.key}`}>
                <div className="cfg-field-label">
                  <label htmlFor={`hw-${r.key}`}>{r.label}</label>
                  <span className="chip chip-enforced">Aplicado</span>
                  {since && <span className="chip hw-since" title={`En equipos con un Android anterior se reporta «no soportado».`}>{since}</span>}
                  <span className="cfg-field-help">{r.help}</span>
                  {v === 'block' && r.warn && <span className="cfg-field-help hw-warn" role="note">⚠ {r.warn}</span>}
                  {r.key === 'camera' && v === 'block' && cameraInKiosk && (
                    <span className="cfg-field-help hw-warn" role="alert" data-testid="hw-camera-kiosk-warning">
                      ⚠ El quiosco de esta política tiene la función «Cámara»: con la cámara bloqueada su ícono deja de mostrarse, y las
                      apps del quiosco que la usan (escanear códigos, fotos) verán el aviso «cámara desactivada». Quita el bloqueo o la
                      función si esas apps la necesitan.
                    </span>
                  )}
                </div>
                <div className="cfg-field-ctl">
                  <select id={`hw-${r.key}`} className="sel" disabled={disabled} value={v} onChange={(e) => write(r, e.target.value as Choice)}>
                    {r.key === 'tethering'
                      ? <option value="">Permitir (ícono en el quiosco)</option>
                      : <><option value="">Sin gestionar</option><option value="allow">Permitir</option></>}
                    <option value="block">Bloquear</option>
                  </select>
                </div>
              </div>
            );
          })}
        </div>
      ))}

      <div className="hw-group">Otras restricciones de Android</div>
      <div className="cfg-field" data-testid="hw-free">
        <div className="cfg-field-label">
          <label htmlFor="hw-free-input">Restricciones de Android</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Restricciones de usuario adicionales, separadas por coma. Se aceptan por nombre (<span className="mono">DISALLOW_SMS</span>) o por
            valor (<span className="mono">no_sms</span>) y solo las de la lista: lo que no esté en ella no se envía a los equipos.
          </span>
          {free.accepted.length > 0 && (
            <span className="cfg-field-help" data-testid="hw-free-accepted">
              Se aplican: {free.accepted.map((k) => {
                const info = FREE_RESTRICTIONS.find((r) => r.value === k);
                return <span key={k} className="chip" title={`${info?.label ?? k} · desde ${androidName(info?.minSdk ?? 21)}`}>{k}</span>;
              })}
            </span>
          )}
          {free.unknown.length > 0 && (
            <span className="cfg-field-help hw-warn" role="alert" data-testid="hw-free-unknown">
              ⚠ No están en la lista y no se envían: {free.unknown.map((k) => <span key={k} className="mono">{k} </span>)}
            </span>
          )}
          {free.ownOption.length > 0 && (
            <span className="cfg-field-help hw-warn" role="alert" data-testid="hw-free-own">
              ⚠ Tienen su propia opción arriba (úsala en vez de escribirlas aquí): {free.ownOption.map((k) => <span key={k} className="mono">{k} </span>)}
            </span>
          )}
          <details className="hw-free-list">
            <summary>Ver la lista aceptada ({FREE_RESTRICTIONS.length})</summary>
            <ul>
              {FREE_RESTRICTIONS.map((r) => (
                <li key={r.value}>
                  <button type="button" className="btn btn-sm btn-ghost mono" disabled={disabled || free.accepted.includes(r.value)}
                    onClick={() => set('restrictions', [...free.accepted, ...free.unknown, ...free.ownOption, r.value].join(','))}>
                    + {r.value}
                  </button>{' '}
                  {r.label} <span className="muted">· {androidName(r.minSdk)}+</span>
                </li>
              ))}
            </ul>
          </details>
        </div>
        <div className="cfg-field-ctl">
          <textarea id="hw-free-input" className="input mono" rows={3} disabled={disabled} placeholder="no_sms, no_safe_boot"
            value={typeof draft.restrictions === 'string' ? draft.restrictions : ''}
            onChange={(e) => set('restrictions', e.target.value || null)} />
        </div>
      </div>
    </section>
  );
}
