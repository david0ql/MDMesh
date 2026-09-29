# Testing DallyControl end to end

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

Final matrix: one clean emulator per release, ADB enrollment, the full functional test (including silent
install / upgrade / sha256 refusal / uninstall). Checks a release cannot support are skipped (Android 6: no
Bluetooth policy or lock-screen message; below 8.1: no `locksettings verify`), hence the different totals.

| Android | API | Functional test | Extra |
|---|---|---|---|
| 6.0 | 23 | 43/43 | self-update 3/3, wipe 2/2 |
| 7.0 | 24 | 46/46 | |
| 7.1 | 25 | 46/46 * | latency probe 40/40, max 0.6 s |
| 8.0 | 26 | 48/48 | |
| 8.1 | 27 | 50/50 | |
| 9 | 28 | 50/50 | |
| 10 | 29 | 50/50 | |
| 11 | 30 | 50/50 | |
| 12 | 31 | 50/50 | |
| 12L | 32 | 50/50 | |
| 13 | 33 | 50/50 | |
| 14 | 34 | 50/50 | reboot + wipe 5/5, console Apps tab uninstall (browser) |
| 15 | 35 | 50/50 | self-update 3/3 |
| 16 | 36 | 50/50 | |
| 17 | 37 | 50/50 | |
| 17 QPR2 | 37.2 | 50/50 | reboot + wipe 5/5 |

\* The final matrix run of API 25 was 45/46: one command was delivered late, the intermittent delay later traced
to check-ins reusing NAT-dropped pooled connections and fixed (commit `ddabb1c`). See the re-validation below.

Re-validation with the final agent (`ddabb1c`, clean emulators): API 23 43/43, API 25 46/46, API 30 50/50,
API 35 50/50, API 37.2 50/50.

## Remote view/control (ADR 0010)

`scripts/remote-e2e.mjs` runs against a device enrolled with `scripts/adb-enroll.sh --remote`: it starts a session
with `scripts/remote-session.sh`, opens the stack's noVNC viewer (`/remote/vnc/vnc.html`) in Chromium signed in to
the console, and checks that the viewer connects, renders the screen, that a key pressed in the viewer controls the
device (Settings is opened over adb, Home in the viewer must bring the launcher back), and that `remote.vnc.stop`
ends the session. `--console` drives the console instead: device page → **Remote**, the battery-saver warning and
**Set Always-on** (the device must report always-on and the warning go away), **View & control**, the viewer inline
in the page, the command detail proving the device used the encrypted tunnel, the soft keys (Back, Home, Recents
each take the device out of Settings) and **End session** (14 checks; 11 when the device is already always-on).
The emulator table below predates the soft keys; they were verified on API 34 (11/11, already always-on). `REMOTE_E2E=1 REMOTE_E2E_MODES="script console" VNC_APK=… scripts/emulator-matrix.sh …` runs both on
clean emulators. `scripts/agent-v1-e2e.sh` covers the server side (status, session start, the tunnel gate's
204/401/403, the password kept out of the history, Observer denied).

**Encrypted tunnel + console button (2026-09-28)**, fresh enrollment, both modes; in the repeater log every device
connection comes from `websockify-device`, none from the plain port:

| Android | API | script mode | console mode |
|---|---|---|---|
| 7.0 | 24 | 5/5 | 8/8 |
| 10 | 29 | 5/5 | 8/8 |
| 14 | 34 | 5/5 | 11/11 (with Set Always-on) |
| 17 QPR2 | 37.2 | 5/5 | 8/8 |

Before the tunnel (plain repeater port), script mode:

