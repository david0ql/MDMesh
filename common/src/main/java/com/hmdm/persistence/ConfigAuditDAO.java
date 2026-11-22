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

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.ConfigAudit;
import com.hmdm.persistence.mapper.ConfigAuditMapper;

import java.util.List;

/**
 * <p>DAO for configuration (policy) change auditing &amp; versioning.</p>
 */
@Singleton
public class ConfigAuditDAO {

    private static final int DEFAULT_HISTORY_LIMIT = 200;

    private final ConfigAuditMapper mapper;

    @Inject
    public ConfigAuditDAO(ConfigAuditMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(ConfigAudit entry) {
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(System.currentTimeMillis());
        }
        mapper.insert(entry);
    }

    public List<ConfigAudit> listByConfiguration(int customerId, int configurationId) {
        return mapper.listByConfiguration(customerId, configurationId, DEFAULT_HISTORY_LIMIT);
    }
}
