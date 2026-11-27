# Informe posterior al incidente — dos cortes causados por despliegues (octubre–noviembre de 2026)

> Los datos marcados **[completar]** no están en el repositorio ni en los registros disponibles al escribir
> este informe; debe completarlos quien operó el servidor esos días (registros de Docker y de Cloudflare).
> Lo demás sale del historial de cambios y de las pruebas hechas al construir las correcciones.

## Resumen

| | |
|---|---|
| **Título** | Dos despliegues dejaron el servicio sin atender: uno por un error de código que impedía arrancar al servidor (~11 min) y otro por recrear el borde durante la actualización |
| **Fechas** | Incidente A: 2 de octubre de 2026. Incidente B: último despliegue, noviembre de 2026 **[completar fecha]** |
| **Severidad** | P1 (servicio no disponible) |
| **Duración** | A: ~11 minutos. B: **[completar]** (segundos a pocos minutos) |
| **Impacto** | Consola y API sin responder. Los equipos no pudieron reportar ni recibir comandos; al volver el servicio se pusieron al día solos. Sin pérdida de datos. |
| **Detectado por** | Personal propio, al verificar el despliegue. **No había monitoreo ni alertas.** |
| **Estado** | Resuelto. Correcciones de fondo construidas y probadas; pendiente activarlas en producción. |

**En tres frases:** el servicio se actualizaba reemplazando en caliente el único servidor y el único borde,
sin comprobar antes que la versión nueva arrancaba y sin forma automática de volver atrás. Un error que
cualquier arranque de prueba habría mostrado llegó a producción, y en otro despliegue el reinicio del borde
dejó al túnel sin destino. Ahora la versión nueva arranca **al lado** de la anterior y solo recibe tráfico
cuando está saludable; si no arranca, nadie lo nota.

## Impacto

- **A:** ~11 minutos sin consola ni API para todos los usuarios y equipos.
- **B:** el acceso por el túnel respondió con error mientras el borde (Caddy) se recreaba y el conector
  (cloudflared) no tenía a quién entregar las peticiones («connection refused»). **[completar duración]**
- Datos: ninguna pérdida. Los comandos pendientes se entregaron al reconectar los equipos.
- Con un objetivo del 99,5 % mensual (3 h 36 min), los dos incidentes consumen menos del 10 % del margen
  del mes. No se medía la disponibilidad: la cifra es una estimación.

## Línea de tiempo

### Incidente A — 2 de octubre de 2026 (hora de Colombia)

| Hora | Hecho |
|---|---|
| **[completar]** | Se despliega (`git pull && ./setup.sh`) una versión en la que, al editar `AgentCommandMapper`, una anotación de consulta de MyBatis quedó encima de un método que no era el suyo. |
| +0 | `setup.sh` recrea el servidor. El servidor nuevo no termina de arrancar: MyBatis falla al cargar los mapeadores. El servidor anterior ya fue detenido. |
| +0 a +11 min | La consola muestra la página de recuperación; los equipos reciben errores y reintentan. Se revisa el registro del servidor, se identifica la anotación y se corrige. |
| 18:36 | Corrección registrada: `fix: mapper annotations back on their own methods (server failed to start)` (7262ffb). |
| ~+11 min | Se despliega la corrección; el servicio vuelve. |

### Incidente B — último despliegue **[completar fecha y horas]**

| Hora | Hecho |
|---|---|
| +0 | `./setup.sh` reconstruye las imágenes y ejecuta `docker compose up -d`, que recrea servidor, borde (Caddy) y demás servicios cuya imagen cambió. |
| +0 | Mientras Caddy se recrea, cloudflared sigue recibiendo peticiones de Cloudflare y no puede entregarlas: `connection refused` hacia `caddy:80`. Los usuarios ven un error de Cloudflare. |
| **[completar]** | Caddy termina de arrancar y el tráfico vuelve. |

## Causa

**Causas directas**

- A: una anotación `@Update` separada de su método en un mapeador. Compila sin error; falla al arrancar.
- B: recrear el único borde mientras el túnel le enviaba tráfico.

**Causas de fondo** (comunes a los dos)

1. **Despliegue por reemplazo.** Se detenía la versión en uso antes de saber si la nueva funcionaba. No
   había una segunda instancia que tomara el relevo.
2. **Ninguna verificación de arranque antes de producción.** Las pruebas automáticas no cargaban los
   mapeadores contra una base de datos, así que un error de arranque solo aparecía en el servidor real.
3. **Sin vuelta atrás automática.** Recuperarse dependía de que una persona diagnosticara y corrigiera en
   caliente.
4. **Sin monitoreo ni alertas.** `/healthz` existía, pero nadie lo consultaba; además no comprobaba la base
   de datos, y el contenedor del servidor no tenía comprobación de salud.
5. **El despliegue recreaba todo lo que hubiera cambiado**, incluido el borde, aunque solo cambiaran los
   archivos de la consola.

