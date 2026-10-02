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
  browser?: { mode: 'open' | 'allowlist' | 'blocklist'; allow?: string[]; block?: string[] };
  apps?: { mode: 'open' | 'allowlist'; allowed?: string[]; roles?: string[]; hidePlayStore?: boolean };
  trackingMinutes?: number;
  kioskQuickSettings?: boolean;
  deviceName?: 'serial' | 'imei' | 'model-serial' | 'none';
  wifi?: { ssid: string; password?: string; security?: 'WPA' | 'WEP' | 'NONE'; hidden?: boolean }[];
  /** App groups used by the policy; kiosk = its apps also show in the kiosk. */
  appGroups?: { id: number; kiosk?: boolean }[];
  /** The policy's own apps installed and allowed but kept out of the kiosk. */
  notInKiosk?: string[];
  /** Kiosk branding: logos, wallpaper, serial, support line (folders may override). */
  kioskBrand?: import('../api/fleet').KioskBrand;
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
  if (p.kioskQuickSettings) out.kioskQuickSettings = true;
  if (p.deviceName) out.deviceName = p.deviceName;
  if (p.wifi?.length) out.wifi = p.wifi; // rows still being typed stay; the server drops unnamed ones on save
  if (p.appGroups?.length) out.appGroups = p.appGroups;
  if (p.notInKiosk?.length) out.notInKiosk = p.notInKiosk;
  if (p.kioskBrand && Object.values(p.kioskBrand).some(Boolean)) out.kioskBrand = p.kioskBrand;
  return Object.keys(out).length ? JSON.stringify(out) : null;
}

const lines = (s: string) => s.split(/[\n,]/).map((x) => x.trim()).filter(Boolean);

/** A list of sites edited one at a time: type one and add it (Enter or the button); remove with ✕. */
function SiteList({ label, value, disabled, onChange }: { label: string; value: string[]; disabled?: boolean; onChange: (v: string[]) => void }) {
  const [draft, setDraft] = useState('');
  // A pasted batch (commas, spaces or lines) becomes one item each; repeats are skipped.
  const add = () => {
    const fresh = draft.split(/[\s,]+/).map((x) => x.trim()).filter((x) => x && !value.includes(x));
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
            placeholder="amovil.com.co"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }}
          />
          <button type="button" className="btn btn-sm" disabled={!draft.trim()} onClick={add}>Agregar</button>
        </div>
      )}
      {value.length === 0 ? (
        <div className="cfg-empty">Aún no hay sitios en la lista.</div>
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
          <label>Ajustes rápidos del quiosco</label>
          <span className="chip chip-enforced">Aplicado</span>
          <span className="cfg-field-help">
            Un ícono de Ajustes en el inicio del quiosco (y una notificación, si el quiosco muestra la barra de estado) con brillo, volumen,
            Wi‑Fi y Bluetooth. Android mantiene cerrado su propio panel de ajustes rápidos en el quiosco; este nunca abre los Ajustes del sistema.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <input type="checkbox" className="dev-check" aria-label="Ajustes rápidos del quiosco" checked={p.kioskQuickSettings === true} disabled={disabled}
            onChange={(e) => update({ ...p, kioskQuickSettings: e.target.checked || undefined })} />
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
