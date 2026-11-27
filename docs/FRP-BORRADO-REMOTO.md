# Protección de restablecimiento de fábrica (FRP), modo perdido y borrado remoto

Cubre dos criterios de la matriz: **MDM-03** «Protección Factory Reset: realizar Hard Reset con botones físicos» y
**MDM-09** «Borrado remoto (Factory Wipe) ante pérdida o robo».

## 1. Qué se puede y qué no se puede impedir

| Vía | Qué hace DallyControl |
|-----|-----------------------|
| Restablecer desde Ajustes | Se **impide** (`DISALLOW_FACTORY_RESET`). |
| Modo seguro | Se **impide** (`DISALLOW_SAFE_BOOT`). |
| Depuración USB / opciones de desarrollador | Se **impide** si la política lo pide (`DISALLOW_DEBUGGING_FEATURES`). |
| Desbloqueo del bootloader (flashear otro sistema) | Se **impide**: Android 7–8.1 con `DISALLOW_OEM_UNLOCK`; desde Android 9 el interruptor «Desbloqueo de OEM» queda cerrado por el bloqueo de restablecimiento. |
| **Reset por botones físicos (recovery)** | **Ningún MDM lo puede impedir**: ocurre antes de que Android arranque. Lo que se garantiza es que el equipo quede **inservible para un tercero**: al terminar el reset solo se activa con una cuenta de Google de la empresa (FRP corporativa) y, una vez activado, vuelve a su carpeta en la consola. |

No existe una API pública para forzar el estado del bootloader (`setOemUnlock…` es de sistema); el cierre se hace con las
restricciones de arriba, que es lo que Android ofrece a un Device Owner.

## 2. Cómo funciona la FRP corporativa

El agente aplica las cuentas autorizadas según la versión de Android y **lee de vuelta** lo que quedó en el sistema:

| Android | Mecanismo | Verificación |
|---------|-----------|--------------|
| 11 o posterior (API 30+) | `DevicePolicyManager.setFactoryResetProtectionPolicy` con las cuentas y `factoryResetProtectionEnabled = true` | `getFactoryResetProtectionPolicy` |
| 5.1 a 10 (API 22–29) | Restricción de aplicación `factoryResetProtectionAdmin` sobre `com.google.android.gms` + broadcast `com.google.android.gms.auth.FRP_CONFIG_CHANGED` | `getApplicationRestrictions` |
| Anterior a 5.1 | No existe FRP | Se reporta «inactiva» |

El estado efectivo viaja al servidor en cada sincronización (`telemetry.security.protection`) y la consola lo muestra por
equipo en **Dispositivo → Control → Protección contra restablecimiento y robo**:

- «FRP activa con N cuentas».
- «FRP inactiva: …» con el motivo: no hay cuentas, el equipo no admite FRP administrada (sin bloque de datos
  persistente: algunos equipos sin Google, emuladores), faltan los Servicios de Google Play (Android 10 o anterior),
  el agente no es propietario del dispositivo, o el error exacto que devolvió Android.
- «FRP pendiente» cuando hay cuentas configuradas y el equipo aún no las aplica.

Nada falla en silencio: si Android rechaza una regla, `config.apply` responde `failed: …`, el evento
`protectionFailed` queda en el historial del equipo y el panel lo muestra en rojo.

### Android 15 y posteriores

Android 15 reforzó la FRP: saltarse el asistente de configuración ya no la desactiva, activar «Desbloqueo de OEM» tampoco,
y mientras la FRP está pendiente no se pueden agregar cuentas, poner bloqueo de pantalla ni instalar apps. Para un
Device Owner no cambia la API: `setFactoryResetProtectionPolicy` sigue definiendo qué cuentas desbloquean, y un borrado
ordenado por el MDM con «eliminar la protección» (`WIPE_RESET_PROTECTION_DATA`) sigue dejando el equipo libre para
reasignarlo. Lo que un Device Owner **no** puede hacer es desactivar la FRP de un equipo que ya fue restablecido por
recovery: solo la cuenta autorizada lo desbloquea.

## 3. Configurar las cuentas (una sola vez)

Android identifica la cuenta por su **ID numérico** (21 dígitos), no por el correo. La consola guarda ambos.

1. **Políticas → (una política) → Restablecimiento de fábrica → «Administrar cuentas de la empresa y carpetas»**.
2. En «Toda la empresa» escribe el correo de la cuenta corporativa y su ID, y pulsa «Agregar» y «Guardar cuentas».
3. Opcional: elige una carpeta y dale cuentas propias (por ejemplo, una por país). Sus subcarpetas las heredan.

Herencia, de más cercano a más lejano: cuentas propias de la **política** → **carpeta** más cercana del equipo que tenga
cuentas → **empresa**. Una política también puede elegir «Sin protección».

