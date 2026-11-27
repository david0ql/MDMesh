# ADR 0012 — Remote support inside the agent: screen, touch, voice, chat and files over one session channel

**Status:** Accepted (2026-11-27). Extends ADR 0010 (droidVNC-NG stays as the fallback engine) and revises ADR 0005
(the agent now declares one accessibility service).

## Context

The customer's criterion MDM-08 is "see and control the phone's screen in real time from the console for technical
support". The droidVNC-NG path of ADR 0010 was rated "in development", with the note "remote support only, no call
from the console". Three gaps were behind that rating:

1. **Unattended only after a USB enrollment.** The two grants droidVNC-NG needs (the screen-capture app-op and its
   accessibility service) can only be given over adb. On a QR / zero-touch enrollment somebody had to tap on the
   phone before a session; in production `remote.inputSetup` took a median of 154 s and `remote.vnc.start` a p90 of
   134 s, most of it a person finding a switch.
2. **A second APK.** droidVNC-NG has to be installed, updated, kept alive by makers' battery managers (HyperOS killed
   it: "Not allowed to start service") and allowed in kiosks.
3. **No voice, chat or files.** A technician could look and tap, but not talk to the person holding the phone.

### What a Device Owner can and cannot authorise by itself

Checked on emulators (API 28, 30, 35, 36) with the probes the agent runs at start (`SupportEngine.probeDeviceOwnerRoutes`;
the outcome travels in the session's `info.probe` and is printed by the end-to-end test):

| Route | Result |
|-------|--------|
| `DevicePolicyManager.setPermissionGrantState(WRITE_SECURE_SETTINGS)` | Refused on every release: it only takes runtime permissions, and this one is `signature\|privileged\|development`. |
| `DevicePolicyManager.setSecureSetting(ENABLED_ACCESSIBILITY_SERVICES)` | `SecurityException` on every release: a Device Owner may write two unrelated secure settings only. |
| `setPermissionGrantState` for screen capture | Does not exist: MediaProjection is a consent, not a permission. The `PROJECT_MEDIA` app-op needs the shell. |
| `setPermissionGrantState(RECORD_AUDIO)` for the agent itself | **Works** on a fully managed device: the microphone for voice calls needs nobody. |
| Starting a microphone foreground service and activities from the background | **Allowed** for a Device Owner (exempt from the while-in-use and background-start rules). |
| `pm grant … WRITE_SECURE_SETTINGS` over adb | Works; with it the agent switches its own accessibility service on. `adb install -g` already grants it. |

So Android leaves exactly one thing a QR-enrolled Device Owner cannot do alone: switch an accessibility service on.
That switch is persistent (it survives reboots and updates of the agent), unlike the screen-capture consent, which
Android 10+ asks again for every session.

## Decision

**The agent captures the screen, injects input, carries the voice call, the chat and the files itself, over one
WebSocket per session that the server relays to the console.** droidVNC-NG remains, selected by policy or used
automatically where the agent's engine is not authorised.

### Screen and control: one accessibility service in the agent

`SupportAccessibilityService` (declared in the agent, listed in Accessibility as "Soporte remoto DallyControl"):

- **Android 11+ (API 30+):** `takeScreenshot()`, no dialog at all, also in a kiosk and on the lock screen.
- **Android 7–10 (API 24–29):** Android's screen capture (MediaProjection); the service itself presses "Start now"
  (and ticks "Don't show again" where the dialog has it), so these releases need nobody either. Not available in a
  kiosk on these releases (Android does not let the System UI consent show in lock task): droidVNC-NG has the same
  limit there.
- Touch through `dispatchGesture` (taps, long presses, swipes), navigation through global actions (Back, Home,
  Recents, notifications, lock, power menu), typing through `ACTION_SET_TEXT` on the focused field.
- It listens only to System UI window changes (for that consent) and receives no other app's events.

It is switched on **once**:

- **USB enrollment** (`adb-enroll.sh`, the Windows enroller): the agent holds WRITE_SECURE_SETTINGS and switches the
  service on by itself at the first session. Zero touches.
- **QR / zero-touch enrollment:** one guided screen (`RemoteSetupActivity`) opens right after enrollment: "Abrir
  ajustes" → the service → the switch → Permitir. It notices the switch by itself and closes. The console shows
  **Falta autorización** until then and **Remoto listo** after, and can bring the screen up again
  ("Pedir autorización en el teléfono", the existing `remote.inputSetup` command). A phone already in the field that
  receives this agent as an update is not interrupted: only the console's button opens the screen there.

### Transport: the session channel, not WebRTC

- Device: `wss://<server>/agent/ws/{deviceId}/support/{sessionKey}`, bearer = the device secret (same check as the
  wake channel). Console: `wss://<server>/agent/ws/support/console/{sessionKey}`, first message = the one-time token
  only the operator who started the session received. `SupportHub` pairs the two and relays; it never decodes frames
  or audio. Both ride the server's HTTPS origin: same TLS, same port 443, through Caddy and the Cloudflare tunnel
  with no new route, behind any carrier NAT. **No STUN/TURN server is needed or deployed.**
- Text messages are small JSON (`{"t": "tap", …}`), binary messages carry a kind byte: screen frame (JPEG), audio
  (G.711 µ-law, 16 kHz mono, 20 ms frames, 128 kbit/s), file chunk. Frames are acknowledged one at a time, so the
  rate adapts to the link and a still screen sends nothing.
- **Start goes over the live channel:** the wake names the session (`{"wake":"support","sessionId":…}`) and a
  connected agent dials in at once, with no check-in round trip. A `remote.session.start` command is queued too for
  a phone whose socket was down. Agents older than this ADR ignore the unknown wake kind (they just check in) and
  never receive the command (it is gated on `device.supportSession`).

**Why not WebRTC in the agent.** `libwebrtc` adds 6–9 MB per ABI to an APK that is 1.7 MB today (it is downloaded by
every phone at enrollment, over mobile data, and re-downloaded at every update), needs a TURN relay deployed and
operated for phones behind symmetric carrier NAT (most of the fleet), and a second port/protocol through customers'
firewalls. For one talker each way, µ-law over the existing TLS socket costs 128 kbit/s and a few hundred lines; the
platform's communication audio path (`VOICE_COMMUNICATION` source, `MODE_IN_COMMUNICATION`, AcousticEchoCanceler,
NoiseSuppressor, AutomaticGainControl) and the browser's `getUserMedia` constraints do the echo cancelling. The
trade-off: TCP head-of-line blocking on a bad link shows as a short audio gap instead of packet-loss concealment,
and 128 kbit/s is more than Opus would need. MediaCodec has no Opus encoder before Android 10 and browsers no
portable raw-Opus API, so Opus would have meant a native library on one side or the other.

