// The Wi-Fi part of a policy (see common DcWifi.java, which is the authority: it cleans and validates on save).
// This file holds the shapes and the same checks, so the editor can say what is wrong before saving.

export type WifiSecurity = 'NONE' | 'OWE' | 'WEP' | 'WPA' | 'WPA3' | 'WPA3_TRANSITION' | 'EAP' | 'EAP_WPA3';

export interface WifiEap {
  method?: 'PEAP' | 'TTLS' | 'TLS';
  phase2?: 'MSCHAPV2' | 'PAP' | 'GTC';
  identity?: string;
  anonymousIdentity?: string;
  password?: string;
  /** From the server: a password is stored (it never comes back). Sent back to keep it. */
  passwordSet?: boolean;
  domain?: string;
  altSubjectMatch?: string;
  caCertIds?: number[];
  clientCertId?: number;
}

export interface WifiNetwork {
  /** Stable id inside the policy: the stored password is kept by it. */
  id?: string;
  ssid: string;
  password?: string;
  passwordSet?: boolean;
  security?: WifiSecurity;
  hidden?: boolean;
  /** false = saved but never joined on its own. */
  autoConnect?: boolean;
  priority?: number;
  proxy?: { mode: 'manual' | 'pac'; host?: string; port?: number; exclude?: string[]; pacUrl?: string };
  ip?: { address: string; prefix?: number; gateway?: string; dns?: string[] };
  mac?: 'random' | 'device';
  metered?: 'metered' | 'unmetered';
  eap?: WifiEap;
}

export interface WifiRules {
  lockManaged?: boolean;
  blockAdd?: boolean;
  blockConfig?: boolean;
  blockState?: boolean;
  ssidMode?: 'allow' | 'deny';
  ssids?: string[];
  minSecurity?: 'personal' | 'enterprise' | 'enterprise192';
  safeguardMinutes?: number;
}

export const SECURITIES: { value: WifiSecurity; label: string; note?: string }[] = [
  { value: 'WPA', label: 'WPA2‑Personal (contraseña)' },
  { value: 'WPA3', label: 'WPA3‑Personal (SAE)', note: 'Android 10 o superior, y un chip Wi‑Fi con WPA3.' },
  { value: 'WPA3_TRANSITION', label: 'WPA2/WPA3 (transición)', note: 'Usa WPA3 cuando el punto de acceso y el teléfono (Android 12+) lo permiten; si no, WPA2.' },
  { value: 'EAP', label: 'WPA2‑Enterprise (802.1X)' },
  { value: 'EAP_WPA3', label: 'WPA3‑Enterprise (802.1X)', note: 'Android 12 o superior.' },
  { value: 'OWE', label: 'Abierta cifrada (OWE)', note: 'Android 10 o superior.' },
  { value: 'NONE', label: 'Abierta' },
  { value: 'WEP', label: 'WEP (obsoleta)' },
];

export const isEnterprise = (s?: WifiSecurity) => s === 'EAP' || s === 'EAP_WPA3';
export const isKeyed = (s?: WifiSecurity) => !s || s === 'WPA' || s === 'WPA3' || s === 'WPA3_TRANSITION' || s === 'WEP';
/** Android's ranking: 0 open, 1 personal, 2 enterprise. */
export const securityLevel = (s?: WifiSecurity) => (s === 'NONE' || s === 'OWE' ? 0 : isEnterprise(s) ? 2 : 1);
const MIN_LEVEL: Record<string, number> = { personal: 1, enterprise: 2, enterprise192: 3 };

const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;
export const isIpv4 = (s?: string) => !!s && IPV4.test(s.trim());

export const newNetworkId = () => `n${Math.random().toString(36).slice(2, 10)}`;

export interface WifiProblem {
  /** Index of the network it is about; undefined = the rules. */
  network?: number;
  /** error = the server will refuse the save; warn = it saves, but read this. */
  level: 'error' | 'warn';
  text: string;
}

/**
 * What is wrong with the Wi-Fi of a policy. The errors are the server's own refusals (DcWifi.validate); the warnings
 * are things worth knowing before sending a lock-down to a fleet.
 *
 * @param radio the policy's Wi-Fi radio setting (false = forced off).
 */