### Obtener el ID de la cuenta (método oficial)

1. En un navegador, inicia sesión en Google **con la cuenta corporativa** (usa una ventana de incógnito si tienes otra
   sesión abierta).
2. Abre <https://developers.google.com/people/api/rest/v1/people/get>. En el panel «Try this method» escribe
   `resourceName = people/me` y `personFields = metadata` (el enlace de la consola ya los trae puestos).
3. Pulsa **Execute** y acepta el permiso de lectura del perfil.
4. En la respuesta, el ID es el número de `"resourceName": "people/123456789012345678901"` (también está en
   `metadata.sources[0].id`).

La consola no tiene un botón «Obtener ID» con inicio de sesión de Google: eso exige registrar credenciales OAuth de
cliente en Google Cloud para cada instalación. El explorador de la API de Google usa las credenciales de Google y no
requiere registrar nada. La consola valida el formato (solo dígitos) y rechaza un correo escrito en el campo del ID.

**Guarda la contraseña de esa cuenta en un lugar seguro y con verificación en dos pasos recuperable**: es la única llave
para reactivar un equipo restablecido por botones.

### Aviso «bloqueo activo sin cuentas»

Si una política bloquea el restablecimiento desde Ajustes pero ni ella, ni las carpetas, ni la empresa tienen cuentas, el
editor de la política muestra un aviso: tras un reset por botones el equipo quedaría libre.

## 4. Recuperar un equipo restablecido por botones

1. Enciende el equipo y conéctalo a Wi-Fi. El asistente pide **verificar la cuenta**: inicia sesión con la cuenta
   corporativa autorizada. Con cualquier otra cuenta el equipo no pasa de ahí.
2. Vuelve a inscribirlo:
   - **Por QR**: termina el asistente, ve a Ajustes → Sistema → Restablecer (este reset, hecho con la cuenta
     verificada, es de confianza y no vuelve a activar la FRP) y, en la pantalla de bienvenida, toca 6 veces y escanea
     el QR de inscripción.
   - **Por cable, sin otro reset**: termina el asistente, quita la cuenta en Ajustes → Cuentas y ejecuta
     `scripts/adb-enroll.sh` (ver `docs/DEV.md`).
3. El equipo **vuelve a su fila y a su carpeta**: el servidor lo reconoce por el identificador de inscripción y, si ese
   cambió (Android 11 o anterior), por el **número de serie o el IMEI**. Si la fila se había eliminado de la consola,
   se recrea en la carpeta que tenía (o en «Recuperados» si esa carpeta ya no existe). En el historial del equipo queda
   el evento «Se inscribió de nuevo después de un restablecimiento».

Protección del emparejamiento: no se usan números de serie genéricos (`unknown`, `0123456789ABCDEF`, emuladores) ni
IMEI inválidos, para no confundir dos teléfonos distintos.

### Con Zero-Touch

Un equipo registrado en Zero-Touch descarga e instala el agente por sí solo en cada restablecimiento (inscripción
forzada), así que el paso 2 es automático: basta encenderlo con red. El emparejamiento por serie/IMEI es el mismo, de
modo que vuelve a su carpeta sin intervención. La FRP corporativa y Zero-Touch son compatibles y se complementan: Zero-
Touch garantiza que el equipo vuelva a ser gestionado; la FRP, que nadie lo use mientras tanto. La exportación de la
configuración de Zero-Touch desde la consola está documentada aparte.

## 5. Modo perdido

**Dispositivo → Control → Modo perdido**. Al activarlo el equipo:

- se bloquea de inmediato y muestra el mensaje y el teléfono de contacto en la pantalla de bloqueo;
- suena un minuto (opcional);
- toma una posición cada minuto (y vuelve a la cadencia de su política al desactivarlo);
- opcional: **se borra tras N intentos fallidos de desbloqueo** (`setMaximumFailedPasswordsForWipe`; requiere que el
  equipo tenga bloqueo de pantalla);
- opcional: **se borra si no logra reportar al servidor en N días**. Lo decide el propio teléfono, así que funciona
  aunque le quiten la SIM y lo dejen sin Wi-Fi. Ese borrado conserva la FRP.

La orden espera en cola sin vencer si el equipo no tiene enlace. El estado («Modo perdido: activo desde…») lo reporta
el equipo y se ve en el mismo panel.

## 6. Borrado remoto

**Dispositivo → Control → Borrado remoto**. Dos intenciones:

| Intención | FRP | Para qué |
|-----------|-----|----------|
| Equipo perdido o robado | **Se conserva** | Tras el borrado solo se activa con la cuenta de la empresa. |
| Preparar para reasignar | **Se elimina** (`WIPE_RESET_PROTECTION_DATA`) | Queda como nuevo para inscribirlo otra vez. |

