// "Wi-Fi gestionado" on a device's page: what the agent reports about the policy's networks and lock-down
// (telemetry dynamic.managedWifi, see the agent's ManagedWifiReport). Shown only when the policy manages Wi-Fi.

export interface ManagedWifiStatus {
  ssid: string;
  security?: string;
  configured?: boolean;
  connected?: boolean;
  inRange?: boolean | null;
  reason?: string | null;
  detail?: string | null;
  unsupportedOptions?: string[];
  via?: string | null;
}
export interface ManagedWifiReport {
  networks?: ManagedWifiStatus[];
  rules?: {
    applied?: string[]; unsupported?: string[]; relaxed?: boolean;
    relaxedAt?: number | null; relaxedUntil?: number | null; relaxedReason?: string | null;
  } | null;
  checkedAt?: number;
}

const SECURITY: Record<string, string> = {
  NONE: 'Abierta', OWE: 'Abierta cifrada (OWE)', WEP: 'WEP', WPA: 'WPA2‑Personal', WPA3: 'WPA3‑Personal',
  WPA3_TRANSITION: 'WPA2/WPA3', EAP: 'WPA2‑Enterprise', EAP_WPA3: 'WPA3‑Enterprise',
};
const REASON: Record<string, string> = {
  password: 'Contraseña incorrecta',
  certificate: 'Certificado rechazado, vencido o ilegible',
  credentials: 'Credenciales rechazadas (identidad o contraseña EAP, o el certificado del servidor no coincide)',
  out_of_range: 'Fuera de alcance',
  security_mismatch: 'La red está cerca pero con otra seguridad',
  unsupported: 'No soportada en esta versión de Android',
  save_failed: 'Android no la guardó',
  disabled: 'Guardada, sin conexión automática',
  radio_off: 'Wi‑Fi apagado',
};
const RULE: Record<string, string> = {
  lockManaged: 'Redes gestionadas bloqueadas',
  blockAdd: 'No se pueden agregar redes',
  blockConfig: 'Ajustes de Wi‑Fi cerrados',
  blockState: 'Interruptor de Wi‑Fi bloqueado',
  ssidPolicy: 'Lista de redes permitidas/bloqueadas',
  minSecurity: 'Seguridad mínima',
  radioOn: 'Wi‑Fi forzado encendido',
  radioOff: 'Wi‑Fi forzado apagado',
};
const OPTION: Record<string, string> = { staticIp: 'IP fija', metered: 'uso de datos', mac: 'MAC', autoConnect: 'conexión automática', proxy: 'proxy' };

function state(n: ManagedWifiStatus): { tone: 'ok' | 'warn' | 'alert'; label: string } {
  if (n.connected) return { tone: 'ok', label: 'Conectada' };
  if (n.reason === 'out_of_range' || n.reason === 'disabled' || n.reason === 'radio_off') return { tone: 'warn', label: REASON[n.reason] };
  if (n.reason) return { tone: 'alert', label: REASON[n.reason] ?? n.reason };
  return n.configured ? { tone: 'ok', label: 'Configurada' } : { tone: 'alert', label: 'Sin configurar' };
}

const time = (ms?: number | null) => (ms ? new Date(ms).toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit' }) : '');

export function ManagedWifiCard({ report }: { report: unknown }) {
  const r = (report && typeof report === 'object' ? report : null) as ManagedWifiReport | null;
  if (!r) return null;
  const networks = r.networks ?? [];
  const rules = r.rules;
  if (!networks.length && !rules) return null;
  return (
    <div data-testid="managed-wifi">
      <div className="grp">Wi‑Fi gestionado</div>
      {rules?.relaxed && (
        <div className="banner banner-warn" role="alert" data-testid="managed-wifi-relaxed">
          <b>Bloqueo de Wi‑Fi relajado por el equipo.</b> Se quedó sin ninguna red con el bloqueo activo
          {rules.relaxedAt ? ` (${time(rules.relaxedAt)})` : ''}; vuelve a aplicarlo{rules.relaxedUntil ? ` a las ${time(rules.relaxedUntil)}` : ' más tarde'}.
          Revisa las redes y contraseñas de la política.
        </div>
      )}
      {networks.map((n) => {
        const s = state(n);
        return (
          <div className="wf-status" key={n.ssid} data-testid={`managed-wifi-${n.ssid}`}>
            <div className="row">
              <span className="k" title={n.via === 'suggestion' ? 'Sugerida al sistema (el agente no es Device Owner)' : undefined}>{n.ssid}</span>
              <span className={`v chip tone-${s.tone}`}>{s.label}</span>
            </div>
            <div className="muted small">
              {SECURITY[n.security ?? ''] ?? n.security}
              {n.connected ? '' : n.configured ? ' · guardada en el equipo' : ''}
              {n.via === 'suggestion' ? ' · como sugerencia' : ''}
            </div>
            {n.detail && !n.connected && <div className="mono small" style={{ wordBreak: 'break-word' }}>{n.detail}</div>}
            {!!n.unsupportedOptions?.length && (
              <div className="muted small">Sin aplicar en esta versión de Android: {n.unsupportedOptions.map((o) => OPTION[o] ?? o).join(', ')}.</div>
            )}
          </div>
        );
      })}
      {rules && (rules.applied?.length || rules.unsupported?.length) ? (
        <ul className="cfg-outcomes">
          {(rules.applied ?? []).map((k) => <li key={k} className="ok"><span>{RULE[k] ?? k}</span></li>)}
          {(rules.unsupported ?? []).map((k) => (
            <li key={k} className="muted"><span>{RULE[k] ?? k}: no disponible en esta versión de Android</span></li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
