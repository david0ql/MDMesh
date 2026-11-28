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

## Geofences, alert e-mails and location history ([GEOFENCES.md](GEOFENCES.md))

```bash
scripts/geofence-e2e.py --base http://localhost:8080 [--mailpit http://localhost:8025]        # this script plays the device
scripts/geofence-e2e.py --base http://localhost:8088 --serial emulator-5554 --device <number> \
                        [--mailpit http://localhost:8025]                                     # a real agent, moved with `adb emu geo fix`
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/geofence-console-e2e.mjs --api http://localhost:8088 [--device <number>]
```

- Simulated device: zones (circle, polygon, schedule), inside → outside → inside, a jumpy point, inaccurate points, a
  batch of buffered positions sent out of order, the tracking interval a zone pushes into `config.apply`, the paginated
  track and its CSV/Excel export, alert filter/review and, with `--mailpit`, the grouped e-mail.
- Emulator: the agent takes a position every minute (the zone asks for it); the script moves the phone out and back,
  then repeats the trip with Wi-Fi and data off so the positions arrive as one batch, creates a zone the phone is
  already outside of, and reads the agent's own account of which location providers it tried. On API ≤ 29 it uses
  `adb emu geo fix`; on API 30+ (see the GNSS quirk above) pass `--mock-provider`, which feeds the positions through
  Android's test location provider (`cmd location providers add-test-provider`, Android 12+).
- Console (headless Chromium): draws a circle and a polygon on the map (click, drag the radius/centre/vertex handles,
  remove and add vertices), saves, edits, deletes; recipients and thresholds tabs; the zone types in Alertas; the zones
  on the fleet map and on the device's Ubicación tab with its history, accuracy and CSV download.

### Results (2026-11-27, isolated dev stack + Mailpit requiring STARTTLS and a password, debug agent of this branch)

| Suite | Result |
|---|---|
| `geofence-e2e.py` (simulated device, e-mail over STARTTLS + auth) | 89/89 |
| `geofence-e2e.py --serial` on Android 10 (API 29, `adb emu geo fix`), with e-mail (before the "already outside" checks were added) | 31/31 |
| `geofence-e2e.py --serial --mock-provider` on Android 13 (API 33, Google APIs): whole trip, offline batch, zone created with the phone already outside, e-mail | 38/39 → the one miss was the diagnosis check when a recent position is reused; fixed and re-run with `--first-fix-only`: 8/8 |
| `geofence-console-e2e.mjs` (`--device`: 35/35 earlier; without device after the latest changes) | 29/29 |
| `agent-v1-e2e.sh` (regression) | 84/84 |
| SMTP modes through *Enviar prueba*: STARTTLS + auth, implicit SSL + auth → delivered; wrong password, no TLS against a STARTTLS-only server → refused cleanly (`error.geofence.smtp.failed`) | as expected |
| server/common unit tests · agent `lintDebug` + unit tests | all green |

Location providers on the API 33 Google APIs emulator (agent log `fresh fix: …`): `fused` is present and enabled but
gave nothing within its 8 s cap, then `gps` answered in 4.7–7.3 s; on the next request `fused` was skipped and `gps`
answered in 4.0 s. Fed through the test provider, positions arrive as `provider=fused` ±6 m.

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

## Remote commands that are never lost (MDM-07 / MDM-09)

`scripts/command-reliability-e2e.py` drives an enrolled device (emulator or USB) against the dev stack and checks,
on the device itself, that commands survive a lost link and that their latency can be shown:

```bash
scripts/command-reliability-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id> \
    [--reps 10] [--pg-container dallycontrol-dev-postgres-1] [--only latency,offline,lostack,reboot,report] [--wipe]
```

- `latency`: lock, alarm, message and lock-screen message, `--reps` times each, device online; prints p50/p90/max
  from the server's timestamps (created → delivered → completed).
- `offline`: airplane mode, queue lock + message + alert, move them 2 h back in the database (past the old
  60-minute expiry), cancel one, reconnect: the kept ones run, the cancelled one never reaches the device.
- `lostack`: a delivered command without answer is handed out again after 2 minutes; one that already ran is not run
  twice; a lost state change is not replayed over a newer one.
- `reboot`: the reboot's answer is really lost; the repeat is answered from the agent's ledger, no second reboot.
- `report`: the "Latencia de comandos" report (JSON, Excel, CSV) and the issuer stored on each command.
- `wipe` (`--wipe`, last, destructive): wipe queued without link, delivered on reconnect, device marked "Borrado".

The per-type waiting times and resend rules are `CommandPolicy` (common), pinned by `CommandPolicyTest`.

## App management: big uploads, silent install, failures (MDM-04 / MDM-05)

