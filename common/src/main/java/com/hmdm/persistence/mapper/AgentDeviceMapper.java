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

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.DeviceSyncRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>Minimal mapper for the agent v1 capability column on the {@code devices} table. Kept separate
 * from {@link DeviceMapper} so the agent feature touches the shared device mapper as little as
 * possible.</p>
 */
public interface AgentDeviceMapper {

    @Update({"UPDATE devices SET agentCapabilities = #{capabilitiesJson} WHERE number = #{deviceNumber}"})
    void updateDeviceCapabilities(@Param("deviceNumber") String deviceNumber,
                                  @Param("capabilitiesJson") String capabilitiesJson);

    @Select({"SELECT agentCapabilities FROM devices WHERE number = #{deviceNumber}"})
    String getDeviceCapabilities(@Param("deviceNumber") String deviceNumber);

    @Update({"UPDATE devices SET agentSecretHash = #{secretHash} WHERE number = #{deviceNumber}"})
    void updateDeviceSecretHash(@Param("deviceNumber") String deviceNumber,
                                @Param("secretHash") String secretHash);

    @Select({"SELECT agentSecretHash FROM devices WHERE number = #{deviceNumber}"})
    String getDeviceSecretHash(@Param("deviceNumber") String deviceNumber);

    @Update({"UPDATE devices SET lastUpdate = #{ts} WHERE number = #{deviceNumber}"})
    void updateLastUpdate(@Param("deviceNumber") String deviceNumber, @Param("ts") long ts);

    @Update({"UPDATE devices SET hardwareid = #{hardwareId} WHERE number = #{deviceNumber}"})
    void updateHardwareId(@Param("deviceNumber") String deviceNumber,
                          @Param("hardwareId") String hardwareId);

    /**
     * Store the Android version into infojson (the device list reads
     * {@code devices.infojson ->> 'androidVersion'}; our agent reports it via telemetry/state).
     */
    @Update({"UPDATE devices SET infojson = jsonb_set(COALESCE(infojson, '{}'::jsonb), " +
            "'{androidVersion}', to_jsonb(#{androidVersion}::text), true) WHERE number = #{deviceNumber}"})
    void updateAndroidVersion(@Param("deviceNumber") String deviceNumber,
                              @Param("androidVersion") String androidVersion);

    /** Mirror the agent-reported serial / IMEI into the device row (the list shows and searches them there). */
    @Update({"UPDATE devices SET imei = COALESCE(#{imei}, imei), infojson = CASE WHEN #{serial}::text IS NULL THEN infojson " +
            "ELSE jsonb_set(COALESCE(infojson, '{}'::jsonb), '{serial}', to_jsonb(#{serial}::text), true) END " +
            "WHERE number = #{deviceNumber}"})
    void updateIdentity(@Param("deviceNumber") String deviceNumber, @Param("serial") String serial, @Param("imei") String imei);

    // --- Location breadcrumb trail (device_location) ---

    /**
     * Append a fix ONLY when it's newer than everything stored — dedupe and insert in one
     * statement, so two concurrent check-ins carrying the same fix can't both slip past a
     * separate read-then-insert check.
     */
    @Insert({"INSERT INTO device_location (deviceNumber, lat, lon, accuracy, provider, capturedAt, recordedAt) " +
            "SELECT #{deviceNumber}, #{lat}, #{lon}, #{accuracy}, #{provider}, #{capturedAt}, #{recordedAt} " +
            "WHERE NOT EXISTS (SELECT 1 FROM device_location " +
            "WHERE deviceNumber = #{deviceNumber} AND capturedAt >= #{capturedAt})"})
    void insertLocation(DeviceLocation location);

    @Select({"SELECT * FROM device_location WHERE deviceNumber = #{deviceNumber} AND capturedAt >= #{since} " +
            "ORDER BY capturedAt DESC LIMIT #{limit}"})
    List<DeviceLocation> listLocations(@Param("deviceNumber") String deviceNumber,
                                       @Param("since") long since, @Param("limit") int limit);

    /** Every fix of the customer's devices captured in [from, to], per device in time order (the fleet map). */
    @Select({"SELECT l.* FROM device_location l JOIN devices d ON d.number = l.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND l.capturedAt BETWEEN #{from} AND #{to} " +
            "ORDER BY l.deviceNumber, l.capturedAt LIMIT #{limit}"})
    List<DeviceLocation> listFleetLocations(@Param("customerId") int customerId, @Param("from") long from,
                                            @Param("to") long to, @Param("limit") int limit);

