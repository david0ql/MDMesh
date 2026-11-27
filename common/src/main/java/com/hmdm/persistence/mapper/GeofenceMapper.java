/*
 *
 * DallyControl — Geocercas (new console module, not part of upstream Headwind).
 *
 * MyBatis mapper for geofences, their assignments (folders with inheritance, devices), the per-device state
 * machine, the per-customer thresholds, the alert e-mail recipients/cursors, and the paginated location history.
 * Postgres folds unquoted identifiers to lower case, so Map results come back with lower-case keys.
 * Auto-registered by package scan (com.hmdm.persistence.mapper).
 *
 */

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.Geofence;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface GeofenceMapper {

    // --- zones ---------------------------------------------------------------------------------------------------

    @Select({"SELECT * FROM geofence WHERE customerId = #{customerId} ORDER BY lower(name), id"})
    List<Geofence> listZones(@Param("customerId") int customerId);

    @Select({"SELECT * FROM geofence WHERE customerId = #{customerId} AND id = #{id}"})
    Geofence findZone(@Param("customerId") int customerId, @Param("id") int id);

    @Insert({"INSERT INTO geofence (customerId, name, color, shape, centerLat, centerLon, radiusM, polygon, rule, active, " +
            "alertOnReturn, allDevices, schedule, emails, trackingMinutes, createdAt, updatedAt) VALUES (#{customerId}, #{name}, " +
            "#{color}, #{shape}, #{centerLat}, #{centerLon}, #{radiusM}, #{polygon}, #{rule}, #{active}, #{alertOnReturn}, " +
            "#{allDevices}, #{schedule}, #{emails}, #{trackingMinutes}, #{createdAt}, #{updatedAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertZone(Geofence zone);

    @Update({"UPDATE geofence SET name = #{name}, color = #{color}, shape = #{shape}, centerLat = #{centerLat}, " +
            "centerLon = #{centerLon}, radiusM = #{radiusM}, polygon = #{polygon}, rule = #{rule}, active = #{active}, " +
            "alertOnReturn = #{alertOnReturn}, allDevices = #{allDevices}, schedule = #{schedule}, emails = #{emails}, " +
            "trackingMinutes = #{trackingMinutes}, updatedAt = #{updatedAt} WHERE id = #{id} AND customerId = #{customerId}"})
    int updateZone(Geofence zone);

    @Delete({"DELETE FROM geofence WHERE id = #{id} AND customerId = #{customerId}"})
    int deleteZone(@Param("customerId") int customerId, @Param("id") int id);

    @Select({"SELECT gg.geofenceId AS zoneid, gg.groupId AS groupid FROM geofence_group gg " +
            "JOIN geofence z ON z.id = gg.geofenceId WHERE z.customerId = #{customerId}"})
    List<Map<String, Object>> listZoneGroups(@Param("customerId") int customerId);

    @Select({"SELECT gd.geofenceId AS zoneid, gd.deviceId AS deviceid, d.number, d.description FROM geofence_device gd " +
            "JOIN geofence z ON z.id = gd.geofenceId JOIN devices d ON d.id = gd.deviceId WHERE z.customerId = #{customerId}"})
    List<Map<String, Object>> listZoneDevices(@Param("customerId") int customerId);

    @Delete({"DELETE FROM geofence_group WHERE geofenceId = #{id}"})
    void clearZoneGroups(@Param("id") int id);

    @Delete({"DELETE FROM geofence_device WHERE geofenceId = #{id}"})
    void clearZoneDevices(@Param("id") int id);

    /** Only folders of the same customer can be linked. */
    @Insert({"INSERT INTO geofence_group (geofenceId, groupId) SELECT #{id}, g.id FROM groups g " +
            "WHERE g.id = #{groupId} AND g.customerId = #{customerId} ON CONFLICT DO NOTHING"})
    void addZoneGroup(@Param("customerId") int customerId, @Param("id") int id, @Param("groupId") int groupId);

    /** Only devices of the same customer can be linked. */
    @Insert({"INSERT INTO geofence_device (geofenceId, deviceId) SELECT #{id}, d.id FROM devices d " +
            "WHERE d.id = #{deviceId} AND d.customerId = #{customerId} ON CONFLICT DO NOTHING"})
    void addZoneDevice(@Param("customerId") int customerId, @Param("id") int id, @Param("deviceId") int deviceId);

    /**
     * The active zones that apply to a device: assigned to it, to its folder or to any folder above it (inheritance,
     * like policies), or to the whole fleet.
     */
    @Select({"WITH RECURSIVE dev AS (SELECT id, customerId FROM devices WHERE number = #{number}), " +
            "up(id, parentId, depth) AS (" +
            "  SELECT g.id, g.parentId, 0 FROM groups g JOIN deviceGroups dg ON dg.groupId = g.id JOIN dev ON dev.id = dg.deviceId " +
            "  UNION ALL SELECT g.id, g.parentId, up.depth + 1 FROM groups g JOIN up ON g.id = up.parentId WHERE up.depth < 32) " +
            "SELECT z.* FROM geofence z JOIN dev ON dev.customerId = z.customerId WHERE z.active AND (z.allDevices " +
            "  OR EXISTS (SELECT 1 FROM geofence_group gg WHERE gg.geofenceId = z.id AND gg.groupId IN (SELECT id FROM up)) " +
            "  OR EXISTS (SELECT 1 FROM geofence_device gd WHERE gd.geofenceId = z.id AND gd.deviceId = dev.id)) " +
            "ORDER BY z.id"})
    List<Geofence> zonesForDevice(@Param("number") String number);

    // --- state machine -------------------------------------------------------------------------------------------

    @Select({"SELECT geofenceId AS zoneid, state, since, pending, pendingCount AS pendingcount, pendingSince AS pendingsince, " +
            "alerted, lastFixAt AS lastfixat FROM geofence_state WHERE deviceNumber = #{number}"})
    List<Map<String, Object>> statesOfDevice(@Param("number") String number);

    @Insert({"INSERT INTO geofence_state (geofenceId, deviceNumber, state, since, pending, pendingCount, pendingSince, alerted, lastFixAt) " +
            "VALUES (#{zoneId}, #{number}, #{state}, #{since}, #{pending}, #{pendingCount}, #{pendingSince}, #{alerted}, #{lastFixAt}) " +
            "ON CONFLICT (geofenceId, deviceNumber) DO UPDATE SET state = EXCLUDED.state, since = EXCLUDED.since, " +
            "pending = EXCLUDED.pending, pendingCount = EXCLUDED.pendingCount, pendingSince = EXCLUDED.pendingSince, " +
            "alerted = EXCLUDED.alerted, lastFixAt = EXCLUDED.lastFixAt"})
    void upsertState(@Param("zoneId") int zoneId, @Param("number") String number, @Param("state") String state,
                     @Param("since") long since, @Param("pending") String pending, @Param("pendingCount") int pendingCount,
                     @Param("pendingSince") long pendingSince, @Param("alerted") boolean alerted, @Param("lastFixAt") long lastFixAt);

    /** A zone whose shape or rule changed starts again from a fresh baseline. */
    @Delete({"DELETE FROM geofence_state WHERE geofenceId = #{zoneId}"})
    void clearStates(@Param("zoneId") int zoneId);

    /** Inside/outside of every device with a state, for the customer's zones (the console's indicator). */
    @Select({"SELECT s.geofenceId AS zoneid, s.deviceNumber AS number, d.description, s.state, s.since, s.lastFixAt AS lastfixat " +
            "FROM geofence_state s JOIN geofence z ON z.id = s.geofenceId JOIN devices d ON d.number = s.deviceNumber " +
            "WHERE z.customerId = #{customerId} AND d.customerId = #{customerId} AND s.state <> 'unknown' ORDER BY s.geofenceId, lower(d.description), d.number"})
    List<Map<String, Object>> listStates(@Param("customerId") int customerId);

    @Delete({"DELETE FROM geofence_state s WHERE NOT EXISTS (SELECT 1 FROM devices d WHERE d.number = s.deviceNumber)"})
    int purgeOrphanStates();

    // --- alerts --------------------------------------------------------------------------------------------------

    /** One geofence alert; 0 rows when (device, type, time) is already taken (the caller nudges the time). */
    @Insert({"INSERT INTO device_alert (customerId, deviceNumber, type, detail, createdAt, reviewed, zoneId) " +
            "VALUES (#{customerId}, #{number}, #{type}, #{detail}, #{createdAt}, FALSE, #{zoneId}) " +
            "ON CONFLICT (deviceNumber, type, createdAt) DO NOTHING"})
    int insertAlert(@Param("customerId") int customerId, @Param("number") String number, @Param("type") String type,
                    @Param("detail") String detail, @Param("createdAt") long createdAt, @Param("zoneId") int zoneId);

    @Select({"SELECT COALESCE(MAX(id), 0) FROM device_alert WHERE customerId = #{customerId}"})
    long maxAlertId(@Param("customerId") int customerId);

    /** Alerts newer than an id and not older than a time, with what the e-mail needs (device, folder, zone). */
    @Select({"SELECT a.id, a.deviceNumber AS number, d.description, ", ReportsMapper.GROUP_ID_SUBQUERY, ", ",
            "a.type, a.detail, a.createdAt AS createdat, a.zoneId AS zoneid, z.name AS zonename, z.emails AS zoneemails ",
            "FROM device_alert a JOIN devices d ON d.number = a.deviceNumber LEFT JOIN geofence z ON z.id = a.zoneId ",
            "WHERE a.customerId = #{customerId} AND a.id > #{afterId} AND a.createdAt >= #{since} AND NOT a.reviewed ",
            "ORDER BY a.id LIMIT #{limit}"})
    List<Map<String, Object>> alertsAfter(@Param("customerId") int customerId, @Param("afterId") long afterId,
                                          @Param("since") long since, @Param("limit") int limit);

    // --- settings ------------------------------------------------------------------------------------------------

    @Select({"SELECT maxAccuracyM AS maxaccuracym, hysteresisM AS hysteresism, confirmFixes AS confirmfixes, " +
            "retentionDays AS retentiondays, emailIntervalMin AS emailintervalmin, offlineMinutes AS offlineminutes FROM geofence_settings WHERE customerId = #{customerId}"})
    Map<String, Object> getSettings(@Param("customerId") int customerId);

    @Insert({"INSERT INTO geofence_settings (customerId, maxAccuracyM, hysteresisM, confirmFixes, retentionDays, emailIntervalMin, offlineMinutes) " +
            "VALUES (#{customerId}, #{maxAccuracyM}, #{hysteresisM}, #{confirmFixes}, #{retentionDays}, #{emailIntervalMin}, #{offlineMinutes}) " +
            "ON CONFLICT (customerId) DO UPDATE SET maxAccuracyM = EXCLUDED.maxAccuracyM, hysteresisM = EXCLUDED.hysteresisM, " +
            "confirmFixes = EXCLUDED.confirmFixes, retentionDays = EXCLUDED.retentionDays, emailIntervalMin = EXCLUDED.emailIntervalMin, " +
            "offlineMinutes = EXCLUDED.offlineMinutes"})
    void upsertSettings(@Param("customerId") int customerId, @Param("maxAccuracyM") int maxAccuracyM,
                        @Param("hysteresisM") int hysteresisM, @Param("confirmFixes") int confirmFixes,
                        @Param("retentionDays") int retentionDays, @Param("emailIntervalMin") int emailIntervalMin,
                        @Param("offlineMinutes") int offlineMinutes);

    @Select({"SELECT customerId AS customerid, retentionDays AS retentiondays FROM geofence_settings WHERE retentionDays > 0"})
    List<Map<String, Object>> listRetention();

    @Delete({"DELETE FROM device_location l USING devices d WHERE d.number = l.deviceNumber " +
            "AND d.customerId = #{customerId} AND l.capturedAt < #{before}"})
    int purgeLocations(@Param("customerId") int customerId, @Param("before") long before);

    // --- e-mail recipients ---------------------------------------------------------------------------------------

    @Select({"SELECT r.id, r.groupId AS groupid, r.emails, r.types, r.active FROM alert_recipient r " +
            "WHERE r.customerId = #{customerId} ORDER BY r.id"})
    List<Map<String, Object>> listRecipients(@Param("customerId") int customerId);

    @Select({"SELECT r.id, r.groupId AS groupid, r.emails, r.types, r.active FROM alert_recipient r " +
            "WHERE r.customerId = #{customerId} AND r.id = #{id}"})
    Map<String, Object> findRecipient(@Param("customerId") int customerId, @Param("id") int id);

    @Insert({"INSERT INTO alert_recipient (customerId, groupId, emails, types, active) " +
            "SELECT #{customerId}, #{groupId}::int, #{emails}, #{types}, #{active} " +
            "WHERE #{groupId}::int IS NULL OR EXISTS (SELECT 1 FROM groups g WHERE g.id = #{groupId}::int AND g.customerId = #{customerId})"})
    int insertRecipient(@Param("customerId") int customerId, @Param("groupId") Integer groupId,
                        @Param("emails") String emails, @Param("types") String types, @Param("active") boolean active);

    @Update({"UPDATE alert_recipient SET emails = #{emails}, types = #{types}, active = #{active} " +
            "WHERE id = #{id} AND customerId = #{customerId}"})
    int updateRecipient(@Param("customerId") int customerId, @Param("id") int id, @Param("emails") String emails,
                        @Param("types") String types, @Param("active") boolean active);

    @Delete({"DELETE FROM alert_recipient WHERE id = #{id} AND customerId = #{customerId}"})
    int deleteRecipient(@Param("customerId") int customerId, @Param("id") int id);

    /** Customers with someone to notify: an active recipient rule, or an active zone with its own addresses. */
    @Select({"SELECT DISTINCT customerId FROM alert_recipient WHERE active " +
            "UNION SELECT DISTINCT customerId FROM geofence WHERE active AND emails IS NOT NULL AND emails <> ''"})
    List<Integer> customersToNotify();

    @Select({"SELECT email, lastSentAt AS lastsentat, lastAlertId AS lastalertid FROM alert_email_state WHERE customerId = #{customerId}"})
    List<Map<String, Object>> listEmailStates(@Param("customerId") int customerId);

    @Insert({"INSERT INTO alert_email_state (customerId, email, lastSentAt, lastAlertId) VALUES (#{customerId}, #{email}, #{lastSentAt}, #{lastAlertId}) " +
            "ON CONFLICT (customerId, email) DO UPDATE SET lastSentAt = EXCLUDED.lastSentAt, lastAlertId = EXCLUDED.lastAlertId"})
    void upsertEmailState(@Param("customerId") int customerId, @Param("email") String email,
                          @Param("lastSentAt") long lastSentAt, @Param("lastAlertId") long lastAlertId);

    // --- location history (paginated) ----------------------------------------------------------------------------

    @Select({"SELECT count(*) FROM device_location WHERE deviceNumber = #{number} AND capturedAt BETWEEN #{from} AND #{to}"})
    long countTrack(@Param("number") String number, @Param("from") long from, @Param("to") long to);

    @Select({"SELECT * FROM device_location WHERE deviceNumber = #{number} AND capturedAt BETWEEN #{from} AND #{to} " +
            "ORDER BY capturedAt, id LIMIT #{limit} OFFSET #{offset}"})
    List<DeviceLocation> listTrack(@Param("number") String number, @Param("from") long from, @Param("to") long to,
                                   @Param("limit") int limit, @Param("offset") long offset);

    @Select({"SELECT * FROM device_location WHERE deviceNumber = #{number} AND capturedAt BETWEEN #{from} AND #{to} " +
            "ORDER BY capturedAt DESC, id DESC LIMIT #{limit} OFFSET #{offset}"})
    List<DeviceLocation> listTrackDesc(@Param("number") String number, @Param("from") long from, @Param("to") long to,
                                       @Param("limit") int limit, @Param("offset") long offset);
}
