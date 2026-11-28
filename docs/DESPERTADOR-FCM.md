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
  ser detenido desde Ajustes por el usuario y `am force-stop` es ignorado por Android (medido, ver §8); lo que sí ocurre es que el sistema o el gestor de batería del fabricante **maten el proceso**:
  en ese caso el push sí llega y lo revive.
- **Cuota de prioridad alta**: Google puede bajar la prioridad a una app que recibe muchos mensajes de prioridad alta
  sin mostrar nada al usuario. Por eso el push se usa solo cuando hace falta (canal en vivo cerrado u orden urgente),
  no en cada orden.
- **Fabricantes que bloquean el arranque en segundo plano** (Xiaomi/HyperOS, Oppo, Vivo, Huawei): pueden retener
  también los push de apps sin "inicio automático". Ver §6.
- Sin credenciales no se puede probar la entrega real de Google; ver §7 para lo que se simuló y lo que queda por
  confirmar en un teléfono físico.

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

Qué obtiene el agente sin diálogo y qué no. Lo marcado "observado" se comprobó en los emuladores API 30, 35 y 36 de las
mediciones; lo demás es el comportamiento documentado de Android y no se verificó aquí.

| Restricción de Android | Situación del agente |
|---|---|
| Ser detenido a la fuerza | **Observado**: `am force-stop` es ignorado (Device Owner); el proceso siguió vivo en API 30, 35 y 36. |
| Cubo de App Standby | **Observado**: `am get-standby-bucket` = 5 (exento) en ambos. |
| Iniciar el servicio en primer plano desde segundo plano (Android 12+) | **Observado** en API 35 y 36: 0 rechazos al reiniciarlo desde el push y desde la alarma con el proceso recién muerto. |
| Alarmas exactas | **Observado**: permitidas sin diálogo (API 30 siempre; API 35 y 36 por `USE_EXACT_ALARM`, `exactAllowReason=policy_permission`). |
| "Uso de batería: Restringido" (segundo plano) | No verificado de forma aislada: `scripts/adb-enroll.sh` ya deja `RUN_ANY_IN_BACKGROUND=allow`. El agente lo reporta (`bgRestricted`) y la consola lo avisa. |
| Lista de **optimización de batería (Doze)** | **No hay API pública** para que un Device Owner se agregue solo. Vías: (a) el enrolamiento por cable/ADB (`scripts/adb-enroll.sh`) lo agrega sin diálogo (`cmd deviceidle whitelist +paquete`; observado: el agente aparece en la lista); (b) en el enrolamiento por QR el agente abre una vez el diálogo del sistema "¿Permitir que la app se ejecute siempre en segundo plano?" → **Permitir**; (c) en compilaciones firmadas por el fabricante, `setApplicationExemptions` (Android 14+), que el agente ya intenta. |

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
     hacen los servicios de Google con un mensaje de prioridad alta; con `--via c2dm` y `adb root` se entrega por el
     receptor real de Firebase, es decir, por todo el camino real dentro del teléfono) y una compuerta que corta el WebSocket dejando
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

Corridas del 27-nov-2026 con `scripts/wake-latency-e2e.py`: agente de depuración 0.9.7 con este canal, stack local
construido del mismo árbol, emuladores con Google APIs recién creados y enrolados por `scripts/adb-enroll.sh`, push
simulado entregado por el receptor real de Firebase (`--via c2dm`) con 0,3 s de tránsito simulado. Tiempo = "creada
en el servidor → ejecutada en el teléfono" según las marcas del propio servidor (lo que muestra el historial de
órdenes de la consola). 10 repeticiones por orden; en las condiciones sin despertador, 3 repeticiones de bloqueo
(cada una tarda minutos). La columna "Carga" es la carga a 1 minuto de la máquina de pruebas durante esa fila.

### Android 16 (API 36) — carga 3–14

