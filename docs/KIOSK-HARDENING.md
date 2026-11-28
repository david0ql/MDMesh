# Quiosco sin vías de escape

Cómo queda cerrado el modo quiosco de DallyControl (una app o varias), qué configuración se recomienda y cómo
comprobar en un teléfono que no hay forma de salir a los Ajustes del sistema ni a apps que no son de trabajo.

## Qué cierra el quiosco

| Capa | Android 7 a 10 | Android 11 a 15 | Android 16 o superior |
|---|---|---|---|
| Bloqueo de tareas (lock task) | Sí | Sí | No se usa (ver «Por qué Android 16 es distinto») |
| Pantalla de inicio propia y persistente | Sí | Sí | Sí |
| Ajustes del sistema (`com.android.settings`) | Lock task (tareas nuevas) + **vigilante de primer plano** | Lock task, que además **bloquea los inicios dentro de la tarea** de una app permitida (`LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK`) | **Vigilante de primer plano** |
| Buscador de Ajustes y otras apps que llevan a Ajustes | Suspendidos | Suspendidos | Suspendidos |
| Todas las apps con ícono que no están en el quiosco, suspendidas | No hace falta (lock task) | No hace falta (lock task) | Sí |
| Barra de estado / ajustes rápidos del sistema | Lock task deja solo las notificaciones (sin ajustes rápidos ni engranaje) | Igual | Deshabilitada, salvo «Notificaciones = Sí» |
| Modo seguro bloqueado (`DISALLOW_SAFE_BOOT`) | Sí | Sí | Sí |
| Crear usuarios / cambiar de usuario (`DISALLOW_ADD_USER`, `DISALLOW_USER_SWITCH`) | Sí | Sí | Sí |
| Diálogos de «la app se detuvo» (su botón «Info de la app» abre Ajustes) | Bloqueados (Android 9+) | Bloqueados | Bloqueados |
| Salida en el teléfono | Solo con contraseña, con límite de intentos | Igual | Igual |

**El hueco que cierra esto.** Lock task solo impide abrir una pantalla en una tarea *nueva*. Una app permitida puede
abrir la pantalla de otra app *dentro de su propia tarea* (p. ej. `startActivity(Settings.ACTION_WIFI_SETTINGS)`), y
desde ahí navegar por todo Ajustes. Comprobado en emulador con Android 15 antes del cambio: la pantalla de Wi‑Fi de
Ajustes se abría desde la app de trabajo.

- **Android 11 a 15:** se activa la opción de lock task que bloquea iniciar, dentro de la tarea de una app permitida,
  pantallas de apps que no están en el quiosco. Android muestra «esta app no está disponible». **Efecto colateral a
  validar con la app de trabajo:** si la app abre *dentro de su tarea* otra app que no está en el quiosco (cámara por
  intent, selector de archivos, visor de PDF, Chrome), ahora se bloquea: hay que agregar esa app al quiosco (puede
  ir en «Ocultar del quiosco» para que no tenga ícono). El diálogo de permisos de Android sigue funcionando.
- **Android 7 a 10** (no existe esa opción) **y Android 16+** (sin lock task): el agente vigila qué app está en primer
  plano (acceso de uso, que da el enrolamiento por cable) y, si aparece Ajustes, vuelve al quiosco de inmediato y lo
  reporta (`kioskEscapeBlocked`). Es una mitigación: la pantalla puede verse un instante (menos de un segundo). La
  siguiente vez que se abre la app de trabajo arranca limpia, para que no quede Ajustes encima. En un equipo
  enrolado por QR el vigilante es el servicio de pantalla del agente y necesita un toque; **mientras falte, el
  quiosco no abre ninguna app** (ver «El vigilante y su única autorización»).

**Por qué no se suspende ni se oculta la app Ajustes.** En Ajustes vive la pantalla de inicio que Android usa
mientras arranca (FallbackHome). Probado en emulador: con Ajustes *oculto* (`setApplicationHidden`) el teléfono no
termina de encender (Android 15); con Ajustes *suspendido* enciende en un aviso «acción no permitida» en vez del
quiosco y no entra al quiosco hasta que alguien lo toca (Android 10). Además Android 14+ ya no deja suspenderlo. Por
eso el quiosco nunca toca esa app: solo suspende lo que lleva a ella y las apps que no son de trabajo.

El agente vuelve a aplicar todo esto en cada ciclo (cada check-in, al instalarse una app y al arrancar), así que una
app instalada después o una restricción quitada por fuera se vuelve a cerrar sola. Al salir del quiosco (con la
contraseña o desde la consola) se levanta únicamente lo que puso el quiosco.

### El vigilante y su única autorización (equipos enrolados por QR)

El vigilante (Android 7–10 y 16+) es el **servicio de pantalla del propio agente**, el mismo del soporte remoto
(ADR 0012): Android le avisa en el instante en que se abre una ventana y, si es de una app que el quiosco mantiene
cerrada, manda el equipo a Inicio (el quiosco). No hace falta «acceso de uso». Como Android no siempre avisa
(visto en emulador: una tarea ya abierta que vuelve al frente, y los primeros segundos tras reiniciar), el servicio
además mira cada 0,6 s qué ventana está al frente, dentro del propio agente. En Android 16+ devuelve
**cualquier** app que no esté permitida en el quiosco; en Android 7–10, los Ajustes (lo demás ya lo cierra lock task).
Mientras hay un quiosco activo el servicio recibe los cambios de ventana de todas las apps, y solo lee el nombre del
paquete; fuera del quiosco vuelve a escuchar solo a la interfaz del sistema.

Android no deja que un Device Owner encienda su propio servicio de accesibilidad. Lo enciende:

