// The server answers a refused operation with a message KEY (`error.agent.command.bulkForbidden`), not with a
// sentence. This is the one place that turns those keys into what the console shows. It is applied where the
// ApiError is built (client.ts), so every page gets it; the raw key stays on `ApiError.code`.
//
// Keep this file free of imports: the unit test loads it directly with Node.

/** What the console says when the server gave no message at all. */
export const GENERIC_SERVER_ERROR = 'El servidor devolvió un error';

/** Server message key → the sentence shown to the operator. Append new keys at the end of their group. */
export const ERROR_MESSAGES: Record<string, string> = {
  // --- General (com.hmdm.rest.json.Response) ---
  'error.internal.server': 'Error interno del servidor. Intenta de nuevo; si se repite, avisa a soporte.',
  'error.permission.denied': 'No tienes permiso para hacer esta operación.',
  'error.resource.disabled': 'Esta función está desactivada en el servidor.',
  'error.notfound.object': 'El elemento ya no existe.',
  'error.notfound.device': 'El dispositivo no existe.',
  'error.params.missing': 'Faltan datos obligatorios en la solicitud.',

  // --- Dispositivos y comandos ---
  'error.agent.device.unknown': 'El dispositivo no existe o ya no está en tu consola.',
  'error.devices.empty': 'Selecciona al menos un dispositivo.',
  'error.duplicate.device': 'Ya existe un dispositivo con ese número.',
  'error.agent.command.invalid': 'El comando no es válido o le faltan datos.',
  'error.agent.command.unknown': 'Ese comando ya no existe.',
  'error.agent.command.notCancellable': 'Este comando ya no se puede cancelar: ya se ejecutó, venció o ya estaba cancelado.',
  'error.agent.command.bulkForbidden': 'Por seguridad, esta acción no se puede enviar a varios dispositivos a la vez. Envíala desde la ficha de cada dispositivo.',
  'error.agent.config.none': 'El dispositivo no tiene una política asignada para aplicar.',
  'error.agent.remote.unsupported': 'El agente de este dispositivo no admite sesiones de soporte remoto: actualízalo.',
  'error.agent.locations.range': 'El rango de fechas no es válido: la fecha final debe ser posterior a la inicial y el rango no puede pasar de 31 días.',
  'error.agent.app.pkg.required': 'Falta el paquete de la aplicación.',
  'error.agent.account.email.invalid': 'El correo de la cuenta no tiene un formato válido.',
  'error.mobiledata.failed': 'No se pudo consultar el consumo de datos del dispositivo.',

  // --- Inscripción ---
  'error.agent.token.invalid': 'El código de inscripción no es válido o fue revocado.',
  'error.agent.token.expired': 'El código de inscripción venció.',
  'error.agent.token.used': 'El código de inscripción ya fue usado por otro dispositivo.',
  'error.agent.code.expiry.invalid': 'La fecha de vencimiento del código debe ser futura.',
  'error.agent.code.wifi.invalid': 'Los datos de Wi‑Fi del código no son válidos (nombre de hasta 32 caracteres, contraseña de hasta 63).',
  'error.agent.code.not.found': 'Ese código de inscripción ya no existe.',
  'error.agent.code.not.revoked': 'Solo se puede eliminar un código que ya fue revocado.',
  'error.agent.package.missing': 'El servidor no tiene el instalador del agente (APK).',
  'error.agent.package.unreadable': 'El servidor no pudo leer el instalador del agente (APK).',

  // --- Carpetas (grupos de dispositivos) ---
  'error.group.name.invalid': 'Escribe un nombre válido para la carpeta.',
  'error.group.name.taken': 'Ya existe una carpeta con ese nombre en el mismo nivel.',
  'error.group.parent.invalid': 'La carpeta de destino no es válida: no existe o es la misma carpeta o una de sus subcarpetas.',
  'error.group.not.found': 'La carpeta ya no existe.',
  'error.duplicate.group': 'Ya existe un grupo con ese nombre.',
  'error.notempty.group': 'El grupo todavía tiene dispositivos; muévelos antes de eliminarlo.',

  // --- Políticas (configuraciones) ---
  'error.configuration.not.found': 'La política no existe o ya fue eliminada.',
  'error.configuration.kiosk.password': 'La contraseña de salida del quiosco debe tener entre 4 y 64 caracteres. Déjala vacía para que se genere una.',
  'error.configuration.dcpolicy.invalid': 'La política tiene un valor que no es válido. Revisa los campos y guarda de nuevo.',
  'error.configuration.device.use': 'Todavía hay dispositivos que usan esta política.',
  'error.duplicate.configuration': 'Ya existe una política con ese nombre.',

  // --- Aplicaciones y archivos ---
  'error.application.not.found': 'La aplicación no existe.',
  'error.duplicate.application': 'Ya existe una aplicación con ese paquete y esa versión.',
  'error.app.name.exists': 'Ya existe otra aplicación con ese nombre.',
  'error.recent.application.version.exists': 'Ya hay una versión igual o más reciente de esta aplicación.',
  'error.application.config.reference.exists': 'La aplicación está en uso en alguna política; quítala de las políticas antes de eliminarla.',
  'error.application.version.pkg.mismatch': 'El APK es de otra aplicación: su paquete no coincide con el de esta.',
  'error.common.application.access.prohibited': 'Esta aplicación es compartida y no se puede modificar desde tu cuenta.',
  'error.play.package.invalid': 'Escribe el paquete (por ejemplo com.whatsapp) o pega el enlace de la Play Store.',
  'error.play.not.found': 'Esa aplicación no existe en la Play Store.',
  'error.play.unreachable': 'No se pudo consultar la Play Store. Intenta de nuevo.',
  'error.fdroid.fetch': 'No se pudo consultar el catálogo de F‑Droid. Intenta de nuevo.',
  'error.bundle.noApks': 'El archivo no contiene APK utilizables (puede estar cifrado o no ser un paquete de APK).',
  'error.bundle.unreadable': 'No se pudo leer la aplicación dentro del archivo.',
  'error.bundle.unpack': 'No se pudo descomprimir el archivo de la aplicación.',
  'error.duplicate.file': 'Ya existe un archivo con ese nombre.',
  'error.used.file': 'El archivo está en uso y no se puede eliminar.',
  'error.file.save': 'El servidor no pudo guardar el archivo.',
  'error.file.deletion': 'El servidor no pudo eliminar el archivo.',
  'error.file.name.invalid': 'El nombre del archivo no es válido.',
  'error.size.limit.exceeded': 'Se superó el espacio de almacenamiento disponible para tu organización.',
  'error.icon.dimension.invalid': 'El ícono debe ser cuadrado (mismo ancho y alto).',

  // --- Actualización del agente por etapas ---
  'error.rollout.invalid': 'Faltan datos de la actualización: versión, paquete o código de versión.',
  'error.rollout.sha256': 'Falta la huella SHA‑256 del APK o no es válida (64 caracteres hexadecimales).',
  'error.rollout.canary.empty': 'Elige al menos un dispositivo de prueba.',
  'error.rollout.active': 'Ya hay una actualización en curso. Termínala o cancélala antes de iniciar otra.',
  'error.rollout.stage': 'La actualización ya no está en la etapa de prueba.',

  // --- Anuncios ---
  'error.announcement.title': 'Escribe un título para el anuncio (máximo 200 caracteres).',
  'error.announcement.body': 'El texto del anuncio admite hasta 5000 caracteres.',
  'error.announcement.media': 'La imagen o el video del anuncio no es válido: debe ser un enlace http(s) a una imagen o un video.',
  'error.announcement.noDevices': 'El anuncio no tiene destinatarios: elige al menos un dispositivo o una carpeta con dispositivos.',
  'error.announcement.tooMany': 'Demasiados destinatarios para un solo anuncio (máximo 10 000 dispositivos). Envíalo por carpetas.',
  'error.announcement.notFound': 'El anuncio ya no existe.',

  // --- Scripts ---
  'error.script.not.found': 'El script ya no existe.',
  'error.script.run.noDevices': 'Elige al menos un dispositivo para ejecutar el script.',
  'error.script.run.empty': 'El script no tiene ningún paso para ejecutar.',

  // --- Geocercas, alertas y reportes ---
  'error.geofence.badRequest': 'Faltan datos de la zona.',
  'error.geofence.name': 'Escribe un nombre para la zona (máximo 100 caracteres).',
  'error.geofence.shape': 'La forma de la zona no es válida: debe ser un círculo o un polígono.',
  'error.geofence.circle': 'El círculo necesita un centro y un radio entre 20 m y 500 km.',
  'error.geofence.polygon': 'El polígono necesita entre 3 y 200 vértices.',
  'error.geofence.schedule': 'El horario no es válido.',
  'error.geofence.email': 'Escribe al menos un correo válido.',
  'error.geofence.smtp.missing': 'El servidor no tiene SMTP configurado.',
  'error.geofence.smtp.failed': 'El servidor de correo rechazó el mensaje; revisa la configuración SMTP.',
  'error.alerts.badRequest': 'Faltan datos de la alerta.',
  'error.alerts.failed': 'No se pudieron cargar las alertas. Intenta de nuevo.',
  'error.report.failed': 'No se pudo generar el reporte. Intenta de nuevo.',

  // --- Protección contra restablecimiento de fábrica (FRP) ---
  'error.protection.account.invalid': 'Una de las cuentas no es válida: el ID debe ser numérico y el correo debe tener formato de correo.',
  'error.protection.folder.unknown': 'Esa carpeta ya no existe.',

  // --- Usuarios, roles y organización ---
  'error.duplicate.role': 'Ya existe un rol con ese nombre.',
  'error.duplicate.login': 'Ya existe un usuario con ese nombre de usuario.',
  'error.duplicate.email': 'Ya existe un usuario con ese correo.',
  'error.duplicate.customer.name': 'Ya existe una organización con ese nombre.',
  'error.notfound.customer.admin': 'La organización no tiene un administrador.',
  'error.user.not.found': 'El usuario no existe.',
  'error.password.empty': 'Escribe una contraseña.',
  'error.password.wrong': 'La contraseña actual no es correcta.',
};

/** A message that is a bare server key (`error.x.y`), as opposed to a sentence. */
const KEY = /^error\.[A-Za-z0-9_.-]+$/;

/**
 * What to show for the `message` of a server error envelope: the Spanish sentence of a known key, a generic
 * sentence naming the key for an unknown one (support can look it up), and anything else — the server already
 * writes many refusals as Spanish sentences — exactly as it came.
 */
export function serverErrorMessage(message: string | null | undefined): string {
  if (message == null || message === '') return GENERIC_SERVER_ERROR;
  const key = message.trim();
  if (!KEY.test(key)) return message;
  return ERROR_MESSAGES[key] ?? `El servidor rechazó la operación (${key}).`;
}
