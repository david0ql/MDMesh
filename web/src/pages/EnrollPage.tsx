import { useEffect, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { IconCopy } from '../ui/icons';
import { useToast } from '../ui/toast';
import { mintEnrollToken } from '../api/enroll';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import { ApiError } from '../api/client';
import { fmtDateTime } from '../ui/format';
import { QrCanvas } from '../components/QrCanvas';
import { EnrollmentCodesPanel } from '../components/EnrollmentCodesPanel';
import { NoResetEnrollPanel } from '../components/NoResetEnrollPanel';
import { buildProvisioningPayload, serverBaseUrl, agentApkUrl, type WifiSecurity } from '../enroll/provisioning';
import { getConfigurations, type Configuration } from '../api/configurations';

/** The group last enrolled into, remembered per browser for the next enrollment. */
const ENROLL_GROUP_KEY = 'dallycontrol-enroll-group';
const SECURITY_VALUES: WifiSecurity[] = ['WPA', 'WEP', 'NONE', 'EAP'];

const STEPS = [
  { title: 'Parte de un dispositivo formateado (restablecido de fábrica)', sub: 'En la primera pantalla de bienvenida ("Hola"), todavía no inicies sesión.' },
  { title: 'Toca la pantalla 6 veces', sub: 'Así se abre el escáner QR de aprovisionamiento. Conéctate al Wi‑Fi si te lo pide.' },
  { title: 'Escanea este código', sub: 'Android descarga el agente de DallyControl y lo configura como propietario del dispositivo.' },
  { title: 'Espera la inscripción', sub: 'El dispositivo aparece en Dispositivos después de su primera conexión.' },
];

type Mode = 'qr' | 'token' | 'codes' | 'noreset';

export function EnrollPage() {
  const toast = useToast();
  const [mode, setMode] = useState<Mode>('qr');
  const [token, setToken] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<number | undefined>();
  const [busy, setBusy] = useState(false);
  const [tokError, setTokError] = useState<string | null>(null);
  const [wifiSsid, setWifiSsid] = useState('');
  const [wifiPass, setWifiPass] = useState('');
  const [wifiSec, setWifiSec] = useState<WifiSecurity>('WPA');
  const [configs, setConfigs] = useState<Configuration[]>([]);
  // Only fills the Wi-Fi fields below; the device's configuration comes from its group (or the global one).
  const [cfgId, setCfgId] = useState<string>('');
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [groupId, setGroupId] = useState<string>(() => {
    try { return localStorage.getItem(ENROLL_GROUP_KEY) ?? ''; } catch { return ''; }
  });
  useEffect(() => { listGroups().then((o) => setGroups(o.groups)).catch(() => undefined); }, []);
  useEffect(() => {
    try { if (groupId) localStorage.setItem(ENROLL_GROUP_KEY, groupId); else localStorage.removeItem(ENROLL_GROUP_KEY); }
    catch { /* ignore */ }
  }, [groupId]);

  // Load configurations so the enroller can pull a config's saved provisioning Wi-Fi into the QR.
  useEffect(() => { getConfigurations().then(setConfigs).catch(() => undefined); }, []);

  // When a configuration is selected, fill the Wi-Fi fields from its saved values (still editable).
  useEffect(() => {
    const c = configs.find((x) => String(x.id) === cfgId);
    if (!c) return;
    setWifiSsid(typeof c.wifiSSID === 'string' ? c.wifiSSID : '');
    setWifiPass(typeof c.wifiPassword === 'string' ? c.wifiPassword : '');
    const sec = typeof c.wifiSecurityType === 'string' ? c.wifiSecurityType : '';
    setWifiSec((SECURITY_VALUES as string[]).includes(sec) ? (sec as WifiSecurity) : 'WPA');
  }, [cfgId, configs]);

  async function generate() {
    setBusy(true);
    setTokError(null);
    try {
      const gid = Number(groupId);
      const res = await mintEnrollToken({ groupId: Number.isFinite(gid) && gid > 0 ? gid : undefined });
      if (res.token) {
        setToken(res.token);
        setExpiresAt(res.expiresAt);
      } else {
        setTokError('El servidor no devolvió un token.');
      }
    } catch (err) {
      if (err instanceof ApiError && err.httpStatus === 0) setTokError('No se puede conectar con el servidor.');
      else if (err instanceof ApiError) setTokError(err.message || 'No se pudo generar un token.');
      else setTokError('No se pudo generar un token.');
    } finally {
      setBusy(false);
    }
  }

  // Mint a token whenever the selected group changes (including first load): the token puts the device in
  // that group server-side, so a QR shown for AMOVIL must never carry a token minted for another group.
  useEffect(() => {
    void generate();
    // eslint-disable-next-line
  }, [groupId]);

  async function copy() {
    if (!token) return;
    try {
      await navigator.clipboard.writeText(token);
      toast.push('ok', 'Token copiado', 'Token de inscripción copiado al portapapeles.');
    } catch {
      toast.push('err', 'No se pudo copiar', 'Selecciona y copia el token manualmente.');
    }
  }

  return (
    <AppShell title="Inscribir">
      <div className="enroll">
        <div className="enroll-top">
          <h1 style={{ fontSize: 24, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>
            Inscribir un dispositivo
          </h1>
          <div className="sp" />
          <label className="enroll-group">
            <span>Carpeta</span>
            <select className="sel" value={groupId} onChange={(e) => setGroupId(e.target.value)} aria-label="Carpeta">
              <option value="">Sin carpeta</option>
              {groupTree(groups).map((n) => <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>)}
            </select>
          </label>
          <span className="seg" role="tablist" aria-label="Método de inscripción">
            <button className={mode === 'qr' ? 'on' : ''} onClick={() => setMode('qr')}>Escanear QR</button>
            <button className={mode === 'token' ? 'on' : ''} onClick={() => setMode('token')}>Token</button>
            <button className={mode === 'codes' ? 'on' : ''} onClick={() => setMode('codes')}>Códigos de carpeta</button>
            <button className={mode === 'noreset' ? 'on' : ''} onClick={() => setMode('noreset')}>Sin formatear</button>
          </span>
        </div>

        {mode === 'qr' && (
          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title">Escanea para inscribir</h2>
              <button className="btn btn-sm" onClick={() => void generate()} disabled={busy}>
                {busy ? <span key="busy">Generando…</span> : <span key="idle">Nuevo código</span>}
              </button>
            </div>
            {tokError && <div className="banner banner-alert">{tokError}</div>}
            <div className="qr-layout">
              <div>
                <div className="qr-frame">
                  {token ? (
                    <QrCanvas
                      text={buildProvisioningPayload(
                        token,
                        wifiSsid.trim() ? { ssid: wifiSsid, password: wifiPass, security: wifiSec } : undefined,
                      )}
                      size={320}
                    />
                  ) : (
                    <div className="empty"><span className="spin" /> Preparando…</div>
                  )}
                </div>
                <div className="qr-cap">
                  Un solo uso{expiresAt ? ` · vence ${fmtDateTime(expiresAt)}` : ''}
                  {wifiSsid.trim() ? ` · se conecta al Wi‑Fi “${wifiSsid.trim()}”` : ''}
                </div>
                <details className="wifi-block" open={!!wifiSsid.trim()}>
                  <summary>Conectar a Wi‑Fi durante la configuración (opcional)</summary>
                  <div className="wifi-fields">
                    {configs.length > 0 && (
                      <label>
                        Cargar Wi‑Fi desde una configuración
                        <select className="sel" value={cfgId} onChange={(e) => setCfgId(e.target.value)}>
                          <option value="">— ninguna / ingresar manualmente —</option>
                          {configs.map((c) => (
                            <option key={String(c.id)} value={String(c.id)}>{c.name}</option>
                          ))}
                        </select>
                      </label>
                    )}
                    <label>
                      Nombre de la red (SSID)
                      <input value={wifiSsid} onChange={(e) => setWifiSsid(e.target.value)} placeholder="WiFi-Oficina" />
                    </label>
                    <label>
                      Seguridad
                      <select className="sel" value={wifiSec} onChange={(e) => setWifiSec(e.target.value as WifiSecurity)}>
                        <option value="WPA">WPA / WPA2</option>
                        <option value="WEP">WEP</option>
                        <option value="NONE">Abierta (sin contraseña)</option>
                      </select>
                    </label>
                    {wifiSec !== 'NONE' && (
                      <label>
                        Contraseña
                        <input type="password" value={wifiPass} onChange={(e) => setWifiPass(e.target.value)} autoComplete="off" />
                      </label>
                    )}
                    <p className="note">
                      El dispositivo se conecta a esta red durante el aprovisionamiento (antes de descargar el agente).
                      Ojo: la contraseña va incluida en el QR; muéstralo solo a personas de confianza que vayan a inscribir.
                    </p>
                  </div>
                </details>
              </div>
              <ol className="qr-steps" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                {STEPS.map((s, i) => (
                  <li className="qr-step" key={i}>
                    <span className="step-n">{i + 1}</span>
                    <span className="st-tx">{s.title}<span className="sub">{s.sub}</span></span>
                  </li>
                ))}
              </ol>
            </div>
            <p className="note" style={{ padding: '0 20px 16px' }}>
              Servidor <span className="mono">{serverBaseUrl()}</span> · agente{' '}
              <span className="mono">{agentApkUrl()}</span>. Publica el APK del agente en esa URL.
            </p>
          </section>
        )}

        {mode === 'codes' && <EnrollmentCodesPanel groups={groups} />}
        {mode === 'noreset' && <NoResetEnrollPanel token={token ?? undefined} />}

        {mode === 'token' && (
          <section className="panel enroll-wrap">
            <div className="panel-head">
              <h2 className="panel-title">Token de inscripción</h2>
              <button className="btn btn-primary btn-sm" onClick={() => void generate()} disabled={busy}>
                {busy ? <span key="busy">Generando…</span> : token ? <span key="again">Generar otro</span> : <span key="first">Generar token</span>}
              </button>
            </div>
            <div style={{ padding: 20 }}>
              {tokError && <div className="banner banner-alert">{tokError}</div>}
              {token ? (
                <>
                  <div className="token-box">
                    <span className="tok">{token}</span>
                    <button className="btn btn-sm btn-ghost" onClick={() => void copy()} aria-label="Copiar token">
                      <IconCopy className="ico" />
                    </button>
                  </div>
                  <p className="note">
                    Token de un solo uso{expiresAt ? `, vence ${fmtDateTime(expiresAt)}` : ''}. Para
                    aprovisionamiento sin pantalla o por script: inclúyelo como{' '}
                    <span className="mono">com.dallycontrol.ENROLL_TOKEN</span> (con{' '}
                    <span className="mono">com.dallycontrol.SERVER_URL</span>). Lo habitual es usar el QR.
                  </p>
                </>
              ) : (
                <p className="note">Para aprovisionamiento sin pantalla o por script. Para el flujo habitual, escanea el QR.</p>
              )}
            </div>
          </section>
        )}
      </div>
    </AppShell>
  );
}