| Condición | Orden | n | Sin ejecutar | p50 (s) | p90 (s) | máx (s) | Canal anunciado | Carga (1 min) |
|---|---|---|---|---|---|---|---|---|
| Canal en vivo arriba | Bloqueo | 10 | 0 | 0,7 | 1,1 | 1,4 | en vivo | 11,7 |
| Canal en vivo arriba | Alarma | 10 | 0 | 0,2 | 0,6 | 1,2 | en vivo | 13,5 |
| Canal en vivo arriba | Mensaje | 10 | 0 | 0,2 | 0,3 | 0,3 | en vivo | 13,9 |
| Canal caído + despertador push | Bloqueo | 10 | 0 | 1,5 | 1,8 | 1,9 | push | 13,0 |
| Canal caído + despertador push | Alarma | 10 | 0 | 1,6 | 1,8 | 1,9 | push | 10,0 |
| Canal caído + despertador push | Mensaje | 10 | 0 | 1,6 | 1,8 | 1,8 | push | 7,0 |
| Doze forzado + despertador push (agente **sin** exención de batería) | Bloqueo | 10 | 0 | 1,6 | 1,8 | 1,9 | push | 5,3 |
| Doze forzado + despertador push (agente **sin** exención de batería) | Alarma | 10 | 0 | 1,6 | 1,8 | 1,8 | push | 4,3 |
| Doze forzado + despertador push (agente **sin** exención de batería) | Mensaje | 10 | 0 | 1,4 | 1,7 | 1,9 | push | 3,5 |
| Proceso del agente muerto (`kill -9`) + despertador push | Bloqueo | 10 | 0 | 2,0 | 2,2 | 2,3 | push | 4,6 |
| Proceso del agente muerto (`kill -9`) + despertador push | Alarma | 10 | 0 | 1,5 | 2,0 | 2,0 | push | 5,3 |
| Proceso del agente muerto (`kill -9`) + despertador push | Mensaje | 10 | 0 | 2,0 | 2,4 | 2,5 | push | 8,6 |
| Proceso del agente muerto (`kill -9`), sin despertador (reinicio de Android) | Bloqueo | 3 | 0 | 2,5 | 2,9 | 2,9 | — | 6,5 |

### Android 11 (API 30) — carga 1–11

| Condición | Orden | n | Sin ejecutar | p50 (s) | p90 (s) | máx (s) | Canal anunciado | Carga (1 min) |
|---|---|---|---|---|---|---|---|---|
| Canal en vivo arriba | Bloqueo | 10 | 0 | 1,0 | 1,5 | 1,5 | en vivo | 4,2 |
| Canal en vivo arriba | Alarma | 10 | 0 | 1,1 | 1,5 | 1,5 | en vivo | 3,4 |
| Canal en vivo arriba | Mensaje | 10 | 0 | 1,5 | 1,5 | 1,5 | en vivo | 2,8 |
| Canal caído + despertador push | Bloqueo | 10 | 0 | 1,2 | 1,4 | 1,4 | push | 2,4 |
| Canal caído + despertador push | Alarma | 10 | 0 | 1,4 | 1,7 | 1,7 | push | 2,1 |
| Canal caído + despertador push | Mensaje | 10 | 0 | 1,6 | 1,8 | 1,8 | push | 2,0 |
| Doze forzado + despertador push (agente **sin** exención de batería) | Bloqueo | 10 | 0 | 1,3 | 1,7 | 7,8 | push | 2,3 |
| Doze forzado + despertador push (agente **sin** exención de batería) | Alarma | 10 | 0 | 1,5 | 1,7 | 1,8 | push | 1,9 |
| Doze forzado + despertador push (agente **sin** exención de batería) | Mensaje | 10 | 0 | 1,6 | 1,7 | 1,8 | push | 1,4 |
| Proceso del agente muerto (`kill -9`) + despertador push | Bloqueo | 10 | 0 | 1,7 | 2,0 | 2,0 | push | 1,8 |
| Proceso del agente muerto (`kill -9`) + despertador push | Alarma | 10 | 0 | 1,5 | 1,8 | 2,0 | push | 2,3 |
| Proceso del agente muerto (`kill -9`) + despertador push | Mensaje | 10 | 0 | 1,3 | 1,6 | 1,6 | push | 2,5 |
| Proceso del agente muerto (`kill -9`), sin despertador (reinicio de Android) | Bloqueo | 3 | 0 | 2,5 | 2,5 | 2,5 | — | 2,0 |
| Canal caído, sin despertador (solo sondeo del agente) | Bloqueo | 3 | 0 | 120,6 | 120,8 | 120,8 | — | 2,0 |
| Doze forzado, sin despertador (agente **sin** exención de batería) | Bloqueo | 3 | 0 | 118,3 | 120,2 | 120,2 | — | 10,8 |

**252 órdenes medidas en estas dos corridas, 0 sin ejecutar.**

### Lectura de los resultados

- **Canal en vivo arriba**: 0,2–1,5 s de mediana, p90 ≤ 1,5 s.
- **Con el despertador push, canal caído, Doze o proceso muerto dejan de importar**: 1,2–2,0 s de mediana y p90 ≤ 2,4 s
  en las tres órdenes y en las dos versiones, incluso con el agente **sin** exención de batería. De ese tiempo, 0,3 s
  son el tránsito simulado de Google y ~1 s la entrega por `adb`; con Google real cuente con su tránsito (habitualmente
  menos de 1 s con el equipo en línea).
