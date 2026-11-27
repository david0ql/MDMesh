# Despliegue sin corte

Actualizar una instalación en marcha:

```bash
git pull
scripts/deploy.sh
```

`./setup.sh` sigue siendo el instalador (primera instalación, `--reset`, reparaciones). Volver a ejecutarlo
sobre una instalación en marcha funciona, pero recrea los contenedores a la manera antigua, con corte.

## Qué hace `scripts/deploy.sh`

| Paso | Qué hace | Si falla |
|---|---|---|
| 1. Construir | Construye (o descarga) las imágenes nuevas **antes** de tocar nada, de una en una para no quitarle recursos al servicio. | No se cambió nada. |
| 2. Respaldar | Volcado verificado de la base de datos en `/backups/predeploy` (conserva los 10 últimos). | No se cambió nada. |
| 3. Servidor, con relevo | Arranca una segunda instancia (`server-b`) con la imagen nueva junto a la que está atendiendo y espera a que esté saludable (base de datos incluida) y a que responda con rapidez cinco veces seguidas. Solo entonces marca la anterior como «en retiro», la recrea con la imagen nueva, espera a que esté saludable y retira el relevo. | Si la versión nueva no arranca: se elimina el relevo; **la instancia anterior nunca se tocó**. |
| 4. Web | Copia los archivos nuevos de la consola dentro del borde en ejecución (primero los archivos con nombre único, al final `index.html`). Si cambió el `Caddyfile`, lo valida y lo recarga sin reiniciar. El borde solo se recrea si cambió el binario de Caddy o la definición del servicio. | Se restaura la consola anterior. |
| 5. Verificar | Salud del servidor, salud a través del borde y, si responde desde el servidor, la dirección pública. | Vuelta atrás automática a las imágenes anteriores. |

El borde (Caddy) no se reinicia para actualizar el servidor: conoce las dos instancias (`server` y
`server-b`), comprueba la salud de ambas cada segundo y envía las peticiones a la que esté sana. Una petición
que no encuentra ninguna instancia sana **espera** hasta 20 segundos (`LB_TRY_DURATION`) en lugar de fallar.

El túnel (cloudflared) no se toca, salvo con `--tunnel`.

Opciones: `--no-build`, `--no-backup`, `--skip-web`, `--tunnel`, `--classic`, `--rollback`, `--yes`
(`scripts/deploy.sh --help`).

## Mediciones

Hechas el 27 de noviembre de 2026 en un stack local completo construido desde este repositorio (PostgreSQL,
servidor, borde, supervisor, monitor, respaldo), con tres sondas de 5 consultas por segundo durante todo el
despliegue (`scripts/measure-downtime.sh`) y un cliente que mantiene el WebSocket de un equipo igual que el
agente Android, con sus mismos tiempos de reintento (`scripts/ws-probe.mjs`).

La máquina de pruebas era compartida con otros trabajos y su carga cambió mucho durante el día; se indica
en cada fila porque **resultó ser el factor que más pesa**.

