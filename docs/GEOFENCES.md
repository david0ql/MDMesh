# Geocercas, alertas por correo e historial de ubicación

Zonas sobre el mapa que avisan cuando un equipo **sale de donde debe permanecer** o **entra a donde no debe**, con
aviso por correo, historial del recorrido exportable y control de la precisión del GPS.

Las zonas se evalúan **en el servidor** cada vez que se guarda una posición. El teléfono solo reporta dónde está, así
que funciona con cualquier versión del agente ya instalada (0.8.x en adelante); las zonas nunca viajan al equipo.

## 1. Crear una zona

Consola → **Geocercas** → *Nuevo círculo* o *Nuevo polígono*.

| Qué | Cómo |
|---|---|
| Círculo | Clic en el mapa para el centro. Arrastra el centro para moverlo y el punto del borde para el radio (o escribe el radio en metros; mínimo 20 m). |
| Polígono | Cada clic agrega un vértice (mínimo 3, máximo 200). Arrastra un vértice para moverlo, clic en un punto intermedio para agregar uno ahí, clic derecho (o doble clic) sobre un vértice para quitarlo. |
| Regla | **Debe permanecer dentro** (alerta al salir; línea continua) o **No debe entrar** (alerta al entrar; línea punteada). |
| Aplica a | Carpetas (incluye sus subcarpetas: se hereda como las políticas), dispositivos sueltos, o *Toda la flota* (solo administradores completos). |
| Horario | *Siempre*, o días y horas. Fuera del horario no se alerta; si el equipo sigue fuera de lugar cuando el horario empieza, se alerta en ese momento. Un horario que termina antes de empezar (22:00 a 06:00) cruza la medianoche y pertenece al día en que empieza. |
| Frecuencia de ubicación | Minutos entre posiciones mientras la zona esté activa (ver §4). |
| Avisar por correo a | Correos propios de la zona, además de los destinatarios por carpeta. |
| Avisar también cuando regrese | Segunda alerta cuando el equipo vuelve a estar donde debe. |

Un administrador de carpetas ve las zonas que tocan sus carpetas o dispositivos (también las heredadas de carpetas
superiores, en solo lectura) y solo puede crear o cambiar zonas asignadas por completo dentro de lo suyo. Se necesita
el permiso `manage_fleet` para modificar (el mismo de Carpetas, Reportes y Alertas).

Las zonas activas se dibujan en **Mapa** (casilla *Zonas*; los equipos fuera de lugar llevan una etiqueta) y en la
pestaña **Ubicación** de cada dispositivo, que indica por zona si está *dentro* o *fuera*.

## 2. Cómo se decide una entrada o salida

Por cada equipo y zona el servidor guarda un estado (dentro / fuera). Una posición lo cambia solo si:

1. **Es suficientemente precisa.** Las posiciones con un margen de error (±m) mayor que *Precisión máxima aceptada*
   (100 m por defecto) se ignoran para las zonas y se marcan como *baja precisión* en el historial.
2. **Es concluyente.** Para dar por cierto que el equipo cruzó hacia el lado indebido, el punto debe quedar más allá
   del borde por *Margen del borde* (15 m) **más la precisión de esa posición**. Para volver basta con estar del otro
   lado del borde. Entre ambos hay una franja donde nada cambia: un equipo parado junto al límite no genera alertas
   intermitentes. En zonas pequeñas el margen se limita a la mitad del tamaño de la zona.
3. **Se confirma.** Hacen falta *Posiciones de confirmación* (2) seguidas. Un punto saltón del GPS no alerta. La
   alerta lleva la hora de la **primera** de esas posiciones, que es cuando el equipo cruzó.

La primera posición que ve una zona solo fija el punto de partida; no alerta. Si se cambia la forma o la regla de
una zona, vuelve a empezar desde ahí.

Cuando un teléfono se reconecta después de estar sin red, sube todas las posiciones acumuladas (hasta 288). El
servidor las recorre **en orden**, de la más antigua a la más reciente, por la misma máquina de estados: las alertas
quedan con la hora real en que ocurrió cada cruce, no con la hora de la reconexión.

Los tres valores se cambian en **Geocercas → Precisión e historial** (solo administradores completos).

## 3. Alertas y correo

Los tipos **Salida de zona** (`geofenceExit`) y **Entrada a zona** (`geofenceEnter`) aparecen en **Alertas** con los
demás (desconexión, salida de kiosko, cambio de SIM): filtro por tipo, estado, fechas, marcar como revisada y
exportación. Cada cruce queda también en el historial del dispositivo (pestaña *Eventos*).

### Correo

