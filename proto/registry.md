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
`transport: ["vnc-repeater"]`, `tier: view` when droidVNC-NG is installed and `control` when its input service is
enabled (ADR 0010).

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
| `remote.vnc.start` | `remote.view` / `remote.control` | `{ sessionId: "<8-18 digits>", password?, viewOnly?, host?, port? }` — droidVNC-NG dials the Mode-II repeater (ADR 0010) |
| `remote.vnc.stop` | — | none |
| `remote.startSession` | `remoteControl.tier>=view` | reserved (the unbuilt in-agent design; superseded by `remote.vnc.*`) |
| `remote.stopSession` | — | reserved |
| `device.reboot` | — | none (DO) |
| `device.lock` | — | none |

Per-type payload JSON Schemas go in `proto/payloads/` as the registry grows.
