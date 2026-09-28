# ADR 0011 — Groups (companies) and three levels of change

**Status:** Accepted (2026-09-28)

## Context

The console treated the fleet as one flat list: each device had one configuration picked by hand, and bulk
actions ran on a hand-made selection. DallyControl manages devices for several companies (DISAY, AMOVIL, …) and
needs to change things for one device, for one company, or for everyone.

## Decision

- **Groups are companies.** Headwind's existing `groups` / `deviceGroups` tables are reused; DallyControl keeps
  exactly one group per device (`POST /private/fleet/v1/devices/group` replaces the membership).
- **Configuration has three levels, the most specific wins:** a configuration set on the device (`devices.
  configurationPinned`), else its group's (`groups.configurationId`), else the global default (the customer's
  `settings.newDeviceConfigurationId`). `ConfigurationScopes` decides; `devices.configurationId` always holds the
  result, so the desired-state reconcile (`config.apply`) is unchanged. Changing a level re-resolves the devices
  under it and wakes the ones whose configuration changed (`ConfigurationScopeApplier`).
- **Actions have three levels:** one device (as before), a group (`POST /groups/{id}/commands`) or every device
  (`POST /global/commands`). Wipe and passcode reset are refused for groups and the whole fleet.
- **Enrollment** tokens may carry a group (`groupId`): the device enrolls into its company and inherits its
  configuration. A token's `configurationId` still pins one on the device.
- **Console:** a Groups page (global default + actions on all devices; per group: devices, configuration, actions,
  rename, delete), a group column/filter and "Move to group" in Devices, a "Group & configuration" card on the device
  page, the group selector on Enroll, and the global configuration in Settings.

## Consequences

- (+) One place per level; a group change reaches all its devices at once, a device can still deviate.
- (+) No agent change: devices only ever see their effective configuration.
- (−) One group per device: a device cannot belong to two companies. Headwind's many-to-many table would allow it,
  but precedence between two group configurations would be ambiguous.
- (−) Headwind's legacy device editing (`PUT /private/devices`) still writes `configurationId` directly; the console
  no longer uses it, and the next re-resolution of that device's level overrides such a write unless it is pinned.