**Geocercas → Notificaciones por correo**: reglas de destinatarios por carpeta (cubren sus subcarpetas) o para toda
la flota, con los tipos de alerta que le interesan a cada una. Aplica a **todas** las alertas, no solo a las zonas.

Para no inundar:

- cada destinatario recibe **como máximo un correo por intervalo** (10 min por defecto; *Intervalo de correos*). La
  primera alerta sale de inmediato (el envío corre cada minuto); lo que ocurra dentro del intervalo se junta en un
  solo mensaje cuando termina;
- dentro del mensaje, las repeticiones del mismo equipo, tipo y zona son una línea con su conteo;
- un destinatario recién agregado solo recibe alertas ocurridas desde ese momento; nunca se envían alertas de más
  de 24 horas ni las ya marcadas como revisadas.

El mensaje (en español) trae por alerta: tipo, equipo (enlace al dispositivo), carpeta, fecha, detalle y *Ver en el
mapa* (abre la pestaña Ubicación del equipo), más un enlace a la página de Alertas.

### Configurar SMTP

El servidor toma el correo saliente del entorno (`.env` del stack):

```
SMTP_HOST=smtp.ejemplo.com      # vacío = sin correo
SMTP_PORT=587
SMTP_FROM=alertas@ejemplo.com
SMTP_USERNAME=usuario           # opcional
SMTP_PASSWORD=clave             # opcional
BASE_URL=https://mdm.ejemplo.com   # ya existe; es la base de los enlaces del correo
```

y luego `docker compose up -d server`. Si `SMTP_HOST` está vacío **nada falla**: las alertas se generan y se ven en
la consola, y tanto Alertas como Geocercas → Notificaciones muestran el aviso «El servidor no tiene correo saliente
(SMTP) configurado». Con SMTP configurado, *Enviar prueba* (en esa misma pestaña) confirma que el servidor de correo
acepta los mensajes. Nota: la imagen actual arranca Tomcat con `smtp.ssl=false` y `smtp.starttls=false`
(`docker/entrypoint.sh`); un proveedor que exija STARTTLS/SSL necesita habilitarlo ahí.

## 4. Precisión y frecuencia de la ubicación

- **Modo de ubicación** (política o acción *Ubicación: precisa*): en modo preciso el agente pide una posición nueva
  en cada reporte. Desde esta versión del agente espera hasta **25 s** (antes 5 s) y es configurable por política:
  *Espera de posición precisa*, 5–60 s. Tras una espera sin resultado (bajo techo) las siguientes son cortas durante
  2 minutos para no frenar los reportes.
- **Proveedor**: el agente prefiere el proveedor *fused* (Google Play services: mezcla GPS, Wi-Fi y celdas) cuando el
  equipo lo tiene; si no (equipos sin GMS), GPS y luego red. Si el primero no responde en su parte de la espera, se
  intenta el siguiente.
- **Frecuencia**: los equipos reportan posición al hacer check-in. Para detectar a tiempo una salida, una zona puede
  pedir una *Frecuencia de ubicación*: mientras esté activa, sus equipos toman una posición al menos cada N minutos
  (el servidor ajusta el intervalo de recorrido de la configuración del equipo al menor entre el de la política y el
  de sus zonas; al borrar o desactivar la zona vuelve al de la política). Requiere un agente con `config.apply`
  (todos los 0.8.x+). Con confirmación de 2 posiciones, la alerta llega entre 1 y 2 intervalos después del cruce.
  Posiciones frecuentes gastan batería; para avisos al minuto usa además el modo *Siempre conectado*.
- El intervalo también se puede fijar en la política (*Recorrido de ubicación*), sin zonas.

## 5. Historial

Pestaña **Ubicación** del dispositivo: rango de fechas (hasta 366 días), carga por páginas de 1000 posiciones
(*Cargar anteriores*), precisión de la última posición (círculo ±m en el mapa), posiciones de baja precisión en gris
con opción de ocultarlas, y **Excel / CSV** del recorrido completo del rango (fecha, latitud, longitud, precisión,
proveedor, calidad y enlace al mapa).

**Retención**: *Geocercas → Precisión e historial → Retención del historial*. 0 = conservar siempre; con N días, un
trabajo del servidor borra cada 6 horas las posiciones más antiguas.

## 6. API

Todo bajo `/rest/private/geofences/v1` (sesión de consola):