- el enrolamiento por cable (`scripts/adb-enroll.sh`), solo; o
- **un toque en el teléfono, una sola vez**: la pantalla guiada «Autoriza DallyControl en este dispositivo», que
  aparece al enrolar por QR y explica las dos cosas que habilita (soporte remoto y quiosco reforzado).

Si el equipo ya tenía «acceso de uso» (enrolado por cable con un agente anterior), el vigilante lo usa como
alternativa (sondeo) mientras el servicio de pantalla no esté encendido.

**Cerrado hasta autorizar.** Sin esa autorización el quiosco **no abre ninguna app** (desde el agente siguiente a
la 0.9.8):

- las apps permitidas quedan **suspendidas** igual que las demás, así que no las abre ni el inicio del quiosco, ni
  Recientes, ni una notificación; Android responde «Bloqueada por la política de trabajo»;
- el inicio del quiosco muestra solo la pantalla «**Este equipo aún no está autorizado**» con el botón **Autorizar
  ahora**, y la pantalla guiada se abre sola encima al entrar al quiosco;
- en modo de una sola app, la app no se lanza hasta que se da el toque; en cuanto se da, se abre sola (no hay que
  tocar nada más ni esperar a la consola);
- el agente reporta `kioskGuard = needsAuthorization` y `kioskLockdownGap = authGate: quiosco cerrado…`; la consola
  muestra en la ficha del equipo «**Falta autorización en el teléfono**» con el botón **«Pedir autorización en el
  teléfono»**;
- la salida con contraseña sigue disponible en esa pantalla (según el modo de salida de la política).

Con esto **no existe un quiosco en uso desde el que una app permitida pueda abrir Ajustes**: el hueco de Android
7–10 y 16+ ya no depende de que alguien se acuerde de dar el toque; sin el toque el equipo no sirve para trabajar y
lo dice en pantalla y en la consola. Lo único que sigue pasando por Ajustes es el toque mismo: al pulsar «Autorizar
ahora» se abre la pantalla de Accesibilidad de Ajustes durante 5 minutos como máximo (se cierra en cuanto el
interruptor queda encendido o se vuelve al inicio del quiosco). Por eso **el toque lo da quien enrola el equipo,
antes de entregarlo**.

Dos casos en que el quiosco NO se cierra así (queda como antes: franja roja «Falta una autorización», pantalla
guiada cada 10 minutos, y una app permitida puede abrir Ajustes hasta que se dé el toque):

- la política tiene **Soporte remoto → Pedir la autorización al inscribir = No**: el administrador decidió no pedir
  el toque y acepta el hueco;
- un equipo que **ya estaba trabajando en quiosco** cuando recibió este agente como actualización: no se le
  interrumpe el trabajo en campo. En cuanto se autoriza, o sale del quiosco y vuelve a entrar, pasa a la regla
  general.

#### Por qué no se puede quitar el toque (comprobado, 2026-11-28)

Se probaron en emulador, como Device Owner sin ningún permiso dado por cable, todas las vías que Android ofrece. El
agente las intenta al arrancar mientras falte el toque y deja el resultado en el registro (`SupportEngine:
device-owner self-authorisation: …`) y en la sesión de soporte (`info.probe`). Texto literal en los dos emuladores:
`grantWriteSecureSettings=refused(false) setSecureSetting=threw SecurityException (Permission denial: Device owners
cannot update enabled_accessibility_services) grantUsageAccess=refused(false)`.

| Vía | Qué responde Android (API 29 y API 36) |
|---|---|
| `DevicePolicyManager.setSecureSetting(ENABLED_ACCESSIBILITY_SERVICES, …)` | `SecurityException: Permission denial: Device owners cannot update enabled_accessibility_services`. Un Device Owner solo puede escribir una lista fija de ajustes (método de entrada por defecto, fuentes desconocidas, modo de ubicación…); los servicios de accesibilidad no están en ella. |
| `setPermissionGrantState(WRITE_SECURE_SETTINGS)` (para escribir el ajuste directamente) | Devuelve `false` y el permiso sigue sin concederse: solo concede permisos «de ejecución»; este es de firma/desarrollo. |
| `setPermissionGrantState(PACKAGE_USAGE_STATS)` (acceso de uso, la otra fuente del vigilante) | Devuelve `false` por la misma razón; el acceso de uso es además una «operación de app» que solo cambian el usuario en Ajustes o adb. |
| `setPermittedAccessibilityServices` | Solo **restringe** qué servicios puede encender el usuario; no enciende ninguno. |
| Ocultar o suspender la app Ajustes | Rompe el arranque del teléfono (ver arriba). |
| Lock task con bloqueo de inicios dentro de la tarea | No existe antes de Android 11; en Android 16 lock task hace caer el sistema en los equipos del cliente (ver abajo). |
| Dejar inútil lo que Ajustes permite (restricciones de usuario) | Reduce el daño, pero Ajustes sigue abriéndose: no cumple «sin vías de escape». |

Conclusión: en un enrolamiento por QR / Zero-Touch **no hay forma de API de encender el vigilante sin un toque** en
Android 7–10 ni en Android 16+. Android 11–15 no lo necesita. El enrolamiento por cable (`scripts/adb-enroll.sh`, el
inscriptor de Windows) sí lo enciende solo, porque adb puede dar `WRITE_SECURE_SETTINGS`. Por eso el toque quedó
como paso obligatorio e imposible de saltar (arriba), no como recomendación.

Estados en la consola (evento `kioskGuard`):

