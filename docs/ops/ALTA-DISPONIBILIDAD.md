# Alta disponibilidad: servidor en espera

Hoy todo corre en un servidor. Si ese servidor se pierde, el servicio vuelve cuando se consiga otro y se
restaure el respaldo: horas, con hasta 24 horas de datos perdidos. Esta guía describe cómo tener un
**segundo servidor listo**, con una copia continua de la base de datos, para volver en minutos y sin perder
datos. Es lo que permite comprometer un 99,9 % en el SLA.

## Opciones

| Opción | Pérdida de datos (RPO) | Vuelta al servicio (RTO) | Costo y esfuerzo |
|---|---|---|---|
| **A. Solo respaldos** (situación con el perfil `backup`) | hasta 24 h | horas (conseguir servidor + restaurar) | ninguno adicional |
| **B. Servidor en espera con réplica de PostgreSQL** (esta guía) | segundos | minutos (conmutación manual con un comando) | un segundo servidor pequeño + enlace privado |
| **C. Base de datos gestionada** (RDS, Cloud SQL, Neon, Supabase…) | segundos (la gestiona el proveedor) | minutos para la base; el servidor de aplicación sigue siendo uno | mensualidad del servicio; la base sale del servidor compartido |

B y C se pueden combinar (base gestionada + segundo servidor de aplicación). La conmutación es **manual** a
propósito: con dos servidores, una conmutación automática mal decidida deja dos bases de datos aceptando
escrituras y divergiendo, que es peor que unos minutos de corte.

## B. Servidor en espera con replicación continua

```
        Cloudflare (túnel, mismo token)
              │                      ┆ (solo tras conmutar)
   ┌──────────▼──────────┐    ┌──────▼──────────────┐
   │ PRINCIPAL           │    │ EN ESPERA           │
   │ cloudflared, caddy  │    │ (todo detenido)     │
   │ server              │    │                     │
   │ postgres (primario) ├────► postgres (réplica)  │
   └─────────────────────┘ WAL└─────────────────────┘
        enlace privado (WireGuard / VPN / VLAN)
```

- El servidor en espera solo ejecuta PostgreSQL, como réplica de solo lectura que recibe cada cambio en
  cuanto ocurre (replicación por flujo).
- Los **archivos** (APKs) no viajan por la replicación: se copian con el destino externo de los respaldos o
  con un rsync periódico (abajo).
- Al conmutar, la réplica se promueve a primaria y se arranca allí el stack completo. Con el túnel de
  Cloudflare **no hay que cambiar el DNS**: el conector del servidor en espera usa el mismo token.

### Requisitos

- Segundo servidor Linux con Docker, **en otro centro de datos o proveedor**. Tamaño: el de PostgreSQL más
  el servidor de aplicación (mida en producción; como referencia, 2 núcleos y 4 GB).
- Enlace privado entre los dos (WireGuard es suficiente). **El puerto de PostgreSQL nunca se publica en
  internet.**
- El mismo repositorio en la misma versión, y una copia de `.env` del principal.

### Preparar el principal

```ini
# .env del principal: agregue
COMPOSE_FILE=docker-compose.yml:docker-compose.ha-primary.yml
PG_REPL_BIND=10.8.0.1            # su dirección en el enlace privado
REPLICATION_PASSWORD=<openssl rand -hex 24>
```

```bash
docker compose up -d postgres    # recrea PostgreSQL UNA vez: ~10 s de base de datos reiniciando. Hágalo en ventana.
scripts/ha-primary-setup.sh 10.8.0.2/32     # usuario de replicación, ranura y regla de acceso (repetible)
```

### Preparar el servidor en espera

```ini
# .env del servidor en espera: copia del principal, y además
COMPOSE_FILE=docker-compose.yml:docker-compose.ha-standby.yml
PRIMARY_HOST=10.8.0.1
REPLICATION_PASSWORD=<la misma>
# quite PG_REPL_BIND y docker-compose.ha-primary.yml de esta copia
```

