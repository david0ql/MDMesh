# ADR 0010 — Remote view/control through droidVNC-NG and a Mode-II repeater

**Status:** Accepted (2026-09-28)

## Context

Remote support (see the device's screen, tap and type on it) is the one MobiControl feature DallyControl lacked.
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
- **Commands:** `remote.vnc.start { sessionId, password?, viewOnly?, transport?, host?, port? }` and `remote.vnc.stop`,
  gated on `remote.view` / `remote.control`. The agent advertises `capabilities.remoteControl` per check-in:
  `view` when droidVNC-NG is installed, `control` when its input service is on (or the agent may turn it on).
  `remote.vnc.start` stops any running server, starts a fresh one with the session password, waits for
  droidVNC-NG to confirm, then dials the repeater and waits for that answer; `done` means connected.
- **Server:** an UltraVNC-style **Mode-II repeater** ([uvncrepeater-ac](https://github.com/tenchman/uvncrepeater-ac),
  GPL-2.0, `docker/vnc-repeater.Dockerfile`) on `REPEATER_PORT` (5500), and **websockify** serving the unmodified
  official **noVNC** viewer (MPL-2.0, pinned by sha256). Caddy exposes them at `/remote/vnc/` only to signed-in
  console sessions (`forward_auth` to `/rest/private/users/current`). Both are the opt-in compose profile `remote`.
- **Encrypted transport (2026-09-28):** droidVNC-NG can only dial plain TCP, so the agent (`RepeaterTunnel`) gives
  it a loopback port and carries each of its connections inside a WebSocket to `<server>/remote/device/websockify`,
  i.e. over the server's own HTTPS origin and TLS, the same trust as the API. Caddy lets that WebSocket through
  (`forward_auth` to `GET /rest/public/agent/v1/remote/tunnel`) only with the device's secret and a
  `remote.vnc.start` queued for it in the last 15 minutes; `websockify-device` bridges it to the repeater's device
  port. The agent advertises the transport `vnc-repeater-wss`; the server then sends `transport: "wss"`. The
  repeater's plain port is published on loopback only; agents without the tunnel still dial it directly
  (unencrypted) when an operator publishes it for them.
- **Session:** the console's **Remote** tab (or `scripts/remote-session.sh`) calls
  `POST /rest/private/agent/v1/devices/{id}/remote/start { viewOnly }`; the server mints a one-time **numeric** id
  (the repeater pairs only positive decimal ids) and an 8-character VNC password, picks the transport from the
  device's capabilities and queues the command. The console waits for the device's `done`, then shows the viewer
  inline (`/remote/vnc/vnc.html#path=remote/vnc/websockify&repeaterID=<id>&password=<pw>&autoconnect=true`; the
  parameters ride in the URL fragment so the password never reaches access logs), with open-in-new-tab,
  full-screen and end-session controls, and in control sessions a Nexus-style soft-key bar (Back, Home, Recents,
  plus Power, Volume and Rotate) that sends droidVNC-NG's action chords through the viewer's connection. The
  agent pins those chords in droidVNC-NG's managed restrictions so the bar keeps working. `GET .../devices/{id}/remote` reports tier, transports, encryption and
  power mode for the tab.
- **Unattended grants** a Device Owner cannot give itself are made once at USB enrollment
  (`scripts/adb-enroll.sh --remote --vnc-apk …`): the PROJECT_MEDIA app-op for droidVNC-NG (no capture-consent
  dialog) and its accessibility input service, plus WRITE_SECURE_SETTINGS for the agent.

## Consequences

- (+) Works behind NAT and on mobile data with one outbound TCP port; no TURN, no custom relay code.
- (+) The agent APK carries no accessibility declaration at all (`:remote` is no longer a dependency).
- (+) The server never decodes frames; access to a session needs a console session, the id and the password.
- (−) Android 7+ only (droidVNC-NG's minimum). Android 6 devices get no remote view.
- (+) The whole session is encrypted between phone and server (TLS of the server's origin) and needs no extra
  public port. Between the server's reverse proxy and the repeater, inside the Docker network, it is plain.
- (−) The tunnel's loopback port is open to other apps on the phone for the moment between the agent opening it
  and droidVNC-NG connecting; an app racing for it would still need the VNC password to be useful to a viewer.
- (−) Agents older than the tunnel dial the repeater port in clear (only the password exchange is protected); the
  console marks such devices "Not encrypted". Publish the port for them only behind a VPN.
- (−) A second app to deploy and keep updated (`scripts/fetch-droidvnc.sh` pins the release by sha256).
- (−) A locked phone on battery (adaptive power mode) only checks in every few minutes; for instant sessions keep
  support devices in `alwaysOn` power mode. The Remote tab warns about battery-saver devices and offers
  **Set Always-on**; the device list sets it in bulk. A secure lock screen is shown as is; the viewer can unlock only
  a swipe/no-PIN keyguard.
