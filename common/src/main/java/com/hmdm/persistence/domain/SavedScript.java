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
 * <p>A reusable, admin-authored device script (a short list of action verbs, one per line, see
 * {@code ScriptsResource}). Stored per customer. When {@code quickAction} is set, the console offers
 * it as a one-click delegable action on a device or a selection.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavedScript implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private int customerId;
    private String name;
    private String description;
    /** The script body: action verbs, one per line (same grammar as the ad-hoc ScriptPanel). */
    private String body;
    /** Optional JSON array describing editable parameters: [{key,label,placeholder?,default?}]. */
    private String paramsJson;
    /** A one-click "quick action" shown in the device console (e.g. "Borrar datos de Preventa"). */
    private boolean quickAction;
    /** The user id who created it (nullable; kept for traceability). */
    private Integer createdBy;
    private Long createdAt;
    private Long updatedAt;

    public SavedScript() {
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public int getCustomerId() { return customerId; }
    public void setCustomerId(int customerId) { this.customerId = customerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }

    public boolean isQuickAction() { return quickAction; }
    public void setQuickAction(boolean quickAction) { this.quickAction = quickAction; }

    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