```bash
docker compose build                 # deja las imágenes listas para el día que hagan falta
docker compose up -d postgres        # SOLO postgres: clona el principal y se queda siguiéndolo
scripts/ha-status.sh
```

> En el servidor en espera **no** ejecute `docker compose up -d` a secas antes de una conmutación: el
> servidor de aplicación fallaría contra una base de solo lectura y, peor, un `cloudflared` allí recibiría
> parte del tráfico real.

Archivos: programe en el servidor en espera una copia periódica, por ejemplo cada 15 minutos con cron,

```bash
rsync -a --delete usuario@10.8.0.1:/var/lib/docker/volumes/dallycontrol_dallycontroldata/_data/files/ \
      /var/lib/docker/volumes/dallycontrol_dallycontroldata/_data/files/
```

o restáurelos del destino externo de respaldos al conmutar (`scripts/restore-backup.sh files`).

### Vigilar la réplica

`scripts/ha-status.sh` en cualquiera de los dos servidores. Sale con error si la replicación está rota (un
primario sin réplica conectada, o una réplica que no recibe): ejecútelo desde cron cada 5 minutos y avise si
falla. En el principal muestra también cuánto WAL retiene la ranura; `max_slot_wal_keep_size` (4 GB por
defecto) impide que una réplica muerta llene el disco del principal: pasado ese límite la réplica queda
inservible y hay que volver a clonarla, pero el principal sigue.

### Conmutación (el principal se perdió)

1. Confirme que el principal está realmente fuera de servicio. Si aún responde y lo va a retirar, deténgalo:
   `docker compose stop` en el principal.
2. En el servidor en espera:

   ```bash
   scripts/ha-status.sh          # anote el retraso: es lo que se pierde
   scripts/ha-promote.sh --yes
   ```

   El script se niega si el principal todavía responde (protección contra dos primarios); `--force` lo
   omite cuando el principal está aislado de los usuarios pero aún visible por el enlace privado.
   Promueve la réplica, arranca el stack completo y espera a que el servidor esté saludable.
3. Compruebe desde fuera `https://<dominio>/healthz/deep`. Con ruta directa en vez de túnel, cambie el DNS.
4. Quite del `.env` del nuevo principal `docker-compose.ha-standby.yml` y `PRIMARY_HOST` cuando haya calma
   (no afecta mientras tanto: el directorio de datos ya es un primario normal).

### Volver a tener réplica

El servidor antiguo, cuando vuelva, tiene una base que ya no sirve (se quedó atrás). **No lo arranque como
estaba.** Conviértalo en el nuevo servidor en espera:

1. En el servidor antiguo: `docker compose down` y borre **solo** su volumen de base de datos
   (`docker volume rm <proyecto>_pgdata`).
2. En el nuevo principal: agregue `docker-compose.ha-primary.yml`, `PG_REPL_BIND`, recree postgres y ejecute
   `scripts/ha-primary-setup.sh`.
3. En el antiguo: configure `.env` como servidor en espera y `docker compose up -d postgres`.

Para volver al servidor original como principal, repita la conmutación en sentido contrario en una ventana
de mantenimiento, deteniendo antes el principal (así el retraso es cero y no se pierde nada).

### Prueba realizada

Ejecutada el 27 de noviembre de 2026 con los archivos de este repositorio, dos proyectos de compose
independientes en una misma máquina (cada uno con su `.env`, como dos servidores) y el servidor de
aplicación real:

