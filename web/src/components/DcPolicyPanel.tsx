// DallyControl policies of a configuration (stored as JSON in configurations.dcPolicy, see common DcPolicy.java):
// kiosk functions, the managed browser's site lists, the app policy and the location trail. The server validates
// and normalises what is saved; this editor only builds the object.

export const ROLES: { key: string; label: string; help: string }[] = [
  { key: 'phone', label: 'Phone', help: 'The dialer and the incoming-call screen (calls ring inside the kiosk).' },
  { key: 'contacts', label: 'Contacts', help: 'The contacts app.' },
  { key: 'messages', label: 'Messages', help: 'The default SMS app.' },
  { key: 'browser', label: 'Browser', help: 'The browser (Chrome), with the site lists below.' },
  { key: 'camera', label: 'Camera', help: 'The camera app.' },
  { key: 'maps', label: 'Maps', help: 'The maps app.' },
];

export interface DcPolicy {
  kioskRoles?: string[];
  browser?: { mode: 'open' | 'allowlist' | 'blocklist'; allow?: string[]; block?: string[] };
  apps?: { mode: 'open' | 'allowlist'; allowed?: string[]; roles?: string[]; hidePlayStore?: boolean };
  trackingMinutes?: number;
  kioskQuickSettings?: boolean;
  deviceName?: 'serial' | 'imei' | 'model-serial' | 'none';
  wifi?: { ssid: string; password?: string; security?: 'WPA' | 'WEP' | 'NONE'; hidden?: boolean }[];
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
  return Object.keys(out).length ? JSON.stringify(out) : null;
}

