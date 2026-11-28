/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.DeviceEvent;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.persistence.domain.DeviceSyncRow;
import com.hmdm.persistence.mapper.AgentCommandMapper;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.DeviceEventMapper;
import com.hmdm.persistence.mapper.DeviceStateMapper;

import java.util.List;

/**
 * <p>DAO for the opaque agent v1 command queue and the device capability column. The server never
 * interprets command {@code type}/{@code payload}; this DAO only stores and forwards them.</p>
 */
@Singleton
public class AgentCommandDAO {

    private final AgentCommandMapper mapper;
    private final AgentDeviceMapper deviceMapper;
    private final DeviceStateMapper stateMapper;
    private final DeviceEventMapper eventMapper;

    @Inject
    public AgentCommandDAO(AgentCommandMapper mapper, AgentDeviceMapper deviceMapper,
                          DeviceStateMapper stateMapper, DeviceEventMapper eventMapper) {
        this.mapper = mapper;
        this.deviceMapper = deviceMapper;
        this.stateMapper = stateMapper;
        this.eventMapper = eventMapper;
    }

    /**
     * Queue a command. Every route goes through here, so this is where the issuer is stamped: the console user
     * serving the current request (id, login, address), or {@link AgentCommand#SYSTEM_ISSUER} when nobody is
     * signed in on this thread (a device check-in, an enrollment, a background task).
     */
    public void insert(AgentCommand command) {
        stampIssuer(command);
        String type = command.getType();
        boolean wipe = com.hmdm.util.ProtectionCommands.WIPE.equals(type);
        boolean lost = com.hmdm.util.ProtectionCommands.LOST_MODE.equals(type);
        // Anti-theft orders keep only known, bounded options (a wipe without options keeps the reset protection).
        if (wipe) command.setPayload(com.hmdm.util.ProtectionCommands.normalizeWipe(command.getPayload()));
        if (lost) command.setPayload(com.hmdm.util.ProtectionCommands.normalizeLostMode(command.getPayload()));
        mapper.insert(command);
        if (wipe || lost) recordProtectionOrder(command, wipe);
    }

    /** Who ordered a wipe or lost mode, from where and with which options: kept in the device's events. */
    private void recordProtectionOrder(AgentCommand command, boolean wipe) {
        try {
            String what = wipe ? com.hmdm.util.ProtectionCommands.describeWipe(command.getPayload())
                    : com.hmdm.util.ProtectionCommands.describeLostMode(command.getPayload());
            String who = command.getIssuerName() + (command.getIssuerIp() == null ? "" : " (" + command.getIssuerIp() + ")");
            insertEvent(command.getDeviceNumber(), wipe ? EVENT_WIPE_ORDERED : EVENT_LOST_MODE_ORDERED,
                    command.getCreatedAt() == null ? System.currentTimeMillis() : command.getCreatedAt(),
                    "Orden " + command.getId() + " de " + who + ": " + what);
        } catch (Exception e) {
            // The order itself is queued; a missing history line must not fail it.
        }
    }

    public static final String EVENT_WIPE_ORDERED = "wipeOrdered";
    public static final String EVENT_WIPE_STARTED = "wipeStarted";
    public static final String EVENT_WIPE_FAILED = "wipeFailed";
    public static final String EVENT_LOST_MODE_ORDERED = "lostModeOrdered";
    public static final String EVENT_REENROLLED = "reenrolled";
    public static final String DETAIL_WIPE_ANNOUNCED =
            "Borrado ejecutado: el equipo confirmó que iniciaba el borrado y no volvió a reportar";

    static void stampIssuer(AgentCommand command) {
        if (command.getIssuerName() != null) return;
        com.hmdm.security.SecurityContext ctx = com.hmdm.security.SecurityContext.get();
        com.hmdm.persistence.domain.User user = ctx == null ? null : ctx.getCurrentUser().orElse(null);
        if (user == null) {
            command.setIssuerName(AgentCommand.SYSTEM_ISSUER);
            return;
        }
        command.setIssuerId(user.getId());
        String login = user.getLogin();
        command.setIssuerName(login == null || login.trim().isEmpty() ? "usuario " + user.getId() : login);
        command.setIssuerIp(com.hmdm.security.RequestOrigin.ip());
    }

