# Fleet organisation and policies

How DallyControl covers the way an operations team runs a fleet in MobiControl: a folder tree, enrollment rules per
folder, app and browser policies, location every few minutes, SIM alerts and device scripts. Everything here is open
source and needs no Google-managed service.

## Folders (groups) and inheritance

Groups nest: **Colombia → Preventa → Agencia Norte → Samsung A15**. A folder may carry a configuration; one without
runs its **nearest ancestor's**, else the global default. A device's own (pinned) configuration wins over all of it.

- Groups page: `+ Sub-folder`, `Edit` (rename or move under another folder — never under itself or one of its own
  sub-folders), `Delete` (its sub-folders move up one level; its devices keep enrolled, without a group).
- A folder's configuration change reaches the devices of the whole branch; *Run action* on a folder reaches its
  sub-folders' devices too; the Devices filter for a folder includes its sub-folders.
- Sibling names are unique; the same name may repeat under different parents (Agencia Norte in Colombia and Ecuador).

Server: `groups.parentId`, `dallycontrol_group_configuration(groupId)` (walks up to 32 levels),
`/rest/private/fleet/v1/groups` (`parentId` in the body).

## Enrollment codes per folder (enrollment rules)

**Enroll → Folder codes**: a reusable code such as `UMW7-E4S7` that sends every phone enrolled with it to that folder.
It works for any number of phones until revoked (optionally until a date) and counts its uses. Use it for teams that
enroll their own phones (e.g. Ecuador, provisioning partners):

- **typed on the phone**: the DallyControl agent screen shows *Enrollment code* (and the server) while the phone is
  Device Owner but not enrolled — case, dashes and spaces do not matter;
- **as a QR**: the code's QR provisions a factory-reset phone straight into the folder (print it).

Codes use 31 unambiguous characters (no 0/O, 1/I/L). Single-use tokens (Enroll → Scan QR / Token) still exist.
API: `POST/GET /rest/private/agent/v1/codes`, `DELETE /rest/private/agent/v1/codes/{id}`.

## Configuration policies (Configurations → *Device functions, browser, apps and location*)

Stored as JSON in `configurations.dcPolicy`, validated by the server and delivered in `config.apply`.

| Policy | What the phone does |
|---|---|
| **Kiosk functions** — Phone, Contacts, Messages, Browser, Camera, Maps | Each phone resolves the function to *its own* app (default dialer + in-call screen, default SMS app, …), so one configuration fits every brand and no folder per phone model is needed for it. The kiosk becomes a home screen with the kiosk apps and these functions. With Phone allowed, an incoming call is brought to the front inside the kiosk (lock task hides notifications, which would otherwise make it invisible). |
| **Browser (Chrome)** — *only these sites* / *any site except these* | Chrome's managed configuration (`URLAllowlist` / `URLBlocklist`, Chrome's URL-filter format: `amovil.com.co`, `*.gov.co`, `https://example.com/path`). Chrome shows its "blocked by your administrator" page. Other browsers are not covered: block them with the app policy (or allow only Browser in kiosk). |
| **App policy** — *only allowed apps* | Apps the user installs outside the list (configuration apps + *also allowed* packages + allowed functions) are **suspended** within seconds: installed but greyed out, cannot be opened, reported as `appBlocked`; allowing one later lifts it. *Hide the Play Store* hides the store app. |
| **Location every N minutes** | A fresh GPS fix every N minutes, kept on the phone while offline and uploaded with its original times when it reconnects (Fleet map / device Location tab). Use Always-on for phones that must report on time. |

**App policy limit.** Filtering what the Play Store *lists* (showing only allowed apps) needs Google's managed Play
(Android Enterprise enrollment), which DallyControl does not use. The open equivalent: the store stays (or is hidden)
and anything installed outside the list is paused at once.

Register apps that the phones already have (WhatsApp from the Play Store, Chrome…) in **Apps → On the phone**: no APK,
nothing is installed; they become pickable in configurations.

## SIM and phone number

The device page shows the SIM (carrier, or *No SIM*) and the phone number when the carrier stores it on the SIM. The
timeline records **SIM removed / inserted / changed** (old → new carrier and number) and the device page shows a
*No SIM card* alert. A modem restart is not reported as a removal.

## Actions and scripts

Device page → Control: *Open app* (brings an installed app to the front; in kiosk only apps the kiosk allows),
*Ring device*, *Reset passcode* (sets or clears the lock-screen PIN), lock, reboot, messages… and a **Script** box, one
action per line, run in order:

```
open co.amovil.preventa
ring 20
message Por favor comunícate con soporte
lockscreen Propiedad de Amovil
location accurate
wait 10
lock
```

## Kiosk safety

Leaving the pinned app repeatedly (Back/Home pressed several times, or a crashing app) never releases the kiosk:
after more than 3 returns in a minute the phone stays locked and shows *Open <app>*; one tap reopens it. A phone
whose app really keeps crashing stays reachable from the console (check-ins continue) and can leave kiosk remotely.

## Verification

`scripts/parity-e2e.sh` (API + a real phone/emulator over adb) and `scripts/parity-console-e2e.mjs` (the console in a
browser) check every item above end to end — see [TESTING.md](TESTING.md).
