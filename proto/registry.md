# Capability & command registries (v1)

Open string registries. Adding a row is an additive (MINOR) change. Keep this file in sync
with the agent's capability advertisement and the server's command catalog.

## Capability keys

### policy
| key | meaning | min SDK | notes |
|-----|---------|---------|-------|
| `wifi` | toggle/lock Wi-Fi | 24 | |
| `bluetooth` | toggle/lock Bluetooth | 24 | BLUETOOTH_SCAN auto-grant fails if target app targetSdk<=30 |
| `gps` | location toggle | 24 | |
| `mobileData` | mobile data toggle | 24 | |
| `usbStorage` | block USB mass storage | 24 | |
| `camera` | disable camera | 24 | |
| `screenshots` | disable screenshots | 24 | |
| `kioskLockTask` | COSU lock-task kiosk | 24 | setLockTaskPackages/Features |
| `passwordComplexity` | password policy | 31 | setRequiredPasswordComplexity (setPasswordQuality deprecated @26) |
| `systemUpdatePolicy` | OS update windows | 24 | |
| `factoryResetProtection` | FRP policy | 30 | setFactoryResetProtectionPolicy |

### appManagement
| key | meaning | notes |
|-----|---------|-------|
| `silentInstall` | PackageInstaller silent install | Device Owner only |
| `silentUninstall` | silent uninstall | Device Owner only |
| `splitApk` | split/.xapk install | |
| `fdroidCatalog` | can pull from an F-Droid repo | for the APK browser/catalog feature |

### remoteControl
Advertised as an object (`tier`, `screenCapture`, `inputInjection`, `transport`). See README. The agent reports
`transport: ["vnc-repeater", "vnc-repeater-wss"]`, `tier: view` when droidVNC-NG is installed and `control` when its
input service is enabled (ADR 0010). `vnc-repeater-wss` means the agent can tunnel the session over the server's HTTPS
origin (encrypted); the server then sends `transport: "wss"` in `remote.vnc.start`.

`support` (optional object, ADR 0012; absent on agents that predate it): the agent's own support-session channel.
`session` (it speaks the channel: chat, voice, files), `screen` (`ready` | `needsAuthorization` | `unsupported` |
`disabled`), `control`, `voice` (`ready` | `needsPermission`), `capture` (`a11y` | `projection` | `none`),
`selfEnable` (it can switch its screen service on by itself). With `screen: ready` the agent also reports
`tier: control` and the transport `support-ws`. The device key `supportSession` gates `remote.session.start`.

### oem
`vendor`, `knox` (parked tier).

### device
| key | meaning | notes |
|-----|---------|-------|
| `configApply` | accepts config.apply | |

## Command types

| type | requiresCapability | payload (sketch) |
|------|--------------------|------------------|
| `config.sync` | — | none (triggers a full reconcile) |
| `config.apply` | `device.configApply` | desired-state document, see `payloads/config-apply.schema.json`; result detail is `payloads/config-apply-result.schema.json` |
| `policy.apply` | the specific policy key | `{ policy: "wifi", value: false }` |
| `app.install` | `silentInstall` | `{ url, packageName, versionCode, sha256, runAfterInstall }` |
| `app.uninstall` | `silentUninstall` | `{ packageName }` |
| `app.launch` | — | `{ packageName, activity? }` |
| `remote.vnc.start` | `remote.view` / `remote.control` | `{ sessionId: "<8-18 digits>", password?, viewOnly?, transport?: "wss", host?, port? }` — droidVNC-NG dials the Mode-II repeater (ADR 0010); with `transport: "wss"` through the agent's encrypted tunnel to `/remote/device/`, otherwise directly to `host:port`. Minted by `POST /rest/private/agent/v1/devices/{id}/remote/start` |
| `remote.vnc.stop` | — | none |
| `remote.startSession` | `remoteControl.tier>=view` | reserved (the unbuilt in-agent design; superseded by `remote.vnc.*`) |
| `remote.stopSession` | — | reserved |
| `remote.session.start` | `device.supportSession` | `{ sessionId: "<24 hex>" }` — join the support session the console opened (ADR 0012): the agent dials `wss://<server>/agent/ws/{deviceId}/support/{sessionId}` with its secret. A connected agent has usually joined already from the wake `{"wake":"support","sessionId":…}`; a session that is over by then answers `done` |
| `remote.session.stop` | — | none — leave the support session |
| `remote.inputSetup` | — | none — remote support needs its one-time authorisation: the agent switches its screen service on when it may, otherwise opens the guided screen on the phone (droidVNC-NG's accessibility settings when the policy keeps the agent's engine off) |
| `device.reboot` | — | none (DO) |
| `device.lock` | — | none |
| `device.dataSharing` | `device.dataSharing` | `{ enabled: true \| false }` — per-device "share data" (tethering) override: `true` lifts `DISALLOW_CONFIG_TETHERING`, `false` blocks it; idempotent, wins over the policy |

