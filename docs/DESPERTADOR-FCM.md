# Despertador del teléfono: segundo canal (push FCM) y respaldos sin Google

Criterio de la matriz: **MDM-07 — Latencia de comandos remotos** ("ejecutar bloqueo, alarma o mensaje y medir el
tiempo real"). Este documento explica cómo llega una orden al teléfono, cómo activar el despertador por Firebase
Cloud Messaging (FCM) con el proyecto de Firebase de su organización, qué pasa en los equipos sin Google y cómo se
midió.

## 1. Cómo llega una orden

Una orden nunca viaja "empujada" al teléfono: se guarda en la cola del servidor y el agente la recoge en su
siguiente reporte (check-in) por HTTPS, autenticado con el secreto del equipo. Lo que cambia es **quién avisa al
agente de que hay algo que recoger**:

| Canal | Cuándo actúa | Espera habitual |
|---|---|---|
| **Canal en vivo** (WebSocket `/agent/ws`) | El agente lo mantiene abierto siempre. | segundos |
| **Mensaje push (FCM)** — nuevo | El canal en vivo está cerrado, o no hubo reporte 12 s después de avisar por él, o la orden es urgente (bloqueo, alarma, mensaje, anuncio, borrado, cambio de clave, reinicio, sesión remota): en ese caso se envía **además** del aviso por el canal en vivo. | segundos |
| **Sondeo del agente** (respaldo) | Siempre, sin depender de nadie: alarma cada 5 min (2 min durante los 10 min siguientes a actividad en el equipo) y reporte inmediato al encender pantalla, desbloquear, conectar el cargador o recuperar la red. | hasta el periodo configurado |

El mensaje push es **solo una señal** (`wake=commands`): no lleva la orden ni ningún dato del equipo. Quien pudiera
leerlo o falsificarlo solo lograría que el agente haga un reporte, que sigue autenticándose como siempre.

La consola lo muestra:

- **Equipo → "Alcance de órdenes"**: canal por el que quedó alcanzable ahora (en vivo / push / solo sondeo), latencia
  esperada, si está registrado para push, si el agente está exento de optimización de batería y qué provocó su último
  reporte.
- **Al enviar una orden** a un equipo sin canal en vivo, el aviso dice cómo se le despertará ("se le despierta con un
  mensaje push…" o "queda en cola y se ejecuta en su próximo reporte…").
- **Ajustes → "Despertador push (FCM)"**: configurado / incompleto / no configurado, equipos registrados, mensajes
  enviados, respondidos y con error, y los últimos errores.

Si FCM no está configurado **todo funciona como antes**: canal en vivo + sondeo. Nada falla ni cambia de
comportamiento.

## 2. Activar FCM con su proyecto de Firebase

Lo hace una sola vez el dueño de la instalación. No se sube nada al repositorio y el APK no cambia: el agente recibe
los datos públicos del proyecto desde el servidor en cada reporte.

### 2.1 Crear el proyecto y registrar la app

1. Entre a <https://console.firebase.google.com> con la cuenta de Google de la organización → **Agregar proyecto**
   (por ejemplo `dallycontrol-mdm`). Google Analytics no hace falta: desactívelo.
2. En el proyecto: **Agregar app → Android**.
   - **Nombre del paquete de Android**: `com.dallycontrol.agent` (exactamente así; es el paquete del agente de
     producción).
   - Apodo y SHA-1: opcionales; déjelos vacíos.
   - **Registrar app** y **Descargar `google-services.json`**. Los pasos siguientes del asistente ("Agregar el SDK")
     se omiten: el agente ya lo trae.
   - Solo si también prueba con compilaciones de depuración, agregue otra app Android con el paquete
     `com.dallycontrol.agent.debug` y vuelva a descargar el `google-services.json` (el archivo incluye todas las apps).
3. **Configuración del proyecto (engranaje) → Cloud Messaging**: compruebe que **Firebase Cloud Messaging API (V1)**
   aparece **Habilitada**. Si no, ábrala desde ahí en Google Cloud y pulse *Habilitar*.

### 2.2 Crear la credencial del servidor (cuenta de servicio)

1. **Configuración del proyecto → Cuentas de servicio → Generar nueva clave privada → Generar clave**. Se descarga un
   archivo `…-firebase-adminsdk-….json`. **Es un secreto**: quien lo tenga puede enviar mensajes a sus equipos.
   Guárdelo como guarda la contraseña de la base de datos; no lo envíe por correo ni lo suba a ningún repositorio.
2. (Recomendado, mínimo privilegio) En Google Cloud → **IAM y administración → Cuentas de servicio → Crear cuenta de
   servicio**, asígnele solo el rol **Administrador de la API de Firebase Cloud Messaging**
   (`roles/firebasecloudmessaging.admin`), y genere la clave JSON de **esa** cuenta en lugar de la de *adminsdk*,
   que tiene más permisos de los necesarios.

### 2.3 Cargar ambos archivos en el servidor

En el servidor, en el archivo `.env` de la instalación (el mismo donde está `DB_PASSWORD`; permisos `600`):

```bash
cd /ruta/de/la/instalacion
printf 'FCM_SERVICE_ACCOUNT_JSON=%s\n' "$(base64 < cuenta-de-servicio.json | tr -d '\n')" >> .env
printf 'FCM_GOOGLE_SERVICES_JSON=%s\n' "$(base64 < google-services.json   | tr -d '\n')" >> .env
docker compose up -d server          # reinicia solo el servidor con las variables nuevas
shred -u cuenta-de-servicio.json     # no deje copias sueltas de la clave
```

Alternativa con archivos (por ejemplo secretos de Docker): monte los archivos en el contenedor `server` y defina
`FCM_SERVICE_ACCOUNT_FILE=/run/secrets/fcm.json` y `FCM_GOOGLE_SERVICES_FILE=/run/secrets/google-services.json`.
Si no quiere cargar el `google-services.json`, sirven sus tres valores sueltos: `FCM_API_KEY` (`current_key`),
`FCM_APP_ID` (`mobilesdk_app_id`; acepta `paquete=id,paquete=id`) y `FCM_SENDER_ID` (`project_number`).

| Variable | Qué es | ¿Secreto? |
|---|---|---|
| `FCM_SERVICE_ACCOUNT_JSON` / `_FILE` | Clave de la cuenta de servicio (JSON o base64 / ruta). Con ella el servidor firma sus envíos. | **Sí** |
| `FCM_GOOGLE_SERVICES_JSON` / `_FILE` | `google-services.json` del proyecto. De ahí salen los datos públicos que usan los agentes para registrarse. | No (va dentro de cualquier app) |
| `FCM_API_KEY`, `FCM_APP_ID`, `FCM_SENDER_ID` | Lo mismo, valor por valor. | No |
| `FCM_ENDPOINT`, `FCM_TOKEN_URI` | Solo para pruebas con un servicio simulado. No los defina en producción. | — |

### 2.4 Comprobar

1. Consola → **Ajustes → Despertador push (FCM)** debe decir **Configurado** y mostrar el id del proyecto. Si dice
   **Incompleto**, el texto indica qué falta (JSON inválido, falta el `google-services.json`…).
2. Los agentes se registran solos en su siguiente reporte (versión del agente que incluye este canal; los anteriores
   siguen funcionando igual que hoy). "Equipos registrados" empieza a subir en unos minutos.
3. Abra un equipo → "Alcance de órdenes" → **Mensajes push: Registrado**.
4. Prueba real: apague la pantalla del teléfono, espere unos minutos, envíe **Bloquear** y mire en el historial de
   órdenes el tiempo "creada → completada". En "Alcance de órdenes" verá "Último push … respondió en X s".
5. Prueba de campo desde un computador con el teléfono por USB (no necesita credenciales; simula la llegada del
   mensaje y ejecuta el mismo código que el mensaje real):
   `adb shell am broadcast -a com.dallycontrol.agent.PUSH_TEST -n com.dallycontrol.agent/com.dallycontrol.agent.wake.PushTestReceiver`

### 2.5 (Opcional) Restringir la clave de API

La `current_key` del `google-services.json` no es un secreto, pero puede restringirse en Google Cloud → **APIs y
servicios → Credenciales**: restricción de aplicación *Apps para Android* con el paquete `com.dallycontrol.agent` y
la huella SHA-1 del certificado con que se firma el agente, y restricción de API a *Firebase Installations API* y
*FCM Registration API*. Si después de restringir los equipos pasan a "No se pudo registrar", la huella no coincide.

### 2.6 Retirar o cambiar el proyecto

Quite las variables y reinicie el servidor: los agentes borran su registro en el siguiente reporte y todo vuelve a
canal en vivo + sondeo. Si cambia de proyecto, los agentes se registran en el nuevo solos. Si sospecha que la clave
de la cuenta de servicio se filtró: en Google Cloud elimine esa clave, genere otra y cárguela.

## 3. Qué hace exactamente el servidor

- Envía por **FCM HTTP v1** (`POST /v1/projects/<proyecto>/messages:send`) un mensaje de datos de **prioridad alta**
  con `collapse_key=wake` (varios avisos a un equipo fuera de cobertura se funden en uno) y vigencia de 4 h (24 h en
  órdenes urgentes), autenticado con un token OAuth2 obtenido con la cuenta de servicio (se renueva solo).
- **Antirrebote**: una ráfaga de órdenes para el mismo equipo es un solo push (30 s); en órdenes urgentes, 3 s.
- **Reintentos**: errores de red, `429` y `5xx` se reintentan hasta 4 veces con espera creciente (respeta
  `Retry-After`). Un `401` renueva el token una vez. Un token que Google ya no conoce (`UNREGISTERED`) se borra y el
  equipo vuelve a registrarse.
- **Insistencia en órdenes urgentes**: si tras el push no hay reporte del equipo, se repite a los 30 s y a los 2 min.
  Después la orden sigue en cola (la cola no caduca ni pierde órdenes; ver `scripts/command-reliability-e2e.py`).
- **Canal en vivo "zombi"**: si se avisó por el canal en vivo y en 12 s no hubo reporte, se envía el push.
- El token de cada equipo se guarda en la tabla `device_push` y **nunca** se devuelve a la consola.

## 4. Límites reales de FCM (para no prometer de más)

- **Equipos sin servicios de Google** (Huawei recientes, AOSP, algunos equipos industriales): no hay push; la consola
  muestra "El equipo no tiene servicios de Google" y el equipo queda en canal en vivo + sondeo.
- **App detenida a la fuerza**: Android no entrega mensajes a una app en estado "detenida". Un Device Owner no puede
  ser detenido desde Ajustes por el usuario (el botón está deshabilitado) y `am force-stop` es ignorado por Android
  (medido, ver §7); lo que sí ocurre es que el sistema o el gestor de batería del fabricante **maten el proceso**:
  en ese caso el push sí llega y lo revive.
- **Cuota de prioridad alta**: Google puede bajar la prioridad a una app que recibe muchos mensajes de prioridad alta
  sin mostrar nada al usuario. Por eso el push se usa solo cuando hace falta (canal en vivo cerrado u orden urgente),
  no en cada orden.
- **Fabricantes que bloquean el arranque en segundo plano** (Xiaomi/HyperOS, Oppo, Vivo, Huawei): pueden retener
  también los push de apps sin "inicio automático". Ver §6.
- El emulador no puede probar la entrega real de Google sin credenciales; ver §7 para lo que se simuló y lo que queda
  por confirmar en un teléfono físico.

## 5. Respaldo que no depende de Google

Incluido en el agente y activo siempre:

- **Reporte inmediato** al encender la pantalla, desbloquear, conectar el cargador o recuperar/cambiar de red (como
  máximo uno cada 20 s).
- **Alarma de respaldo** con `setExactAndAllowWhileIdle` (alarma exacta permitida en reposo). El permiso de alarmas
  exactas se obtiene sin diálogo: hasta Android 12L se concede al instalar (`SCHEDULE_EXACT_ALARM`) y desde Android 13
  con `USE_EXACT_ALARM`. Si en algún equipo no estuviera permitido, se usa `setAndAllowWhileIdle` y la consola lo
  indica ("Alarmas exactas: no permitidas").
- **Sondeo rápido**: durante 10 min tras actividad en el equipo la alarma pasa a cada 2 min. (El agente no puede saber
  que hay una orden urgente pendiente si no tiene enlace; la actividad en el equipo es la mejor señal disponible y es
  cuando el reporte cuesta menos batería.)
- **Configurable por política** (Políticas → editar → "Sondeo de respaldo"): minutos del sondeo normal (1–15),
  minutos del sondeo rápido (1–5), duración del sondeo rápido (0–60, 0 = apagado) y "Alarma garantizada en reposo
  profundo", que usa una alarma de tipo despertador (`setAlarmClock`): es la única que Android **nunca** aplaza, a
  cambio de mostrar el icono de alarma en la barra de estado. Úsela solo en equipos críticos sin Google.
- En **reposo profundo (Doze)** Android limita las alarmas "en reposo" de cada app a aproximadamente una cada 9
  minutos (salvo la de tipo despertador). Con el agente **exento de optimización de batería** el canal en vivo sigue
  funcionando en Doze y este límite deja de importar.

## 6. Exención de batería y restricciones de segundo plano

Qué puede hacer un Device Owner **sin diálogo** y qué no (verificado en emulador API 30 y 35):

| Restricción de Android | ¿El agente queda exento solo? |
|---|---|
| Iniciar servicio en primer plano desde segundo plano (Android 12+) | **Sí**: Android exime al Device Owner. |
| "Uso de batería: Restringido" / cubos de App Standby | **Sí**: un administrador de dispositivo activo queda exento. |
| Ser detenido a la fuerza por el usuario | **Sí**: el botón está deshabilitado y `am force-stop` se ignora. |
| Lista de **optimización de batería (Doze)** | **No hay API pública** para que un Device Owner se agregue solo. Vías: (a) el enrolamiento por cable/ADB (`scripts/adb-enroll.sh`) lo agrega sin diálogo (`cmd deviceidle whitelist +paquete`); (b) en el enrolamiento por QR el agente abre una sola vez el diálogo del sistema "¿Permitir que la app se ejecute siempre en segundo plano?" → **Permitir**; (c) en compilaciones firmadas por el fabricante, `setApplicationExemptions` (Android 14+), que el agente ya intenta. |

La consola muestra por equipo "Exento de batería: Sí/No" para localizar los que faltan.

Además de lo anterior, cada fabricante tiene su propio gestor de batería. En equipos **no** exentos o que la consola
muestre con cortes repetidos por "proceso detenido por el sistema", ajuste una vez por equipo (o por plantilla de
aprovisionamiento del fabricante):

| Fabricante | Ajuste |
|---|---|
| **Xiaomi / Redmi / POCO (MIUI, HyperOS)** | Ajustes → Apps → Administrar apps → DallyControl → **Inicio automático: activado**; **Ahorro de batería: Sin restricciones**. En Recientes, mantener pulsada la tarjeta del agente → **candado**. Ajustes → Batería → desactivar "Borrar memoria al bloquear" si existe. |
| **Samsung (One UI)** | Ajustes → Batería → Límites de uso en segundo plano → **Aplicaciones que nunca se suspenden** → agregar DallyControl. Ajustes → Apps → DallyControl → Batería → **No restringida**. Con Knox Service Plugin: `Battery optimization allowlist`. |
| **Huawei / Honor (EMUI, MagicOS)** | Ajustes → Batería → **Inicio de aplicaciones** → DallyControl → gestionar manualmente → activar las tres opciones (inicio automático, inicio secundario, ejecución en segundo plano). Sin servicios de Google en modelos recientes: no hay push. |
| **Oppo / Realme / OnePlus (ColorOS, OxygenOS)** | Ajustes → Batería → Más ajustes → **Optimizar uso de batería** → DallyControl → No optimizar; Apps → DallyControl → Uso de batería → **Permitir actividad en segundo plano** e **Inicio automático**. |
| **Vivo / iQOO (Funtouch, OriginOS)** | Ajustes → Batería → **Consumo alto en segundo plano** → permitir DallyControl; i Manager → Gestor de apps → **Inicio automático**. |
| **Motorola, Nokia, Pixel, Zebra, Honeywell (Android "limpio")** | Ajustes → Apps → DallyControl → Batería → **Sin restricciones**. No hay gestor adicional. |

## 7. Cómo se probó sin credenciales de Firebase

No se dispone aquí del proyecto de Firebase del dueño, así que la parte de Google se simuló de punta a punta:

- **Servidor** — pruebas unitarias con un servicio FCM v1 falso (`server/src/test/java/com/hmdm/notification/push/`):
  cuándo se envía (equipo sin canal en vivo; orden urgente por ambos canales; canal en vivo que no responde), a qué
  token, prioridad alta y sin datos de la orden, firma de la cuenta de servicio verificada, reintentos (5xx, 429 con
  `Retry-After`, red caída, 401), abandono tras 4 intentos, token desconocido borrado, antirrebote, insistencia en
  urgentes y su cancelación al reportar el equipo, y que sin proyecto todo es un no-op.
- **Agente** — pruebas unitarias del horario de sondeo y del bloque de despertar del check-in (compatibilidad con
  servidores anteriores y posteriores).
- **Emulador con Google APIs** — `scripts/wake-latency-e2e.py`:
  1. `scripts/wake-latency-e2e.py env --port 8126 > fcm-sim.env` genera una cuenta de servicio desechable y apunta
     el servidor al simulador; levante el stack con esas variables.
  2. `scripts/wake-latency-e2e.py sim --port 8126 --upstream 127.0.0.1:<puerto de la consola>` es, a la vez, el FCM
     falso, la "entrega" del mensaje al teléfono (lista blanca temporal de energía + entrega a la app, que es lo que
     hacen los servicios de Google con un mensaje de prioridad alta) y una compuerta que corta el WebSocket dejando
     pasar los reportes HTTPS ("canal en vivo caído pero con red").
  3. Enrole el emulador **a través de la compuerta**:
     `scripts/adb-enroll.sh --server http://10.0.2.2:8126 --api-url http://localhost:<puerto> --apk app-debug.apk --debug-build --admin-user admin`
  4. `scripts/wake-latency-e2e.py run --api http://localhost:<puerto> --sim http://127.0.0.1:8126 --serial emulator-XXXX --device-id <número> --md-out resultados.md`
- **Consola** — `scripts/wake-console-e2e.mjs` (Chromium sin interfaz): Ajustes, tarjeta "Alcance de órdenes", aviso al
  enviar una orden sin canal en vivo, y "Sondeo de respaldo" de la política.

Resultados: ver §8.

**Lo que solo se confirma con el proyecto real y un teléfono físico**: que Google emite el token con los datos del
proyecto (registro real), el tiempo de tránsito real de Google (habitualmente menos de 1 s con el equipo en línea; el
simulador usa 0,3 s), la entrega en Doze real tras horas de reposo, y el comportamiento de los gestores de batería de
cada fabricante (§6).

## 8. Mediciones

<!-- MEDICIONES -->

## 9. Tamaño del APK y por qué esta integración

Medido con `./gradlew :app:assembleRelease` (R8 + reducción de recursos, sin firmar), misma máquina y misma base:

| Compilación | Tamaño del APK |
|---|---|
| Antes (sin push) | 1 810 028 bytes (1,73 MiB) |
| Con `firebase-messaging` 24.1.0 e inicialización manual | 2 041 556 bytes (1,95 MiB) |
| **Diferencia** | **+231 528 bytes (+12,8 %)** |

Se eligió la biblioteca oficial `firebase-messaging` **sin** el plugin `google-services` y **sin**
`google-services.json` en la compilación: `FirebaseApp` se inicializa a mano (`PushRegistrar`) con los valores
públicos del proyecto que el servidor entrega en cada reporte. Razones:

- Un solo APK sirve para cualquier instalación y para instalaciones sin FCM; compilar no exige ningún archivo de
  Firebase ni cuenta de Google (CI y forks siguen compilando igual).
- El costo real es de 0,22 MiB, no los varios MB que suele suponerse: R8 elimina lo que no se usa y no se incluyen
  Analytics ni el BoM.
- La alternativa "sin biblioteca" (hablar a mano con el registro interno de los servicios de Google) ahorraría esos
  0,22 MiB pero depende de un protocolo no documentado que Google cambia sin aviso (ya lo hizo al pasar de GCM a
  Firebase Installations); para un canal cuyo único fin es la fiabilidad no compensa.
- En equipos **sin** servicios de Google la biblioteca queda inerte: el agente detecta su ausencia, no intenta
  registrarse y reporta "sin servicios de Google". No se exige Google ni al compilar ni al ejecutar.
- Se quitó del manifiesto el proveedor de arranque de Firebase (`FirebaseInitProvider`), que solo sirve para leer un
  `google-services.json` empaquetado: no hay costo de arranque cuando no hay proyecto.

Versión fijada en 24.1.0: es la última línea compatible con la cadena de compilación actual del agente (AGP 8.6,
Kotlin 2.0.20, minSdk 23). Subirla es una tarea de mantenimiento aparte, junto con AGP/Kotlin.

## 10. SMS de despertador (diseño; no activo)

Para equipos sin Google **y** sin datos móviles activos (o con el proceso retenido por el fabricante) queda diseñado
un tercer canal por SMS. No hay proveedor de SMS contratado, así que solo existen la interfaz y un simulador.

- **Servidor**: `com.hmdm.notification.push.SmsWakeProvider` — `send(teléfono, texto)`; el texto es
  `DCWAKE <número de equipo>`, sin órdenes ni secretos. Implementación disponible: `Simulator`
  (`SMS_WAKE_PROVIDER=simulator`), que registra lo que se enviaría; aparece en Ajustes como "SMS de despertador:
  simulador". Un proveedor real (Twilio, Infobip, un SMPP del operador) implementa esa misma interfaz con sus
  credenciales por variables de entorno.
- **Número de destino**: el del SIM que el equipo ya reporta en su telemetría, o el "teléfono esperado" cargado en
  el enrolamiento.
- **Cuándo se enviaría**: orden urgente + sin canal en vivo + sin token push (o push sin respuesta tras las dos
  insistencias), con un máximo por equipo y día para acotar el costo.
- **Agente**: necesita el permiso `RECEIVE_SMS` y un receptor de `SMS_RECEIVED` que, si el texto empieza por
  `DCWAKE` y el número coincide, haga un reporte. Hoy el agente **no** declara permisos de SMS a propósito (los
  permisos de SMS disparan las heurísticas de Play Protect en un agente instalado fuera de Play); activarlo es una
  decisión del dueño, y como Device Owner el permiso se concede sin diálogo.
- **Limitaciones**: costo por mensaje, SIM solo de datos sin SMS, y que el SMS despierta al equipo pero la orden
  sigue necesitando datos para ejecutarse (sirve para "despertar", no para "sin cobertura de datos").