`scripts/app-install-e2e.py` checks the whole path of a private APK. It puts a local proxy that refuses request bodies
over 100 MB (what the Cloudflare tunnel does) in front of the stack and, through it: the big APK fails in one request
(HTTP 413) and goes up in parts (damaged part refused, resume, whole file verified); the hosted file downloads by
`Range`; a version signed with another key is warned about and blocked until confirmed; a corrupt APK and an `.aab` are
refused with a clear message; an install on a device that is not Device Owner shows as held, with the reason. With a real
enrolled agent (`--serial`, `--device-id`) it also installs and updates silently, and makes the installs fail (another
signature, corrupt file, wrong SHA-256, no free space) to check the reason shown in "Instalación en la flota", the
"Reintentar" button and the automatic retry.

```bash
scripts/build-install-testapps.sh /tmp/apks        # 120 MB v1, v2, v3 signed with another key, a corrupt one
scripts/app-install-e2e.py --api http://localhost:8088 --apks /tmp/apks \
    --serial emulator-5600 --device-id <id> \
    --pg "docker compose --env-file docker/dev.env exec -T postgres psql -U dallycontrol -d dallycontrol -At -c"
```

Without `--serial` only the server part runs. `--pg` is only for the automatic-retry check (it moves a failure 11
minutes into the past and waits for the server's 5-minute timer); `--skip-auto-retry` and `--skip-storage` shorten the run.

## Fleet inventory exports and scheduled reports

`scripts/fleet-report-e2e.py` checks the fleet inventory against a real enrolled device (see
[REPORTES.md](REPORTES.md)): it sets the battery level (`adb emu power`), writes a file to use storage, switches
Wi-Fi off and on, asks the device to report with "Actualizar ahora" (and times it), downloads the real Excel and CSV
and compares each column (battery, charging, free/total storage, % used, Android, model, connection state, network)
with what the device itself says over adb. With `--mailpit` it also tests the scheduled e-mail against a local test
SMTP: "Enviar ahora" in both formats, the attachment opened and checked, and one run fired by the server's
once-a-minute scheduler.

```bash
# test SMTP next to the dev stack (remove it afterwards: docker rm -f dc-mailpit)
docker run -d --name dc-mailpit --network dallycontrol-dev_default -p 127.0.0.1:8025:8025 axllent/mailpit
SMTP_HOST=dc-mailpit SMTP_PORT=1025 docker compose --env-file docker/dev.env up -d server

scripts/fleet-report-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id> \
    --mailpit http://localhost:8025

# without SMTP_HOST: "Enviar ahora" must say so instead of failing
scripts/fleet-report-e2e.py --api http://localhost:8088 --device-id <id> --expect-no-smtp

# with the device off for more than 20 minutes: it must export as "Sin conexión"
scripts/fleet-report-e2e.py --api http://localhost:8088 --device-id <id> --expect-offline
```

Several stacks on one machine: give each its own project name, ports **and image tags** (`COMPOSE_PROJECT_NAME`,
`DEV_*_PORT`, `SERVER_VERSION`, `WEB_VERSION`, `SUPERVISOR_VERSION`), or one stack's `up --build` replaces the `:dev`
image another stack will pick up on its next `up`.

Result (2026-11-27, own stack + debug agent 0.9.7 on a clean API 31 emulator, Mailpit as SMTP): 81/81 with the
device and `--mailpit`; "Actualizar ahora" showed the new battery level 1.8 s after the request; the scheduled
e-mail fired by the server arrived 32 s after its slot, exactly once; `--expect-no-smtp` 3/3; `--expect-offline` 4/4 (22 min after the emulator was shut down).

## Kiosk escape test (docs/KIOSK-HARDENING.md)

`scripts/kiosk-escape-test.py` puts an enrolled Device-Owner device in the kiosk through the admin API (single app,
then several apps) and tries every known way out over adb: Settings by `am start`, an allowed app opening Settings by
intent (same task and new task), the status bar and its gear, Home, Recents, the power menu (open by default, closed
with "lock buttons"), the "share data" screen, the 7-tap exit with wrong passwords (wait after the third, attempts
reported to the server) and with the right one, a kiosk without password (no exit on the device), the kiosk an old
server would send (`--legacy-psql`) and, with `--reboot`, a restart that must come back into the kiosk untouched.
`scripts/build-escapeapp.sh` builds its stand-in work app; `scripts/kiosk-escape-matrix.sh` runs it on a clean
emulator per release (headless; `EMU_PORT`, `WEB_PORT`, `LEGACY_PSQL`, `EMU_ARGS`); `scripts/kiosk-console-e2e.mjs`
checks the console and server side of the exit password in headless Chromium.

```bash
scripts/build-escapeapp.sh
LEGACY_PSQL="docker compose --env-file docker/dev.env exec -T postgres psql -U dallycontrol -d dallycontrol" \
  scripts/kiosk-escape-matrix.sh agent-android/app/build/outputs/apk/debug/app-debug.apk mdm_api29 mdm_api35 mdm_api36
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/kiosk-console-e2e.mjs --api http://localhost:8088
```

Results: see the table at the end of [KIOSK-HARDENING.md](KIOSK-HARDENING.md).
## QR provisioning on every Android release (MDM-01)

`scripts/qr-matrix/qr_matrix.py` runs Android's real managed provisioning with a QR payload on a clean emulator per
release. It starts right after the point where the camera reads the QR: `ProvisionIntent.java` (run as root with
`app_process`, userdebug images only) turns the QR's JSON into the same `PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE`
intent the setup wizard builds, so everything after the scan is the platform's own code: Wi-Fi, clock, the agent
download, the signature checksum, the install, Device Owner, and the agent's provisioning callbacks
(`GET_PROVISIONING_MODE` + `ADMIN_POLICY_COMPLIANCE` on Android 12+, `PROFILE_PROVISIONING_COMPLETE` +
`PROVISIONING_SUCCESSFUL` before). The script taps through the consent screens, waits until the console's API says
the token enrolled a device, and saves logcat and the screens it saw.

```bash
# the dev stack (docs/DEV.md) with the debug agent published as /files/dallycontrol-agent.apk; payload.json is what
# web/src/enroll/payload.ts builds, with ${TOKEN}, ${TOKEN12} and "${NOW}" left for the script to fill per device
scripts/qr-matrix/qr_matrix.py matrix --payload payload.json --out /tmp/qr-matrix \
    --mint http://localhost:8088 --admin admin:admin mdm_api24 mdm_api30 mdm_api35 ...

# one device, with the clock set six years back first (a phone out of the box with a dead clock)
scripts/qr-matrix/qr_matrix.py run --serial emulator-5600 --payload payload.json --out /tmp/qr-one --clock-back-years 6

scripts/qr-matrix/enroll_api_check.py http://localhost:8088      # token retries, serial/IMEI de-dup, attempts log
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/enroll-console-e2e.mjs --api http://localhost:8088   # the Enroll page
```

What the emulator run does **not** cover: the camera and the QR decoder themselves (and the download of the QR reader
from Google Play on Android 7-8), the setup wizard of each maker (Samsung/Knox, Xiaomi/HyperOS, Motorola), Play
Protect's verdict on the agent, factory-reset protection (FRP), captive portals, and real Zero-Touch / Knox Mobile
Enrollment assignment. Emulator facts the script works around (none of them are agent behaviour):

- A booted emulator is already "set up", and `DevicePolicyManagerService` keeps its own copy of that fact: with it,
  Android answers "Device already provisioned". On Android 8+ the script clears the two settings and asks the service
  to read them again (`forceUpdateUserSetupComplete`, a platform test API, through `ProvisionIntent --reset-setup`).
  On Android 7 it disables the apps that mark an emulator as set up on every boot (`com.android.provision`,
  `com.google.android.sdksetup`, the image's setup wizard), deletes `device_policies.xml` and reboots.
- With no setup wizard running, nothing "finishes" the provisioning. Up to Android 11 the agent only receives the
  QR's token at that moment, so after 45 s without an enrollment the script does what a wizard does at its end
  (`PROVISION_FINALIZATION`, then the two settings). `--finalize-after 99999` never does it: the "wizard abandoned"
  case, where the result line says whether the agent's own enrollment screen and notification are up.
- `--pause-cmd` / `--resume-cmd` take the server away while the phone enrolls; `--replay-token` deletes the agent's
  stored identity after it enrolled, so it presents the same single-use token again (the answer-was-lost case).
- `matrix --callbacks-apk <apk>` is the fallback for a release whose emulator will not run the wizard part: the agent
  is made Device Owner with adb and receives the payload through the same callbacks Android uses
  (`ProvisionIntent --callback`). It verifies the agent's side only, and the result line says `flow=agent-callbacks`.
- Several emulators at once make every step slow enough to time out; results taken on an overloaded machine are
  reported as not obtained, not as failures.
- Android 6 (API 23) has no QR provisioning (it arrived in Android 7.0): that release enrolls by cable or NFC only.
- Every emulator of a release reports the same serial and IMEI, so "same phone after a factory reset" is checked
  with made-up identities by `enroll_api_check.py`, and the server ignores `EMULATOR*` serials for de-duplication.

Results of `scripts/command-reliability-e2e.py` (2026-11-27, debug agent, dev stack behind Caddy; the machine was
running 13 agents' builds and emulators at the same time, 1-minute load 30–50, so the latencies are an upper bound):

| Android | API | Result | Latency measured by the server, send → executed (10 each) |
|---|---|---|---|
| 16 | 36 | offline/cancel/lost-answer 30/30; report 8/8; wipe 7/7; reboot answered from the ledger, no second reboot | lock p50 3.5 s, p90 8.2 s · alarm p50 4.6 s, p90 12.0 s (load ≈ 38) |
| 11 | 30 | 32 pass, 9 fail: every failure is a wait of the test that ran out under load; all the commands ended executed (see the database) | lock p50 2.1 s, p90 3.9 s · alarm p50 3.7 s, p90 9.6 s · message p50 11.6 s, p90 17.0 s (load 30 → 50) |
| 15 | 35 | before the wake-channel fix: commands queued without link survived 2 h and ran on reconnect, cancel, fast expiry of remote sessions | no live channel: each command waited for the periodic check-in (up to ~10 min) — the worst case |

`scripts/command-console-e2e.mjs` (Chromium, console of the same stack): 16/16 — history with the three steps, latency,
issuer, states, "Borrado" mark, the notice for a device without link, cancel, and the latency report with its Excel.
Repeat the latency phase on an idle machine (`--only latency`) for representative figures.

### Results (2026-11-27, dev stack + debug agent of this branch)

New agent, QR payload from `web/src/enroll/payload.ts`, real provisioning flow (the platform's own code from the
provisioning intent on), to "enrolled with its configuration" in the console. One clean emulator per release.

| Android | Result | How the agent got the token | Attempts |
|---------|--------|------------------------------|----------|
| 6 (API 23) | not applicable | QR provisioning starts in Android 7 | - |
| 7.0, 7.1, 8.0, 8.1, 9 (API 24-28) | enrolled | `qr-legacy`: only once the setup wizard finalized (52-57 s) | 1 |
| 10, 11, 12, 12L, 13 (API 29-33) | enrolled | `qr-early`: the copy kept at `GET_PROVISIONING_MODE`, before the wizard finalized | 1 |
| 14 (API 34) | **not obtained** | the emulator had no network when the download started | - |
| 15, 16 (API 35, 36) | enrolled | `qr-early` | 1 |
| 17 (API 37, both images) | **not obtained** | stayed at "Getting ready for work setup" with ANR dialogs; cause not established | - |

7.1, 8.0 and 8.1 were run before the last merge and not repeated with the final APK; 7.0, 9 and 10 were. The three
runs of Android 13, 15 and 16 landed on one device row: every emulator has the same hardware identifiers.

Cases:

| Case | Result |
|------|--------|
| Wizard abandoned (never finalizes), Android 7.0 / 9 | Device Owner, no token; the agent's screen ("Scan the console's QR" / folder code) and its notification were up |
| Same, Android 8.0 | Device Owner, no token; notification up; the screen was behind a "System UI has stopped" dialog of the emulator |
| Same, Android 10 / 11 | enrolled anyway (`qr-early`) |
| Clock 6 years back, QR **without** the time, HTTPS download - Android 9 and 13 | never becomes Device Owner: stays at "Setting up work device" / "Getting ready for work setup"; logcat shows `CertificateNotYetValidException` |
| Same, QR **with** the time - Android 9 | the clock was set before `DownloadPackageTask` (logcat), but the download still did not finish in 8 minutes; cause not established |
| Same, QR **with** the time - Android 13 | the clock was NOT set before the download (logcat keeps the old date): fails like the QR without the time |
| Clock 6 years back, plain-HTTP download, no time in the QR - Android 9 | enrolled, first attempt |
| Same - Android 13 | not obtained (run stopped) |
| Token presented again by the same phone after the answer was lost - Android 9 | accepted, same device row, credentials stored |
| Same - Android 13 | not obtained (the emulator hung in provisioning) |
| Server down while the phone enrolls | **not run** on a device |
| Remote-support guide vs. the wizard | before the fix it opened on top of Android's wizard (Android 13); after it: never during the wizard, shown once the wizard ended (Android 7.0, 9, 10, 11, 12, 12L, 13) |

Also seen: a cut download cannot be resumed when the server sends no ETag (`DownloadManager` reason 1008, then
"Couldn't download the admin app") - twice, on Android 11 and 13; `/files` now sends ETag and Last-Modified. On
Android 13 a payload without `PROVISIONING_ALLOW_OFFLINE` stops before the download on an image without Google Play.

Server and console: `enroll_api_check.py` 24/24 against Postgres (token retry by hardware id / serial / IMEI, de-dup,
attempts log, tracked download, the report endpoint and the counter); `enroll-console-e2e.mjs` 24/24. The Windows
enroller was run under PowerShell 7 on macOS with a simulated adb (`scripts/windows-enroller-sim.sh`, 9/9), not on
Windows PowerShell 5.1. Not covered by any of this: the camera and the QR reader, each maker's setup wizard, Play
Protect, FRP, captive portals, real Zero-Touch / Knox enrollment, the agent's own QR scanner with a real camera.
