/**
 * "Por cable (USB)" enrollment method. On Android 15/16, Google Play Protect blocks the QR
 * device-owner provisioning of a custom DPC (the install is vetoed before the agent ever runs),
 * so those phones are enrolled over USB with the bundled 1-click Windows tool, which Play Protect
 * does not block. This panel is the in-app tutorial + download for that flow.
 */
const TOOL_URL = '/files/DallyControl-Enrolar.zip';

const STEPS: { title: string; sub: string }[] = [
  {
    title: 'Formatea el teléfono (restablecer de fábrica)',
    sub: 'Es obligatorio: el modo administrado (Device Owner) solo se activa en un equipo recién formateado.',
  },
  {
    title: 'No inicies sesión en ninguna cuenta',
    sub: 'En el asistente de bienvenida NO agregues cuenta de Google ni Samsung. Si agregas una cuenta, el enrolamiento falla.',
  },
  {
    title: 'Activa Opciones de desarrollador',
    sub: 'Ajustes → Acerca del teléfono → Información de software → toca 7 veces “Número de compilación”.',
  },
  {
    title: 'Activa Depuración USB y conecta el cable',
    sub: 'Ajustes → Opciones de desarrollador → “Depuración USB”. Conecta por USB y acepta “Permitir depuración USB” en el teléfono.',
  },
  {
    title: 'Ejecuta la herramienta en el PC (Windows)',
    sub: 'Descomprime el ZIP y doble clic en el .bat. Usa el código de la carpeta del equipo (pestaña “Códigos de carpeta”).',
  },
  {
    title: 'Verifica',
    sub: 'En 1–2 minutos el equipo aparece en Dispositivos, en línea, como Device Owner.',
  },
];

export function UsbEnrollPanel() {
  return (
    <section className="panel enroll-wrap">
      <div className="panel-head">
        <h2 className="panel-title">Inscribir por cable (USB)</h2>
        <a className="btn btn-primary btn-sm" href={TOOL_URL} download>
          Descargar herramienta (Windows)
        </a>
      </div>

      <div style={{ padding: 20 }}>
        <div className="banner banner-alert" style={{ marginBottom: 16 }}>
          El <b>QR</b> está probado en emuladores de Android 7 a 17. En teléfonos físicos con <b>Android 15 o
          superior</b> aún no está verificado: si el teléfono muestra “Se produjo un error” al instalar el agente
          (Google Play Protect puede bloquearlo), inscribe ese equipo <b>por cable USB</b> con esta herramienta.
        </div>

        <p className="note" style={{ marginTop: 0 }}>
          Necesitas un <b>PC con Windows</b> y un <b>cable USB</b> (que transmita datos). La herramienta trae todo
          incluido (no hay que instalar nada).
        </p>

        <ol className="qr-steps" style={{ listStyle: 'none', margin: '8px 0 0', padding: 0 }}>
          {STEPS.map((s, i) => (
            <li className="qr-step" key={i}>
              <span className="step-n">{i + 1}</span>
              <span className="st-tx">
                {s.title}
                <span className="sub">{s.sub}</span>
              </span>
            </li>
          ))}
        </ol>

        <h3 style={{ fontSize: 15, fontWeight: 700, margin: '20px 0 6px' }}>Si algo falla</h3>
        <ul className="note" style={{ margin: 0, paddingLeft: 18 }}>
          <li><b>“no autorizado / unauthorized”:</b> acepta el permiso de depuración USB en el teléfono y vuelve a ejecutar.</li>
          <li><b>“No se pudo poner como Device Owner”:</b> el equipo tiene una cuenta o no estaba recién formateado. Formatea de nuevo sin agregar cuentas.</li>
          <li><b>No detecta el teléfono:</b> prueba otro cable o puerto USB y confirma que “Depuración USB” esté activada.</li>
        </ul>
      </div>
    </section>
  );
}
