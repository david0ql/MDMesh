package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Factory-reset protection and remote wipe: the default company accounts (per company and per folder), finding the
 * row of a phone that enrolls again after a reset by its serial / IMEI, and the "wiped" mark set by the agent's own
 * announcement.
 */
public interface ProtectionMapper {

    // --- Default accounts: groupId 0 = the company, otherwise a folder -------------------------------------------

    @Select({"SELECT accounts FROM frp_default WHERE customerId = #{customerId} AND groupId = #{groupId}"})
    String findDefault(@Param("customerId") int customerId, @Param("groupId") int groupId);

    @Select({"SELECT f.groupId AS groupid, f.accounts, f.updatedAt AS updatedat, f.updatedBy AS updatedby " +
            "FROM frp_default f WHERE f.customerId = #{customerId}"})
    List<Map<String, Object>> listDefaults(@Param("customerId") int customerId);

    @Insert({"INSERT INTO frp_default (customerId, groupId, accounts, updatedAt, updatedBy) " +
            "VALUES (#{customerId}, #{groupId}, #{accounts}, #{now}, #{by}) " +
            "ON CONFLICT (customerId, groupId) DO UPDATE SET accounts = EXCLUDED.accounts, " +
            "updatedAt = EXCLUDED.updatedAt, updatedBy = EXCLUDED.updatedBy"})
    void saveDefault(@Param("customerId") int customerId, @Param("groupId") int groupId,
                     @Param("accounts") String accounts, @Param("now") long now, @Param("by") String by);

    @Delete({"DELETE FROM frp_default WHERE customerId = #{customerId} AND groupId = #{groupId}"})
    int deleteDefault(@Param("customerId") int customerId, @Param("groupId") int groupId);

    /** The accounts of the folders above a device, nearest folder first; folders without accounts are skipped. */
    @Select({"WITH RECURSIVE up(id, parentId, name, depth) AS (" +
            "SELECT g.id, g.parentId, g.name, 0 FROM groups g JOIN deviceGroups dg ON dg.groupId = g.id WHERE dg.deviceId = #{deviceId} " +
            "UNION ALL SELECT g.id, g.parentId, g.name, up.depth + 1 FROM groups g JOIN up ON g.id = up.parentId WHERE up.depth < 32) " +
            "SELECT up.id AS groupid, up.name AS groupname, f.accounts FROM up " +
            "JOIN frp_default f ON f.groupId = up.id AND f.customerId = #{customerId} " +
            "WHERE f.accounts IS NOT NULL ORDER BY up.depth, up.id"})
    List<Map<String, Object>> listDeviceFolderDefaults(@Param("customerId") int customerId, @Param("deviceId") int deviceId);

    @Select({"SELECT COUNT(*) FROM groups WHERE id = #{groupId} AND customerId = #{customerId}"})
    int countGroup(@Param("customerId") int customerId, @Param("groupId") int groupId);

    // --- A phone that enrolls again after a factory reset --------------------------------------------------------

    /** The customer's device that carries this IMEI or serial (IMEI first); either may be null. */
    @Select({"SELECT number, lastUpdate AS lastupdate, wipedAt AS wipedat FROM devices WHERE customerId = #{customerId} " +
            "AND ((#{imei}::text IS NOT NULL AND imei = #{imei}::text) " +
            "OR (#{serial}::text IS NOT NULL AND infojson->>'serial' = #{serial}::text)) " +
            "ORDER BY (CASE WHEN #{imei}::text IS NOT NULL AND imei = #{imei}::text THEN 0 ELSE 1 END), " +
            "lastUpdate DESC NULLS LAST LIMIT 1"})
    Map<String, Object> findDeviceByIdentity(@Param("customerId") int customerId, @Param("serial") String serial,
                                             @Param("imei") String imei);

    /** The deleted device (kept in device_tombstone) that carried this IMEI or serial. */
    @Select({"SELECT number, customerId AS customerid, description, groupId AS groupid, configurationId AS configurationid, " +
            "pinned, hardwareId AS hardwareid FROM device_tombstone WHERE customerId = #{customerId} AND deletedAt >= #{since} " +
            "AND ((#{imei}::text IS NOT NULL AND imei = #{imei}::text) OR (#{serial}::text IS NOT NULL AND serial = #{serial}::text)) " +
            "ORDER BY deletedAt DESC LIMIT 1"})
    Map<String, Object> findTombstoneByIdentity(@Param("customerId") int customerId, @Param("serial") String serial,
                                                @Param("imei") String imei, @Param("since") long since);

    // --- The "wiped" mark, from the agent's announcement ---------------------------------------------------------

    /** The agent said "wiping now": mark the device with that moment and with who ordered the wipe. */
    @Update({"UPDATE devices d SET wipedAt = #{at}, wipedBy = (SELECT c.issuerName FROM agentCommand c " +
            "WHERE c.id = #{commandId} AND c.deviceNumber = d.number) WHERE d.number = #{number}"})
    int markWiped(@Param("number") String number, @Param("commandId") int commandId, @Param("at") long at);

    /** The wipe failed on the device after it was announced: the device is not wiped. */
    @Update({"UPDATE devices SET wipedAt = NULL, wipedBy = NULL WHERE number = #{number}"})
    int unmarkWiped(@Param("number") String number);

    @Select({"SELECT type FROM agentCommand WHERE id = #{id} AND deviceNumber = #{number}"})
    String commandType(@Param("number") String number, @Param("id") int id);
}
