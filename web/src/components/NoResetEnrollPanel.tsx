import { useState } from 'react';
import { serverBaseUrl } from '../enroll/provisioning';
import { groupTree, type FleetGroup } from '../api/fleet';
import { createEnrollmentCode, listEnrollmentCodes } from '../api/enroll';
import { buildWindowsEnroller, enrollerFileName } from '../enroll/windowsEnroller';

/** The guide for the person enrolling (static, in web/public). */
export const NO_RESET_GUIDE = '/guia-inscribir-sin-formatear.pdf';
const ENROLLER_LABEL = 'Inscriptor sin formatear (Windows)';

function download(name: string, text: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], { type: 'application/octet-stream' }));
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
}

/**
 * Enrolling a phone that is already in use, without a factory reset (its data stays). Android only lets an app become
 * Device Owner on a phone with no accounts, and only a factory reset (QR) or ADB can make it one. ADB works over USB or,
 * on Android 11+, over Wi-Fi (Wireless debugging) — no cable. scripts/adb-enroll.sh does the rest.
 */
export function NoResetEnrollPanel({ token, groups = [] }: { token?: string; groups?: FleetGroup[] }) {
  const [folderId, setFolderId] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const tree = groupTree(groups);

  /** The folder's reusable code made for the enroller (created the first time), so one file enrolls many phones. */
  async function downloadEnroller() {
    const node = tree.find((n) => String(n.group.id) === folderId);
    if (!node) return;
    setBusy(true); setMsg(null);
    try {
      const codes = await listEnrollmentCodes();
      const now = Date.now();
      let c = codes.find((x) => x.groupId === node.group.id && !x.revoked && x.label === ENROLLER_LABEL && (!x.expiresAt || x.expiresAt > now));
      if (!c) c = await createEnrollmentCode(node.group.id, ENROLLER_LABEL);
      download(enrollerFileName(node.path), buildWindowsEnroller(serverBaseUrl(), c.code, node.path));
      setMsg(`Descargado. Sirve para todos los teléfonos de «${node.path}» (usa el código ${c.code}; se revoca en Códigos de carpeta).`);
    } catch (e) {
      setMsg(`No se pudo preparar el inscriptor: ${e instanceof Error ? e.message : String(e)}`);
    }
    setBusy(false);
  }

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
        <div className="noreset-easy" data-testid="no-reset-easy">
          <h3 className="sub-h" style={{ marginTop: 0 }}>Fácil, desde un computador con Windows</h3>
          <p className="note" style={{ marginTop: 0 }}>
            Para quien no es técnico: un programa que se descarga, se abre con doble clic y va diciendo qué hacer. No hay que
            instalar nada (descarga solo lo que necesita). El teléfono <b>conserva sus datos</b>.
          </p>
          <div className="codes-new">
            <select className="sel" value={folderId} onChange={(e) => setFolderId(e.target.value)} aria-label="Carpeta del inscriptor">
              <option value="">Carpeta donde quedan los teléfonos…</option>
              {tree.map((n) => <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>)}
            </select>
            <button className="btn btn-primary" disabled={!folderId || busy} onClick={() => void downloadEnroller()} data-testid="download-enroller">
              {busy ? 'Preparando…' : 'Descargar inscriptor para Windows'}
            </button>
            <a className="btn" href={NO_RESET_GUIDE} download data-testid="download-guide">Descargar guía (PDF)</a>
          </div>
          {msg && <p className="note">{msg}</p>}
          <ol className="steps-list">
            <li>En el teléfono: <b>quitar las cuentas</b> y activar las <b>opciones de desarrollador</b> (la guía lo muestra con imágenes).</li>
            <li>En el computador: abrir el archivo descargado con <b>doble clic</b> (si Windows avisa «protegió su PC»: «Más información» → «Ejecutar de todas formas»).</li>
            <li>Seguir lo que dice la ventana: conectar por cable o por Wi‑Fi, y listo. Al final se vuelven a agregar las cuentas.</li>
          </ol>
        </div>
        <details className="noreset-tech">
          <summary>Para técnicos: con el repositorio (Mac o Linux)</summary>
        <p className="note">
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
        </details>
      </div>
    </section>
  );
}