- **Sin despertador** (equipo sin Google o servidor sin proyecto): el canal caído cuesta lo que tarde el siguiente
  sondeo — 2 min aquí, porque el corte del canal cuenta como actividad y activa el sondeo rápido; 5 min (o lo que diga
  la política) en reposo sin actividad.
- **Doze forzado sin despertador dio ~2 min**, igual que fuera de Doze: la alarma exacta "en reposo" sonó a tiempo.
  Ojo: Android solo garantiza una de esas alarmas cada ~9 min por app en reposo profundo real, y el Doze forzado del
  emulador no suspende el procesador; en un teléfono real sin exención de batería y sin push cuente con hasta ~9 min,
  salvo que active la "alarma garantizada" de la política.
- **Proceso muerto**: `am force-stop` sobre el agente es **ignorado por Android** (es Device Owner; el proceso siguió
  vivo en API 30, 35 y 36). Tras `kill -9`, Android reinicia el servicio solo (2,5 s en la primera muerte), pero cada
  muerte seguida multiplica por 4 esa espera (en el registro: 4 s, 16 s, 64 s… hasta más de una hora tras 6–7 muertes
  en un minuto); el push lo revive siempre en ~2 s, y sin push lo hace la alarma de respaldo.
- La consola anunció el canal correcto en todas las órdenes (en vivo / push).
- **Órdenes no urgentes solo por el canal en vivo** (sin push; `device.diagnose`, API 30, carga 27): 0,8–1,3 s hasta la
  entrega.
- Consola (`scripts/wake-console-e2e.mjs`, Chromium sin interfaz), sobre el árbol final: **13/13 en Android 11**;
  12/13 en Android 16 (el paso 13, "el teléfono adoptó el nuevo sondeo", esperaba un solo reporte y hacen falta dos;
  se corrigió el script y pasó en la corrida siguiente, la de Android 11; no se repitió en Android 16).

### Corridas anteriores, bajo carga alta (misma fecha)

Antes de las corridas de arriba se midió con la máquina ejecutando a la vez las compilaciones y emuladores de otros
trece desarrollos (carga 20–37). Sirven como peor caso de un servidor/equipo saturado, no como latencia típica:

| Versión | Condición | p50 (s) | p90 (s) | Sin ejecutar |
|---|---|---|---|---|
| Android 11 | Canal en vivo arriba | 1,7–2,6 | 2,6–5,4 | 0 de 30 |
| Android 11 | Canal caído + push | 2,0–2,3 | 2,6–4,1 | 0 de 30 |
| Android 11 | Doze + push (sin exención) | 2,4–6,0 | 3,1–9,3 | 0 de 30 |
| Android 11 | Proceso muerto + push | 1,8–2,6 | 3,3–4,0 | 0 de 30 |
| Android 15 | Canal en vivo arriba | 4,0–8,6 | 8,7–60,5 | 1 de 30 |
| Android 15 | Canal caído + push | 1,7–2,5 | 3,3–4,9 | 0 de 30 |
| Android 15 | Doze + push (sin exención) | 1,2–2,2 | 2,0–3,5 | 0 de 30 |
| Android 15 | Proceso muerto + push | 2,2–4,6 | 6,2–7,7 | 1 de 30 |

- Las dos órdenes "sin ejecutar" (una alarma con canal en vivo, un bloqueo tras `kill -9`, ambas en Android 15 con
  carga 21–36) quedaron **entregadas y sin respuesta** dentro de la espera del script (60 y 120 s). No se perdieron:
  la cola las reentrega a los 2 minutos (`scripts/command-reliability-e2e.py`), pero el script no esperó ese plazo.
  No se determinó si la causa fue la saturación del emulador o el agente; no se reprodujo en las 252 órdenes con
  carga baja.
- En la primera de estas corridas apareció un defecto real, **ya corregido y con prueba**: una orden urgente enviada
  menos de 3 s después de un push ya respondido se quedaba sin su push (antirrebote) y esperaba al sondeo. Ahora el
  antirrebote solo aplica mientras el push anterior sigue sin respuesta, y en ese caso sale uno más al cerrar la
  ventana.

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

Versión fijada en 24.1.0, que es la medida y probada con la cadena de compilación actual del agente (AGP 8.6,
Kotlin 2.0.20, minSdk 23). Hay versiones más nuevas; no se probó si compilan con esta cadena, así que subirla queda
como mantenimiento junto con AGP/Kotlin.

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
