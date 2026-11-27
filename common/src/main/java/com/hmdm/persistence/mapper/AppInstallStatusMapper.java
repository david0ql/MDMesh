package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the install commands ({@code app.install} and its forced variant) for the console's
 * "Instalación en la flota" and for the automatic retry of failed installs. It never changes a command.
 * Rows come back as maps with lower-case keys.
 */
public interface AppInstallStatusMapper {

    String COLUMNS = "c.id, c.deviceNumber, c.type, c.status, c.detail, c.payload, c.requiresCapability, "
            + "c.createdAt, c.completedAt ";
    String INSTALL_TYPES = "c.type IN ('app.install', 'device.appInstallForce') ";

    /** A customer's install commands whose payload mentions the package (the caller checks the exact package), oldest first. */
    @Select({"SELECT " + COLUMNS + "FROM agentCommand c JOIN devices d ON d.number = c.deviceNumber "
            + "WHERE d.customerId = #{customerId} AND " + INSTALL_TYPES
            + "AND c.createdAt >= #{since} AND c.payload LIKE #{like} ORDER BY c.id"})
    List<Map<String, Object>> commandsForPackage(@Param("customerId") int customerId, @Param("like") String like,
                                                 @Param("since") long since);

    /** One device's install commands, oldest first. */
    @Select({"SELECT " + COLUMNS + "FROM agentCommand c WHERE c.deviceNumber = #{deviceNumber} AND " + INSTALL_TYPES
            + "AND c.createdAt >= #{since} ORDER BY c.id"})
    List<Map<String, Object>> commandsForDevice(@Param("deviceNumber") String deviceNumber, @Param("since") long since);

    /** Every install command of the devices that had one fail or expire since [failedSince], oldest first. */
    @Select({"SELECT " + COLUMNS + "FROM agentCommand c WHERE " + INSTALL_TYPES + "AND c.createdAt >= #{since} "
            + "AND c.deviceNumber IN (SELECT f.deviceNumber FROM agentCommand f WHERE f.type IN ('app.install', 'device.appInstallForce') "
            + "AND f.status IN ('failed', 'expired') AND f.completedAt >= #{failedSince}) ORDER BY c.id"})
    List<Map<String, Object>> commandsOfDevicesWithFailures(@Param("since") long since, @Param("failedSince") long failedSince);

    /** Remember that a policy (not a person) queued this install order. */
    @Insert({"INSERT INTO agent_install_origin (commandId, origin, createdAt) VALUES (#{commandId}, #{origin}, #{createdAt}) "
            + "ON CONFLICT (commandId) DO NOTHING"})
    void recordOrigin(@Param("commandId") int commandId, @Param("origin") String origin, @Param("createdAt") long createdAt);

    /** "policy" when a policy queued the order; null when a person did (console, API) or it predates this record. */
    @Select({"SELECT origin FROM agent_install_origin WHERE commandId = #{commandId}"})
    String originOf(@Param("commandId") int commandId);

    /** Uninstall orders for the package (payload LIKE) sent to the device at or after [since], whatever became of them. */
    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = 'app.uninstall' "
            + "AND createdAt >= #{since} AND regexp_replace(payload, '\\s', '', 'g') LIKE #{like}"})
    int uninstallsSince(@Param("deviceNumber") String deviceNumber, @Param("like") String like, @Param("since") long since);

    /** The customer's devices with what the view shows about each, and the version of [pkg] its last inventory reported. */
    @Select({"SELECT d.id, d.number, d.description, d.configurationId, d.agentCapabilities, d.lastUpdate, g.name AS groupName, "
            + "(SELECT app ->> 'version' FROM jsonb_array_elements(COALESCE(d.infojson -> 'applications', '[]'::jsonb)) app "
            + " WHERE app ->> 'pkg' = #{pkg} LIMIT 1) AS installedVersion "
            + "FROM devices d LEFT JOIN LATERAL (SELECT dg.groupId FROM deviceGroups dg WHERE dg.deviceId = d.id "
            + "ORDER BY dg.id LIMIT 1) m ON true LEFT JOIN groups g ON g.id = m.groupId "
            + "WHERE d.customerId = #{customerId} ORDER BY d.number"})
    List<Map<String, Object>> devices(@Param("customerId") int customerId, @Param("pkg") String pkg);
}
