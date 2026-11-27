# Monitoreo, alertas y página de estado

Tres piezas, de dentro hacia fuera:

| Pieza | Qué hace | Dónde |
|---|---|---|
| **Salud real** | Cada instancia dice si puede atender: responde y llega a la base de datos y al almacén de archivos. | `/healthz/deep`; comprobación de salud de los contenedores |
| **Monitor propio** | Cada minuto comprueba todo, guarda el resultado, avisa por correo / webhook / Telegram y publica la página de estado. | Servicio `monitor` (perfil `monitor`) |
| **Uptime Kuma** | Segunda fuente, independiente del propio stack: si el servidor entero muere, el monitor propio muere con él. | Stack aparte que ya corre en el servidor |

## Direcciones de salud

Todas sin autenticación y sin datos: solo palabras fijas.

| Dirección | Responde 200 cuando… | Úsela para |
|---|---|---|
| `/healthz/deep` | el borde, el servidor **y la base de datos** funcionan | **Monitoreo.** Es la que define la disponibilidad |
| `/healthz` | el borde y el servidor responden (no consulta la base de datos) | Compatibilidad con lo que ya la usa |
| `/healthz/edge` | el borde responde, aunque el servidor esté caído | Distinguir «cayó el túnel o el borde» de «cayó el servidor» |
| `/healthz/supervisor` | el supervisor (actualizaciones, recuperación, descarga del agente) responde | Vigilar que los enrolamientos nuevos puedan descargar el APK |
| `/estado`, `/estado.json` | — | Página de estado para el cliente (requiere el perfil `monitor`) |

Respuesta de `/healthz/deep`:

```json
{"status":"ok","checks":{"db":"ok","files":"ok"}}          → 200
{"status":"down","checks":{"db":"fail","files":"ok"}}      → 503   (base de datos caída)
{"status":"down","checks":{"server":"fail"}}               → 503   (ningún servidor responde; lo contesta el borde)
```

Durante un despliegue, la instancia que se va a retirar responde «en retiro» (`"status":"draining"`, 503)
**solo a la comprobación de salud del propio borde**, para que deje de enviarle peticiones. A un monitor le
sigue respondiendo 200 mientras atienda bien: un despliegue no se registra como tiempo sin servicio.

La consulta a la base de datos tiene un límite de 5 segundos y su resultado se reutiliza 2 segundos, de modo
que nadie puede usar esta dirección para cargar la base de datos.

Los contenedores `server` y `caddy` tienen comprobación de salud (`docker compose ps` muestra `healthy`), y
`cloudflared` no arranca hasta que `caddy` está saludable.

## Activar el monitor

1. En `.env` agregue el perfil y configure (lista completa y comentada en `.env.example`):

   ```ini
   COMPOSE_PROFILES=cloudflare,remote,monitor
   MONITOR_PUBLIC_URL=https://mdm.ejemplo.com/healthz/deep
   MONITOR_TUNNEL_URLS=http://cloudflared:20241/ready
   MONITOR_BACKUP_MAX_AGE_HOURS=26
   ALERT_EMAIL_TO=guardia@ejemplo.com,lider@ejemplo.com
   ALERT_TELEGRAM_BOT_TOKEN=...
   ALERT_TELEGRAM_CHAT_ID=...
   ```

2. `docker compose up -d monitor`
3. Compruebe: `docker compose logs monitor` debe listar las comprobaciones activas, y
   `https://<dominio>/estado` debe abrir.
4. **Pruebe el canal de alertas** (no dé por hecho que funciona): detenga un minuto algo inofensivo, por
   ejemplo `docker compose stop supervisor`, espere el aviso (2 a 3 minutos), y arránquelo de nuevo
   (`docker compose start supervisor`); debe llegar el aviso de recuperación.

`MONITOR_TUNNEL_URLS` usa la dirección `/ready` de cloudflared, que este repositorio activa con
`--metrics 0.0.0.0:20241` (solo en la red interna de Docker). Como eso cambia la orden del contenedor, la
primera vez hay que recrear el conector: hágalo según `TUNEL.md` para no cortar el acceso.

