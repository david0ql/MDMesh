# Inventario y reportes de la flota

Cómo exportar el estado de toda la flota (batería, almacenamiento, versión de Android, estado de conexión, red),
qué reportes hay, qué columnas trae cada archivo y cómo programar su envío por correo.

Todo sale del **último reporte** que cada equipo envió en su conexión periódica (check-in): el servidor no inventa
ni estima datos. La columna **Última muestra** dice de cuándo es el dato de cada equipo, y **Actualizar ahora** pide
uno nuevo en el momento.

## 1. Dónde se exporta

| Dónde | Qué hace |
|---|---|
| **Dispositivos → Exportar** | Inventario en Excel o CSV. Se elige el alcance: todos los dispositivos (o la carpeta filtrada con sus subcarpetas), *lo que muestra la tabla* (respeta estado, política, Android, búsqueda y duplicados) o *los seleccionados*. |
| **Reportes → Inventario de la flota** | El mismo inventario con resumen en pantalla (totales, alertas y distribución por Android, modelo y agente), filtro por carpeta, umbrales ajustables y exportación a Excel o CSV. |
| **Reportes → Envíos programados** | El inventario (u otro reporte) por correo, diario, semanal o mensual. |
| **Reportes → (demás pestañas)** | Cada reporte tiene sus botones Excel y CSV. |

Un administrador de carpeta solo ve y exporta los equipos de sus carpetas; el resumen cuenta solo esos.

### Formatos

- **Excel (.xlsx)**: libro con cuatro hojas — `Resumen`, `Dispositivos`, `Carpetas`, `Conexiones`. Las fechas son
  fechas reales de Excel en la zona horaria del navegador (o la del envío programado). El nombre del equipo abre su
  página en la consola y la ubicación abre el mapa.
- **CSV**: la hoja `Dispositivos`, una fila por equipo. UTF-8 **con BOM** (Excel en Windows abre bien los acentos),
  filas terminadas en CRLF, fechas `aaaa-mm-dd hh:mm`, decimales con punto. El separador se elige: punto y coma
  (por defecto, lo que espera Excel en español), coma, tabulador o barra vertical. Un campo que contiene el
  separador, comillas o saltos de línea va entre comillas. Un texto que una hoja de cálculo ejecutaría como fórmula
  (empieza por `=`, `@`, o `+`/`-` sin ser un número) se escribe con un apóstrofo delante.

## 2. Columnas del inventario (hoja `Dispositivos` y CSV)

| Columna | Contenido |
|---|---|
| Nombre | Nombre del equipo (o su número si no tiene). |
| Número | Identificador del equipo en la consola. |
| Carpeta | Ruta completa de su carpeta (`Padre / Hija`) o `Sin carpeta`. |
| Política | Política que tiene aplicada. |
| Estado | `En línea` si su canal en vivo está abierto o reportó en los últimos **20 minutos**; si no, `Sin conexión`. |
| Min. desde el último reporte | Minutos transcurridos desde su último reporte (vacío si nunca reportó). |
| Última conexión | Fecha y hora de su último reporte. |
| Última muestra | Fecha y hora en que el servidor recibió los datos de esta fila (batería, almacenamiento, red…). |
| Conectado desde | Inicio de su sesión de conexión actual (vacío si está sin conexión). |
| Modelo, Fabricante | Del hardware. |
| Android | Versión del sistema operativo. |
| Parche de seguridad | Nivel de parche de seguridad de Android. |
| Actualización de Android | `Pendiente` si hay una actualización del sistema esperando; `Al día` si no. |
| Agente | Versión del agente DallyControl instalado. |
| Serie, IMEI, MAC Wi-Fi | Identificadores (según los permisos del equipo). |
| Teléfono, SIM | Número(s) y operador(es) de la SIM, o `Sin SIM` / `Bloqueada (PIN/PUK)`. |
| Celular esperado, SIM esperada | Número declarado al inscribir y si la SIM `Coincide`, `No coincide` o `No disponible`. |
| Correo | Cuenta de correo del equipo (o la de su carpeta). |
| Batería % | Nivel de batería. |
| Cargando | `Sí` / `No`. |
| Salud batería | `Buena`, `Sobrecalentada`, `Fría`, `Sobrevoltaje`, `Agotada` o `Desconocida`. |
| Temperatura °C | Temperatura de la batería. |
| Libre GB, Total GB | Almacenamiento interno libre y total (GiB, un decimal). |
| % usado | Porcentaje del almacenamiento interno ocupado. |
| Red | `Wi-Fi`, `Datos móviles` o `Sin red`. |
| Señal | Calidad de 0 a 4 con su nombre (`Buena (3/4)`); en Wi-Fi también los dBm. |
| SSID/Operador | Nombre de la red Wi-Fi, o el operador en datos móviles. El SSID solo está disponible si el equipo tiene permiso de ubicación. |
| IP | Dirección IP local del equipo. |
| Quiosco | `Sí` si el modo quiosco está activo. |
| Modo de conexión | `Siempre conectado` o `Ahorro de batería`. |
| Ubicación, Hora de la ubicación | Última posición (enlace al mapa) y cuándo se tomó. |
| Inscrito | Fecha de inscripción. |
| Alertas | `Batería baja`, `Almacenamiento casi lleno`, `Sin conexión > N d`, `Nunca ha reportado`, `Agente desactualizado`, `Actualización de Android pendiente`. |

