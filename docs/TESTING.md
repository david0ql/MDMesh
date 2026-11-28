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

Final pass on the fully merged tree (2026-11-27, Android 16 / API 36 Google APIs, `--mock-provider`, mobile-data
policy active, heap `-Xmx420m`, host load 22–42): trip inside → outside → inside, zone created with the phone already
outside, diagnosis and e-mail passed (36/39). The three misses were the test's, not the product's: that image keeps an
emulated Ethernet up, so `svc wifi/data disable` does not take it offline (the offline batch is covered on API 29 and
33; the script now detects this and skips the section), and one row-count race. With lost mode on (W9, a position per
minute) positions kept arriving and a new zone was evaluated. `geofence-console-e2e.mjs`: 29/29.

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
- `--clock-back-years N` alone turns automatic time off and leaves the Wi-Fi as it was (already validated).
  `--clock-at-join` joins the Wi-Fi again after the clock went back, as a phone that boots with that clock does;
  `--clock-auto` also leaves automatic time on with the radio off and Android's remembered time cleared, so only the
  Wi-Fi's network time (NTP) can correct the clock. The line "Wi-Fi joined with that clock" says what happened.
- Android 17 blocks apps without the local-network permission from the subnet the phone is attached to, and the
  platform's downloader has no such permission. On an emulator that subnet holds 10.0.2.2 ("the host"), so a download
  from there never connects. `scripts/qr-matrix/host_forward.py 8133 8088` exposes the stack on the host's LAN
  address (to this machine only); put that address in the payload's download location.
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
| 14 (API 34) | enrolled (2026-11-28; Device Owner 20 s after the scan) | `qr-early` | 1 |
| 15, 16 (API 35, 36) | enrolled | `qr-early` | 1 |
| 17 (API 37; images 37.0 `google_apis` and 37.2 `google_apis_ps16k`) | enrolled (2026-11-28; Device Owner 25 s after the scan) - with the agent download on an address outside the emulator's own subnet, see "Third round" | `qr-early` | 1 |

7.1, 8.0 and 8.1 were run before the last merge and not repeated with the final APK; 7.0, 9 and 10 were. The three
runs of Android 13, 15 and 16 landed on one device row: every emulator has the same hardware identifiers.

Cases:

| Case | Result |
|------|--------|
| Server, simulated device, with `--mailpit` | 55/55 (one e-mail with both alerts, linked to the device) |
| Console in headless Chromium | 37/37 |
| Emulator API 30 (Android 11) | 94/95 — the one miss was a check of this script (it looked for the metered list in `dumpsys device_policy`, which Android 11 does not print); fixed |
| Emulator API 35 (Android 15) | first run 93/95 (two checks of this script: a stale state read while the phone switched mechanism, and `no_config_vpn` looked up in `dumpsys device_policy`, where Android 15 keeps an empty entry; both fixed). Final run after merging `integration/mobicontrol`, stack and APK built from that tree, droidVNC-NG installed: **107/107** in 7 min, console 37/37 |

On both emulators, with both mechanisms: the blocked app failed on mobile data and worked on Wi-Fi, the allowed app
and the agent's check-in kept working on mobile data, and switching Wi-Fi ↔ mobile changed the outcome each time. With
the local VPN there was no consent dialog, `always_on_vpn_app` was the agent and it was removed when the policy was.
The emulated cellular network is counted per app by `NetworkStatsManager`, so the limits ran for real: a 1.3 MB
download against a 2 MB app limit with a 50 % threshold raised the warning, the next one the limit alert, and the app
lost mobile data without the server telling it to.

More fleet-inventory checks (2026-11-27, own stack, machine under heavy load — figures marked with the load):

- `scripts/fleet-console-e2e.mjs` — the console in headless Chromium (`npx -y -p playwright@1.48 node …`): Dispositivos →
  Exportar (Excel and CSV; everything / what the table shows / the selection, every downloaded file opened), the
  "Dato de" column, "Actualizar ahora" (list and device page), Reportes → Inventario de la flota (tiles, thresholds,
  exports) and Envíos programados (create, edit, pause, resume, "Enviar ahora", delete; `--no-smtp` for the notice).
  `--folder-user` signs in as a folder administrator. `--slow N` multiplies the waits on a loaded machine.
- `scripts/fleet-report-scope-e2e.py` — a user limited to one folder, over the API: exports, inventory report and
  scheduled e-mails hold only that folder's devices; other folders' schedules cannot be seen, edited, sent or deleted.
- `scripts/dev-synthetic-fleet.py` — SQL for a synthetic fleet (folders, devices with their census, a month of
  sessions) to load-test a dev database; `--remove` deletes it.

Results: browser as administrator 33/33 (5,001 devices: 29 checks before the mail part, then 33/33 with STARTTLS mail);
browser as folder administrator 35 checks passed (262 of 5,001 devices; mail verified over the API); browser without
SMTP 31/31; folder scope over the API 33/33; Android 9 (API 28): device 76/76 and browser with the live device 34/34
(refresh 1.6 s, load 33); Android 15 (API 35): device 76/76 and browser 34/34 (refresh 4.4 s, load 38; enrolling
needed the `dpm set-device-owner` retry of the first minutes after boot). Scheduled e-mails over STARTTLS + user/password against Mailpit started with
`--smtp-require-starttls --smtp-auth-file` (it answers 530 without STARTTLS or without AUTH): "Enviar ahora" and the
scheduler-fired run both delivered. 5,000 devices + 103,000 sessions, server heap capped at 512 MB: Excel 5.9–6.5 s,
CSV 0.6–1.4 s, inventory report 1 s (measured before the machine saturated).

