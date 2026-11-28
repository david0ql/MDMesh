import { useEffect, useState } from 'react';
import {
  getReach, CHANNEL_LABEL, PUSH_STATE_LABEL, WOKEN_BY_LABEL, expectedWait, type DeviceReach,
} from '../api/wake';
import { fmtRelative } from '../ui/format';

const CHIP: Record<DeviceReach['channel'], string> = {
  live: 'chip tone-ok',
  push: 'chip tone-ok',
  poll: 'chip tone-warn',
};

function yesNo(v: boolean | null | undefined, yes: string, no: string): string {
  return v == null ? 'sin dato (agente anterior)' : v ? yes : no;
}

/**
 * Left-rail card: by which channel a command sent now would reach this device (live socket, push message, or only
 * its own periodic check-in), the usual wait, and what on the phone can stretch it.
 */
export function ReachCard({ deviceNumber }: { deviceNumber: string }) {
  const [reach, setReach] = useState<DeviceReach | null>(null);

  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout> | undefined;
    const loop = async () => {
      try {
        const r = await getReach(deviceNumber);
        if (on) setReach(r);
      } catch {
        // an older server: the card stays hidden
      }
      if (on) t = setTimeout(() => void loop(), 15_000);
    };
    void loop();
    return () => { on = false; clearTimeout(t); };
  }, [deviceNumber]);

  if (!reach) return null;
  const why = reach.channel === 'live'
    ? 'El equipo mantiene abierto el canal en vivo con el servidor.'
    : reach.channel === 'push'
      ? 'El canal en vivo está cerrado; el servidor lo despierta con un mensaje push de alta prioridad.'
      : reach.pushConfigured
        ? `El canal en vivo está cerrado y el equipo no tiene registro de mensajes push (${PUSH_STATE_LABEL[reach.pushState ?? ''] ?? 'sin registro'}). La orden espera a su próximo reporte.`
        : 'El canal en vivo está cerrado y el servidor no tiene mensajes push configurados. La orden espera al próximo reporte del equipo.';

  return (
    <div data-testid="reach-card">
      <div className="grp">Alcance de órdenes</div>
      <div className="row">
        <span className="k">Canal ahora</span>
        <span className="v">
          <span className={CHIP[reach.channel]} data-testid="reach-channel" data-channel={reach.channel}>
            {CHANNEL_LABEL[reach.channel]}
          </span>
        </span>
      </div>
      <div className="row">
        <span className="k">Latencia esperada</span>
        <span className="v" data-testid="reach-latency">
          {expectedWait(reach.expectedSeconds)}
          {reach.mayBeLonger ? ' · puede ser más en reposo' : ''}
        </span>
      </div>
      <p className="muted" style={{ marginTop: 4 }}>{why}</p>
      <div className="row">
        <span className="k">Mensajes push</span>
        <span className="v" title={reach.pushError ?? undefined} data-testid="reach-push">
          {!reach.pushConfigured
            ? 'No configurados en el servidor'
            : reach.hasToken
              ? `Registrado${reach.tokenAt ? ` · ${fmtRelative(reach.tokenAt)}` : ''}`
              : PUSH_STATE_LABEL[reach.pushState ?? ''] ?? 'Sin registro (agente anterior)'}
        </span>
      </div>
      {reach.lastSentAt ? (
        <div className="row">
          <span className="k">Último push</span>
          <span className="v" title={reach.lastSendError ?? undefined}>
            {fmtRelative(reach.lastSentAt)}
            {reach.lastSentOk === false
              ? ' · falló'
              : reach.lastWakeMs != null && reach.lastWakeMs >= 0
                ? ` · respondió en ${(reach.lastWakeMs / 1000).toFixed(1)} s`
                : ' · sin respuesta aún'}
          </span>
        </div>
      ) : null}
      <div className="row">
        <span className="k">Sondeo de respaldo</span>
        <span className="v">{reach.pollMinutes ? `cada ${reach.pollMinutes} min` : 'cada 5–15 min (agente anterior)'}</span>
      </div>
      <div className="row">
        <span className="k">Exento de batería</span>
        <span className="v" data-testid="reach-battery">
          {yesNo(reach.batteryExempt, 'Sí', 'No: Android puede retrasar sus reportes en reposo')}
        </span>
      </div>
      {reach.bgRestricted ? (
        <div className="row">
          <span className="k">Segundo plano</span>
          <span className="v">Restringido en el equipo: quite la restricción de batería del agente</span>
        </div>
      ) : null}
      {reach.exactAlarms === false ? (
        <div className="row">
          <span className="k">Alarmas exactas</span>
          <span className="v">No permitidas: el sondeo puede atrasarse unos minutos</span>
        </div>
      ) : null}
      {reach.lastWokenBy ? (
        <div className="row">
          <span className="k">Último reporte por</span>
          <span className="v">{WOKEN_BY_LABEL[reach.lastWokenBy] ?? reach.lastWokenBy}</span>
        </div>
      ) : null}
    </div>
  );
}
