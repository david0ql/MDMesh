# Runbook de incidentes de DallyControl

Para quien está de guardia. Cada sección: **síntoma → confirmar → actuar → verificar**. Todos los comandos se
ejecutan en el servidor, en el directorio de la instalación (donde está `.env`).

## Primeros 5 minutos (cualquier incidente)

1. **Confirme y clasifique** (P1–P4 según `SLA.md`). Mire `https://<dominio>/estado` y el aviso recibido.
2. **Avise** que lo está atendiendo (canal de guardia; al cliente si es P1/P2). Anote la hora.
3. **Ubique la capa que falla:**

   ```bash
   docker compose ps                                   # ¿qué contenedor no está "healthy" / "Up"?
   curl -s -o /dev/null -w '%{http_code}\n' https://<dominio>/healthz/edge   # borde y túnel
   curl -s https://<dominio>/healthz/deep              # servidor + base de datos
   df -h / ; docker system df                          # disco
   ```

   | `/healthz/edge` | `/healthz/deep` | Vaya a |
   |---|---|---|
   | no responde / error de Cloudflare | — | [Caída del túnel](#caída-del-túnel) o [del servidor completo](#caída-del-servidor-completo) |
   | 200 | 503 con `"db":"fail"` | [Caída de la base de datos](#caída-de-la-base-de-datos) |
   | 200 | 503 sin cuerpo JSON del servidor, o página de recuperación | [El servidor de aplicación no responde](#el-servidor-de-aplicación-no-responde) |
   | 200 | 200 | [Los equipos no reportan](#los-equipos-no-reportan) |

4. **¿Hubo un despliegue en la última hora?** Si sí, lo primero es [volver atrás](#volver-atrás-un-despliegue).
5. **No borre nada** para «limpiar» (volúmenes, contenedores, la base de datos). Si duda, pare y escale.

Al cerrar: aviso de recuperación al cliente, y para P1 el informe posterior (`POSTMORTEM-PLANTILLA.md`).

---

## Caída del servidor completo

**Síntoma:** nada responde; no se puede entrar por SSH, o el servidor reinició.

1. Si no hay SSH: consola del proveedor del servidor (reinicio, estado del disco y de la red). Escale al
   responsable de infraestructura: es un servidor compartido con otros productos.
2. Cuando vuelva, Docker arranca solo los contenedores (`restart: unless-stopped`). Compruebe:

   ```bash
   docker compose ps
   docker compose logs --tail 50 server
   ```

3. Si algún servicio no arrancó: `docker compose up -d` (no recrea lo que ya está bien).
4. Si el servidor no va a volver en el plazo del RTO:
   - con servidor en espera: [conmutación](#conmutar-al-servidor-en-espera);
   - sin él: [restaurar en un servidor nuevo](#restaurar-desde-un-respaldo) (`RESPALDOS.md`, «desastre total»).

**Verificar:** `/healthz/deep` 200; en la consola, los equipos vuelven a reportar en 15–20 minutos.

## El servidor de aplicación no responde

**Síntoma:** la consola muestra la página de recuperación; `/healthz/deep` 503; `docker compose ps` muestra
`server` como `unhealthy`, reiniciando o detenido.

```bash
docker compose ps server
docker compose logs --tail 100 server            # busque "ERROR", "Caused by", "OutOfMemory"
docker inspect -f '{{.State.OOMKilled}} {{.RestartCount}}' "$(docker compose ps -q server)"
```

| Lo que ve | Qué hacer |
|---|---|
| Acaba de haber un despliegue | [Volver atrás](#volver-atrás-un-despliegue) |
| `OOMKilled true` o `OutOfMemoryError` | Suba `SERVER_MEM_LIMIT` (y `SERVER_JAVA_OPTS=-XX:MaxRAMPercentage=60`) en `.env`, luego `docker compose up -d server` |
| Error de conexión a la base de datos | [Caída de la base de datos](#caída-de-la-base-de-datos) |
| Error de Liquibase / migración al arrancar | No reintente a ciegas. Vuelva a la versión anterior y escale a desarrollo |
| Colgado sin errores (no responde, CPU al 100 % o 0 %) | `docker compose restart server` (corte de un arranque: ~20–40 s) |

**Verificar:** `docker compose ps server` → `healthy`; `/healthz/deep` 200.

## Caída de la base de datos

**Síntoma:** `/healthz/deep` → `{"status":"down","checks":{"db":"fail",…}}`; la consola carga pero nada
funciona; alerta «Servicio interno».

```bash
docker compose ps postgres
docker compose logs --tail 80 postgres
docker compose exec postgres df -h /var/lib/postgresql/data     # si el contenedor sigue arriba
```

| Lo que ve | Qué hacer |
|---|---|
| Contenedor detenido | `docker compose up -d postgres`; el servidor se reconecta solo (medido: 9 s tras volver la base) |
| `No space left on device` | [Disco lleno](#disco-lleno) primero; después `docker compose up -d postgres` |
| `too many connections` | `docker compose restart server` libera las conexiones; investigue qué las agotó |
| `PANIC`, `could not read block`, `invalid page`, no arranca | **Posible corrupción. No borre el volumen.** Copie el volumen tal como está si hay espacio, y [restaure](#restaurar-desde-un-respaldo) o [conmute](#conmutar-al-servidor-en-espera) |
| `OOMKilled` | Suba `POSTGRES_MEM_LIMIT` |

**Verificar:** `docker compose exec postgres pg_isready`; `/healthz/deep` 200; llega el aviso de recuperación.

## Caída del túnel

**Síntoma:** error de Cloudflare (1033, 530, 502) desde fuera; desde el servidor todo está bien; alerta
«Acceso público» y/o «Túnel Cloudflare» sin alerta de «Servicio interno».

```bash
docker compose ps cloudflared cloudflared-2 caddy
docker compose logs --tail 60 cloudflared
docker compose exec server curl -s -o /dev/null -w '%{http_code}\n' http://caddy:80/healthz/edge   # ¿el borde responde por dentro?
```

| Lo que ve | Qué hacer |
|---|---|
| `cloudflared` detenido o en bucle de reinicio | `docker compose up -d cloudflared`. Si el registro dice token inválido: revise `TUNNEL_TOKEN` en `.env` contra el panel |
| `connection refused` hacia `caddy:80` | El borde está caído: `docker compose up -d caddy`; mire `docker compose logs --tail 60 caddy` |
| Conector conectado, error solo desde fuera | Estado de Cloudflare (cloudflarestatus.com); DNS del dominio en el panel |
| Un conector de dos caído | No hay corte. Reinícielo cuando convenga: `docker compose up -d --no-deps cloudflared-2` |

Si el problema es de Cloudflare y se prolonga: ruta directa (`TUNEL.md`, sección 2), si se dejó preparada.

**Verificar:** `curl https://<dominio>/healthz/deep` desde **fuera** del servidor (un teléfono con datos).

## Disco lleno

**Síntoma:** alerta «Espacio en disco»; errores `No space left on device`; la base de datos se detiene.

```bash
df -h /
docker system df                                   # imágenes, contenedores, volúmenes, caché de construcción
docker compose run --rm backup du -sh /backups/* /backups/scheduled/* 2>/dev/null
```

Libere, de menor a mayor riesgo:

1. Caché de construcción e imágenes sin uso (seguro; no toca nada en ejecución):

   ```bash
   docker builder prune -f
   docker image prune -f
   ```

   `docker image prune -a` borra también las imágenes de la versión anterior (`dallycontrol-rollback/*` se
   conservan porque están etiquetadas). **Nunca** use `docker system prune --volumes` ni `docker volume prune`:
   borran datos.
2. Registros de contenedores de otros productos que crecieron sin límite (coordine con su responsable).
3. Bases guardadas por restauraciones anteriores: `docker compose exec postgres psql -U dallycontrol -l`
   y `DROP DATABASE dallycontrol_before_…;` de las que ya no hagan falta.
4. Respaldos locales: baje `BACKUP_KEEP_DAILY` **solo si** la copia externa está al día
   (`cat` de `/backups/scheduled/last_success.json` → `"remoteOk":true`).

Es un servidor compartido: si lo que llena el disco es de otro producto, escale a su responsable.

**Verificar:** uso por debajo del 80 %; `docker compose ps` todo arriba; llega el aviso de recuperación.

## Restaurar desde un respaldo

**Cuándo:** pérdida o corrupción de datos; borrado masivo por error; migración que dejó la base inservible.
Decisión de P1: se pierden los cambios posteriores al respaldo. Confirme con el líder técnico.

```bash
scripts/restore-backup.sh list
scripts/restore-backup.sh verify <respaldo>          # primero la prueba: no toca el servicio
scripts/restore-backup.sh apply <respaldo> --yes     # la real: detiene el servidor mientras dura
```

- La base en uso **no se borra**: queda como `dallycontrol_before_<fecha>`. Si la restauración fue un error,
  se vuelve atrás renombrando (`RESPALDOS.md`, «Restaurar»).
- Con archivos (APKs): agregue `--with-files`.
- Respaldo previo al último despliegue: está en `/backups/predeploy`
  (`docker compose run --rm -e BACKUP_DIR=/backups/predeploy backup /ops/restore.sh list`).
- Servidor nuevo: `RESPALDOS.md`, «Restaurar en un servidor nuevo».

Tiempo medido en pruebas: segundos para bases pequeñas. **Anote aquí el de producción** la primera vez que
ejecute `verify`: `______ s` (fecha: `______`).

**Verificar:** el script termina con «Servicio restaurado y saludable»; entre a la consola y confirme que los
equipos y políticas son los esperados; los equipos se reconectan solos.

## Volver atrás un despliegue

**Cuándo:** algo falla después de actualizar.

- Si la versión nueva **no arrancó**, `scripts/deploy.sh` ya volvió atrás solo y lo dijo
  («VUELTA ATRÁS AUTOMÁTICA»). El servicio nunca dejó la versión anterior. Solo queda leer el error.
- Si arrancó pero **funciona mal**:

  ```bash
  scripts/deploy.sh --rollback
  ```

  Vuelve a las imágenes de antes del último despliegue con el mismo relevo (sin corte). Medido: 0 peticiones
  fallidas.

**La base de datos no retrocede.** Las migraciones de la versión nueva ya se aplicaron. Casi siempre son
compatibles con la versión anterior (agregan tablas o columnas). Si la versión anterior no arranca contra el
esquema nuevo, o la versión nueva dañó datos: [restaure](#restaurar-desde-un-respaldo) el respaldo de
`/backups/predeploy`, sabiendo que se pierde lo ocurrido desde el despliegue.

Si el despliegue se hizo con `./setup.sh` (método anterior) no hay imágenes guardadas: vuelva al commit
anterior (`git checkout <commit>`) y ejecute `scripts/deploy.sh`.

**Verificar:** `/healthz/deep` 200; la consola muestra la versión anterior; `docker compose ps` sin `server-b`.

## Los equipos no reportan

**Síntoma:** alerta «Equipos reportando»; la consola muestra muchos equipos sin conexión; el servicio
responde bien.

1. **¿Cuántos y cuáles?** Todos, una carpeta, un operador móvil, una versión del agente. En la consola,
   ordene por última conexión.
2. **¿El camino de los equipos funciona?** Desde un teléfono con datos móviles (no la Wi-Fi de la oficina):
   `https://<dominio>/healthz/deep` debe responder. Pruebe también con otro operador.
3. **¿Llegan peticiones de agentes?**

   ```bash
   docker compose logs --since 10m caddy  | grep -c '/rest/public/agent/v1/'
   docker compose logs --since 10m server | grep -ci 'error\|exception'
   ```

   | Lo que ve | Causa probable | Qué hacer |
   |---|---|---|
   | Cero peticiones de agentes, servicio sano | DNS, certificado, túnel o regla de Cloudflare (WAF, «modo bajo ataque», bloqueo por país) | Revise el panel de Cloudflare → Security → Events. Compruebe el certificado: `curl -vI https://<dominio>/` |
   | Peticiones con respuesta 401/403 | Cambió `HASH_SECRET` o se restauró una base antigua | Compare `HASH_SECRET` con el de la copia segura de `.env`. **Nunca lo regenere** |
   | Peticiones con 5xx | Error del servidor | [El servidor de aplicación no responde](#el-servidor-de-aplicación-no-responde) |
   | Solo faltan los de un operador o zona | Red móvil | Confirme con otro operador; informe al cliente |
   | Solo faltan los de una versión del agente | Defecto del agente o actualización en curso | Escale a desarrollo con dos números de equipo de ejemplo |
   | Empezó tras un despliegue | Cambio en la API | [Volver atrás](#volver-atrás-un-despliegue) |

4. Recuerde que el agente reporta cada 15 minutos y reintenta solo con espera creciente (hasta 60 s para el
   canal inmediato): tras arreglar la causa, la flota tarda hasta 15–20 minutos en verse completa.
5. Un equipo aislado sin reportar no es un incidente del servicio: batería, sin datos, apagado.

**Verificar:** la proporción de equipos en línea vuelve a su nivel habitual; llega el aviso de recuperación.

## Conmutar al servidor en espera

Solo si existe (`ALTA-DISPONIBILIDAD.md`) y el principal no va a volver a tiempo.

1. **Asegúrese de que el principal está realmente fuera** (apagado, o `docker compose stop` en él). Dos
   servidores atendiendo a la vez dañan los datos.
2. En el servidor en espera: `scripts/ha-status.sh` (anote el retraso) y `scripts/ha-promote.sh --yes`.
3. Con túnel, el tráfico llega solo al nuevo servidor. Con ruta directa, cambie el DNS.
4. **No arranque el servidor antiguo tal como quedó.** Reconstrúyalo como réplica nueva.

**Verificar:** `/healthz/deep` 200 desde fuera; `scripts/ha-status.sh` dice «PRIMARIO».

---

## Antes de desplegar

Lista corta que evita los incidentes ya vividos (`POSTMORTEM-2026-10-despliegues.md`):

1. **Deriva de esquema.** Con un volcado de solo esquema y la matriz de permisos de producción:

   ```bash
   # en producción (solo lectura):
   docker compose exec -T postgres pg_dump --schema-only --no-owner --no-privileges -U dallycontrol dallycontrol > prod-schema.sql
   docker compose exec -T postgres psql -U dallycontrol -d dallycontrol -At -F, -c "SELECT urp.roleid, p.name FROM userrolepermissions urp JOIN permissions p ON p.id = urp.permissionid WHERE urp.roleid IN (1,2,3,100,200) ORDER BY 1,2" > prod-roles.csv
   # en el equipo de desarrollo, sobre el código que se va a desplegar:
   scripts/schema-drift-check.sh --compare prod-schema.sql --compare-roles prod-roles.csv
   ```

   Comprueba que una base creada solo con las migraciones sirve a todas las consultas del servidor (lo que
   habría detenido el incidente del 2 de octubre) y que tiene todo lo que tiene producción.
   - «SOLO EN EL VOLCADO»: alguien cambió producción a mano. Escriba la migración (idempotente) antes de
     desplegar. **Nunca se altera el esquema de producción a mano.**
   - «solo en una instalación nueva»: migraciones que producción aún no corrió; es lo esperado.
   - Los archivos del volcado no se suben al repositorio.
2. `scripts/backup-restore-test.sh` si se tocó algo de respaldos.
3. Espacio en disco por debajo del 85 % (la construcción de imágenes necesita varios GB).
4. Despliegue con `scripts/deploy.sh`, no con `./setup.sh`. Mida el corte si quiere evidencia:
   `scripts/measure-downtime.sh https://<dominio>/healthz/deep 600 &`

## Datos de guardia **[completar]**

| | |
|---|---|
| Servidor (acceso, proveedor, consola de emergencia) | |
| Panel de Cloudflare (cuenta, quién tiene acceso) | |
| Dónde está la copia segura de `.env` y la frase de cifrado de respaldos | |
| Destino externo de respaldos | |
| Canal de alertas y quién lo recibe | |
| Líder técnico / gerencia (escalamiento) | |
| Contacto del cliente para avisos | |
| Tiempo medido de restauración en producción | |