| Estado | Qué significa |
|---|---|
| «Quiosco reforzado: Android bloquea los Ajustes del sistema» (`lockTask`) | Android 11–15, o Android 16+ con el bloqueo nativo. No necesita autorización. |
| «Quiosco reforzado: si se abren los Ajustes, el equipo vuelve solo al quiosco» (`guard`) | Android 7–10 / 16+ con el servicio de pantalla encendido (o con acceso de uso). |
| «Falta autorización en el teléfono» (`needsAuthorization`) | Android 7–10 / 16+ sin el toque: el quiosco está cerrado (no abre ninguna app) hasta que se dé. |

### Por qué Android 16 es distinto

En Android 16 el bloqueo de tareas hace caer `system_server`: un `NullPointerException` dentro de
`LockTaskController` cuando el sistema anima la transición de una tarea que está en la lista de lock task (traza en
`DisplayContent.executeAppTransition`; confirmado en Samsung A17 con Android 16). Al caer `system_server` el teléfono
se reinicia, vuelve al quiosco, abre la app y cae de nuevo: un ciclo de reinicios del que no se sale a distancia. No
se puede atrapar desde una app y ocurre con solo tener paquetes en la lista de lock task (no hace falta llamar a
`startLockTask`), así que desde el agente 0.9.0 en Android 16+ **no se usa lock task** («quiosco suave»): la lista se
deja vacía.

`LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` no lo arregla: es una opción *de* lock task, y el fallo está en lock
task mismo. En el emulador de Android 16 (imagen de Google, no Samsung) no se reproduce la caída. Por eso Android 16+
queda así por defecto, **sin presentarlo como bloqueo absoluto**:

- Cerrado de verdad: apps que no son de trabajo (suspendidas), barra de estado y ajustes rápidos (deshabilitados por
  defecto), Inicio (vuelve al quiosco), modo seguro, usuarios, salida sin contraseña.
- **Mitigado, no bloqueado:** los Ajustes del sistema. Si una app permitida los abre, el vigilante devuelve el
  equipo al quiosco en el instante en que se abre la ventana; puede alcanzar a verse un parpadeo.
- No se puede cerrar: el menú de apagado (apagar/reiniciar; al encender vuelve al quiosco) y los botones
  Inicio/Recientes (Recientes solo puede reabrir apps permitidas).

#### Interruptor «Bloqueo nativo en Android 16+» (apagado por defecto)

Política → panel de funciones → **Bloqueo nativo en Android 16+**. Encendido, el agente usa lock task en Android 16+
igual que en Android 15 (con el bloqueo de inicios dentro de la tarea): los Ajustes quedan bloqueados por Android, sin
depender de ninguna autorización, y «Bloquear botones físicos» vuelve a cerrar el menú de apagado.

Cómo probarlo en un modelo (que no sea el A17, donde ya se sabe que falla):

1. Crea una política de prueba, copia de la real, con el interruptor encendido, y asígnala a **un** equipo que
   tengas en la mano (con depuración USB permitida, para poder sacarlo si entra en ciclo).
2. Abre la app de trabajo, sal con Inicio y vuelve a entrar unas 20 veces; reinicia el equipo dos veces. Si
   `adb shell dumpsys activity activities | grep mLockTaskModeState` dice `LOCKED` y el equipo no se reinicia solo, corre
   `scripts/kiosk-escape-test.py` contra él.
3. Red de seguridad: si el equipo arranca 3 veces en 10 minutos con el interruptor encendido, el agente lo apaga
   solo en ese equipo (vuelve al quiosco suave) y lo reporta (`kioskLockdownGap: nativeLock16 apagado…`). Para
   sacarlo a mano: apagar el interruptor en la política, o `adb shell dpm remove-active-admin` en un equipo de
   prueba.
4. Solo cuando un modelo pase, asigna la política a los demás equipos de ese modelo.

## Salida del quiosco

- Siempre pide la **contraseña de salida** de la política (7 toques en la esquina superior derecha, o el botón
  «Salir del quiosco» si la política lo muestra). En una sola app, la esquina está en la pantalla del quiosco que
  aparece al salir varias veces de la app («Abrir …»).
- La consola no deja guardar un quiosco sin contraseña (mínimo 4 caracteres; botón **Generar**). Si una API guarda
  una política de quiosco sin contraseña, el servidor genera una de 8 dígitos.
- 2 errores libres; al tercero, espera de 30 s que se duplica con cada error (1 min, 2 min … máx. 30 min). Durante
  la espera no se compara nada, ni la contraseña correcta. La espera sobrevive a un reinicio. La comparación es en
  tiempo constante.
- Cada intento llega al historial del equipo: `kioskExit` (salió), `kioskExitFailed` (contraseña incorrecta, con el
  número de intento) y `kioskExitDenied` (rechazado: en espera o sin contraseña).
- Un quiosco **sin contraseña** no tiene salida en el teléfono: solo desde la consola («Quitar quiosco»).
- La clave antirrobo (apagar/reiniciar) usa el mismo límite de intentos (`powerPinFailed`).

### Equipos y políticas que ya estaban en campo

| Caso | Qué pasa al actualizar el servidor |
|---|---|
| Política de quiosco **con** contraseña | Nada cambia. |
| Política de quiosco **sin** contraseña | La migración `26.11.27-kiosk-exit-password` le genera una de 8 dígitos (se ve en la política: Seguridad → «Contraseña de salida del quiosco» → **Ver**). El equipo sigue en su quiosco; desde el siguiente check-in los 7 toques piden esa contraseña. Antes salían sin preguntar. |
| Agente antiguo (0.8.x–0.9.8) con quiosco sin contraseña que aún no se reaplica | Sigue como estaba hasta su siguiente check-in; entonces recibe la contraseña generada y la exige (los agentes antiguos ya comparaban la contraseña cuando había una). |
| «Entrar en quiosco» personalizado enviado sin contraseña (API o consola antigua) | El servidor lo envía con salida «solo remoto»: el teléfono no muestra ninguna salida, en cualquier versión del agente. |
| Agente nuevo con un quiosco guardado sin contraseña (antes de reaplicar) | No sale en el teléfono: muestra «solo se sale desde la consola» y lo reporta. |

