#!/usr/bin/env python3
"""
Print SQL that fills a DEV database with a synthetic fleet: folders, devices, each device's last state (battery,
storage, network, Android, agent — the census a real agent reports) and a month of connection sessions. For load and
performance tests of the device list, the fleet inventory exports and the reports. Never run it on a real install.

  scripts/dev-synthetic-fleet.py --devices 5000 | docker compose --env-file docker/dev.env exec -T postgres \
      psql -U dallycontrol dallycontrol -q
  scripts/dev-synthetic-fleet.py --remove | docker compose --env-file docker/dev.env exec -T postgres \
      psql -U dallycontrol dallycontrol -q

Every synthetic device's number starts with "syn-" and every folder's name with "Sint ", so --remove deletes exactly
what this script created. The data is deterministic (same --seed, same fleet).
"""
import argparse, json, random, sys, time

P = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
P.add_argument("--devices", type=int, default=5000)
P.add_argument("--folders", type=int, default=20, help="top-level folders; each gets 2 sub-folders")
P.add_argument("--sessions", type=int, default=20, help="closed connection sessions per device (last 30 days)")
P.add_argument("--customer", type=int, default=1)
P.add_argument("--seed", type=int, default=7)
P.add_argument("--remove", action="store_true", help="print the SQL that removes the synthetic fleet")
A = P.parse_args()

MODELS = [("samsung", "SM-A045M", "13"), ("samsung", "SM-A156M", "14"), ("motorola", "moto g31", "11"),
          ("motorola", "moto g54 5G", "14"), ("Xiaomi", "Redmi 12C", "12"), ("Xiaomi", "Redmi Note 13", "13"),
          ("HONOR", "X6a", "13"), ("ZTE", "Blade A54", "13"), ("samsung", "SM-A065M", "15"), ("TECNO", "Spark 20", "13")]
