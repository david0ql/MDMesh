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
 * <p>A single-use enrollment token for the from-scratch Android agent v1 protocol. Minted by an
 * authenticated admin and carried into the device through the QR provisioning bundle.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEnrollmentToken implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private String token;
    private int customerId;
    private boolean used;
    private Long createdAt;
    private Long expiresAt;
    /** Configuration the enrolled device is bound to; NULL = the customer's settings default. */
    private Integer configurationId;

    /** Optional group (company) the enrolled device joins; its configuration then comes from the group. */
    private Integer groupId;

    /**
     * Reusable enrollment code (a folder's "enrollment policy"): enrolls any number of devices into its group until
     * revoked or expired, instead of being consumed by the first one. Typed on the phone or put in a QR.
     */
    private boolean reusable;
    private String label;
    private int uses;
    private boolean revoked;
    /** Wi-Fi the code's QR provisions the phone on (optional). */
    private String wifiSsid;
    private String wifiPassword;
    private String wifiSecurity;

    /**
     * Phone number the operator declares the enrolled device's SIM should carry (optional). Copied onto the device at
     * enrollment; the server later compares it against the number the agent reads from the SIM and flags a mismatch.
     */
    private String expectedPhone;

    public String getExpectedPhone() { return expectedPhone; }
    public void setExpectedPhone(String expectedPhone) { this.expectedPhone = expectedPhone; }

    /**
     * Optional email account associated with the enrollment, used to tag/identify the device. On enroll the device
     * inherits this value (device > group/folder); it is a metadata/identification field only — it does NOT create
     * any account on the phone.
     */
    private String accountEmail;

    public String getAccountEmail() { return accountEmail; }
    public void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }

    public String getWifiSsid() { return wifiSsid; }
    public void setWifiSsid(String wifiSsid) { this.wifiSsid = wifiSsid; }
    public String getWifiPassword() { return wifiPassword; }
    public void setWifiPassword(String wifiPassword) { this.wifiPassword = wifiPassword; }
    public String getWifiSecurity() { return wifiSecurity; }
    public void setWifiSecurity(String wifiSecurity) { this.wifiSecurity = wifiSecurity; }

    /** When the single-use token was consumed, and by which device (so that same device can retry with it). */
    private Long usedAt;
    private String claimedHardwareId;
    private String claimedSerial;
    private String claimedImei;
    /** Device enrolled through this single-use token. */
    private String deviceNumber;
    /** The code's Wi-Fi does not broadcast its name (PROVISIONING_WIFI_HIDDEN). */
    private boolean wifiHidden;

    public Long getUsedAt() { return usedAt; }
    public void setUsedAt(Long usedAt) { this.usedAt = usedAt; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getClaimedHardwareId() { return claimedHardwareId; }
    public void setClaimedHardwareId(String claimedHardwareId) { this.claimedHardwareId = claimedHardwareId; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getClaimedSerial() { return claimedSerial; }
    public void setClaimedSerial(String claimedSerial) { this.claimedSerial = claimedSerial; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getClaimedImei() { return claimedImei; }
    public void setClaimedImei(String claimedImei) { this.claimedImei = claimedImei; }
    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }
    public boolean isWifiHidden() { return wifiHidden; }
    public void setWifiHidden(boolean wifiHidden) { this.wifiHidden = wifiHidden; }

    public AgentEnrollmentToken() {
    }

    public boolean isReusable() { return reusable; }
    public void setReusable(boolean reusable) { this.reusable = reusable; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public int getUses() { return uses; }
    public void setUses(int uses) { this.uses = uses; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getConfigurationId() {
        return configurationId;
    }

    public void setConfigurationId(Integer configurationId) {
        this.configurationId = configurationId;
    }

    public Integer getGroupId() {
        return groupId;
    }

    public void setGroupId(Integer groupId) {
        this.groupId = groupId;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