## Auditoría y roles (MDM-15)

What is checked, and with what:

| Check | How |
|-------|-----|
| Every state-changing route is audited | `AuditCatalogCoverageTest` (server tests): reads the JAX-RS annotations of every resource and fails when a reachable POST / PUT / DELETE has no entry in `AuditCatalog` (or is recorded under another route's rule, or the catalog names a route that no longer exists) |
| What a row contains, secrets never kept, before / after, result | `AuditTrailServiceTest`, `AuditPiecesTest`, `AuditTrailFilterTest`, `AuditHashTest` |
| The trail end to end, as a limited technician | `scripts/audit-e2e.py` (needs an enrolled device; an emulator is enough) |
| Each permission of the role matrix restricts the API | `scripts/roles-matrix-e2e.py` (no device needed) |
| The Auditoría screen, the device / user activity, Roles | `scripts/audit-console-e2e.mjs` (headless Chromium) |

```bash
# the technician, the allowed and refused actions (a real command to the device, a wipe without permission…), the
# rows, the scope of a folder user, the exports, immutability (also at SQL level) and the retention purge
scripts/audit-e2e.py --api http://localhost:8080 --device <device number> --db-container dallycontrol-dev-postgres-1

# for EACH permission: a role without it is refused on its endpoints, the same role with it passes; role life cycle,
# no privilege escalation, the ready-made roles (--only perm1,perm2 runs a subset)
scripts/roles-matrix-e2e.py --api http://localhost:8080 [--device <device number>]

# the console (once: cd scripts/shots && npm install && npx playwright install chromium)
node scripts/audit-console-e2e.mjs --web http://localhost:8088 --device <device number> --shots /tmp/audit-shots
```

A new mutating endpoint makes `AuditCatalogCoverageTest` fail until it gets a line in
`server/src/main/java/com/hmdm/audit/AuditCatalog.java` (`audit(...)` with a Spanish label, or `skip(...)` with the
reason). Until then the request is still recorded, with a generic label. A new permission belongs in
`web/src/data/roleCapabilities.ts` and in the `MATRIX` of `scripts/roles-matrix-e2e.py`, with the endpoints it gates.

Result (2026-11-27, own stack on an empty database + debug agent 0.9.7 on a clean API 35 emulator, through Caddy):
`audit-e2e.py` 97/97 (the allowed command reached the device and was reported `delivered`; the wipe, lock and
passcode attempts were refused and recorded as denied; SQL `UPDATE` / `DELETE` / `TRUNCATE` on `audit_trail` refused;
the purge removed the planted 120-day-old row and recorded itself); `roles-matrix-e2e.py` 19 permissions, 274 calls,
308/308; `audit-console-e2e.mjs` 48/48. The emulator has no droidVNC-NG, so starting a remote session answered "the
device does not support remote control" (recorded as an error row); the duration of a session that did open is
covered by `AuditTrailServiceTest` and needs a phone with remote support to be seen end to end.

## App permissions without the user (MDM-06, [PERMISOS.md](PERMISOS.md))

```bash
scripts/build-permtestapp.sh                       # com.dallycontrol.permtest -> scripts/testapp/permtest.apk
scripts/permissions-e2e.py --api http://localhost:8088 --serial emulator-5600 --device-id <device number>
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/permissions-console-e2e.mjs --api http://localhost:8088
```

The test app declares location (fine, coarse, background), camera, microphone, storage/media, contacts and
notifications plus the special accesses, and on every start asks for each runtime permission it does not hold, like
a real app; it logs what it holds (`adb logcat -s PermTest`).

`permissions-e2e.py` drives a REAL agent (Device Owner) through the console's API and checks the phone itself:

- the three default policies reach the phone (`dumpsys device_policy`: permission policy 0 / 1 / 2) and the console
  shows the one in force; the `config.apply` result carries the `permissions` outcome;
- "grant all the app declares" in the policy, then a silent install from the console: `dumpsys package` shows every
  runtime permission `granted=true` with `POLICY_FIXED` (background location and, on Android 13+,
  `POST_NOTIFICATIONS` included), the app's own screen is in front (no `GrantPermissionsActivity`) and its log says
  `missing=0 asked=0`;
- a per-device rule denies the camera (`granted=false`, `POLICY_FIXED`), "ask" releases it, removing the device
  rule brings the policy's grant back; "ask" after a grant of the MDM takes the permission back;
- an app installed by hand BEFORE the rule gets its permissions when the rule arrives; a rule removed while the app
  is uninstalled does not leave anything fixed when the app comes back (Android 14+ re-applies it on its own);
- with "grant all" and no rule, the app asks and Android grants with no dialog;
- special accesses: battery protection through `setUserControlDisabledPackages`, "needs a tap" for all files /
  overlay / unknown sources, `device.permissionScreen` opens the exact Settings screen, and once the access is turned
  on (the tap is simulated with `appops set`) the console shows it active;
- the policy and the grants survive an agent restart.

`permissions-console-e2e.mjs` checks the console in headless Chromium with a scripted phone: the policy editor (no
dead options, 13 permission rows, 8 special accesses, what is saved and what the phone receives), the device's
«Permisos» tab (effective policy, real state per app, per-device rules, «Abrir en el teléfono», CSV), the Android 12+
"this phone does not let the administrator grant sensor permissions" warning, and the "update the agent" notice.

### Results (2026-11-27, isolated dev stack, debug agent built from this branch after merging `integration/mobicontrol`)

| What | Result |
|---|---|
| `permissions-e2e.py`, emulator Android 10 (API 29) | 67/67 |
| `permissions-e2e.py`, emulator Android 15 (API 35) | 76/76 |
| `permissions-e2e.py`, emulator Android 16 (API 36) | 76/76 |
| `permissions-console-e2e.mjs`, headless Chromium | 44/44 |

Notes from the runs:

- Android 14+ prints the permission policy in `dumpsys device_policy` (`Permission policy: {0=1}`); Android 10 does
  not, so there the script reads it from `/data/system/device_policies.xml` through the emulator's root shell (on a
  phone without root that check is skipped and the agent's own report is used).
- Android 10 does not let an app read another app's app-ops: overlay / write settings / unknown sources are reported
  as "Android does not let it be read" there; from Android 11 they are read.
- Android 14+ keeps a permission fixed by the Device Owner across an uninstall and re-applies it when the app comes
  back; the agent releases it then if its rule is gone (step 6b).
- With "grant all", Android 16 on a loaded machine took ~10 s to answer the app's request (its permission activity
  passes by with no dialog); the script waits for it instead of sampling once.
