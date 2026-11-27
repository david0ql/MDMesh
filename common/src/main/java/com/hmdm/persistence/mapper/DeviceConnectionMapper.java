package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DeviceConnection;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for {@code device_conn_diag}. The upsert refreshes the "as of the last report" columns every time
 * and keeps the last failure / silence until a newer one arrives (a check-in with nothing to report must not erase
 * the explanation of yesterday's gap).
 */
public interface DeviceConnectionMapper {

    @Insert({"INSERT INTO device_conn_diag (deviceNumber, updatedAt, screenOn, network, serverHost, usingBackup, reconnects, " +
            "failureKind, failureAt, failureDetail, failureHttpCode, failureChannel, failureNetwork, failureSsid, failureHost, failureIssuer, failureCount, " +
            "stopReason, stopAt, stopResumedAt, stopBattery) " +
            "VALUES (#{deviceNumber}, #{updatedAt}, #{screenOn}, #{network}, #{serverHost}, #{usingBackup}, COALESCE(#{reconnects}, 0), " +
            "#{failureKind}, #{failureAt}, #{failureDetail}, #{failureHttpCode}, #{failureChannel}, #{failureNetwork}, #{failureSsid}, #{failureHost}, #{failureIssuer}, #{failureCount}, " +
            "#{stopReason}, #{stopAt}, #{stopResumedAt}, #{stopBattery}) " +
            "ON CONFLICT (deviceNumber) DO UPDATE SET " +
            "updatedAt = EXCLUDED.updatedAt, screenOn = EXCLUDED.screenOn, network = EXCLUDED.network, " +
            "serverHost = COALESCE(EXCLUDED.serverHost, device_conn_diag.serverHost), " +
            "usingBackup = COALESCE(EXCLUDED.usingBackup, device_conn_diag.usingBackup), " +
            "reconnects = device_conn_diag.reconnects + EXCLUDED.reconnects, " +
            "failureKind = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureKind ELSE EXCLUDED.failureKind END, " +
            "failureAt = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureAt ELSE EXCLUDED.failureAt END, " +
            "failureDetail = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureDetail ELSE EXCLUDED.failureDetail END, " +
            "failureHttpCode = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureHttpCode ELSE EXCLUDED.failureHttpCode END, " +
            "failureChannel = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureChannel ELSE EXCLUDED.failureChannel END, " +
            "failureNetwork = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureNetwork ELSE EXCLUDED.failureNetwork END, " +
            "failureSsid = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureSsid ELSE EXCLUDED.failureSsid END, " +
            "failureHost = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureHost ELSE EXCLUDED.failureHost END, " +
            "failureIssuer = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureIssuer ELSE EXCLUDED.failureIssuer END, " +
            "failureCount = CASE WHEN EXCLUDED.failureKind IS NULL THEN device_conn_diag.failureCount ELSE EXCLUDED.failureCount END, " +
            "stopReason = CASE WHEN EXCLUDED.stopReason IS NULL THEN device_conn_diag.stopReason ELSE EXCLUDED.stopReason END, " +
            "stopAt = CASE WHEN EXCLUDED.stopReason IS NULL THEN device_conn_diag.stopAt ELSE EXCLUDED.stopAt END, " +
            "stopResumedAt = CASE WHEN EXCLUDED.stopReason IS NULL THEN device_conn_diag.stopResumedAt ELSE EXCLUDED.stopResumedAt END, " +
            "stopBattery = CASE WHEN EXCLUDED.stopReason IS NULL THEN device_conn_diag.stopBattery ELSE EXCLUDED.stopBattery END"})
    void upsert(DeviceConnection row);

    @Select({"SELECT * FROM device_conn_diag WHERE deviceNumber = #{deviceNumber}"})
    DeviceConnection find(@Param("deviceNumber") String deviceNumber);

    /** One row per device of the customer: last report time, battery, and its connection row (LEFT JOIN: may be absent). */
    @Select({"SELECT d.number AS number, d.lastUpdate AS lastupdate, s.battery AS battery, s.charging AS charging, " +
            "c.screenOn AS screenon, c.network AS network, c.serverHost AS serverhost, c.usingBackup AS usingbackup, c.reconnects AS reconnects, " +
            "c.failureKind AS failurekind, c.failureAt AS failureat, c.failureDetail AS failuredetail, c.failureHttpCode AS failurehttpcode, " +
            "c.failureChannel AS failurechannel, c.failureNetwork AS failurenetwork, c.failureSsid AS failuressid, c.failureHost AS failurehost, " +
            "c.failureIssuer AS failureissuer, c.failureCount AS failurecount, " +
            "c.stopReason AS stopreason, c.stopAt AS stopat, c.stopResumedAt AS stopresumedat, c.stopBattery AS stopbattery " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "LEFT JOIN device_conn_diag c ON c.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId}"})
    List<Map<String, Object>> listForCustomer(@Param("customerId") int customerId);
}
