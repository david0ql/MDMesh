import { useState } from 'react';
// DallyControl policies of a configuration (stored as JSON in configurations.dcPolicy, see common DcPolicy.java):
// kiosk functions, the managed browser's site lists, the app policy and the location trail. The server validates
// and normalises what is saved; this editor only builds the object.

export const ROLES: { key: string; label: string; help: string }[] = [
  { key: 'phone', label: 'Teléfono', help: 'El marcador y la pantalla de llamada entrante (las llamadas suenan dentro del quiosco).' },
  { key: 'contacts', label: 'Contactos', help: 'La app de contactos.' },
  { key: 'messages', label: 'Mensajes', help: 'La app de SMS predeterminada.' },
  { key: 'browser', label: 'Navegador', help: 'El navegador (Chrome), con las listas de sitios de abajo.' },
  { key: 'camera', label: 'Cámara', help: 'La app de cámara.' },
  { key: 'maps', label: 'Mapas', help: 'La app de mapas.' },
];

export interface DcPolicy {
  kioskRoles?: string[];
  browser?: { mode: 'open' | 'allowlist' | 'blocklist'; allow?: string[]; block?: string[]; homeUrl?: string };
  apps?: { mode: 'open' | 'allowlist'; allowed?: string[]; roles?: string[]; hidePlayStore?: boolean };
  trackingMinutes?: number;
  kioskQuickSettings?: boolean;
  deviceName?: 'serial' | 'imei' | 'model-serial' | 'none';
  wifi?: { ssid: string; password?: string; security?: 'WPA' | 'WEP' | 'NONE'; hidden?: boolean }[];
  /** App groups used by the policy; kiosk = its apps also show in the kiosk. */
  appGroups?: { id: number; kiosk?: boolean }[];
  /** The policy's own apps installed and allowed but kept out of the kiosk. */
  notInKiosk?: string[];
  /** Packages never shown as kiosk icons, whatever brings them (functions such as the browser, groups…). */
  kioskHidden?: string[];
  /** Kiosk branding: logos, wallpaper, serial, support line (folders may override). */
  kioskBrand?: import('../api/fleet').KioskBrand;
  /** Anti-theft: 4-12 digits asked in the kiosk before switching off or restarting. */
  powerPin?: string;
  /** Device security rules: data sharing, Google accounts, factory reset. */
  device?: {
    tethering?: 'allow' | 'block'; // 'allow' = the default (kept for older policies)
    googleAccounts?: 'block';
    /** @deprecated one domain; accountDomains replaces it. */
    accountDomain?: string;
    accountDomains?: string[];
    factoryReset?: 'block';
    frpAccounts?: string[];
  };
}

export function parseDcPolicy(raw: unknown): DcPolicy {
  if (typeof raw !== 'string' || !raw.trim()) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as DcPolicy) : {};
  } catch {
    return {};
  }
}

/** The JSON to store, or null when nothing is set. */
export function serializeDcPolicy(p: DcPolicy): string | null {
  const out: DcPolicy = {};
  if (p.kioskRoles?.length) out.kioskRoles = p.kioskRoles;
  if (p.browser) out.browser = p.browser;
  if (p.apps) out.apps = p.apps;
  if (p.trackingMinutes && p.trackingMinutes > 0) out.trackingMinutes = p.trackingMinutes;
  if (p.kioskQuickSettings === false) out.kioskQuickSettings = false; // on by default
  if (p.deviceName) out.deviceName = p.deviceName;
  if (p.wifi?.length) out.wifi = p.wifi; // rows still being typed stay; the server drops unnamed ones on save
  if (p.appGroups?.length) out.appGroups = p.appGroups;
  if (p.notInKiosk?.length) out.notInKiosk = p.notInKiosk;
  if (p.kioskHidden?.length) out.kioskHidden = p.kioskHidden;
  if (p.kioskBrand && Object.values(p.kioskBrand).some(Boolean)) out.kioskBrand = p.kioskBrand;
  if (p.powerPin) out.powerPin = p.powerPin;
  if (p.device && Object.values(p.device).some((v) => (Array.isArray(v) ? v.length : v))) out.device = p.device;
  return Object.keys(out).length ? JSON.stringify(out) : null;
}