const lines = (s: string) => s.split(/[\n,]/).map((x) => x.trim()).filter(Boolean);

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
      <div className="cfg-sec-h">Device functions, browser, apps and location</div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Kiosk functions</label>
          <span className="chip chip-enforced">Enforced</span>
          <span className="cfg-field-help">
            Allowed in kiosk besides the apps above. Each phone uses its own app for the function (Samsung, Motorola, Pixel…),
            so there is nothing to configure per model. Any function turns the kiosk into a home screen with these apps.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <RoleChecks name="Kiosk functions" value={p.kioskRoles ?? []} disabled={disabled} onChange={(v) => update({ ...p, kioskRoles: v })} />
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Kiosk quick settings</label>
          <span className="chip chip-enforced">Enforced</span>
          <span className="cfg-field-help">
            A Settings tile on the kiosk home (and a notification, when the kiosk shows the status bar) with brightness, volume,
            Wi-Fi and Bluetooth. Android keeps its own quick-settings panel closed in kiosk; this one never opens system Settings.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <input type="checkbox" className="dev-check" aria-label="Kiosk quick settings" checked={p.kioskQuickSettings === true} disabled={disabled}
            onChange={(e) => update({ ...p, kioskQuickSettings: e.target.checked || undefined })} />
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Browser (Chrome)</label>
          <span className="chip chip-enforced">Enforced</span>
          <span className="cfg-field-help">
            Allowed sites only (allowlist), or every site but some (blocklist). One entry per line: <code>amovil.com.co</code>,{' '}
            <code>*.gov.co</code>, <code>https://example.com/path</code>.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select
            className="sel"
            aria-label="Browser mode"
            value={browserMode}
            disabled={disabled}
            onChange={(e) => {
              const m = e.target.value;
              update({ ...p, browser: m === 'unmanaged' ? undefined : { ...(p.browser ?? {}), mode: m as 'open' | 'allowlist' | 'blocklist' } });
            }}
          >
            <option value="unmanaged">Not managed</option>
            <option value="open">Any site</option>
            <option value="allowlist">Only these sites</option>
            <option value="blocklist">Any site except these</option>
          </select>
        </div>
      </div>
      {browserMode === 'allowlist' || browserMode === 'blocklist' ? (
        <div className="cfg-field">
          <div className="cfg-field-label">
            <label>{browserMode === 'allowlist' ? 'Allowed sites' : 'Blocked sites'}</label>
          </div>
          <div className="cfg-field-ctl">
            <textarea
              key={browserMode}
              className="dcp-list mono"
              aria-label={browserMode === 'allowlist' ? 'Allowed sites' : 'Blocked sites'}
              rows={5}
              disabled={disabled}
              defaultValue={(browserMode === 'allowlist' ? p.browser?.allow : p.browser?.block)?.join('\n') ?? ''}
              onBlur={(e) => {
                const list = lines(e.target.value);
                update({ ...p, browser: { ...(p.browser ?? { mode: browserMode }), mode: browserMode, ...(browserMode === 'allowlist' ? { allow: list } : { block: list }) } });
              }}
            />
          </div>
        </div>
      ) : null}

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>App policy</label>
          <span className="chip chip-enforced">Enforced</span>
          <span className="cfg-field-help">
            “Only allowed apps”: anything the user installs outside the list (e.g. from the Play Store) is paused at once and cannot
            be opened; it is unpaused when you allow it. The apps of this configuration are always allowed.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select
            className="sel"
            aria-label="App policy"
            value={appsMode}
            disabled={disabled}
            onChange={(e) => {
              const m = e.target.value;
              update({ ...p, apps: m === 'unmanaged' ? undefined : { ...(p.apps ?? {}), mode: m as 'open' | 'allowlist' } });
            }}
          >
            <option value="unmanaged">Not managed</option>
            <option value="open">Any app</option>
            <option value="allowlist">Only allowed apps</option>
          </select>
        </div>
      </div>
      {appsMode === 'allowlist' ? (
        <>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>Also allowed (package names)</label>
              <span className="cfg-field-help">Apps the user may install themselves, one package per line, e.g. <code>com.whatsapp</code>.</span>
            </div>
            <div className="cfg-field-ctl">
              <textarea
                className="dcp-list mono"
                aria-label="Also allowed packages"
                rows={4}
                disabled={disabled}
                defaultValue={p.apps?.allowed?.join('\n') ?? ''}
                onBlur={(e) => update({ ...p, apps: { ...(p.apps ?? { mode: 'allowlist' }), allowed: lines(e.target.value) } })}
              />
            </div>
          </div>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>Allowed functions</label>
              <span className="cfg-field-help">The phone's own dialer, contacts, browser… whatever brand.</span>
            </div>
            <div className="cfg-field-ctl">
              <RoleChecks name="Allowed functions" value={p.apps?.roles ?? []} disabled={disabled}
                onChange={(v) => update({ ...p, apps: { ...(p.apps ?? { mode: 'allowlist' }), roles: v } })} />
            </div>
          </div>
          <div className="cfg-field">
            <div className="cfg-field-label">
              <label>Hide the Play Store</label>
              <span className="cfg-field-help">Users cannot browse or install from the store; you install apps from the console.</span>
            </div>
            <div className="cfg-field-ctl">
              <input type="checkbox" className="dev-check" aria-label="Hide the Play Store" checked={p.apps?.hidePlayStore === true} disabled={disabled}
                onChange={(e) => update({ ...p, apps: { ...(p.apps ?? { mode: 'allowlist' }), hidePlayStore: e.target.checked || undefined } })} />
            </div>
          </div>
        </>
      ) : null}

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Wi-Fi networks</label>
          <span className="chip chip-enforced">Enforced</span>
          <span className="cfg-field-help">
            Saved on every device of this configuration (the first one is joined when the phone has no Wi-Fi). Removing one here
            removes it from the phones; networks the user added are left alone.
          </span>
        </div>
        <div className="cfg-field-ctl" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }} data-testid="policy-wifi">
          {(p.wifi ?? []).map((w, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto auto', gap: 6 }}>
              <input className="input" placeholder="SSID" value={w.ssid} disabled={disabled} aria-label={`Wi-Fi ${i + 1} SSID`}
                onChange={(e) => update({ ...p, wifi: (p.wifi ?? []).map((x, j) => (j === i ? { ...x, ssid: e.target.value } : x)) })} />
              <input className="input" type="password" placeholder="Password" value={w.password ?? ''} disabled={disabled || w.security === 'NONE'}
                aria-label={`Wi-Fi ${i + 1} password`}
                onChange={(e) => update({ ...p, wifi: (p.wifi ?? []).map((x, j) => (j === i ? { ...x, password: e.target.value } : x)) })} />
              <select className="sel" value={w.security ?? 'WPA'} disabled={disabled} aria-label={`Wi-Fi ${i + 1} security`}
                onChange={(e) => update({ ...p, wifi: (p.wifi ?? []).map((x, j) => (j === i ? { ...x, security: e.target.value as 'WPA' | 'WEP' | 'NONE' } : x)) })}>
                <option value="WPA">WPA/WPA2</option><option value="WEP">WEP</option><option value="NONE">Open</option>
              </select>
              <button className="btn btn-sm btn-ghost" disabled={disabled} aria-label="Remove network"
                onClick={() => update({ ...p, wifi: (p.wifi ?? []).filter((_, j) => j !== i) })}>✕</button>
            </div>
          ))}
          <button className="btn btn-sm" disabled={disabled} style={{ alignSelf: 'flex-end' }}
            onClick={() => update({ ...p, wifi: [...(p.wifi ?? []), { ssid: '', security: 'WPA' }] })}>+ Add network</button>
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Device name</label>
          <span className="cfg-field-help">
            How a new device is named from its first report (only while it has no name — a name you set is never replaced).
            Default: its serial number.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <select className="sel" aria-label="Device name" value={p.deviceName ?? 'serial'} disabled={disabled}
            onChange={(e) => update({ ...p, deviceName: e.target.value === 'serial' ? undefined : (e.target.value as DcPolicy['deviceName']) })}>
            <option value="serial">Serial number</option>
            <option value="imei">IMEI</option>
            <option value="model-serial">Model + serial</option>
            <option value="none">Do not name</option>
          </select>
        </div>
      </div>

      <div className="cfg-field">
        <div className="cfg-field-label">
          <label>Location every</label>
          <span className="chip chip-enforced">Enforced</span>
          <span className="cfg-field-help">
            A GPS point every N minutes, kept on the phone while it is offline and uploaded when it reconnects (Fleet map).
            Empty = only when the phone checks in. Frequent points cost battery; use Always-on for phones that must report on time.
          </span>
        </div>
        <div className="cfg-field-ctl">
          <input
            type="number"
            className="input"
            aria-label="Location interval in minutes"
            min={1}
            max={1440}
            placeholder="off"
            style={{ width: 100 }}
            disabled={disabled}
            value={p.trackingMinutes ?? ''}
            onChange={(e) => {
              const n = parseInt(e.target.value, 10);
              update({ ...p, trackingMinutes: Number.isFinite(n) && n > 0 ? Math.min(n, 1440) : undefined });
            }}
          />{' '}
          <span className="muted">minutes</span>
        </div>
      </div>
    </section>
  );
}
