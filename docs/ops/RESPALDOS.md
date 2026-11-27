# Respaldos y restauración

## Qué se respalda

| Qué | Cómo | Dónde queda |
|---|---|---|
| **Base de datos** | `pg_dump` en formato comprimido, cada día | `db/dallycontrol-AAAAMMDD-HHMMSS.dump` (o `.dump.enc` si hay cifrado), con su `.sha256` y un `.manifest.json` con el número de filas de las tablas principales |
| **Archivos subidos** (APKs, íconos, archivos de configuración) | Instantánea diaria; los archivos que no cambian no ocupan espacio de nuevo (enlaces duros) | `files/AAAAMMDD-HHMMSS/` |
| **Configuración secreta** (`.env`, clave JWT) | Solo si hay cifrado activo | `config/config-…tar.gz.enc` |

Todo bajo `/backups/scheduled` en el volumen `backups`. El respaldo previo a cada despliegue va aparte, en
`/backups/predeploy`.

> **`.env` es parte del respaldo.** Contiene `HASH_SECRET`; sin él, los equipos ya enrolados no pueden
> volver a autenticarse y habría que enrolarlos de nuevo. Si no activa el cifrado, guarde una copia de `.env`
> en un gestor de secretos.

Cada respaldo se **verifica** al hacerse: el archivo debe poder leerse completo y contener datos de tablas;
se guarda su suma SHA-256; la copia de archivos debe tener el mismo número de archivos que el origen. Además,
según `BACKUP_VERIFY_RESTORE`, se **restaura de verdad** en una base temporal y se valida (por defecto, cada
domingo).

## Activar los respaldos programados

1. En `.env` (lista completa en `.env.example`):

   ```ini
   COMPOSE_PROFILES=cloudflare,remote,monitor,backup
   BACKUP_TIME=02:30
   BACKUP_ENCRYPTION_PASSPHRASE=<frase larga; guárdela FUERA del servidor>
   # destino fuera del servidor: una de las tres opciones de abajo
   MONITOR_BACKUP_MAX_AGE_HOURS=26
   ```

2. `docker compose up -d backup`
3. Haga el primero a mano y mire el resultado:
   `docker compose run --rm backup /ops/backup.sh`
4. Pruebe la restauración: `scripts/restore-backup.sh verify`

Con `MONITOR_BACKUP_MAX_AGE_HOURS=26`, el monitor avisa si pasa más de un día sin un respaldo correcto o si
la copia externa falla.

### Retención

Una sola regla para volcados e instantáneas: se conservan los **30 más recientes** (`BACKUP_KEEP_DAILY`), más
el último de cada una de las **8 últimas semanas** (`BACKUP_KEEP_WEEKLY`) y de los **12 últimos meses**
(`BACKUP_KEEP_MONTHLY`). En régimen son unos 43 respaldos. Probado: de 400 respaldos diarios consecutivos
quedan 43.

Espacio: un volcado comprimido ocupa mucho menos que la base de datos (en pruebas, 245 KB para una base
recién instalada). Vigile el volumen con la comprobación de disco del monitor; con el disco del servidor al
76 %, revise el tamaño real tras la primera semana: `docker compose run --rm backup du -sh /backups/scheduled/*`.

### Copia fuera del servidor

Un respaldo que vive en el mismo servidor no sirve si se pierde el servidor. Elija **una** opción:

**a) Almacenamiento compatible con S3** (AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO):

```ini
BACKUP_S3_ENDPOINT=https://<cuenta>.r2.cloudflarestorage.com
BACKUP_S3_REGION=auto
BACKUP_S3_BUCKET=respaldos-dallycontrol
BACKUP_S3_PREFIX=produccion
BACKUP_S3_ACCESS_KEY=...
BACKUP_S3_SECRET_KEY=...
```

Cree una credencial que solo pueda escribir en ese bucket, y active en el bucket el versionado o el bloqueo
de objetos: así un atacante con acceso al servidor no puede borrar los respaldos.