**Hallazgo relacionado: deriva del esquema de la base de datos.** Al levantar una instalación nueva para
probar las correcciones, el servidor no arrancaba: varias tablas, columnas y permisos existían en producción
porque se habían creado **a mano**, pero ninguna migración los creaba (`device_alert`, `device_data_sharing`,
`config_audit`, `savedScript`, columnas de `settings`, `groups` y `devices`, el permiso `manage_scripts`), y
dos migraciones buscaban roles por un nombre que en una base nueva todavía no existe. Producción funcionaba;
una instalación nueva —o **una restauración en un servidor nuevo tras un desastre**— no. Es la misma causa
de fondo n.º 2: nada probaba el arranque desde cero.

## Qué funcionó y qué no

- Funcionó: la página de recuperación del supervisor siguió disponible en A; los equipos reintentaron solos y
  no se perdió ningún dato; el diagnóstico de A fue rápido porque el registro del servidor decía qué mapeador
  fallaba.
- No funcionó: enterarse dependió de que alguien estuviera mirando; no había a qué volver sin corregir el
  código; no había medición del tiempo sin servicio.
- Tuvimos suerte en: ambos despliegues se hicieron con alguien pendiente del resultado; el error de A era
  evidente en el registro.

## Acciones

Todas las acciones «Hecho» están en el repositorio y fueron probadas en un entorno local completo (ver
`DESPLIEGUE.md`, `MONITOREO.md`, `RESPALDOS.md`). «Pendiente» significa que falta activarlas en producción
o una decisión del dueño.

| # | Acción | Tipo | Estado |
|---|---|---|---|
| 1 | Despliegue con relevo (`scripts/deploy.sh`): la versión nueva arranca junto a la anterior y solo recibe tráfico cuando está saludable. Probado: un despliegue con la falla del incidente A termina en «vuelta atrás automática» sin una sola petición fallida. | Prevenir | Hecho; pendiente usarlo en producción |
| 2 | Vuelta atrás automática si la versión nueva no queda saludable, y `scripts/deploy.sh --rollback`. | Mitigar | Hecho |
| 3 | La consola se actualiza sin recrear el borde; el borde solo se recrea si cambia Caddy. cloudflared espera a que Caddy esté saludable. | Prevenir | Hecho |
| 4 | Segundo conector del túnel (`cloudflared-2`) para poder reiniciar uno sin corte. | Prevenir | Hecho; pendiente activar el perfil |
| 5 | Salud real: `/healthz/deep` comprueba la base de datos; comprobación de salud en los contenedores del servidor y del borde. | Detectar | Hecho |
| 6 | Monitor con alertas por correo y webhook/Telegram cada minuto, y página de estado con disponibilidad mensual. | Detectar | Hecho; pendiente elegir canal y destinatarios |
| 7 | Verificación automática de deriva (`scripts/schema-drift-check.sh`): crea una base vacía con las migraciones, carga todos los mapeadores como el servidor y hace que PostgreSQL analice cada consulta; opcionalmente compara contra el esquema y los permisos reales de producción. Habría detenido A y detectó toda la deriva descrita arriba. | Prevenir | Hecho; pendiente añadirla a la integración continua |
| 8 | Migraciones que crean lo que solo existía en producción (idénticas a producción, sin efecto allí). Comparación final: 968 objetos de esquema y 81 permisos de rol, cero diferencias. | Prevenir | Hecho |
| 9 | Regla: **ningún cambio de esquema a mano en producción**; todo cambio entra como migración. Antes de cada despliegue se ejecuta la comparación contra producción (`RUNBOOK.md`, «Antes de desplegar»). | Prevenir | Pendiente: adoptarla como norma |
| 10 | Respaldo verificado antes de cada despliegue, respaldo diario con copia externa y prueba de restauración. | Mitigar | Hecho; pendiente elegir destino externo |
| 11 | SLA y runbook escritos; guardia definida para P1. | Proceso | Documentos hechos; pendiente confirmar valores y personas |
| 12 | Primera actualización en producción con el mecanismo nuevo: todavía tiene el corte clásico (el borde en ejecución aún no conoce el relevo). Hacerla en ventana de mantenimiento, con aviso. | Mitigar | Pendiente |

## Comunicación

**[completar]** Qué se informó al cliente en cada incidente y por qué canal. Texto propuesto para el cierre:

> El 2 de octubre y en la actualización de noviembre el servicio estuvo sin responder durante unos minutos
> mientras se instalaba una versión nueva. No se perdió información y los equipos se pusieron al día solos.
> La causa fue la forma de actualizar: se reemplazaba el servidor en uso sin comprobar antes la versión
> nueva. Cambiamos el procedimiento: ahora la versión nueva se enciende junto a la anterior y solo empieza a
> atender cuando demuestra estar sana; si no lo está, la actualización se cancela sola sin afectarlos.
> Además, el servicio se vigila cada minuto y pueden consultar su estado en `https://<dominio>/estado`.
