# Testing MDMesh end to end

Three layers, from fastest to most complete. All of them run against the loopback dev stack
(`docker compose --env-file docker/dev.env up -d --build` + `scripts/dev-seed.sh`, see [DEV.md](DEV.md)).

## 1. Unit + contract tests

```bash
mvn -pl common,server -am test                                   # server (JDK 17)
cd agent-android && ./gradlew detekt lintDebug testDebugUnitTest # agent
cd web && npx tsc -b --noEmit && npm run build                   # console
```

## 2. Agent v1 protocol, curl playing the device

```bash
scripts/agent-v1-e2e.sh http://localhost:8080      # expect RESULT: PASS=56 FAIL=0
```

On macOS run it with bash >= 4 (`/opt/homebrew/bin/bash scripts/agent-v1-e2e.sh ...`): the stock bash 3.2
mis-expands braces inside nested command substitutions and the requests go out malformed.

## 3. A real agent on real Android

`scripts/device-func-test.py` drives an enrolled device through the admin API and checks the effect on the
device over adb: policies (camera, screenshots, Wi-Fi, Bluetooth, USB), lock-screen message, alert, ring,
lock, power/location modes, kiosk (single app and launcher), passcode reset (verified with
`locksettings verify` on API 27+), app inventory, silent install / upgrade / sha256 refusal / uninstall,
location telemetry, and with `--destructive` reboot and wipe; `--self-update-apk` tests the agent updating
itself. Capabilities a release does not advertise are skipped, not failed.

```bash
# one device (emulator or USB), after scripts/adb-enroll.sh
scripts/device-func-test.py --serial emulator-5554 --device-id <id>

# a clean emulator per release: boot (wiped) -> ADB enrollment -> functional test -> shut down
scripts/emulator-matrix.sh agent-android/app/build/outputs/apk/debug/app-debug.apk mdm_api23 mdm_api35 ...
```

Emulator quirks the test accounts for (none of them are agent behaviour):

- API 30+ emulator GNSS stamps injected fixes with a constant elapsed-realtime, so only the first `geo fix`
  after boot is accepted; the test injects its fix before anything turns on active location mode.
- Emulator images have no `/misc` partition, so a factory reset cannot run; the test accepts the wipe as
  handed to recovery (the platform logs the command, or deletes the recovery command at the next boot).
- A device on battery in *adaptive* power mode drops its wake socket while the screen is off, so commands
  wait for the periodic check-in; the emulator reports AC power, so this does not apply to the matrix.

## Results (2026-09-28, dev stack + debug agent)

| Android | API | Functional test |
|---|---|---|
| 6.0 | 23 | 36/36, install + self-update 10/10, wipe 2/2 |
| 7.0 | 24 | 39/39 |
| 7.1 | 25 | 39/39 |
| 8.0 | 26 | 41/41 |
| 8.1 | 27 | 43/43 |
| 9 | 28 | 43/43 |
| 10 | 29 | 43/43 |
| 11 | 30 | 43/43 |
| 12 | 31 | 43/43 |
| 12L | 32 | 43/43 |
| 13 | 33 | 43/43 |
| 14 | 34 | 43/43, install 7/7, reboot + wipe 5/5 |
| 15 | 35 | 50/50 (with install), self-update 3/3 |
| 16 | 36 | 50/50 (with install) |
| 17 | 37 | 43/43 |
| 17 QPR2 | 37.2 | 43/43, reboot + wipe 5/5 |

The native installer was verified separately on a root-only Debian 12 container (no sudo, no systemd):
it installs, migrates, seeds, and the Agent v1 suite passes 56/56 against it.