| Paso | Resultado medido |
|---|---|
| `docker compose config` de las dos combinaciones de archivos | válidas |
| Clonado inicial de la réplica (base recién instalada + 500 equipos) y entrada en replicación | 4 s |
| Estado tras clonar | `streaming`, 0 bytes de retraso, ranura activa |
| Una escritura en el principal aparece en la réplica | en menos de 0,7 s (incluye el tiempo de las consultas de la prueba) |
| Escribir en la réplica | rechazado (`read-only transaction`) |
| `ha-promote.sh` con el principal todavía vivo | **se niega** y explica por qué |
| Caída brusca del principal (`kill`) inmediatamente después de una escritura, y `ha-promote.sh --yes` | réplica promovida y aceptando escrituras en 1 s |
| Servicio completo saludable en el servidor en espera (servidor, borde, supervisor) | **21 s** desde la caída del principal |
| Datos tras conmutar | los 502 registros presentes, **incluida la última escritura** hecha un segundo antes de la caída; el nuevo principal acepta escrituras; `/rest/public/health` responde `ok` |

Esos 21 segundos son solo el trabajo técnico, en una máquina sin carga y con una base pequeña. En un
incidente real el tiempo lo domina **decidir** conmutar (detectar, confirmar que el principal no vuelve): el
RTO realista con servidor en espera es de 15 minutos, no de 21 segundos.

Lo que la prueba **no** cubre y debe verificarse al montar el segundo servidor real: el enlace privado entre
dos centros de datos (latencia, cortes), el registro del conector del túnel en el servidor en espera con el
token real, y la copia de archivos. Haga un simulacro de conmutación completo antes de darlo por bueno, y
repítalo cada seis meses.

## C. Base de datos gestionada

Saca PostgreSQL del servidor compartido: el proveedor se encarga de la réplica, los respaldos continuos y la
conmutación de la base.

1. Cree una instancia PostgreSQL 17, con respaldo continuo y, si el plan lo permite, réplica en otra zona.
2. Migre los datos: `scripts/restore-backup.sh` no aplica aquí; use
   `pg_restore --no-owner -d "postgresql://usuario:clave@host:5432/dallycontrol" <respaldo.dump>` con el
   servidor de aplicación detenido.
3. Apunte el servidor a la base externa con un archivo de compose adicional (por ejemplo
   `docker-compose.managed-db.yml`, que debe crear en su instalación):

   ```yaml
   services:
     server:
       environment:
         DB_HOST: ${EXTERNAL_DB_HOST:?}
         DB_PORT: ${EXTERNAL_DB_PORT:-5432}
       depends_on: !reset {}
     server-b:                    # la instancia de relevo de los despliegues
       environment:
         DB_HOST: ${EXTERNAL_DB_HOST:?}
         DB_PORT: ${EXTERNAL_DB_PORT:-5432}
       depends_on: !reset {}
     backup:
       depends_on: !reset {}
     postgres:
       profiles: ["local-db"]     # el postgres local deja de arrancar
   ```

   y `MONITOR_DB_HOST` / `BACKUP_DB_HOST` con el mismo host, para que el monitor y los respaldos propios
   sigan funcionando contra la base gestionada.

Consideraciones: la conexión debe ir cifrada y por red privada o con lista de IP permitidas; la latencia
entre el servidor y la base afecta a cada petición (elija la misma región); el servidor de aplicación sigue
siendo un punto único, salvo que además monte el servidor en espera (que entonces solo necesita el stack de
aplicación, sin réplica propia).

**Esta opción no se probó de punta a punta**: depende del proveedor que se elija. El fragmento de compose se
validó con `docker compose config` (con y sin los perfiles `relay`, `backup` y `monitor`), pero no se ejecutó
contra una base gestionada real: pruébelo en un entorno de pruebas antes de usarlo.

## Qué elegir

- Si el objetivo es cumplir 99,5 % y aceptar hasta 24 h de pérdida en un desastre: **A** (respaldos con
  copia externa) es suficiente. Es lo mínimo y hay que activarlo ya.
- Si el cliente exige 99,9 % o no acepta perder un día de datos: **B**.
- Si además se quiere dejar de depender del servidor compartido para la base de datos: **C**, sola o con B.