- Not covered by an emulator: a phone provisioned with the "administrator may not grant sensor permissions" opt-out
  (`dpm set-device-owner` cannot set it; covered by the planner's unit tests and the console test with a scripted
  phone), and Android 7 or older (`setSecureSetting(INSTALL_NON_MARKET_APPS)` for unknown sources).

## Hardware y puertos (MDM-12)

`scripts/hardware-e2e.py` runs against a real enrolled device (see [HARDWARE.md](HARDWARE.md)). On the policy the
device uses (restored at the end) it: blocks every hardware rule, the older toggles (screenshots, USB storage,
Bluetooth), data sharing and a free list of Android restrictions; checks the server status ("Restricciones aplicadas")
rule by rule, `dumpsys user`, `dumpsys device_policy`, that a screenshot fails or is black, that the camera is refused,
that MTP is refused (`svc usb`) and that the hotspot cannot be switched on in Settings; blocks USB debugging and checks
that adb stops answering; reverts everything and checks that adb comes back by itself and nothing is left; and sets and
removes a per-device camera exception. Rules newer than the device's Android must be reported `unsupported: sdk-N`.

```bash
scripts/hardware-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <device number>
# on a phone you reach only by cable, keep adb: --keep-usb-debug

(cd scripts/shots && npm ci)       # once: Playwright
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/hardware-console-e2e.mjs --api http://localhost:8088 [--device <number>]
```

Result (2026-11-27, own stack + debug agent on clean emulators; a rule the release cannot honour must be reported, so
the totals differ):

| Android | API | `hardware-e2e.py` | Notes |
|---|---|---|---|
| 9 | 28 | 57/57 | USB data, NFC, Wi-Fi Direct, content capture and assistant reported `unsupported: sdk-N`; date/time with the restriction; no camera app on the image (camera check skipped, policy state verified) |
| 15 | 35 | 58/58 | after merging the base branch; NFC reported `unsupported: platform` (this image refuses the restriction); `hardware-console-e2e.mjs --device` 26/26 |
| 16 | 36 | 57/58 | NFC applied; the one failure was the script counting `dumpsys user`'s per-user-type templates as restrictions in force (fixed, re-verified on API 35) |

On every release: adb stopped answering when USB debugging was blocked and came back by itself after the revert; the
emulators report `canUsbDataSignalingBeDisabled() = true`, so "USB data" was applied and reverted there. `svc usb
getFunctions` gives no answer on these images, so the MTP check is skipped (the `no_usb_file_transfer` restriction is
verified instead).

## Remote support through the agent (ADR 0012)

