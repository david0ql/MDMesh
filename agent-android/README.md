# MDM Agent (Android)

A from-scratch, self-hosted **custom DPC** (Device Owner) Android MDM agent.
Modern Kotlin: coroutines/Flow, Hilt, Room, DataStore, WorkManager,
kotlinx.serialization + Retrofit.

- `applicationId` / base namespace: `com.mdmesh.agent`
- `minSdk 23` (Android 6), `targetSdk 35`, `compileSdk 35`
- Identity is a **server-issued device id** (DataStore). The agent never uses
  IMEI/IMSI/serial as identity (restricted post-Android 10).

> Building needs JDK 17 and the Android SDK: set `ANDROID_HOME`, or `sdk.dir` in
> `local.properties` (gitignored). Gradle itself comes from the committed wrapper.

## Module map

| Module | Type | Responsibility |
|--------|------|----------------|
| `:proto` | Kotlin/JVM lib | `@Serializable` wire contract mirroring `../proto/` (`CapabilityMatrix`, `CommandEnvelope`, `CommandResult`, `ProtocolJson`). No Android deps. |
| `:policy` | android-lib | **Capability-abstraction layer.** `DeviceControl`, `PolicyStrategy`, SDK-gated `WifiPolicy` (modern/legacy strategies + factory), `CapabilityRegistry`. All `DevicePolicyManager` calls stay behind interfaces. |
| `:core` | android-lib | Sync/check-in: Retrofit `MdmApi`, `CapabilityCollector`, `CommandDispatcher` (+ handlers), `DeviceIdStore` (DataStore), `CheckInCoordinator`/`CheckInWorker`. Base URL via `BuildConfig`. |
| `:kiosk` | android-lib | COSU skeleton: `KioskController` (+ stub), `CrashLoopGuard`. |
| `:remote` | android-lib | The original in-agent remote-control skeleton. **Not a dependency of the app any more** (see below). |
| `:oem` | android-lib | `OemAdapter` + `GenericOemAdapter` (no-op) + `KnoxAdapter` (PARKED, no Knox dep). |
| `:app` | android-app | Hilt `Application`, `AdminReceiver`, provisioning activities, `MainActivity` launcher/home stub, `CheckInService`, manifest with the minimal permission set. |

### The capability-abstraction intent

Android is a permanent version treadmill — every OS release adds, removes, or
restricts a policy API. Rather than scatter `Build.VERSION.SDK_INT` branches through
feature code, each policy area defines an interface (`WifiPolicy`) with multiple
`PolicyStrategy` implementations; a factory picks the supported one **once**. The
`CapabilityRegistry` reports only the keys that have a working strategy, and that
set becomes the `capabilities` advertised in the `CapabilityMatrix`. The server is
contractually forbidden from sending a command whose `requiresCapability` isn't
advertised, so a 3-year-old agent on Android 9 and a fresh agent on Android 16 talk
to the same server without special-casing. Unknown command types degrade to
`status=unsupported` instead of crashing.

### Permission minimization (Play Protect)

- Base `:app` manifest has **no** `READ_SMS` and **no** `QUERY_ALL_PACKAGES`;
  package visibility is scoped via `<queries>`.
- **No accessibility declaration at all.** Remote view/control runs through droidVNC-NG, a separate
  open-source app the agent deploys and drives (`app/.../remote/DroidVncController.kt`, commands
  `remote.vnc.start` / `remote.vnc.stop`, [ADR 0010](../docs/adr/0010-remote-control-droidvnc.md)); its input
  service belongs to that app, not to the agent.
- No phone-state / device-identifier permissions — identity is server-issued.

## Build

```bash
./gradlew assembleDebug                                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew detekt assembleDebug lintDebug testDebugUnitTest   # what CI runs (T0)
```

> The Gradle wrapper is committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
> and `.properties`, pinned to Gradle 8.10.2), so no system Gradle is needed: the first `./gradlew`
> run downloads that version. Upgrade it with `./gradlew wrapper --gradle-version <version>` and
> commit all four files.

Release signing reads from env vars (`MDM_RELEASE_STORE_FILE`,
`MDM_RELEASE_STORE_PASSWORD`, `MDM_RELEASE_KEY_ALIAS`, `MDM_RELEASE_KEY_PASSWORD`);
see the `// TODO(keystore custody)` note in `app/build.gradle.kts`. The DO binding
is tied to the signing certificate — re-signing a deployed DPC with a different key
forces a factory reset of every enrolled device, so guard the release key carefully.

## ADB Device-Owner dev enrollment loop

Device Owner can only be set on a device/emulator with **no accounts** (fresh or
factory-reset). Then:

```bash
# 1. Install the agent.
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Bind it as Device Owner (note the .debug applicationIdSuffix on debug builds).
adb shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver
#   release build would be:
#   adb shell dpm set-device-owner com.mdmesh.agent/com.mdmesh.agent.admin.AdminReceiver

# 3. Confirm.
adb shell dumpsys device_policy | grep -i "Device Owner"
```

Then give it the server and an enroll token (what the QR bundle does in production), or run
`scripts/adb-enroll.sh`, which does all three steps:

```bash
adb shell am broadcast -a com.mdmesh.agent.ADB_PROVISION \
  -n com.mdmesh.agent.debug/com.mdmesh.agent.provisioning.AdbProvisionReceiver \
  --es server_url http://10.0.2.2:8088 --es enroll_token <token>
```

Debug builds may use plain HTTP to `10.0.2.2` / `localhost` (the emulator's host and `adb reverse`), so an
emulator reaches the loopback dev stack directly; release builds need HTTPS.

If `set-device-owner` fails with "Not allowed to set the device owner because
there are already some accounts" — remove all accounts, or factory reset.

### Getting OFF Device Owner (dev cycle)

A Device Owner cannot simply be uninstalled. To clear it:

```bash
# Clears the DO binding (works on debug/userdebug builds).
adb shell dpm remove-active-admin com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver

# If that is blocked, factory reset:
adb shell am broadcast -a android.intent.action.MASTER_CLEAR   # or wipe via Settings / recovery
```

On an emulator, the fastest reset is **Wipe Data** (cold boot) from the AVD
manager, then re-run the enrollment loop.

## Real provisioning (production)

Production enrollment is via QR / NFC / zero-touch using the
`GET_PROVISIONING_MODE` + `ADMIN_POLICY_COMPLIANCE` activities (already wired). The
ADB loop above is for the dev inner loop only.