AGENTS = ["0.8.2", "0.9.1", "0.9.5", "0.9.7", "0.9.7", "0.9.7"]
CARRIERS = ["Claro", "Movistar", "Tigo", "WOM"]
GIB = 2 ** 30


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def main():
    out = sys.stdout.write
    if A.remove:
        out("BEGIN;\n"
            "DELETE FROM device_connection WHERE deviceNumber LIKE 'syn-%';\n"
            "DELETE FROM device_state WHERE deviceNumber LIKE 'syn-%';\n"
            "DELETE FROM deviceGroups WHERE deviceId IN (SELECT id FROM devices WHERE number LIKE 'syn-%');\n"
            "DELETE FROM devices WHERE number LIKE 'syn-%';\n"
            "DELETE FROM groups WHERE name LIKE 'Sint %' AND parentId IS NOT NULL;\n"
            "DELETE FROM groups WHERE name LIKE 'Sint %';\n"
            "COMMIT;\n")
        return
    rnd = random.Random(A.seed)
    now = int(time.time() * 1000)
    c = A.customer
    out("BEGIN;\n")
    out("CREATE TEMP TABLE syn_cfg AS SELECT min(id) AS id FROM configurations WHERE customerId = %d;\n" % c)
    names = []
    for f in range(1, A.folders + 1):
        top = "Sint %02d" % f
        out("INSERT INTO groups (name, customerId) SELECT %s, %d WHERE NOT EXISTS "
            "(SELECT 1 FROM groups WHERE name = %s AND customerId = %d AND parentId IS NULL);\n" % (q(top), c, q(top), c))
        names.append((top, None))
        for sub in ("Norte", "Sur"):
            n = "%s %s" % (top, sub)
            out("INSERT INTO groups (name, customerId, parentId) SELECT %s, %d, g.id FROM groups g WHERE g.name = %s "
                "AND g.customerId = %d AND g.parentId IS NULL AND NOT EXISTS (SELECT 1 FROM groups WHERE name = %s "
                "AND customerId = %d);\n" % (q(n), c, q(top), c, q(n), c))
            names.append((n, top))
    for i in range(A.devices):
        number = "syn-%05d" % i
        man, model, android = MODELS[rnd.randrange(len(MODELS))]
        total = rnd.choice([32, 64, 128]) * GIB
        free = int(total * rnd.choice([0.03, 0.08, 0.2, 0.35, 0.5, 0.7]))
        battery = rnd.randrange(3, 101)
        charging = rnd.random() < 0.25
        wifi = rnd.random() < 0.6
        # 70 % reported in the last 20 minutes, 20 % in the last days, 10 % long ago
        r = rnd.random()
        age = rnd.randrange(0, 19 * 60_000) if r < 0.7 else rnd.randrange(3_600_000, 5 * 86_400_000) if r < 0.9 \
            else rnd.randrange(8 * 86_400_000, 60 * 86_400_000)
        last = now - age
        dyn = {"batteryPct": battery, "batteryHealth": rnd.choice(["good"] * 8 + ["overheat", "cold"]),
               "batteryTempC": round(rnd.uniform(24, 41), 1), "batteryVoltageMv": 4000,
               "chargingSource": "ac" if charging else "none", "freeStorageBytes": free, "freeRamBytes": 2 * GIB,
               "networkType": "wifi" if wifi else "cellular", "ipAddress": "10.%d.%d.%d" % (i % 250, (i // 250) % 250, 7),
               "screenOn": False, "locked": False, "kioskActive": i % 3 == 0, "uptimeMs": 86_400_000, "lastBootAt": last - 86_400_000,
               "sim": {"state": "ready", "slots": [{"slot": 0, "carrier": rnd.choice(CARRIERS), "number": "+57300%07d" % i}]},
               "location": {"lat": round(4.6 + rnd.uniform(-2, 2), 6), "lon": round(-74.08 + rnd.uniform(-2, 2), 6), "capturedAt": last}}
        if wifi:
            dyn.update({"wifiSsid": '"Bodega-%d"' % (i % 40), "wifiRssi": rnd.randrange(-92, -40)})
        else:
            dyn.update({"cellularOperator": rnd.choice(CARRIERS), "cellularSignalLevel": rnd.randrange(0, 5)})
        tel = {"dynamic": dyn,
               "hardware": {"manufacturer": man, "brand": man, "model": model, "device": model, "osRelease": android,
                            "sdkInt": 30, "securityPatch": "2026-0%d-01" % rnd.randrange(1, 10), "buildFingerprint": "syn",
                            "abis": ["arm64-v8a"], "totalRamBytes": 4 * GIB, "totalStorageBytes": total,
                            "screenWidthPx": 720, "screenHeightPx": 1600, "densityDpi": 320},
               "identity": {"serial": "SYN%07d" % i, "imei": ["35693803%07d" % i], "wifiMac": "02:00:00:%02x:%02x:%02x" % (i >> 16 & 255, i >> 8 & 255, i & 255)}}
        folder = names[rnd.randrange(len(names))][0]
        out("INSERT INTO devices (number, description, lastUpdate, configurationId, customerId, enrollTime, infojson) "
            "SELECT %s, %s, %d, id, %d, %d, %s::jsonb FROM syn_cfg ON CONFLICT DO NOTHING;\n"
            % (q(number), q("Equipo sintético %05d" % i), last, c, now - 90 * 86_400_000, q(json.dumps({"androidVersion": android}))))
        out("INSERT INTO deviceGroups (deviceId, groupId) SELECT d.id, g.id FROM devices d, groups g WHERE d.number = %s "
            "AND g.name = %s AND g.customerId = %d AND NOT EXISTS (SELECT 1 FROM deviceGroups x WHERE x.deviceId = d.id);\n"
            % (q(number), q(folder), c))
        out("INSERT INTO device_state (deviceNumber, battery, charging, locked, kioskActive, androidRelease, lastBootAt, "
            "updatedAt, agentVersion, powerMode, telemetry) SELECT %s, %d, %s, FALSE, %s, %s, %d, %d, %s, %s, %s "
            "WHERE NOT EXISTS (SELECT 1 FROM device_state WHERE deviceNumber = %s);\n"
            % (q(number), battery, q(charging), q(i % 3 == 0), q(android), last - 86_400_000, last,
               q(rnd.choice(AGENTS)), q(rnd.choice(["alwaysOn", "adaptive"])), q(json.dumps(tel, ensure_ascii=False)), q(number)))
        sess = []
        for k in range(A.sessions):
            start = now - (k + 1) * 86_400_000 - rnd.randrange(0, 3_600_000)
            sess.append("(%s, %d, %d)" % (q(number), start, start + rnd.randrange(600_000, 8 * 3_600_000)))
        if age < 20 * 60_000:
            sess.append("(%s, %d, %d)" % (q(number), last - rnd.randrange(60_000, 3 * 3_600_000), last))
        if sess:
            out("INSERT INTO device_connection (deviceNumber, connectedAt, lastSeenAt) VALUES %s;\n" % ", ".join(sess))
    out("COMMIT;\n")


if __name__ == "__main__":
    main()