Screen, touch, voice, chat and files over the agent's own session channel, with no droidVNC-NG. Unit tests:
`SupportSessionsTest` (relay rules, view-only, audit, capabilities, policy), `SupportHubTest` (when a session ends by
itself), `UserScopeFilterTest#remoteSupportIsScopedToTheirDevices`, and on the agent `SupportProtocolTest` (µ-law
codec, frame layout, scaling, typing, readiness, compatibility with older servers) and `SupportChannelTest` (the
device's socket against a real WebSocket server).

End to end, on an emulator or a phone (`scripts/remote-e2e.mjs --support …` runs both):

```bash
# the operator's side of the session socket, no browser: every check is on the wire or read back over adb
node scripts/remote-support-e2e.mjs --api http://localhost:8088 --device <id> --serial emulator-5554 [--authorize]
# the console's screen in headless Chromium (synthetic microphone): image, soft keys, click, chat, call, history
node scripts/remote-support-console-e2e.mjs --api http://localhost:8088 --device <id> --serial emulator-5554
# from a shell: status, start (prints key + token), stop, log
scripts/remote-session.sh --api http://localhost:8088 --device <id> --support --status
```

**QR-style enrollment on an emulator.** `adb install -g` (what `scripts/adb-enroll.sh --apk …` does) grants the agent
WRITE_SECURE_SETTINGS, so that device needs no tap at all. To reproduce a QR / zero-touch enrollment, install the
agent first **without** `-g` (`adb install agent.apk`) and run `scripts/adb-enroll.sh` without `--apk`: the agent then
holds nothing a Device Owner cannot grant itself, shows the guided screen by itself after enrolling, and
`remote-support-e2e.mjs --authorize` does the one tap on the phone's real Settings screens before going on.

`uiautomator dump` (used by both scripts to read what is on the phone) makes Android unbind every accessibility
service for a moment, the agent's screen service included; the scripts wait for it to be bound again. A real session
never meets that.

Voice on an emulator started with `-no-audio`: the phone's microphone records silence, so a debug agent sends a 440 Hz
tone when the call is started with `testTone` (the scripts do), and the checks are on level and pitch at both ends;
the real microphone path is still opened and checked (`recording`, frames flowing).

Results (2026-11-27, own stack, debug agent merged with `integration/mobicontrol`, emulators at 720x1600, enrolled
QR-style: no adb grant). **The test machine was at load 20-35 (15 cores) during every run, so the times are upper
bounds, not the product's speed**; the host load is printed with each run's measures.

| | Android 11 (API 30) | Android 15 (API 35) |
|---|---|---|
| Device-Owner self-authorisation (`info.probe`) | grant refused, `setSecureSetting` SecurityException | same |
| Guided screen after enrollment, one tap in the real Settings screens, console goes from "Falta autorización" to "Remoto listo" | pass | pass |
| Authorisation kept after a reboot and after updating the agent | pass | pass |
| `remote-support-e2e.mjs` (merged agent) | 35/40 | 37/40 |
| `remote-support-console-e2e.mjs` (merged agent) | 20/20 | 19/20 (the one miss was the test's own mute check, since fixed; 20/20 on API 30 with the fix) |
| Device joins after "start" | 0.4 s | 1.1 s |
| First image (protocol / console click) | 0.7 s / 2.4 s | 2.3 s / 1.8 s |
| Touch to image | 1.2 s at load 29 | 0.26 s at load 22 (0.7 s at load 27, 2.3 s at load 35) |
| Images while scrolling | 0.6 /s at load 29 | 2.1 /s at load 22 |
| Capture / encode per image | 204 ms / 28 ms | 209 ms / 28 ms |

What did not pass in the protocol script, and why:

- **Typing a reply through the remote session and tapping Enviar** (API 30 and 35): the reply typed *on the phone*
  (console script, over adb) arrives and is logged; the variant where the operator taps the reply box, types and taps
  Enviar through the session did not complete in any run. Typing into a focused field through the session was seen
  working once (API 35, text read back with uiautomator); the script's way of finding the box and the button on a
  busy emulator is the suspect, but it is **not confirmed**. Check on a phone: open a text field, type from the console.
- **Notice turned off by the policy while a session is open** (API 35, 2 of 3 runs): the session notification stayed.
  A session opened after the policy has arrived shows no notice (API 30 pass).
- **Touch-to-image under 1 s and images while scrolling** on API 30 at load 29: see the table; Android 11 also
  refused some screenshots as "too soon" at 340 ms spacing, so the agent backed off to 500 ms there.
- **The support screen for a chat line** was not seen on top in the last API 30 protocol run (it was in the console run
  of the same boot and in the earlier ones).

Not run: Android 7-10 (the screen-capture path with its consent pressed by the service), kiosk, a physical phone.


## Connection stability (MDM-02): reconnection, diagnostics, resting state

```bash
# the customer's network check (DNS, port, TLS issuer, HTTPS, WebSocket upgrade); see docs/REQUISITOS-RED.md
scripts/prueba-red.sh https://<server>

# two "bad networks" in front of the dev stack, to see the tools and the agent fail the right way
scripts/red-simulada.py tls  --listen 8443 --upstream localhost:8088 &   # certificate of a made-up firewall CA
scripts/red-simulada.py nows --listen 8122 --upstream localhost:8088 &   # 403 to any WebSocket upgrade
scripts/prueba-red.sh https://localhost:8443 http://localhost:8122        # expect one FALLA each (TLS, WebSocket)

# a real agent (debug build, enrolled with scripts/adb-enroll.sh): cuts the network in several ways, measures the
# time back to the live channel and to a command's result, and checks what reaches /rest/private/connection/v1.
# The stack needs AGENT_BACKUP_URLS (the tls scenario reports through the backup URL).
scripts/conexion-e2e.py --serial emulator-5554 --device-id <id> --api http://localhost:8088 \
    --server-url http://10.0.2.2:8088 --tls-url https://10.0.2.2:8443 --nows-url http://10.0.2.2:8122 \
    --backup-device-url http://10.0.2.2:8080 --compose "docker compose --env-file docker/dev.env" \
    --db-container dallycontrol-dev-postgres-1 [--only baseline,wifi,outage,airplane,doze,server,failover,tls,nows,resting,reboot]

# the console in headless Chromium: the "Última falla de conexión" card and "En reposo"
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/consola-conexion-e2e.mjs --api http://localhost:8088 --device <id> \
    --expect "Certificado TLS rechazado" --issuer "FortiGate CA simulada" [--resting <id shown as En reposo>]
```

Results (2026-11-27, own stack through Caddy, debug agent on clean emulators). The machine ran about 13 agents at
once (load average 27–40 during every run), so the times are upper bounds, not what an idle machine gives.

| Scenario | Android 16 (API 36) | Android 10 (API 29) |
|---|---|---|
| Command with the live channel open | 0.7–0.9 s at load ~20; 2.0–11.6 s at load ~30 | 1.9–3.9 s |
| Wi-Fi off, falls to mobile data: command done | 2.0 s (channel live again in 3.3 s) | 6.8 s (17.0 s) |
| Wi-Fi back: command done | 6.4 s | 2.8 s |
| 90 s with no network, then back: channel / command queued meanwhile | 16.5 s / 39.5 s (earlier run: 3.9 s) | 1.5 s / 12.2 s |
| Airplane mode 60 s, then off: command queued meanwhile | 35.9 s (the emulator's radio takes most of it) | not run |
| Forced Doze (screen off, unplugged): command | 17.7 s | 4.4 s, 2.1 s; channel open 98 % of 5 min, 0 reconnections |
| Server drops a socket whose phone lost the network (150 s idle) | 146.9 s | 156.0 s |
| Server restart: command after it answers again | not measured (run lost to the test machine) | 2.9 s (channel 38.6 s, limit was 30 s) |
| Network inspects TLS (red-simulada tls) | console gets `tls` + issuer "FortiGate CA simulada" in 212 s, through the backup URL; command by the backup URL 4.9 s | not run with the final build |
| Network blocks WebSocket (red-simulada nows) | console gets "WebSocket rechazado (HTTP 403)" in 131 s; commands keep arriving (3.3 s) | not run |
| "En reposo" vs "Sin conexión" | screen off + silent 45 min = resting; after 17 h = offline; in use + silent = offline | not run (docker exec timed out) |
| Orderly reboot | goodbye report received ("Apagado o reinicio del equipo"), closed with its duration; channel back 55 s after the reboot command, without opening the app | channel back in 53.9 s, command 4.0 s (goodbye not received with the earlier build) |

Console (`consola-conexion-e2e.mjs`): 9/9. `prueba-red.sh`: passes against the stack, fails with the right cause
against both simulated networks.

Not covered by an emulator: makers' battery managers (HyperOS, One UI) stopping the service, a real Wi-Fi to
mobile hand-over while walking, Doze over hours (forced Doze only), and the path through Cloudflare (WebSocket ping
frames and the 150 s idle limit were only checked through the local Caddy).

## Factory-reset protection, lost mode and remote wipe

`scripts/frp-wipe-e2e.py` checks, on a real enrolled agent, the two anti-theft criteria (MDM-03 and MDM-09; the user
guide is [FRP-BORRADO-REMOTO.md](FRP-BORRADO-REMOTO.md)):

```bash
scripts/frp-wipe-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id> --destructive
scripts/frp-wipe-e2e.py --api http://localhost:8088 --only reenroll        # API only, no device
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/frp-console-e2e.mjs --api http://localhost:8088 \
    --device-id <numeric id> --policy "Managed Launcher"                   # the console screens, headless Chromium
```

- `frp`: company default account, a policy that blocks reset from Settings / safe mode / OEM unlock and wipes after 10
  wrong unlocks; `dumpsys device_policy` and the agent's own read-back must agree, and the console must say what the
  device really holds. Android 10 and older go through Google Play services' `factoryResetProtectionAdmin`
  restriction (read back from `/data/system/users/0/res_com.google.android.gms.xml`); Android 11+ through
  `setFactoryResetProtectionPolicy`.
- `lost`: lost mode end to end (lock, message + phone on the lock screen, a position every minute, wipe after N wrong
  unlocks, back to the policy's values when it ends).
- `reenroll`: a phone that enrolls again after a reset returns to its row / folder by serial or IMEI; a deleted one is
  re-created from its tombstone; junk identifiers never match.
- `wipe-online`, `wipe-offline` (`--destructive`): the agent announces the wipe before running it, flags per option,
  who ordered it and with which options, and a wipe queued without link for 3 days that runs on reconnect.

Emulator limits: no `/misc` partition (the reset is accepted as handed to recovery, as above) and no persistent data
block, so on Android 11+ `setFactoryResetProtectionPolicy` answers "unsupported" there. The test then requires the
console to say "FRP inactiva: este equipo no admite…" instead of a false "activa". The FRP challenge after a reset
by hardware buttons can only be confirmed on a physical phone with Google services: the steps are in
[FRP-BORRADO-REMOTO.md](FRP-BORRADO-REMOTO.md), section 8.

Result (2026-11-27, own stack, debug agent built from this branch after merging `integration/mobicontrol`; machine
load 26-34 while measuring, so no timing is claimed):

| Android | API | frp + lost | wipe-online | wipe-offline | console (`frp-console-e2e.mjs`) |
|---------|-----|------------|-------------|--------------|----------------------------------|
| 10 | 29 | 26/26: FRP active through Google Play services, account read back from its restrictions file | 8/8, `wipeData(flags=3)` | 13/13, `wipeData` with reason, flags 0 | 34/34 with lost mode really sent and turned off |
| 15 | 35 | 25/25: restrictions held; `setFactoryResetProtectionPolicy` answers "The persistent data block service is not supported on this device" and the console shows "FRP inactiva: este equipo no admite…" | 8/8, `wipeDevice(flags=3)`; the image then erased its user data in place and restarted | 13/13 on a fresh emulator, `wipeDevice(flags=0)` | 32/33 before the script waited for the device's answer; not repeated on this API |

`--only reenroll`: 11/11. Not covered on an emulator: the FRP challenge after a reset by hardware buttons, and FRP
actually held on Android 11+ (no persistent data block on the images).

## Corporate Wi-Fi (MDM-13)

`scripts/wifi-policy-e2e.py` checks the policy's Wi-Fi against a real enrolled Device Owner (see [WIFI.md](WIFI.md)):
it uploads a CA and a client PKCS#12 made on the spot, saves one network of each kind (hidden PSK, WPA3-SAE, WPA2/WPA3,
OWE, PEAP, TTLS, EAP-TLS, and one with every option) and reads the phone over adb (`cmd wifi list-networks`,
`dumpsys wifi`, `dumpsys device_policy`): parameters, installed certificates, the lock-down (`cmd wifi forget-network`
refused), the Android 13 SSID allow list and minimum security, the forced radio, the safeguard that relaxes a
lock-down which left the phone without any network, and the removal with its certificates. The console is covered by
`scripts/wifi-console-e2e.mjs` (headless Chromium).

```bash
scripts/wifi-policy-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <id>   # ~8 min; --skip-safeguard: ~4
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/wifi-console-e2e.mjs --api http://localhost:8088 [--device <id>]
```

The emulator only has an open network (`AndroidWifi`), so association is real for that one and the rest is checked as
saved configuration. Authentication against WPA2/WPA3/802.1X needs an access point: WIFI.md, "Con un punto de acceso
real".

Result (2026-11-27, own stack + debug agent after merging `integration/mobicontrol`, clean emulators; run twice, on two
successive merges of the base branch, host load 2–30, same numbers both times):

| Emulator | Checks | Not applicable there (reported by the agent, not failures) |
|---|---|---|
| Android 9 (API 28) | 70/70 | WPA3 and OWE (no API before Android 10: reported "not supported"), metered flag refused, `installKeyPair` needs a screen lock, no `cmd wifi forget-network` (the lock-down setting is checked instead), Android 13 rules reported as not available |
| Android 11 (API 30) | 78/78 | device-MAC option refused; Android 13 rules reported as not available |
| Android 15 (API 35) | 86/86 | — |

Console: `wifi-console-e2e.mjs` 30/30 with `--device` (29 without). The runs found and fixed three agent bugs: on
Android 13+ a static IP dropped the proxy; a network saved while the radio was starting stayed disabled for auto-join
(Android 11); and "out of range" could not be told without a fresh scan (Android 9). Under a host load above 40 one
earlier attempt lost the emulator's `system_server` (thread-suspend timeout) and the agent could not start before its
ANR deadline until it was precompiled (`cmd package compile -m speed`); neither happened again below load 30.

## Second wake channel (push FCM, backup polling) — MDM-07

Full description, the steps to load a real Firebase project and the measured latencies: [DESPERTADOR-FCM.md](DESPERTADOR-FCM.md).
No Firebase credentials are needed to test: Google's side is simulated.

```bash
# unit: fake FCM v1 service (when a push goes out, to which token, retries, debounce) and the agent's schedule
mvn -q -pl common,server -am test -Dtest='FcmClientTest,PushWakerTest' -Dsurefire.failIfNoSpecifiedTests=false
(cd agent-android && ./gradlew :core:testDebugUnitTest --tests '*Wake*')

# end to end on an emulator: point the stack at the simulator, enroll THROUGH its gate, measure
scripts/wake-latency-e2e.py env --port 8126 > /tmp/fcm-sim.env          # export these before `docker compose up`
scripts/wake-latency-e2e.py sim --port 8126 --upstream 127.0.0.1:8088 &
scripts/adb-enroll.sh --server http://10.0.2.2:8126 --api-url http://localhost:8088 --apk app-debug.apk --debug-build --admin-user admin
scripts/wake-latency-e2e.py run --api http://localhost:8088 --sim http://127.0.0.1:8126 --serial emulator-5554 \
    --device-id <device number> --md-out /tmp/latencias.md
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/wake-console-e2e.mjs --api http://localhost:8088 \
    --device-id <numeric id> --device <device number> --config <configuration id> --sim http://127.0.0.1:8126
```

Closing run on the merged tree (2026-11-27, no emulator; own light stack started from existing images with this
tree's WAR and web build, a device record as fixture): `roles-matrix-e2e.py` 19 permissions, 290 calls, 324/324 (now
also mobile data, hardware and ports, app permissions, remote support, cancel a command, FRP defaults and Wi-Fi
certificates); `audit-console-e2e.mjs` 56/56 (adds: no user answer carries a password hash or token, the retention
panel stays hidden while the viewer's scope is unknown, the "Campo" column keeps a field name on one line, opening a
role card does not stretch the others, one matrix row per permission the server checks). `UserSecretsJsonTest`
guards the JSON of every answer that carries a user.

Final runs of `scripts/command-reliability-e2e.py` (2026-11-27 evening, tree merged with `integration/mobicontrol`,
debug agent built from it, dev stack behind Caddy with `-Xmx420m -XX:+UseSerialGC`; all phases, wipe included):

| Android | API | Result | Host load during the run |
|---|---|---|---|
| 11 | 30 | 56 pass, 3 fail: only the latency thresholds (measured with the agent before the faster result report); every functional phase clean | 37–40 |
| 15 | 35 | 57 pass, 2 fail: only the latency threshold of lock and alarm; every functional phase clean | 29–40 |
| 16 | 36 | 59/59 | 16–30 |
| 11 | 30 | latency only, final agent: 9/9 | 20–23 |

Latency measured by the server (created → delivered → completed), 10 repetitions each, device online with its live
channel, final agent:

| Command | Android 11 (load ≈ 21): delivery p50 / p90 · executed p50 / p90 / max | Android 16 (load 18–30): delivery p50 / p90 · executed p50 / p90 / max |
|---|---|---|
| Lock | 0.27 / 0.64 s · 0.41 / 0.92 / 1.99 s | 0.20 / 0.43 s · 0.30 / 0.59 / 1.17 s |
| Alarm (ring) | 0.12 / 0.24 s · 0.33 / 0.50 / 1.72 s | 0.45 / 1.37 s · 0.90 / 3.88 / 5.87 s |
| Message (alert) | 0.11 / 0.19 s · 0.16 / 0.28 / 0.49 s | 0.55 / 1.00 s · 0.87 / 1.50 / 3.70 s |
| Lock-screen message | 0.12 / 0.35 s · 0.18 / 0.43 / 0.59 s | 0.64 / 1.41 s · 1.29 / 2.03 / 2.81 s |

What made "executed" late before: the agent took the whole device census again (it can wait on a location fix) in the
round that only reports a result. That round now carries just the result. `scripts/command-console-e2e.mjs`: 16/16.

The wiped mark: set when the agent announces the wipe (or after 10 minutes of silence for an agent that cannot
announce); kept while the phone stays silent; removed, with a `wipeNotCompleted` line in the device's history, when
the phone reports again with its old credentials (a wiped phone has none); cleared without that line when the phone
enrolls again.

Living with the rest of the agent (final run, API 35): with the local VPN up and capturing on mobile data — blocklist
and allowlist — the wake channel stayed connected and a command sent from the console ran on the phone in 2.3–2.5 s
(load average ~30); the agent and remote support (droidVNC-NG) were never in the captured list and the console kept
reporting the remote tier `control`. A full remote session through the repeater with the VPN up was not run here (the
stack was started without the `remote` profile).

Final repeat (2026-11-27 21:24-21:36, tree = `integration/mobicontrol` with everything merged, own stack with
`-Xmx420m -XX:+UseSerialGC`, Android 15 / API 35, load 15-22): `frp` + `lost` + `reenroll` 35/36, then 25/25 after the
fix below; console 34/34 (lost mode really sent and turned off); `wipe-online` 8/8; `wipe-offline` 13/13. The one
failure was real: a follow-up check-in (it carries no census) stored an empty telemetry, so the device's protection
status was blank until the next full report; `device_state.telemetry` now keeps the last census when a check-in
brings none. The emulator booted again after its wipe enrolled onto its own row (same hardware id), as designed.

| Wizard abandoned (never finalizes), Android 7.0 / 9 | Device Owner, no token; the agent's screen ("Scan the console's QR" / folder code) and its notification were up |
| Same, Android 8.0 | Device Owner, no token; notification up; the screen was behind a "System UI has stopped" dialog of the emulator |
| Same, Android 10 / 11 | enrolled anyway (`qr-early`) |
| Clock 6 years back, QR **without** the time, HTTPS download - Android 9 and 13 | never becomes Device Owner: stays at "Setting up work device" / "Getting ready for work setup"; logcat shows `CertificateNotYetValidException` |
| Same, QR **with** the time - Android 9 | the clock was set before `DownloadPackageTask` (logcat), but the download still did not finish in 8 minutes; cause not established |
| Same, QR **with** the time - Android 13 | the clock was NOT set before the download (logcat keeps the old date): fails like the QR without the time |
| Clock 6 years back, plain-HTTP download, no time in the QR - Android 9 | enrolled, first attempt |
| Same - Android 13 | see "Third round": with that clock the Wi-Fi is not validated and no download starts, HTTP or HTTPS |
| Token presented again by the same phone after the answer was lost - Android 9 | accepted, same device row, credentials stored |
| Same - Android 14 and 17 (2026-11-28) | accepted, same device row, credentials stored |
| Server down while the phone enrolls (2026-11-28) | Android 15, server stopped behind the proxy: enrolled 108 s after Device Owner, on the 7th try, once the server was back. Android 13, proxy and server both stopped (connection refused): enrolled 125 s after Device Owner. Meanwhile the phone showed "Could not enroll yet. Retrying…" with the reason; the token was never lost |
| Remote-support guide vs. the wizard | before the fix it opened on top of Android's wizard (Android 13); after it: never during the wizard, shown once the wizard ended (Android 7.0, 9, 10, 11, 12, 12L, 13) |

Also seen: a cut download cannot be resumed when the server sends no ETag (`DownloadManager` reason 1008, then
"Couldn't download the admin app") - twice, on Android 11 and 13; `/files` now sends ETag and Last-Modified. On
Android 13 a payload without `PROVISIONING_ALLOW_OFFLINE` stops before the download on an image without Google Play.

Server and console: `enroll_api_check.py` 24/24 against Postgres (token retry by hardware id / serial / IMEI, de-dup,
attempts log, tracked download, the report endpoint and the counter); `enroll-console-e2e.mjs` 24/24. The Windows
enroller was run under PowerShell 7 on macOS with a simulated adb (`scripts/windows-enroller-sim.sh`, 9/9), not on
Windows PowerShell 5.1. Not covered by any of this: the camera and the QR reader, each maker's setup wizard, Play
Protect, FRP, captive portals, real Zero-Touch / Knox enrollment, the agent's own QR scanner with a real camera.

**Third round (2026-11-28, idle machine, load 5-20; own stack, debug agent of this branch).** Real provisioning flow
on a clean emulator each time; "total" is the whole run of the script (it includes up to 70 s of watching the wizard
after the enrollment, or the token replay).

| Case | Android | Result |
|------|---------|--------|
| Plain QR flow | 14 (API 34) | enrolled; download 1.5 s, Device Owner at 20 s, total 60 s. The earlier "not obtained" was the overloaded machine (the emulator had no network), not the product |
| Plain QR flow, download from `10.0.2.2` | 17 (API 37) | never becomes Device Owner: `DownloadManager` reports `failed to connect to /10.0.2.2 ... after 20000ms` and retries for ever, while root and shell reach the same address at once. `dumpsys connectivity trafficcontroller` shows why: `10.0.2.0/24` is in the local-network map and the downloader's uid has no `PERMISSION_ACCESS_LOCAL_NETWORK` (Android 17's local network protection). Emulator-only for a server on the internet; a real limit for a server inside the same subnet as the phones' Wi-Fi |
| Plain QR flow, download from the host's LAN address | 17, both images | enrolled; Device Owner at 25 s, total 132 s (37.0) and 71 s (37.2) |
| Sanity of an old release | 9 (API 28) | enrolled, `qr-legacy`, 55 s after Device Owner |
| Same token presented again after the answer was lost | 14, 17 | accepted, same device, credentials stored |
| Clock 3 years back when the Wi-Fi is joined, automatic time on, only NTP available | 13, 16 | the clock was correct 8 s (13) and 4 s (16) after the join, the network validated, enrolled on the first try (Device Owner at 20 s / 14 s) |
| Clock 3 years back when the Wi-Fi is joined, **no network time** (automatic time off) | 13, 14 | no download and no Device Owner, even with a plain-HTTP download address. `NetworkMonitor`: `PROBE_HTTPS ... Chain validation failed`, `isPartialConnectivity()=true`; the Wi-Fi is never validated. Android 13 stays at "Getting ready for work setup" (the download job waits for a validated network); Android 14 shows the Wi-Fi picker with "Limited connection". Nothing in the QR or the agent can change this: the agent is not on the phone yet |
| Clock 3 years back, Wi-Fi validated before, HTTP download, HTTPS console (local CA) | 14 | enrolled on the 2nd try, total 147 s: the first try failed with `CertificateNotYetValidException`, the agent set the clock from the plain-HTTP `Date` header (`ClockRepair: clock was wrong; set from the server's Date header: true`) and retried. The console shows the earlier failure as `device.clock` |
| Same, the server's port 80 closed | 14 | enrolled 37 s after Device Owner. Run as: Device Owner with the right clock while the console was unreachable, then the clock put 3 years back and the console brought back. The agent took the time from the fallback source (`connectivitycheck.gstatic.com`, new in this round) and enrolled |

What follows for a deployment: a phone with a factory date enrolls by QR when the enrollment Wi-Fi lets network time
through (NTP, UDP 123 to `time.android.com`) or the phone has a SIM with signal - it then corrects itself within
seconds. Where the network gives no time, Android 10+ cannot download the agent at all; set the date by hand or
enroll by cable. The "download without HTTPS" option of the Enroll page only helps in the narrow case where the
phone does download but its date is still older than the server's certificate. The time in the QR is applied before
the download on Android 7-11 and after it from Android 12 on.

**Second round (2026-11-27 evening, final agent merged with `integration/mobicontrol`, QR-style enrollment, host load
17-24 unless noted).** `--latency 10` = 10 fresh sessions and 10 taps; `--kiosk` = single-app and multi-app kiosk.

| | Android 10 (API 29) | Android 11 (API 30) | Android 15 (API 35) | Android 16 (API 36) |
|---|---|---|---|---|
| Capture | screen capture, consent pressed by the service | same (accessibility screenshots inside a kiosk) | accessibility screenshots | accessibility screenshots |
| `remote-support-e2e.mjs` | 45/48 | 63/64 (with `--kiosk`) | 59/59 (with `--kiosk`) | 46/50 at load 41 |
| `remote-support-console-e2e.mjs` | 26/29 | 30/30 | 30/30 | 29/30 |
| First image, p50 / p90 | 4.2 s / 4.5 s | 1.8 s / 2.8 s | 0.5 s / 1.3 s | 1.2 s / 1.7 s (load 41) |
| Touch to image, p50 / p90 | 250 / 319 ms | 212 / 288 ms | 297 / 608 ms | 874 / 3461 ms (load 41: not valid) |
| Session inside the kiosk (screen, touch, chat + reply, call, still in kiosk) | not run | pass, both kiosks | pass, both kiosks | **not verified**: `kiosk.enter` answered done but the phone was not in lock task; two later runs did not get past enrollment on the loaded machine |

Misses, as they were: Android 10 - the policy's unattended call did not connect, Enter from the console does not send
in a single-line field (Android 10 has no "IME enter" action), "Calidad: Alta" did not enlarge the image (fixed
afterwards, not re-run), the synthetic microphone of Chromium was heard as near-silence once (also once on Android 11;
it passed three times on Android 15). Android 11 - the session notice cannot be hidden while screen capture runs
(Android's rule; the script now skips that check there). Android 16 console - the reply typed over adb did not
arrive (the one typed through the session did).
