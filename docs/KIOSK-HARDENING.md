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
  siguiente vez que se abre la app de trabajo arranca limpia, para que no quede Ajustes encima. Sin acceso de uso
  el agente lo avisa en el historial (`kioskLockdownGap: settingsNotGuarded`).

**Por qué no se suspende ni se oculta la app Ajustes.** En Ajustes vive la pantalla de inicio que Android usa
mientras arranca (FallbackHome). Probado en emulador: con Ajustes *oculto* (`setApplicationHidden`) el teléfono no
termina de encender (Android 15); con Ajustes *suspendido* enciende en un aviso «acción no permitida» en vez del
quiosco y no entra al quiosco hasta que alguien lo toca (Android 10). Además Android 14+ ya no deja suspenderlo. Por
eso el quiosco nunca toca esa app: solo suspende lo que lleva a ella y las apps que no son de trabajo.

El agente vuelve a aplicar todo esto en cada ciclo (cada check-in, al instalarse una app y al arrancar), así que una
app instalada después o una restricción quitada por fuera se vuelve a cerrar sola. Al salir del quiosco (con la
contraseña o desde la consola) se levanta únicamente lo que puso el quiosco.

### El vigilante y el «acceso de uso» (equipos enrolados por QR)

El vigilante de primer plano (Android 7–10 y 16+) necesita que DallyControl tenga el permiso especial «Acceso de uso».
Android no deja que un Device Owner se lo dé a sí mismo (no es un permiso de ejecución: `setPermissionGrantState` no
aplica, y cambiar el *app-op* exige firma de sistema). Lo dan:

- el enrolamiento por cable (`scripts/adb-enroll.sh`), automáticamente; o
- **un toque en el teléfono**: Ajustes → Acceso de uso → DallyControl. La consola lo pide a distancia: ficha del
  equipo → interruptor del quiosco → **«Pedir autorización en el teléfono»** (abre esa pantalla aunque el equipo esté
  en quiosco; al activarlo, el equipo vuelve solo al quiosco).

El agente reporta el estado real y la consola lo muestra por equipo, debajo del interruptor del quiosco (evento
`kioskGuard`):

| Estado | Qué significa |
|---|---|
| «Quiosco reforzado: Android bloquea los Ajustes del sistema» (`lockTask`) | Android 11–15. No depende del acceso de uso. |
| «Quiosco reforzado: si se abren los Ajustes, el equipo vuelve solo al quiosco» (`guard`) | Android 7–10 / 16+ con acceso de uso. |
| «Refuerzo incompleto: falta el acceso de uso» (`needsUsageAccess`) | Android 7–10 / 16+ **sin** acceso de uso (enrolado por QR y aún sin autorizar). Queda abierta una vía: una app permitida que enlace a Ajustes los abre y nada los cierra. En Android 7–10 el resto (barra, Recientes, `am start`) sigue cerrado por lock task; en Android 16+ la barra de estado está deshabilitada por defecto y las demás apps suspendidas. |

Recomendación para flotas enroladas por QR: Android 11–15 no requiere nada; en Android 7–10 y 16+ autorizar el acceso
de uso al entregar el equipo (un toque) y vigilar en la consola que ninguno quede en «Refuerzo incompleto».

### Por qué Android 16 es distinto

En Android 16 el bloqueo de tareas hace caer `system_server`: un `NullPointerException` dentro de
`LockTaskController` cuando el sistema anima la transición de una tarea que está en la lista de lock task (traza en
`DisplayContent.executeAppTransition`; confirmado en Samsung A17 con Android 16). Al caer `system_server` el teléfono
se reinicia, vuelve al quiosco, abre la app y cae de nuevo: un ciclo de reinicios del que no se sale a distancia. No
se puede atrapar desde una app y ocurre con solo tener paquetes en la lista de lock task (no hace falta llamar a
`startLockTask`), así que desde el agente 0.9.0 en Android 16+ **no se usa lock task** («quiosco suave»): la lista se
deja vacía.

`LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` no lo arregla: es una opción *de* lock task, y el fallo está en lock
task mismo. En el emulador de Android 16 (imagen de Google, no Samsung) no se reproduce la caída, de modo que el
emulador no sirve para decidir si ya es seguro; reactivarlo exige probar en los modelos reales (ver «Solo en teléfono
físico»). Mientras tanto Android 16+ queda así, **sin presentarlo como bloqueo absoluto**:

- Cerrado de verdad: apps que no son de trabajo (suspendidas), barra de estado y ajustes rápidos (deshabilitados por
  defecto), Inicio (vuelve al quiosco), modo seguro, usuarios, salida sin contraseña.
- **Mitigado, no bloqueado:** los Ajustes del sistema. Si una app permitida los abre por intent (o el usuario toca el
  engranaje con «Notificaciones = Sí»), se ven durante menos de un segundo y el vigilante devuelve el equipo al
  quiosco. Sin acceso de uso no hay vigilante y esa vía queda abierta.
- No se puede cerrar: el menú de apagado (apagar/reiniciar; al encender vuelve al quiosco) y los botones
  Inicio/Recientes (Recientes solo puede reabrir apps permitidas; una tarjeta de Ajustes se devuelve igual).

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

RESULTADOS_EMULADOR