    // --- Connection history (device_connection): a session lasts while check-ins keep coming within the gap ---

    @Update({"UPDATE device_connection SET lastSeenAt = #{now} WHERE id = (SELECT id FROM device_connection " +
            "WHERE deviceNumber = #{number} ORDER BY lastSeenAt DESC LIMIT 1) AND lastSeenAt >= #{since}"})
    int extendConnection(@Param("number") String number, @Param("now") long now, @Param("since") long since);

    @Insert({"INSERT INTO device_connection (deviceNumber, connectedAt, lastSeenAt) VALUES (#{number}, #{now}, #{now})"})
    void openConnection(@Param("number") String number, @Param("now") long now);

    @Select({"SELECT c.deviceNumber, c.connectedAt, c.lastSeenAt FROM device_connection c JOIN devices d ON d.number = c.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND c.lastSeenAt >= #{from} AND c.connectedAt <= #{to} " +
            "ORDER BY c.deviceNumber, c.connectedAt"})
    List<java.util.Map<String, Object>> listConnections(@Param("customerId") int customerId, @Param("from") long from, @Param("to") long to);

    // --- Device history (device_metric): one sample at most every few minutes, kept a month ---

    @Insert({"INSERT INTO device_metric (deviceNumber, ts, battery, charging, networkType, wifiRssi, signalLevel, " +
            "freeStorageBytes, freeRamBytes, kioskActive, locked) " +
            "SELECT #{number}, #{ts}, #{m.battery}, #{m.charging}, #{m.networkType}, #{m.wifiRssi}, #{m.signalLevel}, " +
            "#{m.freeStorageBytes}, #{m.freeRamBytes}, #{m.kioskActive}, #{m.locked} " +
            "WHERE NOT EXISTS (SELECT 1 FROM device_metric WHERE deviceNumber = #{number} AND ts > #{since})"})
    int insertMetric(@Param("number") String number, @Param("ts") long ts, @Param("since") long since,
                     @Param("m") java.util.Map<String, Object> m);

    @Select({"SELECT ts, battery, charging, networkType, wifiRssi, signalLevel, freeStorageBytes, freeRamBytes, kioskActive, locked " +
            "FROM device_metric WHERE deviceNumber = #{number} AND ts >= #{from} ORDER BY ts"})
    List<java.util.Map<String, Object>> listMetrics(@Param("number") String number, @Param("from") long from);

    @Select({"SELECT connectedAt, lastSeenAt FROM device_connection WHERE deviceNumber = #{number} AND lastSeenAt >= #{from} " +
            "ORDER BY connectedAt"})
    List<java.util.Map<String, Object>> listDeviceConnections(@Param("number") String number, @Param("from") long from);

    @org.apache.ibatis.annotations.Delete({"DELETE FROM device_metric WHERE ts < #{before}"})
    int purgeMetrics(@Param("before") long before);

    /** Everything the device export needs, one row per device (telemetry is the agent's last census JSON). */
    @Select({"SELECT d.id, d.number, d.description, d.enrollTime, d.lastUpdate, d.configurationId, c.name AS configurationName, " +
            "m.groupId, s.battery, s.charging, s.kioskActive, s.agentVersion, s.powerMode, s.androidRelease, s.telemetry, " +
            "s.updatedAt AS stateAt " +
            "FROM devices d LEFT JOIN configurations c ON c.id = d.configurationId " +
            "LEFT JOIN LATERAL (SELECT dg.groupId FROM deviceGroups dg WHERE dg.deviceId = d.id ORDER BY dg.id LIMIT 1) m ON true " +
            "LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} ORDER BY lower(coalesce(d.description, d.number))"})
    List<java.util.Map<String, Object>> listDeviceExportRows(@Param("customerId") int customerId);

    /** Name a device only while it has none (an operator's name is never overwritten). */
    @Update({"UPDATE devices SET description = #{name} WHERE number = #{number} AND (description IS NULL OR trim(description) = '')"})
    int nameIfUnnamed(@Param("number") String number, @Param("name") String name);

    // --- Groups (companies) and configuration scopes: device > group > global ---

