/*
 *
 * DallyControl — mobile data per app (new module, not part of upstream Headwind).
 *
 * A device's own override of its policy's mobile-data section (device_mobile_data) and the consumption of the
 * limits' period, read from the per-app usage the agent already reports (device_app_usage, one row per device,
 * local day and app) and from the agent's own figure in its last state report (device_state.telemetry).
 *
 * Auto-registered by package scan ({@code com.hmdm.persistence.mapper}); no manual Guice wiring needed. Postgres
 * folds unquoted identifiers to lower case, so the result-map keys come back lower case.
 *
 */

package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** MyBatis mapper for the mobile-data module. */
public interface MobileDataMapper {

    @Select("SELECT settings FROM device_mobile_data WHERE deviceId = #{deviceId}")
    String getOverride(@Param("deviceId") int deviceId);

    @Insert({"INSERT INTO device_mobile_data (deviceId, settings, updatedAt) VALUES (#{deviceId}, #{settings}, #{now}) ",
            "ON CONFLICT (deviceId) DO UPDATE SET settings = EXCLUDED.settings, updatedAt = EXCLUDED.updatedAt"})
    void setOverride(@Param("deviceId") int deviceId, @Param("settings") String settings, @Param("now") long now);

    @Delete("DELETE FROM device_mobile_data WHERE deviceId = #{deviceId}")
    void clearOverride(@Param("deviceId") int deviceId);

    /** Mobile bytes per app of one device since a local day (inclusive). */
    @Select({"SELECT u.pkg, MAX(u.label) AS label, SUM(u.mobileBytes) AS bytes ",
            "FROM device_app_usage u WHERE u.deviceNumber = #{number} AND u.day >= #{fromDay} ",
            "GROUP BY u.pkg HAVING SUM(u.mobileBytes) > 0 ORDER BY SUM(u.mobileBytes) DESC"})
    List<Map<String, Object>> usageByApp(@Param("number") String number, @Param("fromDay") String fromDay);

    /** The same for every device of a configuration (one period for all of them): rows of number, pkg, label, bytes. */
    @Select({"SELECT u.deviceNumber AS number, u.pkg, MAX(u.label) AS label, SUM(u.mobileBytes) AS bytes ",
            "FROM device_app_usage u JOIN devices d ON d.number = u.deviceNumber ",
            "WHERE d.customerId = #{customerId} AND d.configurationId = #{configurationId} AND u.day >= #{fromDay} ",
            "GROUP BY u.deviceNumber, u.pkg HAVING SUM(u.mobileBytes) > 0"})
    List<Map<String, Object>> usageOfConfiguration(@Param("customerId") int customerId,
                                                   @Param("configurationId") int configurationId,
                                                   @Param("fromDay") String fromDay);

    /**
     * Every device whose mobile data is managed: its policy has the section, or the device has its own override.
     * Carries the policy JSON, the override and the agent's last reported mobile-data state (JSON text, or null).
     */
    @Select({"SELECT d.id, d.number, d.description, ", ReportsMapper.GROUP_ID_SUBQUERY, ", ",
            "d.configurationId AS configurationid, c.name AS configurationname, c.dcPolicy AS dcpolicy, ",
            "m.settings AS override, s.updatedAt AS stateat, ",
            "CASE WHEN s.telemetry LIKE '{%' THEN (s.telemetry::jsonb #>> '{dynamic,mobileData}') END AS status ",
            "FROM devices d LEFT JOIN configurations c ON c.id = d.configurationId ",
            "LEFT JOIN device_mobile_data m ON m.deviceId = d.id ",
            "LEFT JOIN device_state s ON s.deviceNumber = d.number ",
            "WHERE d.customerId = #{customerId} AND (c.dcPolicy LIKE '%\"mobileData\"%' OR m.deviceId IS NOT NULL) ",
            "ORDER BY lower(coalesce(d.description, d.number))"})
    List<Map<String, Object>> managedDevices(@Param("customerId") int customerId);
}
