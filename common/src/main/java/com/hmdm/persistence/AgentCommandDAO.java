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

    public void insert(AgentCommand command) {
        mapper.insert(command);
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
     * Lazy TTL expiry. Pending commands age out {@code pendingTtlMillis} after creation;
     * delivered ones get their own {@code deliveredTtlMillis} leash from delivery time (the
     * device already holds them — see the mapper note).
     */
    public void expireStale(String deviceNumber, long pendingTtlMillis, long deliveredTtlMillis) {
        long now = System.currentTimeMillis();
        mapper.expireStale(deviceNumber, now - pendingTtlMillis, now - deliveredTtlMillis, now);
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

    /**
     * Append a location fix to the device's trail, skipping it if it isn't newer than the last
     * stored fix — passive last-known reporting returns the same fix until the OS refreshes it, so
     * de-duping on capturedAt keeps the trail meaningful without a distance calc. The dedupe lives
     * inside the INSERT itself (single statement), so concurrent check-ins can't double-insert.
     */
    public void recordLocation(com.hmdm.persistence.domain.DeviceLocation location) {
        location.setRecordedAt(System.currentTimeMillis());
        deviceMapper.insertLocation(location);
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

    /** Move a group's direct children under {@code parentId} (null = top level). */
    public void reparentChildren(int customerId, int id, Integer parentId) {
        deviceMapper.reparentChildren(customerId, id, parentId);
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
}
