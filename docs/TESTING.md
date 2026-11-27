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