    /**
     * A device's group is its first deviceGroups row (DallyControl keeps exactly one per device). Groups nest: the
     * group's configuration is its own or, when it has none, its nearest ancestor's
     * ({@code dallycontrol_group_configuration}, Liquibase 26.09.28-audio-parity).
     */
    String DEVICE_SCOPE_SELECT = "SELECT d.id, d.number, d.configurationId, d.configurationPinned, " +
            "g.id AS groupId, g.name AS groupName, dallycontrol_group_configuration(g.id) AS groupConfigurationId " +
            "FROM devices d LEFT JOIN LATERAL (SELECT dg.groupId FROM deviceGroups dg WHERE dg.deviceId = d.id " +
            "ORDER BY dg.id LIMIT 1) m ON true LEFT JOIN groups g ON g.id = m.groupId ";

    /** A device's folder chain branding (groups.brand), nearest folder first; folders without branding are skipped. */
    @Select({"WITH RECURSIVE up(id, parentId, brand, depth) AS (" +
            "SELECT g.id, g.parentId, g.brand, 0 FROM groups g JOIN deviceGroups dg ON dg.groupId = g.id WHERE dg.deviceId = #{deviceId} " +
            "UNION ALL SELECT g.id, g.parentId, g.brand, up.depth + 1 FROM groups g JOIN up ON g.id = up.parentId WHERE up.depth < 32) " +
            "SELECT brand FROM up WHERE brand IS NOT NULL ORDER BY depth"})
    List<String> listDeviceFolderBrands(@Param("deviceId") int deviceId);

    @Update({"UPDATE groups SET brand = #{brand} WHERE id = #{id} AND customerId = #{customerId}"})
    int updateGroupBrand(@Param("customerId") int customerId, @Param("id") int id, @Param("brand") String brand);

    String GROUP_VIEW_SELECT = "SELECT g.id, g.name, g.parentId, g.configurationId, g.brand, c.name AS configurationName, " +
            "e.id AS effectiveConfigurationId, e.name AS effectiveConfigurationName, " +
            "(SELECT count(*) FROM deviceGroups dg JOIN devices d ON d.id = dg.deviceId WHERE dg.groupId = g.id) AS deviceCount " +
            "FROM groups g LEFT JOIN configurations c ON c.id = g.configurationId " +
            "LEFT JOIN configurations e ON e.id = dallycontrol_group_configuration(g.id) ";

    @Select({GROUP_VIEW_SELECT + "WHERE g.customerId = #{customerId} ORDER BY lower(g.name)"})
    List<com.hmdm.persistence.domain.DeviceGroupView> listGroups(@Param("customerId") int customerId);

    @Select({GROUP_VIEW_SELECT + "WHERE g.customerId = #{customerId} AND g.id = #{groupId}"})
    com.hmdm.persistence.domain.DeviceGroupView findGroup(@Param("customerId") int customerId, @Param("groupId") int groupId);

    /** Sibling names are unique (case-insensitive); the same name may repeat under different parents. */
    @Select({"SELECT count(*) FROM groups WHERE customerId = #{customerId} AND lower(name) = lower(#{name}) " +
            "AND parentId IS NOT DISTINCT FROM #{parentId}::int " +
            "AND (#{exceptId}::int IS NULL OR id <> #{exceptId}::int)"})
    int countGroupsNamed(@Param("customerId") int customerId, @Param("name") String name,
                         @Param("parentId") Integer parentId, @Param("exceptId") Integer exceptId);

    /** The group and every group below it. */
    @Select({"WITH RECURSIVE down(id, depth) AS (SELECT id, 0 FROM groups WHERE id = #{groupId} AND customerId = #{customerId} " +
            "UNION ALL SELECT g.id, down.depth + 1 FROM groups g JOIN down ON g.parentId = down.id WHERE down.depth < 32) " +
            "SELECT id FROM down"})
    List<Integer> listGroupSubtree(@Param("customerId") int customerId, @Param("groupId") int groupId);

    @Insert({"INSERT INTO groups (name, customerId, configurationId, parentId) VALUES (#{name}, #{customerId}, #{configurationId}, #{parentId})"})
    @SelectKey(statement = "SELECT currval('groups_id_seq')", keyColumn = "id", keyProperty = "id", before = false, resultType = int.class)
    void insertGroup(com.hmdm.persistence.domain.DeviceGroupInsert group);

