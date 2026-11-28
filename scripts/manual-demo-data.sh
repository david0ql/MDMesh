#!/usr/bin/env bash
#
# Datos de demostración para las capturas de pantalla de los manuales (docs/manuales). SOLO para un stack de
# desarrollo (docker/dev.env): nombres, series, teléfonos y posiciones son ficticios.
#
#   scripts/manual-demo-data.sh               # siembra: flota sintética con nombres legibles, recorridos, eventos, alertas
#   scripts/manual-demo-data.sh --refrescar   # solo vuelve a poner "en línea" a los equipos (envejecen en 20 min)
#
# Antes: docker compose --env-file docker/dev.env up -d --build && scripts/dev-seed.sh
# Después: node scripts/manual-capturas.mjs --web http://localhost:<puerto>   (crea el resto por la consola y captura)
# Con un stack aislado, exporte COMPOSE_PROJECT_NAME (y sus puertos) igual que al levantarlo.
set -euo pipefail
cd "$(CDPATH='' cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f docker/dev.env ] || { echo "docker/dev.env no existe: este script solo siembra el stack de desarrollo" >&2; exit 1; }
DC=(docker compose --env-file docker/dev.env)
files=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$("${DC[@]}" ps -q postgres)")
case ",$files," in *docker-compose.dev.yml,*) ;; *) echo "el stack no se levantó con docker-compose.dev.yml: no es de desarrollo" >&2; exit 1 ;; esac
PSQL=("${DC[@]}" exec -T postgres psql -U dallycontrol -d dallycontrol -q -v ON_ERROR_STOP=1)

refrescar() {
  "${PSQL[@]}" <<'SQL'
-- Los equipos que estaban "en línea" al sembrar vuelven a estarlo: último reporte en los últimos 5 minutos.
CREATE TEMP TABLE vivos AS
  SELECT number, (extract(epoch FROM now()) * 1000)::bigint - (random() * 300000)::bigint AS t
  FROM devices WHERE number LIKE 'dc-%'
    AND lastUpdate > (SELECT max(lastUpdate) FROM devices WHERE number LIKE 'dc-%') - 20 * 60000;
UPDATE devices d SET lastUpdate = v.t FROM vivos v WHERE d.number = v.number;
UPDATE device_state s SET updatedAt = v.t FROM vivos v WHERE s.deviceNumber = v.number;
UPDATE device_connection c SET lastSeenAt = v.t FROM vivos v
  WHERE c.deviceNumber = v.number AND c.lastSeenAt = (SELECT max(lastSeenAt) FROM device_connection x WHERE x.deviceNumber = v.number);
-- El APK de prueba que sube scripts/manual-capturas.mjs (--apk) aparece con un nombre de aplicación de negocio.
UPDATE applications SET name = 'Preventa Móvil' WHERE pkg = 'com.dallycontrol.testapp';
-- Restos de la siembra de desarrollo que no deben salir en una captura: el equipo de ejemplo y la versión sin rellenar.
DELETE FROM devices WHERE number = 'h0001';
UPDATE applications SET name = 'Lanzador administrado' WHERE pkg = 'com.hmdm.launcher';
UPDATE applicationVersions SET version = '0.9.7' WHERE version = '_HMDM_VERSION_';
-- Los teléfonos simulados que inscribe scripts/manual-capturas.mjs (para «Intentos de inscripción») reciben nombre.
UPDATE devices SET description = 'Preventa 0' || (70 + id % 20)::text
  WHERE number NOT LIKE 'dc-%' AND coalesce(description, '') = '';
SQL
  echo "equipos en línea refrescados"
}

if [ "${1:-}" = "--refrescar" ]; then refrescar; exit 0; fi

if [ "$("${PSQL[@]}" -At -c "SELECT count(*) FROM devices WHERE number LIKE 'dc-0001%'")" != "0" ]; then
  echo "los datos de demostración ya están sembrados; solo se refrescan"; refrescar; exit 0
fi

scripts/dev-synthetic-fleet.py --devices 72 --folders 3 --sessions 6 | "${PSQL[@]}"

"${PSQL[@]}" <<'SQL'
BEGIN;
-- Carpetas con nombres de una empresa ficticia.
UPDATE groups g SET name = m.neu FROM (VALUES
  ('Sint 01', 'Andina Distribución'), ('Sint 01 Norte', 'Bogotá'), ('Sint 01 Sur', 'Medellín'),
  ('Sint 02', 'Cafetal Logística'), ('Sint 02 Norte', 'Cali'), ('Sint 02 Sur', 'Barranquilla'),
  ('Sint 03', 'Almacenes del Sol'), ('Sint 03 Norte', 'Bodega central'), ('Sint 03 Sur', 'Tiendas')) m(old, neu)
  WHERE g.name = m.old;
DELETE FROM groups WHERE name = 'General' AND NOT EXISTS (SELECT 1 FROM deviceGroups dg WHERE dg.groupId = groups.id);
-- Equipos: números dc-0001NN y nombres por función.
CREATE TEMP TABLE ren AS
  SELECT number AS old, 'dc-' || lpad((100 + row_number() OVER (ORDER BY number))::text, 6, '0') AS neu,
         row_number() OVER (ORDER BY number) AS n
  FROM devices WHERE number LIKE 'syn-%';
