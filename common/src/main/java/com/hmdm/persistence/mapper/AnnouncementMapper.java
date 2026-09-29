package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** Announcements (shown in the agent app) and their per-device receipts. */
public interface AnnouncementMapper {

    /** Insert; the generated id lands in {@code a.id}. */
    @Insert({"INSERT INTO announcement (customerId, title, body, mediaUrl, mediaType, mandatory, targetLabel, createdBy, createdAt, expiresAt) " +
            "VALUES (#{a.customerId}, #{a.title}, #{a.body}, #{a.mediaUrl}, #{a.mediaType}, #{a.mandatory}, #{a.targetLabel}, " +
            "#{a.createdBy}, #{a.createdAt}, #{a.expiresAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "a.id", keyColumn = "id")
    void insert(@Param("a") Map<String, Object> a);

    @Insert({"INSERT INTO announcement_receipt (announcementId, deviceNumber) VALUES (#{id}, #{number}) ON CONFLICT DO NOTHING"})
    void addReceipt(@Param("id") int id, @Param("number") String number);

    @Select({"SELECT a.id, a.title, a.body, a.mediaUrl, a.mediaType, a.mandatory, a.targetLabel, a.createdBy, a.createdAt, " +
            "a.expiresAt, a.withdrawnAt, " +
            "count(r.deviceNumber) AS total, count(r.receivedAt) AS received, count(r.seenAt) AS seen, count(r.ackAt) AS acked " +
            "FROM announcement a LEFT JOIN announcement_receipt r ON r.announcementId = a.id " +
            "WHERE a.customerId = #{customerId} GROUP BY a.id ORDER BY a.createdAt DESC LIMIT 200"})
    List<Map<String, Object>> list(@Param("customerId") int customerId);

    @Select({"SELECT * FROM announcement WHERE id = #{id} AND customerId = #{customerId}"})
    Map<String, Object> find(@Param("customerId") int customerId, @Param("id") int id);

    @Select({"SELECT r.deviceNumber, d.id AS deviceId, d.description, r.sentAt, r.receivedAt, r.seenAt, r.ackAt " +
            "FROM announcement_receipt r LEFT JOIN devices d ON d.number = r.deviceNumber " +
            "WHERE r.announcementId = #{id} ORDER BY r.ackAt NULLS FIRST, r.seenAt NULLS FIRST, d.description"})
    List<Map<String, Object>> receipts(@Param("id") int id);

    @Update({"UPDATE announcement SET withdrawnAt = #{now} WHERE id = #{id} AND customerId = #{customerId} AND withdrawnAt IS NULL"})
    int withdraw(@Param("customerId") int customerId, @Param("id") int id, @Param("now") long now);

    /** Live announcements this device has not confirmed receiving yet (to (re)send on its check-in). */
    @Select({"SELECT a.id, a.title, a.body, a.mediaUrl, a.mediaType, a.mandatory, a.createdAt, a.expiresAt, r.commandId " +
            "FROM announcement_receipt r JOIN announcement a ON a.id = r.announcementId " +
            "WHERE r.deviceNumber = #{number} AND r.receivedAt IS NULL AND a.withdrawnAt IS NULL " +
            "AND (a.expiresAt IS NULL OR a.expiresAt > #{now}) " +
            "AND (r.commandId IS NULL OR NOT EXISTS (SELECT 1 FROM agentCommand c WHERE c.id = r.commandId " +
            "AND c.status IN ('pending','delivered','accepted','done'))) ORDER BY a.createdAt LIMIT 10"})
    List<Map<String, Object>> pendingFor(@Param("number") String number, @Param("now") long now);

    @Update({"UPDATE announcement_receipt SET commandId = #{commandId}, sentAt = #{now} WHERE announcementId = #{id} AND deviceNumber = #{number}"})
    void markSent(@Param("id") int id, @Param("number") String number, @Param("commandId") int commandId, @Param("now") long now);

    @Update({"UPDATE announcement_receipt SET receivedAt = COALESCE(receivedAt, #{ts}) WHERE announcementId = #{id} AND deviceNumber = #{number}"})
    void markReceived(@Param("id") int id, @Param("number") String number, @Param("ts") long ts);

    @Update({"UPDATE announcement_receipt SET receivedAt = COALESCE(receivedAt, #{ts}), seenAt = COALESCE(seenAt, #{ts}) " +
            "WHERE announcementId = #{id} AND deviceNumber = #{number}"})
    void markSeen(@Param("id") int id, @Param("number") String number, @Param("ts") long ts);

    @Update({"UPDATE announcement_receipt SET receivedAt = COALESCE(receivedAt, #{ts}), seenAt = COALESCE(seenAt, #{ts}), " +
            "ackAt = COALESCE(ackAt, #{ts}) WHERE announcementId = #{id} AND deviceNumber = #{number}"})
    void markAck(@Param("id") int id, @Param("number") String number, @Param("ts") long ts);

    /** Devices that got a now-withdrawn announcement (to remove it from their app). */
    @Select({"SELECT deviceNumber FROM announcement_receipt WHERE announcementId = #{id} AND sentAt IS NOT NULL"})
    List<String> sentTo(@Param("id") int id);
}