    @Update({"UPDATE groups SET name = #{name}, configurationId = #{configurationId}, parentId = #{parentId} " +
            "WHERE id = #{id} AND customerId = #{customerId}"})
    int updateGroup(@Param("customerId") int customerId, @Param("id") int id, @Param("name") String name,
                    @Param("configurationId") Integer configurationId, @Param("parentId") Integer parentId);

    @Update({"UPDATE groups SET parentId = #{parentId} WHERE parentId = #{id} AND customerId = #{customerId}"})
    int reparentChildren(@Param("customerId") int customerId, @Param("id") int id, @Param("parentId") Integer parentId);

    @org.apache.ibatis.annotations.Delete({"DELETE FROM groups WHERE id = #{id} AND customerId = #{customerId}"})
    int deleteGroup(@Param("customerId") int customerId, @Param("id") int id);

    @org.apache.ibatis.annotations.Delete({"DELETE FROM deviceGroups WHERE deviceId = #{deviceId}"})
    void clearDeviceGroups(@Param("deviceId") int deviceId);

    @Insert({"INSERT INTO deviceGroups (deviceId, groupId) VALUES (#{deviceId}, #{groupId})"})
    void addDeviceGroup(@Param("deviceId") int deviceId, @Param("groupId") int groupId);

    @Select({DEVICE_SCOPE_SELECT + "WHERE d.customerId = #{customerId} ORDER BY d.id"})
    List<com.hmdm.persistence.domain.DeviceScopeRow> listDeviceScopes(@Param("customerId") int customerId);

    @Select({DEVICE_SCOPE_SELECT + "WHERE d.customerId = #{customerId} AND d.id = #{deviceId}"})
    com.hmdm.persistence.domain.DeviceScopeRow findDeviceScope(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    @Update({"UPDATE devices SET configurationId = #{configurationId} WHERE id = #{deviceId}"})
    void updateDeviceConfiguration(@Param("deviceId") int deviceId, @Param("configurationId") int configurationId);

    @Update({"UPDATE devices SET configurationPinned = #{pinned} WHERE id = #{deviceId}"})
    void updateDevicePinned(@Param("deviceId") int deviceId, @Param("pinned") boolean pinned);

    @Select({"SELECT newDeviceConfigurationId FROM settings WHERE customerId = #{customerId}"})
    Integer getGlobalConfigurationId(@Param("customerId") int customerId);

    @Update({"UPDATE settings SET newDeviceConfigurationId = #{configurationId} WHERE customerId = #{customerId}"})
    void updateGlobalConfigurationId(@Param("customerId") int customerId, @Param("configurationId") int configurationId);

    // --- App versions: configurations follow an app's latest version ---

    @Update({"UPDATE configurationApplications SET applicationVersionId = #{newVersionId} " +
            "WHERE applicationId = #{appId} AND applicationVersionId IS DISTINCT FROM #{newVersionId}"})
    int relinkConfigurationApps(@Param("appId") int appId, @Param("newVersionId") int newVersionId);

    @Update({"UPDATE configurations SET mainAppId = #{newVersionId} WHERE mainAppId <> #{newVersionId} AND mainAppId IN " +
            "(SELECT id FROM applicationVersions WHERE applicationId = #{appId})"})
    int relinkConfigurationMainApps(@Param("appId") int appId, @Param("newVersionId") int newVersionId);

    @Select({"SELECT DISTINCT configurationId FROM configurationApplications WHERE applicationId = #{appId} " +
            "UNION SELECT c.id FROM configurations c JOIN applicationVersions v ON v.id = c.mainAppId WHERE v.applicationId = #{appId}"})
    List<Integer> listConfigurationsUsingApp(@Param("appId") int appId);

    @Select({"SELECT number FROM devices WHERE configurationId = #{configurationId}"})
    List<String> listDeviceNumbersByConfigurationId(@Param("configurationId") int configurationId);

    /** One row per device of the customer with its configuration + last applied revision (LEFT JOIN: never-reported devices included). */
    @Select({"SELECT d.number AS deviceNumber, d.configurationId AS configurationId, d.agentCapabilities AS capabilitiesJson, " +
            "s.appliedConfigRevision AS appliedConfigRevision " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} AND d.configurationId IS NOT NULL"})
    List<DeviceSyncRow> listDevicesForSync(@Param("customerId") int customerId);
}