**b) Cualquier destino de rclone** (Google Drive, SFTP, Azure…): ponga `rclone.conf` en
`ops/backup-config/` y `BACKUP_RCLONE_REMOTE=nombre:ruta`.

**c) rsync por SSH a otro servidor:** ponga `id_ed25519` y `known_hosts` en `ops/backup-config/` y
`BACKUP_RSYNC_TARGET=usuario@host:/ruta`.

Qué se envía: los volcados y la configuración cifrada (con la misma retención; nunca se borran más de 20
archivos remotos por ejecución, de modo que un directorio local vaciado por error no puede vaciar el
destino) y la instantánea de archivos más reciente (lo que desaparece de ella se conserva en
`files-deleted/<fecha>` en el destino). Tras copiar, se compara la suma del volcado en el destino.

Con `BACKUP_REMOTE_REQUIRED=1`, una copia externa fallida hace fallar el respaldo.

### Cifrado

Con `BACKUP_ENCRYPTION_PASSPHRASE`, los volcados y la configuración se guardan cifrados con AES-256
(`openssl`, PBKDF2). Los APK no se cifran (no son secretos; si lo necesita, use un destino `crypt` de rclone).

**Si pierde la frase, pierde los respaldos.** Guárdela en el gestor de secretos de la empresa, no en el
servidor ni en este repositorio.

