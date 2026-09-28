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

    // --- Groups (companies) and configuration scopes: device > group > global ---

    /** A device's group is its first deviceGroups row (DallyControl keeps exactly one per device). */
    String DEVICE_SCOPE_SELECT = "SELECT d.id, d.number, d.configurationId, d.configurationPinned, " +
            "g.id AS groupId, g.name AS groupName, g.configurationId AS groupConfigurationId " +
            "FROM devices d LEFT JOIN LATERAL (SELECT dg.groupId FROM deviceGroups dg WHERE dg.deviceId = d.id " +
            "ORDER BY dg.id LIMIT 1) m ON true LEFT JOIN groups g ON g.id = m.groupId ";

    @Select({"SELECT g.id, g.name, g.configurationId, c.name AS configurationName, " +
            "(SELECT count(*) FROM deviceGroups dg JOIN devices d ON d.id = dg.deviceId WHERE dg.groupId = g.id) AS deviceCount " +
            "FROM groups g LEFT JOIN configurations c ON c.id = g.configurationId " +
            "WHERE g.customerId = #{customerId} ORDER BY lower(g.name)"})
    List<com.hmdm.persistence.domain.DeviceGroupView> listGroups(@Param("customerId") int customerId);

    @Select({"SELECT g.id, g.name, g.configurationId, c.name AS configurationName, " +
            "(SELECT count(*) FROM deviceGroups dg JOIN devices d ON d.id = dg.deviceId WHERE dg.groupId = g.id) AS deviceCount " +
            "FROM groups g LEFT JOIN configurations c ON c.id = g.configurationId " +
            "WHERE g.customerId = #{customerId} AND g.id = #{groupId}"})
    com.hmdm.persistence.domain.DeviceGroupView findGroup(@Param("customerId") int customerId, @Param("groupId") int groupId);

    @Select({"SELECT count(*) FROM groups WHERE customerId = #{customerId} AND lower(name) = lower(#{name}) " +
            "AND (#{exceptId}::int IS NULL OR id <> #{exceptId}::int)"})
    int countGroupsNamed(@Param("customerId") int customerId, @Param("name") String name, @Param("exceptId") Integer exceptId);

    @Insert({"INSERT INTO groups (name, customerId, configurationId) VALUES (#{name}, #{customerId}, #{configurationId})"})
    @SelectKey(statement = "SELECT currval('groups_id_seq')", keyColumn = "id", keyProperty = "id", before = false, resultType = int.class)
    void insertGroup(com.hmdm.persistence.domain.DeviceGroupInsert group);

    @Update({"UPDATE groups SET name = #{name}, configurationId = #{configurationId} WHERE id = #{id} AND customerId = #{customerId}"})
    int updateGroup(@Param("customerId") int customerId, @Param("id") int id, @Param("name") String name,
                    @Param("configurationId") Integer configurationId);

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