export function wifiProblems(networks: WifiNetwork[], rules: WifiRules | undefined, radio?: boolean | null): WifiProblem[] {
  const out: WifiProblem[] = [];
  const named = networks.filter((n) => n.ssid.trim());
  const seen = new Set<string>();
  networks.forEach((n, i) => {
    const ssid = n.ssid.trim();
    const sec = n.security ?? 'WPA';
    if (!ssid) {
      out.push({ network: i, level: 'warn', text: 'Sin nombre (SSID): esta red no se guardará.' });
      return;
    }
    if (new TextEncoder().encode(ssid).length > 32) out.push({ network: i, level: 'error', text: 'El SSID no puede pasar de 32 bytes.' });
    if (seen.has(ssid)) out.push({ network: i, level: 'error', text: 'SSID repetido: solo se guarda la primera red con ese nombre.' });
    seen.add(ssid);
    if (isKeyed(sec)) {
      const pw = n.password ?? '';
      if (!pw && !n.passwordSet) out.push({ network: i, level: 'error', text: 'Falta la contraseña.' });
      else if (pw && sec !== 'WEP' && (pw.length < 8 || pw.length > 64 || (pw.length === 64 && (sec === 'WPA3' || !/^[0-9A-Fa-f]{64}$/.test(pw))))) {
        out.push({ network: i, level: 'error', text: 'La contraseña debe tener entre 8 y 63 caracteres.' });
      }
    }
    if (isEnterprise(sec)) {
      const e = n.eap ?? {};
      const tls = e.method === 'TLS';
      if (!e.caCertIds?.length) out.push({ network: i, level: 'error', text: 'Elige el certificado de la CA del servidor: sin él, cualquier punto de acceso con el mismo nombre recibiría las credenciales.' });
      if (!e.domain?.trim() && !e.altSubjectMatch?.trim()) out.push({ network: i, level: 'error', text: 'Indica el dominio del certificado del servidor.' });
      if (tls && !e.clientCertId) out.push({ network: i, level: 'error', text: 'EAP‑TLS necesita un certificado de cliente (PKCS#12).' });
      if (!tls && !e.identity?.trim()) out.push({ network: i, level: 'error', text: 'Falta la identidad (usuario).' });
      if (!tls && !e.password && !e.passwordSet) out.push({ network: i, level: 'error', text: 'Falta la contraseña de EAP.' });
    }
    if (n.ip) {
      if (!isIpv4(n.ip.address)) out.push({ network: i, level: 'error', text: 'La IP fija no es una dirección IPv4 válida.' });
      if (!isIpv4(n.ip.gateway)) out.push({ network: i, level: 'error', text: 'La IP fija necesita la puerta de enlace.' });
    }
    if (n.proxy?.mode === 'manual' && !n.proxy.host?.trim()) out.push({ network: i, level: 'warn', text: 'Proxy manual sin servidor: no se guardará el proxy.' });
    if (n.proxy?.mode === 'pac' && !/^https?:\/\//.test(n.proxy.pacUrl ?? '')) out.push({ network: i, level: 'warn', text: 'La URL del archivo PAC debe empezar por http:// o https://.' });
    if (sec === 'WEP') out.push({ network: i, level: 'warn', text: 'WEP no protege: úsala solo si el equipo de red no admite otra cosa.' });
  });

  if (radio === false && named.length) {
    out.push({ level: 'error', text: 'La política fuerza el Wi‑Fi apagado (Red → Wi‑Fi) y a la vez configura redes. Deja el radio sin administrar o encendido.' });
  }
  const r = rules;
  if (!r) return out;
  if (r.ssidMode === 'allow' && !named.length && !r.ssids?.length) {
    out.push({ level: 'error', text: '«Solo redes permitidas» sin ninguna red dejaría los equipos sin Wi‑Fi: agrega una red gestionada o un SSID a la lista.' });
  }
  if (r.ssidMode === 'deny' && !r.ssids?.length) out.push({ level: 'error', text: 'La lista de redes bloqueadas está vacía.' });
  if (r.ssidMode === 'deny' && named.some((n) => r.ssids?.includes(n.ssid.trim()))) {
    out.push({ level: 'error', text: 'Una red gestionada está en la lista de redes bloqueadas.' });
  }
  const min = r.minSecurity ? MIN_LEVEL[r.minSecurity] ?? 0 : 0;
  if (min > 0 && named.length && Math.max(...named.map((n) => securityLevel(n.security))) < min) {
    out.push({ level: 'error', text: 'Ninguna red gestionada alcanza la seguridad mínima exigida: los equipos no podrían conectarse a ninguna.' });
  }
  if ((r.blockAdd || r.blockConfig) && !named.length) {
    out.push({ level: 'error', text: 'Impides agregar o cambiar redes pero la política no trae ninguna: agrega al menos una red gestionada.' });
  }
  const restrictive = r.blockAdd || r.blockConfig || r.blockState || !!r.ssidMode || min > 0;
  if (restrictive) {
    out.push({
      level: 'warn',
      text: `Si un equipo se queda sin ninguna red (ni datos móviles) durante ${r.safeguardMinutes ?? 10} minutos con este bloqueo, lo relaja por su cuenta un rato y lo reporta aquí. Revisa bien las contraseñas y los certificados antes de guardar.`,
    });
  }
  if (r.ssidMode === 'allow' && named.length && named.every((n) => n.autoConnect === false)) {
    out.push({ level: 'warn', text: 'Todas las redes gestionadas tienen la conexión automática apagada: nadie se conectará solo.' });
  }
  return out;
}

/** What is sent on save: empty rows dropped is the server's job; here only empty option blocks are removed. */
export function tidyNetwork(n: WifiNetwork): WifiNetwork {
  const out: WifiNetwork = { ...n };
  if (!isKeyed(out.security)) { delete out.password; delete out.passwordSet; }
  if (!isEnterprise(out.security)) delete out.eap;
  return out;
}

export function rulesOrUndefined(r: WifiRules | undefined): WifiRules | undefined {
  if (!r) return undefined;
  const any = r.lockManaged || r.blockAdd || r.blockConfig || r.blockState || r.ssidMode || r.minSecurity;
  return any ? r : undefined;
}
