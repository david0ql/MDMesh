# ADR 0010 — Remote view/control through droidVNC-NG and a Mode-II repeater

**Status:** Accepted (2026-09-28)

## Context

Remote support (see the device's screen, tap and type on it) is the one MobiControl feature MDMesh lacked.
The `:remote` module held only a skeleton for an in-agent implementation (MediaProjection capture plus an
AccessibilityService for input). Building that meant a capture encoder, a transport and our own relay, and it
would put an accessibility declaration into the agent APK, which ADR 0005 keeps out because it trips Play
Protect. Field devices sit behind carrier NAT, so the device has to dial out.

## Decision

Use existing open-source components instead of writing a remote-control stack:

- **Device:** [droidVNC-NG](https://github.com/bk138/droidVNC-NG) (GPL-2.0), a VNC server app for Android 7+,
  installed next to the agent. The agent (Device Owner) configures it through managed app restrictions
  (per-device access key, never listening, no start-on-boot), keeps it from being force-stopped, keeps its input
  service enabled, and drives it through its documented Intent interface (`DroidVncController`).
- **Commands:** `remote.vnc.start { sessionId, password?, viewOnly?, host?, port? }` and `remote.vnc.stop`,
  gated on `remote.view` / `remote.control`. The agent advertises `capabilities.remoteControl` per check-in:
  `view` when droidVNC-NG is installed, `control` when its input service is on (or the agent may turn it on).
  `remote.vnc.start` stops any running server, starts a fresh one with the session password, waits for
  droidVNC-NG to confirm, then dials the repeater and waits for that answer; `done` means connected.
- **Server:** an UltraVNC-style **Mode-II repeater** ([uvncrepeater-ac](https://github.com/tenchman/uvncrepeater-ac),
  GPL-2.0, `docker/vnc-repeater.Dockerfile`) on `REPEATER_PORT` (5500), and **websockify** serving the unmodified
  official **noVNC** viewer (MPL-2.0, pinned by sha256). Caddy exposes them at `/remote/vnc/` only to signed-in
  console sessions (`forward_auth` to `/rest/private/users/current`). Both are the opt-in compose profile `remote`.
- **Session:** the operator mints a one-time **numeric** id (the repeater pairs only positive decimal ids) and an
  8-character VNC password (`scripts/remote-session.sh`), then opens
  `/remote/vnc/vnc.html?path=remote/vnc/websockify&repeaterID=<id>&password=<pw>&autoconnect=true`.
- **Unattended grants** a Device Owner cannot give itself are made once at USB enrollment
  (`scripts/adb-enroll.sh --remote --vnc-apk …`): the PROJECT_MEDIA app-op for droidVNC-NG (no capture-consent
  dialog) and its accessibility input service, plus WRITE_SECURE_SETTINGS for the agent.

## Consequences

- (+) Works behind NAT and on mobile data with one outbound TCP port; no TURN, no custom relay code.
- (+) The agent APK carries no accessibility declaration at all (`:remote` is no longer a dependency).
- (+) The server never decodes frames; access to a session needs a console session, the id and the password.
- (−) Android 7+ only (droidVNC-NG's minimum). Android 6 devices get no remote view.
- (−) VNC traffic between device and repeater is not encrypted (only the password exchange is). Put the repeater
  port behind a VPN, or accept it for view/support of dedicated devices.
- (−) A second app to deploy and keep updated (`scripts/fetch-droidvnc.sh` pins the release by sha256).
- (−) A locked phone on battery (adaptive power mode) only checks in every few minutes; for instant sessions keep
  support devices in `alwaysOn` power mode or wake them. A secure lock screen is shown as is; the viewer can
  unlock only a swipe/no-PIN keyguard.
- (−) The console has no in-page "Remote" button yet: sessions start from `scripts/remote-session.sh` and open the
  viewer link. A console button only needs to queue `remote.vnc.start` and open that link.
