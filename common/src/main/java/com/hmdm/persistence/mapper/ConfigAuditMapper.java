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

import com.hmdm.persistence.domain.ConfigAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;

import java.util.List;

/**
 * <p>MyBatis mapper for {@link ConfigAudit} (configuration/policy change audit &amp; versioning).</p>
 */
public interface ConfigAuditMapper {

    @Insert({"INSERT INTO config_audit (customerId, configurationId, configurationName, userId, userName, action, " +
            "changesJson, revision, createdAt) VALUES (#{customerId}, #{configurationId}, #{configurationName}, " +
            "#{userId}, #{userName}, #{action}, #{changesJson}, #{revision}, #{createdAt})"})
    @SelectKey(statement = "SELECT currval('config_audit_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(ConfigAudit entry);

    /** History of one configuration (customer-scoped), newest first. */
    @Select({"SELECT * FROM config_audit WHERE configurationId = #{configurationId} AND customerId = #{customerId} " +
            "ORDER BY id DESC LIMIT #{limit}"})
    List<ConfigAudit> listByConfiguration(@Param("customerId") int customerId,
                                          @Param("configurationId") int configurationId,
                                          @Param("limit") int limit);
}