| Equipo Android 7–10 o 16+ enrolado por QR, **ya en quiosco**, que recibe el agente nuevo | Sigue trabajando: no se cierra. Muestra la franja «Falta una autorización» y la pantalla guiada cada 10 minutos hasta que alguien dé el toque. La regla «cerrado hasta autorizar» le aplica desde la siguiente vez que entre al quiosco. |
| Equipo Android 7–10 o 16+ enrolado por QR, sin autorizar, al que se le activa el quiosco **después** de actualizar | El quiosco queda cerrado (no abre apps) hasta dar el toque en el teléfono; la consola dice «Falta autorización en el teléfono». Para no exigirlo: política → Soporte remoto → «Pedir la autorización al inscribir = No» (queda el hueco de Ajustes). |

Ningún equipo sale del quiosco ni queda bloqueado por la actualización: las apps permitidas siguen abriendo y
«Quitar quiosco» desde la consola funciona siempre.

## Configuración recomendada

Política → grupo **Quiosco** y **Seguridad**, y el panel de funciones del dispositivo:

| Opción | Recomendado | Por qué / riesgo de cambiarlo |
|---|---|---|
| Modo quiosco | Sí, con App principal | Una app instalada = una sola app; varias apps o alguna función = pantalla de inicio con esas apps. |
| Contraseña de salida del quiosco | Generada (8 dígitos) y distinta por política | Obligatoria. Quien la conoce saca el equipo del quiosco. |
| Botón Salir del quiosco | Auto (oculto) | Visible invita a probar contraseñas (hay límite de intentos, pero no aporta nada al usuario). |
| Botones Atrás e Inicio | Auto (visibles) | Sin riesgo: Inicio vuelve al quiosco. |
| Botón Recientes | Auto, o «No» en una sola app | Solo abre apps permitidas. «No» evita la pantalla dividida. |
| Notificaciones | Auto | Auto = barra permitida en Android ≤ 15 y cerrada en Android 16+. «Sí» en Android 16+ expone los ajustes rápidos del sistema (modo avión, Wi‑Fi, datos): no se llega a Ajustes, pero el usuario puede dejar el equipo sin conexión. «No» = cerrada siempre. |
| Hora, batería y señal | Auto | Solo informativa. |
| Bloquear botones físicos | Sí | Cierra el menú de apagado (Android 9 a 15). Sin esto se puede apagar/reiniciar (vuelve al quiosco). |
| Antirrobo: clave para apagar | Una clave de 6+ dígitos | Apagar/reiniciar pide la clave; bloquea además el restablecimiento desde Ajustes. |
| Restablecimiento de fábrica | Bloquear, con cuentas de reactivación | El borrado por botones (recovery) no lo impide ningún MDM; las cuentas de reactivación lo dejan inservible para un tercero. |
| Depuración USB | Bloquear | Con cable y adb se pueden abrir pantallas o quitar apps. Al bloquear, soporte tampoco puede usar adb. |
| Instalación de apps por el usuario | Bloquear | Solo la consola instala. |
| Compartir datos | Bloquear si no se necesita | Permitido abre **solo** la pantalla de zona Wi‑Fi, sola en su tarea, 2 minutos como máximo y cerrada al volver al quiosco. |
| Ajustes rápidos del quiosco | Sí | Panel propio (brillo, volumen, Wi‑Fi, Bluetooth): nunca abre los Ajustes del sistema. |
| Política de apps | Solo apps permitidas + ocultar Play Store | Lo que no es de trabajo desaparece también fuera del quiosco. |

Lo que ningún MDM puede impedir: el apagado forzado (mantener el botón ~10 s; al encender vuelve al quiosco) y el
borrado desde recovery (mitigado con las cuentas de reactivación).

## Lista de pruebas de escape (en el teléfono)

Con el equipo en el quiosco de su política. Todo debe terminar **dentro** del quiosco.

| # | Prueba | Resultado esperado |
|---|---|---|
| 1 | Deslizar desde arriba (barra de estado) y tocar el engranaje | Android ≤ 15: baja la barra solo con notificaciones, sin ajustes rápidos del sistema ni engranaje. Android 16+: la barra no baja (con «Notificaciones = Sí» baja, y si se toca el engranaje el equipo vuelve al quiosco). |
| 2 | Botón/gesto Inicio | Vuelve al quiosco (o a la app fijada). |
| 3 | Botón/gesto Recientes; abrir lo que muestre | Solo apps permitidas; ninguna otra abre. |
| 4 | Mantener el botón de encendido | Con «Bloquear botones» o antirrobo: no aparece el menú (Android 9–15). Sin eso: aparece; reiniciar vuelve al quiosco. |
| 5 | Reiniciar | Enciende directo en el quiosco. |
| 6 | Modo seguro (mantener «Apagar» en el menú, o volumen abajo durante el arranque) | No ofrece modo seguro; arranca normal, en el quiosco. |
| 7 | Desde una app permitida, cualquier enlace a Ajustes (permisos, «Info de la app», Wi‑Fi…) | Android 11–15: «esta app no está disponible». Android 7–10 y 16+: Ajustes puede asomar un instante y el equipo vuelve solo al quiosco; la app de trabajo se abre normal después. |
| 8 | `adb shell am start -a android.settings.SETTINGS` (si la depuración no está bloqueada) | No abre Ajustes (Android 16+: vuelve solo al quiosco). |
| 9 | 7 toques en la esquina superior derecha, contraseña incorrecta 3 veces | No sale; al tercer error indica la espera; un 4.º intento ni pide contraseña. En la consola quedan los intentos. |
| 10 | 7 toques con la contraseña correcta (pasada la espera) | Sale al inicio normal del teléfono; en la consola queda «Salió del quiosco». |
| 11 | Quiosco sin contraseña (personalizado, «solo remoto») y 7 toques | No hay salida en el teléfono. |
| 12 | «Compartir datos» → pantalla de zona Wi‑Fi → Atrás | Vuelve al quiosco; después Ajustes sigue sin abrir. |
| 13 | Notificación de otra app / llamada entrante | Solo se atienden las de apps permitidas (la llamada, si la función Teléfono está permitida). |