Un agente antiguo que no envía algún dato deja esa celda vacía; no se rellena con ceros.

### Hoja `Resumen`

Alcance, total de dispositivos, **En línea (últimos 20 min)**, sin conexión, sin conexión por más de N días,
batería baja, almacenamiento casi lleno, agente desactualizado, actualización de Android pendiente, rango del
historial de conexiones, fecha de generación, y la distribución **por versión de Android, por modelo y por versión
del agente**.

Reglas de las alertas (umbrales ajustables en Reportes → Inventario de la flota):

| Alerta | Regla | Por defecto |
|---|---|---|
| Batería baja | batería ≤ umbral y el equipo **no** está cargando | 20 % |
| Almacenamiento casi lleno | % usado ≥ umbral | 90 % |
| Sin conexión > N días | no está en línea y su último reporte es más antiguo que N días (o nunca reportó) | 7 días |
| Agente desactualizado | versión del agente menor que la que sirve este servidor (si no hay APK servido, la más nueva vista en la flota) | — |

### Hojas `Carpetas` y `Conexiones`

- `Carpetas`: cada carpeta con su política propia, la que aplica, cuántos equipos tiene (con y sin subcarpetas) y
  cuántos están en línea.
- `Conexiones`: cada periodo en que un equipo estuvo reportándose en los últimos 30 días (inicio, fin, duración en
  horas, `Conectado ahora` / `Cerrada`). Una pausa de más de 20 minutos cierra el periodo.

## 3. Datos al momento: antigüedad y «Actualizar ahora»

- La lista de dispositivos tiene la columna **Dato de** («hace 2 min»), en verde hasta 20 minutos, ámbar hasta un
  día y gris después. El detalle del equipo muestra «Datos de hace …» junto al estado.
- **Actualizar ahora** (barra de selección de la lista, y detalle del equipo) le pide a los equipos que reporten ya.
  Usa la misma señal que «Sincronizar ahora»: un aviso por el canal en vivo del equipo; no se encola ningún comando.
  - Un equipo con el canal en vivo abierto responde en segundos; la consola avisa cuántos reportaron y en cuánto.
  - Un equipo sin canal abierto (apagado, sin red, o en ahorro de batería con la pantalla apagada) no puede
    despertarse: reporta en su próxima conexión periódica, y la consola lo dice.

## 4. Envíos programados por correo

Reportes → **Envíos programados** → *Nuevo envío*: nombre, reporte, carpeta (o todos), frecuencia (diaria, semanal
con día de la semana, mensual con día del mes — en un mes más corto se usa su último día), hora y zona horaria,
formato (Excel o CSV con su separador) y destinatarios (hasta 20 correos separados por coma).

- **Pausar / Reanudar** conserva el envío sin enviarlo. **Enviar ahora** lo genera y lo envía en el momento (sirve de
  prueba) sin mover su horario. La tabla muestra el próximo envío y cómo terminó el último.
- El correo trae un resumen (totales y alertas) y el archivo adjunto.
- Reportes que se pueden programar: Inventario de la flota, Equipos sin conexión, Agentes desactualizados, Apps
  desactualizadas, Incumplimiento de políticas, Equipos que no reciben comandos, Consumo de datos móviles,
  Retiro/cambio de SIM y Equipos dados de baja (con sus filtros por defecto).
- Requiere el permiso `manage_fleet`. Un administrador de carpeta solo puede programar envíos de sus carpetas.
- El servidor revisa cada minuto qué envíos tocan. Si estuvo apagado a la hora de un envío, lo manda una sola vez al
  volver. Con varios servidores sobre la misma base de datos, cada envío sale una sola vez.

### Configurar el correo saliente (SMTP)

