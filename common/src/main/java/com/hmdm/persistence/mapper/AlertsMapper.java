/*
 *
 * DallyControl — Alertas (new console module, not part of upstream Headwind).
 *
 * MyBatis mapper for the fleet alert module. Three alert kinds are derived from data the agent already
 * reports and persisted into {@code device_alert} so an operator can mark each one "revisada":
 *
 *   - disconnected : a device whose last sign of life (devices.lastUpdate / device_state.updatedAt) is older
 *                    than the "offline" threshold. Generated on read; one row per disconnection episode.
 *   - kioskExit    : the on-device "kiosk exit" lifecycle event (device_event.type = 'kioskExit').
 *   - simChange    : any SIM lifecycle event (device_event.type LIKE 'sim%').
 *
 * Generation is idempotent: event-derived alerts dedupe on (deviceNumber, type, createdAt=event.ts) via
 * ON CONFLICT, and disconnection alerts skip when an open alert already covers the current offline episode
 * (a row created at/after the device's last-seen). Postgres folds unquoted identifiers to lower case, so the
 * read result-map keys come back lower case (createdat, reviewedat, groupid), as elsewhere in the console.
 *
 * Auto-registered by package scan ({@code com.hmdm.persistence.mapper}); no manual Guice wiring needed.
 *
 */

package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** MyBatis mapper for the "Alertas" console module (device_alert table). */
public interface AlertsMapper {

    /** Newest sign of life for a device row {@code d} with an optional joined device_state {@code s}. */
    String LAST_SEEN = "GREATEST(COALESCE(d.lastUpdate, 0), COALESCE(s.updatedAt, 0))";

    // --- generation (idempotent) ---------------------------------------------------------------------------------

    /**
     * Create a 'disconnected' alert for every device offline longer than the threshold, unless an alert already
     * covers the current offline episode. {@code cutoff} = now − threshold; a device is offline when its last-seen
     * is below it (and > 0, i.e. it has connected at least once). The NOT EXISTS keeps exactly one open alert per
     * episode: once the device reconnects (last-seen advances past the alert) a later drop makes a new row.
     */
    @Insert({"INSERT INTO device_alert (customerId, deviceNumber, type, detail, createdAt, reviewed) " +
            "SELECT d.customerId, d.number, 'disconnected', " +
            "       'Última conexión ' || to_char(to_timestamp(" + LAST_SEEN + " / 1000) AT TIME ZONE #{zone}, 'YYYY-MM-DD HH24:MI'), " +
            "       #{now}, FALSE " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} AND " + LAST_SEEN + " > 0 AND " + LAST_SEEN + " < #{cutoff} " +
            "  AND NOT EXISTS (SELECT 1 FROM device_alert a WHERE a.deviceNumber = d.number " +
            "                  AND a.type = 'disconnected' AND a.createdAt >= " + LAST_SEEN + ") " +
            "ON CONFLICT (deviceNumber, type, createdAt) DO NOTHING"})
    int generateDisconnected(@Param("customerId") int customerId, @Param("cutoff") long cutoff,
                             @Param("now") long now, @Param("zone") String zone);

    /** Create a 'kioskExit' alert for every on-device kiosk-exit event since {@code since} (dedup by event ts). */
    @Insert({"INSERT INTO device_alert (customerId, deviceNumber, type, detail, createdAt, reviewed) " +
            "SELECT d.customerId, e.deviceNumber, 'kioskExit', e.detail, e.ts, FALSE " +
            "FROM device_event e JOIN devices d ON d.number = e.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND e.type = 'kioskExit' AND e.ts >= #{since} " +
            "ON CONFLICT (deviceNumber, type, createdAt) DO NOTHING"})
    int generateKioskExit(@Param("customerId") int customerId, @Param("since") long since);

    /**
     * Create a 'simChange' alert for every SIM lifecycle event since {@code since} (dedup by event ts). The original
     * sub-type (simRemoved / simChanged / simInserted) and any detail are kept in {@code detail} for display.
     */
    @Insert({"INSERT INTO device_alert (customerId, deviceNumber, type, detail, createdAt, reviewed) " +
            "SELECT d.customerId, e.deviceNumber, 'simChange', " +
            "       e.type || CASE WHEN e.detail IS NULL OR e.detail = '' THEN '' ELSE ': ' || e.detail END, " +
            "       e.ts, FALSE " +
            "FROM device_event e JOIN devices d ON d.number = e.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND e.type LIKE 'sim%' AND e.ts >= #{since} " +
            "ON CONFLICT (deviceNumber, type, createdAt) DO NOTHING"})
    int generateSimChange(@Param("customerId") int customerId, @Param("since") long since);

    // --- read ----------------------------------------------------------------------------------------------------

    /**
     * Alerts for a customer, newest first, filtered by the optional type / date-range / status. {@code status} is
     * 'reviewed', 'pending' or null (all). Carries the device description + folder id (for name/path enrichment)
     * and the reviewer's login/name.
     */
    @Select({"<script>",
            "SELECT a.id, a.deviceNumber AS number, d.description, ",
            ReportsMapper.GROUP_ID_SUBQUERY, ", ",
            "a.type, a.detail, a.createdAt AS createdat, a.reviewed, ",
            "a.reviewedBy AS reviewedby, a.reviewedAt AS reviewedat, u.login AS reviewedbylogin, u.name AS reviewedbyname ",
            "FROM device_alert a JOIN devices d ON d.number = a.deviceNumber ",
            "LEFT JOIN users u ON u.id = a.reviewedBy ",
            "WHERE a.customerId = #{customerId} ",
            "<if test='type != null'> AND a.type = #{type} </if>",
            "<if test='from != null'> AND a.createdAt &gt;= #{from} </if>",
            "<if test='to != null'> AND a.createdAt &lt;= #{to} </if>",
            "<if test=\"status == 'reviewed'\"> AND a.reviewed </if>",
            "<if test=\"status == 'pending'\"> AND NOT a.reviewed </if>",
            "ORDER BY a.createdAt DESC, a.id DESC LIMIT #{limit}",
            "</script>"})
    List<Map<String, Object>> list(@Param("customerId") int customerId, @Param("type") String type,
                                   @Param("from") Long from, @Param("to") Long to,
                                   @Param("status") String status, @Param("limit") int limit);

    /** The device number behind one alert (for the folder-scope check on review), or null if it isn't the customer's. */
    @Select({"SELECT deviceNumber FROM device_alert WHERE id = #{id} AND customerId = #{customerId}"})
    String deviceNumberOf(@Param("id") long id, @Param("customerId") int customerId);

    // --- review --------------------------------------------------------------------------------------------------

    /**
     * Mark one alert reviewed / unreviewed. Reviewing stamps who (userId) and when (now); unreviewing clears both.
     * Scoped to the customer so an id from another tenant is a no-op. Returns the number of rows changed.
     */
    @Update({"UPDATE device_alert SET reviewed = #{reviewed}, " +
            "reviewedBy = CASE WHEN #{reviewed} THEN #{userId} ELSE NULL END, " +
            "reviewedAt = CASE WHEN #{reviewed} THEN #{now} ELSE NULL END " +
            "WHERE id = #{id} AND customerId = #{customerId}"})
    int setReviewed(@Param("id") long id, @Param("reviewed") boolean reviewed,
                    @Param("userId") Integer userId, @Param("now") Long now,
                    @Param("customerId") int customerId);
}