Opciones: borrar la tarjeta SD / almacenamiento externo (`WIPE_EXTERNAL_STORAGE`), borrar las eSIM (`WIPE_EUICC`,
Android 9+), borrar sin aviso (`WIPE_SILENTLY`, Android 10+) y el motivo que se muestra en el teléfono (Android 9 a 13;
desde Android 14 el borrado completo usa `wipeDevice`, que no muestra motivo). Lo que una versión de Android no admite
se informa en el resultado de la orden (`notApplied=…`), no se ignora en silencio.

Confirmación y evidencia:

- Hay que escribir **BORRAR**.
- El historial del equipo guarda **quién** lo ordenó, desde qué dirección y **con qué opciones**.
- El agente **avisa al servidor justo antes de borrar** (con reintento breve); la consola muestra «Borrado iniciado por
  el equipo» con la hora y marca el equipo como «Borrado». Si el equipo no vuelve a reportar, la orden queda como
  «Borrado ejecutado».
- Si el equipo está sin enlace, la orden **no vence**: se entrega y ejecuta en cuanto se reconecta.
- Si el agente muere entre el aviso y el borrado, lo retoma al arrancar.
- Agentes anteriores (0.9.8 o menos) borran el equipo pero ignoran las opciones; la consola lo advierte.

## 7. Prueba en emulador

`scripts/frp-wipe-e2e.sh` (ver su cabecera) inscribe un emulador contra un stack local y comprueba: FRP aplicada y leída
con `dumpsys device_policy`, restricciones, modo perdido de punta a punta, borrado en línea y borrado con el equipo en
modo avión más de 60 minutos (adelantando la base de datos). Las imágenes del emulador no tienen `/misc`, así que el
reset real no corre: se verifica la orden entregada a recovery, como describe `docs/TESTING.md`.

Las imágenes del emulador tampoco tienen bloque de datos persistente, de modo que Android 11+ responde «no admite FRP
administrada» al aplicar la política: el script comprueba que ese motivo llegue a la consola en vez de un «aplicado»
falso. La activación real de la FRP solo se puede confirmar en un teléfono con Servicios de Google.

## 8. Prueba en teléfono físico (reset por botones)

Necesitas un teléfono con Servicios de Google, inscrito como Device Owner, y una cuenta de Google corporativa de prueba.

**Preparación**

1. Configura la cuenta en «Toda la empresa» (sección 3) y deja la política con «Bloquear desde Ajustes».
2. Sincroniza el equipo y confirma en el panel: **«FRP activa con 1 cuenta»**. Por cable:
   `adb shell dumpsys device_policy | grep -i -A3 "factory"` debe mostrar la cuenta y `enabled=true`
   (Android 11+), o `adb shell dumpsys device_policy | grep -A3 com.google.android.gms` (Android 10 o anterior).
3. Comprueba que Ajustes → Sistema → Restablecer está deshabilitado.

**Caso A — reset por botones con la cuenta autorizada**

1. Apaga el teléfono. Entra a recovery (según el fabricante: volumen arriba + encendido) y elige
   «Wipe data / factory reset».
2. Al reiniciar, conecta Wi-Fi. Debe aparecer **«Verifica tu cuenta»**.
3. Inicia sesión con la cuenta corporativa: el asistente continúa. **Esperado: pasa.**
4. Reinscríbelo (sección 4) y confirma que vuelve a su carpeta y que el historial muestra el evento de reinscripción.

**Caso B — reset por botones sin la cuenta autorizada**

1. Repite el reset por recovery.
2. En «Verifica tu cuenta» usa una cuenta de Google personal (aunque hubiera estado antes en el teléfono).
   **Esperado: la rechaza; el equipo no pasa del asistente.**
3. Intenta saltar el asistente sin red. **Esperado: no permite terminar (Android 15+: tampoco agregar cuentas ni
   instalar apps).**
4. Termina con la cuenta corporativa para recuperar el equipo.

**Caso C — borrado remoto «perdido o robado»**: ordena el borrado desde la consola; el equipo se borra, la consola marca
«Borrado ejecutado» con hora, y al encender pide la cuenta corporativa.

**Caso D — borrado remoto «preparar para reasignar»**: tras el borrado el asistente **no** pide verificar cuenta.

**Caso E — modo perdido**: actívalo con mensaje y teléfono; el equipo se bloquea, suena y la pantalla de bloqueo muestra
el contacto; con «borrar tras 5 intentos» y un PIN puesto, cinco PIN erróneos lo borran.

Anota modelo, versión de Android y resultado de cada caso: el comportamiento del asistente lo implementa Google Play
services y varía ligeramente entre fabricantes.
