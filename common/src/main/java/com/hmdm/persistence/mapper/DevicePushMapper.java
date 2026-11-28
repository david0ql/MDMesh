package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DevicePush;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for {@code device_push} (the second wake channel). */
public interface DevicePushMapper {

    /**
     * What the agent reports with a check-in. A report without a token keeps the stored one only while the agent
     * still says the channel is ready (an older report in flight); any other state clears it.
     */
    @Insert({"INSERT INTO device_push (deviceNumber, updatedAt, provider, token, tokenAt, state, error, batteryExempt, " +
            "exactAlarms, bgRestricted, pollMinutes, lastWokenBy) " +
            "VALUES (#{deviceNumber}, #{updatedAt}, #{provider}, #{token}, #{tokenAt}, #{state}, #{error}, #{batteryExempt}, " +
            "#{exactAlarms}, #{bgRestricted}, #{pollMinutes}, #{lastWokenBy}) " +
            "ON CONFLICT (deviceNumber) DO UPDATE SET updatedAt = EXCLUDED.updatedAt, provider = EXCLUDED.provider, " +
            "tokenAt = CASE WHEN EXCLUDED.token IS NOT NULL AND EXCLUDED.token IS DISTINCT FROM device_push.token " +
            "               THEN EXCLUDED.tokenAt ELSE device_push.tokenAt END, " +
            "token = CASE WHEN EXCLUDED.token IS NOT NULL THEN EXCLUDED.token " +
            "             WHEN EXCLUDED.state = 'ready' THEN device_push.token ELSE NULL END, " +
            "state = EXCLUDED.state, error = EXCLUDED.error, " +
            "batteryExempt = COALESCE(EXCLUDED.batteryExempt, device_push.batteryExempt), " +
            "exactAlarms = COALESCE(EXCLUDED.exactAlarms, device_push.exactAlarms), " +
            "bgRestricted = COALESCE(EXCLUDED.bgRestricted, device_push.bgRestricted), " +
            "pollMinutes = COALESCE(EXCLUDED.pollMinutes, device_push.pollMinutes), " +
            "lastWokenBy = COALESCE(EXCLUDED.lastWokenBy, device_push.lastWokenBy)"})
    void upsertReport(DevicePush row);

    @Select({"SELECT * FROM device_push WHERE deviceNumber = #{deviceNumber}"})
    DevicePush find(@Param("deviceNumber") String deviceNumber);

    @Update({"UPDATE device_push SET lastSentAt = #{at}, lastSentOk = #{ok}, lastSendError = #{error} " +
            "WHERE deviceNumber = #{deviceNumber}"})
    void recordSend(@Param("deviceNumber") String deviceNumber, @Param("at") long at,
                    @Param("ok") boolean ok, @Param("error") String error);

    /** The first check-in after a push answers it; later check-ins leave the measured latency alone. */
    @Update({"UPDATE device_push SET lastAnsweredAt = #{at}, lastWakeMs = #{at} - lastSentAt " +
            "WHERE deviceNumber = #{deviceNumber} AND lastSentOk = TRUE AND lastSentAt IS NOT NULL " +
            "AND (lastAnsweredAt IS NULL OR lastAnsweredAt < lastSentAt)"})
    int recordAnswer(@Param("deviceNumber") String deviceNumber, @Param("at") long at);

    /** The push service says this token is gone (app uninstalled, token rotated): stop sending to it. */
    @Update({"UPDATE device_push SET token = NULL, state = 'error', error = #{error} " +
            "WHERE deviceNumber = #{deviceNumber} AND token = #{token}"})
    void dropToken(@Param("deviceNumber") String deviceNumber, @Param("token") String token, @Param("error") String error);

    @Select({"SELECT COUNT(*) FROM device_push p JOIN devices d ON d.number = p.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND p.token IS NOT NULL"})
    int countWithToken(@Param("customerId") int customerId);
}
