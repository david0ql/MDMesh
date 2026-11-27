import { useState } from 'react';
import { useToast } from '../ui/toast';
import {
  agentAdminComponent, agentApkUrl, agentChecksum, agentPackage, kmeCustomJson, zeroTouchExtras, type WifiConfig,
} from '../enroll/provisioning';

function saveText(name: string, text: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  a.download = name.replace(/[^A-Za-z0-9._-]+/g, '_');
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

/**
 * "Exportar configuración Zero-Touch / KME": the same content a folder code's QR carries, as the JSON those portals
 * ask for. Only a reusable folder code makes sense here: the portal keeps the JSON for every phone it will ever set up.
 */
export function ZeroTouchExport({ code, label, wifi }: { code: string; label: string; wifi?: WifiConfig }) {
  const toast = useToast();
  const [tab, setTab] = useState<'zt' | 'kme'>('zt');
  const zt = zeroTouchExtras(code, wifi);
  const kme = kmeCustomJson(code);
  const text = tab === 'zt' ? zt : kme;
  const debugBuild = agentPackage().endsWith('.debug');

  async function copy(value: string, what: string) {
    try {
      await navigator.clipboard.writeText(value);
      toast.push('ok', 'Copiado', what);
    } catch {
      toast.push('err', 'No se pudo copiar', 'Selecciona el texto y cópialo manualmente.');
    }
  }

  return (
    <div className="zt-export" data-testid="zt-export" style={{ marginTop: 18, paddingTop: 18, borderTop: '1px solid var(--border)' }}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <b>Configuración Zero‑Touch / KME · {label}</b>
        <span className="seg" role="tablist" aria-label="Portal">
          <button className={tab === 'zt' ? 'on' : ''} onClick={() => setTab('zt')}>Android Zero‑Touch</button>
          <button className={tab === 'kme' ? 'on' : ''} onClick={() => setTab('kme')}>Samsung KME</button>
        </span>
      </div>
      {debugBuild && (
        <div className="banner banner-warn" style={{ marginTop: 12 }}>
          Esta consola está compilada con el agente de pruebas (<span className="mono">{agentPackage()}</span>). Para
          Zero‑Touch / KME usa una consola compilada con el paquete de producción (VITE_AGENT_PACKAGE, VITE_AGENT_CHECKSUM).
        </div>
      )}
      <textarea className="input mono" readOnly value={text} rows={tab === 'zt' ? 14 : 5} aria-label="JSON de configuración"
        style={{ width: '100%', marginTop: 12, fontSize: 12, whiteSpace: 'pre' }} onFocus={(e) => e.currentTarget.select()} />
      <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
        <button className="btn btn-sm" onClick={() => void copy(text, 'JSON copiado al portapapeles.')}>Copiar JSON</button>
        <button className="btn btn-sm" data-testid="zt-download"
          onClick={() => saveText(tab === 'zt' ? `zero-touch-${code}.json` : `kme-${code}.json`, text)}>Descargar .json</button>
      </div>
      {tab === 'zt' ? (
        <ol className="note" style={{ paddingLeft: 18, lineHeight: 1.6 }}>
          <li>El revendedor autorizado registra los equipos (IMEI/serie) en tu cuenta de <b>Android Zero‑Touch</b> (partner.android.com/zerotouch).</li>
          <li>En <b>Configuraciones → Agregar</b>, elige el DPC que indique tu revendedor y pega este JSON en <b>DPC extras</b>. El JSON
            ya lleva el paquete del agente, su firma y de dónde descargarlo, así que el equipo instala DallyControl.</li>
          <li>Aplica la configuración a los equipos. Al encenderlos formateados y conectarlos a internet, se inscriben solos en esta carpeta.</li>
          <li>Si cambias el certificado de firma o la dirección del APK, vuelve a exportar y actualiza la configuración.</li>
        </ol>
      ) : (
        <>
          <ol className="note" style={{ paddingLeft: 18, lineHeight: 1.6 }}>
            <li>En <b>Knox Mobile Enrollment → Profiles → Create profile → Android Enterprise</b>, tipo <b>Device Owner</b>.</li>
            <li>En <b>Pick your MDM</b> elige «Other» y en <b>MDM Agent APK</b> pega la dirección del agente (abajo).</li>
            <li>Pega este JSON en <b>Custom JSON Data (as defined by MDM)</b> y marca «Leave all system apps enabled».</li>
            <li>Asigna el perfil a los equipos que subió tu revendedor Samsung. Al encenderlos formateados se inscriben en esta carpeta.</li>
          </ol>
          <table className="gr-table" style={{ marginTop: 4 }}>
            <tbody>
              <tr><td>MDM Agent APK</td><td className="mono" style={{ wordBreak: 'break-all' }}>{agentApkUrl()}</td>
                <td><button className="btn btn-sm btn-ghost" onClick={() => void copy(agentApkUrl(), 'Dirección del APK copiada.')}>Copiar</button></td></tr>
              <tr><td>Componente (DPC)</td><td className="mono" style={{ wordBreak: 'break-all' }}>{agentAdminComponent()}</td>
                <td><button className="btn btn-sm btn-ghost" onClick={() => void copy(agentAdminComponent(), 'Componente copiado.')}>Copiar</button></td></tr>
              <tr><td>Firma (SHA‑256, base64 URL)</td><td className="mono" style={{ wordBreak: 'break-all' }}>{agentChecksum()}</td>
                <td><button className="btn btn-sm btn-ghost" onClick={() => void copy(agentChecksum(), 'Firma copiada.')}>Copiar</button></td></tr>
            </tbody>
          </table>
        </>
      )}
      <p className="note">
        Usa un código de carpeta sin vencimiento: el portal guarda este JSON para todos los equipos que configure. Si revocas
        el código, los equipos nuevos dejarán de inscribirse (verás el motivo en «Intentos de inscripción»).
      </p>
    </div>
  );
}
