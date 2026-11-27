/*
 *
 * DallyControl — audit trail (new module, not part of upstream Headwind).
 *
 * MyBatis mapper of audit_trail (append-only: the table's triggers refuse UPDATE, and DELETE outside the retention
 * purge) and audit_settings, plus the small look-ups the audit needs to name who acted and what an action touched.
 * Postgres folds unquoted identifiers to lower case, so Map results come back with lower-case keys.
 * Auto-registered by package scan (com.hmdm.persistence.mapper).
 *
 */

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AuditEntry;
import com.hmdm.persistence.domain.AuditQuery;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface AuditTrailMapper {

    String COLUMNS = "e.id, e.createdAt, e.customerId, e.userId, e.login, e.userName, e.roleId, e.roleName, e.ip, " +
            "e.userAgent, e.category, e.action, e.label, e.method, e.path, e.targetType, e.targetId, e.targetName, " +
            "array_to_string(e.deviceNumbers, ',') AS deviceNumbers, array_to_string(e.groupIds, ',') AS groupIds, " +
            "e.result, e.httpStatus, e.message, e.detail, e.durationMs, e.prevHash, e.hash";

    String WHERE = "<where>" +
            "(e.customerId = #{customerId}<if test='includeNoCustomer'> OR e.customerId IS NULL</if>)" +
            "<if test='from != null'> AND e.createdAt &gt;= #{from}</if>" +
            "<if test='to != null'> AND e.createdAt &lt;= #{to}</if>" +
            "<if test='userId != null'> AND e.userId = #{userId}</if>" +
            "<if test='category != null'> AND e.category = #{category}</if>" +
            "<if test='action != null'> AND e.action = #{action}</if>" +
            "<if test='result != null'> AND e.result = #{result}</if>" +
            "<if test='targetType != null'> AND e.targetType = #{targetType}</if>" +
            "<if test='targetId != null'> AND e.targetId = #{targetId}</if>" +
            "<if test='deviceNumber != null'> AND e.deviceNumbers @&gt; ARRAY[#{deviceNumber}]::text[]</if>" +
            "<if test='groupId != null'> AND e.groupIds @&gt; ARRAY[#{groupId}]::int[]</if>" +
            "<if test='q != null'> AND (e.login ILIKE #{q} OR e.userName ILIKE #{q} OR e.label ILIKE #{q} " +
            "OR e.targetName ILIKE #{q} OR e.targetId ILIKE #{q} OR e.ip ILIKE #{q} OR e.detail ILIKE #{q} " +
            "OR e.message ILIKE #{q} OR array_to_string(e.deviceNumbers, ',') ILIKE #{q})</if>" +
            "<if test='scopeGroups != null'> AND (e.groupIds &amp;&amp; #{scopeGroups}::int[]" +
            "<if test='scopeUsers != null and scopeUsers.size() > 0'> OR e.userId IN " +
            "<foreach item='u' collection='scopeUsers' open='(' separator=',' close=')'>#{u}</foreach></if>)</if>" +
            "</where>";

    // --- write (append-only) -------------------------------------------------------------------------------------

    /** Serialises the writers, so every row's prevHash is the hash of the row written just before it. */
    @Select({"SELECT 1 FROM (SELECT pg_advisory_xact_lock(742600127)) l"})
    Integer lockChain();

    /** The chain is per customer (each has its own retention), in id order. */
    @Select({"SELECT hash FROM audit_trail WHERE customerId IS NOT DISTINCT FROM #{customerId,jdbcType=INTEGER} " +
            "ORDER BY id DESC LIMIT 1"})
    String lastHash(@Param("customerId") Integer customerId);

    @Insert({"INSERT INTO audit_trail (createdAt, customerId, userId, login, userName, roleId, roleName, ip, userAgent, " +
            "category, action, label, method, path, targetType, targetId, targetName, deviceNumbers, groupIds, result, " +
            "httpStatus, message, detail, durationMs, prevHash, hash) VALUES (#{createdAt}, #{customerId}, #{userId}, " +
            "#{login}, #{userName}, #{roleId}, #{roleName}, #{ip}, #{userAgent}, #{category}, #{action}, #{label}, " +
            "#{method}, #{path}, #{targetType}, #{targetId}, #{targetName}, #{deviceNumbersLiteral}::text[], " +
            "#{groupIdsLiteral}::int[], #{result}, #{httpStatus}, #{message}, #{detail}, #{durationMs}, #{prevHash}, #{hash})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(AuditEntry entry);

    // --- read ----------------------------------------------------------------------------------------------------

    @Select({"<script>SELECT " + COLUMNS + " FROM audit_trail e " + WHERE +
            " ORDER BY e.createdAt DESC, e.id DESC LIMIT #{limit} OFFSET #{offset}</script>"})
    List<AuditEntry> search(AuditQuery query);

    @Select({"<script>SELECT COUNT(*) FROM audit_trail e " + WHERE + "</script>"})
    long count(AuditQuery query);

    /** The technicians that appear in the rows the caller may see (for the filter). */
    @Select({"<script>SELECT e.userId AS id, MAX(e.login) AS login, MAX(e.userName) AS name, COUNT(*) AS n " +
            "FROM audit_trail e " + WHERE + " AND e.userId IS NOT NULL GROUP BY e.userId ORDER BY MAX(e.login)</script>"})
    List<Map<String, Object>> users(AuditQuery query);

    /** The actions that appear in the rows the caller may see (for the filter). */
    @Select({"<script>SELECT e.category AS category, e.action AS action, MAX(e.label) AS label, COUNT(*) AS n " +
            "FROM audit_trail e " + WHERE + " GROUP BY e.category, e.action ORDER BY e.category, MAX(e.label)</script>"})
    List<Map<String, Object>> actions(AuditQuery query);

    /** When this device's still-open remote session was started (for the duration of the one being closed). */
    @Select({"SELECT MAX(createdAt) FROM audit_trail WHERE action = 'remote.session.start' AND result = 'ok' " +
            "AND deviceNumbers @> ARRAY[#{number}]::text[] AND createdAt >= #{since} " +
            "AND createdAt > COALESCE((SELECT MAX(createdAt) FROM audit_trail WHERE action = 'remote.session.stop' " +
            "AND result = 'ok' AND deviceNumbers @> ARRAY[#{number}]::text[]), 0)"})
    Long lastRemoteStart(@Param("number") String number, @Param("since") long since);

    /** A customer's chain in id order, for verification. */
    @Select({"SELECT " + COLUMNS + " FROM audit_trail e WHERE e.customerId IS NOT DISTINCT FROM #{customerId,jdbcType=INTEGER} " +
            "AND e.id > #{afterId} ORDER BY e.id LIMIT #{limit}"})
    List<AuditEntry> chain(@Param("customerId") Integer customerId, @Param("afterId") long afterId, @Param("limit") int limit);

    @Select({"SELECT COUNT(*) FROM audit_trail"})
    long total();

    // --- retention -----------------------------------------------------------------------------------------------

    @Select({"SELECT retentionDays FROM audit_settings WHERE customerId = #{customerId}"})
    Integer retentionDays(@Param("customerId") int customerId);

    @Insert({"INSERT INTO audit_settings (customerId, retentionDays) VALUES (#{customerId}, #{days}) " +
            "ON CONFLICT (customerId) DO UPDATE SET retentionDays = EXCLUDED.retentionDays"})
    void setRetentionDays(@Param("customerId") int customerId, @Param("days") int days);

    @Select({"SELECT id FROM customers ORDER BY id"})
    List<Integer> customerIds();

    /** Lets this transaction delete from audit_trail (the table's trigger refuses it otherwise). */
    @Select({"SELECT set_config('dallycontrol.audit_purge', 'on', true)"})
    String allowPurge();

    /**
     * Deletes the OLDEST rows of a customer's chain: everything written before the first row that must be kept.
     * Cutting only the head of the chain (never a row in the middle, even when two requests were written in a
     * different order than they started) is what keeps the rest verifiable after a purge.
     */
    @Delete({"DELETE FROM audit_trail WHERE customerId IS NOT DISTINCT FROM #{customerId,jdbcType=INTEGER} AND id < COALESCE(" +
            "(SELECT MIN(id) FROM audit_trail WHERE customerId IS NOT DISTINCT FROM #{customerId,jdbcType=INTEGER} " +
            "AND createdAt >= #{before}), 9223372036854775807)"})
    int purge(@Param("customerId") Integer customerId, @Param("before") long before);

    // --- look-ups (who / what) -----------------------------------------------------------------------------------

    @Select({"SELECT u.id, u.login, u.name, u.customerId, u.allDevicesAvailable, r.id AS roleId, r.name AS roleName " +
            "FROM users u LEFT JOIN userRoles r ON r.id = u.userRoleId WHERE u.id = #{id}"})
    Map<String, Object> userById(@Param("id") int id);

    @Select({"SELECT u.id, u.login, u.name, u.customerId, u.allDevicesAvailable, r.id AS roleId, r.name AS roleName " +
            "FROM users u LEFT JOIN userRoles r ON r.id = u.userRoleId " +
            "WHERE lower(u.login) = lower(#{login}) OR lower(u.email) = lower(#{login}) ORDER BY u.id LIMIT 1"})
    Map<String, Object> userByLogin(@Param("login") String login);

    @Select({"SELECT u.id, u.login, u.name, u.customerId, u.allDevicesAvailable, r.id AS roleId, r.name AS roleName " +
            "FROM users u LEFT JOIN userRoles r ON r.id = u.userRoleId WHERE u.passwordResetToken = #{token} LIMIT 1"})
    Map<String, Object> userByResetToken(@Param("token") String token);

    @Select({"SELECT d.id, d.number, d.description, d.customerId, " +
            "(SELECT string_agg(dg.groupId::text, ',') FROM deviceGroups dg WHERE dg.deviceId = d.id) AS groups " +
            "FROM devices d WHERE d.number = #{number}"})
    Map<String, Object> deviceByNumber(@Param("number") String number);

    @Select({"SELECT d.id, d.number, d.description, d.customerId, " +
            "(SELECT string_agg(dg.groupId::text, ',') FROM deviceGroups dg WHERE dg.deviceId = d.id) AS groups " +
            "FROM devices d WHERE d.id = #{id}"})
    Map<String, Object> deviceById(@Param("id") int id);

    @Select({"<script>SELECT d.id, d.number, d.description, d.customerId, " +
            "(SELECT string_agg(dg.groupId::text, ',') FROM deviceGroups dg WHERE dg.deviceId = d.id) AS groups " +
            "FROM devices d WHERE d.id IN " +
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach></script>"})
    List<Map<String, Object>> devicesByIds(@Param("ids") List<Integer> ids);

    @Select({"<script>SELECT d.id, d.number, d.description, d.customerId, " +
            "(SELECT string_agg(dg.groupId::text, ',') FROM deviceGroups dg WHERE dg.deviceId = d.id) AS groups " +
            "FROM devices d WHERE d.number IN " +
            "<foreach item='n' collection='numbers' open='(' separator=',' close=')'>#{n}</foreach></script>"})
    List<Map<String, Object>> devicesByNumbers(@Param("numbers") List<String> numbers);

    /** Devices of these folders (already expanded to their subtree), capped. */
    @Select({"<script>SELECT DISTINCT d.number FROM devices d INNER JOIN deviceGroups dg ON dg.deviceId = d.id " +
            "WHERE d.customerId = #{customerId} AND dg.groupId IN " +
            "<foreach item='g' collection='groups' open='(' separator=',' close=')'>#{g}</foreach> LIMIT #{limit}</script>"})
    List<String> deviceNumbersOfGroups(@Param("customerId") int customerId, @Param("groups") List<Integer> groups,
                                       @Param("limit") int limit);

    /** A folder and every folder below it. */
    @Select({"WITH RECURSIVE sub(id, depth) AS (SELECT g.id, 0 FROM groups g WHERE g.id = #{id} AND g.customerId = #{customerId} " +
            "UNION ALL SELECT c.id, sub.depth + 1 FROM groups c INNER JOIN sub ON c.parentId = sub.id WHERE sub.depth < 32) " +
            "SELECT DISTINCT id FROM sub"})
    List<Integer> groupSubtree(@Param("customerId") int customerId, @Param("id") int id);

    /** The whole row as JSON text; {@code table} always comes from AuditTargets' fixed list, never from a request. */
    @Select({"SELECT row_to_json(t)::text FROM ${table} t WHERE t.id = #{id}"})
    String rowJson(@Param("table") String table, @Param("id") int id);

    @Select({"SELECT string_agg(p.name, ', ' ORDER BY p.name) FROM userRolePermissions rp " +
            "INNER JOIN permissions p ON p.id = rp.permissionId WHERE rp.roleId = #{roleId}"})
    String rolePermissions(@Param("roleId") int roleId);

    @Select({"SELECT string_agg(g.name, ', ' ORDER BY g.name) FROM userFolderRoots r INNER JOIN groups g ON g.id = r.groupId " +
            "WHERE r.userId = #{userId}"})
    String userFolders(@Param("userId") int userId);

    @Select({"SELECT r.groupId FROM userFolderRoots r WHERE r.userId = #{userId}"})
    List<Integer> userFolderIds(@Param("userId") int userId);

    @Select({"SELECT string_agg(g.name, ', ' ORDER BY g.name) FROM deviceGroups dg INNER JOIN groups g ON g.id = dg.groupId " +
            "WHERE dg.deviceId = #{deviceId}"})
    String deviceFolders(@Param("deviceId") int deviceId);

    @Select({"SELECT name FROM userRoles WHERE id = #{id}"})
    String roleName(@Param("id") int id);

    @Select({"SELECT name FROM groups WHERE id = #{id}"})
    String groupName(@Param("id") int id);

    @Select({"SELECT name FROM configurations WHERE id = #{id}"})
    String configurationName(@Param("id") int id);

    /** Folder users of the customer with the folders each was given: one row per (user, folder). */
    @Select({"SELECT u.id AS userId, r.groupId AS groupId FROM users u LEFT JOIN userFolderRoots r ON r.userId = u.id " +
            "WHERE u.customerId = #{customerId} AND u.allDevicesAvailable = FALSE"})
    List<Map<String, Object>> folderUsers(@Param("customerId") int customerId);
}