    /** Delivered commands (other than an agent self-install) the device has not answered yet. */
    public int countInFlight(String deviceNumber) {
        return mapper.countInFlight(deviceNumber);
    }

    public List<AgentCommand> listPending(String deviceNumber) {
        return mapper.listPending(deviceNumber);
    }

    /**
     * Record a result status + detail + the completion timestamp (epoch millis). Ownership-scoped:
     * the row is only touched when it belongs to {@code deviceNumber}, and a genuinely terminal
     * done/failed/unsupported is never overwritten (device-reported results DO overwrite 'expired').
     */
    public void markResultWithTime(String deviceNumber, Integer commandId, String status,
                                   String detail, Long completedAt) {
        mapper.markResultWithTime(deviceNumber, commandId, status, detail, completedAt);
    }

    /** Atomically claim a pending command for delivery. Returns true iff this caller claimed it. */
    public boolean claimForDelivery(Integer commandId, Long deliveredAt) {
        return mapper.claimForDelivery(commandId, deliveredAt) == 1;
    }

    /**
     * Lazy housekeeping of a device's queue, by the per-type table in {@link com.hmdm.util.CommandPolicy}: commands
     * whose wait is over end as expired, and delivered commands the device never answered go back to the queue
     * (the answer, or the delivery itself, was lost). A check-in only happens once the phone's previous cycle ended
     * (one at a time), so a command still running on the phone is never handed out a second time.
     *
     * @param ledger the device's agent advertises {@link com.hmdm.util.CommandPolicy#LEDGER_CAPABILITY}
     */
    public com.hmdm.util.CommandQueueSweep.Outcome sweep(final String deviceNumber, boolean ledger) {
        return com.hmdm.util.CommandQueueSweep.run(queue(), deviceNumber, ledger, System.currentTimeMillis());
    }

    /** Close the device's overdue commands without handing anything out again (the console reading its history). */
    public void expireOverdue(String deviceNumber) {
        com.hmdm.util.CommandQueueSweep.expireOnly(queue(), deviceNumber, System.currentTimeMillis());
    }

    private com.hmdm.util.CommandQueueSweep.Queue queue() {
        return new com.hmdm.util.CommandQueueSweep.Queue() {
            @Override
            public List<AgentCommand> listOpen(String number) {
                return mapper.listOpen(number);
            }

            @Override
            public void expire(Integer id, long now, String detail) {
                mapper.expireOpen(id, now, detail);
            }

            @Override
            public void requeue(Integer id) {
                mapper.requeueDelivered(id);
            }

            @Override
            public boolean newerDelivered(String number, Integer id, java.util.Set<String> lowerCaseTypes) {
                return mapper.countNewerDelivered(number, id, String.join(",", lowerCaseTypes)) > 0;
            }
        };
    }

    /** Withdraw a command that has not run yet. @return false when it was already closed (or is not this device's). */
    public boolean cancel(String deviceNumber, Integer commandId, String detail) {
        return mapper.cancelOpen(deviceNumber, commandId, System.currentTimeMillis(), detail) > 0;
    }

    /** A device is taken as wiped when it received the wipe and stayed silent this long. */
    public static final long WIPE_SILENCE_MS = 10 * 60_000L;
    /** The check-in that carries the wipe (and its best-effort answer) may stamp lastUpdate slightly after delivery. */
    public static final long WIPE_SLACK_MS = 60_000L;
    public static final String DETAIL_WIPE_INFERRED =
            "Borrado confirmado por silencio: el equipo recibió la orden y no volvió a reportar";
    public static final String EVENT_WIPE_NOT_COMPLETED = "wipeNotCompleted";
    public static final String DETAIL_WIPE_NOT_COMPLETED =
            "El equipo siguió reportando después de la orden de borrado: no se borró. Se retira la marca «Borrado»";
    public static final String DETAIL_WIPE_REENROLLED = "El equipo se inscribió de nuevo después de recibir el borrado";
    public static final String DETAIL_WIPE_WITHDRAWN = "Retirado: el equipo se inscribió de nuevo antes de recibirlo";

