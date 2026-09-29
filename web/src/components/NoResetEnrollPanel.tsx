import { useState } from 'react';
import { serverBaseUrl } from '../enroll/provisioning';

/**
 * Enrolling a phone that is already in use, without a factory reset (its data stays). Android only lets an app become
 * Device Owner on a phone with no accounts, and only a factory reset (QR) or ADB can make it one. ADB works over USB or,
 * on Android 11+, over Wi-Fi (Wireless debugging) — no cable. scripts/adb-enroll.sh does the rest.
 */
export function NoResetEnrollPanel({ token }: { token?: string }) {
  const [pair, setPair] = useState('');
  const [code, setCode] = useState('');
  const [connect, setConnect] = useState('');
  const server = serverBaseUrl();
  const tok = token || '<CÓDIGO-O-TOKEN>';
  const cmd = [
    'scripts/adb-enroll.sh',
    `--server ${server}`,
    `--token ${tok}`,
    pair ? `--pair ${pair} --pair-code ${code || '<CÓDIGO>'}` : '',
    connect ? `--connect ${connect}` : '',
    '--remote',
  ].filter(Boolean).join(' \\\n    ');

  return (
    <section className="panel enroll-wrap" data-testid="enroll-no-reset">
      <div className="panel-head"><h2 className="panel-title">Inscribir sin formatear</h2></div>
      <div style={{ padding: 20 }}>
        <p className="note" style={{ marginTop: 0 }}>
          El teléfono <b>conserva sus datos</b> (fotos, archivos, WhatsApp). Android solo deja que DallyControl administre un
          teléfono que no tenga cuentas mientras se inscribe, y para eso hace falta un computador conectado al teléfono:
          por <b>cable</b> o, en Android 11 o superior, <b>sin cable por Wi‑Fi</b>. No existe otra vía sin formatear.
        </p>
        <ol className="steps-list">
          <li><b>Quitar las cuentas</b> del teléfono (Ajustes → Cuentas → Google, Samsung…): no borra nada; se vuelven a agregar al final.</li>
          <li><b>Activar las opciones de desarrollador</b> (Ajustes → Acerca del teléfono → tocar 7 veces «Número de compilación»).</li>
          <li><b>Sin cable (Android 11+)</b>: Opciones de desarrollador → <b>Depuración inalámbrica</b> → «Vincular dispositivo con código».
            El teléfono y el computador en la misma red Wi‑Fi. Escribe aquí lo que muestra:</li>
        </ol>
        <div className="codes-new">
          <input className="input mono" placeholder="IP:puerto de vinculación (p. ej. 192.168.1.20:37123)" value={pair} onChange={(e) => setPair(e.target.value.trim())} aria-label="Dirección de vinculación" />
          <input className="input mono" placeholder="Código de 6 dígitos" value={code} onChange={(e) => setCode(e.target.value.trim())} aria-label="Código de vinculación" />
          <input className="input mono" placeholder="IP:puerto de la pantalla Depuración inalámbrica" value={connect} onChange={(e) => setConnect(e.target.value.trim())} aria-label="Dirección de conexión" />
        </div>
        <ol className="steps-list" start={4}>
          <li><b>Ejecutar en el computador</b> (con este repositorio y <span className="mono">adb</span>):</li>
        </ol>
        <pre className="cmd-block" data-testid="no-reset-command">{cmd}</pre>
        <p className="note">
          Con cable: conecta el teléfono con la depuración USB activada y omite los datos de Wi‑Fi. <span className="mono">--remote</span> deja
          el control remoto listo sin pedir nada en el teléfono. Usa el token de la pestaña <b>Token</b> o un <b>código de carpeta</b>.
          Al terminar, vuelve a agregar las cuentas y desactiva la depuración.
        </p>
      </div>
    </section>
  );
}
