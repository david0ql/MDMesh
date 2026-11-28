package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DeviceState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for the latest agent-reported device snapshot ({@link DeviceState}).
 * One row per device number; {@link #upsert} replaces the prior snapshot.
 */
public interface DeviceStateMapper {

    @Insert({"INSERT INTO device_state " +
            "(deviceNumber, battery, charging, locked, kioskActive, androidRelease, lastBootAt, updatedAt, agentVersion, powerMode, telemetry, appliedConfigRevision, appliedConfigAt) " +
            "VALUES (#{deviceNumber}, #{battery}, #{charging}, #{locked}, #{kioskActive}, " +
            "#{androidRelease}, #{lastBootAt}, #{updatedAt}, #{agentVersion}, #{powerMode}, #{telemetry}, #{appliedConfigRevision}, #{appliedConfigAt}) " +
            "ON CONFLICT (deviceNumber) DO UPDATE SET " +
            "battery = EXCLUDED.battery, charging = EXCLUDED.charging, locked = EXCLUDED.locked, " +
            "kioskActive = EXCLUDED.kioskActive, androidRelease = EXCLUDED.androidRelease, " +
            "lastBootAt = EXCLUDED.lastBootAt, updatedAt = EXCLUDED.updatedAt, " +
            "agentVersion = EXCLUDED.agentVersion, powerMode = EXCLUDED.powerMode, " +
            // A follow-up round (it only reports what the previous round ran) carries no census: keep the last one,
            // or every screen that reads it (hardware, security, reset protection) goes blank until the next full report.
            "telemetry = COALESCE(EXCLUDED.telemetry, device_state.telemetry), " +
            // Keep the last known applied revision when a check-in omits it (state without config info).
            "appliedConfigRevision = COALESCE(EXCLUDED.appliedConfigRevision, device_state.appliedConfigRevision), " +
            "appliedConfigAt = CASE WHEN EXCLUDED.appliedConfigRevision IS NULL THEN device_state.appliedConfigAt " +
            "                       WHEN EXCLUDED.appliedConfigRevision IS DISTINCT FROM device_state.appliedConfigRevision THEN EXCLUDED.updatedAt " +
            "                       ELSE COALESCE(device_state.appliedConfigAt, EXCLUDED.updatedAt) END"})
    void upsert(DeviceState state);

    @Select({"SELECT deviceNumber, battery, charging, locked, kioskActive, androidRelease, lastBootAt, updatedAt, agentVersion, powerMode, telemetry, appliedConfigRevision, appliedConfigAt " +
            "FROM device_state WHERE deviceNumber = #{deviceNumber}"})
    DeviceState findByDeviceNumber(@Param("deviceNumber") String deviceNumber);

    // --- Fleet dashboard aggregates (scoped by customer through the devices table) ---

    /** How many of the customer's devices are currently reported as locked (blocked). */
    @Select({"SELECT COUNT(*) FROM device_state s JOIN devices d ON d.number = s.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND s.locked = TRUE"})
    long countLocked(@Param("customerId") int customerId);

    /** Android release distribution across the fleet (one row per reported release, descending by count). */
    @Select({"SELECT COALESCE(s.androidRelease, '') AS label, COUNT(*) AS counter " +
            "FROM device_state s JOIN devices d ON d.number = s.deviceNumber " +
            "WHERE d.customerId = #{customerId} " +
            "GROUP BY COALESCE(s.androidRelease, '') ORDER BY counter DESC"})
    List<Map<String, Object>> countByAndroidRelease(@Param("customerId") int customerId);

    /** Last-census telemetry JSON for every device of the customer; parsed for the fleet storage total. */
    @Select({"SELECT s.telemetry AS telemetry FROM device_state s JOIN devices d ON d.number = s.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND s.telemetry IS NOT NULL"})
    List<Map<String, Object>> telemetryRows(@Param("customerId") int customerId);
}
