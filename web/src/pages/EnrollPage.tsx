import { useEffect, useMemo, useRef, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { IconCopy } from '../ui/icons';
import { useToast } from '../ui/toast';
import { attemptReason, getTokenStatus, mintEnrollToken, type TokenStatus } from '../api/enroll';
import { EnrollAttemptsPanel } from '../components/EnrollAttemptsPanel';
import { useAuth } from '../auth/AuthContext';
import { isFolderAdmin } from '../api/auth';
import { groupTree, listGroups, type FleetGroup } from '../api/fleet';
import { ApiError } from '../api/client';
import { fmtDateTime } from '../ui/format';
import { QrCanvas, downloadQrPng } from '../components/QrCanvas';
import { EnrollmentCodesPanel } from '../components/EnrollmentCodesPanel';
import { NoResetEnrollPanel } from '../components/NoResetEnrollPanel';
import { UsbEnrollPanel } from '../components/UsbEnrollPanel';
import {
  agentScanPayload, buildProvisioningPayload, clockNow, qrDensity, serverBaseUrl, agentApkUrl,
  type EapMethod, type EapPhase2, type WifiConfig, type WifiSecurity,
} from '../enroll/provisioning';
import { getConfigurations, type Configuration } from '../api/configurations';

/** The group last enrolled into, remembered per browser for the next enrollment. */
const ENROLL_GROUP_KEY = 'dallycontrol-enroll-group';
const SECURITY_VALUES: WifiSecurity[] = ['WPA', 'WEP', 'NONE', 'EAP'];

const STEPS = [
  { title: 'Parte de un dispositivo formateado (restablecido de fábrica)', sub: 'En la primera pantalla de bienvenida ("Hola"), todavía no inicies sesión.' },
  { title: 'Toca la pantalla 6 veces', sub: 'Así se abre el escáner QR de aprovisionamiento (Android 7 o superior). En Android 7 y 8 el teléfono pide antes un Wi‑Fi con internet para descargar el lector.' },
  { title: 'Escanea este código', sub: 'Android descarga el agente de DallyControl y lo configura como propietario del dispositivo.' },
  { title: 'Espera la inscripción', sub: 'El teléfono muestra «Equipo inscrito» y esta página lo confirma. Si falla, el motivo sale en el teléfono y abajo, en «Intentos de inscripción».' },
];

type Mode = 'qr' | 'token' | 'codes' | 'noreset' | 'usb';

export function EnrollPage() {
  const toast = useToast();
  const { user } = useAuth();
  const [mode, setMode] = useState<Mode>('qr');
  const [token, setToken] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<number | undefined>();
  const [busy, setBusy] = useState(false);
  const [tokError, setTokError] = useState<string | null>(null);
  const [wifiSsid, setWifiSsid] = useState('');
  const [wifiPass, setWifiPass] = useState('');
  const [wifiSec, setWifiSec] = useState<WifiSecurity>('WPA');
  const [wifiHidden, setWifiHidden] = useState(false);
  const [eapMethod, setEapMethod] = useState<EapMethod>('PEAP');
  const [eapPhase2, setEapPhase2] = useState<EapPhase2>('MSCHAPV2');
  const [eapIdentity, setEapIdentity] = useState('');
  const [eapAnon, setEapAnon] = useState('');
  const [eapDomain, setEapDomain] = useState('');
  const [proxyHost, setProxyHost] = useState('');
  const [proxyPort, setProxyPort] = useState('');
  const [proxyBypass, setProxyBypass] = useState('');
  // The QR carries the current time (a phone out of the box boots with its factory date and then rejects the
  // server's certificate). Re-drawn every 30 s so the time it carries is never stale.
  const [nowTick, setNowTick] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNowTick(Date.now()), 30_000);
    return () => window.clearInterval(t);
  }, []);
  // For phones that boot with a factory date: download the agent over plain HTTP (see plainHttpUrl in payload.ts).
  const [plainHttp, setPlainHttp] = useState(false);
  const [status, setStatus] = useState<TokenStatus | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [lastEnrolled, setLastEnrolled] = useState<{ deviceNumber: string; configurationName?: string } | null>(null);
  const [configs, setConfigs] = useState<Configuration[]>([]);
  // Only fills the Wi-Fi fields below; the device's configuration comes from its group (or the global one).
  const [cfgId, setCfgId] = useState<string>('');
  const [groups, setGroups] = useState<FleetGroup[]>([]);
  const [groupId, setGroupId] = useState<string>(() => {
    try { return localStorage.getItem(ENROLL_GROUP_KEY) ?? ''; } catch { return ''; }
  });
  // Operator-declared phone number of the device's SIM. Stored with the token and copied onto the device at
  // enrollment; the server later compares it against the number the SIM reports and flags a mismatch.
  const [expectedPhone, setExpectedPhone] = useState('');
  // Optional email account tagged onto the enrolled device (metadata/identification only; no account is created on
  // the phone). The device inherits it (device > group/folder). Baked into the token, so changing it re-mints.
  const [accountEmail, setAccountEmail] = useState('');
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

  const wifi: WifiConfig | undefined = useMemo(() => {
    if (!wifiSsid.trim()) return undefined;
    const port = Number(proxyPort);
    return {
      ssid: wifiSsid,
      password: wifiPass,
      security: wifiSec,
      hidden: wifiHidden,
      eap: wifiSec === 'EAP'
        ? { method: eapMethod, phase2: eapPhase2, identity: eapIdentity, anonymousIdentity: eapAnon, domain: eapDomain }
        : undefined,
      proxy: proxyHost.trim() ? { host: proxyHost, port: Number.isFinite(port) && port > 0 ? port : undefined, bypass: proxyBypass } : undefined,
    };
  }, [wifiSsid, wifiPass, wifiSec, wifiHidden, eapMethod, eapPhase2, eapIdentity, eapAnon, eapDomain, proxyHost, proxyPort, proxyBypass]);
  // On screen: with the current time. Downloaded: with the time of the download (the caption says when).
  const qrText = token ? buildProvisioningPayload(token, wifi, { clock: clockNow(nowTick), plainHttpDownload: plainHttp }) : '';
  const density = qrText ? qrDensity(qrText, 'L') : null;

  async function generate() {
    setStatus(null);
    const gid = Number(groupId);
    const validGroup = Number.isFinite(gid) && gid > 0;
    // A folder editor can only enroll into one of their own folders; the server refuses an unscoped token. Guide
    // them to pick a folder instead of surfacing a raw "permiso denegado".
    if (isFolderAdmin(user) && !validGroup) {
      setToken(null);
      setTokError('Selecciona una de tus carpetas para generar el código de inscripción.');
      return;
    }
    setBusy(true);
    setTokError(null);
    try {
      const res = await mintEnrollToken({
        groupId: validGroup ? gid : undefined,
        expectedPhone,
        accountEmail: accountEmail.trim() || undefined,
      });
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

  const generateRef = useRef(generate);
  generateRef.current = generate;
  useEffect(() => {
    if (!token || (mode !== 'qr' && mode !== 'token')) return undefined;
    let alive = true;
    const check = async () => {
      // A token whose time ran out while the page stayed open: replace it before anyone scans a dead QR.
      if (expiresAt && Date.now() > expiresAt - 60_000) {
        setNotice('El código anterior venció; se generó uno nuevo.');
        void generateRef.current();
        return;
      }
      try {
        const st = await getTokenStatus(token);
        if (!alive) return;
        setStatus(st);
        if (st.deviceNumber) {
          setLastEnrolled({ deviceNumber: st.deviceNumber, configurationName: st.configurationName });
          toast.push('ok', 'Equipo inscrito', st.configurationName ? `Política: ${st.configurationName}` : st.deviceNumber);
          setNotice(null);
          // One QR, one phone: have the next one ready.
          void generateRef.current();
        }
      } catch { /* the next tick tries again */ }
    };
    const t = window.setInterval(() => void check(), 4_000);
    return () => { alive = false; window.clearInterval(t); };
    // eslint-disable-next-line
  }, [token, expiresAt, mode]);

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
          <label className="enroll-group">
            <span>Celular esperado</span>
            <input
              className="input"
              type="tel"
              inputMode="tel"
              value={expectedPhone}
              onChange={(e) => setExpectedPhone(e.target.value)}
              placeholder="opcional, p. ej. 3001234567"
              aria-label="Número de celular esperado"
              title="Número de la SIM que debería tener el equipo. Al inscribir se compara con el número real; si difiere se genera una alerta. Tras cambiarlo, genera un nuevo código."
            />
          </label>
          <label className="enroll-group">
            <span>Cuenta de correo (opcional)</span>
            <input
              className="sel"
              type="email"
              aria-label="Cuenta de correo"
              placeholder="correo@empresa.com"
              value={accountEmail}
              onChange={(e) => setAccountEmail(e.target.value)}
              onBlur={() => void generate()}
            />
          </label>
          <span className="seg" role="tablist" aria-label="Método de inscripción">
            <button className={mode === 'qr' ? 'on' : ''} onClick={() => setMode('qr')}>Escanear QR</button>
            <button className={mode === 'token' ? 'on' : ''} onClick={() => setMode('token')}>Token</button>
            <button className={mode === 'codes' ? 'on' : ''} onClick={() => setMode('codes')}>Códigos de carpeta</button>
            <button className={mode === 'noreset' ? 'on' : ''} onClick={() => setMode('noreset')}>Sin formatear</button>
            <button className={mode === 'usb' ? 'on' : ''} onClick={() => setMode('usb')}>Por cable (USB)</button>
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
                    <QrCanvas text={qrText} size={360} level="L" />
                  ) : (
                    <div className="empty"><span className="spin" /> Preparando…</div>
                  )}
                </div>
                <div className="qr-cap">
                  Un solo uso{expiresAt ? ` · vence ${fmtDateTime(expiresAt)}` : ''}
                  {wifiSsid.trim() ? ` · se conecta al Wi‑Fi “${wifiSsid.trim()}”${wifiHidden ? ' (oculta)' : ''}` : ''}
                  {' · lleva la hora actual (se actualiza solo)'}
                </div>
                {notice && <div className="banner banner-warn" data-testid="qr-notice" style={{ marginTop: 10 }}>{notice}</div>}
                {density?.dense && (
                  <div className="banner banner-warn" data-testid="qr-dense" style={{ marginTop: 10 }}>
                    Este QR lleva muchos datos ({density.bytes} bytes) y queda muy fino. Sube el brillo, amplía la página
                    (Ctrl +) y acerca el teléfono; o quita el proxy / 802.1X del QR y configúralos en la política.
                  </div>
                )}
                <div className="note" data-testid="qr-status" style={{ marginTop: 8 }}>
                  {status && status.attempts.length > 0
                    ? <>Último movimiento con este QR: <b>{attemptReason(status.attempts[0])}</b>
                        {status.attempts[0].model ? ` · ${[status.attempts[0].manufacturer, status.attempts[0].model].filter(Boolean).join(' ')}` : ''}
                        {status.attempts[0].androidRelease ? ` · Android ${status.attempts[0].androidRelease}` : ''}</>
                    : 'Esperando a que un teléfono escanee este QR…'}
                  {lastEnrolled && (
                    <div data-testid="qr-enrolled">
                      Último equipo inscrito: <span className="mono">{lastEnrolled.deviceNumber}</span>
                      {lastEnrolled.configurationName ? ` · política ${lastEnrolled.configurationName}` : ''}
                    </div>
                  )}
                </div>
                {token && (
                  <button className="btn btn-sm" data-testid="qr-download"
                    onClick={() => void downloadQrPng(
                      buildProvisioningPayload(token, wifi, { clock: clockNow(), plainHttpDownload: plainHttp }),
                      `qr-inscripcion-${new Date().toISOString().slice(0, 10)}.png`,
                      ['Inscripción DallyControl', `Un solo uso${expiresAt ? ` · vence ${fmtDateTime(expiresAt)}` : ''}`,
                        `Generado ${fmtDateTime(Date.now())} · úsalo hoy: lleva esta hora para poner en fecha el teléfono`,
                        wifiSsid.trim() ? `Wi‑Fi: ${wifiSsid.trim()}` : ''],
                    )}>
                    Descargar QR
                  </button>
                )}
                {token && (
                  <details className="wifi-block" data-testid="agent-qr">
                    <summary>Equipo ya administrado que no se inscribió (sin formatear de nuevo)</summary>
                    <div className="wifi-fields">
                      <p className="note">
                        Si el teléfono ya muestra DallyControl como administrador pero no aparece en la consola (el asistente
                        se abandonó o el fabricante no entregó el QR al agente): en el teléfono abre <b>DallyControl</b> →
                        <b> Escanear el QR de la consola</b> y apunta a este QR pequeño. Lleva el mismo código de un solo uso.
                      </p>
                      <div className="qr-frame" style={{ maxWidth: 240 }}>
                        <QrCanvas text={agentScanPayload(token)} size={220} level="L" />
                      </div>
                    </div>
                  </details>
                )}
                <details className="wifi-block" data-testid="plain-http" open={plainHttp}>
                  <summary>Equipo con la fecha de fábrica (se queda en «Preparando…» y no descarga el agente)</summary>
                  <div className="wifi-fields">
                    <label style={{ display: 'flex', gap: 8, alignItems: 'center', gridAutoFlow: 'column', justifyContent: 'start' }}>
                      <input type="checkbox" checked={plainHttp} onChange={(e) => setPlainHttp(e.target.checked)}
                        style={{ width: 'auto' }} data-testid="plain-http-toggle" />
                      Descargar el agente sin HTTPS en este QR
                    </label>
                    <p className="note">
                      Un teléfono con la fecha años atrás rechaza cualquier certificado HTTPS. Lo normal es que se corrija solo
                      en segundos al entrar al Wi‑Fi (hora de red) y este QR funcione sin tocar nada: para eso el Wi‑Fi debe
                      dejar salir la hora de red (NTP, UDP 123 hacia <span className="mono">time.android.com</span>) o el equipo
                      debe tener una SIM con señal. Si la red no da la hora, Android marca el Wi‑Fi como «conexión limitada» y no
                      descarga el agente con ningún QR (Android 10 o superior): corrige la fecha a mano, o inscribe por cable.
                      Esta opción cubre el caso restante: el equipo sí descarga pero su fecha sigue mal para el certificado del
                      servidor. Sin HTTPS no hay certificado que rechazar, y Android igual solo instala el agente si está firmado
                      con el certificado de este QR; el agente corrige luego la fecha por su cuenta (Android 9 o superior).
                      Requiere que el servidor entregue <span className="mono"> /files/…apk </span> por HTTP sin redirigir a
                      HTTPS (en Cloudflare: una regla que excluya esa ruta de «Always Use HTTPS»).
                    </p>
                  </div>
                </details>
                <details className="wifi-block" open={!!wifiSsid.trim()}>
                  <summary>Conectar a Wi‑Fi durante la puesta en marcha (opcional)</summary>
                  <div className="wifi-fields">
                    {configs.length > 0 && (
                      <label>
                        Cargar Wi‑Fi desde una política
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
                        <option value="EAP">Empresarial 802.1X (EAP)</option>
                      </select>
                    </label>
                    <label style={{ display: 'flex', gap: 8, alignItems: 'center', gridAutoFlow: 'column', justifyContent: 'start' }}>
                      <input type="checkbox" checked={wifiHidden} onChange={(e) => setWifiHidden(e.target.checked)}
                        style={{ width: 'auto' }} data-testid="wifi-hidden" />
                      Red oculta (no difunde su nombre)
                    </label>
                    {wifiSec === 'EAP' && (
                      <>
                        <label>
                          Método EAP
                          <select className="sel" value={eapMethod} onChange={(e) => setEapMethod(e.target.value as EapMethod)}>
                            <option value="PEAP">PEAP</option><option value="TTLS">TTLS</option><option value="PWD">PWD</option>
                            <option value="TLS">TLS</option><option value="SIM">SIM</option><option value="AKA">AKA</option>
                          </select>
                        </label>
                        <label>
                          Autenticación de fase 2
                          <select className="sel" value={eapPhase2} onChange={(e) => setEapPhase2(e.target.value as EapPhase2)}>
                            <option value="MSCHAPV2">MSCHAPv2</option><option value="GTC">GTC</option><option value="PAP">PAP</option>
                            <option value="MSCHAP">MSCHAP</option><option value="NONE">Ninguna</option>
                          </select>
                        </label>
                        <label>Usuario (identidad)<input value={eapIdentity} onChange={(e) => setEapIdentity(e.target.value)} autoComplete="off" /></label>
                        <label>Identidad anónima (opcional)<input value={eapAnon} onChange={(e) => setEapAnon(e.target.value)} autoComplete="off" /></label>
                        <label>Dominio del servidor RADIUS<input value={eapDomain} onChange={(e) => setEapDomain(e.target.value)} placeholder="radius.empresa.com" /></label>
                        <p className="note">
                          802.1X en el QR funciona desde Android 10. El teléfono valida el certificado del RADIUS con las
                          autoridades del sistema para ese dominio; un certificado propio no cabe en un QR (usa una red
                          WPA de puesta en marcha y aplica la red 802.1X desde la política).
                        </p>
                      </>
                    )}
                    {wifiSec !== 'NONE' && (
                      <label>
                        Contraseña
                        <input type="password" value={wifiPass} onChange={(e) => setWifiPass(e.target.value)} autoComplete="off" />
                      </label>
                    )}
                    <label>
                      Proxy de esa red (opcional)
                      <input value={proxyHost} onChange={(e) => setProxyHost(e.target.value)} placeholder="proxy.empresa.com" data-testid="proxy-host" />
                    </label>
                    {proxyHost.trim() && (
                      <>
                        <label>Puerto del proxy<input value={proxyPort} inputMode="numeric" onChange={(e) => setProxyPort(e.target.value)} placeholder="8080" /></label>
                        <label>Sin proxy para (opcional)<input value={proxyBypass} onChange={(e) => setProxyBypass(e.target.value)} placeholder="localhost,*.empresa.local" /></label>
                      </>
                    )}
                    <p className="note">
                      El dispositivo se conecta a esta red durante el aprovisionamiento (antes de descargar el agente).
                      La red debe dar internet sin página de inicio de sesión (portal cautivo).
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

        {mode === 'qr' && (
          <section className="panel" style={{ marginTop: 20 }} data-testid="qr-troubleshoot">
            <div className="panel-head"><h2 className="panel-title">Si el QR no funciona</h2></div>
            <ul className="note" style={{ padding: '4px 20px 16px 38px', lineHeight: 1.65, margin: 0 }}>
              <li><b>No abre el lector al tocar 6 veces:</b> el equipo no está recién formateado, o es Android 6 o anterior (sin QR: usa «Por cable» o «Sin formatear»). En Android 7–8 primero pide Wi‑Fi para descargar el lector.</li>
              <li><b>«No se puede configurar el dispositivo» antes de descargar:</b> el Wi‑Fi no tiene internet, pide iniciar sesión, o el teléfono trae la fecha de fábrica y la red no le da la hora (aparece «conexión limitada» o vuelve a pedir Wi‑Fi). Usa una red sin portal que permita la hora de red (NTP, UDP 123) o pon una SIM con señal.</li>
              <li><b>Pide la cuenta de Google anterior:</b> el equipo tiene protección contra restablecimiento (FRP). Hay que quitar la cuenta antes de formatear, o entrar con ella.</li>
              <li><b>Samsung:</b> si tiene Knox Mobile Enrollment asignado a otro MDM, ese perfil gana; pide al revendedor que lo libere o usa «Exportar Zero‑Touch / KME» en Códigos de carpeta.</li>
              <li><b>Xiaomi / HyperOS y otros sin el lector integrado:</b> si no aparece el lector, conecta Wi‑Fi cuando lo pida; si aun así no aparece, usa «Por cable (USB)».</li>
              <li><b>Descarga pero no inscribe:</b> mira «Intentos de inscripción» abajo: dirá si descargó el agente y por qué no terminó.</li>
            </ul>
          </section>
        )}
        {mode === 'codes' && <EnrollmentCodesPanel groups={groups} />}
        {mode === 'noreset' && <NoResetEnrollPanel token={token ?? undefined} groups={groups} />}
        {mode === 'usb' && <UsbEnrollPanel />}

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

        <EnrollAttemptsPanel />
      </div>
    </AppShell>
  );
}
