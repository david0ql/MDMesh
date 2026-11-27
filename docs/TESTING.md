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

### Results (2026-11-27, dev stack + debug agent of this branch; published agent 0.9.8 where noted)

New agent, QR payload from `web/src/enroll/payload.ts` (tracked download, clock, time zone, locale), from the
provisioning intent to "enrolled with its configuration" in the console:

| Android | Result | How the agent got the token |
|---------|--------|------------------------------|
| 6 (API 23) | not applicable | QR provisioning starts in Android 7 |
| 7.0, 7.1, 8.0, 8.1, 9 (API 24-28) | enrolled, first attempt, 52-56 s after Device Owner | `qr-legacy`: only once the setup wizard finalized |
| 10 (API 29) | enrolled, first attempt | `qr-early`: the copy kept at `GET_PROVISIONING_MODE`, before the wizard finalized |
| 11-17 (API 30-37) | **not obtained** | see below |

Published agent 0.9.8 with the production QR payload of that day (to Device Owner only; it cannot enroll against the
dev stack): Device Owner on Android 7.0, 7.1, 8.0, 8.1, 9, 10 and 11 (the Android 11 run had the agent already
installed by an earlier attempt on the same emulator). Android 12+ not obtained.

"Device Owner but never enrolled" (the wizard is abandoned, or does not finalize): reproduced on Android 7.0 and 9 -
the agent is Device Owner and logs `no enrollment token available` until finalization. The delivery point is the
same code path for every release up to Android 11, and 0.9.8 has no fallback on any of them. With this branch:
Android 10 enrolls without the finalization (`qr-early`); on Android 7.0, left without it, the agent showed its
enrollment screen ("Scan the console's QR" / folder code) and the notification.

Seen on the way: on Android 13 a payload without `PROVISIONING_ALLOW_OFFLINE` stops before the download ("Failed to
perform a platform-requested role holder update ... offline provisioning is false") on an image without Google Play;
with the extra the log says "allowOffline set, provisioning via platform" and the download and install proceed.
On Android 9 the device clock jumped to the payload's `PROVISIONING_LOCAL_TIME` before `DownloadPackageTask`.

Not obtained: Android 11-17 end to end, the clock-set-back runs, the server-outage and token-replay runs. The machine
was running the emulators of several other test suites (load average 40-50): `uiautomator` and `pm` calls timed out,
the dev server answered 502, and on the last Android 13 attempt the emulator's system server died mid-run. The
token-replay and de-dup behaviour is covered at the API by `enroll_api_check.py` (24 checks), the agent's handling of
each callback by unit tests; neither replaces the device run. The Windows enroller was run under PowerShell 7 on
macOS with a simulated adb (`scripts/windows-enroller-sim.sh`), not on Windows PowerShell 5.1.
## Mobile data per app ([DATOS-MOVILES.md](DATOS-MOVILES.md))

`scripts/mobile-data-e2e.py` has two modes. Without a device it plays the phone over the Agent v1 API and checks the
server: the policy's section in the device document (megabytes → bytes), the per-device override and its revision,
the device card's "use against limit", limit events → alerts (not duplicated), the report "Datos móviles: uso vs.
límite" with its CSV/Excel export, and with `--mailpit` the alert e-mail. With `--serial` it drives a real agent on an
emulator, where `adb shell svc wifi disable` leaves the emulated (metered) cellular network: three test apps that make
one HTTP request per launch (`scripts/build-netprobe-testapps.sh`), one blocked, one allowed, one essential.

```bash
scripts/mobile-data-e2e.py --base http://localhost:8080 [--mailpit http://localhost:8025]

scripts/build-netprobe-testapps.sh
scripts/adb-enroll.sh --server http://10.0.2.2:8088 --api-url http://localhost:8088 --debug-build \
    --apk agent-android/app/build/outputs/apk/debug/app-debug.apk --admin-user admin --serial emulator-5554
scripts/mobile-data-e2e.py --base http://localhost:8088 --serial emulator-5554 --device <device number>

DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/mobile-data-console-e2e.mjs --api http://localhost:8088
```

The emulator run covers: nothing restricted → blocklist with the system restriction (`setMeteredDataDisabledPackages`:
the blocked app fails on mobile data and works on Wi-Fi, the allowed app and the agent keep working, Wi-Fi ↔ mobile) →
the same with the agent's local VPN forced (always-on VPN = the agent, no consent dialog, `no_config_vpn`, idle on
Wi-Fi) → allowlist → per-device override (allow / block / unrestricted) → app limit (warning alert, then limit alert
and automatic block; Wi-Fi still works; announced once per period) → device limit (every app but the essential one) →
"ignore limits" on the device → section removed (no admin-restricted uid, no always-on VPN, VPN settings unlocked).
It reads `dumpsys netpolicy`, `dumpsys device_policy` and `dumpsys connectivity` on the way.

Emulator notes: a busy dev stack's Caddy can answer 502 while the agent enrolls and the agent then keeps retrying a
used token; enrolling against Tomcat's own port (`DEV_API_PORT`) avoids it. `dumpsys device_policy` prints the
metered-disabled list only on recent Android versions; `dumpsys netpolicy` ("Admin restricted uids for metered
data") shows it on all of them. Newer images do not always rejoin "AndroidWifi" after `svc wifi enable`; the script
asks for it (`cmd wifi connect-network AndroidWifi open`).

### Results (2026-11-27, own stack + debug agent built from this branch)

Machine under heavy load (load average 45+, a dozen stacks and five emulators), so the waits are long but nothing was
timed. Emulators: fresh AVDs with the `google_apis` images, agent enrolled over adb as Device Owner.

| What | Result |
|------|--------|
| Server, simulated device, with `--mailpit` | 55/55 (one e-mail with both alerts, linked to the device) |
| Console in headless Chromium | 37/37 |
| Emulator API 30 (Android 11) | 94/95 — the one miss was a check of this script (it looked for the metered list in `dumpsys device_policy`, which Android 11 does not print); fixed |
| Emulator API 35 (Android 15) | 93/95 — both misses were checks of this script, not of the product: it read a stale state while the phone switched from the VPN to the system restriction, and it looked for `no_config_vpn` in `dumpsys device_policy`, where Android 15 keeps an empty entry after the restriction is lifted (`dumpsys user` showed it gone); both fixed |

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