    /**
     * Bring the "wiped" marks of the customer's devices up to date and return them, keyed by device number
     * ({@code wipedat}, {@code wipedby}). A device that received a wipe and went silent gets the mark; one that
     * reports again loses it. The device row is never deleted.
     */
    public List<java.util.Map<String, Object>> refreshWipedDevices(int customerId) {
        long now = System.currentTimeMillis();
        // Still reporting with its old credentials more than a minute after the mark: the phone was NOT wiped
        // (a wiped phone has to enroll again). The mark goes away and its history says why.
        mapper.noteWipesNotCompleted(customerId, WIPE_SLACK_MS, now, EVENT_WIPE_NOT_COMPLETED, DETAIL_WIPE_NOT_COMPLETED);
        mapper.unmarkReportingDevices(customerId, WIPE_SLACK_MS);
        mapper.markWipedDevices(customerId, now - WIPE_SILENCE_MS, WIPE_SLACK_MS);
        mapper.closeWipesOfWipedDevices(customerId, DETAIL_WIPE_INFERRED);
        mapper.closeAnnouncedWipes(customerId, now - WIPE_SILENCE_MS, WIPE_SLACK_MS, DETAIL_WIPE_ANNOUNCED);
        return mapper.listWipedDevices(customerId);
    }

    /** A phone enrolling again must never receive the wipe queued for its previous life. */
    public void closeWipesOnReenroll(String deviceNumber) {
        mapper.closeWipesOnReenroll(deviceNumber, System.currentTimeMillis(), DETAIL_WIPE_REENROLLED, DETAIL_WIPE_WITHDRAWN);
        // Back in service: no longer shown as wiped (and not as "the wipe did not complete" either).
        mapper.clearWipedMark(deviceNumber);
    }

    /** Command lifecycle history for a device, newest first, created at/after {@code since}. */
    public List<AgentCommand> listHistory(String deviceNumber, long since, int limit) {
        return mapper.listHistory(deviceNumber, since, limit);
    }

    /** Upsert the latest device-state snapshot. */
    public void upsertState(DeviceState state) {
        stateMapper.upsert(state);
    }

    /** Current device-state snapshot, or null if none reported yet. */
    public DeviceState getState(String deviceNumber) {
        return stateMapper.findByDeviceNumber(deviceNumber);
    }

    /** Insert one agent lifecycle event. */
    public void insertEvent(String deviceNumber, String type, Long ts, String detail) {
        DeviceEvent e = new DeviceEvent();
        e.setDeviceNumber(deviceNumber);
        e.setType(type);
        e.setTs(ts);
        e.setDetail(detail);
        eventMapper.insert(e);
    }

    /** Event timeline for a device, newest first, at/after {@code since}. */
    public List<DeviceEvent> listEvents(String deviceNumber, long since, int limit) {
        return eventMapper.list(deviceNumber, since, limit);
    }