| Escenario | Carga de la máquina | API (`/rest/public/name`) | Consola (`/`) | Canal inmediato del equipo (WebSocket) | Duración |
|---|---|---|---|---|---|
| **Despliegue normal** (servidor + consola) | normal | **0 fallidas de 343** | **0 de 346** | 2 reconexiones: 1,1 s y 2,1 s sin canal | 74 s |
| Despliegue normal, configuración final | media-alta (carga 11–19 en 15 núcleos) | **0 fallidas de 334** | **0 de 336** | 3 reconexiones: 1,1 + 2,0 + 4,0 s (la tercera, por la recarga del `Caddyfile`) | 62 s |
| Despliegue normal | alta (carga 20–22) | 0 fallidas de 419 | 0 de 430 | 3 reconexiones: 1,1 + 2,1 + 4,0 s | 115 s |
| **Versión rota** (la falla del incidente del 2 de octubre: anotación de MyBatis mal ubicada) | normal | 0 fallidas de 103 | 0 de 103 | **ninguna desconexión** | detectada a los 16 s; vuelta atrás automática |
| La misma versión rota, antes de la detección temprana (esperó los 300 s) | normal | 0 fallidas de 1 204 | 0 de 1 224 | ninguna desconexión | 491 s de sondeo sin un fallo |
| `--rollback` (volver a la versión anterior) | normal | 0 fallidas de 333 | 0 de 339 | 2 reconexiones: 1,2 s y 1,1 s | 84 s |
| `--classic`: recrear el servidor en su sitio (lo que hacía el método anterior) | normal | 0 fallidas de 171; las peticiones **esperaron** al servidor (hasta 7 s) | 0 fallidas | 1 desconexión de **6,8 s** | 8 s |
| Borde recreado (cambió su imagen) | normal | 2 fallidas: corte de **0,6 s** | 1 fallida: 0,3 s | 1 reconexión de 2,0 s | 3 s |
| **Ensayo del primer despliegue** (servidor y borde anteriores a este mecanismo → versión nueva) | alta (carga 20) | corte de un arranque del servidor: **32 s** según el registro del despliegue (la sonda de entonces no distinguía la página de recuperación) | sin corte | 1 desconexión de **26,6 s**, y otra de 1,1 s | 109 s |
| Despliegue normal con la **máquina saturada** (carga 38–39, más del doble de sus núcleos) | saturada | **sin servicio unos 135 s** (medido en la sonda de salud): las peticiones esperaban 20 s y recibían la página de recuperación | 0 fallidas | 1 desconexión de 2,6 s | 414 s |

Cómo leerlo:

- **Con el servidor en condiciones normales o cargado, el despliegue no tiene corte** para la consola ni
  para la API de los equipos: ninguna petición falló.
- **Con la máquina saturada, el relevo falló.** Arrancar una segunda JVM en un servidor sin CPU libre tardó
  87 s (el relevo) y 155 s (la instancia principal); en ese intervalo la única instancia en servicio
  respondía tan despacio que las comprobaciones de salud del borde expiraban, el borde la sacó de servicio y
  los usuarios vieron la página de recuperación durante unos 135 segundos. A raíz de esa prueba se hicieron
  tres cambios, **cuya eficacia bajo saturación no se pudo volver a medir** (ver «Qué queda por medir»):
  las comprobaciones de salud del borde toleran respuestas lentas (8 s, tres fallas seguidas); el script
  exige que el relevo responda con rapidez cinco veces seguidas antes de tocar la instancia anterior, y si
  no, cancela sin cambiar nada; y las imágenes se construyen de una en una.
- **Construir las imágenes en el mismo servidor compite con el servicio.** En dos pruebas, durante la
  construcción (antes de cambiar nada), todo Docker se detuvo unos 25–40 segundos: ni el servicio ni las
  comprobaciones de salud respondieron. Coincidió con otras construcciones simultáneas en la máquina. En
  producción, lo más seguro es **construir fuera del servidor y descargar las imágenes** (`IMAGE_OWNER` de
  un registro), o desplegar en horas de poca carga.
- **El WebSocket sí se corta, y no se puede evitar** con este diseño: cada conexión vive en una instancia y
  esa instancia se reemplaza. Cada equipo conectado pierde su canal de aviso inmediato dos veces (al pasar al
  relevo y al volver), unos 3 segundos en total, y se reconecta solo. Si el despliegue cambia el `Caddyfile`
  hay una tercera reconexión (4 s). Mientras tanto no pierde nada: los comandos quedan en cola en la base de
  datos. Un comando enviado justo en esos segundos no despierta al equipo al instante: lo recibe en su
  siguiente reporte periódico (como máximo 15 minutos después).
- Las sesiones de la consola viven en la memoria de cada instancia del servidor, así que es de esperar que
  **un despliegue cierre la sesión de los usuarios de la consola** y deban volver a entrar, igual que con el
  método anterior. Esto se deduce del diseño; no se midió (las sondas no inician sesión).
- La sonda de salud profunda (`/healthz/deep`), que es la que usa el monitor, respondió bien durante todo el
  despliegue en la configuración final (0 fallidas de 298): un despliegue no cuenta como tiempo sin servicio.

### Qué queda por medir

