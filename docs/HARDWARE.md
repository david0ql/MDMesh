# Hardware y puertos (criterio MDM-12)

Qué bloquea DallyControl en un equipo, desde qué versión de Android, dónde se configura y cómo se recupera un equipo
cuando el bloqueo estorba al soporte.

## Dónde se configura

- **Política** (Políticas → Editar → «Hardware y puertos»): cada función tiene «Sin gestionar», «Permitir» o
  «Bloquear». «Bloquear» apaga la función en todos los equipos de la política; «Permitir» la deja libre y quita un
  bloqueo anterior; «Sin gestionar» no la toca (y deshace lo que la política había bloqueado antes).
- **Equipo** (ficha del equipo → pestaña «Restricciones»): una excepción por función («Permitir» / «Bloquear» /
  «Según política») que gana sobre la política, y la tabla **Restricciones aplicadas**: qué pide la política, qué
  excepción tiene el equipo y qué reportó el agente (aplicado, no soportado y por qué, o falló y por qué).

El agente lee de vuelta cada restricción después de aplicarla: «Bloqueado en el equipo» significa que Android la tiene
activa, no que se envió la orden.

## Funciones

| Función | Qué hace al bloquear | Android |
|---|---|---|
| Cámara | Ninguna app usa las cámaras | 6+ |
| Capturas de pantalla | Sin capturas ni grabación de pantalla | 6+ |
| Captura de contenido y sugerencias | El sistema no captura el contenido de las apps | 10+ |
| Asistente (leer la pantalla) | El asistente no recibe el contenido de la pantalla | 15+ |
| Micrófono | Queda silenciado (las llamadas se establecen sin audio de ida) | 6+ |
| Transferencia de archivos por USB | Sin MTP/PTP ni memorias externas | 6+ |
| Depuración USB (ADB) | Apaga ADB por USB e inalámbrico y las opciones de desarrollador | 6+ |
| Datos por USB | Corta toda la señal de datos del puerto; la carga sigue | 12+, solo si el equipo lo permite |
| Tarjeta SD y memorias USB | No se montan medios físicos | 6+ |
| Compartir datos | Sin zona Wi‑Fi, módem USB ni módem Bluetooth | 6+ |
| Bluetooth | Apagado y no se puede encender | 8+ |
| Compartir archivos por Bluetooth | Bluetooth funciona pero no envía archivos | 8+ |
| Configurar Bluetooth | No se puede emparejar ni cambiar ajustes | 6+ |
| NFC | Apaga la radio NFC | 15+ (hay compilaciones de Android 15 que la rechazan: se reporta «No soportado por este Android»; verificado en Android 16) |
| Enviar datos por NFC (Beam) | Sin envío por contacto | 6+ |
| Wi‑Fi Direct | Sin conexiones directas entre equipos | 13+ |
| Modo avión | El usuario no puede activarlo | 9+ |
| Restablecer ajustes de red | No se pueden borrar las redes desde Ajustes | 6+ |
| Cambiar la ubicación (GPS) | El usuario no puede encender ni apagar la ubicación | 9+ |
| Compartir ubicación | Ubicación apagada para todas las apps (también para el MDM) | 6+ |
| Cambiar fecha y hora | Fecha, hora y zona fijas (antes de Android 9: hora de la red forzada) | 6+ |
| Instalar de orígenes desconocidos | Sin APK de fuera de la tienda; el MDM sigue instalando | 6+ |
| Imprimir | Ninguna app imprime | 9+ |

En un equipo con un Android anterior al indicado la consola muestra «No soportado: requiere Android N o superior».
«Datos por USB» depende además del fabricante (`canUsbDataSignalingBeDisabled`): si el equipo no puede, se reporta «No
soportado por el equipo» y lo demás de la política se aplica igual.

### Otras restricciones de Android

El campo «Restricciones de Android» acepta restricciones de usuario adicionales por nombre (`DISALLOW_SMS`) o por valor
(`no_sms`), separadas por coma, y solo las de la lista que muestra la consola («Ver la lista aceptada»). Lo que no esté
en la lista no se envía a los equipos y la consola lo marca; las que ya tienen su propia opción arriba se rechazan aquí
para que dos opciones no se contradigan.

## Cámara y quiosco

Con la cámara bloqueada el quiosco deja de mostrar el ícono de la función «Cámara», y las apps permitidas que la usan
(escanear códigos, fotos) reciben de Android el aviso «el administrador desactivó la cámara»: el quiosco sigue
funcionando. La consola avisa al bloquear la cámara en una política cuyo quiosco tiene esa función. Si un solo equipo la
necesita, se le pone la excepción «Permitir» en su pestaña «Restricciones».

## Soporte y recuperación (USB)

Bloquear la depuración USB o los datos por USB deja al técnico sin cable. Lo que hay que saber:

- **El enrolamiento por USB no cambia**: `scripts/adb-enroll.sh` (y el enrolamiento por QR o código) ocurre antes de que
  llegue la política. Un equipo recién restablecido siempre se puede enrolar por cable.
- **Abrir el puerto de un equipo**: ficha del equipo → «Restricciones» → «Depuración USB (ADB)» o «Datos por USB» →
  «Permitir». El equipo lo aplica en su siguiente conexión (segundos si está en línea). Al terminar, «Según política»
  lo vuelve a cerrar.
- **La depuración vuelve sola**: Android apaga el interruptor «Depuración USB» al bloquearla y no lo vuelve a encender
  al desbloquear. El agente recuerda si estaba encendida cuando la bloqueó y la vuelve a encender al quitar el bloqueo,
  así el cable del técnico funciona sin tocar el teléfono. Si estaba apagada, sigue apagada (se enciende en Opciones de
  desarrollador).
- **Para toda la política**: poner la función en «Permitir» o «Sin gestionar» y guardar.
- **Equipo sin conexión y con el puerto cerrado**: no recibe el cambio. Se recupera dándole red (Wi‑Fi conocida o
  datos) o, en último caso, con un restablecimiento de fábrica desde el modo recovery y un nuevo enrolamiento. Por eso
  conviene dejar una red Wi‑Fi de la empresa en la política antes de cerrar el puerto USB.

## Agentes anteriores

Los agentes anteriores a esta versión ignoran la sección nueva: aplican cámara, capturas, almacenamiento USB, Bluetooth
y compartir datos, y la consola muestra «Agente desactualizado» en las demás funciones hasta que se actualicen.

## Prueba

`scripts/hardware-e2e.py` (equipo real o emulador) y `scripts/hardware-console-e2e.mjs` (consola): ver
[TESTING.md](TESTING.md).