### Voice, chat, files, clipboard, log

- **Voice:** the console's microphone ↔ the phone's microphone and loudspeaker. The phone shows a call screen
  (Aceptar / Rechazar while it rings; Silenciar / Colgar during the call), an ongoing notification with Colgar, and
  the policy decides whether the person must accept (`callAccept: ask`, default) or the call is answered unattended
  (`auto`). The microphone permission is granted by the Device Owner.
- **Chat:** the operator's messages open the support screen on the phone (also in kiosk and over the lock screen)
  and raise a notification; the person replies from there.
- **Notice:** "Soporte está viendo tu pantalla" as a pill above every app plus an ongoing notification, on by
  default (`notice: false` turns it off).
- **Files:** console → phone lands in Descargas/Soporte (MediaStore, no storage permission); phone → console browses
  the shared storage (everything with "all files access", otherwise the agent's folder and the phone's media).
- **Clipboard:** text to the phone always; from the phone only the focused field's text when Android (10+) does not
  let a background app read the clipboard.
- **Log:** every session is a row of `support_session` (who, device, start, joined, end, why, counters) with its
  events (`support_session_event`: chat lines, files, calls, lock/restart); the console lists them per device and
  for the fleet (CSV), scoped to a folder administrator's own devices.

### Flag and fallback

`remoteSupport.engine` in the policy: `auto` (default: the agent's engine when authorised, droidVNC-NG otherwise),
`builtin`, `droidvnc` (the agent's accessibility service is disabled as a component and disappears from Settings).
With droidVNC-NG as the screen, the session channel still carries voice, chat and files next to the VNC viewer.

## Consequences

- (+) QR / zero-touch phones: one tap during enrollment, then unattended sessions on Android 7–16. USB-enrolled
  phones: zero taps.
- (+) Nothing else to install, update or keep alive; the session lives in the agent's own foreground process, which
  makers' battery managers already leave alone (it is the Device Owner).
- (+) Voice, chat, files, clipboard, recording and a session log: what evaluators compare with Scalefusion's Remote
  Cast.
- (+) No new port, relay or certificate; old agents keep working through ADR 0010.
- (−) **ADR 0005 is relaxed:** the agent APK now declares an accessibility service, RECORD_AUDIO and two more
  foreground-service types. Play Protect scores those. The enrollment paths in use (adb, and QR on fleets where the
  DPC is allow-listed) are not affected by a warning at install, but this must be re-checked on every release of
  Play Protect; `engine: droidvnc` removes the accessibility surface at runtime (not from the manifest).
- (−) Accessibility screenshots are limited by Android to about 3 per second; enough to guide a person and operate
  the phone, not to watch video. (droidVNC-NG's accessibility capture has the same limit.)
- (−) Windows that set FLAG_SECURE (banking apps, password fields of some keyboards) are black in the image.
- (−) A secure lock screen is shown as is: the operator can type the PIN only if they know it.
- (−) Both sockets of a session must end on the same server process (in-memory relay, like the wake hub).
- (−) The relay sees the session in clear inside the server (TLS ends at the edge), as with the VNC repeater.