### Prueba automática

`scripts/kiosk-escape-test.py` hace las pruebas 1–12 por adb contra un equipo o emulador enrolado, en una sola app y
en varias apps, y deja la tabla de resultados:

```bash
scripts/build-escapeapp.sh                      # una vez: la «app de trabajo» de prueba
scripts/adb-enroll.sh --serial emulator-5600 --server http://10.0.2.2:8088 --api-url http://localhost:8088 \
    --admin-user admin --apk agent-android/app/build/outputs/apk/debug/app-debug.apk --debug-build
scripts/kiosk-escape-test.py --serial emulator-5600 --device-id <id> --api http://localhost:8088 --reboot \
    --summary /tmp/kiosk-api35.md
```

`--legacy-psql "<comando psql del stack>"` además envía el quiosco que mandaría un servidor antiguo (gesto, sin
contraseña) para comprobar que el agente por sí solo se niega a salir.

## Resultados en emulador (2026-11-27)

Stack local propio, agente de depuración ya fusionado con `integration/mobicontrol`, emuladores limpios enrolados
por ADB, `scripts/kiosk-escape-matrix.sh` (una pasada por versión; máquina compartida, carga 5–26).
«pasa» = el intento de escape no prosperó (o el comportamiento esperado se cumplió). Las celdas con texto son
observaciones, no veredictos.

