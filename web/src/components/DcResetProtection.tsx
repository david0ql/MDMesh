import { useEffect, useState } from 'react';
import type { DcPolicy } from './DcPolicyPanel';
import { FrpAccountList, FrpDefaultsDialog } from './FrpAccounts';
import { getFrpDefaults, type FrpAccount, type FrpDefaults } from '../api/protection';

type Mode = 'inherit' | 'own' | 'off';

/**
 * The policy's protection against a factory reset and other ways around the management:
 *  - which company accounts may reactivate the phone after a reset by buttons (the company's / folder's, the
 *    policy's own, or none), with a clear warning when the reset is blocked and no account would apply;
 *  - safe mode, USB debugging, bootloader unlock, and wipe after N wrong unlocks.
 * Rendered inside the "Restablecimiento de fábrica" field of the policy panel.
 */
export function DcResetProtection({ p, update, disabled }: {
  p: DcPolicy; update: (next: DcPolicy) => void; disabled?: boolean;
}) {
  const d = p.device ?? {};
  const own: FrpAccount[] = (d.frpAccounts ?? []).map((id) => ({ id, email: p.frpLabels?.[id] ?? null }));
  const [ownOpen, setOwnOpen] = useState(own.length > 0);
  const [defaults, setDefaults] = useState<FrpDefaults | null>(null);
  const [dialog, setDialog] = useState(false);
  const mode: Mode = d.frpMode === 'off' ? 'off' : own.length > 0 || ownOpen ? 'own' : 'inherit';

  const load = () => { void getFrpDefaults().then(setDefaults).catch(() => setDefaults(null)); };
  useEffect(load, []);

  const setDevice = (patch: Partial<NonNullable<DcPolicy['device']>>, labels?: Record<string, string>) =>
    update({ ...p, device: { ...d, ...patch }, frpLabels: labels ?? p.frpLabels });

  function setMode(next: Mode) {
    setOwnOpen(next === 'own');
    if (next === 'off') setDevice({ frpMode: 'off', frpAccounts: undefined }, {});
    else if (next === 'inherit') setDevice({ frpMode: undefined, frpAccounts: undefined }, {});
    else setDevice({ frpMode: undefined });
  }

  function setOwn(list: FrpAccount[]) {
    const labels: Record<string, string> = {};
    list.forEach((a) => { if (a.email) labels[a.id] = a.email; });
    setDevice({ frpMode: undefined, frpAccounts: list.length ? list.map((a) => a.id) : undefined }, labels);
  }

  const companyCount = defaults?.company.length ?? 0;
  const foldersWithOwn = defaults ? Object.keys(defaults.folders).length : 0;
  const blocked = d.factoryReset === 'block' || !!p.powerPin;
  // Nothing would apply: the policy has none of its own and neither the company nor any folder has defaults.
  const unprotected = mode === 'off' || (mode === 'own' && own.length === 0)
    || (mode === 'inherit' && defaults !== null && companyCount === 0 && foldersWithOwn === 0);
  const partly = mode === 'inherit' && defaults !== null && companyCount === 0 && foldersWithOwn > 0;

  const block = (key: 'safeBoot' | 'oemUnlock', label: string, help: string) => (
    <label className="frp-rule" title={help}>
      <input type="checkbox" disabled={disabled} checked={d[key] === 'block'} aria-label={label}
        onChange={(e) => setDevice({ [key]: e.target.checked ? 'block' : undefined })} />
      <span><strong>{label}</strong><span className="muted small"> — {help}</span></span>
    </label>
  );

  return (
    <div className="frp-policy" data-testid="reset-protection">
      <label className="frp-mode">
        <span>Reactivación tras un restablecimiento por botones (FRP)</span>
        <select className="sel" aria-label="Cuentas de reactivación" disabled={disabled} value={mode}
          onChange={(e) => setMode(e.target.value as Mode)}>
          <option value="inherit">Las cuentas de la empresa o de la carpeta del equipo</option>
          <option value="own">Cuentas propias de esta política</option>
          <option value="off">Sin protección (cualquiera podría reactivarlo)</option>
        </select>
      </label>

      {mode === 'inherit' && (
        <div className="frp-inherit">
          {defaults === null ? <span className="muted small">Leyendo las cuentas de la empresa…</span> : (
            <span className="small">
              {companyCount > 0
                ? <span key="some">Empresa: {defaults.company.map((a) => a.email || a.id).join(', ')}.</span>
                : <span key="none">La empresa aún no tiene cuentas.</span>}
              {foldersWithOwn > 0 && <span> {foldersWithOwn} {foldersWithOwn === 1 ? 'carpeta tiene' : 'carpetas tienen'} cuentas propias.</span>}
            </span>
          )}
          <button type="button" className="btn btn-sm" onClick={() => setDialog(true)}>Administrar cuentas de la empresa y carpetas</button>
        </div>
      )}
      {mode === 'own' && (
        <FrpAccountList value={own} disabled={disabled} onChange={setOwn}
          empty="Aún no hay cuentas en esta política. Agrega al menos una, o usa las de la empresa." />
      )}

      {unprotected && blocked && (
        <p className="frp-warning" role="alert">
          <strong>El restablecimiento desde Ajustes está bloqueado, pero no hay cuentas de reactivación.</strong>{' '}
          Si alguien restablece el teléfono con los botones (recovery), quedará libre: lo podrá usar con cualquier cuenta.
          Agrega la cuenta corporativa para que solo la empresa pueda reactivarlo.
        </p>
      )}
      {unprotected && !blocked && (
        <p className="frp-note muted small">
          Sin cuentas de reactivación, un equipo restablecido por botones (recovery) queda libre para quien lo tenga.
        </p>
      )}
      {partly && (
        <p className="frp-note muted small">
          La empresa no tiene cuentas: solo quedan protegidos los equipos de las carpetas que tienen las suyas.
        </p>
      )}

      <div className="frp-rules">
        <span className="frp-rules-title">Otras vías que también se pueden cerrar</span>
        {block('safeBoot', 'Bloquear el modo seguro', 'impide arrancar sin las apps de gestión.')}
        {block('oemUnlock', 'Bloquear el desbloqueo del bootloader (OEM)', 'impide instalar otro sistema por cable. En Android 9 o posterior va unido al bloqueo desde Ajustes.')}
        <span className="muted small">La depuración USB (adb) se bloquea en el campo «Depuración USB» de esta misma política.</span>
        <label className="frp-rule">
          <span><strong>Borrar tras intentos fallidos de desbloqueo</strong>
            <span className="muted small"> — el equipo se restablece solo (conserva la protección). Vacío = nunca.</span></span>
          <input className="input frp-attempts" type="number" min={3} max={100} placeholder="nunca" disabled={disabled}
            aria-label="Intentos fallidos antes de borrar" value={d.maxFailedPasswordsForWipe ?? ''}
            onChange={(e) => {
              const n = Number.parseInt(e.target.value, 10);
              setDevice({ maxFailedPasswordsForWipe: Number.isFinite(n) && n > 0 ? Math.min(100, n) : undefined });
            }} />
        </label>
        {d.maxFailedPasswordsForWipe !== undefined && d.maxFailedPasswordsForWipe < 3 && (
          <p className="field-error">El mínimo es 3 intentos: con menos, un error al escribir borraría el equipo.</p>
        )}
      </div>

      {dialog && <FrpDefaultsDialog onClose={(changed) => { setDialog(false); if (changed) load(); }} />}
    </div>
  );
}
