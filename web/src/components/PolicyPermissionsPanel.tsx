import { parseDcPolicy, serializeDcPolicy } from './DcPolicyPanel';
import { AppPermissionsEditor } from './AppPermissionsEditor';
import type { PermissionRulesByApp } from '../data/permissions';

const DEFAULT_TEXT: Record<string, string> = {
  GRANTALL: 'Conceder todo: Android concede sin diálogo cualquier permiso que una app pida.',
  ASKALL: 'Preguntar: el usuario decide cada permiso, salvo los que fijes aquí.',
  DENYALL: 'Denegar todo: ninguna app obtiene un permiso, salvo los que concedas aquí.',
  ASKLOCATION: 'Conceder todo a las apps de la política, pero preguntar al usuario por la ubicación.',
  DENYLOCATION: 'Conceder todo, pero denegar la ubicación a las apps de la política.',
};

/**
 * Per-app permissions of a policy (dcPolicy.permissions): for each app of the policy — or any other package — fix
 * Conceder / Denegar / Preguntar per permission, the "all the app declares" preset and the special accesses. The agent
 * applies them as Device Owner on every device of the policy: when the policy is applied, when the app is installed
 * and on every agent start.
 */
export function PolicyPermissionsPanel({ value, apps, defaultOption, disabled, onChange }: {
  /** The policy's raw dcPolicy JSON. */
  value: unknown;
  /** The policy's apps (installed ones). */
  apps: { pkg?: string; name?: string; action?: number }[];
  /** The policy's "Permisos de apps" option (configurations.appPermissions). */
  defaultOption?: string;
  disabled?: boolean;
  onChange: (dcPolicy: string | null) => void;
}) {
  const dc = parseDcPolicy(value);
  const rules: PermissionRulesByApp = dc.permissions ?? {};
  const list = apps.filter((a) => a.pkg && a.action !== 0 && a.action !== 2).map((a) => ({ pkg: a.pkg as string, name: a.name }));
  return (
    <section className="panel cfg-panel" data-testid="policy-permissions">
      <div className="cfg-sec-h">Permisos por app <span className="chip chip-enforced">Aplicado</span></div>
      <p className="cfg-legacy-note">
        Sin intervención del usuario: el agente concede o deniega cada permiso como propietario del dispositivo al aplicar la
        política, al instalar la app y en cada arranque. «Conceder» y «Denegar» quedan fijados (el usuario no puede cambiarlos).
        Política por defecto (campo «Permisos de apps», en Seguridad): <b>{DEFAULT_TEXT[defaultOption ?? 'GRANTALL'] ?? DEFAULT_TEXT.GRANTALL}</b>{' '}
        Cada equipo puede tener además sus propias reglas (Dispositivo → Permisos), donde también se ve el estado real.
      </p>
      <AppPermissionsEditor apps={list} value={rules} disabled={disabled} testid="policy-perm-editor"
        onChange={(next) => onChange(serializeDcPolicy({ ...dc, permissions: Object.keys(next).length ? next : undefined }))} />
    </section>
  );
}