## Qué se comprueba

| Comprobación | Falla cuando… | Qué indica |
|---|---|---|
| Acceso público (`MONITOR_PUBLIC_URL`) | la dirección externa no responde 200 | Los usuarios y los equipos no pueden entrar. **Define la disponibilidad.** |
| Servicio interno (`/healthz/deep` por el borde) | no responde 200 | Servidor o base de datos caídos. Si esta pasa y la pública falla: túnel, DNS o Cloudflare. |
| Supervisor | no responde | Sin actualizaciones ni descarga del agente para enrolar. |
| Túnel (`/ready` de cada conector) | el conector no tiene conexiones con Cloudflare | Túnel caído. |
| Espacio en disco (`/data`, `/backups`) | uso ≥ `MONITOR_DISK_WARN_PERCENT` (85 %) | Riesgo de que la base de datos se detenga por falta de espacio. |
| Último respaldo | pasó más de `MONITOR_BACKUP_MAX_AGE_HOURS`, o la copia externa falló | No hay respaldo reciente. |
| Equipos reportando | los que reportaron en los últimos 30 min caen un 40 % o más respecto de la última hora | **Incidente de comunicación**: el servicio «responde», pero los equipos no llegan (DNS, certificado, operador, token del túnel). |

Sobre «equipos reportando»: el agente reporta cada 15 minutos, así que una caída total se nota a los
15–20 minutos. Es más lenta que las demás a propósito: detecta lo que ninguna comprobación de salud ve.
Un apagado masivo al final de un turno puede dispararla; si ocurre, suba `MONITOR_DEVICES_DROP_PERCENT`.

## Alertas

- **Antirrebote:** se avisa tras 2 fallas seguidas (`MONITOR_FAIL_AFTER`): una consulta perdida no despierta
  a nadie. El incidente se fecha en la primera falla, no en el aviso.
- **Recuperación:** se avisa tras 2 aciertos seguidos, con la duración del incidente.
- **Recordatorio:** cada 60 minutos mientras siga en falla.
- **Tiempo de detección:** con el intervalo de 60 s, entre 1 y 2 minutos desde la falla. Medido en pruebas
  con intervalo de 10 s: la salud cambió a 503 a los 1,6 s de detener la base de datos y la alerta llegó a
  los 20,1 s (dos ciclos), por correo y por webhook.

Ejemplo de aviso:

```
[DallyControl] INCIDENTE: Servicio interno (borde + servidor + base de datos)
Estado: FALLA
Detalle: HTTP 503
Desde: 27/11/2026, 1:08:11 p. m.
Detectado tras 2 comprobaciones fallidas seguidas.
Runbook: docs/ops/RUNBOOK.md

[DallyControl] RECUPERADO: Servicio interno (borde + servidor + base de datos)
Estado: OPERATIVO
Duración del incidente: 30 s
```

Canales (se pueden combinar):

| Canal | Variables | Notas |
|---|---|---|
| Correo SMTP | `ALERT_EMAIL_TO`, y `ALERT_SMTP_*` si el SMTP es distinto del de la aplicación | STARTTLS automático; `ALERT_SMTP_TLS=ssl` para el puerto 465 |
| Telegram | `ALERT_TELEGRAM_BOT_TOKEN`, `ALERT_TELEGRAM_CHAT_ID` | Cree el bot con @BotFather, agréguelo al grupo de guardia |
| Webhook | `ALERT_WEBHOOK_URL` | POST JSON `{"text": "..."}`: el formato de los webhooks entrantes de Slack, Mattermost y Google Chat |

Si el correo de alertas sale por el mismo servidor que se cayó, no llegará: use al menos un canal que no
dependa de este servidor (Telegram o un SMTP externo).

## Página de estado y disponibilidad mensual

`https://<dominio>/estado` (y `/estado.json` para integraciones) muestra, sin datos internos:

