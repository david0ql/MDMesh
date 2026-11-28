import { useState } from 'react';
import {
  PERMISSION_GROUPS, RULE_LABEL, SPECIAL_ACCESS, SPECIAL_STATE_LABEL, cleanRules, ruleFor,
  type AppPermissionRules, type PermissionRule, type PermissionRulesByApp,
} from '../data/permissions';
import type { AppPermissionState, PermissionState } from '../api/devicePermissions';

// Per app and per permission: Conceder / Denegar / Preguntar, the "all the app declares" preset and the special
// accesses. Used in a policy (rules for every device of the policy) and on a device (rules on top of its policy,
// with the real state its agent reported next to each row).

export interface EditorApp { pkg: string; name?: string }

const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

/** The real state of one permission group of an app, from the agent's report. */
export function groupState(perms: PermissionState[] | undefined, group: string): { text: string; tone: 'ok' | 'warn' | 'alert' | 'muted' } {
  const mine = (perms ?? []).filter((p) => p.group === group);
  if (mine.length === 0) return { text: 'La app no lo declara', tone: 'muted' };
  const granted = mine.filter((p) => p.granted).length;
  const fixed = mine.some((p) => p.fixed === 'granted' || p.fixed === 'denied');
  const blocked = mine.some((p) => p.note === 'sensorsBlocked');
  const lock = fixed ? ' · fijado por política' : '';
  if (blocked && granted < mine.length) return { text: 'Sin conceder: este equipo no deja al administrador concederlo', tone: 'alert' };
  if (granted === mine.length) return { text: `Concedido${lock}`, tone: 'ok' };
  if (granted === 0) return { text: `Sin conceder${lock}`, tone: fixed ? 'alert' : 'warn' };
  return { text: `Parcial (${granted} de ${mine.length})${lock}`, tone: 'warn' };
}

function summary(r: AppPermissionRules | undefined): string {
  if (!r) return 'Sin reglas';
  const parts: string[] = [];
  if (r.all) parts.push(r.all === 'grant' ? 'Conceder todos' : r.all === 'deny' ? 'Denegar todos' : 'Preguntar todos');
  const n = Object.keys(r.permissions ?? {}).length;
  if (n) parts.push(`${n} ${n === 1 ? 'permiso' : 'permisos'}`);
  if (r.special?.length) parts.push(`${r.special.length} ${r.special.length === 1 ? 'acceso especial' : 'accesos especiales'}`);
  return parts.length ? parts.join(' · ') : 'Sin reglas';
}

