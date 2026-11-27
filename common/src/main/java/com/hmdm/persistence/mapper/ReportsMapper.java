/*
 *
 * DallyControl — fleet reports (new module, not part of upstream Headwind).
 *
 * Read-only aggregation queries for the "Reportes" console module. Every query is scoped by
 * customerId and reads the data the agent already reports (device_state, device_event,
 * device_app_usage, agentCommand, devices.infojson, device_tombstone). Nothing here mutates.
 *
 * Postgres folds unquoted identifiers to lower case, so the result-map keys come back lower case
 * (e.g. {@code lastupdate}, {@code groupid}); the resource and the web client read them that way.
 *
 */

package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for the fleet reports. Auto-registered by package scan
 * ({@code com.hmdm.persistence.mapper}); no manual Guice wiring needed.
 */
public interface ReportsMapper {

    /** A device's single folder id (DallyControl keeps exactly one per device). */
    String GROUP_ID_SUBQUERY =
            "(SELECT dg.groupId FROM deviceGroups dg WHERE dg.deviceId = d.id ORDER BY dg.id LIMIT 1) AS groupid";

    /** The newest sign of life: the device row's lastUpdate or its last state report, whichever is later. */
    String LAST_SEEN = "GREATEST(COALESCE(d.lastUpdate, 0), COALESCE(s.updatedAt, 0))";