    /** Event timeline restricted to specific types (e.g. just the user-activity events), newest first. */
    public List<DeviceEvent> listEventsByTypes(String deviceNumber, long since, List<String> types, int limit) {
        if (types == null || types.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return eventMapper.listByTypes(deviceNumber, since, types, limit);
    }

    public AgentCommand findByDeviceAndId(String deviceNumber, Integer commandId) {
        return mapper.findByDeviceAndId(deviceNumber, commandId);
    }

    public void updateDeviceCapabilities(String deviceNumber, String capabilitiesJson) {
        deviceMapper.updateDeviceCapabilities(deviceNumber, capabilitiesJson);
    }

    public String getDeviceCapabilities(String deviceNumber) {
        return deviceMapper.getDeviceCapabilities(deviceNumber);
    }

    public void updateDeviceSecretHash(String deviceNumber, String secretHash) {
        deviceMapper.updateDeviceSecretHash(deviceNumber, secretHash);
    }

    public void updateHardwareId(String deviceNumber, String hardwareId) {
        deviceMapper.updateHardwareId(deviceNumber, hardwareId);
    }

    /** Number of an existing device of [customerId] with this stable hardware id, or null (enroll de-dup). */
    public String findDeviceNumberByHardwareId(int customerId, String hardwareId) {
        return deviceMapper.findDeviceNumberByHardwareId(customerId, hardwareId);
    }

    /** Number of an existing device of [customerId] with this serial, or null (enroll de-dup across factory resets). */
    public String findDeviceNumberBySerial(int customerId, String serial) {
        return deviceMapper.findDeviceNumberBySerial(customerId, serial);
    }

    public String findDeviceNumberByImei(int customerId, String imei) {
        return deviceMapper.findDeviceNumberByImei(customerId, imei);
    }

    /** Packages shown in a single device's kiosk (per-device "show in kiosk"). */
    public java.util.List<String> listDeviceKioskApps(int deviceId) {
        return deviceMapper.listDeviceKioskApps(deviceId);
    }

    public void addDeviceKioskApp(int deviceId, String packageName) {
        deviceMapper.addDeviceKioskApp(deviceId, packageName);
    }

    public void removeDeviceKioskApp(int deviceId, String packageName) {
        deviceMapper.removeDeviceKioskApp(deviceId, packageName);
    }

    /** Per-device "share data" (tethering) override: "allow" / "block", or null when it follows the policy. */
    public String getDeviceDataSharing(int deviceId) {
        return deviceMapper.getDeviceDataSharing(deviceId);
    }

    public void setDeviceDataSharing(int deviceId, String state) {
        deviceMapper.setDeviceDataSharing(deviceId, state);
    }

    public void clearDeviceDataSharing(int deviceId) {
        deviceMapper.clearDeviceDataSharing(deviceId);
    }

    /** Per-device app-permission rules (JSON { package: rules }), or null when the device follows its policy. */
    public String getDevicePermissionOverride(int deviceId) {
        return deviceMapper.getDevicePermissionOverride(deviceId);
    }

    /** Stores (or, with null/blank, removes) the device's own app-permission rules. */
    public void setDevicePermissionOverride(int deviceId, String rules) {
        if (rules == null || rules.trim().isEmpty()) deviceMapper.clearDevicePermissionOverride(deviceId);
        else deviceMapper.setDevicePermissionOverride(deviceId, rules, System.currentTimeMillis());
    }

    /** The agent's last permission report (JSON), or null. */
    public String getDevicePermissionReport(int deviceId) {
        return deviceMapper.getDevicePermissionReport(deviceId);
    }

    public Long getDevicePermissionReportAt(int deviceId) {
        return deviceMapper.getDevicePermissionReportAt(deviceId);
    }

    public void setDevicePermissionReport(int deviceId, String report) {
        deviceMapper.setDevicePermissionReport(deviceId, report, System.currentTimeMillis());
    }

    /** The device's hardware and ports override (JSON), or null when it follows its policy. */
    public String getDeviceHardwareOverride(int deviceId) {
        return deviceMapper.getDeviceHardwareOverride(deviceId);
    }

    /** @param rules JSON object; null clears the override. */
    public void setDeviceHardwareOverride(int deviceId, String rules) {
        if (rules == null) deviceMapper.clearDeviceHardwareOverride(deviceId);
        else deviceMapper.setDeviceHardwareOverride(deviceId, rules);
    }

    public String getDeviceSecretHash(String deviceNumber) {
        return deviceMapper.getDeviceSecretHash(deviceNumber);
    }

    /** Stamp the device's last-seen time (drives the admin UI's online/offline state). */
    public void touchLastUpdate(String deviceNumber) {
        deviceMapper.updateLastUpdate(deviceNumber, System.currentTimeMillis());
    }

    /** Persist the reported Android version into infojson so the device list can show it. */
    public void updateIdentity(String deviceNumber, String serial, String imei) {
        deviceMapper.updateIdentity(deviceNumber, serial, imei);
    }

    public void updateAndroidVersion(String deviceNumber, String androidVersion) {
        deviceMapper.updateAndroidVersion(deviceNumber, androidVersion);
    }

    // --- Expected SIM phone (declared at enrollment) vs the SIM's reported number ---

    public String getExpectedPhone(String deviceNumber) {
        return deviceMapper.getExpectedPhone(deviceNumber);
    }

    public Boolean getSimMismatch(String deviceNumber) {
        return deviceMapper.getSimMismatch(deviceNumber);
    }

    public void updateExpectedPhone(String deviceNumber, String expectedPhone) {
        deviceMapper.updateExpectedPhone(deviceNumber, expectedPhone);
    }

    public void updateSimObservation(String deviceNumber, String simPhone, Boolean simMismatch, long ts) {
        deviceMapper.updateSimObservation(deviceNumber, simPhone, simMismatch, ts);
    }

    /**
     * Append a location fix to the device's trail, skipping it if it isn't newer than the last
     * stored fix — passive last-known reporting returns the same fix until the OS refreshes it, so
     * de-duping on capturedAt keeps the trail meaningful without a distance calc. The dedupe lives
     * inside the INSERT itself (single statement), so concurrent check-ins can't double-insert.
     */
    public void recordLocation(com.hmdm.persistence.domain.DeviceLocation location) {
        recordLocationIfNew(location);
    }

    /** Same as {@link #recordLocation}; @return true when the fix was new and stored (geofences evaluate only those). */
    public boolean recordLocationIfNew(com.hmdm.persistence.domain.DeviceLocation location) {
        location.setRecordedAt(System.currentTimeMillis());
        return deviceMapper.insertLocation(location) > 0;
    }

    public java.util.List<com.hmdm.persistence.domain.DeviceLocation> listLocations(
            String deviceNumber, long since, int limit) {
        return deviceMapper.listLocations(deviceNumber, since, limit);
    }

    /** Fixes of all the customer's devices captured in [from, to], grouped by device, oldest first. */
    public java.util.List<com.hmdm.persistence.domain.DeviceLocation> listFleetLocations(
            int customerId, long from, long to, int limit) {
        return deviceMapper.listFleetLocations(customerId, from, to, limit);
    }

    /** Fleet fixes narrowed to a folder editor's own devices (the cap applies after scoping). */
    public java.util.List<com.hmdm.persistence.domain.DeviceLocation> listFleetLocationsScoped(
            int customerId, long from, long to, int limit, String devs) {
        return deviceMapper.listFleetLocationsScoped(customerId, from, to, limit, devs);
    }

    // --- App versions ---

    /**
     * Point every configuration that uses the app (as an app or as its main app) at [newVersionId]; returns the ids
     * of the configurations that use the app.
     */
    public List<Integer> moveConfigurationsToAppVersion(int appId, int newVersionId) {
        deviceMapper.relinkConfigurationApps(appId, newVersionId);
        deviceMapper.relinkConfigurationMainApps(appId, newVersionId);
        return deviceMapper.listConfigurationsUsingApp(appId);
    }

    // --- Groups (companies) and configuration scopes ---

    public java.util.List<com.hmdm.persistence.domain.DeviceGroupView> listGroups(int customerId) {
        return deviceMapper.listGroups(customerId);
    }

    public com.hmdm.persistence.domain.DeviceGroupView findGroup(int customerId, int groupId) {
        return deviceMapper.findGroup(customerId, groupId);
    }

    public boolean groupNameTaken(int customerId, String name, Integer parentId, Integer exceptId) {
        return deviceMapper.countGroupsNamed(customerId, name, parentId, exceptId) > 0;
    }

    public int insertGroup(int customerId, String name, Integer configurationId, Integer parentId) {
        com.hmdm.persistence.domain.DeviceGroupInsert g = new com.hmdm.persistence.domain.DeviceGroupInsert();
        g.setCustomerId(customerId);
        g.setName(name);
        g.setConfigurationId(configurationId);
        g.setParentId(parentId);
        deviceMapper.insertGroup(g);
        return g.getId();
    }

    public boolean updateGroup(int customerId, int id, String name, Integer configurationId, Integer parentId) {
        return deviceMapper.updateGroup(customerId, id, name, configurationId, parentId) > 0;
    }

    private static final java.util.concurrent.atomic.AtomicLong lastUsagePurge = new java.util.concurrent.atomic.AtomicLong();
    public static final int APP_USAGE_KEEP_DAYS = 90;

    /** Store one day of a device's per-app usage (rows: pkg, label, foregroundMs, wifiBytes, mobileBytes, batteryPct). */
    public void saveAppUsage(String deviceNumber, String day, java.util.List<java.util.Map<String, Object>> apps) {
        long now = System.currentTimeMillis();
        for (java.util.Map<String, Object> u : apps) deviceMapper.upsertAppUsage(deviceNumber, day, u, now);
        long prev = lastUsagePurge.get();
        if (now - prev > 6 * 3600_000L && lastUsagePurge.compareAndSet(prev, now)) {
            deviceMapper.purgeAppUsage(java.time.LocalDate.now().minusDays(APP_USAGE_KEEP_DAYS).toString());
        }
    }

    public java.util.List<java.util.Map<String, Object>> listAppUsage(String deviceNumber, int days) {
        return deviceMapper.listAppUsage(deviceNumber, java.time.LocalDate.now().minusDays(Math.max(0, days)).toString());
    }

    /** How long a deleted device may still come back by itself; an administrator's claim lasts {@link #CLAIM_TTL_MS}. */
    public static final long TOMBSTONE_TTL_MS = 90L * 24 * 3600_000L;
    public static final long CLAIM_TTL_MS = 7L * 24 * 3600_000L;

    /** The kept identity of a deleted device that may still be enrolled (null when none, or too old). */
    public java.util.Map<String, Object> findTombstone(String number) {
        java.util.Map<String, Object> t = deviceMapper.findTombstone(number, System.currentTimeMillis() - TOMBSTONE_TTL_MS);
        if (t != null && t.get("secrethash") == null
                && ((Number) t.get("deletedat")).longValue() < System.currentTimeMillis() - CLAIM_TTL_MS) return null;
        return t;
    }

    public void deleteTombstone(String number) { deviceMapper.deleteTombstone(number); }

    public boolean forgetTombstone(int customerId, String number) { return deviceMapper.forgetTombstone(customerId, number) > 0; }

    public void claimTombstone(String number, int customerId, String description, Integer groupId) {
        deviceMapper.claimTombstone(number, customerId, description, groupId, System.currentTimeMillis());
    }

    public java.util.List<java.util.Map<String, Object>> listTombstones(int customerId) {
        return deviceMapper.listTombstones(customerId, System.currentTimeMillis() - TOMBSTONE_TTL_MS);
    }

    public Integer findTopGroupByName(int customerId, String name) { return deviceMapper.findTopGroupByName(customerId, name); }

    public boolean configurationExists(int customerId, int id) { return deviceMapper.countConfiguration(customerId, id) > 0; }

    public void setDescription(String number, String description) { deviceMapper.setDescription(number, description); }

    /** Kiosk branding of a device's folders, nearest first (JSON, see KioskBrand). */
    public java.util.List<String> deviceFolderBrands(int deviceId) {
        return deviceMapper.listDeviceFolderBrands(deviceId);
    }

    public boolean updateGroupBrand(int customerId, int id, String brandJson) {
        return deviceMapper.updateGroupBrand(customerId, id, brandJson) > 0;
    }

    public boolean updateGroupConsoleBrand(int customerId, int id, String brandJson) {
        return deviceMapper.updateGroupConsoleBrand(customerId, id, brandJson) > 0;
    }

    /** Set (or clear with null) the device's own email account, by device number (enrollment inheritance). */
    public void updateDeviceAccountEmail(String deviceNumber, String accountEmail) {
        deviceMapper.updateDeviceAccountEmail(deviceNumber, accountEmail);
    }

    /** Set (or clear with null) the device's own email account, by numeric device id (console fleet action). */
    public void updateDeviceAccountEmailById(int deviceId, String accountEmail) {
        deviceMapper.updateDeviceAccountEmailById(deviceId, accountEmail);
    }

    /** Set (or clear with null) a group/folder's own email account. */
    public boolean updateGroupAccountEmail(int customerId, int id, String accountEmail) {
        return deviceMapper.updateGroupAccountEmail(customerId, id, accountEmail) > 0;
    }

    /** Move a group's direct children under {@code parentId} (null = top level). */
    public void reparentChildren(int customerId, int id, Integer parentId) {
        deviceMapper.reparentChildren(customerId, id, parentId);
    }

    /**
     * Extend the device's current connection session, or open a new one when it was silent longer than [gapMs] (the
     * previous session then ended at its last check-in).
     */
    public void recordPresence(String deviceNumber, long now, long gapMs) {
        if (deviceMapper.extendConnection(deviceNumber, now, now - gapMs) == 0) {
            deviceMapper.openConnection(deviceNumber, now);
        }
    }

    /** Samples kept per device: at most one every {@link #METRIC_EVERY_MS}, for {@link #METRIC_KEEP_MS}. */
    public static final long METRIC_EVERY_MS = 5 * 60_000L;
    public static final long METRIC_KEEP_MS = 30L * 24 * 3600_000L;
    private static final java.util.concurrent.atomic.AtomicLong lastPurge = new java.util.concurrent.atomic.AtomicLong();

    /** Store a history sample (skipped when the last one is recent); purges month-old samples about once an hour. */
    public void recordMetric(String deviceNumber, long now, java.util.Map<String, Object> m) {
        deviceMapper.insertMetric(deviceNumber, now, now - METRIC_EVERY_MS, m);
        long prev = lastPurge.get();
        if (now - prev > 3600_000L && lastPurge.compareAndSet(prev, now)) {
            deviceMapper.purgeMetrics(now - METRIC_KEEP_MS);
        }
    }

    public java.util.List<java.util.Map<String, Object>> listMetrics(String deviceNumber, long from) {
        return deviceMapper.listMetrics(deviceNumber, from);
    }

    public java.util.List<java.util.Map<String, Object>> listDeviceConnections(String deviceNumber, long from) {
        return deviceMapper.listDeviceConnections(deviceNumber, from);
    }

    public java.util.List<java.util.Map<String, Object>> listConnections(int customerId, long from, long to) {
        return deviceMapper.listConnections(customerId, from, to);
    }

    public java.util.List<java.util.Map<String, Object>> listDeviceExportRows(int customerId) {
        return deviceMapper.listDeviceExportRows(customerId);
    }

    public boolean nameIfUnnamed(String deviceNumber, String name) {
        return deviceMapper.nameIfUnnamed(deviceNumber, name) > 0;
    }

    /** The group and all its descendants. */
    public java.util.List<Integer> groupSubtree(int customerId, int groupId) {
        return deviceMapper.listGroupSubtree(customerId, groupId);
    }

    public boolean deleteGroup(int customerId, int id) {
        return deviceMapper.deleteGroup(customerId, id) > 0;
    }

    /** Put the device in exactly one group, or in none when {@code groupId} is null. */
    public void setDeviceGroup(int deviceId, Integer groupId) {
        deviceMapper.clearDeviceGroups(deviceId);
        if (groupId != null) {
            deviceMapper.addDeviceGroup(deviceId, groupId);
        }
    }

    public java.util.List<com.hmdm.persistence.domain.DeviceScopeRow> listDeviceScopes(int customerId) {
        return deviceMapper.listDeviceScopes(customerId);
    }

    public com.hmdm.persistence.domain.DeviceScopeRow findDeviceScope(int customerId, int deviceId) {
        return deviceMapper.findDeviceScope(customerId, deviceId);
    }

    public void updateDeviceConfiguration(int deviceId, int configurationId) {
        deviceMapper.updateDeviceConfiguration(deviceId, configurationId);
    }

    public void updateDevicePinned(int deviceId, boolean pinned) {
        deviceMapper.updateDevicePinned(deviceId, pinned);
    }

    public Integer getGlobalConfigurationId(int customerId) {
        return deviceMapper.getGlobalConfigurationId(customerId);
    }

    public void updateGlobalConfigurationId(int customerId, int configurationId) {
        deviceMapper.updateGlobalConfigurationId(customerId, configurationId);
    }

    /** True if the very same command (type + payload) is already pending or delivered for the device. */
    public boolean hasOpenIdentical(String deviceNumber, String type, String payload) {
        return mapper.countOpenIdentical(deviceNumber, type, payload) > 0;
    }

    /** True if a pending or delivered command of {@code type} is already queued for the device. */
    public boolean hasOpenOfType(String deviceNumber, String type) {
        return mapper.countOpenOfType(deviceNumber, type) > 0;
    }

    /** The most recently created command of {@code type} for the device, or null if none exists. */
    public AgentCommand findLatestOfType(String deviceNumber, String type) {
        return mapper.findLatestOfType(deviceNumber, type);
    }

    /** Device numbers currently assigned to a configuration. */
    public List<String> listDeviceNumbersByConfigurationId(int configurationId) {
        return deviceMapper.listDeviceNumbersByConfigurationId(configurationId);
    }

    /** One row per device of the customer with its configuration + last applied revision. */
    public List<DeviceSyncRow> listDevicesForSync(int customerId) {
        return deviceMapper.listDevicesForSync(customerId);
    }

    /** Devices of one configuration with their applied revision + display info (for the drift / compliance list). */
    public List<com.hmdm.persistence.domain.ConfigDriftRow> listDevicesForConfig(int customerId, int configurationId) {
        return deviceMapper.listDevicesForConfig(customerId, configurationId);
    }
}