Descifrar a mano, sin las herramientas de este repositorio:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in respaldo.dump.enc -out respaldo.dump
pg_restore --list respaldo.dump
```

## Restaurar

Todo desde el directorio de la instalación, en el servidor.

```bash
scripts/restore-backup.sh list                    # qué respaldos hay
scripts/restore-backup.sh verify                  # prueba: restaura el último en una base temporal y la borra
scripts/restore-backup.sh verify <archivo.dump>   # lo mismo con uno concreto
```

`verify` no toca el servicio: se puede ejecutar a cualquier hora.

**Restauración real** (reemplaza la base de datos en uso):

```bash
scripts/restore-backup.sh apply latest --yes                 # solo la base de datos
scripts/restore-backup.sh apply latest --with-files --yes    # base de datos y archivos
scripts/restore-backup.sh files latest --yes                 # solo archivos
```

Qué hace `apply`:

1. Detiene el servidor (el borde sigue arriba y muestra la página de recuperación).
2. Restaura el respaldo en una base **nueva** y la valida: suma SHA-256, todas las tablas, historial de
   migraciones, y número de filas contra el manifiesto.
3. Solo si la validación pasa, intercambia los nombres. **La base anterior no se borra**: queda como
   `dallycontrol_before_<fecha>`.
4. Arranca el servidor y espera a que esté saludable.

Si la validación falla, la base en uso no se toca y el servidor vuelve a arrancar con ella.

Volver atrás de una restauración (con el servidor detenido):

```sql
ALTER DATABASE dallycontrol RENAME TO dallycontrol_descartada;
ALTER DATABASE dallycontrol_before_20261127_125549 RENAME TO dallycontrol;
```

Cuando confirme que todo está bien, libere el espacio: `DROP DATABASE dallycontrol_before_…;`

Un respaldo que no está en el volumen (por ejemplo, descargado del destino externo):

```bash
BACKUP_HOST_DIR=/ruta/con/db scripts/restore-backup.sh verify <archivo.dump>
```

(la carpeta debe contener `db/<archivo>` y, si existen, su `.sha256` y `.manifest.json`).

Los respaldos cifrados necesitan `BACKUP_ENCRYPTION_PASSPHRASE` en `.env` o en el entorno.

### Restaurar en un servidor nuevo (desastre total)

1. Instale Docker, clone el repositorio en la misma versión y recupere `.env` (del gestor de secretos o de
   `config/config-…tar.gz.enc`).
2. `docker compose up -d postgres`
3. Traiga del destino externo la carpeta `db/` (y `files/`) a una ruta local.
4. `BACKUP_HOST_DIR=/ruta scripts/restore-backup.sh apply <archivo.dump> --yes`
5. `scripts/restore-backup.sh files …` o copie `files/` al volumen de datos.
6. `docker compose up -d` y compruebe `/healthz/deep`.

Con túnel de Cloudflare no hay que cambiar el DNS: el conector del servidor nuevo usa el mismo token.

## Prueba automática de respaldo y restauración

```bash
scripts/backup-restore-test.sh
```

Usa contenedores y volúmenes desechables (no toca ninguna instalación) y comprueba, de punta a punta:

1. respaldo completo (volcado verificado, instantánea de archivos, marca para el monitor);
2. restauración de prueba en una base temporal, con validación de filas, y que el origen queda intacto;
3. respaldo cifrado: ida y vuelta, ilegible sin la frase o con otra, e incluye `.env`;
4. un respaldo dañado (suma) o truncado se **rechaza**;
5. la copia externa llega y es idéntica; un destino roto queda registrado y hace fallar el respaldo;
6. desastre simulado: se borran filas, una tabla y los archivos; se restaura de verdad y el contenido queda
   idéntico al original (se compara por suma); la base dañada se conserva;
7. la retención conserva lo documentado.

Resultado de la última ejecución (27 de noviembre de 2026): los 7 pasos correctos. Tiempos con la base de
prueba (5 000 equipos, 3 MB de archivos): respaldo 1 s, restauración de prueba 1 s, restauración real de
base y archivos 1 s.

Además, sobre el stack completo en marcha (73 tablas, 20 equipos, un APK de 5 MB), el mismo día:

| Prueba | Resultado |
|---|---|
| Respaldo con restauración de prueba incluida | 2 s |
| `scripts/restore-backup.sh verify` | correcta, 1 s |
| Desastre provocado (15 de 20 equipos borrados y el APK eliminado) y `apply latest --with-files --yes` | 20 equipos de vuelta, APK idéntico (misma suma SHA-256) y con el dueño correcto; **servicio saludable en 20 s**; sin servicio 17,4 s medidos con sonda; la base dañada quedó guardada |
| Respaldo cifrado con copia a un almacenamiento S3 (servidor S3 de pruebas, `rclone serve s3`) | copia correcta y comprobada; objetos `db/`, `config/` y `files/` en el bucket |
| Bajar el volcado cifrado del bucket a una carpeta y restaurarlo de prueba desde ahí (`BACKUP_HOST_DIR`) | correcta |
| Destino S3 caído con `BACKUP_REMOTE_REQUIRED=1` | el respaldo termina con error y queda `remoteOk=false` (el monitor avisa) |
| El monitor sin ningún respaldo, y tras hacer uno | alerta «Antigüedad del último respaldo» y, después, aviso de recuperación |

**Estos tiempos son de bases pequeñas.** El tiempo real depende del tamaño de la base de producción: mídalo
allí con `scripts/restore-backup.sh verify` (imprime los segundos) y anote el resultado en el runbook. Esa
cifra es la que sustenta el RTO del SLA.

## Qué no cubre

- Lo ocurrido entre el último respaldo y el desastre (hasta 24 h): para bajarlo hace falta la réplica
  continua (`ALTA-DISPONIBILIDAD.md`).
- Los volúmenes de Caddy (certificados: se vuelven a emitir solos) y los del supervisor.
- Las opciones (a) S3 y (b) rclone usan el mismo mecanismo (rclone). Se probó S3 contra un servidor S3 de
  pruebas y rsync contra un volumen local; **no** se probó contra un proveedor real (AWS, R2, B2) ni rsync
  por SSH a otro servidor. **Pruebe la conexión real al configurarla** (paso 3 de «Activar»: el resultado
  debe decir «copia fuera del servidor: correcta»).
