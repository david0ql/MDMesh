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

package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * <p>One audit/version row for a configuration (policy) change: who changed it, when, what action,
 * what changed (before/after snapshot + diff) and the resulting desired-state revision (SHA-256).</p>
 *
 * <p>Written on every create/update/delete in {@code ConfigurationResource}. Immutable once written:
 * it is the policy's change history, so a deleted configuration still keeps its trail (no FK on
 * {@code configurationId}).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private int customerId;
    /** The configuration this entry is about. No FK: a deleted policy keeps its history. */
    private Integer configurationId;
    /** Name of the configuration at the time of the change (survives a later rename/delete). */
    private String configurationName;
    /** The acting user (nullable for a system action). */
    private Integer userId;
    private String userName;
    /** 'create' | 'update' | 'delete'. */
    private String action;
    /** JSON: {@code {"diff": {field: {from, to}}, "snapshot": {full after (or last-known on delete)}}}. */
    private String changesJson;
    /** SHA-256 of the resulting desired-config document, or null when it could not be computed / on delete. */
    private String revision;
    /** Epoch milliseconds. */
    private Long createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public int getCustomerId() { return customerId; }
    public void setCustomerId(int customerId) { this.customerId = customerId; }

    public Integer getConfigurationId() { return configurationId; }
    public void setConfigurationId(Integer configurationId) { this.configurationId = configurationId; }

    public String getConfigurationName() { return configurationName; }
    public void setConfigurationName(String configurationName) { this.configurationName = configurationName; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getChangesJson() { return changesJson; }
    public void setChangesJson(String changesJson) { this.changesJson = changesJson; }

    public String getRevision() { return revision; }
    public void setRevision(String revision) { this.revision = revision; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