| Método y ruta | Qué hace |
|---|---|
| `GET /zones` · `POST /zones` · `PUT /zones/{id}` · `DELETE /zones/{id}` | Zonas. Círculo: `shape:"circle", centerLat, centerLon, radiusM`. Polígono: `shape:"polygon", points:[[lat,lon],…]`. `rule`: `stayInside` / `keepOut`. `groupIds`, `deviceIds`, `allDevices`, `schedule` (texto JSON `{"days":[1..7],"from":"08:00","to":"18:00","tz":"America/Bogota"}`), `emails`, `trackingMinutes`, `alertOnReturn`, `active`. |
| `GET /states` | Por zona y equipo: `state` (`inside`/`outside`), `since`, `violating`. |
| `GET /devices/{número}/zones` | Zonas que aplican al equipo y su estado. |
| `GET /devices/{número}/track?from=&to=&page=&size=&order=desc` | Historial paginado (máx. 2000 por página); `lowAccuracy` por posición. |
| `GET /devices/{número}/track/export?format=xlsx\|csv&from=&to=&tz=` | Recorrido en Excel o CSV. |
| `GET /settings` · `PUT /settings` | `maxAccuracyM`, `hysteresisM`, `confirmFixes`, `retentionDays`, `emailIntervalMin`. |
| `GET /recipients` · `POST` · `PUT /recipients/{id}` · `DELETE` | Destinatarios: `groupId` (null = toda la flota), `emails`, `types`, `active`. |
| `GET /mail` · `POST /mail/test` | Si hay SMTP; enviar un correo de prueba. |

Las alertas siguen en `/rest/private/alerts/v1/list?type=geofenceExit|geofenceEnter`.

## 7. Cómo probar

### Sin teléfono: posiciones inyectadas por la API del agente

`scripts/geofence-e2e.py` hace de dispositivo (igual que `scripts/agent-v1-e2e.sh`): inscribe un equipo de prueba,
crea zonas e inyecta posiciones dentro → fuera → dentro, un punto saltón, posiciones imprecisas y un lote acumulado
desordenado; verifica estado, alertas (tipo, zona y hora de la posición), historial, exportación y el intervalo que
la zona empuja a la configuración. Al terminar borra lo que creó.

```bash
docker compose --env-file docker/dev.env up -d --build && scripts/dev-seed.sh
scripts/geofence-e2e.py --base http://localhost:8080                # RESULT: PASS=<n> FAIL=0
```

Con correo, levanta un SMTP de prueba (Mailpit) en la red del stack y pásalo al script:

```bash
docker run -d --name mailpit --network dallycontrol-dev_default -p 127.0.0.1:8025:8025 axllent/mailpit
SMTP_HOST=mailpit SMTP_PORT=1025 docker compose --env-file docker/dev.env up -d server
scripts/geofence-e2e.py --base http://localhost:8080 --mailpit http://localhost:8025
docker rm -f mailpit
```

Para inyectar una posición a mano, el check-in del agente lleva `telemetry.dynamic.location` y, para un lote,
`telemetry.dynamic.trail` (ver `proto/` y el script):

```bash
curl -X POST -H "Authorization: Bearer $SECRETO" -H 'Content-Type: application/json' \
  -d '{"deviceId":"'$EQUIPO'","capabilities":{},"state":{"battery":80},
       "telemetry":{"dynamic":{"location":{"lat":4.7045,"lon":-74.05,"accuracyM":10,"provider":"gps","capturedAt":'$(($(date +%s)*1000))'}}}}' \
  http://localhost:8080/rest/public/agent/v1/checkin
```

### Con un emulador

Con un emulador inscrito (`scripts/adb-enroll.sh … --debug-build`), el mismo script lo mueve con `adb emu geo fix`
(dentro → fuera → dentro), repite el viaje con la red apagada para que las posiciones lleguen en lote, y revisa
posición y precisión, alertas, historial y correo:

```bash
scripts/geofence-e2e.py --base http://localhost:8088 --serial emulator-5554 --device <número> --mailpit http://localhost:8025
```

Usa un AVD con API ≤ 29: en las imágenes API 30+ el GNSS emulado solo acepta la primera posición inyectada después
del arranque (ver [TESTING.md](TESTING.md)).

### Con un teléfono

1. Crea un círculo de 150–300 m alrededor de donde está el teléfono, asígnalo al equipo, *Frecuencia de ubicación*
   1 minuto, y pon tu correo en la zona.
2. Verifica en la pestaña Ubicación del equipo que aparece *dentro* y que la precisión reportada es razonable
   (al aire libre, ±3–20 m con GPS/fused).
3. Sal de la zona caminando o en vehículo al menos 50 m más allá del borde. En 1–2 intervalos debe aparecer la
   alerta *Salida de zona* (y el correo). Regresa: *Entrada a zona*.
4. Repite con datos y Wi-Fi apagados durante el recorrido: al reconectar deben aparecer ambas alertas con las horas
   reales del cruce.