- **El primer despliegue en producción.** Las cifras de arriba son de una máquina de desarrollo. Mídalo:

  ```bash
  MATCH='"status":"OK"' scripts/measure-downtime.sh https://<dominio>/rest/public/name 900 &
  scripts/deploy.sh
  ```

  (`MATCH` hace que una página de mantenimiento servida con código 200 cuente como falla.) Anote aquí el
  resultado.
- **El relevo con el servidor saturado, tras los cambios.** La máquina de pruebas siguió saturada por otros
  trabajos y no permitió una medición limpia. Hasta medirlo en producción, despliegue cuando el servidor
  tenga CPU libre (`uptime`: carga por debajo del número de núcleos) y memoria para una segunda instancia.
- **Un teléfono real.** El cliente de prueba reproduce los tiempos de reintento del agente (1, 2, 4… hasta
  60 s, y vuelve a 1 s tras 30 s de conexión estable), pero no es el agente.

## El primer despliegue con este mecanismo

La primera vez, el borde que está en ejecución es el antiguo: no conoce la instancia de relevo. El script lo
detecta, avisa y recrea el servidor a la manera clásica, **con corte** (un arranque del servidor: 32 s en el
ensayo, hecho con imágenes construidas desde la rama anterior a este trabajo; más unos segundos si hay que
recrear el borde). A partir del segundo despliegue ya no lo hay.

Hágalo en ventana de mantenimiento y en este orden:

1. Active la réplica del túnel **antes** (`TUNEL.md`), porque la orden de `cloudflared` cambia y se recreará.
2. `scripts/deploy.sh`
3. Compruebe `docker compose ps` (todo `healthy`) y `https://<dominio>/healthz/deep`.

Lo mismo vale al revés: volver, con `--rollback`, a una versión **anterior** a este mecanismo también tiene
corte de un arranque. El script lo detecta (la imagen de destino no tiene la comprobación de salud profunda),
restaura primero el borde anterior y después recrea el servidor.

## Volver atrás

```bash
scripts/deploy.sh --rollback
```

Reinstala, con el mismo relevo y sin corte, las imágenes que estaban en uso antes del último despliegue (se
guardan etiquetadas como `dallycontrol-rollback/*` y en `.deploy-state/`, para que una limpieza de imágenes
no las borre) y restaura el `.env` de entonces.

**La base de datos no vuelve atrás.** Las migraciones de la versión nueva ya se aplicaron. Por eso:

- escriba las migraciones de forma que la versión anterior siga funcionando con ellas: **agregar** tablas y
  columnas (con valor por defecto o que admitan nulo) en una versión, y **quitar** lo viejo en una posterior,
  nunca en la misma;
- la misma regla hace posible el relevo: durante unos segundos conviven la versión anterior y la nueva sobre
  la misma base;
- si una migración dañó datos, la salida es el respaldo de `/backups/predeploy`
  (`RUNBOOK.md`, «Restaurar desde un respaldo»), sabiendo que se pierde lo ocurrido desde el despliegue.

## Límites conocidos

- **Dos instancias a la vez durante el relevo** necesitan memoria y **CPU** para dos servidores durante uno o
  dos minutos. Con el servidor saturado el relevo llegó a fallar (ver «Mediciones»). Revise `uptime` y
  `docker stats` antes; si no hay margen, espere a una hora de menos carga o use `--classic` en ventana.
- **Tareas internas del servidor** (por ejemplo, las programadas) corren en las dos instancias durante el
  solape. Son operaciones repetibles; no se observó ningún efecto en las pruebas, pero no se probó cada tarea.
- **Actualizaciones de un clic del supervisor** (instalaciones con imágenes publicadas) siguen usando el
  método anterior (`apply.sh` recrea servidor y borde): tienen corte. En esas instalaciones también se puede
  usar `scripts/deploy.sh`, que descarga las imágenes en lugar de construirlas.
- Tras una actualización «en caliente» de la consola, el contenedor del borde sigue declarando la imagen con
  la que se creó; un `docker compose up -d` posterior lo recreará (corte de unos segundos, 0,6 s en la
  prueba). Use siempre `scripts/deploy.sh`.
- El disco: la construcción de imágenes necesita varios GB libres. El script avisa por encima del 90 %.