Los envíos usan el SMTP del servidor (el mismo de la recuperación de contraseñas). Sin SMTP, la pantalla lo avisa,
los envíos se pueden crear y editar igual, y cada intento queda marcado «Sin SMTP» sin producir errores.

Instalación con Docker (`.env` junto a `docker-compose.yml`, luego `docker compose up -d`):

```
SMTP_HOST=smtp.ejemplo.com
SMTP_PORT=587
SMTP_FROM=mdm@ejemplo.com
SMTP_USERNAME=usuario      # opcional
SMTP_PASSWORD=secreto      # opcional
SMTP_STARTTLS=true         # con el puerto 587 (o SMTP_SSL=true con el 465)
```

Probado con STARTTLS y usuario/contraseña. `SMTP_SSL_TRUST` solo sirve para servidores de prueba con certificado
propio.

Instalación nativa: los parámetros `smtp.host`, `smtp.port`, `smtp.ssl`, `smtp.starttls`, `smtp.username`,
`smtp.password` y `smtp.from` del contexto de Tomcat (los escribe `install/install-native.sh`).

## 5. Los demás reportes

Cada uno se ve en Reportes y se exporta a Excel o CSV con estas columnas:

| Reporte | Columnas |
|---|---|
| Sin conexión (≥ N días) | Equipo, Número, Carpeta, Última conexión, Agente, Android |
| Retiro de SIM | Equipo, Número, Carpeta, Evento, Fecha, Detalle |
| SIM no coincide | Equipo, Número, Carpeta, Celular esperado, Celular de la SIM, Verificado, Última conexión |
| Apps desplegadas | Paquete, Equipos, Despliegues, Exitosos, Fallidos, Pendientes, Último |
| Despliegues de un paquete | Equipo, Número, Carpeta, Estado, Creado, Completado, Detalle |
| Por app instalada | Equipo, Número, Carpeta, Apps, # apps |
| Sin recibir comandos | Equipo, Número, Carpeta, Pendientes, Más antiguo, Comando, Última conexión |
| Agentes desactualizados | Equipo, Número, Carpeta, Agente, Última, Reportado |
| Apps desactualizadas | Equipo, Número, Carpeta, App, Paquete, Instalada, Biblioteca |
| Datos móviles | Equipo, Número, Carpeta, Datos móviles, Wi-Fi, Último día |
| Incumplimiento | Equipo, Número, Carpeta, Política, Estado, Revisión aplicada, Revisión actual, Aplicada |
| Dados de baja | Equipo, Número, Carpeta, Dado de baja, Reclamado |
| Inventario de la flota | Las de la sección 2 |

### Flotas grandes

El Excel se genera en modo *streaming* (las filas van a un archivo temporal y no se acumulan en memoria) y el CSV se
escribe fila por fila. Con 5.000 equipos y 103.000 periodos de conexión, en un servidor con 512 MB de memoria: Excel
≈ 6 s, CSV ≈ 1 s. En la hoja `Conexiones` solo la primera fila de cada equipo lleva el enlace a su página.

## 6. API

Todas bajo `/rest/private` y con la sesión de la consola.

| Método y ruta | Uso |
|---|---|
| `GET /fleet/v1/export.xlsx?group=&days=&tz=&numbers=a,b` | Libro Excel. |
| `GET /fleet/v1/export.csv?group=&tz=&sep=;\|comma\|tab\|pipe&numbers=a,b` | CSV. |
| `POST /fleet/v1/export` `{format, separator, group, days, tz, numbers[]}` | Lo mismo, para listas largas de equipos. |
| `POST /fleet/v1/devices/refresh` `{deviceIds[], numbers[]}` | «Actualizar ahora». Responde `{requested, live, notLive[], at}`. |
| `GET /reports/v1/inventory?group=&lowBattery=&storagePct=&offlineDays=` | Inventario con `summary`. |
| `GET /reports/v1/inventory/export?format=xlsx\|csv&sep=` | Su exportación. |
| `GET/POST /reports/v1/schedules`, `PUT/DELETE /reports/v1/schedules/{id}` | Envíos programados. |
| `POST /reports/v1/schedules/{id}/send` | «Enviar ahora». Responde `{sent, status: ok\|error\|smtp, message}`. |

## 7. Prueba de punta a punta

`scripts/fleet-report-e2e.py` cambia batería, almacenamiento y red de un equipo inscrito (por adb), usa «Actualizar
ahora», descarga el Excel y el CSV reales y comprueba cada columna contra lo que dice el equipo; con `--mailpit`
prueba el envío programado contra un SMTP de prueba y abre el adjunto recibido. Ver [TESTING.md](TESTING.md).