| Modo | Intento | Android 10 (API 29) | Android 15 (API 35) | Android 16 (API 36) |
|---|---|---|---|---|
| una app | kiosk.enter aplicado | pasa | pasa | pasa |
| una app | lock task LOCKED | pasa | pasa | — |
| una app | restricción no_safe_boot | pasa | pasa | pasa |
| una app | restricción no_add_user | pasa | pasa | pasa |
| una app | restricción no_user_switch | pasa | pasa | pasa |
| una app | restricción no_system_error_dialogs | pasa | pasa | pasa |
| una app | modo seguro deshabilitado (safe_boot_disallowed=1) | pasa | pasa | pasa |
| una app | Ajustes no está suspendido (lo necesita el arranque de Android) | pasa | pasa | pasa |
| una app | Android 7–10 / 16+: el agente tiene acceso de uso (vigilante de primer plano) | pasa | — | pasa |
| una app | la app de trabajo no está suspendida | pasa | pasa | pasa |
| una app | en primer plano: la app fijada | pasa | pasa | pasa |
| una app | adb am start SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| una app | adb am start WIFI_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| una app | adb am start APPLICATION_DEVELOPMENT_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| una app | adb am start SECURITY_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| una app | app permitida → intent SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| una app | app permitida → intent WIFI_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| una app | app permitida → intent SETTINGS (tarea nueva): no abre Ajustes | pasa | pasa | pasa |
| una app | app permitida → intent WIFI_SETTINGS (tarea nueva): no abre Ajustes | pasa | pasa | pasa |
| una app | tras los intentos, la app de trabajo se abre normalmente (no queda Ajustes encima) | pasa | pasa | pasa |
| una app | barra de estado | se despliega, sin engranaje | no se despliega, sin engranaje | no se despliega, sin engranaje |
| una app | barra de estado / engranaje: no abre Ajustes | pasa | pasa | pasa |
| una app | Inicio: sigue en el quiosco | pasa | pasa | pasa |
| una app | Recientes | en primer plano: com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivi | en primer plano: com.dallycontrol.escapetest/.Main | en primer plano: com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivi |
| una app | Recientes: no muestra Ajustes ni saca del quiosco | pasa | pasa | pasa |
| una app | Recientes → abrir tarjeta: sigue en apps permitidas | pasa | pasa | pasa |
| una app | tras Recientes, Inicio vuelve al quiosco | pasa | pasa | pasa |
| una app | menú de encendido (por defecto) | se abre | se abre | se abre |
| una app | tras el menú de encendido sigue en el quiosco | pasa | pasa | pasa |
| una app | quiosco con «bloquear botones»: aplicado | pasa | pasa | — |
| una app | con «bloquear botones» la política quita GLOBAL_ACTIONS (16) de lock task | esta versión no muestra la máscara de lock task en dumpsys | pasa | — |
| una app | con «bloquear botones» el menú de encendido NO se abre | pasa | — | — |
| una app | 7 toques + contraseña incorrecta #1: no sale | pasa | pasa | pasa |
| una app | 7 toques + contraseña incorrecta #2: no sale | pasa | pasa | pasa |
| una app | 7 toques + contraseña incorrecta #3: no sale | pasa | pasa | pasa |
| una app | 4.º intento durante la espera: ni siquiera pide contraseña | pasa | pasa | pasa |
| una app | y la contraseña correcta no sirve durante la espera | pasa | pasa | pasa |
| una app | intentos fallidos registrados en el servidor | pasa | pasa | pasa |
| una app | pasada la espera, la contraseña correcta saca del quiosco | pasa | pasa | pasa |
| una app | al salir: Ajustes vuelve a abrir (el quiosco ya no lo bloquea) | pasa | pasa | pasa |
| una app | y se queda abierto (nada lo devuelve) | pasa | pasa | pasa |
| una app | al salir: se levantan las restricciones del quiosco | pasa | pasa | pasa |
| una app | salida registrada en el servidor | pasa | pasa | pasa |
| una app | quiosco sin contraseña: aplicado | pasa | pasa | pasa |
| una app | sin contraseña: los 7 toques no ofrecen salida | pasa | pasa | pasa |
| una app | sin contraseña: sigue en el quiosco | pasa | pasa | pasa |
| una app | política antigua (gesto, sin contraseña): el agente se niega a salir y lo dice | pasa | pasa | pasa |
| una app | y lo reporta (kioskExitDenied) | pasa | pasa | pasa |
| una app | salida desde la consola (kiosk.exit) | pasa | pasa | pasa |
| varias apps | kiosk.enter aplicado | pasa | pasa | pasa |
| varias apps | lock task LOCKED | pasa | pasa | — |
| varias apps | restricción no_safe_boot | pasa | pasa | pasa |
| varias apps | restricción no_add_user | pasa | pasa | pasa |
| varias apps | restricción no_user_switch | pasa | pasa | pasa |
| varias apps | restricción no_system_error_dialogs | pasa | pasa | pasa |
| varias apps | modo seguro deshabilitado (safe_boot_disallowed=1) | pasa | pasa | pasa |
| varias apps | Ajustes no está suspendido (lo necesita el arranque de Android) | pasa | pasa | pasa |
| varias apps | Android 7–10 / 16+: el agente tiene acceso de uso (vigilante de primer plano) | pasa | — | pasa |
| varias apps | la app de trabajo no está suspendida | pasa | pasa | pasa |
| varias apps | en primer plano: el inicio del quiosco | pasa | pasa | pasa |
| varias apps | adb am start SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| varias apps | adb am start WIFI_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| varias apps | adb am start APPLICATION_DEVELOPMENT_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| varias apps | adb am start SECURITY_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| varias apps | app permitida → intent SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| varias apps | app permitida → intent WIFI_SETTINGS: no abre Ajustes | pasa | pasa | pasa |
| varias apps | app permitida → intent SETTINGS (tarea nueva): no abre Ajustes | pasa | pasa | pasa |
| varias apps | app permitida → intent WIFI_SETTINGS (tarea nueva): no abre Ajustes | pasa | pasa | pasa |
| varias apps | tras los intentos, la app de trabajo se abre normalmente (no queda Ajustes encima) | pasa | **FALLA** | pasa |
| varias apps | barra de estado | se despliega, sin engranaje | no se despliega, sin engranaje | no se despliega, sin engranaje |
| varias apps | barra de estado / engranaje: no abre Ajustes | pasa | pasa | pasa |
| varias apps | Inicio: sigue en el quiosco | pasa | pasa | pasa |
| varias apps | Recientes | en primer plano: com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivi | en primer plano: android/com.android.internal.app.BlockedAppActivity | en primer plano: com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivi |
| varias apps | Recientes: no muestra Ajustes ni saca del quiosco | pasa | pasa | pasa |
| varias apps | Recientes → abrir tarjeta: sigue en apps permitidas | pasa | pasa | pasa |
| varias apps | tras Recientes, Inicio vuelve al quiosco | pasa | pasa | pasa |
| varias apps | menú de encendido (por defecto) | se abre | se abre | se abre |
| varias apps | tras el menú de encendido sigue en el quiosco | pasa | pasa | pasa |
| varias apps | quiosco con «bloquear botones»: aplicado | pasa | pasa | — |
| varias apps | con «bloquear botones» la política quita GLOBAL_ACTIONS (16) de lock task | esta versión no muestra la máscara de lock task en dumpsys | pasa | — |
| varias apps | con «bloquear botones» el menú de encendido NO se abre | pasa | — | — |
| varias apps | Compartir datos | esta imagen no abre la pantalla de zona Wi‑Fi: com.dallycontrol.agent.debug/com.dallycontr | — | — |
| varias apps | después de Compartir datos, Ajustes sigue sin abrir | pasa | pasa | pasa |
| varias apps | 7 toques + contraseña incorrecta #1: no sale | pasa | pasa | pasa |
| varias apps | 7 toques + contraseña incorrecta #2: no sale | pasa | pasa | pasa |
| varias apps | 7 toques + contraseña incorrecta #3: no sale | pasa | pasa | pasa |
| varias apps | 4.º intento durante la espera: ni siquiera pide contraseña | pasa | pasa | pasa |
| varias apps | y la contraseña correcta no sirve durante la espera | pasa | pasa | pasa |
| varias apps | intentos fallidos registrados en el servidor | pasa | pasa | pasa |
| varias apps | pasada la espera, la contraseña correcta saca del quiosco | pasa | pasa | pasa |
| varias apps | al salir: Ajustes vuelve a abrir (el quiosco ya no lo bloquea) | pasa | pasa | pasa |
| varias apps | y se queda abierto (nada lo devuelve) | pasa | pasa | pasa |
| varias apps | al salir: se levantan las restricciones del quiosco | pasa | pasa | pasa |
| varias apps | salida registrada en el servidor | pasa | pasa | pasa |
| varias apps | quiosco sin contraseña: aplicado | pasa | pasa | pasa |
| varias apps | sin contraseña: los 7 toques no ofrecen salida | pasa | pasa | pasa |
| varias apps | sin contraseña: sigue en el quiosco | pasa | pasa | pasa |
| varias apps | política antigua (gesto, sin contraseña): el agente se niega a salir y lo dice | pasa | pasa | pasa |
| varias apps | y lo reporta (kioskExitDenied) | pasa | pasa | pasa |
| varias apps | salida desde la consola (kiosk.exit) | pasa | pasa | pasa |
| refuerzo | sin acceso de uso: el quiosco se aplica igual | pasa | — | pasa |
| refuerzo | sin acceso de uso: la consola recibe «needsUsageAccess» | pasa | — | pasa |
| refuerzo | sin acceso de uso | app permitida → intent WIFI_SETTINGS: ABRE Ajustes y nada lo devuelve (vía abierta hasta a | — | app permitida → intent WIFI_SETTINGS: ABRE Ajustes y nada lo devuelve (vía abierta hasta a |
| refuerzo | al autorizar el acceso de uso la consola recibe «guard» | pasa | — | pasa |
| refuerzo | con acceso de uso: el vigilante devuelve al quiosco | pasa | — | pasa |
| reinicio | quiosco antes de reiniciar: aplicado | pasa | pasa | pasa |
| reinicio | modo seguro | adb sin root: solo se verifica la restricción (no_safe_boot) | adb sin root: solo se verifica la restricción (no_safe_boot) | adb sin root: solo se verifica la restricción (no_safe_boot) |
| reinicio | tras reiniciar vuelve al quiosco SIN tocar nada | pasa | pasa | pasa |
| reinicio | tras reiniciar: lock task LOCKED | pasa | pasa | — |
| reinicio | tras reiniciar: Ajustes sigue sin abrir | pasa | pasa | pasa |
| una app | Android 11–15: lock task bloquea inicios dentro de la tarea (BLOCK_ACTIVITY_START_IN_TASK) | — | pasa | — |
| una app | menú de encendido con «bloquear botones» | — | no se abre (no concluyente en esta imagen; ver la máscara de lock task) | — |
| varias apps | Android 11–15: lock task bloquea inicios dentro de la tarea (BLOCK_ACTIVITY_START_IN_TASK) | — | pasa | — |
| varias apps | menú de encendido con «bloquear botones» | — | no se abre (no concluyente en esta imagen; ver la máscara de lock task) | — |
| varias apps | Compartir datos abre solo la pantalla de zona Wi‑Fi | — | pasa | pasa |
| varias apps | sola en su tarea (sin el resto de Ajustes detrás) | — | pasa | pasa |
| varias apps | Atrás desde zona Wi‑Fi vuelve al quiosco | — | pasa | pasa |
| refuerzo | la consola recibe el estado del refuerzo: «lockTask» (no necesita acceso de uso) | — | pasa | — |
| una app | Android 16+: quiosco sin lock task (estado NONE) | — | — | pasa |
| una app | Android 16+: la barra de estado no se despliega (por defecto) | — | — | pasa |
| una app | menú de encendido bloqueable | — | — | no en esta versión (Android < 9 o quiosco sin lock task) |
| varias apps | Android 16+: quiosco sin lock task (estado NONE) | — | — | pasa |
| varias apps | Android 16+: la barra de estado no se despliega (por defecto) | — | — | pasa |
| varias apps | menú de encendido bloqueable | — | — | no en esta versión (Android < 9 o quiosco sin lock task) |
| reinicio | tras reiniciar (Android 16+): la barra de estado sigue cerrada | — | — | pasa |
| | **Total** | 99 pasan, 0 fallan | 98 pasan, 1 fallan | 100 pasan, 0 fallan |

Lectura de la tabla:

- **Android 15, «la app de trabajo se abre normalmente» en varias apps: FALLA (1 de 99).** Tras un inicio bloqueado
  por lock task, Android deja su aviso «app no disponible» sobre la tarea de la app; al tocar el ícono en el quiosco
  la app no quedó en primer plano en la prueba (pasó igual en una corrida anterior). No es una vía de escape (el
  equipo sigue en el quiosco y el resto de la corrida pasa), pero queda sin determinar si es el toque del script o
  un tropiezo real de usabilidad: repetir a mano.
- **Sin acceso de uso** (equipo enrolado por QR sin autorizar): en Android 10 y en Android 16 una app permitida
  **abre Ajustes y nada lo devuelve**; en Android 16 además lo abre `adb am start`. La consola recibe
  `needsUsageAccess`. Con acceso de uso el vigilante devuelve el equipo (pasa en ambas).
- Android 10: la imagen del emulador no tiene la pantalla de zona Wi‑Fi, así que «Compartir datos» solo se probó en
  Android 15 y 16.
- Modo seguro: los emuladores no dan root a adb, así que solo se verificó la restricción (`no_safe_boot`,
  `safe_boot_disallowed=1`), no un arranque real en modo seguro.
- Consola y servidor (`scripts/kiosk-console-e2e.mjs`, Chromium sin interfaz): 15/15. El aviso por equipo del
  estado del refuerzo (interruptor del quiosco) no se probó en navegador: solo compila; el evento que lo alimenta sí
  se verificó en las tres versiones.

### Corridas estilo QR (vigilante por servicio de pantalla), 2026-11-27 noche

La tabla de arriba es de la corrida con enrolamiento por cable y vigilante por acceso de uso. Con `QR_STYLE=1` (sin
acceso de uso ni `WRITE_SECURE_SETTINGS`; primero sin el toque y luego con el toque simulado), con el árbol final:

| Versión | Resultado | Sin el toque | Con el toque |
|---|---|---|---|
| Android 10 | 102 pasan, 0 fallan | `needsAuthorization`; pantalla y franja de autorización al instante; `am start` de Ajustes no abre (lock task); una app permitida **abre Ajustes** | `guard`; la app que abre Ajustes vuelve al quiosco; resto de la matriz, salida con contraseña y reinicio pasan |
| Android 16 | 104 pasan, 0 fallan | `needsAuthorization`; pantalla y franja al instante; barra cerrada; **Ajustes abre** por `am start` y desde una app permitida | `guard`; `am start` de Ajustes y la app permitida vuelven al quiosco; salida con contraseña correcta y reinicio pasan |
| Android 15 | no repetida con el árbol final | no necesita toque (`lockTask`) | corrida anterior: 97/5, los 5 de la herramienta (`uiautomator dump` con el servicio de pantalla encendido) |

Consola en Chromium (`scripts/kiosk-console-e2e.mjs --device … --expect-guard …`, Android 16): «Falta autorización en
el teléfono» y su botón (el teléfono abre la pantalla guiada), 19/19; tras el toque, «Quiosco reforzado», 18/18.

#### Corridas finales con «cerrado hasta autorizar» (2026-11-28)

`scripts/kiosk-escape-test.py --qr-style --reboot`, equipo enrolado estilo QR (sin `-g`, sin acceso de uso, sin
`WRITE_SECURE_SETTINGS`), agente final de esta rama:

| Versión | Resultado | Sin el toque | Con el toque |
|---|---|---|---|
| Android 10 (API 29) | **105 pasan, 0 fallan** | `needsAuthorization`; la pantalla guiada sale sola; el inicio muestra «Autorizar ahora» y ninguna app; la app permitida está suspendida y **no abre** (ni ella ni Ajustes); en una sola app, la app no se lanza | `guard`; en una sola app la app se abre sola; la app que abre Ajustes vuelve al quiosco; matriz completa, salida con contraseña y reinicio pasan |
| Android 16 (API 36) | **107 pasan, 0 fallan** | igual; al intentar abrir la app suspendida Android muestra «Blocked by work policy» (solo botón Cerrar); la barra de estado no se despliega | igual |
| Android 15 (API 35) | 4 de 4 (solo la parte QR) | no necesita toque (`lockTask`): no se pide nada y la app permitida no abre Ajustes | — |

Lo que sigue abierto sin el toque y por qué no cuenta como vía de escape para quien usa el equipo: `adb shell am
start` de Ajustes en Android 16 (hace falta depuración USB, que la política puede cerrar), y la pantalla de
Accesibilidad durante los 5 minutos del paso guiado.

En Android 10 el aviso de Android para una app suspendida trae un botón «Más información» (lleva a la ficha del
administrador del dispositivo, no al resto de Ajustes); no se probó navegar desde ahí. Dentro del quiosco no hay
forma de pulsar una app suspendida (no hay íconos y lock task cierra Recientes salvo que la política lo abra).

Soporte remoto dentro del quiosco en Android 16 (`scripts/remote-support-e2e.mjs --kiosk`, 2026-11-28): **pasa** en
los tres quioscos de la prueba (una app, varias apps, y varias apps con Ajustes cerrado, que es cuando el vigilante
está activo): la sesión abre sola, se ve la pantalla, el toque del técnico actúa (0,2–0,7 s hasta ver el efecto), el
chat abre la pantalla de soporte sobre el quiosco y la respuesta llega, la llamada se establece, y el equipo sigue
en quiosco. Lo que antes aparecía como «a medias» era la prueba (buscaba lock task, que Android 16 no usa, y abría
Ajustes, que el quiosco devuelve). Durante una sesión el técnico tampoco puede abrir Ajustes en un equipo en
quiosco: se saca del quiosco desde la consola.

Un fallo real encontrado y corregido en esta ronda: tras reiniciar, un equipo ya autorizado se veía unos segundos como
«sin autorizar», volvía a pedir el toque y dejaba Ajustes permitido hasta 5 minutos (Android 10 lo mostró).

### Solo en teléfono físico

- Android 16 en Samsung (A17 y similares): que el quiosco suave no reinicia el equipo, que la barra de estado queda
  deshabilitada con One UI, y cuánto se alcanza a ver de Ajustes antes de que el vigilante devuelva.
- Si lock task vuelve a ser seguro en Android 16 en esos modelos (hoy desactivado por la caída de `system_server`).
- Android 11–15 con la app de trabajo real: que no dependa de abrir otra app dentro de su tarea (cámara, archivos,
  PDF, navegador), ahora bloqueado; si depende, agregar esa app al quiosco.
- Modo seguro con botones físicos, apagado forzado y menú de encendido de cada fabricante.
- Barra de estado y Recientes con navegación por gestos y con las capas de Samsung/Motorola/Xiaomi.
- Suspensión de apps preinstaladas del fabricante en Android 16 (algunas pueden negarse: el agente lo reporta en
  `kioskLockdownGap`).
- «Cerrado hasta autorizar» en un enrolamiento real por QR: que la pantalla guiada aparece al terminar el asistente
  del fabricante y que el interruptor de Accesibilidad se encuentra en su capa (Samsung lo pone en «Apps
  instaladas»); y que el fabricante no apaga el servicio después (Xiaomi/HyperOS): si lo apaga, el quiosco se cierra
  y pide el toque otra vez, no queda abierto.
- Un equipo que ya estaba en quiosco al recibir el agente nuevo: que sigue trabajando (no se cierra) y muestra la
  franja.

