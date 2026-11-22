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

package com.hmdm.rest.json;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;

/**
 * <p>Fleet-level app targeting: one device that reports a given package as installed (from its last check-in
 * inventory, {@code devices.infojson -> 'applications'}). Carries the numeric device id so the console can feed it
 * straight into the bulk command endpoint (e.g. a mass {@code app.uninstall}).</p>
 */
@ApiModel(description = "A device that has a given application installed")
public class DeviceWithApp implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("Numeric device id (as used by the bulk command endpoint)")
    private Integer id;
    @ApiModelProperty("Device number (the enrolled device identifier)")
    private String number;
    @ApiModelProperty("Device description shown in the console")
    private String description;
    @ApiModelProperty("The configuration the device runs")
    private Integer configurationId;
    @ApiModelProperty("The installed version name of the app on this device")
    private String appVersion;
    @ApiModelProperty("The installed label of the app on this device")
    private String appName;

    public DeviceWithApp() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getConfigurationId() {
        return configurationId;
    }

    public void setConfigurationId(Integer configurationId) {
        this.configurationId = configurationId;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }
}