/** The allowed Google account domains (older policies kept one in accountDomain). */
const domainsOf = (d: DcPolicy['device']): string[] =>
  Array.from(new Set([...(d?.accountDomains ?? []), ...(d?.accountDomain ? [d.accountDomain] : [])]));

const lines = (s: string) => s.split(/[\n,]/).map((x) => x.trim()).filter(Boolean);

/** A list of sites edited one at a time: type one and add it (Enter or the button); remove with ✕. */
function SiteList({ label, value, disabled, onChange, placeholder = 'amovil.com.co', empty = 'Aún no hay sitios en la lista.', clean = (x: string) => x, suggestions }: {
  label: string; value: string[]; disabled?: boolean; onChange: (v: string[]) => void;
  placeholder?: string; empty?: string; clean?: (x: string) => string;
  /** One-tap additions shown under the input (value + label). */
  suggestions?: { value: string; label: string }[];
}) {
  const [draft, setDraft] = useState('');
  // A pasted batch (commas, spaces or lines) becomes one item each; repeats are skipped.
  const add = () => {
    const fresh = draft.split(/[\s,]+/).map((x) => clean(x.trim())).filter((x) => x && !value.includes(x));
    if (fresh.length) onChange([...value, ...Array.from(new Set(fresh))]);
    setDraft('');
  };
  return (
    <div className="dcp-sites">
      {!disabled && (
        <div className="dcp-sites-add">
          <input
            className="mono"
            aria-label={`Agregar a ${label.toLowerCase()}`}
            placeholder={placeholder}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }}
          />
          <button type="button" className="btn btn-sm" disabled={!draft.trim()} onClick={add}>Agregar</button>
        </div>
      )}
      {!disabled && suggestions?.some((x) => !value.includes(x.value)) && (
        <div className="dcp-sites-add" style={{ flexWrap: 'wrap' }}>
          {suggestions.filter((x) => !value.includes(x.value)).map((x) => (
            <button key={x.value} type="button" className="btn btn-sm btn-ghost" onClick={() => onChange([...value, x.value])}>+ {x.label}</button>
          ))}
        </div>
      )}
      {value.length === 0 ? (
        <div className="cfg-empty">{empty}</div>
      ) : (
        <ul className="dcp-sites-list" aria-label={label}>
          {value.map((site) => (
            <li key={site}>
              <span className="mono">{site}</span>
              {!disabled && (
                <button type="button" className="btn btn-sm btn-ghost" aria-label={`Quitar ${site}`} onClick={() => onChange(value.filter((x) => x !== site))}>✕</button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function RoleChecks({ value, disabled, onChange, name }: { value: string[]; disabled?: boolean; onChange: (v: string[]) => void; name: string }) {
  return (
    <div className="dcp-roles" role="group" aria-label={name}>
      {ROLES.map((r) => (
        <label key={r.key} className="dcp-role" title={r.help}>
          <input
            type="checkbox"
            className="dev-check"
            checked={value.includes(r.key)}
            disabled={disabled}
            onChange={(e) => onChange(e.target.checked ? [...value, r.key] : value.filter((x) => x !== r.key))}
          />
          <span>{r.label}</span>
        </label>
      ))}
    </div>
  );
}

export function DcPolicyPanel({ value, disabled, onChange }: { value: unknown; disabled?: boolean; onChange: (v: string | null) => void }) {
  const p = parseDcPolicy(value);
  const update = (next: DcPolicy) => onChange(serializeDcPolicy(next));
  const browserMode = p.browser?.mode ?? 'unmanaged';
  const appsMode = p.apps?.mode ?? 'unmanaged';

  return (
    <section className="panel cfg-panel" data-testid="dc-policy">
      <div className="cfg-sec-h">Funciones del dispositivo, navegador, apps y ubicación</div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Funciones del quiosco</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Permitidas en el quiosco además de las apps de arriba. Cada teléfono usa su propia app para la función (Samsung, Motorola, Pixel…),
            así que no hay nada que configurar por modelo. Cualquier función convierte el quiosco en una pantalla de inicio con estas apps.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <RoleChecks name="Funciones del quiosco" value={p.kioskRoles ?? []} disabled={disabled} onChange={(v) => update({ ...p, kioskRoles: v })} />
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Ocultar del quiosco</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Apps que no se muestran como ícono en el quiosco aunque una función o un grupo las traiga (por ejemplo la app de Google).
            Siguen permitidas: si otra app las abre, funcionan. Se escribe el nombre del paquete.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <SiteList label="Ocultas del quiosco" value={p.kioskHidden ?? []} disabled={disabled}
            placeholder="com.google.android.googlequicksearchbox" empty="Ninguna app oculta."
            clean={(x) => x.toLowerCase()}
            suggestions={[
              { value: 'com.google.android.googlequicksearchbox', label: 'App de Google' },
              { value: 'com.google.android.apps.messaging', label: 'Mensajes de Google' },
              { value: 'com.google.android.contacts', label: 'Contactos de Google' },
            ]}
            onChange={(v) => update({ ...p, kioskHidden: v })} />
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Compartir datos (zona Wi‑Fi)</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            «Permitir» (por defecto): el quiosco muestra el ícono «Compartir datos», que abre la pantalla de zona Wi‑Fi del teléfono
            para encenderla o apagarla (Android no deja que el MDM la encienda por su cuenta). «Bloquear»: nadie puede compartir la
            conexión y el ícono no aparece.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select className="sel" aria-label="Compartir datos" disabled={disabled} value={p.device?.tethering === 'block' ? 'block' : ''}
            onChange={(e) => update({ ...p, device: { ...(p.device ?? {}), tethering: (e.target.value || undefined) as 'block' | undefined } })}>
            <option value="">Permitir (ícono en el quiosco)</option>
            <option value="block">Bloquear</option>
          </select>
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Cuentas de Google</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            «No permitir agregar»: nadie agrega cuentas de Google al teléfono (deja puesta antes la corporativa). «Solo de estos
            dominios»: en cuanto se agrega una cuenta de Google de otro dominio (en Gmail o en Ajustes), el teléfono la quita, y Chrome
            solo deja iniciar sesión con esos dominios. Así Gmail y las demás apps de Google solo funcionan con cuentas de la empresa.
            Sin dominios, se acepta cualquiera.
          </span>
        </div>
        <div className="cfg-field-ctl" style={{ flexDirection: 'column', gap: 6, alignItems: 'stretch' }}>
          <select className="sel" aria-label="Cuentas de Google" disabled={disabled} value={p.device?.googleAccounts ?? ''}
            onChange={(e) => update({ ...p, device: { ...(p.device ?? {}), googleAccounts: (e.target.value || undefined) as 'block' | undefined } })}>
            <option value="">Se pueden agregar</option>
            <option value="block">No permitir agregar</option>
          </select>
          <SiteList label="Dominios permitidos" disabled={disabled}
            value={domainsOf(p.device)} placeholder="amovil.co" empty="Cualquier dominio."
            clean={(x) => x.toLowerCase().replace(/^.*@/, '').replace(/^@/, '')}
            onChange={(v) => update({ ...p, device: { ...(p.device ?? {}), accountDomain: undefined, accountDomains: v.length ? v : undefined } })} />
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Restablecimiento de fábrica</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            «Bloquear»: no se puede restablecer desde los Ajustes del teléfono; solo desde esta consola (Control → Restablecer de
            fábrica). El reseteo por botones (recovery) no lo impide ningún MDM, pero en Android 11+ puedes indicar qué cuentas de
            Google pueden volver a activar el equipo después: sin una de ellas queda inservible. Van los ID numéricos de la cuenta
            de Google (no el correo), separados por coma.
          </span>
        </div>
        <div className="cfg-field-ctl" style={{ flexDirection: 'column', gap: 6, alignItems: 'stretch' }}>
          <select className="sel" aria-label="Restablecimiento de fábrica" disabled={disabled} value={p.device?.factoryReset ?? ''}
            onChange={(e) => update({ ...p, device: { ...(p.device ?? {}), factoryReset: (e.target.value || undefined) as 'block' | undefined } })}>
            <option value="">Permitido</option>
            <option value="block">Bloquear desde Ajustes</option>
          </select>
          <input className="input mono" aria-label="Cuentas de reactivación" placeholder="ID de cuentas que pueden reactivar (opcional)" disabled={disabled}
            value={(p.device?.frpAccounts ?? []).join(', ')}
            onChange={(e) => {
              const ids = e.target.value.split(/[\s,]+/).map((x) => x.replace(/[^0-9]/g, '')).filter(Boolean);
              update({ ...p, device: { ...(p.device ?? {}), frpAccounts: ids.length ? ids : undefined } });
            }} />
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Antirrobo: clave para apagar</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Con una clave (4 a 12 dígitos), en el quiosco el botón de encendido ya no abre el menú de apagado: para apagar o reiniciar
            hay que escribir esta clave en «Ajustes rápidos». También se bloquean el modo seguro y el restablecimiento de fábrica desde
            Ajustes. Android no permite impedir el apagado forzado (mantener el botón ~10 segundos); el teléfono vuelve a encender en
            el quiosco. Vacío = sin antirrobo.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <input className="input mono" aria-label="Clave para apagar" inputMode="numeric" maxLength={12} placeholder="p. ej. 4821" disabled={disabled}
            value={p.powerPin ?? ''} data-testid="policy-power-pin"
            onChange={(e) => update({ ...p, powerPin: e.target.value.replace(/[^0-9]/g, '') || undefined })} />
          {!!p.powerPin && p.powerPin.length < 4 && <span className="field-error">Mínimo 4 dígitos.</span>}
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Ajustes rápidos del quiosco</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Una barrita arriba del quiosco (y deslizar hacia abajo) abre un panel con brillo, volumen, Wi‑Fi y Bluetooth; dentro de una
            app se abre desde la notificación «Ajustes rápidos» al bajar la barra de estado. Activado por defecto. Android mantiene cerrado
            su propio panel en el quiosco; este nunca abre los Ajustes del sistema.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <input type="checkbox" className="dev-check" aria-label="Ajustes rápidos del quiosco" checked={p.kioskQuickSettings !== false} disabled={disabled}
            onChange={(e) => update({ ...p, kioskQuickSettings: e.target.checked ? undefined : false })} />
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Navegador (Chrome)</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Solo los sitios permitidos (lista blanca) o todos menos algunos (lista negra). Agrega cada sitio a la lista, p. ej.{' '}
            <code>amovil.com.co</code>, <code>*.gov.co</code> o <code>https://example.com/path</code>.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select
            className="sel"
            aria-label="Modo del navegador"
            value={browserMode}
            disabled={disabled}
            onChange={(e) => {
              const m = e.target.value;
              update({ ...p, browser: m === 'unmanaged' ? undefined : { ...(p.browser ?? {}), mode: m as 'open' | 'allowlist' | 'blocklist' } });
            }}
          >
            <option value="unmanaged">Sin administrar</option>
            <option value="open">Cualquier sitio</option>
            <option value="allowlist">Solo estos sitios</option>
            <option value="blocklist">Cualquier sitio excepto estos</option>
          </select>
        </div>
      </div>
      {browserMode !== 'unmanaged' && (
        <div className="cfg-field">
          <div className="cfg-field-label">
            <label>Página de la empresa</label>
            <span className="cfg-field-help">
              Chrome no permite redirigir automáticamente un sitio bloqueado (muestra «bloqueado por tu organización»). Con esta
              página: es la página de inicio de Chrome y cualquier búsqueda que escriban en la barra lleva a ella. Con «Solo estos
              sitios» queda permitida sola.
            </span>
          </div>
          <div className="cfg-field-ctl">
            <input className="input mono" aria-label="Página de la empresa" placeholder="https://portal.empresa.com" disabled={disabled}
              value={p.browser?.homeUrl ?? ''} data-testid="policy-home-url"
              onChange={(e) => update({ ...p, browser: { ...(p.browser ?? { mode: browserMode as 'open' | 'allowlist' | 'blocklist' }), homeUrl: e.target.value.trim() || undefined } })} />
          </div>
        </div>
      )}
      {browserMode === 'allowlist' || browserMode === 'blocklist' ? (
        <div className="cfg-field">
          <div className="cfg-field-label">
            <label>{browserMode === 'allowlist' ? 'Sitios permitidos' : 'Sitios bloqueados'}</label>
          </div>
          <div className="cfg-field-ctl">
            <SiteList
              key={browserMode}
              label={browserMode === 'allowlist' ? 'Sitios permitidos' : 'Sitios bloqueados'}
              disabled={disabled}
              value={(browserMode === 'allowlist' ? p.browser?.allow : p.browser?.block) ?? []}
              onChange={(list) =>
                update({ ...p, browser: { ...(p.browser ?? { mode: browserMode }), mode: browserMode, ...(browserMode === 'allowlist' ? { allow: list } : { block: list }) } })}
            />
          </div>
        </div>
      ) : null}

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Política de apps</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            “Solo apps permitidas”: lo que el usuario instale fuera de la lista (p. ej. desde la Play Store) se pausa de inmediato y no
            se puede abrir; se reactiva cuando lo permites. Las apps de esta política siempre están permitidas.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select
            className="sel"
            aria-label="Política de apps"
            value={appsMode}
            disabled={disabled}
            onChange={(e) => {
              const m = e.target.value;
              update({ ...p, apps: m === 'unmanaged' ? undefined : { ...(p.apps ?? {}), mode: m as 'open' | 'allowlist' } });
            }}
          >
            <option value="unmanaged">Sin administrar</option>
            <option value="open">Cualquier app</option>
            <option value="allowlist">Solo apps permitidas</option>
          </select>
        </div>
      </div>
      {appsMode === 'allowlist' ? (
        <>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>También permitidas (nombres de paquete)</label>
              <span className="cfg-field-help">Apps que el usuario puede instalar por su cuenta, un paquete por línea, p. ej. <code>com.whatsapp</code>.</span>
            </div>
            <div className="cfg-field-ctl">
              <textarea
                className="dcp-list mono"
                aria-label="Paquetes también permitidos"
                rows={4}
                disabled={disabled}
                defaultValue={p.apps?.allowed?.join('\n') ?? ''}
                onBlur={(e) => update({ ...p, apps: { ...(p.apps ?? { mode: 'allowlist' }), allowed: lines(e.target.value) } })}
              />
            </div>
          </div>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>Funciones permitidas</label>
              <span className="cfg-field-help">El marcador, los contactos, el navegador… propios del teléfono, sea cual sea la marca.</span>
            </div>
            <div className="cfg-field-ctl">
              <RoleChecks name="Funciones permitidas" value={p.apps?.roles ?? []} disabled={disabled}
                onChange={(v) => update({ ...p, apps: { ...(p.apps ?? { mode: 'allowlist' }), roles: v } })} />
            </div>
          </div>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>Ocultar la Play Store</label>
              <span className="cfg-field-help">Los usuarios no pueden explorar ni instalar desde la tienda; tú instalas las apps desde la consola.</span>
            </div>
            <div className="cfg-field-ctl">
              <input type="checkbox" className="dev-check" aria-label="Ocultar la Play Store" checked={p.apps?.hidePlayStore === true} disabled={disabled}
                onChange={(e) => update({ ...p, apps: { ...(p.apps ?? { mode: 'allowlist' }), hidePlayStore: e.target.checked || undefined } })} />
            </div>
          </div>
        </>
      ) : null}

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Redes Wi‑Fi</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Se guardan en todos los dispositivos de esta política (el teléfono se conecta a la primera si no tiene Wi‑Fi). Si quitas
            una aquí, se quita de los teléfonos; las redes que agregó el usuario no se tocan.
          </span>
        </div>
        <div className="cfg-field-ctl" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }} data-testid="policy-wifi">
          {(p.wifi ?? []).map((w, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto auto', gap: 6 }}>
              <input className="input" placeholder="SSID" value={w.ssid} disabled={disabled} aria-label={`Wi‑Fi ${i + 1} SSID`}
                onChange={(e) => update({ ...p, wifi: (p.wifi ?? []).map((x, j) => (j === i ? { ...x, ssid: e.target.value } : x)) })} />
              <input className="input" type="password" placeholder="Contraseña" value={w.password ?? ''} disabled={disabled || w.security === 'NONE'}
                aria-label={`Wi‑Fi ${i + 1} contraseña`}
                onChange={(e) => update({ ...p, wifi: (p.wifi ?? []).map((x, j) => (j === i ? { ...x, password: e.target.value } : x)) })} />
              <select className="sel" value={w.security ?? 'WPA'} disabled={disabled} aria-label={`Wi‑Fi ${i + 1} seguridad`}
                onChange={(e) => update({ ...p, wifi: (p.wifi ?? []).map((x, j) => (j === i ? { ...x, security: e.target.value as 'WPA' | 'WEP' | 'NONE' } : x)) })}>
                <option value="WPA">WPA/WPA2</option><option value="WEP">WEP</option><option value="NONE">Abierta</option>
              </select>
              <button className="btn btn-sm btn-ghost" disabled={disabled} aria-label="Quitar red"
                onClick={() => update({ ...p, wifi: (p.wifi ?? []).filter((_, j) => j !== i) })}>✕</button>
            </div>
          ))}
          <button className="btn btn-sm" disabled={disabled} style={{ alignSelf: 'flex-end' }}
            onClick={() => update({ ...p, wifi: [...(p.wifi ?? []), { ssid: '', security: 'WPA' }] })}>+ Agregar red</button>
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Nombre del dispositivo</label>
          <span className="cfg-field-help">
            Cómo se nombra un dispositivo nuevo a partir de su primer reporte (solo mientras no tenga nombre; un nombre que tú pongas nunca se reemplaza).
            Predeterminado: su número de serie.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select className="sel" aria-label="Nombre del dispositivo" value={p.deviceName ?? 'serial'} disabled={disabled}
            onChange={(e) => update({ ...p, deviceName: e.target.value === 'serial' ? undefined : (e.target.value as DcPolicy['deviceName']) })}>
            <option value="serial">Número de serie</option>
            <option value="imei">IMEI</option>
            <option value="model-serial">Modelo + número de serie</option>
            <option value="none">No nombrar</option>
          </select>
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Ubicación cada</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Un punto GPS cada N minutos, guardado en el teléfono mientras esté sin conexión y enviado al reconectarse (mapa de la flota).
            Vacío = solo cuando el teléfono reporta. Los puntos frecuentes gastan batería; usa Siempre conectado para los teléfonos que deben reportar a tiempo.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <input
            type="number"
            className="input"
            aria-label="Intervalo de ubicación en minutos"
            min={1}
            max={1440}
            placeholder="apagado"
            style={{ width: 100 }}
            disabled={disabled}
            value={p.trackingMinutes ?? ''}
            onChange={(e) => {
              const n = parseInt(e.target.value, 10);
              update({ ...p, trackingMinutes: Number.isFinite(n) && n > 0 ? Math.min(n, 1440) : undefined });
            }}
          />{' '}
          <span className="muted">minutos</span>
        </div>
      </div>
    </section>
  );
}
