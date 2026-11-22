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

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Compliance detail for one configuration: its current desired-state revision, the sync counts, and the
 * list of devices that have NOT applied the current revision (out of compliance), so the console can show
 * "N equipos no han aplicado la última versión" and name them.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigComplianceView {
    private int configurationId;
    private String currentRevision;
    private int total;
    private int inSync;
    /** Supported agents that applied a revision different from the current one. */
    private int outOfSync;
    /** Supported agents that have never reported an applied revision yet. */
    private int neverSeen;
    /** Agents too old to apply configurations (they will never report a revision). */
    private int unsupported;
    /** The devices out of compliance (out of sync + never reported), newest-first state irrelevant. */
    private List<Device> devices;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Device {
        private String number;
        private String description;
        private String appliedRevision;
        private Long appliedAt;
        private Long lastUpdate;
        /** 'outOfSync' | 'neverSeen' | 'unsupported'. */
        private String status;
    }
}