export function AppPermissionsEditor({
  apps, value, onChange, disabled, inherited, states, onOpenScreen, canGrantSensors, allowAdd = true, testid = 'app-permissions',
}: {
  apps: EditorApp[];
  value: PermissionRulesByApp;
  onChange: (v: PermissionRulesByApp) => void;
  disabled?: boolean;
  /** The layer below (a device's policy): shown as what applies when this layer says nothing. */
  inherited?: PermissionRulesByApp;
  /** Real state by package (device view). */
  states?: Record<string, AppPermissionState>;
  /** Device view: open the special-access screen on the phone. */
  onOpenScreen?: (pkg: string, access: string) => void;
  /** Android 12+: false = this phone does not let the administrator grant sensor permissions. */
  canGrantSensors?: boolean | null;
  allowAdd?: boolean;
  testid?: string;
}) {
  const [draft, setDraft] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [added, setAdded] = useState<string[]>([]);

  // The listed apps, then any package that already carries a rule (here or in the layer below), then freshly added.
  const known = new Set(apps.map((a) => a.pkg));
  const extra = [...Object.keys(value), ...Object.keys(inherited ?? {}), ...added].filter((p) => !known.has(p));
  const rows: EditorApp[] = [...apps, ...Array.from(new Set(extra)).sort().map((pkg) => ({ pkg, name: states?.[pkg]?.label ?? undefined }))];

  const setApp = (pkg: string, next: AppPermissionRules) => onChange(cleanRules({ ...value, [pkg]: next }));
  const add = () => {
    const pkg = draft.trim();
    if (!PACKAGE.test(pkg)) return;
    if (!rows.some((r) => r.pkg === pkg)) setAdded((a) => [...a, pkg]);
    setOpen(pkg);
    setDraft('');
  };

  return (
    <div className="perm-editor" data-testid={testid}>
      {rows.length === 0 && <div className="cfg-empty">Aún no hay apps. Agrega apps a la política o escribe un paquete abajo.</div>}
      {rows.map((app) => {
        const mine = value[app.pkg];
        const below = inherited?.[app.pkg];
        const state = states?.[app.pkg];
        const isOpen = open === app.pkg;
        return (
          <div className={`perm-app ${isOpen ? 'open' : ''}`} key={app.pkg} data-testid={`perm-app-${app.pkg}`}>
            <button type="button" className="perm-app-head" aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : app.pkg)}>
              <span className="perm-caret" aria-hidden="true">{isOpen ? '▾' : '▸'}</span>
              <span className="perm-app-name">
                <b>{app.name || state?.label || app.pkg}</b>
                <span className="muted small mono">{app.pkg}</span>
              </span>
              {state && !state.installed && <span className="chip tone-warn">No instalada</span>}
              <span className={`chip ${mine ? 'tone-ok' : ''}`}>{summary(mine)}</span>
              {below && <span className="chip" title="Lo que pide la política de este equipo">Política: {summary(below)}</span>}
            </button>
            {isOpen && (
              <div className="perm-app-body">
                <label className="perm-row perm-preset">
                  <span>
                    <b>Todos los permisos que la app declara</b>
                    <span className="cfg-field-help">Preajuste: se aplica a cada permiso que la app pide en su manifiesto, salvo los que fijes abajo.</span>
                  </span>
                  <select className="sel" aria-label={`Preajuste de ${app.pkg}`} value={mine?.all ?? ''} disabled={disabled}
                    onChange={(e) => setApp(app.pkg, { ...mine, all: (e.target.value || undefined) as PermissionRule | undefined })}>
                    <option value="">{below?.all ? `Según la política (${RULE_LABEL[below.all]})` : 'Sin preajuste'}</option>
                    <option value="grant">Conceder todos los que la app declara</option>
                    <option value="deny">Denegar todos</option>
                    <option value="prompt">Preguntar por todos</option>
                  </select>
                </label>
                <table className="perm-table">
                  <thead>
                    <tr><th>Permiso</th><th>Regla</th>{states && <th>Estado real en el equipo</th>}</tr>
                  </thead>
                  <tbody>
                    {PERMISSION_GROUPS.map((g) => {
                      const own = mine?.permissions?.[g.key];
                      const fallback = mine?.all ?? ruleFor(below, g.key);
                      const real = state?.installed ? groupState(state.permissions, g.key) : null;
                      const effective = own ?? fallback;
                      return (
                        <tr key={g.key}>
                          <td title={g.help}>
                            {g.label}
                            {g.sensor && canGrantSensors === false && effective === 'grant' && (
                              <div className="perm-warn small">Este equipo no deja al administrador concederlo (Android 12+).</div>
                            )}
                          </td>
                          <td>
                            <select className="sel" aria-label={`${g.label} de ${app.pkg}`} value={own ?? ''} disabled={disabled}
                              onChange={(e) => {
                                const next = { ...(mine?.permissions ?? {}) };
                                if (e.target.value) next[g.key] = e.target.value as PermissionRule; else delete next[g.key];
                                setApp(app.pkg, { ...mine, permissions: next });
                              }}>
                              <option value="">{fallback ? `— (${RULE_LABEL[fallback]})` : '— (política por defecto)'}</option>
                              <option value="grant">Conceder</option>
                              <option value="deny">Denegar</option>
                              <option value="prompt">Preguntar</option>
                            </select>
                          </td>
                          {states && <td>{real ? <span className={`perm-state tone-${real.tone}`}>{real.text}</span> : <span className="muted">—</span>}</td>}
                        </tr>
                      );
                    })}
                    {state?.installed && (state.permissions ?? []).some((p) => p.group === 'other') && (
                      <tr>
                        <td title="Permisos de tiempo de ejecución que la app declara y no están en la lista: solo los cubre el preajuste.">Otros permisos</td>
                        <td className="muted small">Solo con el preajuste</td>
                        <td><span className={`perm-state tone-${groupState(state.permissions, 'other').tone}`}>{groupState(state.permissions, 'other').text}</span></td>
                      </tr>
                    )}
                  </tbody>
                </table>
                <div className="perm-special">
                  <div className="perm-special-h">
                    <b>Accesos especiales</b>
                    <span className="cfg-field-help">
                      No son permisos normales: Android no da al MDM una forma de activarlos sin el usuario. El agente hace lo que
                      Android permite y, para lo demás, abre la pantalla exacta en el teléfono.
                    </span>
                  </div>
                  {SPECIAL_ACCESS.map((s) => {
                    const wanted = mine?.special?.includes(s.key) ?? false;
                    const fromPolicy = below?.special?.includes(s.key) ?? false;
                    const real = state?.installed ? state.special?.find((x) => x.key === s.key) : undefined;
                    return (
                      <div className="perm-special-row" key={s.key}>
                        <label title={s.api}>
                          <input type="checkbox" className="dev-check" checked={wanted || fromPolicy} disabled={disabled || fromPolicy}
                            aria-label={`${s.label} de ${app.pkg}`}
                            onChange={(e) => {
                              const cur = mine?.special ?? [];
                              setApp(app.pkg, { ...mine, special: e.target.checked ? [...cur, s.key] : cur.filter((x) => x !== s.key) });
                            }} />
                          <span>
                            {s.label}{fromPolicy && <span className="muted small"> (política)</span>}
                            <span className="cfg-field-help">{s.help}</span>
                          </span>
                        </label>
                        {real && (
                          <span className="perm-special-state">
                            <span className={`perm-state tone-${real.state === 'allowed' ? 'ok' : real.state === 'needsUser' && (wanted || fromPolicy) ? 'alert' : 'muted'}`}>
                              {SPECIAL_STATE_LABEL[real.state] ?? real.state}
                            </span>
                            {real.managed && <span className="muted small">{real.managed}</span>}
                            {onOpenScreen && (real.state === 'needsUser' || real.state === 'unknown') && (
                              <button type="button" className="btn btn-sm" disabled={disabled} onClick={() => onOpenScreen(app.pkg, s.key)}>
                                Abrir en el teléfono
                              </button>
                            )}
                          </span>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        );
      })}
      {allowAdd && !disabled && (
        <div className="dcp-sites-add perm-add">
          <input className="mono" aria-label="Agregar una app por nombre de paquete" placeholder="com.ejemplo.app (otra app: preinstalada o instalada por el usuario)"
            value={draft} onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
          <button type="button" className="btn btn-sm" disabled={!PACKAGE.test(draft.trim())} onClick={add}>Agregar app</button>
        </div>
      )}
    </div>
  );
}
