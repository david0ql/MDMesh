import { useCallback, useEffect, useState } from 'react';
import { deleteWifiCertificate, fileToBase64, listWifiCertificates, uploadWifiCertificate, type WifiCertificate } from '../api/wifiCerts';
import {
  SECURITIES, isEnterprise, isKeyed, newNetworkId, wifiProblems,
  type WifiEap, type WifiNetwork, type WifiProblem, type WifiRules, type WifiSecurity,
} from '../data/wifiPolicy';
import { useToast } from '../ui/toast';

// Corporate Wi-Fi of a policy: the networks (every security Android can save, with their options and, for 802.1X, the
// certificates), and the lock-down rules. Passwords never come back from the server: a saved one shows as "guardada"
// with a "Cambiar" button, and is kept unless a new one is typed.

const fmtDate = (ms?: number | null) => (ms ? new Date(ms).toLocaleDateString('es-CO', { year: 'numeric', month: 'short', day: 'numeric' }) : '—');

function expiry(c: WifiCertificate): { tone: 'ok' | 'warn' | 'alert'; text: string } {
  if (c.status === 'expired') return { tone: 'alert', text: `Venció el ${fmtDate(c.notAfter)}` };
  if (c.status === 'expiring') return { tone: 'warn', text: `Vence en ${c.daysLeft} día${c.daysLeft === 1 ? '' : 's'} (${fmtDate(c.notAfter)})` };
  return { tone: 'ok', text: `Vence el ${fmtDate(c.notAfter)}` };
}

/** A stored secret: "saved" with a Change button, or the input once changing (or when nothing is saved yet). */
function Secret({ label, value, saved, disabled, placeholder, onChange }: {
  label: string; value: string; saved: boolean; disabled?: boolean; placeholder: string; onChange: (v: string) => void;
}) {
  const [changing, setChanging] = useState(false);
  if (saved && !changing && !value) {
    return (
      <span className="wf-secret" data-testid="wifi-secret-saved">
        <span className="muted">•••••••• guardada</span>
        {!disabled && <button type="button" className="btn btn-sm btn-ghost" aria-label={`Cambiar ${label}`} onClick={() => setChanging(true)}>Cambiar</button>}
      </span>
    );
  }
  return (
    <input className="input" type="password" autoComplete="new-password" aria-label={label} disabled={disabled}
      placeholder={saved ? 'Nueva contraseña (vacío = conservar la guardada)' : placeholder}
      value={value} onChange={(e) => onChange(e.target.value)} />
  );
}

function Problems({ items }: { items: WifiProblem[] }) {
  if (!items.length) return null;
  return (
    <ul className="wf-problems" data-testid="wifi-problems">
      {items.map((p, i) => <li key={i} className={p.level === 'error' ? 'alert' : 'warn'}>{p.text}</li>)}
    </ul>
  );
}