UPDATE device_state s SET deviceNumber = r.neu,
       telemetry = ((s.telemetry::jsonb #- '{dynamic,ipAddress}')
                    || jsonb_build_object('identity', jsonb_build_object(
                         'serial', 'R58M' || lpad(r.n::text, 6, '0'), 'imei', jsonb_build_array('35000000' || lpad(r.n::text, 7, '0')))))::text
  FROM ren r WHERE s.deviceNumber = r.old;
UPDATE device_connection c SET deviceNumber = r.neu FROM ren r WHERE c.deviceNumber = r.old;
UPDATE devices d SET number = r.neu,
       description = (ARRAY['Preventa', 'Entrega', 'Bodega', 'Supervisor'])[1 + (r.n % 4)::int] || ' ' || lpad(r.n::text, 3, '0')
  FROM ren r WHERE d.number = r.old;
-- El equipo de ejemplo de las capturas: en línea, en Bogotá, con una falla de red reciente ya resuelta.
UPDATE devices SET lastUpdate = (extract(epoch FROM now()) * 1000)::bigint WHERE number = 'dc-000101';
UPDATE deviceGroups SET groupId = (SELECT id FROM groups WHERE name = 'Bogotá')
  WHERE deviceId = (SELECT id FROM devices WHERE number = 'dc-000101');
UPDATE device_state SET updatedAt = (extract(epoch FROM now()) * 1000)::bigint, battery = 78, agentVersion = '0.9.7'
  WHERE deviceNumber = 'dc-000101';
INSERT INTO device_conn_diag (deviceNumber, updatedAt, screenOn, network, serverHost, usingBackup, reconnects, failureKind,
    failureAt, failureDetail, failureChannel, failureNetwork, failureSsid, failureHost, failureIssuer, failureCount,
    stopReason, stopAt, stopResumedAt, stopBattery)
  SELECT 'dc-000101', t, TRUE, 'cellular', 'mdm.ejemplo.com', FALSE, 3, 'tls', t - 5400000, NULL, 'socket', 'wifi',
         'Sede-Norte', 'mdm.ejemplo.com', 'CN=Firewall Sede Norte CA', 4, 'serverUnreachable', t - 5400000, t - 3000000, 76
  FROM (SELECT (extract(epoch FROM now()) * 1000)::bigint AS t) x
  ON CONFLICT (deviceNumber) DO NOTHING;
INSERT INTO device_event (deviceNumber, type, ts, detail)
  SELECT 'dc-000101', e.type, (extract(epoch FROM now()) * 1000)::bigint - e.ago * 60000, NULL
  FROM (VALUES ('boot', 1900), ('connectivityChange', 1490), ('lowBattery', 1100), ('connectivityChange', 480),
               ('simRemoved', 300), ('simInserted', 296), ('connectivityChange', 90), ('kioskExit', 40)) e(type, ago);
INSERT INTO device_metric (deviceNumber, ts, battery, charging, networkType, wifiRssi, signalLevel, freeStorageBytes,
    freeRamBytes, kioskActive, locked)
  SELECT 'dc-000101', (extract(epoch FROM now()) * 1000)::bigint - i * 300000,
         (55 + 40 * abs(sin(i / 46.0)))::int, (i % 288) BETWEEN 200 AND 250, CASE WHEN (i % 288) < 110 THEN 'wifi' ELSE 'cellular' END,
         (-48 - 30 * abs(sin(i / 17.0)))::int, (2 + 2 * abs(cos(i / 23.0)))::int, 21474836480 - i * 900000::bigint,
         1610612736, TRUE, FALSE
  FROM generate_series(0, 2000) i;
-- Alertas de ejemplo en varias carpetas.
INSERT INTO device_alert (customerId, deviceNumber, type, detail, createdAt, reviewed)
  SELECT 1, 'dc-' || lpad((100 + a.n)::text, 6, '0'), a.type, a.detail, (extract(epoch FROM now()) * 1000)::bigint - a.ago * 60000, a.rev
  FROM (VALUES (4, 'disconnected', 'Sin reportar por más de 20 minutos', 35, FALSE),
               (9, 'simChange', 'SIM retirada', 95, FALSE),
               (1, 'kioskExit', 'Salió del quiosco con la contraseña', 40, FALSE),
               (17, 'disconnected', 'Sin reportar por más de 20 minutos', 260, TRUE),
               (23, 'simChange', 'SIM cambiada', 1300, TRUE),
               (31, 'disconnected', 'Sin reportar por más de 20 minutos', 2900, TRUE)) a(n, type, detail, ago, rev);
COMMIT;
SQL

# Recorridos de hoy para los equipos de Bogotá (caminata aleatoria alrededor de un punto ficticio de la ciudad).
"${PSQL[@]}" -At -c "SELECT d.number FROM devices d JOIN deviceGroups dg ON dg.deviceId = d.id JOIN groups g ON g.id = dg.groupId
                     WHERE g.name = 'Bogotá' ORDER BY d.number LIMIT 9" | python3 -c '
import random, sys, time
rnd = random.Random(11); now = int(time.time() * 1000)
print("BEGIN;")
for k, number in enumerate(l.strip() for l in sys.stdin if l.strip()):
    lat, lon = 4.60 + rnd.uniform(0, 0.12), -74.12 + rnd.uniform(0, 0.08)
    rows = []
    for i in range(48, -1, -1):
        lat += rnd.uniform(-0.0035, 0.0035); lon += rnd.uniform(-0.0035, 0.0035)
        t = now - i * 600000 - rnd.randrange(0, 60000)
        rows.append("(%r, %.6f, %.6f, %d, %r, %d, %d)" % (number, lat, lon, rnd.choice([6, 9, 12, 18, 25]), "gps", t, t + 4000))
    print("INSERT INTO device_location (deviceNumber, lat, lon, accuracy, provider, capturedAt, recordedAt) VALUES " + ", ".join(rows) + ";")
print("COMMIT;")' | "${PSQL[@]}"

refrescar
echo "datos de demostración sembrados: 72 equipos en 9 carpetas"
