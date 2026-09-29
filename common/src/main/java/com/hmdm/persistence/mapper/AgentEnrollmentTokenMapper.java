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
            "reusable, label, wifiSsid, wifiPassword, wifiSecurity) VALUES (#{token}, #{customerId}, #{used}, #{createdAt}, " +
            "#{expiresAt}, #{configurationId}, #{groupId}, #{reusable}, #{label}, #{wifiSsid}, #{wifiPassword}, #{wifiSecurity})"})
    @SelectKey(statement = "SELECT currval('agentenrollmenttoken_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(AgentEnrollmentToken token);

    @Select({"SELECT * FROM agentEnrollmentToken WHERE token = #{token}"})
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
            "t.expiresAt, t.wifiSsid, t.wifiPassword, t.wifiSecurity FROM agentEnrollmentToken t LEFT JOIN groups g ON g.id = t.groupId " +
            "WHERE t.customerId = #{customerId} AND t.reusable = true ORDER BY t.revoked, t.createdAt DESC"})
    java.util.List<com.hmdm.persistence.domain.EnrollmentCodeView> listCodes(@Param("customerId") int customerId);

    @Update({"UPDATE agentEnrollmentToken SET revoked = true WHERE id = #{id} AND customerId = #{customerId} AND reusable = true"})
    int revoke(@Param("customerId") int customerId, @Param("id") int id);

    /** Only a revoked code can be deleted (an active one is revoked first, so no phone is caught mid-enrollment). */
    @org.apache.ibatis.annotations.Delete({"DELETE FROM agentEnrollmentToken WHERE id = #{id} AND customerId = #{customerId} " +
            "AND reusable = true AND revoked = true"})
    int deleteRevoked(@Param("customerId") int customerId, @Param("id") int id);
}