function Certificates({ certs, disabled, reload }: { certs: WifiCertificate[]; disabled?: boolean; reload: () => void }) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [kind, setKind] = useState<'CA' | 'CLIENT'>('CA');
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [fileKey, setFileKey] = useState(0);

  const upload = async () => {
    if (!file) return;
    setBusy(true);
    try {
      const data = await fileToBase64(file);
      await uploadWifiCertificate({ name: name.trim() || file.name, kind, data, password: kind === 'CLIENT' ? password : undefined });
      toast.push('ok', 'Certificado guardado', name.trim() || file.name);
      setName(''); setFile(null); setPassword(''); setFileKey((k) => k + 1);
      reload();
    } catch (e) {
      toast.push('err', 'No se pudo subir el certificado', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  };
  const remove = async (c: WifiCertificate) => {
    if (!window.confirm(`¿Eliminar el certificado «${c.name}»?`)) return;
    try {
      await deleteWifiCertificate(c.id);
      reload();
    } catch (e) {
      toast.push('err', 'No se pudo eliminar', e instanceof Error ? e.message : '');
    }
  };

  return (
    <details className="wf-certs" data-testid="wifi-certs">
      <summary>Certificados Wi‑Fi ({certs.length})</summary>
      <p className="cfg-field-help">
        La CA que firma el certificado del servidor RADIUS (PEM o DER) y, para EAP‑TLS, el certificado del equipo con su clave
        (PKCS#12 y su contraseña). Se guardan cifrados: la consola solo vuelve a mostrar a quién pertenecen y cuándo vencen.
        El teléfono los instala como Device Owner al aplicar la política.
      </p>
      {certs.length === 0 ? <div className="cfg-empty">Aún no hay certificados.</div> : (
        <table className="kv-table wf-cert-table">
          <tbody>
            {certs.map((c) => {
              const x = expiry(c);
              return (
                <tr key={c.id} data-testid={`wifi-cert-${c.id}`}>
                  <th>
                    {c.name}
                    <div className="muted small">{c.kind === 'CA' ? 'CA del servidor' : 'Cliente (EAP‑TLS)'}</div>
                  </th>
                  <td>
                    <div className="mono small">{c.subject}</div>
                    <div className="small"><span className={`chip tone-${x.tone}`}>{x.text}</span>{' '}
                      <span className="muted" title={c.fingerprint}>SHA‑256 {c.fingerprint.slice(0, 16)}…</span></div>
                    {c.usedBy.length > 0 && <div className="muted small">En uso: {c.usedBy.join(', ')}</div>}
                  </td>
                  <td style={{ width: 1 }}>
                    {!disabled && <button type="button" className="btn btn-sm btn-ghost" aria-label={`Eliminar certificado ${c.name}`} onClick={() => void remove(c)}>✕</button>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {!disabled && (
        <div className="wf-cert-upload">
          <select className="sel" aria-label="Tipo de certificado" value={kind} onChange={(e) => setKind(e.target.value as 'CA' | 'CLIENT')}>
            <option value="CA">CA del servidor (PEM/DER)</option>
            <option value="CLIENT">Cliente (PKCS#12)</option>
          </select>
          <input className="input" placeholder="Nombre" aria-label="Nombre del certificado" value={name} onChange={(e) => setName(e.target.value)} />
          <input key={fileKey} type="file" aria-label="Archivo del certificado" data-testid="wifi-cert-file"
            accept={kind === 'CA' ? '.pem,.crt,.cer,.der,.p7b' : '.p12,.pfx'} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          {kind === 'CLIENT' && (
            <input className="input" type="password" autoComplete="new-password" placeholder="Contraseña del PKCS#12" aria-label="Contraseña del PKCS#12"
              value={password} onChange={(e) => setPassword(e.target.value)} />
          )}
          <button type="button" className="btn btn-sm" disabled={!file || busy} onClick={() => void upload()}>{busy ? 'Subiendo…' : 'Subir'}</button>
        </div>
      )}
    </details>
  );
}

function EapFields({ i, eap, certs, disabled, set }: { i: number; eap: WifiEap; certs: WifiCertificate[]; disabled?: boolean; set: (e: WifiEap) => void }) {
  const method = eap.method ?? 'PEAP';
  const tls = method === 'TLS';
  const cas = certs.filter((c) => c.kind === 'CA');
  const clients = certs.filter((c) => c.kind === 'CLIENT');
  const chosen = eap.caCertIds ?? [];
  return (
    <div className="wf-grid" data-testid={`wifi-eap-${i}`}>
      <label>Método EAP
        <select className="sel" aria-label={`Wi‑Fi ${i + 1} método EAP`} disabled={disabled} value={method}
          onChange={(e) => {
            const m = e.target.value as WifiEap['method'];
            set({ ...eap, method: m, phase2: m === 'TLS' ? undefined : m === 'TTLS' ? 'PAP' : 'MSCHAPV2' });
          }}>
          <option value="PEAP">PEAP</option><option value="TTLS">TTLS</option><option value="TLS">TLS (certificado de cliente)</option>
        </select>
      </label>
      {!tls && (
        <label>Autenticación interna
          <select className="sel" aria-label={`Wi‑Fi ${i + 1} fase 2`} disabled={disabled} value={eap.phase2 ?? (method === 'TTLS' ? 'PAP' : 'MSCHAPV2')}
            onChange={(e) => set({ ...eap, phase2: e.target.value as WifiEap['phase2'] })}>
            <option value="MSCHAPV2">MSCHAPv2</option><option value="PAP">PAP</option><option value="GTC">GTC</option>
          </select>
        </label>
      )}
      <label>Identidad
        <input className="input" aria-label={`Wi‑Fi ${i + 1} identidad`} disabled={disabled} placeholder="usuario@empresa.com" value={eap.identity ?? ''}
          onChange={(e) => set({ ...eap, identity: e.target.value })} />
      </label>
      <label>Identidad anónima
        <input className="input" aria-label={`Wi‑Fi ${i + 1} identidad anónima`} disabled={disabled} placeholder="anonymous@empresa.com" value={eap.anonymousIdentity ?? ''}
          onChange={(e) => set({ ...eap, anonymousIdentity: e.target.value || undefined })} />
      </label>
      {!tls && (
        <label>Contraseña
          <Secret label={`Wi‑Fi ${i + 1} contraseña EAP`} value={eap.password ?? ''} saved={eap.passwordSet === true} disabled={disabled} placeholder="Contraseña"
            onChange={(v) => set({ ...eap, password: v || undefined })} />
        </label>
      )}
      <label>Dominio del servidor
        <input className="input mono" aria-label={`Wi‑Fi ${i + 1} dominio del servidor`} disabled={disabled} placeholder="radius.empresa.com" value={eap.domain ?? ''}
          onChange={(e) => set({ ...eap, domain: e.target.value || undefined })} />
      </label>
      <label>Nombre alternativo (opcional)
        <input className="input mono" aria-label={`Wi‑Fi ${i + 1} nombre alternativo`} disabled={disabled} placeholder="DNS:radius.empresa.com" value={eap.altSubjectMatch ?? ''}
          onChange={(e) => set({ ...eap, altSubjectMatch: e.target.value || undefined })} />
      </label>
      <div className="wf-wide">CA del servidor
        {cas.length === 0 ? <div className="cfg-empty">Sube la CA en «Certificados Wi‑Fi», más abajo.</div> : (
          <div className="dcp-roles" role="group" aria-label={`Wi‑Fi ${i + 1} CA`}>
            {cas.map((c) => (
              <label key={c.id} className="dcp-role" title={c.subject ?? ''}>
                <input type="checkbox" className="dev-check" disabled={disabled} checked={chosen.includes(c.id)}
                  onChange={(e) => set({ ...eap, caCertIds: e.target.checked ? [...chosen, c.id] : chosen.filter((x) => x !== c.id) })} />
                <span>{c.name}{c.status !== 'valid' ? ` · ${expiry(c).text}` : ''}</span>
              </label>
            ))}
          </div>
        )}
      </div>
      {tls && (
        <label className="wf-wide">Certificado de cliente
          <select className="sel" aria-label={`Wi‑Fi ${i + 1} certificado de cliente`} disabled={disabled} value={eap.clientCertId ?? ''}
            onChange={(e) => set({ ...eap, clientCertId: e.target.value ? Number(e.target.value) : undefined })}>
            <option value="">— elegir —</option>
            {clients.map((c) => <option key={c.id} value={c.id}>{c.name} · {expiry(c).text}</option>)}
          </select>
        </label>
      )}
    </div>
  );
}

function Network({ i, n, certs, disabled, problems, set, remove }: {
  i: number; n: WifiNetwork; certs: WifiCertificate[]; disabled?: boolean; problems: WifiProblem[];
  set: (n: WifiNetwork) => void; remove: () => void;
}) {
  const sec = n.security ?? 'WPA';
  const extras = !!(n.hidden || n.autoConnect === false || n.priority != null || n.proxy || n.ip || n.mac || n.metered);
  const [open, setOpen] = useState(extras);
  const note = SECURITIES.find((s) => s.value === sec)?.note;
  return (
    <div className="wf-net" data-testid={`wifi-net-${i}`}>
      <div className="wf-net-top">
        <input className="input" placeholder="SSID" value={n.ssid} disabled={disabled} aria-label={`Wi‑Fi ${i + 1} SSID`}
          onChange={(e) => set({ ...n, ssid: e.target.value })} />
        <select className="sel" value={sec} disabled={disabled} aria-label={`Wi‑Fi ${i + 1} seguridad`}
          onChange={(e) => {
            const s = e.target.value as WifiSecurity;
            const next: WifiNetwork = { ...n, security: s };
            if (!isKeyed(s)) { delete next.password; delete next.passwordSet; }
            if (isEnterprise(s)) next.eap = n.eap ?? { method: 'PEAP', phase2: 'MSCHAPV2' }; else delete next.eap;
            set(next);
          }}>
          {SECURITIES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
        {isKeyed(sec) ? (
          <Secret label={`Wi‑Fi ${i + 1} contraseña`} value={n.password ?? ''} saved={n.passwordSet === true} disabled={disabled} placeholder="Contraseña"
            onChange={(v) => set({ ...n, password: v || undefined })} />
        ) : <span className="muted small">{isEnterprise(sec) ? 'Credenciales abajo' : 'Sin contraseña'}</span>}
        <button type="button" className="btn btn-sm btn-ghost" disabled={disabled} aria-expanded={open} aria-label={`Wi‑Fi ${i + 1} opciones`}
          onClick={() => setOpen((v) => !v)}>{open ? 'Menos' : 'Opciones'}</button>
        <button type="button" className="btn btn-sm btn-ghost" disabled={disabled} aria-label="Quitar red" onClick={remove}>✕</button>
      </div>
      {note && <div className="muted small">{note}</div>}
      {isEnterprise(sec) && <EapFields i={i} eap={n.eap ?? {}} certs={certs} disabled={disabled} set={(eap) => set({ ...n, eap })} />}
      {open && (
        <div className="wf-grid" data-testid={`wifi-options-${i}`}>
          <label className="wf-check">
            <input type="checkbox" className="dev-check" aria-label={`Wi‑Fi ${i + 1} red oculta`} disabled={disabled} checked={n.hidden === true}
              onChange={(e) => set({ ...n, hidden: e.target.checked || undefined })} />
            <span>Red oculta (no anuncia su nombre)</span>
          </label>
          <label className="wf-check">
            <input type="checkbox" className="dev-check" aria-label={`Wi‑Fi ${i + 1} conexión automática`} disabled={disabled} checked={n.autoConnect !== false}
              onChange={(e) => set({ ...n, autoConnect: e.target.checked ? undefined : false })} />
            <span>Conectarse automáticamente</span>
          </label>
          <label>Prioridad (0–999, mayor gana)
            <input className="input" type="number" min={0} max={999} aria-label={`Wi‑Fi ${i + 1} prioridad`} disabled={disabled} placeholder="sin prioridad"
              value={n.priority ?? ''} onChange={(e) => {
                const v = parseInt(e.target.value, 10);
                set({ ...n, priority: Number.isFinite(v) ? Math.max(0, Math.min(999, v)) : undefined });
              }} />
          </label>
          <label>Dirección MAC
            <select className="sel" aria-label={`Wi‑Fi ${i + 1} MAC`} disabled={disabled} value={n.mac ?? ''}
              onChange={(e) => set({ ...n, mac: (e.target.value || undefined) as WifiNetwork['mac'] })}>
              <option value="">La que decida Android</option><option value="random">Aleatoria</option><option value="device">La del dispositivo</option>
            </select>
          </label>
          <label>Uso de datos
            <select className="sel" aria-label={`Wi‑Fi ${i + 1} medida`} disabled={disabled} value={n.metered ?? ''}
              onChange={(e) => set({ ...n, metered: (e.target.value || undefined) as WifiNetwork['metered'] })}>
              <option value="">Detección automática</option><option value="metered">Medida (datos limitados)</option><option value="unmetered">No medida</option>
            </select>
          </label>
          <label>Proxy
            <select className="sel" aria-label={`Wi‑Fi ${i + 1} proxy`} disabled={disabled} value={n.proxy?.mode ?? ''}
              onChange={(e) => set({ ...n, proxy: e.target.value ? { ...(n.proxy ?? {}), mode: e.target.value as 'manual' | 'pac' } : undefined })}>
              <option value="">Ninguno</option><option value="manual">Manual</option><option value="pac">Automático (PAC)</option>
            </select>
          </label>
          {n.proxy?.mode === 'manual' && (
            <>
              <label>Servidor proxy
                <input className="input mono" aria-label={`Wi‑Fi ${i + 1} proxy servidor`} disabled={disabled} placeholder="proxy.empresa.com" value={n.proxy.host ?? ''}
                  onChange={(e) => set({ ...n, proxy: { ...n.proxy!, host: e.target.value } })} />
              </label>
              <label>Puerto
                <input className="input" type="number" min={1} max={65535} aria-label={`Wi‑Fi ${i + 1} proxy puerto`} disabled={disabled} placeholder="8080" value={n.proxy.port ?? ''}
                  onChange={(e) => set({ ...n, proxy: { ...n.proxy!, port: parseInt(e.target.value, 10) || undefined } })} />
              </label>
              <label className="wf-wide">Sin proxy para (separados por coma)
                <input className="input mono" aria-label={`Wi‑Fi ${i + 1} proxy excepciones`} disabled={disabled} placeholder="*.empresa.com, 10.0.0.1"
                  value={(n.proxy.exclude ?? []).join(', ')}
                  onChange={(e) => set({ ...n, proxy: { ...n.proxy!, exclude: e.target.value.split(',').map((x) => x.trim()).filter(Boolean) } })} />
              </label>
            </>
          )}
          {n.proxy?.mode === 'pac' && (
            <label className="wf-wide">URL del archivo PAC
              <input className="input mono" aria-label={`Wi‑Fi ${i + 1} proxy PAC`} disabled={disabled} placeholder="https://empresa.com/proxy.pac" value={n.proxy.pacUrl ?? ''}
                onChange={(e) => set({ ...n, proxy: { ...n.proxy!, pacUrl: e.target.value } })} />
            </label>
          )}
          <label>Dirección IP
            <select className="sel" aria-label={`Wi‑Fi ${i + 1} IP`} disabled={disabled} value={n.ip ? 'static' : ''}
              onChange={(e) => set({ ...n, ip: e.target.value ? { address: '', prefix: 24 } : undefined })}>
              <option value="">Automática (DHCP)</option><option value="static">Fija</option>
            </select>
          </label>
          {n.ip && (
            <>
              <label>IP
                <input className="input mono" aria-label={`Wi‑Fi ${i + 1} IP fija`} disabled={disabled} placeholder="192.168.1.50" value={n.ip.address}
                  onChange={(e) => set({ ...n, ip: { ...n.ip!, address: e.target.value } })} />
              </label>
              <label>Prefijo
                <input className="input" type="number" min={1} max={32} aria-label={`Wi‑Fi ${i + 1} prefijo`} disabled={disabled} value={n.ip.prefix ?? 24}
                  onChange={(e) => set({ ...n, ip: { ...n.ip!, prefix: parseInt(e.target.value, 10) || 24 } })} />
              </label>
              <label>Puerta de enlace
                <input className="input mono" aria-label={`Wi‑Fi ${i + 1} puerta de enlace`} disabled={disabled} placeholder="192.168.1.1" value={n.ip.gateway ?? ''}
                  onChange={(e) => set({ ...n, ip: { ...n.ip!, gateway: e.target.value || undefined } })} />
              </label>
              <label>DNS (separados por coma)
                <input className="input mono" aria-label={`Wi‑Fi ${i + 1} DNS`} disabled={disabled} placeholder="8.8.8.8, 1.1.1.1" value={(n.ip.dns ?? []).join(', ')}
                  onChange={(e) => set({ ...n, ip: { ...n.ip!, dns: e.target.value.split(',').map((x) => x.trim()).filter(Boolean) } })} />
              </label>
            </>
          )}
          <div className="wf-wide muted small">
            La MAC, el uso de datos, la IP fija y el proxy dependen de la versión de Android: si un equipo no puede aplicar alguno, guarda la red
            sin esa opción y lo dice en su ficha (Wi‑Fi gestionado).
          </div>
        </div>
      )}
      <Problems items={problems} />
    </div>
  );
}

function Rules({ rules, disabled, set }: { rules: WifiRules; disabled?: boolean; set: (r: WifiRules) => void }) {
  const [draft, setDraft] = useState('');
  const ssids = rules.ssids ?? [];
  const add = () => {
    const fresh = draft.split(/[\n,]/).map((x) => x.trim()).filter((x) => x && !ssids.includes(x));
    if (fresh.length) set({ ...rules, ssids: [...ssids, ...fresh] });
    setDraft('');
  };
  const check = (key: 'lockManaged' | 'blockAdd' | 'blockConfig' | 'blockState', label: string, help: string) => (
    <label className="wf-rule" title={help}>
      <input type="checkbox" className="dev-check" aria-label={label} disabled={disabled} checked={rules[key] === true}
        onChange={(e) => set({ ...rules, [key]: e.target.checked || undefined })} />
      <span><b>{label}</b><span className="cfg-field-help">{help}</span></span>
    </label>
  );
  const restrictive = rules.blockAdd || rules.blockConfig || rules.blockState || rules.ssidMode || rules.minSecurity;
  return (
    <div className="wf-rules" data-testid="wifi-rules">
      {check('lockManaged', 'Bloquear las redes gestionadas', 'El usuario no puede editarlas ni olvidarlas. Todas las versiones de Android.')}
      {check('blockAdd', 'Impedir agregar redes', 'El usuario no puede guardar redes nuevas. Android 13 o superior.')}
      {check('blockConfig', 'Impedir cambiar la configuración de Wi‑Fi', 'Cierra los ajustes de Wi‑Fi por completo. Todas las versiones de Android.')}
      {check('blockState', 'Impedir encender o apagar el Wi‑Fi', 'El interruptor de Wi‑Fi deja de responder al usuario. Android 13 o superior. (Forzar el radio encendido o apagado está en Red → Wi‑Fi.)')}
      <div className="wf-grid">
        <label>Redes a las que puede conectarse
          <select className="sel" aria-label="Redes permitidas" disabled={disabled} value={rules.ssidMode ?? ''}
            onChange={(e) => set({ ...rules, ssidMode: (e.target.value || undefined) as WifiRules['ssidMode'], ssids: e.target.value ? rules.ssids : undefined })}>
            <option value="">Cualquiera</option>
            <option value="allow">Solo las gestionadas y las de esta lista</option>
            <option value="deny">Todas menos las de esta lista</option>
          </select>
        </label>
        <label>Seguridad mínima de la red
          <select className="sel" aria-label="Seguridad mínima" disabled={disabled} value={rules.minSecurity ?? ''}
            onChange={(e) => set({ ...rules, minSecurity: (e.target.value || undefined) as WifiRules['minSecurity'] })}>
            <option value="">Cualquiera (también abiertas)</option>
            <option value="personal">Personal (WPA2/WPA3 con contraseña) o mejor</option>
            <option value="enterprise">Empresarial (802.1X) o mejor</option>
            <option value="enterprise192">Empresarial de 192 bits</option>
          </select>
        </label>
        {rules.ssidMode && (
          <div className="wf-wide">
            <div className="dcp-sites-add">
              <input className="input" aria-label="Agregar SSID a la lista" disabled={disabled} placeholder="Nombre de la red (SSID)" value={draft}
                onChange={(e) => setDraft(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
              <button type="button" className="btn btn-sm" disabled={disabled || !draft.trim()} onClick={add}>Agregar</button>
            </div>
            {ssids.length === 0 ? (
              <div className="cfg-empty">{rules.ssidMode === 'allow' ? 'Solo las redes gestionadas de arriba.' : 'Aún no hay redes bloqueadas.'}</div>
            ) : (
              <ul className="dcp-sites-list" aria-label="Lista de SSID">
                {ssids.map((s) => (
                  <li key={s}><span>{s}</span>
                    {!disabled && <button type="button" className="btn btn-sm btn-ghost" aria-label={`Quitar ${s}`} onClick={() => set({ ...rules, ssids: ssids.filter((x) => x !== s) })}>✕</button>}
                  </li>
                ))}
              </ul>
            )}
            <div className="muted small">Las listas de redes y la seguridad mínima necesitan Android 13 o superior; en equipos anteriores no se aplican y la ficha del equipo lo indica.</div>
          </div>
        )}
        {restrictive && (
          <label>Salvaguarda: relajar tras
            <span className="wf-inline">
              <input className="input" type="number" min={1} max={120} aria-label="Minutos de salvaguarda" disabled={disabled} style={{ width: 80 }}
                value={rules.safeguardMinutes ?? 10}
                onChange={(e) => {
                  const v = parseInt(e.target.value, 10);
                  set({ ...rules, safeguardMinutes: Number.isFinite(v) ? Math.max(1, Math.min(120, v)) : undefined });
                }} />
              <span className="muted">minutos sin ninguna red</span>
            </span>
          </label>
        )}
      </div>
    </div>
  );
}

export function WifiPolicyEditor({ networks, rules, disabled, radio, onChange }: {
  networks: WifiNetwork[]; rules: WifiRules | undefined; disabled?: boolean;
  /** The policy's Wi-Fi radio setting, when the caller knows it (false = forced off). */
  radio?: boolean | null;
  onChange: (networks: WifiNetwork[], rules: WifiRules | undefined) => void;
}) {
  const [certs, setCerts] = useState<WifiCertificate[]>([]);
  const reload = useCallback(() => { void listWifiCertificates().then(setCerts).catch(() => setCerts([])); }, []);
  useEffect(() => { reload(); }, [reload]);

  const problems = wifiProblems(networks, rules, radio);
  const general = problems.filter((p) => p.network === undefined);
  const setNet = (i: number, n: WifiNetwork) => onChange(networks.map((x, j) => (j === i ? n : x)), rules);

  return (
    <div className="wf" data-testid="policy-wifi">
      {networks.map((n, i) => (
        <Network key={n.id ?? i} i={i} n={n} certs={certs} disabled={disabled} problems={problems.filter((p) => p.network === i)}
          set={(x) => setNet(i, x)} remove={() => onChange(networks.filter((_, j) => j !== i), rules)} />
      ))}
      <button type="button" className="btn btn-sm" disabled={disabled || networks.length >= 20} style={{ alignSelf: 'flex-end' }}
        onClick={() => onChange([...networks, { id: newNetworkId(), ssid: '', security: 'WPA' }], rules)}>+ Agregar red</button>
      <Certificates certs={certs} disabled={disabled} reload={reload} />
      <div className="wf-sub">Bloqueos de Wi‑Fi</div>
      <Rules rules={rules ?? {}} disabled={disabled} set={(r) => onChange(networks, r)} />
      <Problems items={general} />
    </div>
  );
}