| Android | API | Remote e2e (fresh enrollment) |
|---|---|---|
| 7.0 | 24 | 5/5 |
| 7.1 | 25 | 5/5 |
| 8.0 | 26 | 5/5 on an emulator that has been up a while; right after a fresh enrollment the stock 8.0 image's System UI crashes (NavigationBarFragment NPE on keyguard-occluded), so it cannot answer the capture request — an emulator image bug |
| 8.1 | 27 | 5/5 |
| 9 | 28 | 5/5 |
| 10 | 29 | 5/5 |
| 11 | 30 | 5/5 |
| 12 | 31 | 5/5 |
| 12L | 32 | 5/5 |
| 13 | 33 | 5/5 |
| 14 | 34 | 5/5 |
| 15 | 35 | 5/5 |
| 16 | 36 | 5/5 |
| 17 | 37 | 5/5 |
| 17 QPR2 | 37.2 | 5/5 |

Android 6 has no remote view (droidVNC-NG needs Android 7).

The native installer was verified separately on a root-only Debian 12 container (no sudo, no systemd):
it installs, migrates, seeds, and the Agent v1 suite passes 56/56 against it.

## Security regression checks

`scripts/security-check.sh [BASE_URL] [SERVER_URL]` re-checks the pre-launch hardening (docs/SECURITY-HARDENING.md)
against a running install: security headers and CSP, the closed legacy endpoints (at the edge and, with SERVER_URL,
directly on Tomcat, including encoded-path tricks), cookie flags and session renewal, a read-only user unable to
escalate, unsafe file names refused, the login lockout, and the update status hidden from anonymous callers. It
creates and deletes one temporary Observer user. Dev stack: 43/43.

## Operations parity: folders, codes, policies (FLEET-POLICIES.md)

Against a running install and a Device-Owner phone or emulator enrolled in it (adb reachable):

```bash
ADMIN_PW=... /opt/homebrew/bin/bash scripts/parity-e2e.sh --base https://mdm.example.com \
    --device <device number> --serial emulator-5554 --config <kiosk configuration id>   # [--only folders,codes,...]
DALLYCONTROL_ADMIN_PASSWORD=... node scripts/parity-console-e2e.mjs --api https://mdm.example.com \
    --device <device number> --config <kiosk configuration id>
```

`parity-e2e.sh` sections: `folders` (nesting, inheritance, loops refused, branch commands), `codes` (one code enrolls
several phones into its folder, typed however, revocable), `roles` (kiosk functions resolved per device, incoming call
visible in kiosk, remote open app), `browser` (Chrome allowlist/blocklist, blocked vs loaded pages), `apps` (installed
outside the list = suspended at once, allowed later = lifted, Play Store hidden), `trail` (a fix per minute online, and
fixes captured offline uploaded on reconnection), `sim` (number, SIM change event, modem restart ≠ removal), `passcode`
(set / wrong refused / cleared, verified with `locksettings`), `launch` (ring plays, open app), `crashloop` (6× Back
keeps the kiosk and shows *Open <app>*), and the opt-in `typedcode` (re-enrolls the phone by typing a folder code on
the agent screen). They create `e2e-*` folders/codes/devices and restore the configuration's policy on exit.

### Results (2026-09-28, https://mdm.felapp.co + emulator DallyControl_Pixel8: Android 14 with Google Play, agent 0.2.2)

| Suite | Result |
|---|---|
| `parity-e2e.sh` (all sections) | 87/87 |
| `parity-e2e.sh --only typedcode` | 6/6 |
| `parity-console-e2e.mjs` | 14/14 |
| remote view/control in kiosk (tap, typing, Back soft key) | 6/6 |
| `security-check.sh` (production, behind Cloudflare) | 40/40 |
| `agent-v1-e2e.sh` (dev stack, regression) | 82/82 |
| `security-check.sh` (dev: edge + Tomcat) | 44/44 |
| server/common unit tests · agent unit tests | all green |

Findings fixed along the way: the enroll half of reusable codes was missing; lock task hid incoming calls; the kiosk
crash-loop guard released the kiosk (4× Back was a way out) and, once fixed, missed bounces when Android recreated the
kiosk home; after a re-enrollment the wake socket kept the old device id; the in-call screen could lose the race with
the phone-state broadcast; the agent never declared WRITE_SECURE_SETTINGS; Cloudflare cached APKs and the console for hours.