    // --- 1. Devices with no connection for X days ----------------------------------------------------------------

    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            LAST_SEEN + " AS lastseen, d.lastUpdate AS lastupdate, s.updatedAt AS stateat, " +
            "s.agentVersion AS agentversion, s.androidRelease AS androidrelease " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} AND " + LAST_SEEN + " < #{cutoff} " +
            "ORDER BY lastseen ASC"})
    List<Map<String, Object>> offlineDevices(@Param("customerId") int customerId, @Param("cutoff") long cutoff);

    // --- 2. SIM removal / change events in a date range ----------------------------------------------------------

    @Select({"SELECT e.deviceNumber AS number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "e.type, e.ts, e.detail " +
            "FROM device_event e JOIN devices d ON d.number = e.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND e.type LIKE 'sim%' AND e.ts BETWEEN #{from} AND #{to} " +
            "ORDER BY e.ts DESC, e.id DESC"})
    List<Map<String, Object>> simEvents(@Param("customerId") int customerId,
                                        @Param("from") long from, @Param("to") long to);

    // --- 2b. Devices whose SIM number does not match the expected one -------------------------------------------

    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "d.expectedPhone AS expectedphone, d.simPhone AS simphone, d.simPhoneAt AS simphoneat, " +
            LAST_SEEN + " AS lastseen " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} AND d.simMismatch = TRUE " +
            "ORDER BY lower(coalesce(d.description, d.number))"})
    List<Map<String, Object>> simMismatchDevices(@Param("customerId") int customerId);

    // --- 3. Apps deployed via app.install, aggregated by package -------------------------------------------------

    @Select({"SELECT pkg, COUNT(DISTINCT deviceNumber) AS devices, COUNT(*) AS commands, " +
            "COUNT(*) FILTER (WHERE status = 'done') AS done, " +
            "COUNT(*) FILTER (WHERE status IN ('failed','unsupported','expired')) AS failed, " +
            "COUNT(*) FILTER (WHERE status IN ('pending','delivered')) AS pending, " +
            "MAX(createdat) AS lastat " +
            "FROM (SELECT a.deviceNumber, a.status, a.createdAt AS createdat, " +
            "             (a.payload::jsonb ->> 'packageName') AS pkg " +
            "      FROM agentCommand a JOIN devices d ON d.number = a.deviceNumber " +
            "      WHERE d.customerId = #{customerId} AND a.type = 'app.install' " +
            "        AND a.payload IS NOT NULL AND a.payload ~ '^\\s*\\{' OFFSET 0) t " +
            "WHERE pkg IS NOT NULL " +
            "GROUP BY pkg ORDER BY devices DESC, commands DESC, pkg"})
    List<Map<String, Object>> appInstallAggregate(@Param("customerId") int customerId);

    /**
     * Same aggregate as {@link #appInstallAggregate}, but narrowed to a folder administrator's own devices.
     * {@code devs} is a comma-separated list of device numbers (UUIDs, no commas); the inner sub-select that
     * joins {@code devices d} gets the extra {@code AND d.number = ANY(...)} guard so counts never include
     * other folders' devices. Everything else is identical to the fleet-wide query.
     */
    @Select({"SELECT pkg, COUNT(DISTINCT deviceNumber) AS devices, COUNT(*) AS commands, " +
            "COUNT(*) FILTER (WHERE status = 'done') AS done, " +
            "COUNT(*) FILTER (WHERE status IN ('failed','unsupported','expired')) AS failed, " +
            "COUNT(*) FILTER (WHERE status IN ('pending','delivered')) AS pending, " +
            "MAX(createdat) AS lastat " +
            "FROM (SELECT a.deviceNumber, a.status, a.createdAt AS createdat, " +
            "             (a.payload::jsonb ->> 'packageName') AS pkg " +
            "      FROM agentCommand a JOIN devices d ON d.number = a.deviceNumber " +
            "      WHERE d.customerId = #{customerId} AND a.type = 'app.install' " +
            "        AND d.number = ANY(string_to_array(#{devs}, ',')) " +
            "        AND a.payload IS NOT NULL AND a.payload ~ '^\\s*\\{' OFFSET 0) t " +
            "WHERE pkg IS NOT NULL " +
            "GROUP BY pkg ORDER BY devices DESC, commands DESC, pkg"})
    List<Map<String, Object>> appInstallAggregateScoped(@Param("customerId") int customerId,
                                                        @Param("devs") String devs);

    /**
     * Drill-down: every app.install for one package, newest first. The JSON cast lives in a sub-select whose WHERE
     * already excluded non-JSON payloads (OFFSET 0 fences it so the planner never lifts the cast above the guard).
     */
    @Select({"SELECT number, description, groupid, status, createdat, deliveredat, completedat, detail FROM (" +
            "  SELECT a.deviceNumber AS number, d.description, " + GROUP_ID_SUBQUERY + ", a.status, " +
            "         a.createdAt AS createdat, a.deliveredAt AS deliveredat, a.completedAt AS completedat, a.detail, " +
            "         (a.payload::jsonb ->> 'packageName') AS pkg " +
            "  FROM agentCommand a JOIN devices d ON d.number = a.deviceNumber " +
            "  WHERE d.customerId = #{customerId} AND a.type = 'app.install' " +
            "    AND a.payload IS NOT NULL AND a.payload ~ '^\\s*\\{' OFFSET 0" +
            ") t WHERE pkg = #{pkg} ORDER BY createdat DESC"})
    List<Map<String, Object>> appInstallDevices(@Param("customerId") int customerId, @Param("pkg") String pkg);

    // --- 4. Devices by installed app -----------------------------------------------------------------------------

    /** Catalogue of every package the fleet reports as installed, with how many devices carry it. */
    @Select({"SELECT app->>'pkg' AS pkg, MAX(app->>'name') AS name, COUNT(DISTINCT d.id) AS devices " +
            "FROM devices d CROSS JOIN LATERAL jsonb_array_elements(COALESCE(d.infojson->'applications', '[]'::jsonb)) app " +
            "WHERE d.customerId = #{customerId} AND app->>'pkg' IS NOT NULL AND app->>'pkg' <> '' " +
            "GROUP BY app->>'pkg' ORDER BY devices DESC, pkg"})
    List<Map<String, Object>> installedAppsCatalog(@Param("customerId") int customerId);

    /**
     * Same catalogue as {@link #installedAppsCatalog}, but narrowed to a folder administrator's own devices.
     * {@code devs} is a comma-separated list of device numbers (UUIDs, no commas); the extra
     * {@code AND d.number = ANY(...)} guard keeps the per-package device counts to the editor's folders.
     */
    @Select({"SELECT app->>'pkg' AS pkg, MAX(app->>'name') AS name, COUNT(DISTINCT d.id) AS devices " +
            "FROM devices d CROSS JOIN LATERAL jsonb_array_elements(COALESCE(d.infojson->'applications', '[]'::jsonb)) app " +
            "WHERE d.customerId = #{customerId} AND app->>'pkg' IS NOT NULL AND app->>'pkg' <> '' " +
            "  AND d.number = ANY(string_to_array(#{devs}, ',')) " +
            "GROUP BY app->>'pkg' ORDER BY devices DESC, pkg"})
    List<Map<String, Object>> installedAppsCatalogScoped(@Param("customerId") int customerId,
                                                         @Param("devs") String devs);

    /** One row per (device, matched package): the devices that have any of the requested packages. */
    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "app->>'pkg' AS pkg, app->>'version' AS version, app->>'name' AS name " +
            "FROM devices d CROSS JOIN LATERAL jsonb_array_elements(COALESCE(d.infojson->'applications', '[]'::jsonb)) app " +
            "WHERE d.customerId = #{customerId} " +
            "  AND app->>'pkg' = ANY(string_to_array(#{pkgs}, ',')) " +
            "ORDER BY lower(coalesce(d.description, d.number)), pkg"})
    List<Map<String, Object>> devicesByApp(@Param("customerId") int customerId, @Param("pkgs") String pkgs);

    // --- 5. Devices that are not picking up commands -------------------------------------------------------------

    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "COUNT(a.id) AS pending, MIN(a.createdAt) AS oldestat, " +
            "(SELECT a2.type FROM agentCommand a2 WHERE a2.deviceNumber = d.number AND a2.status = 'pending' " +
            " ORDER BY a2.createdAt ASC LIMIT 1) AS firsttype, " +
            "d.lastUpdate AS lastupdate, s.updatedAt AS stateat " +
            "FROM devices d " +
            "JOIN agentCommand a ON a.deviceNumber = d.number AND a.status = 'pending' AND a.createdAt < #{cutoff} " +
            "LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} " +
            "GROUP BY d.id, d.number, d.description, d.lastUpdate, s.updatedAt " +
            "ORDER BY MIN(a.createdAt) ASC"})
    List<Map<String, Object>> noCommandDevices(@Param("customerId") int customerId, @Param("cutoff") long cutoff);

    // --- 6a. Devices whose agent is not the latest served version ------------------------------------------------

    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "s.agentVersion AS agentversion, s.updatedAt AS stateat, d.lastUpdate AS lastupdate " +
            "FROM devices d JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} AND s.agentVersion IS NOT NULL " +
            "  AND s.agentVersion IS DISTINCT FROM #{latestVersion} " +
            "ORDER BY s.agentVersion, lower(coalesce(d.description, d.number))"})
    List<Map<String, Object>> outdatedAgents(@Param("customerId") int customerId,
                                             @Param("latestVersion") String latestVersion);

    // --- 6b. Installed apps older than the library's current version (best-effort, by version string) ------------

    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "app->>'pkg' AS pkg, ap.name AS appname, app->>'version' AS installed, av.version AS latest " +
            "FROM devices d CROSS JOIN LATERAL jsonb_array_elements(COALESCE(d.infojson->'applications', '[]'::jsonb)) app " +
            "JOIN applications ap ON ap.pkg = app->>'pkg' AND ap.customerId = d.customerId " +
            "JOIN applicationVersions av ON av.id = ap.latestVersion " +
            "WHERE d.customerId = #{customerId} AND app->>'version' IS NOT NULL AND app->>'version' <> '' " +
            "  AND av.version IS NOT NULL AND (app->>'version') IS DISTINCT FROM av.version " +
            "ORDER BY app->>'pkg', lower(coalesce(d.description, d.number))"})
    List<Map<String, Object>> outdatedApps(@Param("customerId") int customerId);

    // --- 7. Mobile-data consumption (fleet), top consumers over N days -------------------------------------------

    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "SUM(u.mobileBytes) AS mobilebytes, SUM(u.wifiBytes) AS wifibytes, " +
            "MAX(u.day) AS lastday " +
            "FROM device_app_usage u JOIN devices d ON d.number = u.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND u.day >= #{fromDay} " +
            "GROUP BY d.id, d.number, d.description " +
            "HAVING SUM(u.mobileBytes) > 0 " +
            "ORDER BY SUM(u.mobileBytes) DESC " +
            "LIMIT #{limit}"})
    List<Map<String, Object>> dataUsage(@Param("customerId") int customerId,
                                        @Param("fromDay") String fromDay, @Param("limit") int limit);

    /** The same, but one row per (device, package) for the mobile-data breakdown of a single device. */
    @Select({"SELECT u.pkg, MAX(u.label) AS label, SUM(u.mobileBytes) AS mobilebytes, SUM(u.wifiBytes) AS wifibytes " +
            "FROM device_app_usage u JOIN devices d ON d.number = u.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND u.deviceNumber = #{number} AND u.day >= #{fromDay} " +
            "GROUP BY u.pkg HAVING SUM(u.mobileBytes) > 0 ORDER BY SUM(u.mobileBytes) DESC"})
    List<Map<String, Object>> dataUsageByApp(@Param("customerId") int customerId,
                                             @Param("number") String number, @Param("fromDay") String fromDay);

    // --- 8. Policy non-compliance (applied revision != current) --------------------------------------------------

    /** Every device with a configuration, its last applied revision and when (null = never applied). */
    @Select({"SELECT d.id, d.number, d.description, " + GROUP_ID_SUBQUERY + ", " +
            "d.configurationId AS configurationid, c.name AS configurationname, " +
            "s.appliedConfigRevision AS appliedrevision, s.appliedConfigAt AS appliedat, " +
            "d.lastUpdate AS lastupdate, s.updatedAt AS stateat " +
            "FROM devices d LEFT JOIN configurations c ON c.id = d.configurationId " +
            "LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} AND d.configurationId IS NOT NULL " +
            "ORDER BY lower(coalesce(d.description, d.number))"})
    List<Map<String, Object>> complianceRows(@Param("customerId") int customerId);

    // --- 9. Decommissioned devices in a date range (device_tombstone) --------------------------------------------

    @Select({"SELECT t.number, t.description, t.groupId AS groupid, t.deletedAt AS deletedat, " +
            "(t.secretHash IS NULL) AS claimed " +
            "FROM device_tombstone t " +
            "WHERE t.customerId = #{customerId} AND t.deletedAt BETWEEN #{from} AND #{to} " +
            "ORDER BY t.deletedAt DESC"})
    List<Map<String, Object>> decommissioned(@Param("customerId") int customerId,
                                             @Param("from") long from, @Param("to") long to);
}