- estado general y por componente (acceso público, servidor y base de datos, túnel, comunicación con equipos),
- disponibilidad de los últimos 90 días (una barra por día) y por mes, con los minutos sin servicio,
- incidentes recientes con inicio, fin y duración.

**Cálculo:** cada ciclo del monitor guarda el resultado de todas las comprobaciones
(`checks-AAAA-MM.jsonl` en el volumen `monitordata`, 13 meses). La disponibilidad del mes es
`ciclos correctos ÷ ciclos realizados` de la comprobación de **acceso público** (o del servicio interno si no
se configuró la pública). Los minutos en que el monitor mismo no corrió no cuentan a favor ni en contra; por
eso conviene Uptime Kuma como segunda fuente.

Para ocultar la página: `MONITOR_STATUS_PUBLIC=0`. El detalle interno (cada comprobación con su último
resultado) no se publica; se consulta desde el servidor:
`docker compose exec monitor wget -qO- http://127.0.0.1:9100/interno.json`.

## Registrar el servicio en Uptime Kuma

En el Uptime Kuma que ya corre en el servidor, cree estos monitores (**Add New Monitor**):

| Nombre | Tipo | URL | Intervalo | Reintentos | Códigos aceptados |
|---|---|---|---|---|---|
| DallyControl – público | HTTP(s) | `https://<dominio>/healthz/deep` | 60 s | 1 | 200-299 |
| DallyControl – borde | HTTP(s) | `https://<dominio>/healthz/edge` | 60 s | 1 | 200-299 |
| DallyControl – supervisor | HTTP(s) | `https://<dominio>/healthz/supervisor` | 300 s | 1 | 200-299 |
| DallyControl – palabra clave | HTTP(s) - Keyword | `https://<dominio>/healthz/deep`, palabra `"status":"ok"` | 60 s | 1 | — |
| DallyControl – certificado | HTTP(s) | `https://<dominio>/` con «Certificate Expiry Notification» | 1 día | — | — |

Recomendaciones:

- Use la **dirección pública**, no la interna: así Uptime Kuma mide lo mismo que ve un equipo (DNS,
  Cloudflare, túnel, borde, servidor, base de datos).
- Asigne a los monitores el mismo canal de notificación de guardia y agrúpelos con la etiqueta `dallycontrol`.
- Si quiere una sola página de estado para varios productos, agregue estos monitores a la **Status Page** de
  Uptime Kuma; la de `/estado` puede seguir siendo la que se comparte con el cliente de DallyControl.
- **Limitación:** ese Uptime Kuma corre en el mismo servidor. Si el servidor se apaga, no avisa nadie. Para
  cubrirlo, registre además la URL pública en un servicio externo gratuito (UptimeRobot, Better Stack,
  Healthchecks) o en un Uptime Kuma de otro servidor.

## Límites de recursos

Para convivir en un servidor compartido, cada servicio admite tope de memoria y CPU en `.env`
(`SERVER_MEM_LIMIT`, `POSTGRES_MEM_LIMIT`, …). Por defecto no hay tope, para no cambiar una instalación
existente. Antes de activarlos:

1. Mida el consumo real un día normal: `docker stats --no-stream`.
2. Fije el tope con al menos un 30 % de margen sobre el máximo observado.
3. Con tope de memoria en el servidor, fije también el heap: `SERVER_JAVA_OPTS=-XX:MaxRAMPercentage=60`
   (por defecto la JVM usa solo el 25 % de la memoria del contenedor).

Consumo observado en el entorno de pruebas (20 equipos, sin carga): servidor 340 MB, PostgreSQL 37 MB,
borde 14 MB, supervisor 14 MB, monitor 33 MB, respaldo en reposo 9 MB. **No son cifras de producción**: mida
allí antes de fijar topes. Un tope demasiado bajo hace que Docker mate el contenedor (reinicios con
`OOMKilled`): si lo ve, súbalo.