Per-type payload JSON Schemas go in `proto/payloads/` as the registry grows.

## Hardware and ports rules (`config.apply` → `hardware.rules`)

Value `allow` | `block`; a missing key is not managed. The agent reports each one in the result as `hardware.<key>`
(`applied`, `unsupported: sdk-<N>` below the listed Android, `unsupported: device`, `failed: <why>`).

| key | Android mechanism | since API |
|---|---|---|
| `camera` | `DevicePolicyManager.setCameraDisabled` (also sent as `policies.camera` for agents before this section) | 21 |
| `usbDebug` | `DISALLOW_DEBUGGING_FEATURES` (ADB over USB and Wi-Fi, developer options) | 21 |
| `usbData` | `setUsbDataSignalingEnabled(false)` when `canUsbDataSignalingBeDisabled()`; charging keeps working | 31 |
| `physicalMedia` | `DISALLOW_MOUNT_PHYSICAL_MEDIA` | 21 |
| `bluetoothSharing` | `DISALLOW_BLUETOOTH_SHARING` | 26 |
| `bluetoothConfig` | `DISALLOW_CONFIG_BLUETOOTH` | 21 |
| `nfc` | `DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO` (the API 35 emulator image refuses it → `unsupported: platform`; works on API 36) | 35 |
| `nfcBeam` | `DISALLOW_OUTGOING_BEAM` | 22 |
| `microphone` | `DISALLOW_UNMUTE_MICROPHONE` | 21 |
| `airplaneMode` | `DISALLOW_AIRPLANE_MODE` | 28 |
| `wifiDirect` | `DISALLOW_WIFI_DIRECT` | 33 |
| `printing` | `DISALLOW_PRINTING` | 28 |
| `networkReset` | `DISALLOW_NETWORK_RESET` | 23 |
| `dateTime` | `DISALLOW_CONFIG_DATE_TIME` (API 28+); before: `setAutoTimeRequired` | 21 |
| `unknownSources` | `DISALLOW_INSTALL_UNKNOWN_SOURCES` (+ `_GLOBALLY` from API 29) | 21 |
| `locationConfig` | `DISALLOW_CONFIG_LOCATION` | 28 |
| `locationSharing` | `DISALLOW_SHARE_LOCATION` | 21 |
| `contentCapture` | `DISALLOW_CONTENT_CAPTURE` + `DISALLOW_CONTENT_SUGGESTIONS` | 29 |
| `assistContent` | `DISALLOW_ASSIST_CONTENT` | 35 |

Older fields that complete the set: `policies.screenshots` (`setScreenCaptureDisabled`), `policies.usbStorage`
(`DISALLOW_USB_FILE_TRANSFER` + `DISALLOW_MOUNT_PHYSICAL_MEDIA`), `policies.bluetooth` (`DISALLOW_BLUETOOTH`, API 26) and
`device.tethering` (`DISALLOW_CONFIG_TETHERING`: Wi-Fi hotspot, USB and Bluetooth tethering).

`hardware.restrictions` carries extra `UserManager.DISALLOW_*` values (reported as `restrictions.<no_xxx>`). Server and
agent accept only the list in `HardwarePolicy.java` / `HardwareCatalog.kt`.
