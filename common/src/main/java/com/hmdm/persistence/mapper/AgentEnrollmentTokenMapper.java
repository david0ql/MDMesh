/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AgentEnrollmentToken;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

/**
 * <p>MyBatis mapper for {@link AgentEnrollmentToken} (agent v1 single-use enrollment tokens).</p>
 */
public interface AgentEnrollmentTokenMapper {

    @Insert({"INSERT INTO agentEnrollmentToken (token, customerId, used, createdAt, expiresAt, configurationId, groupId, " +
            "reusable, label, wifiSsid, wifiPassword, wifiSecurity, expectedPhone, account_email) VALUES (#{token}, #{customerId}, #{used}, #{createdAt}, " +
            "#{expiresAt}, #{configurationId}, #{groupId}, #{reusable}, #{label}, #{wifiSsid}, #{wifiPassword}, #{wifiSecurity}, #{expectedPhone}, #{accountEmail})"})
    @SelectKey(statement = "SELECT currval('agentenrollmenttoken_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(AgentEnrollmentToken token);

    @Select({"SELECT *, account_email AS accountEmail FROM agentEnrollmentToken WHERE token = #{token}"})
    AgentEnrollmentToken findByToken(@Param("token") String token);

    /**
     * Atomically claim the single-use token: exactly one concurrent enroll gets rowcount 1; the
     * rest get 0 (already used, or expired between the pre-check and here). This is the guard that
     * keeps N concurrent enrolls with one token from creating N device rows.
     */
    @Update({"UPDATE agentEnrollmentToken SET used = true " +
            "WHERE id = #{id} AND used = false AND (expiresAt IS NULL OR expiresAt > #{now})"})
    int claim(@Param("id") Integer id, @Param("now") long now);

    /**
     * Release a claimed token after a SERVER-side enrollment failure (device creation rejected),
     * so a fixable condition doesn't permanently burn the token.
     */
    @Update({"UPDATE agentEnrollmentToken SET used = false WHERE id = #{id}"})
    void release(@Param("id") Integer id);

    /** A reusable code counts one more use; 0 rows = revoked or expired meanwhile. */
    @Update({"UPDATE agentEnrollmentToken SET uses = uses + 1 " +
            "WHERE id = #{id} AND reusable = true AND revoked = false AND (expiresAt IS NULL OR expiresAt > #{now})"})
    int claimReusable(@Param("id") Integer id, @Param("now") long now);

    @Update({"UPDATE agentEnrollmentToken SET uses = GREATEST(uses - 1, 0) WHERE id = #{id} AND reusable = true"})
    void releaseReusable(@Param("id") Integer id);

    @Select({"SELECT t.id, t.token AS code, t.label, t.groupId, g.name AS groupName, t.uses, t.revoked, t.createdAt, " +
            "t.expiresAt, t.wifiSsid, t.wifiPassword, t.wifiSecurity, t.wifiHidden, t.account_email AS accountEmail FROM agentEnrollmentToken t LEFT JOIN groups g ON g.id = t.groupId " +
            "WHERE t.customerId = #{customerId} AND t.reusable = true ORDER BY t.revoked, t.createdAt DESC"})
    java.util.List<com.hmdm.persistence.domain.EnrollmentCodeView> listCodes(@Param("customerId") int customerId);

    @Update({"UPDATE agentEnrollmentToken SET revoked = true WHERE id = #{id} AND customerId = #{customerId} AND reusable = true"})
    int revoke(@Param("customerId") int customerId, @Param("id") int id);

    /** Only a revoked code can be deleted (an active one is revoked first, so no phone is caught mid-enrollment). */
    @org.apache.ibatis.annotations.Delete({"DELETE FROM agentEnrollmentToken WHERE id = #{id} AND customerId = #{customerId} " +
            "AND reusable = true AND revoked = true"})
    int deleteRevoked(@Param("customerId") int customerId, @Param("id") int id);

    // --- Enrollment reliability (26.11.27-enroll-reliability): retries by the same device, and the attempts log ---

    /** The code's Wi-Fi is hidden (kept out of the shared INSERT so that statement stays as it was). */
    @Update({"UPDATE agentEnrollmentToken SET wifiHidden = #{hidden} WHERE id = #{id}"})
    void updateWifiHidden(@Param("id") Integer id, @Param("hidden") boolean hidden);

    /** Remember which device consumed a single-use token: that device may ask again if the answer never reached it. */
    @Update({"UPDATE agentEnrollmentToken SET usedAt = #{now}, claimedHardwareId = #{hardwareId}, claimedSerial = #{serial}, " +
            "claimedImei = #{imei} WHERE id = #{id} AND reusable = false"})
    void recordClaim(@Param("id") Integer id, @Param("now") long now, @Param("hardwareId") String hardwareId,
                     @Param("serial") String serial, @Param("imei") String imei);

    @Update({"UPDATE agentEnrollmentToken SET deviceNumber = #{deviceNumber} WHERE id = #{id} AND reusable = false"})
    void recordDevice(@Param("id") Integer id, @Param("deviceNumber") String deviceNumber);

    /** Tokens starting with [prefix] (the short reference the tracked agent download carries); at most two. */
    @Select({"SELECT *, account_email AS accountEmail FROM agentEnrollmentToken WHERE token LIKE #{prefix} || '%' LIMIT 2"})
    java.util.List<AgentEnrollmentToken> findByPrefix(@Param("prefix") String prefix);

    @Insert({"INSERT INTO agent_enroll_attempt (customerId, tokenId, groupId, at, lastAt, count, outcome, reason, detail, path, " +
            "manufacturer, model, androidRelease, sdkInt, agentVersion, hardwareId, serial, ip, deviceNumber, tries) VALUES (" +
            "#{customerId}, #{tokenId}, #{groupId}, #{at}, #{lastAt}, 1, #{outcome}, #{reason}, #{detail}, #{path}, " +
            "#{manufacturer}, #{model}, #{androidRelease}, #{sdkInt}, #{agentVersion}, #{hardwareId}, #{serial}, #{ip}, " +
            "#{deviceNumber}, #{tries})"})
    void insertAttempt(com.hmdm.persistence.domain.AgentEnrollAttempt attempt);

    /**
     * A phone that keeps failing the same way repeats its request every few seconds: count it on the row it already
     * has instead of flooding the log. Returns 0 when there is no such recent row.
     */
    @Update({"UPDATE agent_enroll_attempt SET count = count + 1, lastAt = #{lastAt}, " +
            "detail = COALESCE(#{detail,jdbcType=VARCHAR}, detail), tries = COALESCE(#{tries,jdbcType=INTEGER}, tries) " +
            "WHERE id = (SELECT id FROM agent_enroll_attempt WHERE outcome = #{outcome} AND reason = #{reason} " +
            "AND lastAt > #{at} - 600000 " +
            "AND tokenId IS NOT DISTINCT FROM #{tokenId,jdbcType=INTEGER} " +
            "AND ip IS NOT DISTINCT FROM #{ip,jdbcType=VARCHAR} " +
            "AND hardwareId IS NOT DISTINCT FROM #{hardwareId,jdbcType=VARCHAR} ORDER BY lastAt DESC LIMIT 1)"})
    int bumpAttempt(com.hmdm.persistence.domain.AgentEnrollAttempt attempt);

    @Select({"SELECT a.*, g.name AS groupName, t.label AS tokenLabel, COALESCE(t.reusable, false) AS reusable " +
            "FROM agent_enroll_attempt a LEFT JOIN agentEnrollmentToken t ON t.id = a.tokenId " +
            "LEFT JOIN groups g ON g.id = a.groupId " +
            "WHERE (a.customerId = #{customerId} OR (#{includeUnknown} AND a.customerId IS NULL)) " +
            "ORDER BY a.lastAt DESC LIMIT #{limit}"})
    java.util.List<com.hmdm.persistence.domain.AgentEnrollAttempt> listAttempts(@Param("customerId") int customerId,
            @Param("includeUnknown") boolean includeUnknown, @Param("limit") int limit);

    @Select({"SELECT a.*, false AS reusable FROM agent_enroll_attempt a WHERE a.tokenId = #{tokenId} ORDER BY a.lastAt DESC LIMIT 20"})
    java.util.List<com.hmdm.persistence.domain.AgentEnrollAttempt> listAttemptsOfToken(@Param("tokenId") int tokenId);

    @org.apache.ibatis.annotations.Delete({"DELETE FROM agent_enroll_attempt WHERE lastAt < #{before}"})
    int purgeAttempts(@Param("before") long before);
}
